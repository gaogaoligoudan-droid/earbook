package com.earbook.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek 客户端（BYOK——用户在设置页填自己的 key）。
 * 最小实现：chat/completions 单轮调用，HttpURLConnection 零依赖。
 */
object DeepSeekClient {

    private const val BASE = "https://api.deepseek.com"

    /**
     * 单轮对话补全。返回首个 choice 文本；失败返回 null（调用方走本地兜底）。
     */
    fun chat(apiKey: String, system: String, user: String, timeoutMs: Int = 60_000): String? {
        return runCatching {
            val conn = URL("$BASE/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")

            val body = JSONObject().apply {
                put("model", "deepseek-chat")
                put("temperature", 0.3)
                put("max_tokens", 8192)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                })
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
            if (code !in 200..299) return null
            JSONObject(text)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotEmpty() && it != "null" }
        }.getOrNull()
    }
}
