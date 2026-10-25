package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord
import com.landosol.toolbox.desktop.platform.DesktopSettings
import com.landosol.toolbox.labyrinth.LabyrinthRerollSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.util.concurrent.TimeUnit

class SharedDataClient(private val endpoint: SharedDataEndpoint) : SharedDataAccess {
    override val authorityId: String get() = endpoint.authorityId
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    private suspend fun rpc(op: String, args: JsonObject = JsonObject(emptyMap())): JsonObject = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("op", op); put("args", args); put("authorityId", authorityId) }
        val request = Request.Builder().url("http://127.0.0.1:${endpoint.port}/shared-data")
            .header("X-Limingjie-Shared-Token", endpoint.token)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "共享数据服务不可用（HTTP ${response.code}）" }
            val document = Json.parseToJsonElement(checkNotNull(response.body).string()).jsonObject
            document["error"]?.jsonPrimitive?.contentOrNull?.let { error(it) }
            document["result"]?.jsonObject ?: error("共享数据服务返回无效结果")
        }
    }

    override suspend fun taskData(accountId: Long): SharedTaskData =
        ControlModelCodec.decode(rpc("runtime.taskData", buildJsonObject { put("accountId", accountId) }).getValue("value"))

    override suspend fun settings(): DesktopSettings = ControlModelCodec.decode(rpc("runtime.settings").getValue("value"))

    override suspend fun updateGameUid(accountId: Long, uid: String) {
        rpc("runtime.uid", buildJsonObject { put("accountId", accountId); put("uid", uid) })
    }

    override suspend fun saveReroll(accountId: Long, value: LabyrinthRerollSettings) {
        rpc("runtime.reroll", buildJsonObject { put("accountId", accountId); put("value", ControlModelCodec.encode(value)) })
    }

    override suspend fun putRecord(sessionId: String, deviceName: String, accountId: Long?, record: DesktopRunRecord) {
        rpc("runtime.record", buildJsonObject {
            put("sessionId", sessionId); put("deviceName", deviceName); put("accountId", accountId)
            put("value", ControlModelCodec.encode(record))
        })
    }
}
