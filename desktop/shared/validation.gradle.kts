import java.security.MessageDigest
import java.util.zip.ZipFile

val upstreamMain = rootProject.file("../android/app/src/main/java")
fun sourceFiles() = upstreamMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
fun normalizeApi(value: String) = value.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
    .replace(Regex("//[^\\n]*"), "").replace(Regex("\\b(private|public|val|var)\\s+"), "")
    .replace(Regex("\\s+"), "").replace(",)", ")").trimEnd(',')
fun functions(source: String): Set<String> {
    var text = source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "")
        .replace(Regex("@Synchronized\\s*"), "")
    val privateType = Regex("(?m)^\\s*private (?:class|object) [^{]+\\{")
    while (true) {
        val declaration = privateType.find(text) ?: break
        var depth = 1
        var end = declaration.range.last + 1
        while (end < text.length && depth > 0) { if (text[end] == '{') depth++; if (text[end] == '}') depth--; end++ }
        check(depth == 0) { "Unbalanced private type" }
        text = text.removeRange(declaration.range.first, end)
    }
    val found = mutableSetOf<String>()
    val start = Regex("(?m)^\\s*((?:(?:public|internal|private|protected|override|suspend|inline|operator)\\s+)*)fun\\s+([a-zA-Z0-9_]+)\\s*\\(")
    for (match in start.findAll(text)) {
        if (match.groupValues[1].contains("private") || match.groupValues[1].contains("protected") || match.groupValues[2] == "migrate") continue
        var depth = 1
        var end = match.range.last + 1
        while (end < text.length && depth > 0) { if (text[end] == '(') depth++; if (text[end] == ')') depth--; end++ }
        check(depth == 0) { "Unbalanced API declaration" }
        val returnType = Regex("^\\s*:\\s*([^={\\n]+)").find(text.substring(end))?.groupValues?.get(1).orEmpty()
        found += (if (match.groupValues[1].contains("suspend")) "suspend" else "") + normalizeApi(text.substring(text.indexOf("fun", match.range.first), end) + returnType)
    }
    return found
}
val checkSubstituteSignatures = tasks.register("checkSubstituteSignatures") {
    group = "verification"
    doLast {
        val paths = file("upstream-excludes.txt").readLines().filter { "# S |" in it }.map { it.substringBefore('#').trim() }
        for (path in paths) {
            val original = upstreamMain.resolve(path).readText()
            val replacement = file("src/main/kotlin/$path").readText()
            val missing = functions(original) - functions(replacement)
            check(missing.isEmpty()) { "Substitute API drift in $path: $missing" }
            val name = File(path).nameWithoutExtension
            val constructor = Regex("class\\s+$name\\s*\\((.*?)\\)\\s*(?::|\\{)", RegexOption.DOT_MATCHES_ALL)
            val expected = constructor.find(original)?.groupValues?.get(1)
            if (expected != null) check(normalizeApi(expected) == normalizeApi(constructor.find(replacement)?.groupValues?.get(1).orEmpty())) { "Constructor drift: $path" }
        }
        logger.lifecycle("Same-name substitute declarations match upstream (JVM migration parameter intentionally adapted).")
    }
}
val checkShimCollisions = tasks.register("checkShimCollisions") {
    group = "verification"
    dependsOn("compileKotlin")
    doLast {
        val classes = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        val shimNames = classes.walkTopDown().filter { it.isFile && it.extension == "class" }.map { it.relativeTo(classes).invariantSeparatorsPath }
            .filter { it.startsWith("android/") || it.startsWith("androidx/") }.toSet()
        configurations.getByName("runtimeClasspath").files.filter { it.extension == "jar" }.forEach { jar ->
            ZipFile(jar).use { zip -> check(shimNames.none { zip.getEntry(it) != null }) { "Shim class collision: $jar" } }
        }
        logger.lifecycle("${shimNames.size} framework shim classes have no runtime JAR collision.")
    }
}
val scanAndroidCoupling = tasks.register("scanAndroidCoupling") {
    group = "verification"
    val report = layout.buildDirectory.file("reports/android-coupling.txt")
    outputs.file(report)
    inputs.dir(upstreamMain)
    doLast {
        val files = sourceFiles()
        val exclusions = file("upstream-excludes.txt").readLines().map { it.substringBefore('#').trim() }.filter { it.isNotBlank() }.toSet()
        val excludedNames = files.filter { it.relativeTo(upstreamMain).invariantSeparatorsPath in exclusions }
            .flatMap { Regex("(?m)^(?:(?:internal|public|private|data|sealed|enum) )*(?:class|object|interface|fun|const val) ([A-Za-z0-9_]+)").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }.toSet()
        val rows = files.flatMap { file ->
            val path = file.relativeTo(upstreamMain).invariantSeparatorsPath
            file.readLines().mapIndexedNotNull { i, line ->
                val category = when {
                    Regex("^import (android\\.|androidx\\.|com.google.mlkit\\.)").containsMatchIn(line) -> "IMPORT"
                    !line.trimStart().startsWith("import ") && Regex("\\b(android|androidx|com.google.mlkit)\\.").containsMatchIn(line) -> "FQN"
                    path !in exclusions && excludedNames.any { Regex("\\b$it\\b").containsMatchIn(line) } -> "TRANSITIVE"
                    Regex("\\b(org.json|BuildConfig|R)\\.").containsMatchIn(line) -> "GENERATED/JSON"
                    else -> null
                }
                category?.let { "$category $path:${i + 1} ${line.trim()}" }
            }
        }
        report.get().asFile.apply { parentFile.mkdirs(); writeText(rows.joinToString("\n") + "\n") }
    }
}
val checkUpstreamDrift = tasks.register("checkUpstreamDrift") {
    group = "verification"
    dependsOn("prepareUpstreamMain", checkSubstituteSignatures, scanAndroidCoupling, "kspKotlin")
    doLast {
        val expected = file("upstream-coupling.txt").readLines().filter { it.isNotBlank() }.toSet()
        val actual = sourceFiles().filter { Regex("(?m)^import (android\\.|androidx\\.|com.google.mlkit\\.)").containsMatchIn(it.readText()) }.map { it.relativeTo(upstreamMain).invariantSeparatorsPath }.toSet()
        check(expected == actual) { "Android coupling changed. Added=${actual - expected}; removed=${expected - actual}" }
        file("upstream-drift.properties").readLines().filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
            val (path, expectedDigest) = line.split('=', limit = 2)
            check(digest(upstreamMain.resolve(path)) == expectedDigest) { "Adapted upstream contract changed: $path; review replacement before updating baseline" }
        }
        val original = rootProject.file("../android/app/schemas/com.landosol.toolbox.data.local.AppDatabase/4.json").readText()
        val desktop = layout.buildDirectory.file("schemas/com.landosol.toolbox.data.local.AppDatabase/4.json").get().asFile.readText()
        val identity = Regex("\"identityHash\"\\s*:\\s*\"([^\"]+)\"")
        check(identity.find(original)!!.groupValues[1] == identity.find(desktop)!!.groupValues[1]) { "Room schema identity changed" }
    }
}
fun androidSnapshot(): String {
    val root = rootProject.file("..").canonicalFile
    val process = ProcessBuilder("git", "status", "--porcelain=v1", "-z", "--untracked-files=all", "--", "android/").directory(root).start()
    val status = process.inputStream.readBytes().toString(Charsets.UTF_8)
    check(process.waitFor() == 0) { "Cannot snapshot upstream working tree" }
    return status + status.split('\u0000').filter { it.length > 3 }.joinToString("\n") { entry ->
        val path = entry.substring(3)
        val target = root.resolve(path)
        if (target.isFile) "$path=${digest(target)}" else "$path=absent"
    }
}
var snapshotBeforeTests: String? = null
val verifyUpstreamUnchanged = tasks.register("verifyUpstreamUnchanged") {
    doLast { snapshotBeforeTests?.let { check(it == androidSnapshot()) { "Tests modified android/ source or fixtures" } } }
}
tasks.named<Test>("test") {
    doFirst { snapshotBeforeTests = androidSnapshot() }
    finalizedBy(verifyUpstreamUnchanged)
}
tasks.named("check") { dependsOn(checkShimCollisions, checkSubstituteSignatures, checkUpstreamDrift) }
