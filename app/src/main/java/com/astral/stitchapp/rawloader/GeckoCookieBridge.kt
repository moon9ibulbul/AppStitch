package com.astral.stitchapp.rawloader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object GeckoCookieBridge {
    private const val TAG = "GeckoCookieBridge"
    private const val EXTENSION_ID = "cookie-bridge@astral.stitchapp"
    private const val BUILTIN_URI = "resource://android/assets/cookie_extractor/"

    @Volatile
    var latestCookieString: String = ""

    private val cookieMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getCookies(): Map<String, String> = cookieMap

    fun getCookieString(): String {
        return if (cookieMap.isNotEmpty()) {
            cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } else {
            latestCookieString
        }
    }

    fun updateCookiesFromExtension(cookieStr: String) {
        if (cookieStr.isBlank()) return
        latestCookieString = cookieStr
        for (part in cookieStr.split(";")) {
            val trimmed = part.trim()
            if (trimmed.contains("=")) {
                val k = trimmed.substringBefore("=").trim()
                val v = trimmed.substringAfter("=").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    cookieMap[k] = v
                }
            }
        }
    }

    suspend fun install(context: Context, runtime: GeckoRuntime) = withContext(Dispatchers.Main) {
        try {
            runtime.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
                override fun onInstallPrompt(extension: WebExtension): GeckoResult<AllowOrDeny> {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }

                override fun onUpdatePrompt(
                    currentlyInstalled: WebExtension,
                    updated: WebExtension,
                    newPermissions: Array<out String>,
                    newOrigins: Array<out String>
                ): GeckoResult<AllowOrDeny> {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
            })

            // Try ensureBuiltIn from APK assets
            runtime.webExtensionController.ensureBuiltIn(BUILTIN_URI, EXTENSION_ID)
                .accept(
                    { ext ->
                        setupExtension(runtime, ext)
                        Log.d(TAG, "ensureBuiltIn succeeded for $EXTENSION_ID")
                    },
                    { err ->
                        Log.w(TAG, "ensureBuiltIn failed ($err), falling back to XPI install")
                        installFromXpi(context, runtime)
                    }
                )
        } catch (e: Exception) {
            Log.e(TAG, "Error installing GeckoCookieBridge", e)
            installFromXpi(context, runtime)
        }
    }

    private fun setupExtension(runtime: GeckoRuntime, extension: WebExtension?) {
        if (extension == null) return
        try {
            runtime.webExtensionController.setAllowedInPrivateBrowsing(extension, true)
            runtime.webExtensionController.enable(extension, WebExtensionController.EnableSource.USER)

            extension.setMessageDelegate(object : WebExtension.MessageDelegate {
                override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                    try {
                        if (message is JSONObject) {
                            val cookiesStr = message.optString("cookieString", "")
                            if (cookiesStr.isNotBlank()) {
                                updateCookiesFromExtension(cookiesStr)
                            }
                        } else if (message is String) {
                            updateCookiesFromExtension(message)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling extension message", e)
                    }
                    return null
                }
            }, "browser")
        } catch (e: Exception) {
            Log.w(TAG, "setupExtension warning: ${e.message}")
        }
    }

    private fun installFromXpi(context: Context, runtime: GeckoRuntime) {
        try {
            val xpiFile = ensureXpiFile(context) ?: return
            val fileUri = "file://" + xpiFile.absolutePath
            runtime.webExtensionController.install(fileUri)
                .accept(
                    { ext ->
                        setupExtension(runtime, ext)
                        Log.d(TAG, "XPI install succeeded for $EXTENSION_ID")
                    },
                    { err ->
                        Log.e(TAG, "XPI install failed for $EXTENSION_ID", err)
                    }
                )
        } catch (e: Exception) {
            Log.e(TAG, "installFromXpi error", e)
        }
    }

    private fun ensureXpiFile(context: Context): File? {
        return try {
            val outFile = File(context.filesDir, "cookie_bridge.xpi")
            val assetManager = context.assets
            val assetFiles = assetManager.list("cookie_extractor") ?: return null
            if (assetFiles.isEmpty()) return null

            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                for (name in assetFiles) {
                    val entry = ZipEntry(name)
                    zos.putNextEntry(entry)
                    assetManager.open("cookie_extractor/$name").use { ins ->
                        ins.copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }
            if (outFile.length() > 0) outFile else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating cookie bridge XPI", e)
            null
        }
    }
}
