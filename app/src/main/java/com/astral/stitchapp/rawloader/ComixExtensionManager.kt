package com.astral.stitchapp.rawloader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import java.io.File
import kotlin.coroutines.resume
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ComixExtensionManager {
    private const val TAG = "ComixExtensionManager"
    private const val AMO_API_URL = "https://addons.mozilla.org/api/v5/addons/addon/comix-chapter-downloader/"
    private const val ADDON_FILE_NAME = "comix_extension.xpi"
    private const val PREF_KEY_VERSION = "comix_addon_version"
    private const val PREF_KEY_AUTO_UPDATE = "comix_addon_autoupdate"

    fun getAddonFile(context: Context): File {
        return File(context.filesDir, ADDON_FILE_NAME)
    }

    fun getInstalledVersion(context: Context): String {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getString(PREF_KEY_VERSION, "None") ?: "None"
    }

    fun isAutoUpdateEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_KEY_AUTO_UPDATE, true)
    }

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KEY_AUTO_UPDATE, enabled).apply()
    }

    suspend fun checkAndUpdateAddon(
        context: Context,
        forceManual: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val addonFile = getAddonFile(context)
            val currentVersion = getInstalledVersion(context)

            onProgress?.invoke("Checking latest version from AMO...")
            val url = URL(AMO_API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) Gecko/120.0 Firefox/120.0")

            if (conn.responseCode != 200) {
                if (addonFile.exists()) {
                    return@withContext Result.success("Offline mode (Using v$currentVersion)")
                }
                return@withContext Result.failure(Exception("Failed to check extension update: HTTP ${conn.responseCode}"))
            }

            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonObj = JSONObject(jsonStr)
            val currentVersionObj = jsonObj.getJSONObject("current_version")
            val latestVersion = currentVersionObj.getString("version")
            val fileObj = currentVersionObj.getJSONObject("file")
            val downloadUrl = fileObj.getString("url")

            val needsUpdate = forceManual || !addonFile.exists() || currentVersion != latestVersion

            if (!needsUpdate) {
                onProgress?.invoke("Addon is already up to date (v$latestVersion)")
                return@withContext Result.success("v$latestVersion (Up to date)")
            }

            onProgress?.invoke("Downloading Comix addon v$latestVersion...")
            val dlConn = URL(downloadUrl).openConnection() as HttpURLConnection
            dlConn.connectTimeout = 15000
            dlConn.readTimeout = 15000
            dlConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) Gecko/120.0 Firefox/120.0")

            if (dlConn.responseCode != 200) {
                return@withContext Result.failure(Exception("Failed to download XPI: HTTP ${dlConn.responseCode}"))
            }

            val tmpFile = File(context.filesDir, "comix_extension_tmp.xpi")
            dlConn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (tmpFile.length() == 0L) {
                tmpFile.delete()
                return@withContext Result.failure(Exception("Downloaded XPI file is empty."))
            }

            if (addonFile.exists()) {
                addonFile.delete()
            }
            if (!tmpFile.renameTo(addonFile)) {
                tmpFile.copyTo(addonFile, overwrite = true)
                tmpFile.delete()
            }

            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_KEY_VERSION, latestVersion).apply()

            onProgress?.invoke("Successfully installed v$latestVersion")
            Log.d(TAG, "Comix addon updated to v$latestVersion")
            Result.success("v$latestVersion")
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkAndUpdateAddon", e)
            val addonFile = getAddonFile(context)
            val currentVersion = getInstalledVersion(context)
            if (addonFile.exists() && currentVersion != "None") {
                Result.success("v$currentVersion (Update failed: ${e.message})")
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun ensureAddonFile(context: Context, onProgress: ((String) -> Unit)? = null): File? {
        val file = getAddonFile(context)
        if (!file.exists() || getInstalledVersion(context) == "None") {
            val res = checkAndUpdateAddon(context, forceManual = true, onProgress = onProgress)
            if (res.isFailure) return null
        } else if (isAutoUpdateEnabled(context)) {
            checkAndUpdateAddon(context, forceManual = false, onProgress = onProgress)
        }
        val addonFile = getAddonFile(context)
        return if (addonFile.exists()) addonFile else null
    }

    suspend fun installToGecko(context: Context, runtime: GeckoRuntime): WebExtension? = withContext(Dispatchers.Main) {
        val addonFile = getAddonFile(context)
        if (!addonFile.exists()) {
            Log.e(TAG, "Cannot install extension: file does not exist")
            return@withContext null
        }

        runtime.webExtensionController.setPromptDelegate(object : org.mozilla.geckoview.WebExtensionController.PromptDelegate {
            override fun onInstallPrompt(extension: WebExtension): GeckoResult<AllowOrDeny> {
                Log.d(TAG, "PromptDelegate.onInstallPrompt for extension: ${extension.id}")
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onUpdatePrompt(
                currentlyInstalled: WebExtension,
                updated: WebExtension,
                newPermissions: Array<out String>,
                newOrigins: Array<out String>
            ): GeckoResult<AllowOrDeny> {
                Log.d(TAG, "PromptDelegate.onUpdatePrompt for extension: ${updated.id}")
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        })

        val fileUri = "file://" + addonFile.absolutePath
        Log.d(TAG, "Installing WebExtension to GeckoRuntime from $fileUri...")
        val geckoResult = runtime.webExtensionController.install(fileUri)

        return@withContext suspendCancellableCoroutine<WebExtension?> { continuation ->
            geckoResult.accept({ extension ->
                Log.d(TAG, "Successfully installed WebExtension to GeckoView: ${extension?.id} (${extension?.location})")
                if (continuation.isActive) {
                    continuation.resume(extension)
                }
            }, { throwable ->
                Log.e(TAG, "Failed to install WebExtension to GeckoView", throwable)
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            })
        }
    }
}
