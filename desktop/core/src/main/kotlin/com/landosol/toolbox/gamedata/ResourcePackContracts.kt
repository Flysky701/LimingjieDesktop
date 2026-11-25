package com.landosol.toolbox.gamedata

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import java.io.File

data class ResourcePackManifest(
    val packId: String,
    val version: String,
    val files: List<ResourcePackFile>,
)

data class ResourcePackFile(
    val relativePath: String,
    val sha256: String? = null,
    val required: Boolean = true,
)

/** JSON manifest format used by assets and future downloaded resource packs. */
@Serializable
data class ResourcePackDocument(
    val packId: String = "",
    val version: String = "",
    val files: List<String> = emptyList(),
    val status: String = "production",
    val schemaVersion: Int = 1,
    val note: String? = null,
)

@Serializable
data class ResourceReferences(
    val characterIds: List<String> = emptyList(),
    val relicIds: List<String> = emptyList(),
    val bossIds: List<String> = emptyList(),
    val eventIds: List<String> = emptyList(),
)

@Serializable
data class CharacterResource(
    val id: String = "",
    val name: String = "",
    /** Official mainland-CN display name; blank keeps legacy resource packs compatible. */
    val officialName: String = "",
    val aliases: List<String> = emptyList(),
    val available: Boolean = true,
    val tags: List<String> = emptyList(),
    val rarity: Int = 0,
    val role: String = "",
    val attackType: String = "",
    val score: Int = 0,
    val notes: String = "",
    val references: ResourceReferences = ResourceReferences(),
) {
    /** Preferred name for UI, logs, saved teams, and recognition overlays. */
    val displayName: String
        get() = officialName.ifBlank { name }
}

@Serializable
data class RelicResource(
    val id: String = "",
    val name: String = "",
    val tags: List<String> = emptyList(),
    val rarity: String = "",
    val effect: String = "",
    val mark: String = "",
    val markBonus: String = "",
    val unlockCondition: String = "",
    val baseScore: Int = 0,
    val price: Int = 0,
    val notes: String = "",
    val references: ResourceReferences = ResourceReferences(),
)

@Serializable
data class BossResource(
    val id: String = "",
    val name: String = "",
    val area: Int = 0,
    val weaknessTags: List<String> = emptyList(),
    val requiredTags: List<String> = emptyList(),
    val forbiddenTags: List<String> = emptyList(),
    val notes: String = "",
    val references: ResourceReferences = ResourceReferences(),
)

@Serializable
data class EventChoiceResource(
    val id: String = "",
    val name: String = "",
    /** Left-to-right option index on the event page. */
    val slot: Int = 0,
    /** Game-visible reward/effect text used by the OCR discriminator and recommendation model. */
    val description: String = "",
    val conditionType: Int = 0,
    val conditionValue: Int = 0,
    val score: Int = 0,
    val risk: String = "unknown",
    val references: ResourceReferences = ResourceReferences(),
)

@Serializable
data class EventResource(
    val id: String = "",
    val type: String = "event",
    val name: String = "",
    val area: Int = 0,
    val choices: List<EventChoiceResource> = emptyList(),
    val references: ResourceReferences = ResourceReferences(),
)

data class ResourcePackData(
    val manifest: ResourcePackDocument,
    val characters: List<CharacterResource>,
    val relics: List<RelicResource>,
    val bosses: List<BossResource>,
    val events: List<EventResource>,
    val workbook: LabyrinthWorkbookDocument? = null,
    val icons: GameIconPackDocument? = null,
    val eventOcr: LabyrinthEventOcrDocument? = null,
    val resolvedEventSignatures: List<ResolvedEventTextSignature> = emptyList(),
)

sealed interface ResourcePackLoadResult {
    data class Success(val data: ResourcePackData) : ResourcePackLoadResult
    data class Failure(val reasons: List<String>) : ResourcePackLoadResult
}

