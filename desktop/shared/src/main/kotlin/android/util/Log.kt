package android.util

import com.landosol.toolbox.desktop.shared.DesktopLog

object Log {
    fun d(tag: String, msg: String, tr: Throwable? = null): Int = DesktopLog.write('D', tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null): Int = DesktopLog.write('I', tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null): Int = DesktopLog.write('W', tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null): Int = DesktopLog.write('E', tag, msg, tr)
}
