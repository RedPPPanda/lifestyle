package cn.lifestatus.app.ai

import cn.lifestatus.app.data.AiConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Official DeepSeek tools ASR endpoint; it is deliberately not guessed for other providers. */
class DeepSeekAsrClient(private val config: AiConfig) {
    @Volatile private var connection: HttpsURLConnection? = null
    @Volatile private var canceled = false

    init { require(isSupported(config)) { "云端语音需要配置官方 DeepSeek 服务或使用系统语音" } }

    fun transcribe(wav: ByteArray): String {
        WavAudio.validateForAsr(wav)
        checkNotCanceled()
        val boundary = "life-asr-" + UUID.randomUUID().toString()
        val prefix = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n" +
            "Content-Type: audio/wav\r\n\r\n").toByteArray(Charsets.US_ASCII)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII)
        var active: HttpsURLConnection? = null
        try {
            active = URI("https://api.deepseek.com/v1/tools/asr").toURL().openConnection() as HttpsURLConnection
            connection = active
            checkNotCanceled()
            active.instanceFollowRedirects = false
            active.connectTimeout = 15_000
            active.readTimeout = 90_000
            active.requestMethod = "POST"
            active.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            active.setRequestProperty("Accept", "application/json")
            active.setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            active.doOutput = true
            active.setFixedLengthStreamingMode(prefix.size + wav.size + suffix.size)
            active.outputStream.use { output ->
                checkNotCanceled(); output.write(prefix); output.write(wav); output.write(suffix)
            }
            checkNotCanceled()
            val status = active.responseCode
            if (status !in 200..299) {
                active.errorStream?.close()
                val hint = when (status) {
                    401, 403 -> "请检查 DeepSeek API 密钥和授权"
                    429 -> "请求较多或额度不足，请稍后重试"
                    400, 413 -> "录音格式或长度不符合语音服务要求"
                    503, 504 -> "语音服务繁忙，请稍后重试"
                    in 300..399 -> "语音服务发生跳转，本次未发送到其他地址"
                    else -> "请稍后重试，或使用文字记录"
                }
                throw AiException("语音服务返回 HTTP $status：$hint")
            }
            val bytes = active.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    checkNotCanceled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 256 * 1024) throw AiException("语音响应过大，本次未采用")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            checkNotCanceled()
            val text = JSONObject(bytes.toString(Charsets.UTF_8)).getString("text").trim()
            if (text.isBlank()) throw AiException("没有识别出文字，请重新录音或使用文字记录")
            if (text.length > 4000) throw AiException("语音内容过长，请分成较短的记录")
            return text
        } catch (error: AiException) {
            throw error
        } catch (_: Exception) {
            checkNotCanceled()
            throw AiException("云端语音转写失败，请检查网络或使用文字记录")
        } finally {
            active?.disconnect()
            connection = null
        }
    }

    fun cancel() { canceled = true; connection?.disconnect() }

    private fun checkNotCanceled() { if (canceled) throw AiException("语音转写已取消") }

    companion object {
        fun isSupported(config: AiConfig): Boolean = runCatching {
            AiClient.validateConfig(config)
            val address = URI(config.baseUrl.trim())
            address.host.equals("api.deepseek.com", ignoreCase = true) && address.port in setOf(-1, 443)
        }.getOrDefault(false)
    }
}
