package com.landosol.toolbox.desktop.app
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserLogLine(val time: String, val text: String, val error: Boolean)
class UserLog {
    private val entries = MutableStateFlow<List<UserLogLine>>(emptyList())
    val lines = entries.asStateFlow()
    @Synchronized fun add(text: String, error: Boolean = false) {
        if (text.isBlank() || entries.value.lastOrNull()?.text == text) return
        val line = UserLogLine(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), com.landosol.toolbox.desktop.shared.DesktopLog.redact(text), error)
        entries.value = (entries.value + line).takeLast(2000)
    }
    fun clear() { entries.value = emptyList() }
    fun text(): String = entries.value.joinToString("\n") { "${it.time} ${it.text}" }
}
