package com.landosol.toolbox.core

import java.io.FileNotFoundException
import java.io.InputStream

class AppAssets(private val loader: ClassLoader = AppAssets::class.java.classLoader) {
    fun open(path: String): InputStream =
        loader.getResourceAsStream("assets/$path") ?: throw FileNotFoundException(path)

    /** Asset paths relative to the classpath assets directory, in manifest order. */
    fun paths(): List<String> {
        val manifest = "desktop/assets-manifest.txt"
        val stream = loader.getResourceAsStream(manifest) ?: throw FileNotFoundException(manifest)
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { it.removePrefix("assets/") }.toList()
        }
    }
}
