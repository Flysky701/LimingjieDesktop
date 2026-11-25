package com.landosol.toolbox.gamedata

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 0..1 归一化 ROI；具体像素坐标由截图尺寸在运行时换算。 */
@Serializable
data class NormalizedOcrRect(
    val left: Double = 0.0,
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
)

@Serializable
data class EventOcrStrategyResource(
    val selectButtonLabel: String = "选择",
    val normalizeWhitespace: Boolean = true,
    val ignorePunctuation: Boolean = true,
    val minTextScore: Double = 0.65,
)

/**
 * 事件页布局模板。初期允许只声明选项数量，等截图齐全后再补 ROI。
 */
@Serializable
data class EventLayoutTemplateResource(
    val id: String = "",
    val optionCount: Int = 0,
    val titleRoi: NormalizedOcrRect? = null,
    val optionTextRois: List<NormalizedOcrRect> = emptyList(),
    val selectButtonRois: List<NormalizedOcrRect> = emptyList(),
    val calibrationRequired: Boolean = true,
)

/**
 * OCR 文本签名不重复保存翻译，而是引用 labyrinth-workbook.json 的「事件」工作表单元格。
 * sourceCell 中原文与中文效果说明会在加载后解析出来。
 */
@Serializable
data class EventTextSignatureResource(
    val id: String = "",
    val sourceCell: String = "",
    val extraAliases: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val baseScore: Int = 0,
)

@Serializable
data class LabyrinthEventOcrDocument(
    val schemaVersion: Int = 1,
    val sourceTable: String = "事件",
    val strategy: EventOcrStrategyResource = EventOcrStrategyResource(),
    val layouts: List<EventLayoutTemplateResource> = emptyList(),
    val signatures: List<EventTextSignatureResource> = emptyList(),
)

data class ResolvedEventTextSignature(
    val id: String,
    val sourceCell: String,
    val sourceText: String,
    val displayText: String,
    val translatedEffect: String,
    val aliases: List<String>,
    val tags: List<String>,
    val baseScore: Int,
)

sealed interface LabyrinthEventOcrLoadResult {
    data class Success(
        val document: LabyrinthEventOcrDocument,
        val resolvedSignatures: List<ResolvedEventTextSignature>,
    ) : LabyrinthEventOcrLoadResult

    data class Failure(val reasons: List<String>) : LabyrinthEventOcrLoadResult
}

class LabyrinthEventOcrValidator {
    fun validate(
        document: LabyrinthEventOcrDocument,
        workbook: LabyrinthWorkbookDocument,
    ): List<String> = buildList {
        if (document.schemaVersion != CURRENT_SCHEMA_VERSION) {
            add("不支持的事件 OCR Schema 版本：${document.schemaVersion}")
        }
        if (document.sourceTable.isBlank()) add("事件 OCR 来源工作表为空")
        if (document.strategy.selectButtonLabel.isBlank()) add("事件选择按钮文字为空")
        if (document.strategy.minTextScore !in 0.0..1.0) add("事件 OCR 最低文本分数超出范围")

        validateUniqueIds("事件布局", document.layouts.map(EventLayoutTemplateResource::id), ::add)
        validateUniqueIds("事件文本签名", document.signatures.map(EventTextSignatureResource::id), ::add)

        document.layouts.forEach { layout ->
            if (layout.optionCount !in 1..3) add("事件布局 ${layout.id} 选项数必须为 1..3")
            if (!layout.calibrationRequired) {
                if (layout.optionTextRois.size != layout.optionCount) {
                    add("事件布局 ${layout.id} 文本 ROI 数量与选项数不一致")
                }
                if (layout.selectButtonRois.size != layout.optionCount) {
                    add("事件布局 ${layout.id} 按钮 ROI 数量与选项数不一致")
                }
            }
            listOfNotNull(layout.titleRoi)
                .plus(layout.optionTextRois)
                .plus(layout.selectButtonRois)
                .forEach { roi ->
                    if (!roi.isValid()) add("事件布局 ${layout.id} 存在无效归一化 ROI")
                }
        }

        val sourceTable = workbook.tables.firstOrNull { it.name == document.sourceTable }
        if (sourceTable == null) {
            add("事件 OCR 来源工作表不存在：${document.sourceTable}")
            return@buildList
        }
        val cells = sourceTable.rows.flatMap(LabyrinthWorkbookRow::cells).associateBy { it.address }
        document.signatures.forEach { signature ->
            if (!CELL_ADDRESS.matches(signature.sourceCell)) {
                add("事件文本签名 ${signature.id} 单元格地址无效：${signature.sourceCell}")
            } else if (cells[signature.sourceCell]?.value.isNullOrBlank()) {
                add("事件文本签名 ${signature.id} 引用不存在或为空的单元格：${signature.sourceCell}")
            }
            if (signature.extraAliases.any(String::isBlank)) {
                add("事件文本签名 ${signature.id} 存在空 OCR 别名")
            }
            if (signature.baseScore < 0) add("事件文本签名 ${signature.id} 评分不能为负数")
        }
    }

