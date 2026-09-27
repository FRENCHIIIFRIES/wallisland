package com.wallisland.island

/** What a press of the Essential Key can do. */
enum class KeyAction(val label: String) {
    DEFAULT("Default (Essential Space)"),
    TORCH("Torch"),
    PLAY_PAUSE("Play / pause music"),
    TOGGLES("Quick toggles"),
    SCREENSHOT("Screenshot"),
    CAMERA("Camera"),
    ASSISTANT("Assistant"),
    RINGER("Ring / vibrate / silent"),
    FOCUS("Focus timer"),
    APP("Open an app…"),
    ;

    companion object {
        fun of(name: String) = values().firstOrNull { it.name == name } ?: DEFAULT
    }
}
