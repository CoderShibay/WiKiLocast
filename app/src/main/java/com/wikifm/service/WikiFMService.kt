package com.wikifm.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.wikifm.MainActivity
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
    val speechRate: Float = 1.0f,
    val jumpIntervalMinutes: Int = 3,
    val error: String? = null
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

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(utteranceListener)
                pendingText?.let { speakText(it) }
                pendingText = null
            }
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onError(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            if (utteranceId == LAST_CHUNK_ID && _state.value.isPlaying) {
                scope.launch { jumpToRelated() }
            }
        }
    }

    fun playTitle(title: String) {
        scope.launch {
            _state.value = _state.value.copy(isPlaying = true, isLoading = true, error = null)
            startForegroundCompat(buildNotification("Loading…"))
            val result = repository.getSummary(title)
            result.onSuccess { playArticle(it) }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isLoading = false, isPlaying = false,
                        error = "Failed: ${e.message}"
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
        }
    }

    fun playRandom() {
        scope.launch {
            _state.value = _state.value.copy(isPlaying = true, isLoading = true, error = null)
            startForegroundCompat(buildNotification("Tuning in…"))
            val result = repository.getRandomSummary()
            result.onSuccess { playArticle(it) }
                .onFailure {
                    _state.value = _state.value.copy(isLoading = false, error = "Couldn't load article")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
        }
    }

    private fun playArticle(summary: WikiSummary) {
        val text = summary.extract.ifBlank { summary.title }
        _state.value = _state.value.copy(
            currentTitle = summary.title,
            currentExtract = text,
            isLoading = false
        )
        updateNotification(summary.title)
        speakText(text)
        scheduleJump()
    }

    private fun speakText(text: String) {
        if (!ttsReady) {
            pendingText = text
            return
        }
        pendingText = null
        tts?.stop()
        val chunks = chunkText(text)
        chunks.forEachIndexed { i, chunk ->
            val utteranceId = if (i == chunks.lastIndex) LAST_CHUNK_ID else "chunk_$i"
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(chunk, mode, null, utteranceId)
        }
    }

    private fun chunkText(text: String): List<String> {
        val maxLen = 3500
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        return sentences.fold(mutableListOf()) { acc, s ->
            if (acc.isEmpty() || acc.last().length + s.length + 1 > maxLen) acc.add(s)
            else acc[acc.lastIndex] = "${acc.last()} $s"
            acc
        }
    }

    private fun scheduleJump() {
        jumpJob?.cancel()
        val minutes = _state.value.jumpIntervalMinutes
        if (minutes > 0) {
            jumpJob = scope.launch {
                delay(minutes * 60_000L)
                if (_state.value.isPlaying) jumpToRelated()
            }
        }
    }

    private suspend fun jumpToRelated() {
        val title = _state.value.currentTitle
        val related = repository.getRelatedTitles(title)
        val next = related.filter { it != title }.randomOrNull()
        if (next == null) { playRandom(); return }
        val result = repository.getSummary(next)
        result.onSuccess { playArticle(it) }
            .onFailure { playRandom() }
    }

    fun pause() {
        tts?.stop()
        jumpJob?.cancel()
        _state.value = _state.value.copy(isPlaying = false)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    fun resume() {
        if (_state.value.currentExtract.isBlank()) return
        _state.value = _state.value.copy(isPlaying = true)
        startForegroundCompat(buildNotification(_state.value.currentTitle))
        speakText(_state.value.currentExtract)
        scheduleJump()
    }

    fun skip() {
        scope.launch {
            tts?.stop()
            jumpJob?.cancel()
            jumpToRelated()
        }
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
        _state.value = _state.value.copy(speechRate = rate)
    }

    fun setJumpInterval(minutes: Int) {
        _state.value = _state.value.copy(jumpIntervalMinutes = minutes)
        if (_state.value.isPlaying) scheduleJump()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.wikifm.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(com.wikifm.R.string.notification_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wiki FM")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(title: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "wikifm_channel"
        const val NOTIFICATION_ID = 1
        const val LAST_CHUNK_ID = "LAST_CHUNK"
    }
}
