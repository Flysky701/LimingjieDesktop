package com.landosol.toolbox.protocol.bilibili

data class BilibiliSdkRequestProfile(
    val commonFields: LinkedHashMap<String, String>,
) {
    fun rsaFields(): LinkedHashMap<String, String> = LinkedHashMap(commonFields)

    fun captchaFields(): LinkedHashMap<String, String> = LinkedHashMap(commonFields)

    fun loginFields(
        credentials: SdkLoginCredentials,
        encryptedPassword: String,
        captcha: CaptchaSolution?,
    ): LinkedHashMap<String, String> = LinkedHashMap(commonFields).apply {
        this["access_key"] = ""
        this["uid"] = ""
        this["user_id"] = credentials.loginId
        this["pwd"] = encryptedPassword
        this["gt_user_id"] = captcha?.gtUserId.orEmpty()
        this["challenge"] = captcha?.challenge.orEmpty()
        this["validate"] = captcha?.validate.orEmpty()
        this["seccode"] = captcha?.let { "${it.validate}|jordan" }.orEmpty()
        this["captcha_type"] = "1"
    }

    companion object {
        fun fixture(): BilibiliSdkRequestProfile = BilibiliSdkRequestProfile(
            linkedMapOf(
                "app_id" to "fixture-app",
                "game_id" to "fixture-game",
                "sdk_ver" to "fixture-sdk",
            ),
        )
    }
}
