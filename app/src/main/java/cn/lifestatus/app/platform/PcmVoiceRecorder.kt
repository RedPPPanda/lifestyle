package cn.lifestatus.app.platform

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import cn.lifestatus.app.ai.WavAudio

/** At most 60 seconds in memory. No audio file enters storage, export or longitudinal analysis. */
internal class PcmVoiceRecorder {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var stopped = false
    @Volatile private var destroyed = false
    private val timeout = Runnable { stop() }

    fun start(onReady: () -> Unit, onComplete: (ByteArray) -> Unit, onFailure: (String) -> Unit) {
        val minimum = AudioRecord.getMinBufferSize(WavAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) { onFailure("手机无法使用当前录音格式，请使用文字记录"); return }
        val chunk = maxOf(minimum, 4096).let { if (it % 2 == 0) it else it + 1 }
        try {
            recorder = AudioRecord(MediaRecorder.AudioSource.MIC, WavAudio.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, chunk * 2)
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException()
        } catch (_: SecurityException) {
            runCatching { recorder?.release() }; recorder = null
            onFailure("麦克风权限不可用或已撤销，请重新允许权限，或使用文字记录")
            return
        } catch (_: Exception) {
            runCatching { recorder?.release() }; recorder = null
            onFailure("麦克风暂时不可用，请检查权限或使用文字记录")
            return
        }
        main.postDelayed(timeout, WavAudio.MAX_SECONDS * 1000L)
        Thread({
            val pcm = ByteArray(WavAudio.MAX_PCM_BYTES)
            var count = 0
            val audio = recorder
            try {
                if (destroyed || stopped || audio == null) return@Thread
                audio.startRecording()
                main.post { if (!destroyed && !stopped) onReady() }
                while (!stopped && !destroyed && count < pcm.size) {
                    val read = try {
                        audio.read(pcm, count, minOf(chunk, pcm.size - count), AudioRecord.READ_BLOCKING)
                    } catch (error: SecurityException) {
                        throw error
                    } catch (_: Exception) {
                        if (stopped || destroyed) -1 else throw IllegalStateException()
                    }
                    if (read > 0) count += read
                    else if (!stopped && !destroyed) throw IllegalStateException()
                }
                if (!destroyed) {
                    if (count < 6400) {
                        main.post { if (!destroyed) onFailure("录音太短，请说完后再点击结束") }
                    } else {
                        val recorded = pcm.copyOf(count - count % 2)
                        val wav = try { WavAudio.pcm16Mono(recorded) } finally { recorded.fill(0) }
                        main.post { if (!destroyed) onComplete(wav) else wav.fill(0) }
                    }
                }
            } catch (_: SecurityException) {
                main.post { if (!destroyed) onFailure("麦克风权限已撤销，请重新允许权限，或使用文字记录") }
            } catch (_: Exception) {
                main.post { if (!destroyed) onFailure("录音中断，请检查麦克风权限后重试，或使用文字记录") }
            } finally {
                stopped = true
                main.removeCallbacks(timeout)
                runCatching { audio?.stop() }
                runCatching { audio?.release() }
                recorder = null
                pcm.fill(0)
            }
        }, "life-voice-capture").apply { isDaemon = true; start() }
    }

    fun stop() { stopped = true; runCatching { recorder?.stop() }; main.removeCallbacks(timeout) }
    fun destroy() { destroyed = true; stop() }
}