    private fun validateUniqueIds(kind: String, ids: List<String>, addReason: (String) -> Unit) {
        ids.forEachIndexed { index, id ->
            if (id.isBlank()) addReason("$kind 第 ${index + 1} 项 ID 为空")
        }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { duplicate ->
            addReason("$kind 存在重复 ID：$duplicate")
        }
    }

    private fun NormalizedOcrRect.isValid(): Boolean =
        left in 0.0..1.0 && top in 0.0..1.0 &&
            right in 0.0..1.0 && bottom in 0.0..1.0 &&
            left < right && top < bottom

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        val CELL_ADDRESS = Regex("^[A-Z]+[1-9][0-9]*$")
    }
}

class LabyrinthEventOcrLoader(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val validator: LabyrinthEventOcrValidator = LabyrinthEventOcrValidator(),
) {
    fun load(
        root: File,
        workbook: LabyrinthWorkbookDocument,
        fileName: String = FILE_NAME,
    ): LabyrinthEventOcrLoadResult = runCatching {
        val file = File(root, fileName)
        require(file.isFile) { "缺少事件 OCR 资源：$fileName" }
        val document = json.decodeFromString<LabyrinthEventOcrDocument>(file.readText())
        val reasons = validator.validate(document, workbook)
        if (reasons.isNotEmpty()) return LabyrinthEventOcrLoadResult.Failure(reasons)
        LabyrinthEventOcrLoadResult.Success(
            document = document,
            resolvedSignatures = document.resolve(workbook),
        )
    }.getOrElse { error ->
        LabyrinthEventOcrLoadResult.Failure(
            listOf("事件 OCR JSON 无法解析：${error.message ?: "未知错误"}"),
        )
    }

    companion object {
        const val FILE_NAME = "labyrinth-event-ocr.json"
    }
}

fun LabyrinthEventOcrDocument.resolve(
    workbook: LabyrinthWorkbookDocument,
): List<ResolvedEventTextSignature> {
    val table = requireNotNull(workbook.tables.firstOrNull { it.name == sourceTable }) {
        "Missing workbook table: $sourceTable"
    }
    val cells = table.rows.flatMap(LabyrinthWorkbookRow::cells).associateBy { it.address }
    return signatures.map { signature ->
        val sourceText = requireNotNull(cells[signature.sourceCell]?.value) {
            "Missing workbook cell: ${signature.sourceCell}"
        }.trim()
        val sections = sourceText
            .split(Regex("\\n\\s*\\n+"), limit = 2)
            .map(String::trim)
        val displayText = sections.firstOrNull().orEmpty()
        val translatedEffect = sections.getOrElse(1) { "" }
        ResolvedEventTextSignature(
            id = signature.id,
            sourceCell = signature.sourceCell,
            sourceText = sourceText,
            displayText = displayText,
            translatedEffect = translatedEffect,
            aliases = (listOf(displayText) + signature.extraAliases)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct(),
            tags = signature.tags,
            baseScore = signature.baseScore,
        )
    }
}
