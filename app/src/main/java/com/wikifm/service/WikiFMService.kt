package com.wikifm.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import com.wikifm.MainActivity
import com.wikifm.R
import com.wikifm.data.ArticleItem
import com.wikifm.data.WikiSummary
import com.wikifm.data.WikipediaRepository
import com.wikifm.tts.KokoroEngine
import com.wikifm.tts.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class ModelStatus { UNCHECKED, DOWNLOADING, READY, FAILED }

data class WikiFMState(
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val currentTitle: String = "",
    val currentExtract: String = "",
    val speechRate: Float = 1.0f,
    val jumpIntervalMinutes: Int = 3,
    val error: String? = null,
    val playlist: List<ArticleItem> = emptyList(),
    val suggestions: List<ArticleItem> = emptyList(),
    val sleepTimerSeconds: Int = 0,
    val playbackProgress: Float = 0f,
    // Kokoro model download
    val modelStatus: ModelStatus = ModelStatus.UNCHECKED,
    val downloadProgress: Float = 0f,
    val downloadLabel: String = ""
)

class WikiFMService : Service() {

    inner class WikiFMBinder : Binder() {
        fun getService(): WikiFMService = this@WikiFMService
    }

    private val binder = WikiFMBinder()
    private val repository = WikipediaRepository()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(WikiFMState())
    val state: StateFlow<WikiFMState> = _state

    // ── Kokoro (primary TTS) ──────────────────────────────────────────────────
    private val modelManager by lazy { ModelManager(this) }
    private var kokoro: KokoroEngine? = null

    // ── Android TTS (fallback while model downloads) ──────────────────────────
    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false

    private var pendingText: String? = null
    private var jumpJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var progressJob: Job? = null
    private var speakJob: Job? = null
    private val queue = mutableListOf<ArticleItem>()

    // Sentence-level position tracking
    private var articleText: String = ""
    private var articleChunks: List<String> = emptyList()
    private var chunkOffsets: List<Int> = emptyList()
    @Volatile private var currentAbsoluteChar: Int = 0