class ResourcePackDataValidator {
    fun validate(data: ResourcePackData): List<String> = buildList {
        val manifest = data.manifest
        if (manifest.schemaVersion != CURRENT_SCHEMA_VERSION) {
            add("不支持的资源包 Schema 版本：${manifest.schemaVersion}")
        }
        if (manifest.packId.isBlank()) add("资源包 ID 为空")
        if (manifest.version.isBlank()) add("资源包版本为空")
        validateIds("角色", data.characters.map(CharacterResource::id), ::add)
        validateIds("遗物", data.relics.map(RelicResource::id), ::add)
        validateIds("Boss", data.bosses.map(BossResource::id), ::add)
        validateIds("事件", data.events.map(EventResource::id), ::add)
        data.characters.forEach { character ->
            if (character.name.isBlank()) add("角色 ${character.id} 名称为空")
            if (character.aliases.any(String::isBlank)) add("角色 ${character.id} 存在空别名")
            val officialName = character.officialName.takeUnless {
                it.normalizedCharacterName() == character.name.normalizedCharacterName()
            }.orEmpty()
            val duplicateNames = (listOf(character.name, officialName) + character.aliases)
                .map(String::normalizedCharacterName)
                .filter(String::isNotEmpty)
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            if (duplicateNames.isNotEmpty()) add("角色 ${character.id} 存在重复名称或别名")
            if (character.rarity !in 0..7) add("角色 ${character.id} 稀有度超出范围")
            if (character.score < 0) add("角色 ${character.id} 评分不能为负数")
        }
        data.relics.forEach { relic ->
            if (relic.baseScore < 0) add("遗物 ${relic.id} 评分不能为负数")
            if (relic.price < 0) add("遗物 ${relic.id} 价格不能为负数")
        }
        data.bosses.forEach { boss ->
            if (boss.area <= 0) add("Boss ${boss.id} 区域必须大于 0")
        }
        data.events.forEach { event ->
            if (event.area <= 0) add("事件 ${event.id} 区域必须大于 0")
            validateIds("事件 ${event.id} 选项", event.choices.map(EventChoiceResource::id), ::add)
        }
        val knownIds = KnownResourceIds(
            characters = data.characters.mapTo(mutableSetOf(), CharacterResource::id),
            relics = data.relics.mapTo(mutableSetOf(), RelicResource::id),
            bosses = data.bosses.mapTo(mutableSetOf(), BossResource::id),
            events = data.events.mapTo(mutableSetOf(), EventResource::id),
        )
        data.characters.forEach { validateReferences("角色 ${it.id}", it.references, knownIds, ::add) }
        data.relics.forEach { validateReferences("遗物 ${it.id}", it.references, knownIds, ::add) }
        data.bosses.forEach { validateReferences("Boss ${it.id}", it.references, knownIds, ::add) }
        data.events.forEach { event ->
            validateReferences("事件 ${event.id}", event.references, knownIds, ::add)
            event.choices.forEach { choice ->
                validateReferences("事件 ${event.id} 选项 ${choice.id}", choice.references, knownIds, ::add)
            }
        }
    }

    private fun validateIds(
        kind: String,
        ids: List<String>,
        addReason: (String) -> Unit,
    ) {
        ids.forEachIndexed { index, id ->
            if (id.isBlank() || id == "TODO") addReason("$kind 第 ${index + 1} 项 ID 未填写")
        }
        ids.groupingBy { it }.eachCount().filter { (id, count) -> id.isNotBlank() && count > 1 }
            .keys.forEach { id -> addReason("$kind 存在重复 ID：$id") }
    }

    private fun validateReferences(
        owner: String,
        references: ResourceReferences,
        known: KnownResourceIds,
        addReason: (String) -> Unit,
    ) {
        findMissing(references.characterIds, known.characters).forEach {
            addReason("$owner 引用了不存在的角色 ID：$it")
        }
        findMissing(references.relicIds, known.relics).forEach {
            addReason("$owner 引用了不存在的遗物 ID：$it")
        }
        findMissing(references.bossIds, known.bosses).forEach {
            addReason("$owner 引用了不存在的 Boss ID：$it")
        }
        findMissing(references.eventIds, known.events).forEach {
            addReason("$owner 引用了不存在的事件 ID：$it")
        }
    }

