package com.landosol.toolbox.desktop.shared
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*

object AtomicFiles {
    /** FB-04: same-directory durable write, at most five move attempts on file-system contention. */
    fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.toAbsolutePath().parent)
        val temporary = Files.createTempFile(path.toAbsolutePath().parent, path.fileName.toString() + ".tmp-", "")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            move(temporary, path)
        } finally { Files.deleteIfExists(temporary) }
    }

    /** Atomic replacement also supports large staged databases without loading them into memory. */
    fun move(source: Path, target: Path) {
        require(source.toAbsolutePath().parent == target.toAbsolutePath().parent) { "Atomic replacement requires the same directory" }
        for (attempt in 0..4) {
            try { Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING); return }
            catch (failure: java.nio.file.AtomicMoveNotSupportedException) { throw failure }
            catch (failure: java.nio.file.FileSystemException) {
                if (attempt == 4) throw failure
                Thread.sleep(100)
            }
        }
    }
}
