package me.desidubanime.tv.data.api

import java.util.concurrent.atomic.AtomicInteger

/**
 * Priority gate between the user's foreground work and the app's background
 * catalog work.
 *
 * The original cold-start stall ("app ko 1 min idle chorna padta hai") was
 * contention: the slug/title sweeps and the full-catalog fetches hammer the
 * SAME hosts the click path needs (ADH, adhlinks, DDA), and OkHttp only opens 5
 * connections per host — so a detail-open request queued behind ~600 sweep
 * pages.
 *
 * Separate clients ([Network.client] / [Network.feed] / [Network.bulk] /
 * [Network.images]) give each lane its own dispatcher, which fixes the queueing.
 * This gate adds the second half of the rule: while a CLICK is being served,
 * background requests STOP and wait — the click gets the device's whole network
 * budget, not a share of it.
 *
 * There are two independent holds:
 *  - [priority]: a click (detail open, episode list, server resolve);
 *  - [holdFeed]: the home feed merge, which is equally "the user is waiting on
 *    this" at launch but must NOT count as a click, because the feed lane's own
 *    interceptor waits for clicks (see [awaitIdle]) — counting the feed as a
 *    click made every feed request wait for the feed to finish.
 *
 * Both are counters, not flags: overlapping operations must not let the first
 * one to finish re-open the gate underneath the second.
 */
object InteractionGate {

    private val clicks = AtomicInteger(0)
    private val feedHold = AtomicInteger(0)
    private val lock = Object()

    /** True while at least one click-time operation is in flight. */
    val busy: Boolean get() = clicks.get() > 0

    /** True while a foreground operation (click OR feed build) is running. */
    val foreground: Boolean get() = clicks.get() > 0 || feedHold.get() > 0

    /** Mark a click-time operation as running for the duration of [block]. */
    suspend fun <T> priority(block: suspend () -> T): T {
        clicks.incrementAndGet()
        try {
            return block()
        } finally {
            release(clicks)
        }
    }

    /**
     * Mark a home-feed build as the foreground job. Background requests park
     * for its duration; click requests do not.
     */
    suspend fun <T> holdFeed(block: suspend () -> T): T {
        feedHold.incrementAndGet()
        try {
            return block()
        } finally {
            release(feedHold)
        }
    }

    private fun release(counter: AtomicInteger) {
        if (counter.decrementAndGet() <= 0) {
            // Clamp: a stray leave() must never drive the counter negative and
            // wedge the gate shut for the rest of the process.
            counter.updateAndGet { if (it < 0) 0 else it }
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /** Block the calling thread while a CLICK is in flight, up to [maxWaitMs]. */
    fun awaitIdle(maxWaitMs: Long = DEFAULT_MAX_WAIT_MS) = await({ busy }, maxWaitMs)

    /** Block while ANY foreground work (click or feed build) is in flight. */
    fun awaitForeground(maxWaitMs: Long = DEFAULT_MAX_WAIT_MS) = await({ foreground }, maxWaitMs)

    /**
     * The cap matters: a click that never completes (dead host, 35s call
     * timeout) must not freeze background work forever, so the sweeps resume on
     * their own after the wait elapses.
     */
    private fun await(condition: () -> Boolean, maxWaitMs: Long) {
        if (!condition()) return
        val deadline = System.currentTimeMillis() + maxWaitMs
        synchronized(lock) {
            while (condition()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return
                try {
                    lock.wait(left)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private const val DEFAULT_MAX_WAIT_MS = 8_000L
}
