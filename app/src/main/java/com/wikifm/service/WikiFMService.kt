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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

data class WikiFMState(
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val currentTitle: String = "",
    val currentExtract: String = "",
    val speechRate: Float = 0.9f,
    val jumpIntervalMinutes: Int = 3,
    val error: String? = null,
    val playlist: List<ArticleItem> = emptyList(),
    val suggestions: List<ArticleItem> = emptyList(),
    val sleepTimerSeconds: Int = 0,
    val availableVoices: List<Voice> = emptyList(),
    val selectedVoiceName: String = "",
    val playbackProgress: Float = 0f   // 0.0 – 1.0
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

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null
    private var jumpJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var progressJob: Job? = null
    private val queue = mutableListOf<ArticleItem>()

    // Chunk tracking – @Volatile so the TTS callback thread and main thread agree
    private var articleChunks: List<String> = emptyList()
    private var chunkOffsets: List<Int> = emptyList()    // char start of each chunk in full text
    private var articleText: String = ""

    @Volatile private var pausedAtChunk: Int = 0
    @Volatile private var currentAbsoluteChar: Int = 0

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val savedVoice = prefs().getString("voice_name", "") ?: ""
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.US
                tts?.setPitch(0.85f)       // noticeably lower, calmer radio tone
                tts?.setSpeechRate(0.9f)   // slightly slower, more deliberate
                tts?.setOnUtteranceProgressListener(utteranceListener)

                val voices = qualityVoices()
                val selected = voices.find { it.name == savedVoice } ?: pickSoothingVoice(voices)
                if (selected != null) {
                    tts?.voice = selected
                    _state.value = _state.value.copy(
                        availableVoices = voices,
                        selectedVoiceName = selected.name
                    )
                }

                pendingText?.let { loadAndSpeak(it) }
                pendingText = null
            }
        }
    }

    private fun prefs() = getSharedPreferences("wikifm_data", Context.MODE_PRIVATE)

    private fun qualityVoices(): List<Voice> =
        tts?.voices
            ?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && it.quality >= Voice.QUALITY_NORMAL }
            ?.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            ?.toList() ?: emptyList()

    /**
     * Pick a voice that sounds calm and radio-like.
     * British → Indian → Irish → highest quality American.
     * These tend to have a warmer, more natural cadence for long-form listening.
     */
    private fun pickSoothingVoice(voices: List<Voice>): Voice? {
        for (country in listOf("GB", "IN", "IE", "AU", "NZ")) {
            val match = voices.filter { it.locale.country == country }.maxByOrNull { it.quality }
            if (match != null) return match
        }
        return voices.maxByOrNull { it.quality }
    }

    // ─── Utterance listener ─────────────────────────────────────────────────

    private val utteranceListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            val idx = utteranceId?.removePrefix("chunk_")?.toIntOrNull() ?: return
            pausedAtChunk = idx
            currentAbsoluteChar = chunkOffsets.getOrElse(idx) { 0 }
        }

        // Word-level progress (API 26+) – fires for every word spoken
        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            val idx = utteranceId.removePrefix("chunk_").toIntOrNull() ?: return
            currentAbsoluteChar = chunkOffsets.getOrElse(idx) { 0 } + start
        }

        override fun onError(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            val idx = utteranceId?.removePrefix("chunk_")?.toIntOrNull() ?: return
            if (idx == articleChunks.lastIndex && _state.value.isPlaying) {
                scope.launch {
                    if (queue.isNotEmpty()) {
                        val next = queue.removeAt(0)
                        _state.value = _state.value.copy(playlist = queue.toList())
                        loadAndPlay(next.title)
                    } else autoJump()
                }
            }
        }
    }

    // ─── Text-to-speech helpers ──────────────────────────────────────────────

    /**
     * Speak text for a brand-new article (resets all position state).
     */
    private fun loadAndSpeak(text: String) {
        val chunks = chunkText(text)
        val offsets = computeOffsets(chunks)
        articleText = text
        articleChunks = chunks
        chunkOffsets = offsets
        pausedAtChunk = 0
        currentAbsoluteChar = 0
        _state.value = _state.value.copy(playbackProgress = 0f)
        speakFrom(0)
        startProgressTimer()
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
        val offsets = mutableListOf<Int>()
        var pos = 0
        chunks.forEach { chunk -> offsets.add(pos); pos += chunk.length + 1 }
        return offsets
    }

    /**
     * Queue chunks starting from [fromChunk].
     * Always stops current playback first for a clean slate.
     */
    private fun speakFrom(fromChunk: Int) {
        if (!ttsReady || articleChunks.isEmpty()) return
        tts?.stop()
        for (i in fromChunk until articleChunks.size) {
            val mode = if (i == fromChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(articleChunks[i], mode, null, "chunk_$i")
        }
    }

    private fun speakText(text: String) {
        if (!ttsReady) { pendingText = text; return }
        pendingText = null
        loadAndSpeak(text)
    }

    // ─── Progress timer ──────────────────────────────────────────────────────

    private fun startProgressTimer() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(300)
                val total = articleText.length
                if (total > 0 && _state.value.isPlaying) {
                    val progress = (currentAbsoluteChar.toFloat() / total).coerceIn(0f, 1f)
                    _state.value = _state.value.copy(playbackProgress = progress)
                }
            }
        }
    }

    // ─── Public playback API ─────────────────────────────────────────────────

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
            repository.getRandomSummary()
                .onSuccess { playArticle(it) }
                .onFailure { fail("Couldn't load a random article") }
        }
    }

    private suspend fun loadAndPlay(title: String) {
        startFg(buildNotification("Loading…"))
        repository.getSummary(title)
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
        tts?.stop()   // pausedAtChunk / currentAbsoluteChar already updated by onStart/onRangeStart
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
        speakFrom(pausedAtChunk)   // ← exact chunk where we stopped
        scheduleJump()
        startProgressTimer()
    }

    fun skip() {
        scope.launch {
            tts?.stop()
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
        val chunkIdx = chunkOffsets.indices.lastOrNull { chunkOffsets[it] <= targetChar } ?: 0
        pausedAtChunk = chunkIdx
        currentAbsoluteChar = targetChar
        _state.value = _state.value.copy(playbackProgress = progress)
        if (_state.value.isPlaying) speakFrom(chunkIdx)
    }

    // ─── Jump / auto-advance ─────────────────────────────────────────────────

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
        if (next != null) repository.getSummary(next).onSuccess { playArticle(it) }.onFailure { playRandom() }
        else playRandom()
    }

    private fun fail(msg: String) {
        _state.value = _state.value.copy(isLoading = false, isPlaying = false, error = msg)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ─── Controls ────────────────────────────────────────────────────────────

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
        _state.value = _state.value.copy(speechRate = rate)
        if (_state.value.isPlaying && articleChunks.isNotEmpty()) speakFrom(pausedAtChunk)
    }

    fun setVoice(voice: Voice) {
        tts?.voice = voice
        prefs().edit().putString("voice_name", voice.name).apply()
        _state.value = _state.value.copy(selectedVoiceName = voice.name)
        if (_state.value.isPlaying) speakFrom(pausedAtChunk)
    }

    fun previewVoice(voice: Voice) {
        tts?.voice = voice
        tts?.speak("Hello. I am your Wikipedia radio guide.", TextToSpeech.QUEUE_FLUSH, null, "preview")
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

    // ─── Notification / foreground ───────────────────────────────────────────

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

    override fun onDestroy() { scope.cancel(); tts?.shutdown(); super.onDestroy() }

    companion object {
        const val CHANNEL_ID = "wikifm_channel"
        const val NOTIFICATION_ID = 1
    }
}
