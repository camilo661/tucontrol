package com.tucontrol.domain.model

/** TV platforms that TuControl knows how to drive over the local network. */
enum class TvBrand(val displayName: String) {
    SAMSUNG("Samsung"),
    LG("LG"),
    SONY("Sony"),
    ROKU("Roku"),
    ANDROID_TV("Android TV"),
    UNKNOWN("Smart TV"),
    ;

    val isSupported: Boolean get() = this != UNKNOWN
}
