package com.wikifm

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wikifm.data.SearchResult
import com.wikifm.data.WikipediaRepository
import com.wikifm.service.WikiFMService
import com.wikifm.service.WikiFMState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class WikiFMViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = WikipediaRepository()

    private val _service = MutableStateFlow<WikiFMService?>(null)
    val state: StateFlow<WikiFMState> = _service
        .flatMapLatest { svc -> svc?.state ?: flowOf(WikiFMState()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WikiFMState())

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var bound = false
    private var pendingAction: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            _service.value = (binder as WikiFMService.WikiFMBinder).getService()
            bound = true
            pendingAction?.invoke()
            pendingAction = null
        }
        override fun onServiceDisconnected(name: ComponentName) {
            _service.value = null
            bound = false
        }
    }

    init {
        val intent = Intent(app, WikiFMService::class.java)
        app.startService(intent)
        app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun search(query: String) {
        if (query.isBlank()) { _searchResults.value = emptyList(); return }
        viewModelScope.launch {
            _isSearching.value = true
            _searchResults.value = repository.searchArticles(query)
            _isSearching.value = false
        }
    }

    fun clearSearch() { _searchResults.value = emptyList() }

    fun playTitle(title: String) {
        clearSearch()
        val svc = _service.value
        if (svc != null) svc.playTitle(title)
        else pendingAction = { _service.value?.playTitle(title) }
    }

    fun playRandom() {
        clearSearch()
        val svc = _service.value
        if (svc != null) svc.playRandom()
        else pendingAction = { _service.value?.playRandom() }
    }

    fun pause() = _service.value?.pause()
    fun resume() = _service.value?.resume()
    fun skip() = _service.value?.skip()
    fun setSpeechRate(rate: Float) = _service.value?.setSpeechRate(rate)
    fun setJumpInterval(minutes: Int) = _service.value?.setJumpInterval(minutes)

    override fun onCleared() {
        if (bound) getApplication<Application>().unbindService(connection)
        super.onCleared()
    }
}
