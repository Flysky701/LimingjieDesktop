package android.util

open class LruCache<K : Any, V : Any>(private val maxSize: Int) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    private var used = 0
    init { require(maxSize > 0) }
    protected open fun sizeOf(key: K, value: V): Int = 1
    @Synchronized fun get(key: K): V? = entries[key]
    @Synchronized fun put(key: K, value: V): V? {
        val size = sizeOf(key, value).also { require(it >= 0) }
        val old = entries.put(key, value)
        used += size - (old?.let { sizeOf(key, it) } ?: 0)
        val iterator = entries.entries.iterator()
        while (used > maxSize && iterator.hasNext()) {
            val entry = iterator.next()
            used -= sizeOf(entry.key, entry.value)
            iterator.remove()
        }
        return old
    }
    @Synchronized fun evictAll() { entries.clear(); used = 0 }
}
