package com.tucontrol.domain.model

/** A single executable step sent to a TV. */
sealed interface TvAction {
    data class Press(val key: RemoteKey, val times: Int = 1) : TvAction {
        init {
            require(times in 1..MAX_REPEAT) { "times must be within 1..$MAX_REPEAT" }
        }
    }

    data class LaunchApp(val app: StreamingApp) : TvAction

    /** Opens any app installed on the TV by its spoken name ("abre Xuper TV"). */
    data class OpenApp(val name: String) : TvAction {
        init {
            require(name.isNotBlank()) { "name must not be blank" }
        }
    }

    /** Opens the TV's settings, at [section] where the platform allows it. */
    data class OpenSettings(val section: SettingsSection = SettingsSection.GENERAL) : TvAction

    /** Tunes a channel by number ("pon el canal 7"), typed on the number keys. */
    data class TuneChannel(val number: Int) : TvAction {
        init {
            require(number in 1..MAX_CHANNEL) { "channel must be within 1..$MAX_CHANNEL" }
        }
    }

    /** Searches for content, inside [app] when given ("busca Stranger Things en Netflix"). */
    data class Search(val query: String, val app: StreamingApp? = null) : TvAction {
        init {
            require(query.isNotBlank()) { "query must not be blank" }
        }
    }

    companion object {
        const val MAX_REPEAT = 30
        const val MAX_CHANNEL = 9_999
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
