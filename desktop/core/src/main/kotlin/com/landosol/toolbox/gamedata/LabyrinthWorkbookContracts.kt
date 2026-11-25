package com.landosol.toolbox.gamedata

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LabyrinthWorkbookSource(
    val file: String = "",
    val note: String = "",
)

@Serializable
data class LabyrinthWorkbookCell(
    val address: String = "",
    val value: String = "",
    val formula: String? = null,
)

@Serializable
data class LabyrinthWorkbookRow(
    val row: Int = 0,
    val cells: List<LabyrinthWorkbookCell> = emptyList(),
)

@Serializable
data class LabyrinthWorkbookTable(
    val name: String = "",
    val rows: List<LabyrinthWorkbookRow> = emptyList(),
)

@Serializable
data class LabyrinthWorkbookDocument(
    val schemaVersion: Int = 1,
    val source: LabyrinthWorkbookSource = LabyrinthWorkbookSource(),
    val excludedSheets: List<String> = emptyList(),
    val tables: List<LabyrinthWorkbookTable> = emptyList(),
)

sealed interface LabyrinthWorkbookLoadResult {
    data class Success(val document: LabyrinthWorkbookDocument) : LabyrinthWorkbookLoadResult
    data class Failure(val reasons: List<String>) : LabyrinthWorkbookLoadResult
}

class LabyrinthWorkbookLoader(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun load(root: File): LabyrinthWorkbookLoadResult {
        val file = File(root, WORKBOOK_FILE)
        if (!file.isFile) return LabyrinthWorkbookLoadResult.Failure(listOf("缺少迷宫数据清单：$WORKBOOK_FILE"))
        return runCatching {
            val document = json.decodeFromString<LabyrinthWorkbookDocument>(file.readText())
            val reasons = validate(document)
            if (reasons.isEmpty()) LabyrinthWorkbookLoadResult.Success(document)
            else LabyrinthWorkbookLoadResult.Failure(reasons)
        }.getOrElse { error ->
            LabyrinthWorkbookLoadResult.Failure(listOf("迷宫数据 JSON 无法解析：${error.message ?: "未知错误"}"))
        }
    }

    private fun validate(document: LabyrinthWorkbookDocument): List<String> = buildList {
        if (document.schemaVersion != CURRENT_SCHEMA_VERSION) {
            add("不支持的迷宫数据 Schema 版本：${document.schemaVersion}")
        }
        if (document.source.file.isBlank()) add("迷宫数据来源文件为空")
        if (document.tables.isEmpty()) add("迷宫数据表为空")
        document.tables.map(LabyrinthWorkbookTable::name)
            .groupingBy { it }
            .eachCount()
            .filter { (name, count) -> name.isNotBlank() && count > 1 }
            .keys
            .forEach { add("迷宫数据表重复：$it") }
        document.tables.forEach { table ->
            if (table.name.isBlank()) add("迷宫数据表名称为空")
            table.rows.forEach { row ->
                if (row.row <= 0) add("迷宫数据行号无效：${table.name}")
                val addresses = row.cells.map(LabyrinthWorkbookCell::address)
                if (addresses.any(String::isBlank)) add("迷宫数据存在空单元格地址：${table.name} 第 ${row.row} 行")
                if (addresses.size != addresses.toSet().size) {
                    add("迷宫数据单元格地址重复：${table.name} 第 ${row.row} 行")
                }
            }
        }
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val WORKBOOK_FILE = "labyrinth-workbook.json"
    }
}
