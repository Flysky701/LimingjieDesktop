package com.landosol.toolbox.desktop.app
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MAX_NORMAL_ENTRIES = 2000

enum class UserLogKind(val label: String) { KEY("关键"), NORMAL("普通"), WARNING("警告"), ERROR("错误") }
data class UserLogLine(val time: String, val text: String, val error: Boolean, val kind: UserLogKind = if(error) UserLogKind.ERROR else UserLogKind.NORMAL, val repeats: Int = 1)
class UserLog {
    private val entries = MutableStateFlow<List<UserLogLine>>(emptyList())
    private var normalEntries = 0
    val lines = entries.asStateFlow()
    @Synchronized fun add(text: String, error: Boolean = false, kind: UserLogKind = if(error) UserLogKind.ERROR else UserLogKind.NORMAL) {
        if (text.isBlank()) return
        val safe = com.landosol.toolbox.desktop.shared.DesktopLog.redact(text)
        val current = entries.value
        val previous = current.lastOrNull()
        if (previous?.text == safe && previous.kind == kind) {
            entries.value = current.dropLast(1) + previous.copy(repeats = previous.repeats + 1)
            return
        }
        val line = UserLogLine(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), safe, error || kind == UserLogKind.ERROR, kind)
        if (kind == UserLogKind.NORMAL && normalEntries == MAX_NORMAL_ENTRIES) {
            val retained = current.toMutableList()
            retained.removeAt(retained.indexOfFirst { it.kind == UserLogKind.NORMAL })
            retained.add(line)
            entries.value = retained
        } else {
            if (kind == UserLogKind.NORMAL) normalEntries++
            entries.value = current + line
        }
    }
    @Synchronized fun clear() {
        normalEntries = 0
        entries.value = emptyList()
    }
    fun text(): String = entries.value.joinToString("\n") { "${it.time} [${it.kind.label}] ${it.text}${if(it.repeats>1) " ×${it.repeats}" else ""}" }
}
