package com.landosol.toolbox.desktop.shared

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

class DesktopContext(val dataDir: File, private val onRecovered: (File) -> Unit = {}) : Context() {
    override val assets = AssetManager()
    override val applicationContext: Context get() = this
    override val filesDir = File(dataDir, "files").apply { mkdirs() }
    override val cacheDir = File(dataDir, "cache").apply { mkdirs() }
    private val preferences = ConcurrentHashMap<String, SharedPreferences>()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        require(mode == MODE_PRIVATE && name.isNotBlank() && '/' !in name && '\\' !in name)
        return preferences.computeIfAbsent(name) { JsonPreferences(File(dataDir, "shared_prefs/$name.json"), onRecovered) }
    }
}
private class JsonPreferences(private val file: File, onRecovered: (File) -> Unit) : SharedPreferences {
    private var values = if (!file.exists()) JSONObject() else try { JSONObject(file.readText(Charsets.UTF_8)) }
    catch (_: org.json.JSONException) {
        val backup = File(file.parentFile, "${file.nameWithoutExtension}.corrupt-${System.currentTimeMillis()}.json")
        Files.move(file.toPath(), backup.toPath())
        onRecovered(backup)
        JSONObject()
    }
    @Synchronized private fun value(key: String, type: String): Any? {
        val entry = values.optJSONObject(key) ?: return null
        if (entry.getString("type") != type) throw ClassCastException("Preference type mismatch: $key")
        return entry.get("value")
    }
    override fun getString(key: String, defValue: String?): String? = value(key, "string") as String? ?: defValue
    override fun getInt(key: String, defValue: Int): Int = (value(key, "int") as Number?)?.toInt() ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (value(key, "long") as Number?)?.toLong() ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (value(key, "float") as Number?)?.toFloat() ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = value(key, "boolean") as Boolean? ?: defValue
    @Synchronized override fun contains(key: String): Boolean = values.has(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, JSONObject?>()
        private var clear = false
        private fun put(key: String, type: String, value: Any?): SharedPreferences.Editor = apply {
            changes[key] = value?.let { JSONObject().put("type", type).put("value", it) }
        }
        override fun putString(key: String, value: String?) = put(key, "string", value)
        override fun putInt(key: String, value: Int) = put(key, "int", value)
        override fun putLong(key: String, value: Long) = put(key, "long", value)
        override fun putFloat(key: String, value: Float) = put(key, "float", value)
        override fun putBoolean(key: String, value: Boolean) = put(key, "boolean", value)
        override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean = synchronized(this@JsonPreferences) {
            val next = if (clear) JSONObject() else JSONObject(values.toString())
            changes.forEach { (key, entry) -> if (entry == null) next.remove(key) else next.put(key, entry) }
            // Android updates the in-memory map even when persistence fails.
            values = next
            try { AtomicFiles.write(file.toPath(), next.toString().toByteArray(Charsets.UTF_8)); true }
            catch (failure: java.io.IOException) { DesktopLog.write('W', "Preferences", "设置写入失败：${file.name}", failure); false }
        }
        override fun apply() { commit() }
    }
}
