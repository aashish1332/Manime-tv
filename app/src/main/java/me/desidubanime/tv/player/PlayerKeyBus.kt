package me.desidubanime.tv.player

/**
 * Bridges hardware key events from the Activity to the Player screen.
 * PlayerView (an AndroidView) holds focus during playback, so Compose's
 * onPreviewKeyEvent can miss the MENU key — we route it via the Activity instead.
 */
object PlayerKeyBus {
    var onMenu: (() -> Unit)? = null
    var onBack: (() -> Boolean)? = null  // return true if handled (e.g. menu was open)
}
