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

class ModelManager(context: Context) {

    val modelDir: File = File(context.filesDir, "kokoro-en-v0_19")
    private val filesDir = context.filesDir

    fun isReady(): Boolean {
        val model = File(modelDir, "model.onnx")
        return model.exists() && model.length() > 50_000_000L &&
               File(modelDir, "voices.bin").exists() &&
               File(modelDir, "tokens.txt").exists() &&
               File(modelDir, "espeak-ng-data").isDirectory
    }

    /**
     * Previous builds extracted files to filesDir/ instead of filesDir/kokoro-en-v0_19/.
     * Move them if found — avoids a 95 MB re-download.
     */
    fun migrateIfNeeded(): Boolean {
        val wrongModel = File(filesDir, "model.onnx")
        if (!wrongModel.exists() || wrongModel.length() < 50_000_000L) return false
        if (isReady()) return false  // already in the right place

        modelDir.mkdirs()
        listOf("model.onnx", "voices.bin", "tokens.txt").forEach { name ->
            val src = File(filesDir, name)
            val dst = File(modelDir, name)
            if (src.exists() && !dst.exists()) src.renameTo(dst)
        }
        val srcEspeak = File(filesDir, "espeak-ng-data")
        val dstEspeak = File(modelDir, "espeak-ng-data")
        if (srcEspeak.isDirectory && !dstEspeak.exists()) srcEspeak.renameTo(dstEspeak)

        return isReady()
    }

    suspend fun download(onProgress: (Long, Long, String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(filesDir, "kokoro-dl-tmp").also { it.mkdirs() }
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
                    // Extract directly into modelDir (strips the kokoro-en-v0_19/ prefix)
                    extractTarBz2(tar, modelDir)
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
                // Strip the top-level "kokoro-en-v0_19/" prefix
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

    companion object {
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"
    }
}
