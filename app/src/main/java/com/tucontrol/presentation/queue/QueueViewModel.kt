package com.tucontrol.presentation.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tucontrol.data.session.RemoteSession
import com.tucontrol.domain.model.MediaQueueItem
import com.tucontrol.presentation.common.containerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface QueueUiState {
    data object Loading : QueueUiState
    data class Ready(val items: List<MediaQueueItem>) : QueueUiState
    /** The app playing shares no list (or nothing is playing). */
    data object Empty : QueueUiState
    /** The TV's debugging access is off, so the list cannot be read. */
    data object Unavailable : QueueUiState
}

class QueueViewModel(private val session: RemoteSession) : ViewModel() {

    private val mutableState = MutableStateFlow<QueueUiState>(QueueUiState.Loading)
    val uiState: StateFlow<QueueUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.value = QueueUiState.Loading
        viewModelScope.launch {
            val items = session.mediaQueue()
            mutableState.value = when {
                items == null -> QueueUiState.Unavailable
                items.isEmpty() -> QueueUiState.Empty
                else -> QueueUiState.Ready(items)
            }
        }
    }

    fun play(item: MediaQueueItem, onPlayed: () -> Unit) {
        viewModelScope.launch {
            if (session.playQueueItem(item.id)) onPlayed()
        }
    }

    companion object {
        val Factory = containerFactory { QueueViewModel(session) }
    }
}
