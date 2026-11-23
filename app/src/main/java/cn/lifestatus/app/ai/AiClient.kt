package cn.lifestatus.app.ai

import cn.lifestatus.app.core.EventKind
import cn.lifestatus.app.core.LifeEvent
import cn.lifestatus.app.data.AiConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/** Blocking IO: callers must use an IO dispatcher or a worker thread. */
class AiClient(private val config: AiConfig) {
    private val endpoint = endpointFor(config)

    fun parse(text: String, now: Long, source: String = "文字"): List<LifeEvent> {
        require(text.isNotBlank() && text.length <= 4000) { "记录请保持在 4000 字以内" }
        return parseEvents(chat(PromptCatalog.record(now), text, 1800, structured = true), text, now, source)
    }

    fun parseImage(imageBytes: ByteArray, mimeType: String, text: String, now: Long,
                   source: String = "图片"): List<LifeEvent> {
        require(imageBytes.isNotEmpty() && imageBytes.size <= 4 * 1024 * 1024) { "图片需压缩至 4 MiB 以内" }
        require(text.length <= 4000) { "图片说明请保持在 4000 字以内" }
        val mime = mimeType.lowercase().trim()
        require(mime in setOf("image/jpeg", "image/png", "image/webp", "image/gif") && hasImageHeader(imageBytes, mime)) {
            "图片格式无效，请使用 JPEG、PNG 或 WebP 图片"
        }
        val description = text.ifBlank { "请识别这次食物照片或健康数据截图，只记录清楚可量化的信息。" }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", description))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:$mime;base64,${Base64.getEncoder().encodeToString(imageBytes)}")
                .put("detail", "high")))
        return parseEvents(chat(PromptCatalog.image(now), content, 2200, structured = true),
            text.ifBlank { "图片记录" }, now, source)
    }

    private fun parseEvents(content: String, text: String, now: Long, source: String): List<LifeEvent> {
        val json = try {
            JSONObject(stripFence(content))
        } catch (_: Exception) {
            throw AiException("AI 未返回可用的记录格式，请重试或使用基础记录")
        }
        val events = json.optJSONArray("events") ?: throw AiException("AI 返回的记录缺少 events")
        if (events.length() > 20) throw AiException("AI 返回的条目过多，本次未采用")
        val batch = UUID.randomUUID().toString()
        return try {
            (0 until events.length()).map { index ->
                val item = events.getJSONObject(index)
                val kind = EventKind.valueOf(item.getString("type"))
                if (kind == EventKind.CALIBRATE) throw IllegalArgumentException()
                val value = if (kind == EventKind.NOTE) 0.0 else item.getDouble("value")
                val range = when (kind) {
                    EventKind.MEAL -> 0.1..3.0
                    EventKind.WATER -> 1.0..5000.0
                    EventKind.SLEEP -> 0.1..24.0
                    EventKind.WEIGHT -> 1.0..500.0
                    EventKind.EXERCISE -> 1.0..1440.0
                    EventKind.STEPS -> 1.0..100000.0
                    EventKind.STEPS_TOTAL -> 0.0..200000.0
                    EventKind.NOTE -> 0.0..0.0
                    EventKind.CALIBRATE -> throw IllegalArgumentException()
                }
                require(value.isFinite() && value in range)
                if (kind == EventKind.STEPS || kind == EventKind.STEPS_TOTAL) require(value % 1.0 == 0.0)
                val estimated = kind == EventKind.MEAL || item.optBoolean("estimated", false)
                require(!estimated || kind !in setOf(EventKind.WEIGHT, EventKind.STEPS, EventKind.STEPS_TOTAL))
                val occurredAt = parseTime(item.optString("occurred_at"), now)
                require(occurredAt <= now + 60_000 && occurredAt >= now - 90L * 24 * 60 * 60 * 1000)
                LifeEvent(batchId = batch, kind = kind, value = value,
                    occurredAt = occurredAt.coerceAtMost(now), recordedAt = now,
                    text = text, source = "AI·${source.take(20)}" + if (estimated) "·估算" else "")
            }
        } catch (_: Exception) {
            throw AiException("AI 返回的事件或数值不符合记录规则，本次未采用")
        }
    }

    fun analyze(payload: JSONObject): String {
        require(payload.toString().toByteArray(Charsets.UTF_8).size <= 500_000) { "分析数据过多，请缩小范围" }
        return chat(PromptCatalog.analysis(), payload.toString(), 1400).trim().take(10000)
            .ifBlank { throw AiException("AI 返回了空的分析结果") }
    }

    private fun chat(system: String, input: Any, maxTokens: Int, structured: Boolean = false): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", input))
        val body = JSONObject().put("model", config.model.trim()).put("messages", messages)
            .put("stream", false).put("temperature", 0.2).put("max_tokens", maxTokens)
        if (structured) body.put("response_format", JSONObject().put("type", "json_object"))
        if (endpoint.host.equals("api.deepseek.com", ignoreCase = true)) {
            body.put("thinking", JSONObject().put("type", "disabled"))
        }
        var connection: HttpsURLConnection? = null
        try {
            connection = endpoint.toURL().openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            connection.doOutput = true
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                // Server error bodies can echo requests or credentials; never expose them.
                connection.errorStream?.close()
                val hint = when (status) {
                    401, 403 -> "请检查 API 密钥和服务授权"
                    404 -> "请检查服务地址和模型名称"
                    429 -> "请求过于频繁或额度不足，请稍后重试"
                    in 300..399 -> "服务要求跳转，请填写最终 HTTPS 服务地址"
                    else -> "请检查服务配置或稍后重试"
                }
                throw AiException("AI 服务返回 HTTP $status：$hint")
            }
            val responseBytes = connection.inputStream.use { inputStream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = inputStream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1024 * 1024) throw AiException("AI 响应过大，本次未采用")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val response = JSONObject(responseBytes.toString(Charsets.UTF_8))
            val choice = response.getJSONArray("choices").getJSONObject(0)
            if (choice.optString("finish_reason") == "length") throw AiException("AI 输出被截断，本次未采用，请缩短记录后重试")
            val message = choice.getJSONObject("message")
            val content = message.opt("content")
            return when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull { index ->
                    content.optJSONObject(index)?.takeIf { it.optString("type") == "text" }?.optString("text")
                }.joinToString("\n")
                else -> throw AiException("AI 服务没有返回可用的文字内容")
            }
        } catch (error: AiException) {
            throw error
        } catch (_: Exception) {
            throw AiException("AI 连接或响应读取失败，请检查网络、服务地址和模型配置")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseTime(value: String, fallback: Long): Long {
        if (value.isBlank()) return fallback
        return value.toLongOrNull()?.takeIf { it > 1_000_000_000_000L }
            ?: Instant.parse(value).toEpochMilli()
    }

    private fun stripFence(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val start = trimmed.indexOf('\n')
        val end = trimmed.lastIndexOf("```")
        if (start < 0 || end <= start) return trimmed
        return trimmed.substring(start + 1, end).trim()
    }

    private fun hasImageHeader(bytes: ByteArray, mime: String): Boolean {
        fun byte(index: Int): Int = bytes.getOrNull(index)?.toInt()?.and(0xff) ?: -1
        return when (mime) {
            "image/jpeg" -> byte(0) == 0xff && byte(1) == 0xd8 && byte(2) == 0xff
            "image/png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))
            "image/webp" -> bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
            "image/gif" -> bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
            else -> false
        }
    }

    companion object {
        fun validateConfig(config: AiConfig) { endpointFor(config) }

        private fun endpointFor(config: AiConfig): URI {
            require(config.isConfigured) { "请先填写 AI 服务地址、模型与 API 密钥" }
            require(config.apiKey.length <= 8192 && config.apiKey.none { it.isISOControl() }) { "API 密钥格式无效" }
            require(config.model.length <= 200 && config.model.none { it.isISOControl() }) { "模型名称格式无效" }
            val uri = try { URI(config.baseUrl.trim()).normalize() } catch (_: Exception) {
                throw IllegalArgumentException("AI 服务地址格式无效")
            }
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "请使用 HTTPS 服务地址，密钥请填在独立的 API 密钥栏"
            }
            require(uri.port in -1..65535 && uri.port != 0) { "服务端口无效" }
            val path = uri.path.orEmpty().trimEnd('/')
            val endpointPath = when {
                path.endsWith("/chat/completions") -> path
                path.isEmpty() -> "/v1/chat/completions"
                else -> "$path/chat/completions"
            }
            return URI("https", null, uri.host, uri.port, endpointPath, null, null)
        }
    }
}

class AiException(message: String) : IOException(message)
