package com.landosol.toolbox.protocol.bilibili

import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class BilibiliSdkHttpGateway(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val requestProfile: BilibiliSdkRequestProfile,
    private val signer: BilibiliSdkSigner,
    private val json: Json,
    private val epochSeconds: () -> Long,
    private val passwordEncryptor: RsaPasswordEncryptor = RsaPasswordEncryptor(),
    private val dispatcher: CoroutineContext = Dispatchers.IO,
) : BilibiliSdkGateway {
    override suspend fun login(
        credentials: SdkLoginCredentials,
        captcha: CaptchaSolution?,
    ): SdkLoginResult = withContext(dispatcher) {
        try {
            val rsa = post<RsaResponse>(RSA_PATH, requestProfile.rsaFields())
            if (rsa.code != SUCCESS || rsa.hash.isBlank() || rsa.rsaKey.isBlank()) {
                return@withContext SdkLoginResult.ProtocolFailure("登录密钥协商失败")
            }
            val encryptedPassword = passwordEncryptor.encrypt(rsa.hash, credentials.password, rsa.rsaKey)
            val response = post<LoginResponse>(
                LOGIN_PATH,
                requestProfile.loginFields(credentials, encryptedPassword, captcha),
            )
            when {
                response.code == SUCCESS && !response.accessKey.isNullOrBlank() && response.uidValue().isNotBlank() ->
                    SdkLoginResult.Success(SdkSession(response.uidValue(), response.accessKey))
                response.code == CAPTCHA_REQUIRED || response.needCaptcha() -> SdkLoginResult.CaptchaRequired
                else -> SdkLoginResult.Rejected(response.code, response.safeMessage())
            }
        } catch (failure: IOException) {
            SdkLoginResult.NetworkFailure("网络连接失败")
        } catch (failure: Throwable) {
            SdkLoginResult.ProtocolFailure("登录响应无法解析")
        }
    }

    override suspend fun startCaptcha(): SdkCaptchaResult = withContext(dispatcher) {
        try {
            val response = post<CaptchaResponse>(CAPTCHA_PATH, requestProfile.captchaFields())
            if (
                response.code == SUCCESS &&
                response.gt.isNotBlank() &&
                response.challenge.isNotBlank() &&
                response.gtUserId.isNotBlank()
            ) {
                SdkCaptchaResult.Ready(
                    CaptchaChallenge(
                        gt = response.gt,
                        challenge = response.challenge,
                        gtUserId = response.gtUserId,
                        captchaType = response.captchaType,
                    ),
                )
            } else {
                SdkCaptchaResult.Rejected(response.code, response.safeMessage())
            }
        } catch (failure: IOException) {
            SdkCaptchaResult.NetworkFailure("验证码初始化网络失败")
        } catch (failure: Throwable) {
            SdkCaptchaResult.ProtocolFailure("验证码响应无法解析")
        }
    }

    private inline fun <reified T> post(path: String, fields: LinkedHashMap<String, String>): T {
        val signed = signer.withSignature(fields, epochSeconds())
        val body = FormBody.Builder().apply {
            signed.forEach { (name, value) -> add(name, value) }
        }.build()
        val url = requireNotNull(endpoint.resolve(path)) { "Invalid SDK endpoint" }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val payload = response.body?.string() ?: throw IOException("Empty response")
            return json.decodeFromString(payload)
        }
    }

    @Serializable
    private data class RsaResponse(
        val code: Int = -1,
        val hash: String = "",
        @SerialName("rsa_key") val rsaKey: String = "",
    )

    @Serializable
    private data class LoginResponse(
        val code: Int = -1,
        val uid: JsonElement? = null,
        @SerialName("access_key") val accessKey: String? = null,
        @SerialName("need_captch") val needCaptch: JsonElement? = null,
        val message: String? = null,
        @SerialName("server_message") val serverMessage: String? = null,
    ) {
        fun uidValue(): String = uid?.jsonPrimitive?.contentOrNull.orEmpty()

        fun needCaptcha(): Boolean = when (needCaptch?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "true", "1" -> true
            else -> false
        }

        fun safeMessage(): String = (message ?: serverMessage).orEmpty().take(MAX_MESSAGE_LENGTH)
    }

    @Serializable
    private data class CaptchaResponse(
        val code: Int = -1,
        val gt: String = "",
        val challenge: String = "",
        @SerialName("gt_user_id") val gtUserId: String = "",
        @SerialName("captcha_type") val captchaType: String = "1",
        val message: String? = null,
        @SerialName("server_message") val serverMessage: String? = null,
    ) {
        fun safeMessage(): String = (message ?: serverMessage).orEmpty().take(MAX_MESSAGE_LENGTH)
    }

    private companion object {
        const val SUCCESS = 0
        const val CAPTCHA_REQUIRED = 200000
        const val RSA_PATH = "api/client/rsa"
        const val LOGIN_PATH = "api/client/login"
        const val CAPTCHA_PATH = "api/client/start_captcha"
        const val USER_AGENT = "Mozilla/5.0 BSGameSDK"
        const val MAX_MESSAGE_LENGTH = 200
    }
}
