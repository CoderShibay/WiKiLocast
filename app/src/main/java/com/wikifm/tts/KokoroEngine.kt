package com.wikifm.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * On-device TTS using Piper en_US-libritts_r-medium via sherpa-onnx.
 * Apache 2.0 — trained on LibriTTS clean audiobook recordings.
 * No network, no API keys. Inference runs on CPU.
 */
class KokoroEngine(private val modelDir: File) {

    private var tts: OfflineTts? = null
    @Volatile private var stopRequested = false

    fun init(): Boolean = runCatching {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model   = File(modelDir, "model.onnx").absolutePath,
                    tokens  = File(modelDir, "tokens.txt").absolutePath,
                    dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                    lexicon = ""
                ),
                numThreads = 2,
                debug      = false,
                provider   = "cpu"
            ),
            maxNumSentences = 2
        )
        tts = OfflineTts(config = config)
        true
    }.getOrElse { false }

    val sampleRate: Int get() = tts?.sampleRate() ?: 22050

    /**
     * Synthesise [text] at [rate] and stream to speaker.
     * [onProgress] receives approximate char offset.
     * [onDone] fires when sentence finishes naturally.
     */
    fun speak(
        text: String,
        rate: Float,
        onProgress: (Int) -> Unit,
        onDone: () -> Unit
    ) {
        val engine = tts ?: return
        stopRequested = false

        val sr = engine.sampleRate()
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 4)
            .build()

        track.play()
        var totalWritten = 0

        engine.generateWithCallback(text = text, sid = 0, speed = rate) { samples ->
            if (stopRequested) { return@generateWithCallback 0 }
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            totalWritten += samples.size
            val secsPlayed = totalWritten.toFloat() / sr
            onProgress((secsPlayed * 12f * rate).toInt().coerceAtMost(text.length))
            1
        }

        track.stop()
        track.release()

        if (!stopRequested) onDone()
    }

    fun stop() { stopRequested = true }

    fun release() {
        stop()
        tts?.release()
        tts = null
    }
}
