package com.tucontrol.domain.model

/** A single executable step sent to a TV. */
sealed interface TvAction {
    data class Press(val key: RemoteKey, val times: Int = 1) : TvAction {
        init {
            require(times in 1..MAX_REPEAT) { "times must be within 1..$MAX_REPEAT" }
        }
    }

    data class LaunchApp(val app: StreamingApp) : TvAction

    companion object {
        const val MAX_REPEAT = 30
    }
}

/**
 * The result of interpreting a voice phrase: ordered steps to run on the TV.
 * An empty plan means the phrase was not understood.
 */
data class CommandPlan(val actions: List<TvAction>) {
    val isEmpty: Boolean get() = actions.isEmpty()

    companion object {
        val EMPTY = CommandPlan(emptyList())
    }
}
