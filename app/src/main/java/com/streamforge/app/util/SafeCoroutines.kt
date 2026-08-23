package com.streamforge.app.util

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * `launch` that can't kill the process.
 *
 * A plain `lifecycleScope.launch { ... }` has no exception handler, so anything thrown inside
 * it reaches the thread's uncaught-exception handler and the app dies. Most of this app's
 * background work — DataStore reads, EncryptedSharedPreferences, GL/encoder calls, network —
 * can throw for reasons entirely outside our control (corrupt prefs file, an unusable
 * keystore, a pipeline mid-teardown). None of those should be fatal.
 *
 * [safeLaunch] keeps cancellation semantics intact (a cancelled scope still cancels normally)
 * and swallows everything else after logging it, so a failed refresh degrades to "the label
 * didn't update" instead of "the app went back to the home screen".
 */
fun LifecycleOwner.safeLaunch(
    tag: String,
    what: String = "background work",
    block: suspend CoroutineScope.() -> Unit
): Job = lifecycleScope.safeLaunch(tag, what, block)

fun CoroutineScope.safeLaunch(
    tag: String,
    what: String = "background work",
    block: suspend CoroutineScope.() -> Unit
): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(tag, "$what failed", t)
    }
}

/**
 * True while the owner is at least STARTED — i.e. it still has a live window, so showing a
 * dialog or a Presentation on it is safe. Work that resumes after a suspend point must check
 * this before touching the UI: `lifecycleScope` only cancels at DESTROYED, which leaves a
 * window where the activity is finishing and its window token is already gone
 * (BadTokenException: "Unable to add window — is your activity running?").
 */
fun LifecycleOwner.isUiAlive(): Boolean =
    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
