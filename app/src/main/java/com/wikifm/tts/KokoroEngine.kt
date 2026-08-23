package com.wikifm.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * On-device TTS using Kokoro-en-v0_19 via sherpa-onnx (Apache 2.0).
 *
 * Uses a producer-consumer queue to decouple neural-net inference from
 * AudioTrack playback. The generator thread puts PCM chunks into the queue
 * as soon as they're computed; the player thread drains it continuously.
 * The player never waits for inference — it keeps playing from the queue
 * while the generator fills it, eliminating stop-start pauses.
 *
 * Voice IDs:  0=af_heart  3=am_adam  5=bf_emma  7=bm_george  8=bm_lewis
 */
class KokoroEngine(private val modelDir: File) {

    private var tts: OfflineTts? = null
    @Volatile private var stopRequested = false
    @Volatile private var audioTrack: AudioTrack? = null

    var voiceSid: Int = 7  // bm_george (British male — calmer for long-form)

    fun init(): Boolean {
        val model   = File(modelDir, "model.onnx")
        val voices  = File(modelDir, "voices.bin")
        val tokens  = File(modelDir, "tokens.txt")
        val espeak  = File(modelDir, "espeak-ng-data")

        if (!model.exists()  || model.length()  < 50_000_000L) return false
        if (!voices.exists() || voices.length() < 1024L)        return false
        if (!tokens.exists() || !espeak.isDirectory)             return false

        return try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model   = model.absolutePath,
                        voices  = voices.absolutePath,
                        tokens  = tokens.absolutePath,
                        dataDir = espeak.absolutePath
                    ),
                    numThreads = 2,
                    debug      = false,
                    provider   = "cpu"
                ),
                maxNumSentences = 4
            )
            tts = OfflineTts(config = config)
            true
        } catch (t: Throwable) { tts = null; false }
    }

    val sampleRate: Int get() = tts?.sampleRate() ?: 24000

    /**
     * Speak [text] continuously using a producer-consumer pipeline.
     *
     * Producer (this thread): runs Kokoro inference, puts FloatArray PCM chunks
     *   into [queue] as they arrive from generateWithCallback.
     *
     * Consumer (writer thread): drains [queue] into AudioTrack via WRITE_BLOCKING.
     *   Runs independently — keeps playing from buffered audio while inference
     *   computes the next batch. Exits when it dequeues [END_OF_STREAM].
     *
     * Result: AudioTrack playback is never blocked waiting for inference.
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
        // Buffer = 2 seconds of audio so inference gaps are hidden behind playback
        val bufBytes = maxOf(
            AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4,
            sr * 4 * 2
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufBytes)
            .build()

        audioTrack = track
        track.play()

        // Unbounded queue: producer never blocks; consumer drains as fast as playback allows
        val queue = LinkedBlockingQueue<FloatArray>()

        // ── Consumer (writer thread) ──────────────────────────────────────────
        val writer = Thread {
            while (true) {
                // Poll with timeout so we can check stopRequested between chunks
                val chunk = queue.poll(80, TimeUnit.MILLISECONDS)
                if (stopRequested) break
                if (chunk === null) continue         // poll timeout — loop & re-check
                if (chunk === END_OF_STREAM) break   // producer finished
                track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            }
        }
        writer.start()

        // ── Producer (this thread) ────────────────────────────────────────────
        // Track progress here (inference side) so the bar moves ahead of playback
        // rather than lagging behind — gives the user a leading indicator.
        var generatedSamples = 0
        engine.generateWithCallback(text = text, sid = voiceSid, speed = rate) { samples ->
            if (stopRequested) return@generateWithCallback 0
            queue.put(samples.copyOf())
            generatedSamples += samples.size
            val approxChar = ((generatedSamples.toFloat() / sr) * 12f * rate).toInt()
            onProgress(approxChar.coerceAtMost(text.length))
            1
        }

        queue.put(END_OF_STREAM)   // tell writer we're done
        writer.join(3_000)         // wait for writer — short timeout so stop() feels instant

        audioTrack = null
        try { track.stop() }    catch (_: Exception) {}
        try { track.release() } catch (_: Exception) {}
        if (!stopRequested) onDone()
    }

    fun stop() {
        stopRequested = true
        try { audioTrack?.stop() } catch (_: Exception) {}   // unblocks WRITE_BLOCKING
    }

    fun release() { stop(); tts?.release(); tts = null }

    companion object {
        // Sentinel: identity comparison (===) distinguishes it from real audio
        private val END_OF_STREAM = FloatArray(0)
    }
}
