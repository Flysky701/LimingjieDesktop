package com.landosol.toolbox.core

import com.landosol.toolbox.desktop.shared.DesktopCharacterIconPack
import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import com.landosol.toolbox.labyrinth.vision.LabyrinthCompiledIconSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AppContext(val dataDir: File, private val onRecovered: (File) -> Unit = {}) : LabyrinthCompiledIconSource {
    override fun characterIconMatcher(attributes: Map<String, LabyrinthCharacterAttribute?>) =
        DesktopCharacterIconPack.load(attributes)

    val assets = AppAssets()
    val filesDir = File(dataDir, "files").apply { mkdirs() }
    val cacheDir = File(dataDir, "cache").apply { mkdirs() }
    private val preferences = ConcurrentHashMap<String, KeyValuePrefs>()
    fun preferences(name: String): KeyValuePrefs {
        require(name.isNotBlank() && '/' !in name && '\\' !in name)
        return preferences.computeIfAbsent(name) { JsonPreferences(File(dataDir, "shared_prefs/$name.json"), onRecovered) }
    }
}
