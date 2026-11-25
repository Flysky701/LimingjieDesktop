package com.landosol.toolbox.protocol.bilibili

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class BilibiliGameProtocolGateway(
    private val client: OkHttpClient,
    private val bootstrapEndpoint: HttpUrl,
    private val profile: GameProtocolProfile,
    private val codec: MessagePackCodec = MessagePackCodec(),
    private val crypto: GameProtocolCrypto = GameProtocolCrypto(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val campaignRandom: SecureRandom = SecureRandom(),
) : BilibiliGameGateway {
    override suspend fun loginAndLoadProfile(
        sdkSession: SdkSession,
        deviceSeed: String,
        captcha: CaptchaSolution?,
    ): GameLoginResult = withContext(Dispatchers.IO) {
        val state = ClientState(
            server = bootstrapEndpoint,
            headers = profile.headers.toMutableMap().apply { put("DEVICE-ID", md5Hex(deviceSeed)) },
        )
        try {
            discoverServer(state)
            loadMaintenance(state)
            val login = encryptedRequest(
                state = state,
                path = "tool/sdk_login",
                stage = Stage.SDK_LOGIN,
                fields = linkedMapOf(
                    "uid" to sdkSession.uid,
                    "access_key" to sdkSession.accessKey,
                    "platform" to "2",
                    "channel_id" to "1",
                    "challenge" to captcha?.challenge,
                    "validate" to captcha?.validate,
                    "seccode" to captcha?.let { "${it.validate}|jordan" },
                    "captcha_type" to captcha?.let { "1" },
                    "image_token" to if (captcha == null) null else "",
                    "captcha_code" to if (captcha == null) null else "",
                ),
            )
            if (login.boolean("is_risk") == true) return@withContext GameLoginResult.RiskRequired

            val gameStart = encryptedRequest(
                state = state,
                path = "check/game_start",
                stage = Stage.GAME_START,
                fields = linkedMapOf(
                    "apptype" to 0L,
                    "campaign_data" to "",
                    "campaign_user" to (campaignRandom.nextInt(100_001) and -2).toLong(),
                ),
            )
            if (gameStart.boolean("now_tutorial") != true) {
                return@withContext GameLoginResult.Rejected("账号尚未完成新手教程")
            }

            val load = encryptedRequest(
                state = state,
                path = "load/index",
                stage = Stage.LOAD_INDEX,
                fields = linkedMapOf("carrier" to "OPPO"),
            )
            val user = load.map("user_info") ?: error("账号信息缺失")
            val viewerId = user.long("viewer_id") ?: state.viewerId.takeIf { it > 0 } ?: error("游戏 UID 缺失")
            val userName = user.string("user_name").orEmpty().ifBlank { "未命名玩家" }
            val teamLevel = user.long("team_level")?.toInt() ?: 0
            val accountProfile = GameAccountProfile(viewerId, userName, teamLevel, profile.appVersion)
            GameLoginResult.Success(accountProfile, ProtocolSession(state, accountProfile))
        } catch (failure: GameApiFailure) {
            val message = failure.message.orEmpty().ifBlank { "游戏服拒绝请求" }.take(MAX_MESSAGE_LENGTH)
            when {
                message.contains("维护") -> GameLoginResult.Maintenance(message)
                failure.stage == Stage.SDK_LOGIN -> GameLoginResult.SessionRejected(message)
                else -> GameLoginResult.Rejected(message)
            }
        } catch (failure: IOException) {
            GameLoginResult.NetworkFailure("游戏服务器网络请求失败")
        } catch (failure: Throwable) {
            GameLoginResult.ProtocolFailure(
                failure.message.orEmpty().ifBlank { "游戏服务器响应无法解析" }.take(MAX_MESSAGE_LENGTH),
            )
        }
    }

    private fun discoverServer(state: ClientState) {
        val envelope = plainRequest(state, "source_ini/index?format=json", Stage.SERVER_DISCOVERY)
        val servers = envelope.data["server"]?.jsonArray.orEmpty().mapNotNull { item ->
            item.jsonPrimitive.content.trim().replace("\t", "").takeIf(String::isNotBlank)
        }
        require(servers.isNotEmpty()) { "游戏服务器列表为空" }
        val first = servers.first()
        state.server = (if (first.startsWith("http://") || first.startsWith("https://")) first else "https://$first")
            .toHttpUrl()
    }

    private fun loadMaintenance(state: ClientState) {
        val envelope = plainRequest(
            state,
            "source_ini/get_maintenance_status?format=json",
            Stage.MAINTENANCE,
        )
        envelope.data.string("required_manifest_ver")?.takeIf(String::isNotBlank)?.let {
            state.headers["MANIFEST-VER"] = it
        }
        envelope.data.string("res_ver")?.takeIf(String::isNotBlank)?.let {
            state.headers["RES-VER"] = it
        }
        val message = envelope.data.string("maintenance_message")
        if (!message.isNullOrBlank()) throw GameApiFailure(Stage.MAINTENANCE, message)
        if (envelope.data.string("login_stop")?.toIntOrNull() == 1) {
            throw GameApiFailure(Stage.MAINTENANCE, "游戏服务器暂时停止登录")
        }
    }

    private fun plainRequest(state: ClientState, path: String, stage: Stage): JsonEnvelope {
        val requestJson = buildJsonObject { put("viewer_id", state.viewerId.toString()) }
        val body = json.encodeToString(JsonObject.serializer(), requestJson)
            .toRequestBody(JSON_MEDIA_TYPE)
        val responseBytes = execute(state, path, body)
        val root = json.parseToJsonElement(responseBytes.toString(Charsets.UTF_8)).jsonObject
        val headers = root["data_headers"]?.jsonObject ?: error("响应头缺失")
        updateState(state, headers.string("sid"), headers.string("request_id"), headers.string("viewer_id"))
        val data = root["data"]?.jsonObject ?: error("响应数据缺失")
        data["server_error"]?.takeUnless { it.toString() == "null" }?.jsonObject?.let { serverError ->
            throw GameApiFailure(stage, serverError.string("message").orEmpty())
        }
        return JsonEnvelope(headers, data)
    }

    private fun encryptedRequest(
        state: ClientState,
        path: String,
        stage: Stage,
        fields: LinkedHashMap<String, Any?>,
    ): Map<Any?, Any?> {
        val key = crypto.createKey()
        val requestMap = LinkedHashMap<String, Any?>(fields.size + 1).apply {
            putAll(fields)
            put("viewer_id", crypto.encryptViewerId(state.viewerId, key))
        }
        val encrypted = crypto.encryptRequest(codec.encode(requestMap), key).toRequestBody(BINARY_MEDIA_TYPE)
        val responseBytes = execute(state, path, encrypted)
        val root = codec.decode(crypto.decryptResponse(responseBytes)).asMap()
        val headers = root.map("data_headers") ?: error("响应头缺失")
        updateState(state, headers.string("sid"), headers.string("request_id"), headers.string("viewer_id"))
        val data = root.map("data") ?: error("响应数据缺失")
        data.map("server_error")?.let { serverError ->
            throw GameApiFailure(stage, serverError.string("message").orEmpty())
        }
        return data
    }

    private fun execute(state: ClientState, path: String, body: okhttp3.RequestBody): ByteArray {
        val url = state.server.resolve(path) ?: error("无效的游戏服务器地址")
        val builder = Request.Builder().url(url).post(body)
        state.headers.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) builder.header(name, value)
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.bytes() ?: throw IOException("Empty response")
        }
    }

    private fun updateState(state: ClientState, sid: String?, requestId: String?, viewerId: String?) {
        sid?.takeIf(String::isNotBlank)?.let { state.headers["SID"] = md5Hex("${it}c!SID!n") }
        requestId?.takeIf(String::isNotBlank)?.let { state.headers["REQUEST-ID"] = it }
        viewerId?.toLongOrNull()?.takeIf { it > 0 }?.let { state.viewerId = it }
    }

    private inner class ProtocolSession(
        private val state: ClientState,
        override val profile: GameAccountProfile,
    ) : BilibiliGameSession {
        private val mutex = Mutex()

        override suspend fun request(
            path: String,
            fields: LinkedHashMap<String, Any?>,
        ): GameSessionResult = withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val data = encryptedRequest(state, path, Stage.SESSION_REQUEST, fields)
                    GameSessionResult.Success(
                        data.entries.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }.toMap(),
                    )
                } catch (failure: GameApiFailure) {
                    GameSessionResult.Rejected(
                        failure.message.orEmpty().ifBlank { "游戏服务器拒绝请求" }.take(MAX_MESSAGE_LENGTH),
                    )
                } catch (failure: IOException) {
                    val detail = failure.message.orEmpty().take(MAX_NETWORK_DETAIL_LENGTH)
                    GameSessionResult.NetworkFailure(
                        if (detail.isBlank()) "游戏服务器网络请求失败" else "游戏服务器网络请求失败：$detail",
                    )
                } catch (failure: Throwable) {
                    GameSessionResult.ProtocolFailure(
                        failure.message.orEmpty().ifBlank { "游戏服务器响应无法解析" }.take(MAX_MESSAGE_LENGTH),
                    )
                }
            }
        }
    }

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class ClientState(
        var server: HttpUrl,
        var viewerId: Long = 0,
        val headers: MutableMap<String, String>,
    )

    private data class JsonEnvelope(
        val headers: JsonObject,
        val data: JsonObject,
    )

    private class GameApiFailure(
        val stage: Stage,
        message: String,
    ) : RuntimeException(message)

    private enum class Stage {
        SERVER_DISCOVERY,
        MAINTENANCE,
        SDK_LOGIN,
        GAME_START,
        LOAD_INDEX,
        SESSION_REQUEST,
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
        const val MAX_MESSAGE_LENGTH = 200
        const val MAX_NETWORK_DETAIL_LENGTH = 80
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<Any?, Any?> = this as? Map<Any?, Any?> ?: error("Expected MessagePack map")

@Suppress("UNCHECKED_CAST")
private fun Map<Any?, Any?>.map(key: String): Map<Any?, Any?>? = this[key] as? Map<Any?, Any?>

private fun Map<Any?, Any?>.string(key: String): String? = when (val value = this[key]) {
    is String -> value
    is Number -> value.toString()
    else -> null
}

private fun Map<Any?, Any?>.long(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

private fun Map<Any?, Any?>.boolean(key: String): Boolean? = when (val value = this[key]) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    else -> null
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
