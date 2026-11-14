package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.WorkKind
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class Batch5SharedPoolIconTest {
    @Test fun bothScoringPassesPreserveCompleteSerialOutputsFromSimultaneousCpuParents() {
        val random = Random(5)
        val image = PixelImage(128, 128, IntArray(128 * 128) {
            val red = random.nextInt(256); val green = random.nextInt(256); val blue = random.nextInt(256)
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        })
        // Two logical roles with tied identical pixels deliberately exercise stable variant/rival
        // ordering. Repeating the SAME templates changes only shard count, never score inputs,
        // exposed candidates or finalist membership (all scores pass the coarse cutoff).
        val templates = listOf(
            LabyrinthBattleCharacterTemplate("a", "A", "31", image),
            LabyrinthBattleCharacterTemplate("b", "B", "31", image),
        )
        val serialPack = List(64) { templates[it % 2] } // Both passes use the old serial branch.
        val parallelPack = List(128) { templates[it % 2] } // Both passes require two CPU shards.
        val rect = EntryPixelRect(0, 0, 128, 128)
        val masks = listOf(LabyrinthCharacterIconMask(), LabyrinthCharacterIconMask(ignoreTopLeft = true, ignoreBottomBand = true))
        for (mask in masks) {
            val expected = LabyrinthCharacterIconMatcher(serialPack).match(image, rect, mask)
            assertEquals(listOf("a", "b"), expected.candidates.map { it.characterId })
            val barrier = CyclicBarrier(2)
            val handles = (0..1).map {
                LabyrinthFourThreadRuntime.submitWork(WorkKind.DETAILED_VISION) {
                    val matcher = LabyrinthCharacterIconMatcher(parallelPack) // one instance per root
                    barrier.await(10, TimeUnit.SECONDS)
                    matcher.match(image, rect, mask)
                }
            }
            try { handles.forEach { assertEquals(expected, it.future.get(10, TimeUnit.SECONDS)) } }
            finally { handles.forEach { it.cancel(false) } }
        }
    }

    @Test fun sharedScoringShardsProduceEveryValueBitExactlyInOriginalIndexOrder() {
        val matcher = LabyrinthCharacterIconMatcher()
        val scoreAll = matcher.javaClass.getDeclaredMethod("scoreAll", Int::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java)
            .also { it.isAccessible = true }
        for (size in listOf(0, 1, 63, 64, 127, 128, 129, 255, 795)) {
            val score: (Int) -> Double = { index -> if (index == 0) -0.0 else index * 0.03125 }
            val expected = DoubleArray(size) { score(it) }
            val actual = LabyrinthFourThreadRuntime.submitWork(WorkKind.OBSERVATION) {
                scoreAll.invoke(matcher, size, score) as DoubleArray
            }.future.get(10, TimeUnit.SECONDS)
            assertEquals(size, actual.size)
            expected.indices.forEach { assertEquals(expected[it].toRawBits(), actual[it].toRawBits()) }
        }
    }

    @Test fun rosterFilteringAndGeometryRecoveryRemainIdenticalAcrossSerialAndSharedPool() {
        val random = Random(505)
        val image = PixelImage(128, 128, IntArray(128 * 128) {
            val value = random.nextInt(256)
            (0xff shl 24) or (value shl 16) or (value shl 8) or value
        })
        val a = LabyrinthBattleCharacterTemplate("a", "A", "31", image)
        val b = LabyrinthBattleCharacterTemplate("b", "B", "31", image)
        val serial = LabyrinthCharacterIconMatcher(List(64) { if (it % 2 == 0) a else b })
        val parallel = LabyrinthCharacterIconMatcher(List(128) { if (it % 2 == 0) a else b })
        // Refinement expands/translates by radius=(128/90).roundToInt()=1 (global cap 3).
        // Keep the same portrait/template pixels, with >=64 px clear margins on this frame.
        val framePixels = IntArray(320 * 256)
        for (y in 0 until image.height) {
            System.arraycopy(image.pixels, y * image.width, framePixels, (64 + y) * 320 + 64, image.width)
        }
        val frame = PixelImage(320, 256, framePixels)
        val rect = EntryPixelRect(64, 64, 128, 128)
        val mask = LabyrinthCharacterIconMask(ignoreRightEdge = true)
        val roster = setOf("a")
        val expected = serial.match(frame, rect, mask, LabyrinthCharacterAttribute.UNKNOWN, roster)
        val handle = LabyrinthFourThreadRuntime.submitWork(WorkKind.DETAILED_VISION) {
            parallel.match(frame, rect, mask, LabyrinthCharacterAttribute.UNKNOWN, roster)
        }
        assertEquals(expected, handle.future.get(10, TimeUnit.SECONDS))
        // Crops and their reconciliation retain the original full candidate/rival records as well.
        val expectedRefined = serial.refineMemberGeometry(frame, rect, expected, mask, force = true, rosterCharacterIds = roster)
        val actualRefined = LabyrinthFourThreadRuntime.submitWork(WorkKind.DETAILED_VISION) {
            parallel.refineMemberGeometry(frame, rect, expected, mask, force = true, rosterCharacterIds = roster)
        }.future.get(10, TimeUnit.SECONDS)
        assertEquals(expectedRefined, actualRefined)
    }
}
