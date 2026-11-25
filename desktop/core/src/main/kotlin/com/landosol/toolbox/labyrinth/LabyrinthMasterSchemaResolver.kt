package com.landosol.toolbox.labyrinth

enum class LabyrinthLogicalTable {
    UNIT_DATA,
    UNIT_SKILL_DATA,
    SKILL_DATA,
    SKILL_ACTION,
}

data class LabyrinthSqliteColumnSchema(
    val physicalName: String,
    val declaredType: String,
    val primaryKeyPosition: Int = 0,
) {
    init {
        require(LabyrinthSqlIdentifier.isSafe(physicalName))
        require(primaryKeyPosition >= 0)
    }

    val normalizedType: String = declaredType.trim().uppercase()
}

data class LabyrinthSqliteTableSchema(
    val physicalName: String,
    val columns: Set<String>,
    val columnDefinitions: List<LabyrinthSqliteColumnSchema> = columns.map {
        LabyrinthSqliteColumnSchema(it, declaredType = "")
    },
) {
    init {
        require(LabyrinthSqlIdentifier.isSafe(physicalName))
        require(columns.isNotEmpty())
        require(columns.all(LabyrinthSqlIdentifier::isSafe))
        require(columnDefinitions.isNotEmpty())
        require(columnDefinitions.mapTo(linkedSetOf(), LabyrinthSqliteColumnSchema::physicalName) == columns)
    }

    val normalizedColumns: Set<String> = columns.map(String::lowercase).toSet()
    val integerColumnCount: Int = columnDefinitions.count { it.normalizedType == "INTEGER" }
    val textColumnCount: Int = columnDefinitions.count { it.normalizedType == "TEXT" }
    val realColumnCount: Int = columnDefinitions.count { it.normalizedType == "REAL" }
    val primaryKeyColumns: List<LabyrinthSqliteColumnSchema> = columnDefinitions
        .filter { it.primaryKeyPosition > 0 }
        .sortedBy(LabyrinthSqliteColumnSchema::primaryKeyPosition)

    val singleIntegerPrimaryKey: LabyrinthSqliteColumnSchema?
        get() = primaryKeyColumns.singleOrNull()?.takeIf { it.normalizedType == "INTEGER" }
}

data class LabyrinthResolvedLogicalTable(
    val logicalTable: LabyrinthLogicalTable,
    val physicalName: String,
    val confidenceScore: Int,
    val matchedSignals: List<String>,
)

data class LabyrinthMasterSchemaResolution(
    val tables: Map<LabyrinthLogicalTable, LabyrinthResolvedLogicalTable>,
    val issues: List<String>,
) {
    val complete: Boolean
        get() = tables.keys.containsAll(LabyrinthLogicalTable.entries)
}

/** Only identifiers returned by sqlite_master and matching this grammar may enter dynamic SQL. */
object LabyrinthSqlIdentifier {
    // CN master columns can be hexadecimal hashes beginning with a digit. Values are still quoted,
    // and only identifiers enumerated from sqlite_master/PRAGMA are allowed to reach SQL.
    private val SAFE = Regex("^[A-Za-z0-9_]{1,128}$")

    fun isSafe(value: String): Boolean = SAFE.matches(value)

    fun quoteEnumerated(value: String): String {
        require(isSafe(value)) { "Unsafe SQLite identifier" }
        return "\"$value\""
    }
}

interface LabyrinthMasterDataProbe {
    fun primaryKeyValuesMatching(
        schema: LabyrinthSqliteTableSchema,
        candidates: Set<Long>,
    ): Set<Long>

    fun integerValuesForPrimaryKeys(
        schema: LabyrinthSqliteTableSchema,
        primaryKeys: Set<Long>,
        range: LongRange,
    ): Set<Long>
}

/**
 * Resolves fully obfuscated CN master tables by stable content and reference relationships:
 * unit ids -> unit skill ids -> skill action ids. No physical hash or hashed column is persisted.
 */