    private fun findMissing(references: List<String>, known: Set<String>): Set<String> =
        references.filterNot(known::contains).toSet()

    private data class KnownResourceIds(
        val characters: Set<String>,
        val relics: Set<String>,
        val bosses: Set<String>,
        val events: Set<String>,
    )

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

class ResourcePackLoader(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val validator: ResourcePackDataValidator = ResourcePackDataValidator(),
) {
    fun load(root: File): ResourcePackLoadResult {
        if (!root.isDirectory) return ResourcePackLoadResult.Failure(listOf("资源包目录不存在"))
        return runCatching {
            val manifest = decode<ResourcePackDocument>(root, "manifest.json")
            val fileNames = manifest.files.map { it.replace('\\', '/') }
            val pathReasons = validatePaths(fileNames)
            if (pathReasons.isNotEmpty()) return ResourcePackLoadResult.Failure(pathReasons)
            val missing = fileNames.filter { !File(root, it).isFile }
            if (missing.isNotEmpty()) {
                return ResourcePackLoadResult.Failure(missing.map { "缺少资源文件：$it" })
            }
            if ("vision.json" in fileNames) {
                when (val vision = VisionPackLoader(json).load(root)) {
                    is VisionPackLoadResult.Success -> Unit
                    is VisionPackLoadResult.Failure -> return ResourcePackLoadResult.Failure(vision.reasons)
                }
            }
            val workbook = if ("labyrinth-workbook.json" in fileNames) {
                when (val parsed = LabyrinthWorkbookLoader(json).load(root)) {
                    is LabyrinthWorkbookLoadResult.Success -> parsed.document
                    is LabyrinthWorkbookLoadResult.Failure -> return ResourcePackLoadResult.Failure(parsed.reasons)
                }
            } else {
                null
            }
            val characters = decodeCategory<CharacterResource>(root, fileNames, "characters")
            val relics = decodeCategory<RelicResource>(root, fileNames, "relics")
            val bosses = decodeCategory<BossResource>(root, fileNames, "bosses")
            val events = decodeCategory<EventResource>(root, fileNames, "events")
            val eventOcr = if (LabyrinthEventOcrLoader.FILE_NAME in fileNames) {
                val sourceWorkbook = workbook
                    ?: return ResourcePackLoadResult.Failure(
                        listOf("事件 OCR 资源要求同时声明 labyrinth-workbook.json"),
                    )
                when (val parsed = LabyrinthEventOcrLoader(json).load(root, sourceWorkbook)) {
                    is LabyrinthEventOcrLoadResult.Success -> parsed
                    is LabyrinthEventOcrLoadResult.Failure ->
                        return ResourcePackLoadResult.Failure(parsed.reasons)
                }
            } else {
                null
            }
            val icons = if ("icons.json" in fileNames) {
                when (
                    val parsed = GameIconPackLoader(json).load(
                        root = root,
                        knownCharacterIds = characters.mapTo(mutableSetOf(), CharacterResource::id),
                        knownRelicIds = relics.mapTo(mutableSetOf(), RelicResource::id),
                    )
                ) {
                    is GameIconPackLoadResult.Success -> {
                        val referencedFiles = (
                            parsed.document.characterIcons.map(GameIconAsset::file) +
                                parsed.document.relicIcons.map(GameIconAsset::file)
                            ).toSet()
                        val declaredFiles = fileNames.toSet()
                        val iconInventoryReasons = buildList {
                            referencedFiles.filterNot(declaredFiles::contains).forEach {
                                add("图标文件未在资源包清单声明：$it")
                            }
                            declaredFiles.filter { it.startsWith("icons/") && it !in referencedFiles }.forEach {
                                add("资源包图标未在图标索引中引用：$it")
                            }
                        }
                        if (iconInventoryReasons.isNotEmpty()) {
                            return ResourcePackLoadResult.Failure(iconInventoryReasons)
                        }
                        parsed.document
                    }
                    is GameIconPackLoadResult.Failure -> return ResourcePackLoadResult.Failure(parsed.reasons)
                }
            } else {
                null
            }
            val data = ResourcePackData(
                manifest = manifest,
                characters = characters,
                relics = relics,
                bosses = bosses,
                events = events,
                workbook = workbook,
                icons = icons,
                eventOcr = eventOcr?.document,
                resolvedEventSignatures = eventOcr?.resolvedSignatures.orEmpty(),
            )
            val validation = validator.validate(data)
            if (validation.isNotEmpty()) ResourcePackLoadResult.Failure(validation)
            else ResourcePackLoadResult.Success(data)
        }.getOrElse { error ->
            ResourcePackLoadResult.Failure(listOf("资源包 JSON 无法解析：${error.message ?: "未知错误"}"))
        }
    }

    private inline fun <reified T> decode(root: File, fileName: String): T {
        val file = File(root, fileName)
        require(file.isFile) { "缺少资源文件：$fileName" }
        return json.decodeFromString(file.readText())
    }

    private inline fun <reified T> decodeCategory(root: File, files: List<String>, category: String): List<T> {
        val matches = files.filter { it.substringAfterLast('/').startsWith("$category.") }
        require(matches.size == 1) {
            if (matches.isEmpty()) "缺少资源类别：$category" else "资源类别文件重复：$category"
        }
        val fileName = matches.single()
        val document = decode<JsonObject>(root, fileName)
        val schemaVersion = document["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: error("资源类别 Schema 版本缺失：$category")
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "不支持的资源类别 Schema 版本：$category=$schemaVersion"
        }
        val values = document[category]?.jsonArray ?: error("资源类别字段缺失：$category")
        return json.decodeFromJsonElement(ListSerializer(serializer<T>()), values)
    }

    private fun validatePaths(paths: List<String>): List<String> = buildList {
        if (paths.isEmpty()) add("资源包文件清单为空")
        if (paths.size != paths.toSet().size) add("资源包文件清单存在重复路径")
        paths.forEach { path ->
            if (path.isUnsafeResourcePath()) add("资源包路径不安全")
        }
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

sealed interface ResourcePackValidation {
    data object Valid : ResourcePackValidation
    data class Invalid(val reasons: List<String>) : ResourcePackValidation
}

class ResourcePackValidator {
    fun validate(root: File, manifest: ResourcePackManifest): ResourcePackValidation {
        val reasons = buildList {
            if (!root.isDirectory) add("资源包目录不存在")
            if (manifest.packId.isBlank()) add("资源包 ID 为空")
            if (manifest.version.isBlank()) add("资源包版本为空")
            if (manifest.files.isEmpty()) add("资源包文件清单为空")

            val normalized = manifest.files.map { it.relativePath.replace('\\', '/') }
            if (normalized.any(String::isUnsafeResourcePath)) {
                add("资源包路径不安全")
            }
            if (normalized.size != normalized.toSet().size) add("资源包文件清单存在重复路径")
            manifest.files.forEach { file ->
                val target = File(root, file.relativePath)
                if (file.required && !target.isFile) add("缺少资源文件：${file.relativePath}")
                if (target.isFile && file.sha256 != null && sha256(target) != file.sha256.lowercase()) {
                    add("资源文件校验失败：${file.relativePath}")
                }
            }
        }
        return if (reasons.isEmpty()) ResourcePackValidation.Valid else ResourcePackValidation.Invalid(reasons)
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal fun String.isUnsafeResourcePath(): Boolean {
    val normalized = replace('\\', '/')
    val segments = normalized.split('/')
    return normalized.isBlank() ||
        normalized.startsWith('/') ||
        DRIVE_PREFIX.containsMatchIn(normalized) ||
        segments.any { it == "." || it == ".." }
}

private val DRIVE_PREFIX = Regex("^[A-Za-z]:")

internal fun String.normalizedCharacterName(): String = trim().lowercase(java.util.Locale.ROOT)
