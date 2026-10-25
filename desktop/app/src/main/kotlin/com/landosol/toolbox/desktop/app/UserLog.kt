package com.landosol.toolbox.desktop.app
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UserLogKind(val label: String) { KEY("关键"), NORMAL("普通"), WARNING("警告"), ERROR("错误") }
data class UserLogLine(val time: String, val text: String, val error: Boolean, val kind: UserLogKind = if(error) UserLogKind.ERROR else UserLogKind.NORMAL, val repeats: Int = 1)
class UserLog {
    private val entries = MutableStateFlow<List<UserLogLine>>(emptyList())
    val lines = entries.asStateFlow()
    @Synchronized fun add(text: String, error: Boolean = false, kind: UserLogKind = if(error) UserLogKind.ERROR else UserLogKind.NORMAL) {
        if (text.isBlank()) return
        val safe = com.landosol.toolbox.desktop.shared.DesktopLog.redact(text)
        val previous = entries.value.lastOrNull()
        if(previous?.text == safe && previous.kind == kind) { entries.value = entries.value.dropLast(1) + previous.copy(repeats=previous.repeats+1); return }
        val line = UserLogLine(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), safe, error || kind == UserLogKind.ERROR, kind)
        entries.value = (entries.value + line).takeLast(2000)
    }
    fun clear() { entries.value = emptyList() }
    fun text(): String = entries.value.joinToString("\n") { "${it.time} [${it.kind.label}] ${it.text}${if(it.repeats>1) " ×${it.repeats}" else ""}" }
}