class LabyrinthMasterSemanticSchemaResolver(
    private val minimumUnitAnchorHits: Int = 8,
    private val minimumReferenceCoveragePercent: Int = 80,
) {
    init {
        require(minimumUnitAnchorHits in 1..UNIT_ID_ANCHORS.size)
        require(minimumReferenceCoveragePercent in 51..100)
    }

    fun resolve(
        schemas: List<LabyrinthSqliteTableSchema>,
        probe: LabyrinthMasterDataProbe,
    ): LabyrinthMasterSchemaResolution {
        val unique = schemas.distinctBy(LabyrinthSqliteTableSchema::physicalName)
        val issues = mutableListOf<String>()
        val resolved = linkedMapOf<LabyrinthLogicalTable, LabyrinthResolvedLogicalTable>()

        val unitData = chooseUnitTable(
            logical = LabyrinthLogicalTable.UNIT_DATA,
            schemas = unique.filter {
                it.singleIntegerPrimaryKey != null &&
                    it.integerColumnCount >= 10 &&
                    it.textColumnCount in 2..10 &&
                    it.columnDefinitions.size in 15..45
            },
            probe = probe,
            structuralScore = { it.integerColumnCount + it.textColumnCount * 2 + it.realColumnCount },
            issues = issues,
        )
        unitData?.let { resolved[LabyrinthLogicalTable.UNIT_DATA] = it }

        val unitSkill = chooseUnitTable(
            logical = LabyrinthLogicalTable.UNIT_SKILL_DATA,
            schemas = unique.filter {
                it.singleIntegerPrimaryKey != null &&
                    it.integerColumnCount >= 25 &&
                    it.textColumnCount == 0 &&
                    it.realColumnCount == 0
            },
            probe = probe,
            structuralScore = { it.integerColumnCount.coerceAtMost(50) },
            issues = issues,
        )
        unitSkill?.let { resolved[LabyrinthLogicalTable.UNIT_SKILL_DATA] = it }

        val skillIds = unitSkill?.let {
            probe.integerValuesForPrimaryKeys(it.toSchema(unique), UNIT_ID_ANCHORS, SKILL_ID_RANGE)
        }.orEmpty()
        val skillData = chooseReferencedTable(
            logical = LabyrinthLogicalTable.SKILL_DATA,
            schemas = unique.filter {
                it.singleIntegerPrimaryKey != null &&
                    it.integerColumnCount >= 10 &&
                    it.textColumnCount >= 2 &&
                    it.columnDefinitions.size in 15..50
            },
            referenceIds = skillIds,
            probe = probe,
            issues = issues,
        )
        skillData?.let { resolved[LabyrinthLogicalTable.SKILL_DATA] = it }

        val actionIds = skillData?.let {
            probe.integerValuesForPrimaryKeys(it.toSchema(unique), skillIds, ACTION_ID_RANGE)
        }.orEmpty()
        val skillAction = chooseReferencedTable(
            logical = LabyrinthLogicalTable.SKILL_ACTION,
            schemas = unique.filter {
                it.singleIntegerPrimaryKey != null &&
                    it.integerColumnCount >= 8 &&
                    it.columnDefinitions.size in 12..50
            },
            referenceIds = actionIds,
            probe = probe,
            issues = issues,
        )
        skillAction?.let { resolved[LabyrinthLogicalTable.SKILL_ACTION] = it }

        val reused = resolved.values.groupBy(LabyrinthResolvedLogicalTable::physicalName)
            .filterValues { it.size > 1 }
        reused.forEach { (physicalName, mappings) ->
            mappings.forEach { resolved.remove(it.logicalTable) }
            issues += "物理表${physicalName}同时匹配${mappings.joinToString { it.logicalTable.name }}，已全部拒绝"
        }
        return LabyrinthMasterSchemaResolution(resolved, issues)
    }

    private fun chooseUnitTable(
        logical: LabyrinthLogicalTable,
        schemas: List<LabyrinthSqliteTableSchema>,
        probe: LabyrinthMasterDataProbe,
        structuralScore: (LabyrinthSqliteTableSchema) -> Int,
        issues: MutableList<String>,
    ): LabyrinthResolvedLogicalTable? {
        val ranked = schemas.map { schema ->
            val hits = probe.primaryKeyValuesMatching(schema, UNIT_ID_ANCHORS).size
            Candidate(schema, hits * 100 + structuralScore(schema), hits)
        }.filter { it.referenceHits >= minimumUnitAnchorHits }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.schema.physicalName })
        val best = ranked.firstOrNull()
        val second = ranked.getOrNull(1)
        if (best == null) {
            issues += "$logical 未找到满足稳定角色ID与结构特征的表"
            return null
        }
        if (second != null && best.referenceHits == second.referenceHits && best.score - second.score < 5) {
            issues += "$logical 无法唯一识别：候选锚点命中数相同"
            return null
        }
        return LabyrinthResolvedLogicalTable(
            logicalTable = logical,
            physicalName = best.schema.physicalName,
            confidenceScore = best.score,
            matchedSignals = listOf(
                "稳定unit_id锚点 ${best.referenceHits}/${UNIT_ID_ANCHORS.size}",
                "字段类型结构 ${best.schema.integerColumnCount}I/${best.schema.textColumnCount}T/" +
                    "${best.schema.realColumnCount}R",
            ),
        )
    }

    private fun chooseReferencedTable(
        logical: LabyrinthLogicalTable,
        schemas: List<LabyrinthSqliteTableSchema>,
        referenceIds: Set<Long>,
        probe: LabyrinthMasterDataProbe,
        issues: MutableList<String>,
    ): LabyrinthResolvedLogicalTable? {
        if (referenceIds.size < MINIMUM_REFERENCE_COUNT) {
            issues += "$logical 引用覆盖校验所需样本不足：${referenceIds.size}"
            return null
        }
        val ranked = schemas.map { schema ->
            val hits = probe.primaryKeyValuesMatching(schema, referenceIds).size
            Candidate(schema, hits, hits)
        }.sortedWith(compareByDescending<Candidate> { it.referenceHits }.thenBy { it.schema.physicalName })
        val best = ranked.firstOrNull()
        val requiredHits = percentageCeiling(referenceIds.size, minimumReferenceCoveragePercent)
        if (best == null || best.referenceHits < requiredHits) {
            issues += "$logical 引用覆盖不足：${best?.referenceHits ?: 0}/${referenceIds.size}，要求至少$requiredHits"
            return null
        }
        val second = ranked.getOrNull(1)
        val requiredMargin = (referenceIds.size / 10).coerceAtLeast(3)
        if (second != null && best.referenceHits - second.referenceHits < requiredMargin) {
            issues += "$logical 无法唯一识别：前两名引用覆盖差小于$requiredMargin"
            return null
        }
        return LabyrinthResolvedLogicalTable(
            logicalTable = logical,
            physicalName = best.schema.physicalName,
            confidenceScore = best.referenceHits,
            matchedSignals = listOf("上游ID引用覆盖 ${best.referenceHits}/${referenceIds.size}"),
        )
    }

    private fun LabyrinthResolvedLogicalTable.toSchema(
        schemas: List<LabyrinthSqliteTableSchema>,
    ): LabyrinthSqliteTableSchema = schemas.single { it.physicalName == physicalName }

    private fun percentageCeiling(value: Int, percent: Int): Int = (value * percent + 99) / 100

    private data class Candidate(
        val schema: LabyrinthSqliteTableSchema,
        val score: Int,
        val referenceHits: Int,
    )

    companion object {
        val UNIT_ID_ANCHORS: Set<Long> = linkedSetOf(
            100101L,
            100301L,
            101101L,
            102301L,
            105901L,
            107501L,
            108801L,
            108901L,
            109101L,
            117101L,
            122501L,
            124201L,
        )
        private const val MINIMUM_REFERENCE_COUNT = 8
        private val SKILL_ID_RANGE = 1_000_000L..99_999_999L
        private val ACTION_ID_RANGE = 100_000_000L..9_999_999_999L
    }
}

