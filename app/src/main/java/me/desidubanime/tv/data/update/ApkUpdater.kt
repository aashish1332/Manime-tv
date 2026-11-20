package me.desidubanime.tv.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.desidubanime.tv.App
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Downloads a release APK and hands it to the system installer.
 *
 * Everything here exists because the target is a TV box: no Play Store, no adb,
 * often a 2 Mbps link and a package manager that reports "app not installed"
 * for anything it dislikes. So the file is checked twice before the installer
 * ever sees it:
 *
 *  1. **SHA-256 while streaming** against GitHub's asset digest — catches a
 *     truncated/corrupted download without a second pass over 28 MB.
 *  2. **Package identity** — the archive's package name must be ours and its
 *     versionCode must be higher than the installed one, otherwise the
 *     installer would fail (or, worse, offer to replace the app with a
 *     different build).
 *
 * The viewer still confirms: Android's installer is the only thing that can
 * actually replace a package.
 */
object ApkUpdater {

    /** A release APK for this app is ~28 MB; anything near this is not an APK. */
    private const val MAX_BYTES = 150L * 1024 * 1024

    /** Progress callback is throttled to this, so Compose is not recomposed per chunk. */
    private const val PROGRESS_INTERVAL_MS = 150L

    /**
     * Download [url] into `cache/updates`. Emits (read, total) — total is 0 when
     * the server sends no Content-Length.
     */
    suspend fun download(
        url: String,
        fileName: String,
        expectedSha256: String?,
        onProgress: (read: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(App.instance.cacheDir, "updates").apply { mkdirs() }
        // One update at a time: a partial file from an earlier attempt is the
        // only thing an interrupted run can leave behind.
        dir.listFiles()?.forEach { runCatching { it.delete() } }

        val safeName = fileName.ifBlank { "manime-update.apk" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".apk", true)) it else "$it.apk" }
        val part = File(dir, "$safeName.part")
        val dest = File(dir, safeName)
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            val request = Request.Builder().url(url).build()
            UpdateApi.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download failed (HTTP ${response.code})")
                val body = response.body ?: error("Download failed (empty response)")
                val total = body.contentLength()
                if (total > MAX_BYTES) error("Update file is larger than expected")

                var read = 0L
                var lastEmit = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            // A cancel must stop a big download promptly, not
                            // hide the dialog while the file keeps arriving.
                            ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            if (read > MAX_BYTES) error("Update file is larger than expected")
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS || (total > 0 && read >= total)) {
                                lastEmit = now
                                onProgress(read, total)
                            }
                        }
                    }
                }
            }

            if (expectedSha256 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    error("Download was corrupted (checksum mismatch) — try again")
                }
            }

            if (!part.renameTo(dest)) {
                part.delete()
                error("Could not save the update file")
            }
            onProgress(dest.length(), dest.length())
            dest
        } catch (e: CancellationException) {
            runCatching { part.delete() }
            throw e
        } catch (e: Throwable) {
            runCatching { part.delete() }
            throw e
        }
    }

    /**
     * Why [apk] must not be installed, or null when it is a newer build of this
     * very app. Checked BEFORE the installer opens, so a bad download produces
     * a sentence instead of "app not installed".
     */
    fun problem(context: Context, apk: File): String? {
        if (!apk.exists() || apk.length() == 0L) return "The update file is missing"
        val archive = runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }.getOrNull() ?: return "The downloaded file is not a valid APK"
        if (archive.packageName != context.packageName) {
            return "That release belongs to a different app"
        }
        val archiveCode = PackageInfoCompat.getLongVersionCode(archive)
        val installedCode = runCatching {
            PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            )
        }.getOrDefault(0L)
        if (archiveCode <= installedCode) {
            return "That build is not newer than the one installed"
        }
        return null
    }

    /** Open the system installer for a verified APK. */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** "12.3 MB"; blank when the size is unknown. */
    fun sizeLabel(bytes: Long): String =
        if (bytes <= 0) "" else "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
