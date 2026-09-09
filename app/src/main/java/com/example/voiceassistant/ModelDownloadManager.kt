package com.example.voiceassistant

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * ModelDownloadManager
 * --------------------
 * Downloads a model file from a Google Drive shareable link and saves it
 * to the app's internal files directory. No OkHttp dependency required —
 * uses HttpURLConnection from the standard library.
 *
 * ── Google Drive large-file handling ──────────────────────────────────────
 * For files > ~100 MB Google Drive shows a virus-scan warning page instead
 * of serving the file directly. The standard bypass:
 *
 *   1. First GET →  https://drive.usercontent.google.com/download
 *                        ?id={FILE_ID}&export=download&confirm=t
 *      Google now accepts ?confirm=t for programmatic access.
 *
 *   2. If Google still redirects to a warning page (HTTP 200 with an HTML
 *      body), we extract the confirmation token from the Set-Cookie header
 *      or from the page body and retry with that token.
 *
 * ── Resumable download ────────────────────────────────────────────────────
 * If a partial file exists (e.g. from a previous interrupted download),
 * we send a Range: bytes=N- header to resume from where we left off.
 *
 * ── Usage ─────────────────────────────────────────────────────────────────
 *   val mgr = ModelDownloadManager(context)
 *   mgr.downloadModel(
 *       shareableLink = "https://drive.google.com/file/d/FILE_ID/view",
 *       destFileName  = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
 *       expectedBytes = 935_000_000L,   // optional; 0 = skip size check
 *       onProgress    = { pct -> /* update UI */ },
 *       onComplete    = { file -> /* start inference */ },
 *       onError       = { msg -> /* show error */ }
 *   )
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG              = "ModelDownload"
        private const val NOTIF_ID         = 2001
        private const val BUFFER_SIZE      = 8 * 1024        // 8 KB read buffer
        private const val CONNECT_TIMEOUT  = 15_000          // ms
        private const val READ_TIMEOUT     = 60_000          // ms
        private const val GDRIVE_DIRECT    =
            "https://drive.usercontent.google.com/download?id=%s&export=download&confirm=t"
        private const val GDRIVE_FALLBACK  =
            "https://drive.google.com/uc?export=download&id=%s&confirm=%s"
    }

    // ---- Destination directory -------------------------------------------
    private val modelsDir: File get() =
        File(context.filesDir, "models").also { it.mkdirs() }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Download the model from [shareableLink] and save to [destFileName]
     * inside the app's models directory.
     *
     * Must be called from a coroutine (suspends on IO dispatcher internally).
     *
     * @param shareableLink   Google Drive shareable link OR a direct download URL.
     * @param destFileName    Target filename (e.g. "model.gguf").
     * @param expectedBytes   Expected file size in bytes; 0 = skip check.
     * @param onProgress      Called periodically with 0..100 completion %.
     * @param onComplete      Called with the finished [File] on success.
     * @param onError         Called with a human-readable error string on failure.
     */
    suspend fun downloadModel(
        shareableLink : String,
        destFileName  : String,
        expectedBytes : Long    = 0L,
        onProgress    : suspend (Int) -> Unit,
        onComplete    : suspend (File) -> Unit,
        onError       : suspend (String) -> Unit
    ) {
        PipelineLogger.log("DLOAD", "Starting download: $shareableLink")

        val destFile = File(modelsDir, destFileName)

        // Derive the direct download URL from the shareable link
        val fileId = extractFileId(shareableLink)
        if (fileId == null) {
            onError("Could not extract Google Drive file ID from:\n$shareableLink")
            return
        }
        PipelineLogger.log("DLOAD", "File ID: $fileId  dest: ${destFile.absolutePath}")

        withContext(Dispatchers.IO) {
            try {
                val downloadUrl = String.format(GDRIVE_DIRECT, fileId)
                performDownload(
                    urlString     = downloadUrl,
                    fileId        = fileId,
                    destFile      = destFile,
                    expectedBytes = expectedBytes,
                    onProgress    = onProgress,
                    onComplete    = onComplete,
                    onError       = onError
                )
            } catch (e: Exception) {
                val msg = "Unexpected download error: ${e.javaClass.simpleName} - ${e.message}"
                Log.e(TAG, msg, e)
                PipelineLogger.log("DLOAD", "ERROR: $msg")
                onError(msg)
            }
        }
    }

    /** Delete any partial or completed model file (for re-download). */
    fun deleteModel(destFileName: String): Boolean {
        val f = File(modelsDir, destFileName)
        return if (f.exists()) f.delete().also {
            PipelineLogger.log("DLOAD", "Deleted ${f.absolutePath}: $it")
        } else false
    }

    /** Returns true if the model file exists and is at least [minBytes]. */
    fun isModelReady(destFileName: String, minBytes: Long = 1_000_000L): Boolean {
        val f = File(modelsDir, destFileName)
        return f.exists() && f.length() >= minBytes
    }

    /** Full path to the model file (exists or not). */
    fun modelPath(destFileName: String): String =
        File(modelsDir, destFileName).absolutePath

    // =========================================================================
    // Core download logic
    // =========================================================================

    private suspend fun performDownload(
        urlString     : String,
        fileId        : String,
        destFile      : File,
        expectedBytes : Long,
        onProgress    : suspend (Int) -> Unit,
        onComplete    : suspend (File) -> Unit,
        onError       : suspend (String) -> Unit
    ) {
        // ---- Resumable: check existing partial file ----------------------
        val existingBytes = if (destFile.exists()) destFile.length() else 0L
        if (existingBytes > 0) {
            PipelineLogger.log("DLOAD", "Resuming from byte $existingBytes")
        }

        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(urlString)

            // Request resume range if we have a partial file
            if (existingBytes > 0) {
                connection.setRequestProperty("Range", "bytes=$existingBytes-")
            }

            val responseCode = connection.responseCode
            PipelineLogger.log("DLOAD", "HTTP $responseCode from $urlString")

            // Handle Google Drive's virus-scan confirmation page (HTTP 200 + HTML)
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val contentType = connection.contentType ?: ""
                if (contentType.contains("text/html", ignoreCase = true)) {
                    PipelineLogger.log("DLOAD", "Got HTML page — extracting confirmation token")
                    val html = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()

                    val token = extractConfirmToken(html)
                    if (token.isNullOrBlank()) {
                        // Try the legacy confirm=1 approach
                        PipelineLogger.log("DLOAD", "No token found, retrying with confirm=1")
                        val retryUrl = String.format(GDRIVE_FALLBACK, fileId, "1")
                        connection = openConnection(retryUrl)
                    } else {
                        PipelineLogger.log("DLOAD", "Token found: $token")
                        val retryUrl = String.format(GDRIVE_FALLBACK, fileId, token)
                        connection = openConnection(retryUrl)
                        if (existingBytes > 0) {
                            connection.setRequestProperty("Range", "bytes=$existingBytes-")
                        }
                    }

                    val retryCode = connection.responseCode
                    PipelineLogger.log("DLOAD", "Retry HTTP $retryCode")
                    if (retryCode != HttpURLConnection.HTTP_OK &&
                        retryCode != HttpURLConnection.HTTP_PARTIAL) {
                        onError("Google Drive returned HTTP $retryCode on retry. " +
                                "Make sure the file sharing is set to Anyone with the link.")
                        return
                    }
                }
            } else if (responseCode != HttpURLConnection.HTTP_PARTIAL) {
                onError("Unexpected HTTP response: $responseCode. " +
                        "Ensure the Drive link is set to Anyone with the link.")
                return
            }

            // ---- Stream to file ------------------------------------------
            val contentLength = connection.contentLengthLong
            val totalBytes = when {
                expectedBytes > 0      -> expectedBytes
                contentLength > 0      -> existingBytes + contentLength
                else                   -> -1L
            }
            PipelineLogger.log("DLOAD",
                "Content-Length=$contentLength totalBytes=$totalBytes")

            val appendMode = existingBytes > 0 &&
                             connection.responseCode == HttpURLConnection.HTTP_PARTIAL

            var downloadedBytes = existingBytes
            val buf = ByteArray(BUFFER_SIZE)

            showProgressNotification(0)

            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(destFile, appendMode).use { output ->
                    var read: Int
                    var lastProgressPct = -1

                    while (input.read(buf).also { read = it } != -1) {
                        if (!withContext(Dispatchers.IO) { isActive }) break
                        output.write(buf, 0, read)
                        downloadedBytes += read

                        // Throttle progress callbacks to 1% increments
                        if (totalBytes > 0) {
                            val pct = ((downloadedBytes * 100) / totalBytes).toInt()
                                .coerceIn(0, 100)
                            if (pct != lastProgressPct) {
                                lastProgressPct = pct
                                PipelineLogger.log("DLOAD",
                                    "Progress $pct% ($downloadedBytes / $totalBytes bytes)")
                                onProgress(pct)
                                showProgressNotification(pct)
                            }
                        }
                    }
                }
            }

            // ---- Verify --------------------------------------------------
            PipelineLogger.log("DLOAD",
                "Download complete. File size: ${destFile.length()} bytes")

            if (expectedBytes > 0 && destFile.length() < expectedBytes * 0.95) {
                // Tolerate up to 5% shortfall (some sources report slightly
                // different sizes), but a large gap means truncation
                onError(
                    "Downloaded file is ${destFile.length()} bytes but expected " +
                    "~$expectedBytes bytes. The download may be incomplete."
                )
                return
            }

            dismissNotification()
            onProgress(100)
            onComplete(destFile)

        } catch (e: IOException) {
            val msg = "IO error during download: ${e.message}. " +
                      "Check network and retry — the download will resume."
            Log.e(TAG, msg, e)
            PipelineLogger.log("DLOAD", "IOException: $msg")
            dismissNotification()
            onError(msg)
        } finally {
            connection?.disconnect()
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun openConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout    = READ_TIMEOUT
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        return conn
    }

    /**
     * Extract the Google Drive file ID from a shareable link.
     *
     * Handles formats:
     *   https://drive.google.com/file/d/{ID}/view
     *   https://drive.google.com/open?id={ID}
     *   https://drive.google.com/uc?id={ID}&export=download
     *   Raw file ID (no slashes)
     */
    private fun extractFileId(link: String): String? {
        // Format: /file/d/{ID}/
        val slashPattern = Regex("/file/d/([a-zA-Z0-9_-]+)")
        slashPattern.find(link)?.groupValues?.get(1)?.let { return it }

        // Format: ?id={ID}
        val idPattern = Regex("[?&]id=([a-zA-Z0-9_-]+)")
        idPattern.find(link)?.groupValues?.get(1)?.let { return it }

        // Assume raw ID if no slashes/query
        if (!link.contains('/') && !link.contains('?')) return link

        return null
    }

    /**
     * Extract the Google virus-scan confirmation token from the HTML warning page.
     * The token appears as a hidden input or in a link:
     *   name="confirm" value="TOKEN"
     *   confirm=TOKEN
     */
    private fun extractConfirmToken(html: String): String? {
        // Hidden input field
        val inputPattern = Regex("name=\"confirm\"\\s+value=\"([^\"]+)\"")
        inputPattern.find(html)?.groupValues?.get(1)?.let { return it }

        // URL query parameter
        val queryPattern = Regex("confirm=([a-zA-Z0-9_-]+)")
        queryPattern.find(html)?.groupValues?.get(1)?.let { return it }

        return null
    }

    // ---- Notification during download ------------------------------------

    private fun showProgressNotification(pct: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        val notif = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Downloading model")
            .setContentText("$pct% complete")
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun dismissNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        nm.cancel(NOTIF_ID)
    }
}
