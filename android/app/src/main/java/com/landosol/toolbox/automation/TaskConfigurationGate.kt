package com.landosol.toolbox.automation

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reserves task startup before its running state is published, excluding configuration transfer. */
class TaskConfigurationGate {
    data class State(val admissions: Int = 0, val transferring: Boolean = false, val revision: Long = 0)

    private val lock = Any()
    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()

    fun refreshAvailability() = synchronized(lock) {
        current.value = current.value.copy(revision = current.value.revision + 1)
    }

    fun tryBeginTask(): Lease? = synchronized(lock) {
        if (current.value.transferring) return null
        current.value = current.value.copy(admissions = current.value.admissions + 1)
        Lease {
            synchronized(lock) {
                current.value = current.value.copy(admissions = current.value.admissions - 1)
            }
        }
    }

    fun beginTransfer(isIdle: () -> Boolean): Lease = synchronized(lock) {
        check(!current.value.transferring && current.value.admissions == 0 && isIdle()) { BUSY_MESSAGE }
        current.value = current.value.copy(transferring = true)
        Lease {
            synchronized(lock) { current.value = current.value.copy(transferring = false) }
        }
    }

    class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    companion object {
        const val BUSY_MESSAGE = "请先停止所有任务，再导入或导出配置；暂停待续不属于空闲状态"
        const val TRANSFER_MESSAGE = "正在处理配置分享码，请稍后再试"
    }
}
