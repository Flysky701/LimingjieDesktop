package com.landosol.toolbox.labyrinth

import java.lang.reflect.Modifier
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Batch4RoleCombinationIndexCacheTest {
    @Test fun everyIndexMatchesFrozenBatch3FillAcrossDimensionsAndLexicographicOrder() {
        val cache = LabyrinthCombinationIndexCache()
        for (n in 0..24) for (k in 0..minOf(5, n)) {
            val expected = batch4FreshReferenceIndices(n, k)
            val table = cache.get(n, k, batch4CombinationCount(n, k))
            assertEquals(expected.size, table.size)
            for (index in expected.indices) assertEquals("($n,$k)[$index]", expected[index], table[index])
            assertSame(table, cache.get(n, k, batch4CombinationCount(n, k)))
            batch4AssertResidentBounds(cache)
        }
    }

    @Test fun readOnlySurfaceCannotExposeOrReplaceMutableArray() {
        val type = LabyrinthCombinationIndexCache.IndexTable::class.java
        assertTrue(type.declaredFields.filter { it.type == IntArray::class.java }.all { Modifier.isPrivate(it.modifiers) })
        assertTrue(type.methods.none { it.returnType.isArray })
        assertTrue(type.methods.none { it.name == "set" || it.name.startsWith("setIndices") })
        assertTrue(type.declaredConstructors.all { Modifier.isPrivate(it.modifiers) || it.isSynthetic })
    }

    @Test fun entryBoundAndAccessOrderEvictOnlyLeastRecentlyUsed() {
        val cache = LabyrinthCombinationIndexCache()
        val tables = (5..12).associateWith { cache.get(it, 1, it) }
        assertEquals(8, batch4ResidentEntryCount(cache))
        assertSame(tables.getValue(5), cache.get(5, 1, 5))
        cache.get(13, 1, 13)
        assertEquals(8, batch4ResidentEntryCount(cache))
        assertSame(tables.getValue(5), cache.get(5, 1, 5))
        assertNotSame(tables.getValue(6), cache.get(6, 1, 6))
        // Identical n with different k must not alias; retained readers remain intact on eviction.
        val differentK = cache.get(5, 2, batch4CombinationCount(5, 2))
        assertNotSame(tables.getValue(5), differentK)
        assertEquals(4, tables.getValue(5)[4])
        batch4AssertResidentBounds(cache)
    }

    @Test fun byteBoundAndOversizeFallbackDoNotRetainOrEvictHotEntries() {
        val cache = LabyrinthCombinationIndexCache()
        val evictedReader = cache.get(24, 5, batch4CombinationCount(24, 5))
        for (n in listOf(23, 22, 21, 20, 24, 23, 19, 18, 17)) {
            cache.get(n, 5, batch4CombinationCount(n, 5))
            batch4AssertResidentBounds(cache)
        }
        val expectedOld = batch4FreshReferenceIndices(24, 5)
        for (index in expectedOld.indices) assertEquals(expectedOld[index], evictedReader[index])
        val hot = cache.get(24, 5, batch4CombinationCount(24, 5))
        val beforeBytes = batch4ResidentBytes(cache)
        val beforeEntries = batch4ResidentEntryCount(cache)
        val first = cache.get(32, 5, batch4CombinationCount(32, 5))
        val second = cache.get(32, 5, batch4CombinationCount(32, 5))
        assertNotSame(first, second)
        assertTrue(first.accountedBytes > 2L * 1024 * 1024)
        val expected = batch4FreshReferenceIndices(32, 5)
        for (index in expected.indices) {
            assertEquals(expected[index], first[index])
            assertEquals(expected[index], second[index])
        }
        assertEquals(beforeBytes, batch4ResidentBytes(cache))
        assertEquals(beforeEntries, batch4ResidentEntryCount(cache))
        assertSame(hot, cache.get(24, 5, batch4CombinationCount(24, 5)))
        batch4AssertResidentBounds(cache)
    }

    @Test fun simultaneousMissPublishesOneTableAndConcurrentSizeChangesCannotCorruptReaders() {
        val cache = LabyrinthCombinationIndexCache()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map { executor.submit(Callable {
                check(start.await(30, TimeUnit.SECONDS))
                cache.get(24, 5, batch4CombinationCount(24, 5))
            }) }
            start.countDown()
            val tables = futures.map { it.get(60, TimeUnit.SECONDS) }
            tables.forEach { assertSame(tables.first(), it) }
            val expected = batch4FreshReferenceIndices(24, 5)
            val changing = (0 until 8).map { worker -> executor.submit(Callable {
                repeat(16) { iteration ->
                    val n = 17 + (worker + iteration) % 9
                    val k = if (iteration % 3 == 0) 3 else 5
                    val table = cache.get(n, k, batch4CombinationCount(n, k))
                    val fresh = batch4FreshReferenceIndices(n, k)
                    for (index in fresh.indices) assertEquals(fresh[index], table[index])
                    batch4AssertResidentBounds(cache)
                }
            }) }
            changing.forEach { it.get(60, TimeUnit.SECONDS) }
            for (index in expected.indices) assertEquals(expected[index], tables.first()[index])
            batch4AssertResidentBounds(cache)
        } finally {
            start.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }
}

internal fun batch4CombinationCount(n: Int, k: Int): Int {
    var result = 1L
    for (index in 0 until k) result = result * (n - index) / (index + 1)
    return result.toInt()
}

internal fun batch4FreshReferenceIndices(n: Int, k: Int): IntArray {
    val reference = Batch3TeamOptimizerReference(LabyrinthTeamScorer(batch3ScoringConfig()), 1)
    val target = IntArray(batch4CombinationCount(n, k) * k)
    reference.javaClass.getDeclaredMethod("fillCombinationIndices", Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, IntArray::class.java).also { it.isAccessible = true }
        .invoke(reference, n, k, target)
    return target
}

internal fun batch4ResidentBytes(cache: LabyrinthCombinationIndexCache): Long = synchronized(cache) {
    cache.javaClass.getDeclaredField("residentBytes").also { it.isAccessible = true }.getLong(cache)
}

internal fun batch4ResidentEntryCount(cache: LabyrinthCombinationIndexCache): Int = synchronized(cache) {
    (cache.javaClass.getDeclaredField("entries").also { it.isAccessible = true }.get(cache) as Map<*, *>).size
}

internal fun batch4AssertResidentBounds(cache: LabyrinthCombinationIndexCache) = synchronized(cache) {
    val entries = cache.javaClass.getDeclaredField("entries").also { it.isAccessible = true }.get(cache) as Map<*, *>
    val accounted = 512L + entries.values.sumOf { (it as LabyrinthCombinationIndexCache.IndexTable).accountedBytes }
    assertEquals(accounted, batch4ResidentBytes(cache))
    assertTrue("resident budget: $accounted", accounted <= 2L * 1024 * 1024)
    assertTrue("resident entry budget: ${entries.size}", entries.size <= 8)
}
