package com.tucontrol.domain.model

/** One entry of the play queue of the app playing on the TV. */
data class MediaQueueItem(
    val id: Long,
    val title: String,
    val subtitle: String,
    val isPlaying: Boolean,
)
