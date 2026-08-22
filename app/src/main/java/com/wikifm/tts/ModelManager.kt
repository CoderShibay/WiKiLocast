package com.wikifm.tts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and manages the Kokoro-en-v0_19 model for sherpa-onnx.
 * One-time download of ~95 MB, stored in app internal storage.
 * Apache 2.0 model from: github.com/k2-fsa/sherpa-onnx
 */
class ModelManager(context: Context) {

    val modelDir: File = File(context.filesDir, "kokoro-en-v0_19")

    private val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"

    fun isReady(): Boolean =
        File(modelDir, "model.onnx").exists() &&
        File(modelDir, "voices.bin").exists() &&
        File(modelDir, "tokens.txt").exists() &&
        File(modelDir, "espeak-ng-data").isDirectory

    /**
     * Download and extract the Kokoro model.
     * [onProgress] receives (bytesDownloaded, totalBytes, statusLabel).
     */
    suspend fun download(onProgress: (Long, Long, String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmpDir = File(modelDir.parentFile, "kokoro-dl-tmp").also { it.mkdirs() }
                val tarFile = File(tmpDir, "kokoro.tar.bz2")

                try {
                    // ── Download ──────────────────────────────────────────────
                    onProgress(0, -1, "Connecting…")
                    val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("User-Agent", "WikiFM/1.0")
                        connectTimeout = 15_000
                        readTimeout    = 60_000
                        connect()
                    }
                    val total = conn.contentLengthLong
                    var done  = 0L

                    conn.inputStream.use { inp ->
                        FileOutputStream(tarFile).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var n: Int
                            while (inp.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                done += n
                                onProgress(done, total, "Downloading Kokoro voice…")
                            }
                        }
                    }

                    // ── Extract ───────────────────────────────────────────────
                    onProgress(done, total, "Installing voice model…")
                    modelDir.mkdirs()
                    extractTarBz2(tarFile, modelDir.parentFile!!)

                    onProgress(total, total, "Ready")
                } finally {
                    tmpDir.deleteRecursively()
                }
            }
        }

    private fun extractTarBz2(archive: File, destDir: File) {
        TarArchiveInputStream(
            BZip2CompressorInputStream(archive.inputStream().buffered())
        ).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                // Strip top-level directory prefix from entry name
                val relativeName = entry.name.substringAfter("/").trimStart('/')
                if (relativeName.isEmpty()) { entry = tar.nextEntry; continue }

                val outFile = File(destDir, relativeName)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        val buf = ByteArray(32 * 1024)
                        var n: Int
                        while (tar.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    fun deleteModel() = modelDir.deleteRecursively()
}
