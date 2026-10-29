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
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

            patchXpiManifestIfNeeded(addonFile)

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
        if (addonFile.exists()) {
            patchXpiManifestIfNeeded(addonFile)
            return addonFile
        }
        return null
    }

    private fun patchXpiManifestIfNeeded(xpiFile: File) {
        try {
            if (!xpiFile.exists() || xpiFile.length() == 0L) return

            var needsPatch = false
            var patchedManifestBytes: ByteArray? = null

            ZipInputStream(xpiFile.inputStream()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "manifest.json") {
                        val manifestText = zis.bufferedReader().use { it.readText() }
                        val jsonObj = JSONObject(manifestText)

                        // Convert MV3 to MV2 if manifest_version is 3
                        if (jsonObj.optInt("manifest_version", 2) == 3) {
                            jsonObj.put("manifest_version", 2)
                            needsPatch = true

                            if (jsonObj.has("action")) {
                                val actionObj = jsonObj.get("action")
                                jsonObj.remove("action")
                                jsonObj.put("browser_action", actionObj)
                            }

                            val permissionsArray = jsonObj.optJSONArray("permissions") ?: org.json.JSONArray()
                            if (jsonObj.has("host_permissions")) {
                                val hostPerms = jsonObj.optJSONArray("host_permissions")
                                if (hostPerms != null) {
                                    for (i in 0 until hostPerms.length()) {
                                        permissionsArray.put(hostPerms.getString(i))
                                    }
                                }
                                jsonObj.remove("host_permissions")
                            }
                            jsonObj.put("permissions", permissionsArray)

                            if (jsonObj.has("optional_host_permissions")) {
                                val optHostPerms = jsonObj.optJSONArray("optional_host_permissions")
                                val optPermsArray = jsonObj.optJSONArray("optional_permissions") ?: org.json.JSONArray()
                                if (optHostPerms != null) {
                                    for (i in 0 until optHostPerms.length()) {
                                        optPermsArray.put(optHostPerms.getString(i))
                                    }
                                }
                                jsonObj.remove("optional_host_permissions")
                                jsonObj.put("optional_permissions", optPermsArray)
                            }

                            if (jsonObj.has("web_accessible_resources")) {
                                val war = jsonObj.optJSONArray("web_accessible_resources")
                                if (war != null) {
                                    val flatResources = org.json.JSONArray()
                                    for (i in 0 until war.length()) {
                                        val item = war.get(i)
                                        if (item is JSONObject && item.has("resources")) {
                                            val resArr = item.optJSONArray("resources")
                                            if (resArr != null) {
                                                for (j in 0 until resArr.length()) {
                                                    flatResources.put(resArr.getString(j))
                                                }
                                            }
                                        } else if (item is String) {
                                            flatResources.put(item)
                                        }
                                    }
                                    jsonObj.put("web_accessible_resources", flatResources)
                                }
                            }
                        }

                        if (jsonObj.has("browser_specific_settings")) {
                            val bss = jsonObj.getJSONObject("browser_specific_settings")
                            if (bss.has("gecko")) {
                                val gecko = bss.getJSONObject("gecko")
                                if (gecko.has("strict_min_version")) {
                                    gecko.put("strict_min_version", "90.0")
                                    needsPatch = true
                                }
                            }
                            if (bss.has("gecko_android")) {
                                val geckoAndroid = bss.getJSONObject("gecko_android")
                                if (geckoAndroid.has("strict_min_version")) {
                                    geckoAndroid.put("strict_min_version", "90.0")
                                    needsPatch = true
                                }
                            }
                        }

                        if (needsPatch) {
                            patchedManifestBytes = jsonObj.toString(2).toByteArray(Charsets.UTF_8)
                        }
                        break
                    }
                    entry = zis.nextEntry
                }
            }

            if (!needsPatch || patchedManifestBytes == null) {
                return
            }

            Log.d(TAG, "Patching manifest.json in XPI to lower strict_min_version for GeckoView v99...")
            val patchedTmpFile = File(xpiFile.parentFile, "comix_extension_patched.xpi")

            ZipInputStream(xpiFile.inputStream()).use { zis ->
                ZipOutputStream(FileOutputStream(patchedTmpFile)).use { zos ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (true) {
                        val currentEntry = entry ?: break
                        val newEntry = ZipEntry(currentEntry.name)
                        zos.putNextEntry(newEntry)
                        if (currentEntry.name == "manifest.json") {
                            zos.write(patchedManifestBytes!!)
                        } else {
                            zis.copyTo(zos)
                        }
                        zos.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (patchedTmpFile.exists() && patchedTmpFile.length() > 0L) {
                xpiFile.delete()
                if (!patchedTmpFile.renameTo(xpiFile)) {
                    patchedTmpFile.copyTo(xpiFile, overwrite = true)
                    patchedTmpFile.delete()
                }
                Log.d(TAG, "Successfully patched XPI manifest.json for GeckoView compatibility!")
            } else {
                patchedTmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error patching XPI manifest.json", e)
        }
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

        // First check if already installed in GeckoRuntime list
        val existingList = suspendCancellableCoroutine<List<WebExtension>?> { cont ->
            runtime.webExtensionController.list().accept({ list ->
                if (cont.isActive) cont.resume(list)
            }, {
                if (cont.isActive) cont.resume(null)
            })
        }

        val existing = existingList?.find { it.id.contains("comix", ignoreCase = true) || it.location.contains("comix", ignoreCase = true) }
        if (existing != null) {
            Log.d(TAG, "Comix WebExtension is already in GeckoRuntime list: ${existing.id}")
            runtime.webExtensionController.setAllowedInPrivateBrowsing(existing, true)
            runtime.webExtensionController.enable(existing, org.mozilla.geckoview.WebExtensionController.EnableSource.USER)
            return@withContext existing
        }

        val fileUri = "file://" + addonFile.absolutePath
        val addonId = "comix-downloader@n3uralcreativity.github.io"
        Log.d(TAG, "Attempting ensureBuiltIn for WebExtension from $fileUri (id=$addonId)...")

        val builtInResult = suspendCancellableCoroutine<WebExtension?> { continuation ->
            runtime.webExtensionController.ensureBuiltIn(fileUri, addonId).accept({ extension ->
                Log.d(TAG, "ensureBuiltIn success: ${extension?.id}")
                if (continuation.isActive) continuation.resume(extension)
            }, { throwable ->
                Log.w(TAG, "ensureBuiltIn failed: ${throwable?.message}")
                if (continuation.isActive) continuation.resume(null)
            })
        }

        if (builtInResult != null) {
            try {
                runtime.webExtensionController.setAllowedInPrivateBrowsing(builtInResult, true)
                runtime.webExtensionController.enable(builtInResult, org.mozilla.geckoview.WebExtensionController.EnableSource.USER)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set allowedInPrivateBrowsing/enable on builtInResult", e)
            }
            return@withContext builtInResult
        }

        Log.d(TAG, "Falling back to install() for WebExtension from $fileUri...")
        val installResult = suspendCancellableCoroutine<WebExtension?> { continuation ->
            runtime.webExtensionController.install(fileUri).accept({ extension ->
                Log.d(TAG, "install() success: ${extension?.id}")
                if (continuation.isActive) continuation.resume(extension)
            }, { throwable ->
                Log.e(TAG, "install() failed: ${throwable?.message}", throwable)
                if (continuation.isActive) continuation.resume(null)
            })
        }

        if (installResult != null) {
            try {
                runtime.webExtensionController.setAllowedInPrivateBrowsing(installResult, true)
                runtime.webExtensionController.enable(installResult, org.mozilla.geckoview.WebExtensionController.EnableSource.USER)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set allowedInPrivateBrowsing/enable on installResult", e)
            }
        }

        return@withContext installResult
    }
}