/** Resolves logical tables from columns; no current v1_hash table name is hard-coded. */
class LabyrinthMasterSchemaResolver(
    private val minimumMargin: Int = 2,
) {
    init {
        require(minimumMargin >= 1)
    }

    fun resolve(schemas: List<LabyrinthSqliteTableSchema>): LabyrinthMasterSchemaResolution {
        val uniqueSchemas = schemas.distinctBy(LabyrinthSqliteTableSchema::physicalName)
        val resolved = linkedMapOf<LabyrinthLogicalTable, LabyrinthResolvedLogicalTable>()
        val issues = mutableListOf<String>()
        SIGNATURES.forEach { (logical, signature) ->
            val ranked = uniqueSchemas.mapNotNull { schema -> signature.score(schema)?.let { schema to it } }
                .sortedWith(
                    compareByDescending<Pair<LabyrinthSqliteTableSchema, MatchScore>> { it.second.score }
                        .thenBy { it.first.physicalName },
                )
            val best = ranked.firstOrNull()
            val second = ranked.getOrNull(1)
            if (best == null || (second != null && best.second.score - second.second.score < minimumMargin)) {
                issues += if (best == null) {
                    "$logical 未找到满足字段特征的表"
                } else {
                    "$logical 无法唯一识别：${best.first.physicalName}=${best.second.score}, " +
                        "${second?.first?.physicalName}=${second?.second?.score}"
                }
                return@forEach
            }
            resolved[logical] = LabyrinthResolvedLogicalTable(
                logicalTable = logical,
                physicalName = best.first.physicalName,
                confidenceScore = best.second.score,
                matchedSignals = best.second.signals,
            )
        }
        val reused = resolved.values.groupBy(LabyrinthResolvedLogicalTable::physicalName)
            .filterValues { it.size > 1 }
        reused.forEach { (physicalName, mappings) ->
            mappings.forEach { resolved.remove(it.logicalTable) }
            issues += "物理表${physicalName}同时匹配${mappings.joinToString { it.logicalTable.name }}，已全部拒绝"
        }
        return LabyrinthMasterSchemaResolution(resolved, issues)
    }

    private data class Signature(
        val requiredExactGroups: List<Set<String>>,
        val requiredPrefixes: List<String>,
        val optionalExact: Set<String>,
        val optionalPrefixes: List<String>,
    ) {
        fun score(schema: LabyrinthSqliteTableSchema): MatchScore? {
            val columns = schema.normalizedColumns
            if (requiredExactGroups.any { group -> group.none(columns::contains) }) return null
            if (requiredPrefixes.any { prefix -> columns.none { it.startsWith(prefix) } }) return null
            val signals = mutableListOf<String>()
            requiredExactGroups.forEach { group ->
                group.firstOrNull(columns::contains)?.let(signals::add)
            }
            requiredPrefixes.forEach { prefix ->
                columns.firstOrNull { it.startsWith(prefix) }?.let(signals::add)
            }
            optionalExact.filter(columns::contains).forEach(signals::add)
            optionalPrefixes.forEach { prefix ->
                columns.filter { it.startsWith(prefix) }.take(3).forEach(signals::add)
            }
            val requiredScore = requiredExactGroups.size * REQUIRED_SIGNAL_WEIGHT +
                requiredPrefixes.size * REQUIRED_SIGNAL_WEIGHT
            val optionalScore = signals.size - requiredExactGroups.size - requiredPrefixes.size
            return MatchScore(requiredScore + optionalScore, signals.distinct().sorted())
        }
    }

    private data class MatchScore(
        val score: Int,
        val signals: List<String>,
    )

    private companion object {
        const val REQUIRED_SIGNAL_WEIGHT = 5
        val SIGNATURES = linkedMapOf(
            LabyrinthLogicalTable.UNIT_DATA to Signature(
                requiredExactGroups = listOf(setOf("unit_id"), setOf("name")),
                requiredPrefixes = emptyList(),
                optionalExact = setOf("kana", "search_area_width", "atk_type", "rarity"),
                optionalPrefixes = listOf("normal_atk_", "comment"),
            ),
            LabyrinthLogicalTable.UNIT_SKILL_DATA to Signature(
                requiredExactGroups = listOf(setOf("unit_id")),
                requiredPrefixes = listOf("union_burst_", "main_skill_", "ex_skill_"),
                optionalExact = emptySet(),
                optionalPrefixes = listOf("sp_skill_", "free_skill_"),
            ),
            LabyrinthLogicalTable.SKILL_DATA to Signature(
                requiredExactGroups = listOf(
                    setOf("skill_id"),
                    setOf("name"),
                    setOf("description", "description_2"),
                ),
                requiredPrefixes = listOf("skill_action_"),
                optionalExact = setOf("icon_type"),
                optionalPrefixes = listOf("skill_action_"),
            ),
            LabyrinthLogicalTable.SKILL_ACTION to Signature(
                requiredExactGroups = listOf(
                    setOf("action_id"),
                    setOf("action_type"),
                    setOf("target_assignment", "target_type"),
                ),
                requiredPrefixes = listOf("action_detail_"),
                optionalExact = setOf("target_area", "target_range", "duration"),
                optionalPrefixes = listOf("action_value_"),
            ),
        )
    }
}
