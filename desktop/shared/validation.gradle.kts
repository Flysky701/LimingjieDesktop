import java.security.MessageDigest
import java.util.zip.ZipFile

val upstreamMain = rootProject.file("../android/app/src/main/java")
fun sourceFiles() = upstreamMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
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
val checkUpstreamContracts = tasks.register("checkUpstreamContracts") {
    group = "verification"
    doLast {
        file("upstream-drift.properties").readLines().filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
            val (path, expectedDigest) = line.split('=', limit = 2)
            check(digest(upstreamMain.resolve(path)) == expectedDigest) { "Adapted upstream contract changed: $path; review replacement before updating baseline" }
        }
    }
}
val checkUpstreamDrift = tasks.register("checkUpstreamDrift") {
    group = "verification"
    dependsOn("prepareUpstreamMain", checkUpstreamContracts, scanAndroidCoupling, "kspKotlin")
    doLast {
        val expected = file("upstream-coupling.txt").readLines().filter { it.isNotBlank() }.toSet()
        val actual = sourceFiles().filter { Regex("(?m)^import (android\\.|androidx\\.|com.google.mlkit\\.)").containsMatchIn(it.readText()) }.map { it.relativeTo(upstreamMain).invariantSeparatorsPath }.toSet()
        check(expected == actual) { "Android coupling changed. Added=${actual - expected}; removed=${expected - actual}" }
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
tasks.named("check") {
    dependsOn(checkUpstreamContracts)
    if (providers.gradleProperty("full").isPresent) dependsOn(checkShimCollisions, checkUpstreamDrift)
}
