package com.landosol.toolbox.desktop.app.adapters.storage

import android.content.Context
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.landosol.toolbox.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

object DatabaseRecovery {
    /** Opens once, preserving db/wal/shm only on SQLite's actual corruption/not-a-database errors. */
    suspend fun open(context: Context, onRecovered: (String) -> Unit = {}): AppDatabase = withContext(Dispatchers.IO) {
        val directory = context.filesDir.parentFile.toPath().resolve("databases")
        val databaseFile = directory.resolve(AppDatabase.FILE_NAME)
        // Room's failed first-open can retain its native connection on Windows. Inspect through an
        // explicitly scoped connection before Room owns the file, so a corrupt file remains movable.
        if (Files.isRegularFile(databaseFile)) {
            try {
                BundledSQLiteDriver().open(databaseFile.toString(), SQLITE_OPEN_READONLY).use { connection ->
                    connection.prepare("PRAGMA quick_check").use { query ->
                        if (query.step() && query.getText(0) != "ok") throw SQLiteException("SQLITE_CORRUPT: quick_check failed")
                    }
                }
            } catch (failure: Exception) {
                if (!isCorruption(failure)) throw failure
                preserveCorruptDatabase(directory, onRecovered)
            }
        }
        val first = AppDatabase.create(context)
        try { first.accountDao().count(); first }
        catch (failure: Exception) {
            first.close()
            if (!isCorruption(failure)) throw failure
            preserveCorruptDatabase(directory, onRecovered)
            AppDatabase.create(context).also { db ->
                try { db.accountDao().count() } catch (openFailure: Exception) { db.close(); throw openFailure }
            }
        }
    }

    private fun preserveCorruptDatabase(directory: Path, onRecovered: (String) -> Unit) {
            val backup = directory.resolve("corrupt-${System.currentTimeMillis()}")
            Files.createDirectory(backup)
            val moved = mutableListOf<Pair<Path, Path>>()
            try {
                listOf(AppDatabase.FILE_NAME, "${AppDatabase.FILE_NAME}-wal", "${AppDatabase.FILE_NAME}-shm").forEach { name ->
                    val original = directory.resolve(name)
                    if (Files.exists(original)) { val saved = backup.resolve(name); Files.move(original, saved); moved.add(original to saved) }
                }
            } catch (backupFailure: Exception) {
                moved.asReversed().forEach { (original, saved) ->
                    try { Files.move(saved, original) } catch (restoreFailure: Exception) { backupFailure.addSuppressed(restoreFailure) }
                }
                throw IllegalStateException("本地账号库损坏，备份未完成，已停止打开：$backup", backupFailure)
            }
            onRecovered("本地账号库已损坏并已备份到 $backup，账号需要重新添加")
    }

    private fun isCorruption(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.any {
        it is SQLiteException && Regex("SQLITE_CORRUPT|SQLITE_NOTADB|database disk image is malformed|file is not a database", RegexOption.IGNORE_CASE).containsMatchIn(it.message.orEmpty())
    }
}
