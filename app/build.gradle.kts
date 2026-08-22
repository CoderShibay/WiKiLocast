import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── Bundle Piper TTS voice into APK assets at build time ─────────────────────
// Model: vits-piper-en_US-libritts_r-medium (Apache 2.0, ~65 MB)
// Trained on LibriTTS audiobook recordings — natural, soothing for long-form.
// Runs ONCE at build time. On device, copied to internal storage in seconds.

val voiceAssetsDir = file("src/main/assets/voice")

val bundleVoiceModel by tasks.registering {
    outputs.dir(voiceAssetsDir)
    onlyIf { !File(voiceAssetsDir, "model.onnx").exists() }
    doLast {
        val tmp = File(buildDir, "voice-model-tmp").also { it.mkdirs() }
        val tarFile = File(tmp, "piper.tar.bz2")

        logger.lifecycle("Downloading Piper LibriTTS voice (~65 MB) — one time only…")
        URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-libritts_r-medium.tar.bz2")
            .openStream().use { inp -> tarFile.outputStream().use { inp.copyTo(it) } }

        logger.lifecycle("Extracting…")
        ProcessBuilder("tar", "xjf", tarFile.absolutePath, "-C", tmp.absolutePath)
            .redirectErrorStream(true).start()
            .also { p -> p.inputStream.copyTo(System.out); check(p.waitFor() == 0) { "tar failed" } }

        val src = tmp.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("vits-piper") }
            ?: error("Extraction failed — no piper directory found in $tmp")

        voiceAssetsDir.mkdirs()

        val onnxFiles = src.listFiles() ?: emptyArray()
        val onnx = onnxFiles.firstOrNull { it.name.endsWith(".onnx") && !it.name.endsWith(".json") }
            ?: error("No .onnx file found in ${src.absolutePath}")
        val json = File(src, "${onnx.name}.json")

        onnx.copyTo(File(voiceAssetsDir, "model.onnx"), overwrite = true)
        if (json.exists()) json.copyTo(File(voiceAssetsDir, "model.onnx.json"), overwrite = true)
        val tokensFile = File(src, "tokens.txt")
        if (tokensFile.exists()) tokensFile.copyTo(File(voiceAssetsDir, "tokens.txt"), overwrite = true)
        val espeakDir = File(src, "espeak-ng-data")
        if (espeakDir.isDirectory) espeakDir.copyRecursively(File(voiceAssetsDir, "espeak-ng-data"), overwrite = true)

        tmp.deleteRecursively()
        logger.lifecycle("Done! Voice bundled: ${onnx.name} (${onnx.length() / 1024 / 1024} MB)")
    }
}

tasks.named("preBuild") { dependsOn(bundleVoiceModel) }

// ─────────────────────────────────────────────────────────────────────────────

android {
    namespace = "com.wikifm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wikifm"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    // Don't compress large binary model files — they're already compressed
    androidResources {
        noCompress += listOf("onnx", "bin")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += setOf("**/libonnxruntime.so")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
