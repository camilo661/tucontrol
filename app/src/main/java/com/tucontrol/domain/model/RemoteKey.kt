package com.tucontrol.domain.model

/** Platform-agnostic remote control keys. Each protocol maps them to its own codes. */
enum class RemoteKey {
    POWER,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
    CHANNEL_UP,
    CHANNEL_DOWN,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    OK,
    BACK,
    HOME,
    MENU,
    INPUT,
    PLAY_PAUSE,
    PLAY,
    PAUSE,
    STOP,
    REWIND,
    FAST_FORWARD,
    NEXT,
    PREVIOUS,
    INFO,
    GUIDE,
    CAPTIONS,
    SETTINGS,
    SEARCH,
    BRIGHTNESS_UP,
    BRIGHTNESS_DOWN,
    NUM_0,
    NUM_1,
    NUM_2,
    NUM_3,
    NUM_4,
    NUM_5,
    NUM_6,
    NUM_7,
    NUM_8,
    NUM_9,
    ;

    companion object {
        /** The number keys for each digit of [number], e.g. 105 → [NUM_1, NUM_0, NUM_5]. */
        fun digitsOf(number: Int): List<RemoteKey> = number.toString().map { NUMBERS[it.digitToInt()] }

        private val NUMBERS = listOf(NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9)
    }
}
