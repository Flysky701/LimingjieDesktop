package com.landosol.toolbox.gamedata

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ResourcePackActivator(
    context: Context,
    private val validator: ResourcePackValidator = ResourcePackValidator(),
) {
    private val root = File(context.applicationContext.filesDir, "game-data")

    fun activeManifest(): File = File(root, "active.json")

    fun activate(source: File, manifest: ResourcePackManifest): ResourcePackValidation {
        val validation = validator.validate(source, manifest)
        if (validation !is ResourcePackValidation.Valid) return validation
        val staging = File(root, "staging/${manifest.packId}-${manifest.version}")
        val versioned = File(root, "versions/${manifest.packId}/${manifest.version}")
        staging.deleteRecursively()
        staging.mkdirs()
        copyDirectory(source, staging)
        val stagedValidation = validator.validate(staging, manifest)
        if (stagedValidation !is ResourcePackValidation.Valid) {
            staging.deleteRecursively()
            return stagedValidation
        }
        versioned.parentFile?.mkdirs()
        versioned.deleteRecursively()
        Files.move(staging.toPath(), versioned.toPath(), StandardCopyOption.ATOMIC_MOVE)
        val active = File(root, "active.json")
        val tempActive = File(root, "active.json.tmp")
        tempActive.parentFile?.mkdirs()
        tempActive.writeText("{\"packId\":\"${manifest.packId}\",\"version\":\"${manifest.version}\"}")
        Files.move(
            tempActive.toPath(),
            active.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        return ResourcePackValidation.Valid
    }

    private fun copyDirectory(source: File, target: File) {
        source.walkTopDown().filter(File::isFile).forEach { file ->
            val relative = file.relativeTo(source)
            val destination = File(target, relative.path)
            destination.parentFile?.mkdirs()
            file.copyTo(destination, overwrite = true)
        }
    }
}
