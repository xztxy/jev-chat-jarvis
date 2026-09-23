package com.jev.probe.jev

import java.net.URI

/** Root URLs default to /v1; explicit gateway prefixes are preserved. */
object ApiUrls {
    fun base(value: String): String {
        val clean = value.trim().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/models").removeSuffix("/responses")
        val uri = URI(clean)
        require(uri.scheme == "https" || uri.scheme == "http") { "API 地址必须以 https:// 或 http:// 开头" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "请填写不含凭据、查询参数的 API 地址" }
        val explicit = value.trim().trimEnd('/').let { it.endsWith("/chat/completions") || it.endsWith("/models") || it.endsWith("/responses") }
        return if (uri.path.isNullOrEmpty() && !explicit) "$clean/v1" else clean
    }
    fun chat(value: String): String = if (value.trim().trimEnd('/').endsWith("/responses")) value.trim().trimEnd('/') else base(value) + "/chat/completions"
}
