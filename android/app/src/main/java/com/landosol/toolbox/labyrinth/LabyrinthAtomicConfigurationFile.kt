package com.landosol.toolbox.labyrinth

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Uses a same-directory atomic replacement; unsupported filesystems fail without a copy fallback. */
internal class LabyrinthAtomicConfigurationFile(private val target: File) {
    fun read(): String? = if (target.exists()) target.readText(Charsets.UTF_8) else null

    fun write(text: String) {
        val staged = File(target.parentFile, "${target.name}.pending")
        try {
            FileOutputStream(staged).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            staged.delete()
            throw IllegalStateException("配置保存失败，原配置未变；请检查存储空间", failure)
        }
    }
}
