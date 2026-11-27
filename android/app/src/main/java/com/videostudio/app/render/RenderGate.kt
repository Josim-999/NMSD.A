package com.videostudio.app.render

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Phone-wide limits for the heavy steps. The phone has one hardware video encoder and a few CPU
 * cores: three renders at once each run about three times slower. So videos render one at a time
 * (in the order they asked), while the next one prepares its voice, pictures and clips, and only
 * two clips are decoded at once across all videos.
 */
object RenderGate {
    val render = Semaphore(1, true)
    val decode = Semaphore(2, true)

    /** Waits for [gate], checking [cancelled] every second. Returns false if cancelled while waiting. */
    fun acquire(gate: Semaphore, cancelled: () -> Boolean, waiting: () -> Unit = {}): Boolean {
        if (gate.tryAcquire()) return true
        waiting()
        while (!cancelled()) if (gate.tryAcquire(1, TimeUnit.SECONDS)) return true
        return false
    }

    /** Runs [block] holding [gate]. */
    inline fun <T> with(gate: Semaphore, block: () -> T): T {
        gate.acquireUninterruptibly()
        try { return block() } finally { gate.release() }
    }
}
