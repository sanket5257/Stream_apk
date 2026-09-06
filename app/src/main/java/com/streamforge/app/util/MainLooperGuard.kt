package com.streamforge.app.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Last-resort net: keeps a main-thread exception from ending the process.
 *
 * The targeted guards elsewhere (safeAction on click handlers, safeLaunch on background work)
 * only cover the paths we thought to wrap. This covers the ones we didn't — a throw inside a
 * Compose composition, a view's draw pass, a framework callback, a third-party library's
 * posted runnable. All of those run on the main looper, and any of them ends the app.
 *
 * How it works: the main thread's message loop is re-entered inside a try/catch. When a
 * message throws, `Looper.loop()` unwinds; we catch it and call `loop()` again, so the app
 * carries on with the next message instead of dying. The stack does not grow — each catch
 * unwinds the previous loop before the next one starts.
 *
 * ## The trade-off, stated plainly
 *
 * This hides bugs. A swallowed exception can leave a screen half-drawn or a value stale, and
 * the app keeps running in that state rather than restarting clean. That is the deal being
 * made deliberately: for a live-broadcast tool, a glitched control is recoverable and a dead
 * process mid-stream is not.
 *
 * Two safeguards stop it making things worse than the crash it prevents:
 *
 *  - **Burst limit.** An exception thrown from the draw pass repeats every frame, and
 *    swallowing it forever would spin the CPU and freeze the phone — worse than crashing.
 *    More than [BURST_LIMIT] failures inside [BURST_WINDOW_MS] means it isn't a one-off, so
 *    the throwable is rethrown and the app dies normally.
 *  - **VM errors are never swallowed.** After an OutOfMemoryError the process has nothing
 *    left to run with; pretending otherwise just produces a slower, stranger death.
 *
 * Everything swallowed is written to the non-fatal log by [CrashReporter.recordNonFatal], so
 * this suppresses the crash without suppressing the evidence.
 */
object MainLooperGuard {

    private const val TAG = "MainLooperGuard"

    /** Failures closer together than this are treated as one repeating fault, not separate ones. */
    private const val BURST_WINDOW_MS = 4_000L

    /** How many failures inside the window are tolerated before giving up and crashing. */
    private const val BURST_LIMIT = 6

    @Volatile
    private var installed = false

    /** Call once, as early as possible — [android.app.Application.onCreate]. */
    fun install() {
        if (installed) return
        installed = true
        // Posting means the guarded loop starts once the framework's own loop is running, so
        // we re-enter it rather than racing it.
        Handler(Looper.getMainLooper()).post { guardedLoop() }
    }

    private fun guardedLoop() {
        var windowStartedAt = 0L
        var failuresInWindow = 0

        while (true) {
            try {
                Looper.loop()
                // loop() returns only when the main looper quits — the process is on its way
                // out and there is nothing left to guard.
                return
            } catch (t: Throwable) {
                // An OOM or similar leaves the VM with no headroom to continue.
                if (t is VirtualMachineError) throw t

                val now = SystemClock.elapsedRealtime()
                if (now - windowStartedAt > BURST_WINDOW_MS) {
                    windowStartedAt = now
                    failuresInWindow = 0
                }
                failuresInWindow++

                if (failuresInWindow > BURST_LIMIT) {
                    Log.e(TAG, "Main thread failing repeatedly; letting it crash", t)
                    throw t
                }

                Log.e(TAG, "Swallowed a main-thread exception (#$failuresInWindow)", t)
                CrashReporter.recordNonFatal(TAG, "unhandled main-thread exception", t)
            }
        }
    }
}
