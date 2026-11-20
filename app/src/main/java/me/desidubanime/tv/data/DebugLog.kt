package me.desidubanime.tv.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app diagnostic log for the server-resolution path.
 *
 * The app had no logging at all, which made "GDFlix is stuck loading" an
 * unanswerable question — and on a TV box there is no adb to hand, so asking
 * for logcat is not a real option either. Every step of a resolve is recorded
 * here and shown BOTH in Settings and on the player's own loading screen: a
 * failing resolve can only be read where it happens, since leaving the player
 * to go to Settings means pressing BACK, which cancels the resolve.
 *
 * Exposed as a [StateFlow] rather than a single callback so more than one
 * screen can observe it at once — with a single-listener slot, whichever
 * screen registered last silently blinded the other.
 *
 * Ring buffer, capped and in-memory only: it must never grow without bound or
 * survive to become another thing that needs clearing.
 */
object DebugLog {

    private const val TAG = "DesiDub"
    private const val MAX = 300

    private val entries = ArrayDeque<String>()
    private var startedAt = 0L

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Newest last. Collect for live updates while a resolve runs. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Synchronized
    fun log(area: String, message: String) {
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        val t = (System.currentTimeMillis() - startedAt) / 1000.0
        val line = String.format("[%6.2fs] %-9s %s", t, area, message)
        entries.addLast(line)
        while (entries.size > MAX) entries.removeFirst()
        Log.d(TAG, line)
        _lines.value = entries.toList()
    }

    /** Snapshot of the current buffer (for the crash handler). */
    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        startedAt = 0L
        _lines.value = emptyList()
    }
}
