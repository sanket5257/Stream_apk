package com.streamforge.app.util

import android.util.Log

/**
 * Click handlers that cannot kill the process.
 *
 * A tap is the least forgiving place in the app for an unhandled throw. The handler runs on
 * the main thread with nothing above it but the framework's uncaught-exception handler, so
 * *any* exception — a corrupt DataStore file, an unusable keystore, a GL object torn down a
 * frame early — ends the process. Android then relaunches at the login screen, and from the
 * user's side the app simply "went back to the phone's home screen" when they pressed a
 * button. That symptom is indistinguishable across a dozen unrelated causes, which is what
 * made it so hard to report.
 *
 * Wrapping the handler turns every one of those into a logged no-op: the button appears not
 * to work, which is a bad outcome but a survivable one, and [onError] lets the screen say so.
 * The throwable is also recorded via [CrashReporter.recordNonFatal] so a swallowed failure is
 * still diagnosable afterwards rather than being silently lost.
 */

/** Run [block], converting any throw into a log line plus an optional [onError] callback. */
fun runGuarded(
    tag: String,
    what: String,
    onError: ((Throwable) -> Unit)? = null,
    block: () -> Unit,
) {
    try {
        block()
    } catch (t: Throwable) {
        Log.e(tag, "$what failed", t)
        CrashReporter.recordNonFatal(tag, what, t)
        try {
            onError?.invoke(t)
        } catch (inner: Throwable) {
            // Reporting the failure must never be the thing that causes the next one.
            Log.e(tag, "Reporting the failure of $what also failed", inner)
        }
    }
}

/** Guarded no-argument click handler. */
fun safeAction(
    tag: String,
    what: String,
    onError: ((Throwable) -> Unit)? = null,
    block: () -> Unit,
): () -> Unit = { runGuarded(tag, what, onError, block) }

/** Guarded one-argument handler (an id, a chosen value). */
fun <A> safeAction1(
    tag: String,
    what: String,
    onError: ((Throwable) -> Unit)? = null,
    block: (A) -> Unit,
): (A) -> Unit = { a -> runGuarded(tag, what, onError) { block(a) } }

/** Guarded two-argument handler. */
fun <A, B> safeAction2(
    tag: String,
    what: String,
    onError: ((Throwable) -> Unit)? = null,
    block: (A, B) -> Unit,
): (A, B) -> Unit = { a, b -> runGuarded(tag, what, onError) { block(a, b) } }

/** Guarded three-argument handler. */
fun <A, B, C> safeAction3(
    tag: String,
    what: String,
    onError: ((Throwable) -> Unit)? = null,
    block: (A, B, C) -> Unit,
): (A, B, C) -> Unit = { a, b, c -> runGuarded(tag, what, onError) { block(a, b, c) } }

/**
 * Read a value that might throw, falling back rather than failing.
 *
 * Used for things read during composition (a stored username, a preference), where a throw
 * would take down the whole screen on the way to drawing it.
 */
fun <T> safeGet(tag: String, what: String, fallback: T, block: () -> T): T = try {
    block()
} catch (t: Throwable) {
    Log.e(tag, "$what failed", t)
    CrashReporter.recordNonFatal(tag, what, t)
    fallback
}
