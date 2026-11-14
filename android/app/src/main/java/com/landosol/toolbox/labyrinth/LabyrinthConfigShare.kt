package com.landosol.toolbox.labyrinth

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Only these preference modules are portable; account identity and task state are never encoded. */
enum class LabyrinthConfigModule(val label: String) {
    STRATEGY("全局策略"),
    PERSONAL_SCORES("个人角色评分"),
    REROLL("刷开局设置"),
}

data class LabyrinthSharedConfiguration(
    val appVersion: String,
    val exportedAtMillis: Long,
    val strategy: LabyrinthStrategySettings? = null,
    val personalScores: Map<String, Int>? = null,
    val reroll: LabyrinthRerollSettings? = null,
) {
    val modules: Set<LabyrinthConfigModule> get() = buildSet {
        if (strategy != null) add(LabyrinthConfigModule.STRATEGY)
        if (personalScores != null) add(LabyrinthConfigModule.PERSONAL_SCORES)
        if (reroll != null) add(LabyrinthConfigModule.REROLL)
    }

    fun defaultSelection(): Set<LabyrinthConfigModule> = modules.filterTo(linkedSetOf()) {
        it != LabyrinthConfigModule.PERSONAL_SCORES || !personalScores.isNullOrEmpty()
    }

    fun replacement(
        current: LabyrinthConfigurationValues,
        selected: Set<LabyrinthConfigModule>,
        targetAccountId: Long?,
        confirmEmptyScores: Boolean,
    ): LabyrinthConfigurationValues {
        require(selected.isNotEmpty() && modules.containsAll(selected)) { "请选择分享码中包含的模块" }
        if (LabyrinthConfigModule.PERSONAL_SCORES in selected && personalScores.isNullOrEmpty()) {
            require(confirmEmptyScores) { "请确认清除自定义评分并恢复使用原评分" }
        }
        val scores = if (LabyrinthConfigModule.PERSONAL_SCORES in selected) personalScores!!
            else current.strategy.personalRoleScores
        val nextStrategy = (if (LabyrinthConfigModule.STRATEGY in selected) strategy!! else current.strategy)
            .copy(personalRoleScores = scores)
        val nextRerolls = if (LabyrinthConfigModule.REROLL in selected) {
            require(targetAccountId != null && targetAccountId > 0) { "请选择本机目标账号" }
            current.rerolls + (targetAccountId to reroll!!)
        } else current.rerolls
        return LabyrinthConfigurationValues(nextStrategy, nextRerolls)
    }

    fun unknownRoleIds(known: Set<String>): Set<String> = buildSet {
        addAll(personalScores.orEmpty().keys)
        strategy?.openingRosters?.values?.forEach { slots -> slots.forEach(::addAll) }
    } - known
}

/** LABY1 is one strict, bounded GZIP member encoded as unpadded Base64URL. */
object LabyrinthConfigShareCodec {
    const val PREFIX = "LABY1."
    const val MAX_INPUT_LENGTH = 262_144
    const val MAX_CODE_LENGTH = 131_072
    const val MAX_JSON_BYTES = 524_288
    private const val MAX_DEPTH = 16
    private const val MAX_SCORES = 2_048
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
    private val strategyTemplate by lazy {
        JsonObject(json.encodeToJsonElement(LabyrinthStrategySettings()).jsonObject - "personalRoleScores")
    }
    private val rerollTemplate by lazy {
        json.encodeToJsonElement(LabyrinthRerollSettings()).jsonObject
    }

    @Serializable
    private data class Envelope(
        val format: String,
        val schemaVersion: Int,
        val appVersion: String,
        val exportedAtMillis: Long,
        val modules: JsonObject,
    )

    fun create(
        values: LabyrinthConfigurationValues,
        selected: Set<LabyrinthConfigModule>,
        accountId: Long?,
        appVersion: String,
        nowMillis: Long,
    ): LabyrinthSharedConfiguration {
        require(selected.isNotEmpty()) { "请至少选择一个导出模块" }
        if (LabyrinthConfigModule.REROLL in selected) require(accountId != null && accountId > 0) { "请先选择当前账号" }
        return LabyrinthSharedConfiguration(
            appVersion = appVersion,
            exportedAtMillis = nowMillis,
            strategy = values.strategy.copy(personalRoleScores = emptyMap()).takeIf { LabyrinthConfigModule.STRATEGY in selected },
            personalScores = values.strategy.personalRoleScores.takeIf { LabyrinthConfigModule.PERSONAL_SCORES in selected },
            reroll = (values.rerolls[accountId] ?: LabyrinthRerollSettings()).takeIf { LabyrinthConfigModule.REROLL in selected },
        )
    }

