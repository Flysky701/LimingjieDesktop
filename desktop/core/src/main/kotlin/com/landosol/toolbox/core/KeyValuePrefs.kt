package com.landosol.toolbox.core

import com.landosol.toolbox.desktop.shared.AtomicFiles
import com.landosol.toolbox.core.logging.AppLogger
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

interface KeyValuePrefs {
    val all: Map<String, *>
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}

internal class JsonPreferences(private val file: File, onRecovered: (File) -> Unit) : KeyValuePrefs {
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
    override val all: Map<String, *>
        get() = synchronized(this) { values.keys().asSequence().associateWith { values.getJSONObject(it).get("value") } }
    override fun getString(key: String, defValue: String?): String? = value(key, "string") as String? ?: defValue
    override fun getInt(key: String, defValue: Int): Int = (value(key, "int") as Number?)?.toInt() ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (value(key, "long") as Number?)?.toLong() ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (value(key, "float") as Number?)?.toFloat() ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = value(key, "boolean") as Boolean? ?: defValue
    @Synchronized override fun contains(key: String): Boolean = values.has(key)
    override fun edit(): KeyValuePrefs.Editor = Editor()
    private inner class Editor : KeyValuePrefs.Editor {
        private val changes = LinkedHashMap<String, JSONObject?>()
        private var clear = false
        private fun put(key: String, type: String, value: Any?): KeyValuePrefs.Editor = apply {
            changes[key] = value?.let { JSONObject().put("type", type).put("value", it) }
        }
        override fun putString(key: String, value: String?) = put(key, "string", value)
        override fun putInt(key: String, value: Int) = put(key, "int", value)
        override fun putLong(key: String, value: Long) = put(key, "long", value)
        override fun putFloat(key: String, value: Float) = put(key, "float", value)
        override fun putBoolean(key: String, value: Boolean) = put(key, "boolean", value)
        override fun remove(key: String): KeyValuePrefs.Editor = apply { changes[key] = null }
        override fun clear(): KeyValuePrefs.Editor = apply { clear = true }
        override fun commit(): Boolean = synchronized(this@JsonPreferences) {
            val next = if (clear) JSONObject() else JSONObject(values.toString())
            changes.forEach { (key, entry) -> if (entry == null) next.remove(key) else next.put(key, entry) }
            // Legacy callers see the edited values even when persistence fails.
            values = next
            try { AtomicFiles.write(file.toPath(), next.toString().toByteArray(Charsets.UTF_8)); true }
            catch (failure: java.io.IOException) { AppLogger.w("Preferences", "设置写入失败：${file.name}", failure); false }
        }
        override fun apply() { commit() }
    }
}
