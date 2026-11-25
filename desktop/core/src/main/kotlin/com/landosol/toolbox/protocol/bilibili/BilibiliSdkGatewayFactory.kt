package com.landosol.toolbox.protocol.bilibili

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

object BilibiliSdkGatewayFactory {
    fun create(context: Context): BilibiliSdkGateway {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        return BilibiliSdkHttpGateway(
            client = client,
            endpoint = SDK_ENDPOINT.toHttpUrl(),
            requestProfile = AndroidBilibiliSdkRequestProfileFactory(context.applicationContext).create(),
            signer = BilibiliSdkSigner(SDK_SIGN_SECRET),
            json = Json {
                ignoreUnknownKeys = true
            },
            epochSeconds = { System.currentTimeMillis() / 1_000L },
        )
    }

    private const val SDK_ENDPOINT = "https://line1-sdk-center-login-sh.biligame.net/"
    private const val SDK_SIGN_SECRET = "fe8aac4e02f845b8ad67c427d48bfaf1"
}
