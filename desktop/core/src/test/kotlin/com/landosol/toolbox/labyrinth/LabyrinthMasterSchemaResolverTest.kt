package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthMasterSchemaResolverTest {
    @Test
    fun `resolves hashed physical tables from column characteristics`() {
        val schemas = listOf(
            schema("v1_a1", "unit_id", "name", "kana", "search_area_width", "atk_type"),
            schema(
                "v1_b2",
                "unit_id",
                "union_burst_1",
                "main_skill_1",
                "main_skill_2",
                "ex_skill_1",
            ),
            schema("v1_c3", "skill_id", "name", "description", "skill_action_1", "skill_action_2"),
            schema(
                "v1_d4",
                "action_id",
                "action_type",
                "action_detail_1",
                "target_assignment",
                "target_area",
            ),
        )

        val result = LabyrinthMasterSchemaResolver().resolve(schemas)

        assertTrue(result.issues.isEmpty())
        assertEquals("v1_a1", result.tables.getValue(LabyrinthLogicalTable.UNIT_DATA).physicalName)
        assertEquals("v1_b2", result.tables.getValue(LabyrinthLogicalTable.UNIT_SKILL_DATA).physicalName)
        assertEquals("v1_c3", result.tables.getValue(LabyrinthLogicalTable.SKILL_DATA).physicalName)
        assertEquals("v1_d4", result.tables.getValue(LabyrinthLogicalTable.SKILL_ACTION).physicalName)
    }

    @Test
    fun `ambiguous schemas stay unresolved instead of guessing a hash table`() {
        val schemas = listOf(
            schema("v1_same1", "skill_id", "name", "description", "skill_action_1"),
            schema("v1_same2", "skill_id", "name", "description", "skill_action_1"),
        )

        val result = LabyrinthMasterSchemaResolver().resolve(schemas)

        assertFalse(result.tables.containsKey(LabyrinthLogicalTable.SKILL_DATA))
        assertTrue(result.issues.any { it.contains("SKILL_DATA") && it.contains("无法唯一识别") })
    }

    @Test
    fun `dynamic sqlite identifiers only accept enumerated identifier grammar`() {
        assertTrue(LabyrinthSqlIdentifier.isSafe("v1_75d2e779_unit"))
        assertTrue(LabyrinthSqlIdentifier.isSafe("75d2e779_column"))
        assertFalse(LabyrinthSqlIdentifier.isSafe("unit_data; DROP TABLE unit_data"))
        assertFalse(LabyrinthSqlIdentifier.isSafe("unit-data"))
        assertFalse(LabyrinthSqlIdentifier.isSafe("\"unit_data\""))
    }

    @Test
    fun `semantic resolver follows stable ids and references when every identifier is hashed`() {
        val unitIds = LabyrinthMasterSemanticSchemaResolver.UNIT_ID_ANCHORS
        val skillIds = unitIds.mapIndexed { index, _ -> 1_000_001L + index }.toSet()
        val actionIds = skillIds.mapIndexed { index, _ -> 100_000_001L + index }.toSet()
        val schemas = listOf(
            typedSchema("v1_aa", integers = 18, texts = 5, reals = 1),
            typedSchema("v1_bb", integers = 33),
            typedSchema("v1_cc", integers = 24, texts = 2, reals = 2),
            typedSchema("v1_dd", integers = 12, texts = 2, reals = 7),
            typedSchema("v1_unit_profile_decoy", integers = 2, texts = 14),
            typedSchema("v1_skill_subset_decoy", integers = 21, texts = 6),
        )
        val rows = mapOf(
            "v1_aa" to unitIds.associateWith { listOf(it) },
            "v1_bb" to unitIds.mapIndexed { index, unitId ->
                unitId to listOf(unitId, skillIds.elementAt(index))
            }.toMap(),
            "v1_cc" to skillIds.mapIndexed { index, skillId ->
                skillId to listOf(skillId, actionIds.elementAt(index))
            }.toMap(),
            "v1_dd" to actionIds.associateWith { listOf(it, 1L, 2L) },
            "v1_unit_profile_decoy" to unitIds.associateWith { listOf(it) },
            "v1_skill_subset_decoy" to skillIds.take(5).associateWith { listOf(it) },
        )

        val result = LabyrinthMasterSemanticSchemaResolver().resolve(schemas, FakeProbe(rows))

        assertTrue(result.issues.isEmpty())
        assertEquals("v1_aa", result.tables.getValue(LabyrinthLogicalTable.UNIT_DATA).physicalName)
        assertEquals("v1_bb", result.tables.getValue(LabyrinthLogicalTable.UNIT_SKILL_DATA).physicalName)
        assertEquals("v1_cc", result.tables.getValue(LabyrinthLogicalTable.SKILL_DATA).physicalName)
        assertEquals("v1_dd", result.tables.getValue(LabyrinthLogicalTable.SKILL_ACTION).physicalName)
    }

    @Test
    fun `semantic resolver refuses incomplete reference coverage`() {
        val unitIds = LabyrinthMasterSemanticSchemaResolver.UNIT_ID_ANCHORS
        val skillIds = unitIds.mapIndexed { index, _ -> 1_000_001L + index }.toSet()
        val schemas = listOf(
            typedSchema("v1_aa", integers = 18, texts = 5, reals = 1),
            typedSchema("v1_bb", integers = 33),
            typedSchema("v1_partial", integers = 24, texts = 2, reals = 2),
        )
        val rows = mapOf(
            "v1_aa" to unitIds.associateWith { listOf(it) },
            "v1_bb" to unitIds.mapIndexed { index, unitId ->
                unitId to listOf(unitId, skillIds.elementAt(index))
            }.toMap(),
            "v1_partial" to skillIds.take(3).associateWith { listOf(it) },
        )

        val result = LabyrinthMasterSemanticSchemaResolver().resolve(schemas, FakeProbe(rows))

        assertFalse(result.complete)
        assertTrue(result.issues.any { it.contains("SKILL_DATA") && it.contains("引用覆盖") })
    }

    private fun schema(name: String, vararg columns: String) = LabyrinthSqliteTableSchema(
        physicalName = name,
        columns = columns.toSet(),
    )

    private fun typedSchema(
        name: String,
        integers: Int,
        texts: Int = 0,
        reals: Int = 0,
    ): LabyrinthSqliteTableSchema {
        val definitions = buildList {
            repeat(integers) { index ->
                add(
                    LabyrinthSqliteColumnSchema(
                        physicalName = "${index}a",
                        declaredType = "INTEGER",
                        primaryKeyPosition = if (index == 0) 1 else 0,
                    ),
                )
            }
            repeat(texts) { index -> add(LabyrinthSqliteColumnSchema("${index}b", "TEXT")) }
            repeat(reals) { index -> add(LabyrinthSqliteColumnSchema("${index}c", "REAL")) }
        }
        return LabyrinthSqliteTableSchema(
            physicalName = name,
            columns = definitions.mapTo(linkedSetOf(), LabyrinthSqliteColumnSchema::physicalName),
            columnDefinitions = definitions,
        )
    }

    private class FakeProbe(
        private val rows: Map<String, Map<Long, List<Long>>>,
    ) : LabyrinthMasterDataProbe {
        override fun primaryKeyValuesMatching(
            schema: LabyrinthSqliteTableSchema,
            candidates: Set<Long>,
        ): Set<Long> = rows[schema.physicalName].orEmpty().keys.intersect(candidates)

        override fun integerValuesForPrimaryKeys(
            schema: LabyrinthSqliteTableSchema,
            primaryKeys: Set<Long>,
            range: LongRange,
        ): Set<Long> = rows[schema.physicalName].orEmpty()
            .filterKeys(primaryKeys::contains)
            .values
            .flatten()
            .filterTo(linkedSetOf(), range::contains)
    }
}
