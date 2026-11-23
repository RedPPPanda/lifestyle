package cn.lifestatus.app.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small PCM WAV codec shared by microphone capture and the cloud ASR upload validator. */
object WavAudio {
    const val SAMPLE_RATE = 16000
    const val MAX_SECONDS = 60
    const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS

    fun pcm16Mono(pcm: ByteArray): ByteArray {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= MAX_PCM_BYTES) { "录音长度无效" }
        val result = ByteArray(44 + pcm.size)
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            .putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
            .putShort(2).putShort(16).put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size).put(pcm)
        return result
    }

    fun validateForAsr(wav: ByteArray) {
        require(wav.size in 46..(8 * 1024 * 1024)) { "录音为空或超过 8 MiB" }
        val input = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(offset: Int, count: Int) = String(wav, offset, count, Charsets.US_ASCII)
        require(ascii(0, 4) == "RIFF" && input.getInt(4) == wav.size - 8 &&
            ascii(8, 8) == "WAVEfmt " && input.getInt(16) == 16 && input.getShort(20).toInt() == 1 &&
            input.getShort(22).toInt() == 1 && input.getInt(24) == SAMPLE_RATE &&
            input.getInt(28) == SAMPLE_RATE * 2 && input.getShort(32).toInt() == 2 &&
            input.getShort(34).toInt() == 16 && ascii(36, 4) == "data" &&
            input.getInt(40) == wav.size - 44 && (wav.size - 44) % 2 == 0 && wav.size - 44 <= MAX_PCM_BYTES) {
            "录音格式需要 16 kHz、单声道、16-bit PCM WAV，最长 60 秒"
        }
    }
}
