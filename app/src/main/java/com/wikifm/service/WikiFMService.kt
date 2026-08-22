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
    val kokoroDownloading: Boolean = false,
    val kokoroReady: Boolean = false,
    val kokoroProgress: Float = 0f,
    val kokoroLabel: String = ""
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

    // Kokoro (primary — downloads on first launch)
    private val modelManager by lazy { ModelManager(this) }
    private var kokoro: KokoroEngine? = null
    private var kokoroJob: Job? = null

    // Android TTS (fallback while Kokoro downloads or if unavailable)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null
    private var jumpJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var progressJob: Job? = null
    private val queue = mutableListOf<ArticleItem>()

    private var articleText: String = ""
    private var articleChunks: List<String> = emptyList()
    private var chunkOffsets: List<Int> = emptyList()
    @Volatile private var pausedAtChunk: Int = 0
    @Volatile private var currentAbsoluteChar: Int = 0

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initKokoroAsync()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.US
                tts?.setPitch(0.85f)
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(utteranceListener)
                pickBestVoice()
                pendingText?.let { loadAndSpeak(it) }
                pendingText = null
            }
        }
    }

    private fun initKokoroAsync() {
        scope.launch(Dispatchers.IO) {
            if (modelManager.isReady()) {
                tryStartKokoro()
            } else {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(kokoroDownloading = true, kokoroLabel = "Downloading Kokoro voice…")
                }
                val result = modelManager.download { done, total, label ->
                    val progress = if (total > 0) done.toFloat() / total else 0f
                    _state.value = _state.value.copy(kokoroProgress = progress, kokoroLabel = label)
                }
                if (result.isSuccess) tryStartKokoro()
                else withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(kokoroDownloading = false, kokoroLabel = "Download failed — using system voice")
                }
            }
        }
    }

    private fun tryStartKokoro() {
        val engine = KokoroEngine(modelManager.modelDir)
        if (engine.init()) {
            kokoro = engine
            _state.value = _state.value.copy(kokoroReady = true, kokoroDownloading = false, kokoroLabel = "Kokoro voice active")
        } else {
            _state.value = _state.value.copy(kokoroDownloading = false, kokoroLabel = "Voice init failed — using system voice")
        }
    }

    private fun pickBestVoice() {
        val voices = tts?.voices
            ?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && it.quality >= Voice.QUALITY_NORMAL }
            ?.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            ?.toList() ?: return
        for (country in listOf("GB", "IN", "IE", "AU", "NZ")) {
            val match = voices.filter { it.locale.country == country }.maxByOrNull { it.quality }
            if (match != null) { tts?.voice = match; return }
        }
        voices.maxByOrNull { it.quality }?.let { tts?.voice = it }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
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
        speakFrom(0)
        startProgressTimer()
    }

    private fun loadAndSpeak(text: String) {
        if (!ttsReady) { pendingText = text; return }
        articleText = text
        articleChunks = chunkText(text)
        chunkOffsets = computeOffsets(articleChunks)
        pausedAtChunk = 0
        currentAbsoluteChar = 0
        _state.value = _state.value.copy(playbackProgress = 0f)
        speakFrom(0)
        startProgressTimer()
    }

    private fun speakFrom(fromChunk: Int) {
        val engine = kokoro
        if (engine != null) {
            // Kokoro: stream each chunk via neural TTS
            kokoroJob?.cancel()
            engine.stop()
            kokoroJob = scope.launch(Dispatchers.IO) {
                for (i in fromChunk until articleChunks.size) {
                    if (!isActive || !_state.value.isPlaying) break
                    pausedAtChunk = i
                    var done = false
                    engine.speak(
                        text = articleChunks[i],
                        rate = _state.value.speechRate,
                        onProgress = { offset -> currentAbsoluteChar = chunkOffsets.getOrElse(i) { 0 } + offset }
                    ) { done = true }
                    if (!done) break
                    if (i == articleChunks.lastIndex && _state.value.isPlaying) {
                        scope.launch { onArticleFinished() }
                    }
                }
            }
        } else {
            // Android TTS fallback
            if (!ttsReady || articleChunks.isEmpty()) return
            tts?.stop()
            for (i in fromChunk until articleChunks.size) {
                val mode = if (i == fromChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts?.speak(articleChunks[i], mode, null, "chunk_$i")
            }
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

    private suspend fun onArticleFinished() {
        if (queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            _state.value = _state.value.copy(playlist = queue.toList())
            loadAndPlay(next.title)
        } else autoJump()
    }

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
        loadAndSpeak(text)
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
        kokoroJob?.cancel(); kokoro?.stop()
        tts?.stop()
        jumpJob?.cancel(); sleepTimerJob?.cancel(); progressJob?.cancel()
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
            tts?.stop(); jumpJob?.cancel()
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
        tts?.setSpeechRate(rate)
        _state.value = _state.value.copy(speechRate = rate)
        if (_state.value.isPlaying && articleText.isNotBlank()) seekToText(findSentenceStart(currentAbsoluteChar))
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

    override fun onDestroy() { scope.cancel(); kokoro?.release(); tts?.shutdown(); super.onDestroy() }

    companion object {
        const val CHANNEL_ID = "wikifm_channel"
        const val NOTIFICATION_ID = 1
    }
}
