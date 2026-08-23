package com.wikifm.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * On-device TTS using Kokoro-en-v0_19 via sherpa-onnx.
 * Apache 2.0. No network calls during inference.
 *
 * Voice IDs:
 *   0 = af_heart  (warm American female — default)
 *   5 = bf_emma   (British female)
 *   7 = bm_george (British male — calmer for long-form)
 *   8 = bm_lewis
 */
class KokoroEngine(private val modelDir: File) {

    private var tts: OfflineTts? = null
    @Volatile private var stopRequested = false
    @Volatile private var audioTrack: AudioTrack? = null

    var voiceSid: Int = 7

    fun init(): Boolean {
        val modelFile  = File(modelDir, "model.onnx")
        val voicesFile = File(modelDir, "voices.bin")
        val tokensFile = File(modelDir, "tokens.txt")
        val espeakDir  = File(modelDir, "espeak-ng-data")

        if (!modelFile.exists()  || modelFile.length()  < 50_000_000L) return false
        if (!voicesFile.exists() || voicesFile.length() < 1024L)        return false
        if (!tokensFile.exists()) return false
        if (!espeakDir.isDirectory) return false

        return try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model   = modelFile.absolutePath,
                        voices  = voicesFile.absolutePath,
                        tokens  = tokensFile.absolutePath,
                        dataDir = espeakDir.absolutePath
                    ),
                    numThreads = 2,
                    debug      = false,
                    provider   = "cpu"
                ),
                // Process 4 sentences per inference batch.
                // Larger = fewer gaps but longer initial wait.
                maxNumSentences = 4
            )
            tts = OfflineTts(config = config)
            true
        } catch (t: Throwable) {
            tts = null
            false
        }
    }

    val sampleRate: Int get() = tts?.sampleRate() ?: 24000

    /**
     * Stream [text] (the entire remaining article) as one continuous call.
     * One AudioTrack is shared for the whole article — no gaps between sentences.
     * Buffer holds ~2 s of audio so inference pauses are hidden behind playback.
     */
    fun speakContinuous(
        text: String,
        rate: Float,
        onProgress: (charOffset: Int) -> Unit,
        onDone: () -> Unit
    ) {
        val engine = tts ?: return
        stopRequested = false

        val sr = engine.sampleRate()

        // 2-second audio buffer: hides inference latency between sentence batches
        val twoSecBytes = sr * 4 * 2   // sampleRate * bytesPerFloat * 2 seconds
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufSize = maxOf(minBuf * 4, twoSecBytes)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufSize)
            .build()

        audioTrack = track
        track.play()

        var totalWritten = 0
        engine.generateWithCallback(text = text, sid = voiceSid, speed = rate) { samples ->
            if (stopRequested) return@generateWithCallback 0
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            totalWritten += samples.size
            val approxChar = ((totalWritten.toFloat() / sr) * 12f * rate).toInt()
            onProgress(approxChar.coerceAtMost(text.length))
            1
        }

        audioTrack = null
        try { track.stop() }    catch (_: Exception) {}
        try { track.release() } catch (_: Exception) {}
        if (!stopRequested) onDone()
    }

    fun stop() {
        stopRequested = true
        try { audioTrack?.stop() } catch (_: Exception) {}
    }

    fun release() { stop(); tts?.release(); tts = null }
}
