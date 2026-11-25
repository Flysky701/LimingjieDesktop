package com.landosol.toolbox.protocol.bilibili

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object GeetestPageBuilder {
    fun build(challenge: CaptchaChallenge): String {
        val gt = javascriptString(challenge.gt)
        val challengeValue = javascriptString(challenge.challenge)
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1" />
              <style>
                body { font-family: sans-serif; padding: 20px; background: #fff; color: #222; }
                #status { margin-bottom: 16px; }
                #captcha { min-height: 120px; }
              </style>
              <script src="https://static.geetest.com/static/tools/gt.js"></script>
            </head>
            <body>
              <div id="status">正在加载官方验证组件…</div>
              <div id="captcha"></div>
              <script>
                initGeetest({
                  gt: $gt,
                  challenge: $challengeValue,
                  offline: false,
                  new_captcha: true,
                  product: "bind",
                  width: "100%"
                }, function (captcha) {
                  captcha.appendTo("#captcha");
                  captcha.onReady(function () {
                    document.getElementById("status").textContent = "请手动完成验证";
                  });
                  captcha.onSuccess(function () {
                    var result = captcha.getValidate();
                    if (result && result.geetest_validate) {
                      AndroidCaptcha.onSolved(result.geetest_validate);
                    } else {
                      AndroidCaptcha.onError("验证结果为空");
                    }
                  });
                  captcha.onError(function () {
                    AndroidCaptcha.onError("验证组件加载失败");
                  });
                });
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun javascriptString(value: String): String = Json.encodeToString(value)
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
}
