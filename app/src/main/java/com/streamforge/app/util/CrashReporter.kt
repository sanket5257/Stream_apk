package com.streamforge.app.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.streamforge.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records every uncaught crash to a file so a field failure can actually be diagnosed.
 *
 * Before this, a crash left nothing behind: the process died, Android relaunched the app at
 * LoginActivity, and the only evidence was the user saying "it went back to home". The handler
 * chains to the platform's default one afterwards, so system behaviour (the "app stopped"
 * dialog, any store-side reporting) is unchanged — we only add a breadcrumb.
 *
 * The log is app-private, capped, and never uploaded anywhere; [readReport] is what the
 * in-app "last crash" prompt reads.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val FILE_NAME = "last_crash.txt"
    private const val NON_FATAL_FILE_NAME = "non_fatals.txt"

    /** Keep the file small — a stack trace is a few KB; this bounds a pathological loop. */
    private const val MAX_BYTES = 256 * 1024

    /**
     * The non-fatal log is append-only and could otherwise grow without bound, so it is
     * rewritten from the tail once it passes this. Smaller than [MAX_BYTES] because these are
     * failures the app already survived — recent ones are what matter.
     */
    private const val MAX_NON_FATAL_BYTES = 128 * 1024

    @Volatile
    private var installed = false

    /**
     * Held so [recordNonFatal] can be called from anywhere — a click handler deep in a
     * composable has no Context to hand, and requiring one would mean threading it through
     * every guarded call site. This is the *application* context, so it leaks nothing.
     */
    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        this.appContext = appContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(appContext, thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to record crash", t)
            }
            // Always hand back to the platform so the process dies the way Android expects.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            appendLine("StreamForge crash report")
            appendLine("time: $stamp")
            appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("thread: ${thread.name}")
            appendLine()
            append(stack)
        }
        val file = File(context.filesDir, FILE_NAME)
        file.writeText(report.take(MAX_BYTES))
    }

    /** The most recent crash report, or null if the app hasn't crashed since it was cleared. */
    fun readReport(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return try {
            if (file.exists() && file.length() > 0) file.readText() else null
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read crash report", t)
            null
        }
    }

    /**
     * Record a throwable the app caught and survived.
     *
     * The guards added around click handlers and background work stop a failure from killing
     * the process, but a silently swallowed exception is its own kind of bug — the button
     * just does nothing and there is no way to find out why. Everything the app swallows
     * lands here, so a "that option doesn't work" report can still be traced to a stack
     * trace rather than guessed at.
     *
     * Deliberately best-effort and never throws: this is called from inside catch blocks
     * whose entire purpose is to stop exceptions propagating.
     */
    fun recordNonFatal(tag: String, what: String, throwable: Throwable) {
        val context = appContext ?: return
        try {
            val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val entry = buildString {
                appendLine("--- $stamp [$tag] $what")
                append(stack)
                appendLine()
            }
            val file = File(context.filesDir, NON_FATAL_FILE_NAME)
            // Trim from the front when the log gets long: the newest failures are the ones
            // being investigated, and an unbounded append-only file on a phone is a bug.
            if (file.exists() && file.length() > MAX_NON_FATAL_BYTES) {
                val kept = file.readText().takeLast(MAX_NON_FATAL_BYTES / 2)
                file.writeText(kept.substringAfter("--- ", kept))
            }
            file.appendText(entry)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to record non-fatal", t)
        }
    }

    /** Everything the app has caught and survived since this was last cleared. */
    fun readNonFatals(context: Context): String? {
        val file = File(context.applicationContext.filesDir, NON_FATAL_FILE_NAME)
        return try {
            if (file.exists() && file.length() > 0) file.readText() else null
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read non-fatals", t)
            null
        }
    }

    /** Drop the stored report once the user has seen (or dismissed) it. */
    fun clear(context: Context) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to clear crash report", t)
        }
    }
}
