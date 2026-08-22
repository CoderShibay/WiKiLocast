package com.wikifm.tts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the bundled Piper TTS voice on-device.
 * Files are copied from APK assets → internal storage on first launch (~2-5 s).
 * Apache 2.0 voice: vits-piper-en_US-libritts_r-medium (LibriTTS audiobooks).
 */
class ModelManager(private val context: Context) {

    val modelDir: File = File(context.filesDir, "voice-model")

    fun isReady(): Boolean =
        File(modelDir, "model.onnx").exists() &&
        File(modelDir, "tokens.txt").exists() &&
        File(modelDir, "espeak-ng-data").isDirectory

    /**
     * Copy bundled voice from APK assets to internal storage.
     * Only runs once; subsequent launches skip this entirely.
     */
    suspend fun installFromAssets(onProgress: (Float, String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                modelDir.mkdirs()

                onProgress(0.0f, "Setting up voice…")
                copyAsset("voice/model.onnx", File(modelDir, "model.onnx"))

                onProgress(0.85f, "Almost done…")
                copyAsset("voice/model.onnx.json", File(modelDir, "model.onnx.json"))
                copyAsset("voice/tokens.txt", File(modelDir, "tokens.txt"))

                onProgress(0.92f, "Installing language data…")
                copyAssetTree("voice/espeak-ng-data", File(modelDir, "espeak-ng-data"))

                onProgress(1.0f, "Voice ready")
            }
        }

    private fun copyAsset(assetPath: String, dest: File) {
        if (dest.exists() && dest.length() > 1024) return
        dest.parentFile?.mkdirs()
        try {
            context.assets.open(assetPath).use { inp ->
                dest.outputStream().use { inp.copyTo(it) }
            }
        } catch (_: Exception) { /* optional files like .json may not exist */ }
    }

    private fun copyAssetTree(assetPath: String, destDir: File) {
        val children = context.assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            copyAsset(assetPath, destDir)
        } else {
            destDir.mkdirs()
            for (child in children) {
                copyAssetTree("$assetPath/$child", File(destDir, child))
            }
        }
    }

    fun deleteModel() = modelDir.deleteRecursively()
}