    // Android TTS chunk tracking (used only when Kokoro not ready)
    @Volatile private var pausedAtChunk: Int = 0

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        checkAndInitKokoro()
        initAndroidTtsFallback()
    }

    // ─── Kokoro init / download ───────────────────────────────────────────────

    private fun checkAndInitKokoro() {
        scope.launch(Dispatchers.IO) {
            if (modelManager.isReady()) {
                initKokoro()
            } else {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(modelStatus = ModelStatus.DOWNLOADING)
                }
                downloadAndInit()
            }
        }
    }

    private fun initKokoro() {
        val engine = KokoroEngine(modelManager.modelDir)
        if (engine.init()) {
            kokoro = engine
            scope.launch(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    modelStatus = ModelStatus.READY,
                    downloadLabel = "Kokoro voice ready"
                )
                pendingText?.let { loadAndSpeak(it) }
                pendingText = null
            }
        } else {
            scope.launch(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    modelStatus = ModelStatus.FAILED,
                    downloadLabel = "Voice init failed — using system voice"
                )
            }
        }
    }

    private fun downloadAndInit() {
        scope.launch {
            val result = modelManager.download { done, total, label ->
                val progress = if (total > 0) done.toFloat() / total else 0f
                _state.value = _state.value.copy(
                    modelStatus = ModelStatus.DOWNLOADING,
                    downloadProgress = progress,
                    downloadLabel = label
                )
            }
            if (result.isSuccess) {
                withContext(Dispatchers.IO) { initKokoro() }
            } else {
                _state.value = _state.value.copy(
                    modelStatus = ModelStatus.FAILED,
                    downloadLabel = "Download failed — using system voice"
                )
            }
        }
    }

    fun retryDownload() = checkAndInitKokoro()

    // ─── Android TTS fallback ────────────────────────────────────────────────

    private fun initAndroidTtsFallback() {
        androidTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidTtsReady = true
                androidTts?.language = Locale.US
                androidTts?.setPitch(0.85f)
                androidTts?.setSpeechRate(1.0f)
                androidTts?.setOnUtteranceProgressListener(androidUtteranceListener)
            }
        }
    }

    private val androidUtteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId?.removePrefix("chunk_")?.toIntOrNull()?.let { pausedAtChunk = it }
        }
        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            val idx = utteranceId.removePrefix("chunk_").toIntOrNull() ?: return
            currentAbsoluteChar = chunkOffsets.getOrElse(idx) { 0 } + start
        }
        override fun onError(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            val idx = utteranceId?.removePrefix("chunk_")?.toIntOrNull() ?: return
            if (idx == articleChunks.lastIndex && _state.value.isPlaying) {
                scope.launch { onArticleFinished() }
            }
        }
    }

    // ─── Core TTS dispatch ───────────────────────────────────────────────────

    /** Speak the remaining article text starting from [startChar]. */
    private fun seekToText(startChar: Int) {
        val safeStart = startChar.coerceIn(0, articleText.length)
        val remaining = articleText.substring(safeStart)
        if (remaining.isBlank()) return

        val newChunks = chunkText(remaining)
        val newOffsets = computeOffsets(newChunks).map { it + safeStart }
        articleChunks = newChunks
        chunkOffsets = newOffsets
        pausedAtChunk = 0
        currentAbsoluteChar = safeStart

        val engine = kokoro
        if (engine != null) {
            speakWithKokoro(engine, newChunks)
        } else {
            speakWithAndroidTts(0)
        }
        startProgressTimer()
    }

    private fun speakWithKokoro(engine: KokoroEngine, chunks: List<String>) {
        speakJob?.cancel()
        engine.stop()
        speakJob = scope.launch(Dispatchers.IO) {
            for ((i, chunk) in chunks.withIndex()) {
                if (!isActive) break
                var chunkDone = false
                engine.speak(
                    text = chunk,
                    rate = _state.value.speechRate,
                    onProgress = { offset ->
                        currentAbsoluteChar = chunkOffsets.getOrElse(i) { 0 } + offset
                    },
                    onDone = { chunkDone = true }
                )
                if (!chunkDone) break   // stopped mid-chunk
                if (i == chunks.lastIndex && _state.value.isPlaying) {
                    scope.launch { onArticleFinished() }
                }
            }
        }
    }

    private fun speakWithAndroidTts(fromChunk: Int) {
        androidTts?.stop()
        for (i in fromChunk until articleChunks.size) {
            val mode = if (i == fromChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            androidTts?.speak(articleChunks[i], mode, null, "chunk_$i")
        }
    }

    private fun stopSpeaking() {
        speakJob?.cancel()
        kokoro?.stop()
        androidTts?.stop()
    }

    private fun findSentenceStart(absoluteChar: Int): Int {
        if (absoluteChar <= 0 || articleText.isEmpty()) return 0
        val pos = absoluteChar.coerceAtMost(articleText.length)
        val last = maxOf(
            articleText.lastIndexOf(". ", pos),
            articleText.lastIndexOf("! ", pos),
            articleText.lastIndexOf("? ", pos)
        )
        return if (last >= 0) (last + 2).coerceAtMost(articleText.length) else 0
    }

    // ─── Article lifecycle ────────────────────────────────────────────────────

    private fun loadAndSpeak(text: String) {
        if (kokoro == null && !androidTtsReady) { pendingText = text; return }
        articleText = text
        articleChunks = chunkText(text)
        chunkOffsets = computeOffsets(articleChunks)
        pausedAtChunk = 0
        currentAbsoluteChar = 0
        _state.value = _state.value.copy(playbackProgress = 0f)
        seekToText(0)
    }

    private fun speakText(text: String) {
        if (kokoro == null && !androidTtsReady) { pendingText = text; return }
        loadAndSpeak(text)
    }

    private suspend fun onArticleFinished() {
        if (queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            _state.value = _state.value.copy(playlist = queue.toList())
            loadAndPlay(next.title)
        } else {
            autoJump()
        }
    }

    private fun chunkText(text: String): List<String> {
        val max = 3500
        return text.split(Regex("(?<=[.!?])\\s+")).fold(mutableListOf()) { acc, s ->
            if (acc.isEmpty() || acc.last().length + s.length + 1 > max) acc.add(s)
            else acc[acc.lastIndex] = "${acc.last()} $s"
            acc
        }
    }

    private fun computeOffsets(chunks: List<String>): List<Int> {
        val offsets = mutableListOf<Int>(); var pos = 0
        chunks.forEach { offsets.add(pos); pos += it.length + 1 }
        return offsets
    }

    // ─── Progress timer ───────────────────────────────────────────────────────

    private fun startProgressTimer() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(300)
                val total = articleText.length
                if (total > 0 && _state.value.isPlaying) {
                    _state.value = _state.value.copy(
                        playbackProgress = (currentAbsoluteChar.toFloat() / total).coerceIn(0f, 1f)
                    )
                }
            }
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun playTitle(title: String) {
        scope.launch {
            _state.value = _state.value.copy(isPlaying = true, isLoading = true, error = null)
            startFg(buildNotification("Loading…"))
            loadAndPlay(title)
        }
    }

    fun playRandom() {
        scope.launch {
            _state.value = _state.value.copy(isPlaying = true, isLoading = true, error = null)
            startFg(buildNotification("Tuning in…"))
            repository.getRandomFullArticle()
                .onSuccess { playArticle(it) }
                .onFailure { fail("Couldn't load a random article") }
        }
    }

    private suspend fun loadAndPlay(title: String) {
        startFg(buildNotification("Loading…"))
        repository.getFullArticle(title)
            .onSuccess { playArticle(it) }
            .onFailure { e -> fail(e.message ?: "Network error") }
    }

    private fun playArticle(summary: WikiSummary) {
        val text = summary.extract.ifBlank { summary.title }
        _state.value = _state.value.copy(
            currentTitle = summary.title,
            currentExtract = text,
            isLoading = false,
            suggestions = emptyList()
        )
        updateNotification(summary.title)
        speakText(text)
        scheduleJump()
        scope.launch { fetchSuggestions(summary.title) }
    }

    private suspend fun fetchSuggestions(title: String) {
        val related = repository.getRelatedTitles(title)
        _state.value = _state.value.copy(
            suggestions = related.filter { it != title }.take(8).map { ArticleItem(it) }
        )
    }

    fun pause() {
        stopSpeaking()
        jumpJob?.cancel()
        sleepTimerJob?.cancel()
        progressJob?.cancel()
        _state.value = _state.value.copy(isPlaying = false, sleepTimerSeconds = 0)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    fun resume() {
        if (_state.value.currentExtract.isBlank()) return
        _state.value = _state.value.copy(isPlaying = true)
        startFg(buildNotification(_state.value.currentTitle))
        seekToText(findSentenceStart(currentAbsoluteChar))
        scheduleJump()
    }

    fun skip() {
        scope.launch {
            stopSpeaking()
            jumpJob?.cancel()
            _state.value = _state.value.copy(isLoading = true)
            startFg(buildNotification("Skipping…"))
            if (queue.isNotEmpty()) {
                val next = queue.removeAt(0)
                _state.value = _state.value.copy(playlist = queue.toList())
                loadAndPlay(next.title)
            } else autoJump()
        }
    }

    fun seekTo(progress: Float) {
        val targetChar = (progress * articleText.length).toInt()
        currentAbsoluteChar = targetChar
        _state.value = _state.value.copy(playbackProgress = progress)
        seekToText(findSentenceStart(targetChar))
    }

    fun setSpeechRate(rate: Float) {
        androidTts?.setSpeechRate(rate)
        _state.value = _state.value.copy(speechRate = rate)
        if (_state.value.isPlaying && articleText.isNotBlank()) {
            seekToText(findSentenceStart(currentAbsoluteChar))
        }
    }

    fun setJumpInterval(minutes: Int) {
        _state.value = _state.value.copy(jumpIntervalMinutes = minutes)
        if (_state.value.isPlaying) scheduleJump()
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes == 0) { _state.value = _state.value.copy(sleepTimerSeconds = 0); return }
        var remaining = minutes * 60
        _state.value = _state.value.copy(sleepTimerSeconds = remaining)
        sleepTimerJob = scope.launch {
            while (remaining > 0) { delay(1000); remaining--; _state.value = _state.value.copy(sleepTimerSeconds = remaining) }
            pause()
        }
    }

    fun addToPlaylist(item: ArticleItem) {
        if (queue.none { it.title == item.title }) { queue.add(item); _state.value = _state.value.copy(playlist = queue.toList()) }
    }
    fun removeFromPlaylist(title: String) { queue.removeAll { it.title == title }; _state.value = _state.value.copy(playlist = queue.toList()) }
    fun clearPlaylist() { queue.clear(); _state.value = _state.value.copy(playlist = emptyList()) }

    // ─── Auto-jump ────────────────────────────────────────────────────────────

    private fun scheduleJump() {
        jumpJob?.cancel()
        val min = _state.value.jumpIntervalMinutes
        if (min > 0) jumpJob = scope.launch {
            delay(min * 60_000L)
            if (_state.value.isPlaying) autoJump()
        }
    }

    private suspend fun autoJump() {
        val title = _state.value.currentTitle
        val next = repository.getRelatedTitles(title).filter { it != title }.randomOrNull()
        if (next != null) repository.getFullArticle(next).onSuccess { playArticle(it) }.onFailure { playRandom() }
        else playRandom()
    }

    private fun fail(msg: String) {
        _state.value = _state.value.copy(isLoading = false, isPlaying = false, error = msg)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { description = getString(R.string.notification_channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiKiLocast").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi).setOngoing(true).setSilent(true).build()
    }

    private fun updateNotification(title: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title))

    private fun startFg(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        scope.cancel()
        kokoro?.release()
        androidTts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "wikifm_channel"
        const val NOTIFICATION_ID = 1
    }
}
