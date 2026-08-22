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
 * Downloads kokoro-en-v0_19 from sherpa-onnx releases on first launch.
 * ~95 MB total. Stored in app internal storage, never in the APK.
 */
class ModelManager(context: Context) {

    val modelDir: File = File(context.filesDir, "kokoro-en-v0_19")

    private val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"

    fun isReady(): Boolean {
        val model = File(modelDir, "model.onnx")
        return model.exists() && model.length() > 50_000_000L &&
               File(modelDir, "voices.bin").exists() &&
               File(modelDir, "tokens.txt").exists() &&
               File(modelDir, "espeak-ng-data").isDirectory
    }

    suspend fun download(onProgress: (Long, Long, String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(modelDir.parentFile, "kokoro-dl-tmp").also { it.mkdirs() }
                val tar = File(tmp, "kokoro.tar.bz2")
                try {
                    onProgress(0, -1, "Connecting…")
                    val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("User-Agent", "WikiFM/1.0")
                        connectTimeout = 15_000; readTimeout = 60_000; connect()
                    }
                    val total = conn.contentLengthLong; var done = 0L
                    conn.inputStream.use { inp ->
                        FileOutputStream(tar).use { out ->
                            val buf = ByteArray(64 * 1024); var n: Int
                            while (inp.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n); done += n
                                onProgress(done, total, "Downloading Kokoro voice…")
                            }
                        }
                    }
                    onProgress(done, total, "Installing…")
                    modelDir.mkdirs()
                    extractTarBz2(tar, modelDir.parentFile!!)
                    onProgress(total, total, "Ready")
                } finally {
                    tmp.deleteRecursively()
                }
            }
        }

    private fun extractTarBz2(archive: File, destDir: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfter("/").trimStart('/')
                if (name.isNotEmpty()) {
                    val out = File(destDir, name)
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { f ->
                            val buf = ByteArray(32 * 1024); var n: Int
                            while (tar.read(buf).also { n = it } != -1) f.write(buf, 0, n)
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
    }
}
