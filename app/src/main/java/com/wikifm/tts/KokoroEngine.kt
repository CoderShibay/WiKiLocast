package com.wikifm.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * On-device TTS using Kokoro-en-v0_19 via sherpa-onnx.
 * Apache 2.0 licensed. No network calls during inference.
 *
 * Voice IDs (sid):
 *   0 = af_heart   (warm American female — default)
 *   1 = af_alloy
 *   2 = af_bella
 *   3 = am_adam    (American male)
 *   4 = am_michael
 *   5 = bf_emma    (British female)
 *   6 = bf_isabella
 *   7 = bm_george  (British male)
 *   8 = bm_lewis
 */
class KokoroEngine(private val modelDir: File) {

    private var tts: OfflineTts? = null
    @Volatile private var stopRequested = false

    /** Voice ID — default 0 (af_heart), set 7 for bm_george (British male) */
    var voiceSid: Int = 7  // British male is more soothing for long-form listening

    fun init(): Boolean = runCatching {
        val espeak = File(modelDir, "espeak-ng-data").absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model   = File(modelDir, "model.onnx").absolutePath,
                    voices  = File(modelDir, "voices.bin").absolutePath,
                    tokens  = File(modelDir, "tokens.txt").absolutePath,
                    dataDir = espeak
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

    val sampleRate: Int get() = tts?.sampleRate() ?: 24000

    /**
     * Synthesise [text] and stream it to the speaker.
     * [onProgress] receives approximate char offset as words are played.
     * [onDone] fires when the sentence finishes naturally (not on stop()).
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

        // AudioTrack in streaming mode
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

        var totalSamplesWritten = 0

        engine.generateWithCallback(
            text  = text,
            sid   = voiceSid,
            speed = rate
        ) { samples ->
            if (stopRequested) {
                track.pause(); track.flush()
                return@generateWithCallback 0   // 0 = stop generation
            }
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            totalSamplesWritten += samples.size
            // Map played samples → approximate char position
            val secsPlayed = totalSamplesWritten.toFloat() / sr
            val charsPerSec = 12f * rate
            onProgress((secsPlayed * charsPerSec).toInt().coerceAtMost(text.length))
            1   // 1 = continue
        }

        track.stop()
        track.release()

        if (!stopRequested) onDone()
    }

    fun stop() {
        stopRequested = true
    }

    fun release() {
        stop()
        tts?.release()
        tts = null
    }
}
