package com.tucontrol.domain.model

/** Streaming apps that can be launched by voice ("abre Netflix"). */
enum class StreamingApp(val displayName: String) {
    NETFLIX("Netflix"),
    YOUTUBE("YouTube"),
    PRIME_VIDEO("Prime Video"),
    DISNEY_PLUS("Disney+"),
    SPOTIFY("Spotify"),
    MAX("Max"),
    APPLE_TV("Apple TV"),
    PARAMOUNT_PLUS("Paramount+"),
    CRUNCHYROLL("Crunchyroll"),
    PLUTO_TV("Pluto TV"),
    PLEX("Plex"),
    VIX("ViX"),
}
