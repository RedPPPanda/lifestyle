package cn.lifestatus.app.platform

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import cn.lifestatus.app.ai.DeepSeekAsrClient
import cn.lifestatus.app.data.AiConfig
import cn.lifestatus.app.data.AppRepository
import java.util.concurrent.Executors

/** Short dictation only; no microphone audio is retained by this app. */
class SpeechInput(private val activity: Activity) {
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var generation = 0
    private var preferSystem = false
    private var preferCloud = false
    private var cloudRecorder: PcmVoiceRecorder? = null
    private var cloudAsr: DeepSeekAsrClient? = null
    private var pendingAudio: ByteArray? = null
    private val network = Executors.newSingleThreadExecutor()
    var isListening: Boolean = false
        private set

    fun start(onText: (String) -> Unit, onStatus: (String) -> Unit) {
        activity.runOnUiThread {
            if (cloudAsr != null) {
                onStatus("正在转写上一段语音，请稍候")
                return@runOnUiThread
            }
            if (isListening) {
                onStatus("正在聆听，结束后会自动转成文字")
                return@runOnUiThread
            }
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                onStatus("请先允许麦克风权限，或直接使用文字记录")
                return@runOnUiThread
            }
            val onDevice = !preferSystem && Build.VERSION.SDK_INT >= 31 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
            val systemAvailable = SpeechRecognizer.isRecognitionAvailable(activity)
            if (preferCloud || (!onDevice && !systemAvailable)) {
                startCloud(onText, onStatus)
                return@runOnUiThread
            }
            val token = ++generation
            recognizer?.destroy()
            try {
                val next = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
                    else SpeechRecognizer.createSpeechRecognizer(activity)
                recognizer = next
                next.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { if (token == generation) onStatus("正在聆听，说完后会自动记录") }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { if (token == generation) onStatus("正在识别语音…") }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onResults(results: Bundle?) {
                        if (token != generation) return
                        isListening = false
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull { it.isNotBlank() }?.trim()
                        if (text.isNullOrBlank()) onStatus("没有识别出文字，请重试或手动输入")
                        else { onStatus("语音已转为文字"); onText(text) }
                    }
                    override fun onError(error: Int) {
                        if (token != generation) return
                        isListening = false
                        if (onDevice && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_SERVER)) {
                            preferSystem = true
                            onStatus("当前离线中文识别不可用，再次点语音将尝试系统识别")
                            return
                        }
                        if (!onDevice && error in setOf(SpeechRecognizer.ERROR_SERVER,
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                            preferCloud = true
                            onStatus("系统语音暂不可用，再点语音可尝试已配置的 DeepSeek 云端录音")
                            return
                        }
                        onStatus(when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "麦克风暂时不可用，请稍后重试"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音识别需要麦克风权限"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络不可用，可使用文字记录"
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清，请再说一次或输入文字"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务正忙，请稍后再试"
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "系统暂不支持中文语音识别，可使用输入法语音"
                            else -> "语音识别暂时不可用，请重试或使用文字"
                        })
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                isListening = true
                next.startListening(intent)
            } catch (_: Exception) {
                isListening = false
                preferSystem = onDevice
                if (!onDevice) preferCloud = true
                onStatus("语音服务启动失败，再点语音可尝试其他识别方式，或使用文字记录")
            }
        }
    }

    private fun startCloud(onText: (String) -> Unit, onStatus: (String) -> Unit) {
        val config = try { AppRepository(activity).getAiConfig() } catch (_: Exception) {
            onStatus("无法读取云端语音配置，请在 AI 设置中重新填写，或使用文字记录")
            return
        }
        if (!DeepSeekAsrClient.isSupported(config)) {
            onStatus("系统语音不可用；可配置官方 DeepSeek 主接口启用云端录音，或使用输入法语音")
            return
        }
        val token = ++generation
        runCatching { recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
        val capture = PcmVoiceRecorder()
        cloudRecorder = capture
        isListening = true
        onStatus("正在准备录音，录音后再次点击结束；最多 60 秒，使用 DeepSeek 云端转写")
        capture.start(
            onReady = { if (token == generation) onStatus("正在录音，再次点击语音结束；最多 60 秒，DeepSeek 云端转写") },
            onFailure = { message ->
                if (token == generation) { cloudRecorder = null; isListening = false; onStatus(message) }
            },
            onComplete = { wav ->
                if (token != generation) { wav.fill(0); return@start }
                cloudRecorder = null
                isListening = false
                onStatus("正在转写语音…")
                transcribe(config, wav, token, onText, onStatus)
            }
        )
    }

    private fun transcribe(config: AiConfig, wav: ByteArray, token: Int,
                           onText: (String) -> Unit, onStatus: (String) -> Unit) {
        val client = DeepSeekAsrClient(config)
        cloudAsr = client
        pendingAudio = wav
        network.execute {
            try {
                val text = client.transcribe(wav)
                activity.runOnUiThread {
                    if (token == generation) { cloudAsr = null; onStatus("语音已转为文字"); onText(text) }
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    if (token == generation) { cloudAsr = null; onStatus(error.message ?: "语音转写失败，请重试或使用文字") }
                }
            } finally {
                wav.fill(0)
                activity.runOnUiThread { if (pendingAudio === wav) pendingAudio = null }
            }
        }
    }

    fun stop() {
        activity.runOnUiThread {
            if (cloudRecorder != null) cloudRecorder?.stop()
            else if (isListening) runCatching { recognizer?.stopListening() }
        }
    }

    fun destroy() {
        activity.runOnUiThread {
            ++generation
            isListening = false
            cloudRecorder?.destroy()
            cloudRecorder = null
            cloudAsr?.cancel()
            cloudAsr = null
            pendingAudio?.fill(0)
            pendingAudio = null
            network.shutdownNow()
            runCatching { recognizer?.cancel(); recognizer?.destroy() }
            recognizer = null
        }
    }
}
