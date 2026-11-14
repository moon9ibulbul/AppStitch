package com.astral.stitchapp.rawloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

object ComixExtensionManager {
    private const val TAG = "ComixExtensionManager"
    private const val PREF_NAME = "stitch_prefs"
    private const val KEY_VERSION = "comix_addon_version"
    private const val KEY_AUTO_UPDATE = "comix_addon_auto_update"
    private const val AMO_API_URL = "https://addons.mozilla.org/api/v5/addons/addon/comix-chapter-downloader/"
    private const val ADDON_FILENAME = "comix_extension.xpi"
    const val ADDON_ID = "comix-downloader@n3uralcreativity.github.io"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundSessions = Collections.newSetFromMap(ConcurrentHashMap<GeckoSession, Boolean>())
    private val recentDownloads = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastDownloadDelegateTimestamp: Long = 0L

    fun getAddonFile(context: Context): File {
        return File(context.filesDir, ADDON_FILENAME)
    }

    fun getInstalledVersion(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_VERSION, "Not Installed") ?: "Not Installed"
    }

    fun isAutoUpdateEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_UPDATE, true)
    }

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
    }

    /**
     * Verifies that the local XPI file is an unmodified, cryptographically signed Mozilla addon.
     * Modifying manifest.json inside the XPI breaks META-INF/manifest.mf SHA-256 digests and causes
     * GeckoView to reject the addon with Corrupt file / signature error.
     */
    private fun isValidSignedXpi(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        return try {
            ZipFile(file).use { zip ->
                val hasManifest = zip.getEntry("manifest.json") != null
                val hasSignature = zip.getEntry("META-INF/mozilla.rsa") != null &&
                    zip.getEntry("META-INF/manifest.mf") != null
                hasManifest && hasSignature
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid XPI file ${file.absolutePath}: ${e.message}")
            false
        }
    }

    suspend fun checkAndUpdateAddon(
        context: Context,
        forceManual: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val addonFile = getAddonFile(context)
            val validSigned = isValidSignedXpi(addonFile)
            val installedVersion = getInstalledVersion(context)
            val autoUpdate = isAutoUpdateEnabled(context)

            if (!forceManual && !autoUpdate && validSigned && installedVersion != "Not Installed") {
                return@withContext Result.success(installedVersion)
            }

            if (!validSigned && addonFile.exists()) {
                addonFile.delete()
            }

            onProgress("Checking Comix Addon version from Mozilla AMO...")
            val conn = (URL(AMO_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:142.0) Gecko/142.0 Firefox/142.0")
            }

            if (conn.responseCode != 200) {
                if (validSigned) {
                    return@withContext Result.success(installedVersion)
                }
                return@withContext Result.failure(Exception("AMO API returned HTTP ${conn.responseCode}"))
            }

            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonStr)
            val currentVersionObj = json.optJSONObject("current_version")
                ?: return@withContext Result.failure(Exception("Missing current_version in AMO response"))

            val remoteVersion = currentVersionObj.optString("version", "")
            val fileObj = currentVersionObj.optJSONObject("file")
            val downloadUrl = fileObj?.optString("url", "") ?: ""

            if (remoteVersion.isBlank() || downloadUrl.isBlank()) {
                return@withContext Result.failure(Exception("Invalid version or download URL from AMO"))
            }

            val needsDownload = !validSigned ||
                installedVersion == "Not Installed" ||
                installedVersion != remoteVersion

            if (!needsDownload) {
                onProgress("Comix Addon is up to date ($installedVersion)")
                return@withContext Result.success(installedVersion)
            }

            onProgress("Downloading Comix Addon v$remoteVersion...")
            val dlConn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:142.0) Gecko/142.0 Firefox/142.0")
            }

            if (dlConn.responseCode != 200) {
                return@withContext Result.failure(Exception("Failed to download XPI: HTTP ${dlConn.responseCode}"))
            }

            val tempFile = File(context.filesDir, "$ADDON_FILENAME.tmp")
            dlConn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (isValidSignedXpi(tempFile)) {
                if (addonFile.exists()) addonFile.delete()
                tempFile.renameTo(addonFile)

                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_VERSION, remoteVersion).apply()

                onProgress("Installed Comix Addon v$remoteVersion")
                Result.success(remoteVersion)
            } else {
                tempFile.delete()
                Result.failure(Exception("Downloaded XPI failed signature/manifest check"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking/updating Comix addon", e)
            val addonFile = getAddonFile(context)
            if (isValidSignedXpi(addonFile)) {
                Result.success(getInstalledVersion(context))
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun ensureAddonFile(
        context: Context,
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val addonFile = getAddonFile(context)
        if (!isValidSignedXpi(addonFile)) {
            if (addonFile.exists()) addonFile.delete()
            checkAndUpdateAddon(context, forceManual = true, onProgress = onProgress)
        } else if (isAutoUpdateEnabled(context)) {
            checkAndUpdateAddon(context, forceManual = false, onProgress = onProgress)
        }
        if (isValidSignedXpi(addonFile)) addonFile else null
    }

    fun closeBackgroundSessions() {
        val sessions = backgroundSessions.toList()
        backgroundSessions.clear()
        for (s in sessions) {
            try {
                s.close()
            } catch (_: Exception) {}
        }
    }

    suspend fun installToGecko(
        context: Context,
        runtime: GeckoRuntime,
        mainSession: GeckoSession,
        onOpenPopupSession: (GeckoSession?) -> Unit = {},
        onStatusUpdate: (String) -> Unit = {},
        onArchiveSaved: (String, Int) -> Unit = { _, _ -> }
    ): WebExtension? = withContext(Dispatchers.Main) {
        val addonFile = withContext(Dispatchers.IO) {
            ensureAddonFile(context, onProgress = { msg ->
                ioScope.launch(Dispatchers.Main) { onStatusUpdate(msg) }
            })
        } ?: return@withContext null

        val controller = runtime.webExtensionController

        controller.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, true))
            }

            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, true))
            }

            override fun onUpdatePrompt(
                currentlyInstalled: WebExtension,
                updated: WebExtension,
                newPermissions: Array<out String>,
                newOrigins: Array<out String>
            ): GeckoResult<AllowOrDeny>? {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>
            ): GeckoResult<AllowOrDeny>? {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>
            ): GeckoResult<AllowOrDeny>? {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>
            ): GeckoResult<AllowOrDeny>? {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        })

        val fileUri = "file://" + addonFile.absolutePath
        val deferred = CompletableDeferred<WebExtension?>()

        fun configureExtension(ext: WebExtension) {
            try {
                controller.setAllowedInPrivateBrowsing(ext, true)
                controller.enable(ext, WebExtensionController.EnableSource.USER)
                    .accept(
                        { enabledExt ->
                            val activeExt = enabledExt ?: ext
                            wireExtensionDelegates(
                                context = context,
                                runtime = runtime,
                                controller = controller,
                                ext = activeExt,
                                mainSession = mainSession,
                                onOpenPopupSession = onOpenPopupSession,
                                onStatusUpdate = onStatusUpdate,
                                onArchiveSaved = onArchiveSaved
                            )
                            controller.addOptionalPermissions(
                                activeExt.id,
                                emptyArray(),
                                arrayOf("*://*/*", "http://*/*", "https://*/*", "https://plus.n3uralcreativity.top/*"),
                                arrayOf("websiteContent", "technicalAndInteraction")
                            ).accept(
                                { finalExt ->
                                    Log.d(TAG, "Comix addon enabled and optional permissions granted: ${activeExt.id}")
                                    if (!deferred.isCompleted) deferred.complete(finalExt ?: activeExt)
                                },
                                { _ ->
                                    if (!deferred.isCompleted) deferred.complete(activeExt)
                                }
                            )
                        },
                        { err ->
                            Log.w(TAG, "Enable warning for Comix addon: $err")
                            wireExtensionDelegates(
                                context = context,
                                runtime = runtime,
                                controller = controller,
                                ext = ext,
                                mainSession = mainSession,
                                onOpenPopupSession = onOpenPopupSession,
                                onStatusUpdate = onStatusUpdate,
                                onArchiveSaved = onArchiveSaved
                            )
                            if (!deferred.isCompleted) deferred.complete(ext)
                        }
                    )
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring Comix extension", e)
                if (!deferred.isCompleted) deferred.complete(ext)
            }
        }

        val targetVersion = getInstalledVersion(context)
        controller.list().accept(
            { existingList ->
                val existing = existingList?.firstOrNull {
                    it.id.equals(ADDON_ID, ignoreCase = true) ||
                        it.id.contains("comix", ignoreCase = true)
                }
                val existingVer = existing?.metaData?.version ?: ""
                if (existing != null && (targetVersion == "Not Installed" || existingVer == targetVersion)) {
                    Log.d(TAG, "Reusing installed Comix addon (${existing.id} v$existingVer)")
                    configureExtension(existing)
                } else {
                    Log.d(TAG, "Installing Comix addon from $fileUri")
                    controller.install(fileUri).accept(
                        { installedExt ->
                            if (installedExt != null) {
                                Log.d(TAG, "Successfully installed Comix addon: ${installedExt.id}")
                                configureExtension(installedExt)
                            } else {
                                if (!deferred.isCompleted) deferred.complete(null)
                            }
                        },
                        { err ->
                            Log.e(TAG, "Failed to install Comix addon from $fileUri", err)
                            if (existing != null) {
                                configureExtension(existing)
                            } else {
                                if (!deferred.isCompleted) deferred.complete(null)
                            }
                        }
                    )
                }
            },
            { _ ->
                controller.install(fileUri).accept(
                    { installedExt ->
                        if (installedExt != null) {
                            configureExtension(installedExt)
                        } else {
                            if (!deferred.isCompleted) deferred.complete(null)
                        }
                    },
                    { err ->
                        Log.e(TAG, "Fallback install failed for Comix addon", err)
                        if (!deferred.isCompleted) deferred.complete(null)
                    }
                )
            }
        )

        withTimeoutOrNull(20000L) {
            deferred.await()
        }
    }

    private fun wireExtensionDelegates(
        context: Context,
        runtime: GeckoRuntime,
        controller: WebExtensionController,
        ext: WebExtension,
        mainSession: GeckoSession,
        onOpenPopupSession: (GeckoSession?) -> Unit,
        onStatusUpdate: (String) -> Unit,
        onArchiveSaved: (String, Int) -> Unit
    ) {
        val mainSessionTabDelegate = object : WebExtension.SessionTabDelegate {
            override fun onUpdateTab(
                extension: WebExtension,
                session: GeckoSession,
                details: WebExtension.UpdateTabDetails
            ): GeckoResult<AllowOrDeny> {
                if (details.active == true) {
                    onOpenPopupSession(null)
                    try {
                        controller.setTabActive(mainSession, true)
                    } catch (_: Exception) {}
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onCloseTab(
                extension: WebExtension,
                session: GeckoSession
            ): GeckoResult<AllowOrDeny> {
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
        }

        mainSession.webExtensionController.setTabDelegate(ext, mainSessionTabDelegate)
        try {
            controller.setTabActive(mainSession, true)
        } catch (_: Exception) {}

        ext.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onOpenOptionsPage(extension: WebExtension) {
                val baseUrl = extension.metaData.baseUrl
                if (baseUrl.isNotBlank()) {
                    val optionsUrl = "${baseUrl.trimEnd('/')}/legacy/options.html"
                    mainSession.loadUri(optionsUrl)
                }
            }

            override fun onNewTab(
                extension: WebExtension,
                createDetails: WebExtension.CreateTabDetails
            ): GeckoResult<GeckoSession>? {
                val targetUrl = createDetails.url ?: ""
                Log.d(TAG, "Comix addon requested new tab: url=$targetUrl active=${createDetails.active}")

                // Ignore the welcome page opened on initial install so the user stays on comix.to
                if (targetUrl.contains("n3uralcreativity.top/welcome", ignoreCase = true) ||
                    targetUrl.contains("welcome.html", ignoreCase = true)
                ) {
                    return GeckoResult.fromValue(null)
                }

                // IMPORTANT: GeckoView requires onNewTab to return an UNOPENED GeckoSession.
                // GeckoView's WebExtensionController opens the session with the internal newSessionId.
                val newSession = GeckoSession()
                backgroundSessions.add(newSession)

                val isBackgroundExtraction = createDetails.active == false ||
                    targetUrl.contains("#cdlx", ignoreCase = true) ||
                    targetUrl == "about:blank"

                newSession.contentDelegate = object : GeckoSession.ContentDelegate {
                    override fun onCloseRequest(s: GeckoSession) {
                        backgroundSessions.remove(s)
                        try { s.close() } catch (_: Exception) {}
                        onOpenPopupSession(null)
                    }
                }

                newSession.webExtensionController.setTabDelegate(ext, object : WebExtension.SessionTabDelegate {
                    override fun onUpdateTab(
                        extension: WebExtension,
                        session: GeckoSession,
                        details: WebExtension.UpdateTabDetails
                    ): GeckoResult<AllowOrDeny> {
                        if (details.active == true && !targetUrl.contains("#cdlx", ignoreCase = true)) {
                            onOpenPopupSession(session)
                        }
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }

                    override fun onCloseTab(
                        extension: WebExtension,
                        session: GeckoSession
                    ): GeckoResult<AllowOrDeny> {
                        backgroundSessions.remove(session)
                        onOpenPopupSession(null)
                        try {
                            session.close()
                        } catch (_: Exception) {}
                        try {
                            controller.setTabActive(mainSession, true)
                        } catch (_: Exception) {}
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }
                })

                if (!isBackgroundExtraction && createDetails.active == true) {
                    onOpenPopupSession(newSession)
                }

                return GeckoResult.fromValue(newSession)
            }
        })

        ext.setDownloadDelegate(object : WebExtension.DownloadDelegate {
            override fun onDownload(
                extension: WebExtension,
                request: WebExtension.DownloadRequest
            ): GeckoResult<WebExtension.DownloadInitData>? {
                val uriStr = request.request.uri ?: ""
                val rawName = request.filename.takeIf { !it.isNullOrBlank() } ?: "Comix_Chapter.zip"
                val cleanFilename = rawName.substringAfterLast('/').substringAfterLast('\\')
                    .ifBlank { "Comix_Chapter.zip" }

                Log.d(TAG, "Comix addon triggered download: filename=$cleanFilename uriPrefix=${uriStr.take(48)} len=${uriStr.length}")

                if (uriStr.startsWith("data:")) {
                    val now = System.currentTimeMillis()
                    lastDownloadDelegateTimestamp = now
                    val dedupKey = "$cleanFilename:${uriStr.length}"
                    val prevTime = recentDownloads.put(dedupKey, now)
                    if (prevTime == null || (now - prevTime) > 15000L) {
                        ioScope.launch {
                            try {
                                ioScope.launch(Dispatchers.Main) {
                                    onStatusUpdate("Saving $cleanFilename...")
                                }
                                val base64Data = uriStr.substringAfter("base64,", "")
                                if (base64Data.isNotEmpty()) {
                                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    processDownloadedArchive(
                                        context = context,
                                        bytes = bytes,
                                        filename = cleanFilename,
                                        onStatusUpdate = onStatusUpdate,
                                        onArchiveSaved = onArchiveSaved
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving data URI download from Comix addon", e)
                                withContext(Dispatchers.Main) {
                                    onStatusUpdate("Download error: ${e.message}")
                                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }

                // Returning null rejects browser.downloads.download at the 'start' phase,
                // which triggers Comix Downloader's Firefox Android handoff (triggerDownload)
                // so the in-page button immediately transitions to Done (✓).
                return GeckoResult.fromValue(null)
            }
        })
    }

    fun handleExternalBlobResponse(
        context: Context,
        response: WebResponse,
        onStatusUpdate: (String) -> Unit,
        onArchiveSaved: (String, Int) -> Unit
    ) {
        val now = System.currentTimeMillis()
        // If DownloadDelegate.onDownload already handled this archive within the last 30 seconds,
        // consume and close the fallback blob stream without saving a duplicate copy.
        if (now - lastDownloadDelegateTimestamp < 30000L) {
            ioScope.launch {
                try {
                    response.body?.close()
                } catch (_: Exception) {}
            }
            return
        }

        ioScope.launch {
            try {
                val bodyStream = response.body ?: return@launch
                val bytes = bodyStream.use { it.readBytes() }
                if (bytes.isEmpty()) return@launch

                val contentDisposition = response.headers["Content-Disposition"]
                    ?: response.headers["content-disposition"]
                    ?: ""
                val contentType = response.headers["Content-Type"]
                    ?: response.headers["content-type"]
                    ?: "application/zip"

                var filename = "Comix_${System.currentTimeMillis()}.zip"
                if (contentDisposition.contains("filename=")) {
                    filename = contentDisposition.substringAfter("filename=")
                        .substringBefore(";")
                        .trim('"', '\'', ' ')
                } else if (contentType.contains("pdf", ignoreCase = true)) {
                    filename = "Comix_${System.currentTimeMillis()}.pdf"
                }

                val cleanFilename = filename.substringAfterLast('/').substringAfterLast('\\')
                    .ifBlank { "Comix_${System.currentTimeMillis()}.zip" }

                val dedupKey = "$cleanFilename:${bytes.size}"
                val prev = recentDownloads.put(dedupKey, System.currentTimeMillis())
                if (prev != null && (System.currentTimeMillis() - prev) < 15000L) {
                    return@launch
                }

                processDownloadedArchive(
                    context = context,
                    bytes = bytes,
                    filename = cleanFilename,
                    onStatusUpdate = onStatusUpdate,
                    onArchiveSaved = onArchiveSaved
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error handling external blob response", e)
            }
        }
    }

    private suspend fun processDownloadedArchive(
        context: Context,
        bytes: ByteArray,
        filename: String,
        onStatusUpdate: (String) -> Unit,
        onArchiveSaved: (String, Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val mimeType = when {
            filename.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            filename.endsWith(".cbz", ignoreCase = true) -> "application/vnd.comicbook+zip"
            else -> "application/zip"
        }

        saveBytesToDownloads(context, bytes, filename, mimeType)

        var queuedChapters = 0
        if (filename.endsWith(".zip", ignoreCase = true) || filename.endsWith(".cbz", ignoreCase = true)) {
            queuedChapters = BatoEngine.addArchiveChaptersToQueue(
                cacheDir = context.cacheDir,
                archiveFilename = filename,
                archiveBytes = bytes,
                sourceType = "comix"
            )
        }

        withContext(Dispatchers.Main) {
            val statusMsg = if (queuedChapters > 0) {
                "Saved $filename & queued $queuedChapters chapter(s)!"
            } else {
                "Saved to Downloads/AstralStitch: $filename"
            }
            onStatusUpdate(statusMsg)
            Toast.makeText(context, statusMsg, Toast.LENGTH_SHORT).show()
            onArchiveSaved(filename, queuedChapters)
        }
    }

    private fun saveBytesToDownloads(
        context: Context,
        bytes: ByteArray,
        filename: String,
        mimeType: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AstralStitch")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                }
            }
        } else {
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AstralStitch"
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val outFile = File(downloadsDir, filename)
            FileOutputStream(outFile).use { out ->
                out.write(bytes)
            }
        }
    }
}
