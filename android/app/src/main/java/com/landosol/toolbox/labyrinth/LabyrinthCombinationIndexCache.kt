package com.landosol.toolbox.labyrinth

/**
 * Only lexicographic integer indices are retained: never roles, positions, contexts or scores.
 * The array is private and the table exposes no mutable view. A caller can keep reading an
 * evicted table while another search changes dimensions. Eligible misses are filled under the
 * same lock as LRU publication, preventing duplicate resident tables on simultaneous misses.
 */
internal class LabyrinthCombinationIndexCache(
    private val maxResidentBytes: Long = 2L * 1024 * 1024,
    private val maxEntries: Int = 8,
) {
    private data class Key(val n: Int, val k: Int)

    internal class IndexTable private constructor(private val indices: IntArray) {
        val size: Int get() = indices.size
        operator fun get(index: Int): Int = indices[index]
        internal val accountedBytes: Long get() = indices.size.toLong() * 4 + ENTRY_OVERHEAD_BYTES

        companion object {
            internal fun fresh(n: Int, k: Int, comboCount: Int): IndexTable {
                val target = IntArray(comboCount * k)
                // Original batch3 fillCombinationIndices algorithm, including enumeration order.
                val working = IntArray(k)
                var position = 0
                fun visit(start: Int, depth: Int) {
                    if (depth == k) {
                        val base = position * k
                        for (slot in 0 until k) target[base + slot] = working[slot]
                        position++
                        return
                    }
                    val remaining = k - depth
                    for (index in start..n - remaining) {
                        working[depth] = index
                        visit(index + 1, depth + 1)
                    }
                }
                visit(0, 0)
                return IndexTable(target)
            }
        }
    }

    private val entries = LinkedHashMap<Key, IndexTable>(8, 0.75f, true)
    // Conservative accounting includes the int-array header, table/key/LRU-entry objects and
    // fixed map/bucket overhead; in-flight/evicted reader-owned tables are not resident entries.
    private var residentBytes = CACHE_OVERHEAD_BYTES

    init {
        require(maxResidentBytes >= CACHE_OVERHEAD_BYTES && maxEntries in 0..8)
    }

    fun get(n: Int, k: Int, comboCount: Int): IndexTable {
        val bytes = comboCount.toLong() * k * 4 + ENTRY_OVERHEAD_BYTES
        if (maxEntries == 0 || bytes > maxResidentBytes - CACHE_OVERHEAD_BYTES) {
            // Oversize keeps the original fresh allocation/fill and never evicts hot entries.
            return IndexTable.fresh(n, k, comboCount)
        }
        synchronized(this) {
            val key = Key(n, k)
            entries[key]?.let { return it }
            val table = IndexTable.fresh(n, k, comboCount)
            while (entries.size >= maxEntries || residentBytes + table.accountedBytes > maxResidentBytes) {
                val iterator = entries.entries.iterator()
                val oldest = iterator.next()
                residentBytes -= oldest.value.accountedBytes
                iterator.remove()
            }
            entries[key] = table
            residentBytes += table.accountedBytes
            return table
        }
    }

    companion object {
        private const val CACHE_OVERHEAD_BYTES = 512L
        private const val ENTRY_OVERHEAD_BYTES = 128L
        internal val shared = LabyrinthCombinationIndexCache()
    }
}
