package com.streamforge.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.streamforge.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Sideload updater: checks a small JSON manifest you host alongside each release, downloads
 * the APK, and hands it to Android's package installer.
 *
 * Distribution without Play Store rests on two rules, both enforced by the platform:
 *  - The new APK must be signed with the SAME key, or the install is rejected outright.
 *  - [Release.versionCode] must be greater than the installed one; Android treats an equal or
 *    lower code as a downgrade and refuses it. versionName is cosmetic — only the code counts.
 *
 * The manifest is deliberately dumb (a static file on any host — GitHub Releases, a bucket, a
 * web server), so publishing an update is "upload APK, edit JSON".
 */
object UpdateManager {

    /**
     * Where the update manifest lives — set `UPDATE_MANIFEST_URL` in local.properties (or the
     * environment) at build time. It is fetched over HTTPS on every check, so the file has to
     * be publicly readable. Blank disables updating entirely.
     */
    val MANIFEST_URL: String = BuildConfig.UPDATE_MANIFEST_URL

    /** False when no update channel was configured for this build. */
    val isConfigured: Boolean get() = MANIFEST_URL.isNotBlank()

    private const val TAG = "UpdateManager"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val APK_FILE_NAME = "streamforge-update.apk"

    /** A published release, as described by the manifest. */
    data class Release(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        /** When true the app should insist on the update rather than offer it. */
        val mandatory: Boolean
    )

    sealed interface CheckResult {
        data class Available(val release: Release) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val reason: String) : CheckResult
    }

    /**
     * Fetch the manifest and compare it with this build. Network work only — never touches
     * the UI, so callers can run it silently on launch or behind a button.
     */
    suspend fun check(manifestUrl: String = MANIFEST_URL): CheckResult = withContext(Dispatchers.IO) {
        if (manifestUrl.isBlank()) {
            return@withContext CheckResult.Failed("No update channel is configured for this build")
        }
        try {
            val body = readText(manifestUrl)
            val release = parse(JSONObject(body))
            if (release.versionCode > BuildConfig.VERSION_CODE) {
                CheckResult.Available(release)
            } else {
                CheckResult.UpToDate
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Update check failed", e)
            CheckResult.Failed(e.message ?: "Couldn't reach the update server")
        }
    }

    private fun parse(json: JSONObject): Release = Release(
        versionCode = json.getInt("versionCode"),
        versionName = json.optString("versionName", ""),
        apkUrl = json.getString("apkUrl"),
        notes = json.optString("notes", ""),
        mandatory = json.optBoolean("mandatory", false)
    )

    /**
     * Download [release]'s APK into app-private storage, reporting 0..100 progress.
     *
     * Kept in getExternalFilesDir/cacheDir rather than Downloads so no storage permission is
     * needed; the installer reads it through our FileProvider grant.
     *
     * @return the downloaded file, or null if the download failed.
     */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, APK_FILE_NAME)
        var connection: HttpURLConnection? = null
        try {
            if (target.exists()) target.delete()
            connection = open(release.apkUrl)
            val total = connection.contentLength.toLong()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER)
                    var written = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            // Only report on change: the caller hops to the main thread.
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (target.length() <= 0L) {
                android.util.Log.e(TAG, "Downloaded APK is empty")
                target.delete()
                return@withContext null
            }
            target
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Update download failed", e)
            target.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Hand the APK to the system installer. Android shows its own confirmation screen — this
     * cannot install silently, which is exactly the guarantee sideloading is supposed to give
     * the user.
     *
     * On Android 8+ the user must also have allowed this app to install unknown apps; if they
     * haven't, [canRequestInstalls] is false and [installPermissionIntent] takes them to the
     * right settings page.
     */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            // The installer runs in another process, so it needs both the read grant and its
            // own task.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** True if the app may launch an install (always true below Android 8). */
    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user grants "install unknown apps" for this app. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))

    private fun readText(url: String): String {
        val connection = open(url)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Open a connection, following redirects across protocols — GitHub Releases and most CDNs
     * answer with a 302 to a different host, which HttpURLConnection will NOT follow on its
     * own when the scheme changes.
     */
    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "*/*")
            }
            val code = connection.responseCode
            val redirected = code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == HTTP_TEMPORARY_REDIRECT ||
                code == HTTP_PERMANENT_REDIRECT
            if (!redirected) {
                if (code !in 200..299) {
                    connection.disconnect()
                    throw IllegalStateException("Server returned HTTP $code")
                }
                require(connection is HttpsURLConnection || current.startsWith("http://localhost")) {
                    "Updates must be served over HTTPS"
                }
                return connection
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            current = URL(URL(current), location ?: error("Redirect with no Location")).toString()
        }
        error("Too many redirects")
    }

    private const val DOWNLOAD_BUFFER = 16 * 1024
    private const val MAX_REDIRECTS = 5
    private const val HTTP_TEMPORARY_REDIRECT = 307
    private const val HTTP_PERMANENT_REDIRECT = 308
}
