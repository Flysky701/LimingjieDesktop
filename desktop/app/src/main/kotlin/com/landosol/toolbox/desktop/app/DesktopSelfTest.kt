package com.landosol.toolbox.desktop.app

import android.graphics.BitmapFactory
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.data.local.AccountEntity
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.desktop.ocr.*
import com.landosol.toolbox.desktop.platform.*
import com.landosol.toolbox.desktop.shared.DesktopContext
import java.net.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Runs only local fixture checks and an optional TLS handshake, never an account/game request. */
object DesktopSelfTest {
    private class Skipped(message: String) : Exception(message)
    fun run(args: Array<String>): Int = runBlocking {
        val results = JSONArray()
        var failures = 0
        suspend fun checkItem(name: String, action: suspend () -> String) {
            val start = System.nanoTime()
            val result = JSONObject().put("name", name)
            try { result.put("status", "passed").put("detail", action()) }
            catch (skip: Skipped) { result.put("status", "skipped").put("detail", skip.message) }
            catch (failure: LinkageError) { failures++; result.put("status", "failed").put("detail", "Native runtime could not load: ${failure.message}") }
            catch (failure: Exception) { failures++; result.put("status", "failed").put("detail", failure.message ?: failure.javaClass.name) }
            result.put("millis", (System.nanoTime() - start) / 1_000_000)
            results.put(result)
        }
        val directory = DataDirectories.resolve(args)
        DataDirectories.ensureWritable(directory)
        val temporary = Files.createTempDirectory(directory, "self-test-").toAbsolutePath().normalize()
        try {
            checkItem("data-dir") {
                DataDirectories.ensureWritable(directory)
                "Writable data directory: $directory"
            }
            checkItem("fonts") {
                val font = trayMenuFont()
                check(font.canDisplayUpTo(TRAY_MENU_GLYPHS) == -1) { "Tray menu font cannot display Chinese: ${font.family}" }
                "Tray menu CJK font: ${font.family} ${font.size2D}pt"
            }
            checkItem("resources") {
                val names = resource("desktop/assets-manifest.txt").toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
                require(names.isNotEmpty()) { "Empty asset manifest" }
                names.forEach { name -> require(javaClass.classLoader.getResource(name) != null) { "Missing asset: $name" } }
                val first = checkNotNull(javaClass.classLoader.getResource(names.first()))
                val packagedCount = if (first.protocol == "jar") {
                    val jar = (first.openConnection() as JarURLConnection).apply { useCaches = false }
                    jar.jarFile.use { file -> file.entries().asSequence().count { !it.isDirectory && it.name.startsWith("assets/") } }
                } else {
                    var root = Path.of(first.toURI())
                    repeat(names.first().split('/').size) { root = root.parent }
                    Files.walk(root.resolve("assets")).use { paths -> paths.filter(Files::isRegularFile).count().toInt() }
                }
                check(packagedCount == names.size) { "Asset count mismatch: $packagedCount versus ${names.size}" }
                val sample = java.util.Properties().apply { resource("desktop/sample.properties").inputStream().reader(Charsets.UTF_8).use { load(it) } }
                val png = sample.getProperty("path")
                val decoded = BitmapFactory.decodeStream(resource(png).inputStream()) ?: error("Cannot decode packaged PNG")
                try { require(decoded.width == sample.getProperty("width").toInt() && decoded.height == sample.getProperty("height").toInt()); "${names.size} assets; $png ${decoded.width}x${decoded.height}" }
                finally { decoded.recycle() }
            }
            checkItem("ocr") {
                val bitmap = BitmapFactory.decodeStream(resource("ocr/self-test.png").inputStream()) ?: error("Cannot decode OCR sample")
                val expected = resource("ocr/self-test.txt").toString(Charsets.UTF_8).trim()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val engine = OnnxOcrEngine()
                try {
                    engine.warmUp()
                    val ready = withTimeout(60_000) { engine.status.first { it is OcrEngineStatus.Ready || it is OcrEngineStatus.Failed } }
                    check(ready is OcrEngineStatus.Ready) { "OCR initialization: $ready" }
                    val text = CompletableDeferred<String?>()
                    engine.submit(bitmap.width, bitmap.height, pixels) { text.complete(it) }
                    val actual = withTimeout(30_000) { text.await() }?.trim()
                    check(actual == expected) { "OCR sample mismatch: expected '$expected', got '$actual'" }
                    "Models loaded; sample recognized as $expected"
                } finally { engine.close(); bitmap.recycle() }
            }
            checkItem("sqlite-bundled") {
                BundledSQLiteDriver().open(temporary.resolve("sqlite.db").toString()).use { connection ->
                    connection.prepare("select sqlite_version()").use { query -> check(query.step()); query.getText(0) }
                }
            }
            checkItem("room") {
                val context = DesktopContext(temporary.resolve("room").toFile())
                val databaseFile = context.dataDir.toPath().resolve("databases/${AppDatabase.FILE_NAME}")
                Files.createDirectories(databaseFile.parent)
                val schema = JSONObject(resource("desktop/schema-v1.json").toString(Charsets.UTF_8)).getJSONObject("database")
                BundledSQLiteDriver().open(databaseFile.toString()).use { connection ->
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        connection.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.getJSONArray("indices")
                        for (j in 0 until indices.length()) connection.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val queries = schema.getJSONArray("setupQueries")
                    for (i in 0 until queries.length()) connection.execSQL(queries.getString(i))
                    connection.execSQL("PRAGMA user_version = 1")
                    connection.execSQL("INSERT INTO accounts(alias,server_id,game_uid,credential_key,is_selected,created_at,updated_at) VALUES('migration-fixture','bilibili',NULL,'fixture',0,1,1)")
                }
                val database = AppDatabase.create(context)
                try {
                    check(database.accountDao().getFirst()?.alias == "migration-fixture")
                    val id = database.accountDao().insert(AccountEntity(alias = "self-test", serverId = "bilibili", gameUid = null, credentialKey = "fixture-only", isSelected = false, createdAt = 1, updatedAt = 1))
                    check(database.accountDao().getById(id)?.alias == "self-test")
                    check(database.accountDao().count() == 2)
                    "Schema 1 → 4; migration preserved data; insert/read passed"
                } finally { database.close() }
            }
            checkItem("dpapi") {
                if (!WindowsDpapi.available) throw Skipped("Windows only")
                val sample = "desktop-self-test-fixture".toByteArray()
                val entropy = "desktop-self-test-entropy".toByteArray()
                check(WindowsDpapi.unprotect(WindowsDpapi.protect(sample, entropy), entropy).contentEquals(sample))
                "Current Windows user encrypt/decrypt round trip passed"
            }
            checkItem("jna-kernel32") {
                if (!WindowsDpapi.available) throw Skipped("Windows only")
                PowerSaveController.verifyNativeSymbol()
                "SetThreadExecutionState resolved without invoking it"
            }
            checkItem("maatouch") {
                val bytes = resource("device/maatouch")
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(actual == "4ea8590cd0349ce900f39ab16ef3751dad2356286b465b4293f80f9858c995d0") { "MaaTouch resource checksum mismatch" }
                "MaaTouch v1.1.0 verified"
            }
            checkItem("tls") {
                val host = "line1-sdk-center-login-sh.biligame.net"
                try {
                    (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { socket ->
                        socket.soTimeout = 8_000
                        socket.sslParameters = SSLParameters().apply { endpointIdentificationAlgorithm = "HTTPS" }
                        socket.connect(InetSocketAddress(host, 443), 8_000)
                        socket.startHandshake()
                        "${socket.session.protocol}; handshake only, no HTTP request"
                    }
                } catch (failure: Exception) {
                    if (failure is UnknownHostException || failure is SocketTimeoutException || failure is ConnectException || failure is NoRouteToHostException) throw Skipped("Network unavailable: ${failure.javaClass.simpleName}")
                    throw failure
                }
            }
        } finally {
            check(temporary.startsWith(directory.toAbsolutePath().normalize()) && temporary.fileName.toString().startsWith("self-test-"))
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
        }
        val index = args.indexOf("--report")
        val report = if (index >= 0) Path.of(args.getOrNull(index + 1) ?: error("--report requires a path")).toAbsolutePath() else directory.resolve("self-test-report.json")
        Files.createDirectories(report.parent)
        Files.writeString(report, JSONObject().put("version", AppVersion.name).put("timestamp", java.time.Instant.now().toString()).put("passed", failures == 0).put("checks", results).toString(2))
        if (failures == 0) 0 else 1
    }
    private fun resource(name: String): ByteArray = checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "Missing resource $name" }.use { it.readBytes() }
}