    fun encode(configuration: LabyrinthSharedConfiguration): String {
        validate(configuration)
        val modules = linkedMapOf<String, JsonElement>()
        configuration.strategy?.let { modules["strategy"] = JsonObject(json.encodeToJsonElement(it).jsonObject - "personalRoleScores") }
        configuration.personalScores?.let { modules["personalScores"] = json.encodeToJsonElement(it) }
        configuration.reroll?.let { modules["reroll"] = json.encodeToJsonElement(it) }
        val text = json.encodeToString(Envelope("labyrinth-config", 1, configuration.appVersion, configuration.exportedAtMillis, JsonObject(modules)))
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "配置内容过大，无法生成分享码" }
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
        return (PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)).also {
            require(it.length <= MAX_CODE_LENGTH) { "分享码过长，请减少导出模块" }
        }
    }

    fun decode(input: String): LabyrinthSharedConfiguration {
        require(input.length <= MAX_INPUT_LENGTH) { "分享码过长" }
        val code = input.filterNot { it == ' ' || it == '\n' || it == '\r' || it == '\t' }
        require(code.startsWith(PREFIX)) {
            if (code.startsWith("LABY")) "不支持此分享码版本，请检查或升级应用" else "请输入以 LABY1. 开头的分享码"
        }
        require(code.length <= MAX_CODE_LENGTH) { "分享码过长" }
        val payload = code.substring(PREFIX.length)
        require(payload.isNotEmpty() && payload.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) {
            "分享码包含无效字符或内容不完整"
        }
        val compressed = try { Base64.getUrlDecoder().decode(payload) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("分享码编码损坏或内容不完整")
        }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(compressed) == payload) { "分享码编码不规范或已损坏" }
        val bytes = try { inflate(compressed) } catch (failure: IllegalArgumentException) { throw failure }
            catch (_: Exception) { throw IllegalArgumentException("分享码压缩内容损坏或不完整") }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw IllegalArgumentException("分享码文本编码无效") }
        checkStructure(text)
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            ?: throw IllegalArgumentException("分享码不是有效的配置对象")
        require(root.keys == setOf("format", "schemaVersion", "appVersion", "exportedAtMillis", "modules")) { "分享码头信息缺失或不受支持" }
        requirePrimitive(root["format"], true)
        requirePrimitive(root["schemaVersion"], false)
        requirePrimitive(root["appVersion"], true)
        requirePrimitive(root["exportedAtMillis"], false)
        val envelope = decodeValue<Envelope>(root)
        require(envelope.format == "labyrinth-config" && envelope.schemaVersion == 1) { "不支持此配置格式，请升级应用" }
        require(envelope.modules.isNotEmpty() && envelope.modules.keys.all { it in setOf("strategy", "personalScores", "reroll") }) { "分享码未包含有效模块，或包含不支持的模块" }
        val strategy = envelope.modules["strategy"]?.let { element ->
            val fields = requireFields(element, strategyTemplate)
            validateStrategyTypes(fields)
            decodeValue<LabyrinthStrategySettings>(fields)
        }
        val scores = envelope.modules["personalScores"]?.let { element ->
            val fields = element as? JsonObject ?: throw IllegalArgumentException("个人评分模块必须是评分列表")
            require(fields.size <= MAX_SCORES) { "个人评分数量超出限制" }
            fields.values.forEach { requirePrimitive(it, false) }
            decodeValue<Map<String, Int>>(fields)
        }
        val reroll = envelope.modules["reroll"]?.let { element ->
            val supplied = element as? JsonObject ?: throw IllegalArgumentException("刷开局模块结构无效")
            val legacyKeys = rerollTemplate.keys - "customNodePlan"
            val compatible = if (supplied.keys == legacyKeys) {
                require(supplied["routeEvaluationMode"] != JsonPrimitive(LabyrinthRouteEvaluationMode.CUSTOM_NODES.name)) {
                    "自定义模式缺少节点配置"
                }
                JsonObject(supplied + ("customNodePlan" to rerollTemplate.getValue("customNodePlan")))
            } else supplied
            val fields = requireFields(compatible, rerollTemplate)
            requireFields(fields.getValue("customNodePlan"), rerollTemplate.getValue("customNodePlan") as JsonObject)
            listOf("area3BossIds", "area5BossIds").forEach { key ->
                requireArray(fields[key]).forEach { requirePrimitive(it, false) }
            }
            decodeValue<LabyrinthRerollSettings>(fields)
        }
        return LabyrinthSharedConfiguration(envelope.appVersion, envelope.exportedAtMillis, strategy, scores, reroll).also(::validate)
    }

    private fun validate(configuration: LabyrinthSharedConfiguration) {
        require(configuration.appVersion.isNotBlank() && configuration.appVersion.length <= 100 && configuration.appVersion.none(Char::isISOControl)) { "来源应用版本无效" }
        require(configuration.exportedAtMillis >= 0) { "导出时间无效" }
        require(configuration.modules.isNotEmpty()) { "分享码不包含任何配置模块" }
        configuration.strategy?.let { settings ->
            require(settings.personalRoleScores.isEmpty()) { "个人评分必须使用独立模块" }
            settings.validationError()?.let { throw IllegalArgumentException(it) }
            val catalog = LabyrinthCustomEventCatalog.cards.associate { it.runtimeName to it.choices.map { choice -> choice.runtimeName }.toSet() }
            require(settings.eventChoiceOverrides.all { (event, choice) -> choice in catalog[event].orEmpty() }) { "事件或分支不受当前版本支持" }
        }
        configuration.personalScores?.let { scores ->
            require(scores.size <= MAX_SCORES) { "个人评分数量超出限制" }
            LabyrinthStrategySettings(personalRoleScores = scores).validationError()?.let { throw IllegalArgumentException(it) }
        }
        configuration.reroll?.validationError()?.let { throw IllegalArgumentException(it) }
    }

    private fun requireFields(element: JsonElement, template: JsonObject): JsonObject {
        val fields = element as? JsonObject ?: throw IllegalArgumentException("配置模块结构无效")
        require(fields.keys == template.keys) { "配置模块字段缺失或不受当前版本支持，请使用兼容版本重新导出" }
        template.forEach { (key, sample) ->
            when (sample) {
                is JsonPrimitive -> requirePrimitive(fields[key], sample.isString)
                is JsonArray -> requireArray(fields[key])
                is JsonObject -> require(fields[key] is JsonObject) { "配置字段 $key 必须是对象" }
            }
        }
        return fields
    }

    private fun requirePrimitive(element: JsonElement?, string: Boolean) {
        require(element is JsonPrimitive && element.isString == string && (string || element.content != "null")) { "配置字段类型无效" }
    }

    private fun requireArray(element: JsonElement?): JsonArray {
        val array = element as? JsonArray ?: throw IllegalArgumentException("配置字段必须是列表")
        require(array.distinct().size == array.size) { "配置列表包含重复项" }
        return array
    }

    private fun validateStrategyTypes(fields: JsonObject) {
        listOf("relicMarkPriority", "specialEquipmentKinds").forEach { key ->
            requireArray(fields[key]).forEach { requirePrimitive(it, true) }
        }
        (fields.getValue("eventChoiceOverrides") as JsonObject).values.forEach { requirePrimitive(it, true) }
        (fields.getValue("openingRosters") as JsonObject).forEach { (guild, slots) ->
            require(guild.toIntOrNull()?.toString() == guild) { "开局方案的公会 ID 无效" }
            requireArray(slots).forEach { slot -> requireArray(slot).forEach { requirePrimitive(it, true) } }
        }
    }

    private inline fun <reified T> decodeValue(value: JsonElement): T = try {
        json.decodeFromJsonElement<T>(value)
    } catch (_: Exception) { throw IllegalArgumentException("配置字段类型、数值或枚举无效") }

    /** Rejects excessive depth and duplicate object keys before the JSON tree can discard information. */
    private fun checkStructure(text: String) {
        data class Frame(val objectFrame: Boolean, val keys: MutableSet<String> = hashSetOf(), var expectingKey: Boolean = true)
        val stack = mutableListOf<Frame>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> {
                    stack += Frame(text[index] == '{')
                    require(stack.size <= MAX_DEPTH) { "配置嵌套层数过多" }
                }
                '}', ']' -> { require(stack.isNotEmpty()) { "配置结构损坏" }; stack.removeAt(stack.lastIndex) }
                ',' -> stack.lastOrNull()?.let { if (it.objectFrame) it.expectingKey = true }
                '"' -> {
                    val start = index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    require(index < text.length) { "配置字符串不完整" }
                    stack.lastOrNull()?.let { frame ->
                        if (frame.objectFrame && frame.expectingKey) {
                            val key = try { json.decodeFromString<String>(text.substring(start, index + 1)) }
                                catch (_: Exception) { throw IllegalArgumentException("配置字段名无效") }
                            require(frame.keys.add(key)) { "配置包含重复字段，已拒绝导入" }
                            frame.expectingKey = false
                        }
                    }
                }
            }
            index++
        }
        require(stack.isEmpty()) { "配置结构不完整" }
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        require(bytes.size >= 18 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() && bytes[2] == 8.toByte() && bytes[3] == 0.toByte()) { "分享码压缩格式不受支持或已损坏" }
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes, 10, bytes.size - 10)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                require(output.size() + count <= MAX_JSON_BYTES) { "分享码解压后的内容过大" }
                if (count > 0) output.write(buffer, 0, count)
                else require(inflater.finished()) { "分享码压缩内容不完整或无效" }
            }
            require(inflater.remaining == 8) { "分享码包含额外内容或校验信息不完整" }
            val result = output.toByteArray()
            val trailer = bytes.size - 8
            require(CRC32().apply { update(result) }.value == unsignedInt(bytes, trailer) && result.size.toLong() == unsignedInt(bytes, trailer + 4)) { "分享码完整性校验失败" }
            return result
        } finally { inflater.end() }
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, index ->
        value or ((bytes[offset + index].toLong() and 0xff) shl (8 * index))
    }
}
