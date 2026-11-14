package com.astral.stitchapp.rawloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.sqrt

data class MangaGoConfig(
    val sojsonHeader: String = "['sojson.v4']",
    val sojsonSplitRegex: String = "[a-zA-Z]+",
    val sojsonHeadCut: Int = 240,
    val sojsonTailCut: Int = 59,
    val keyLocationRegex: String = """str\.charAt\(\s*(\d+)\s*\)""",
    val imgSrcsRegex: String = """var imgsrcs\s*=\s*['"]([a-zA-Z0-9+=/]+)['"]""",
    val colsRegex: String = """var\s*widthnum\s*=\s*heightnum\s*=\s*(\d+);""",
    val totalPagesRegex: String = """total_pages\s*=\s*(\d+)""",
    val hexVariableRegex: String = """var\s+(key|iv)\s*=\s*CryptoJS\.enc\.Hex\.parse\("([0-9a-zA-Z]+)"\)""",
    val jsFilters: List<String> = listOf(
        "jQuery",
        "document",
        "getContext",
        "toDataURL",
        "getImageData",
        "width",
        "height"
    ),
    val replacePosJs: String = """
        function replacePos(strObj, pos, replacetext) {
            var str = strObj.substr(0, pos) + replacetext + strObj.substring(pos + 1, strObj.length);
            return str;
        }
    """.trimIndent(),
    val renImgStart: String = "var renImg = function(img,width,height,id){",
    val renImgEnd: String = "key = key.split(",
    val scrambledKeyword: String = "cspiclink",
    val keySplitDelimiter: String = "a",
    val superCookieName: String = "_m_superu",
    val superCookieValue: String = "1",
    val baseUrl: String = "https://www.mangago.me"
)

object MangaGoSyncManager {
    private const val TAG = "MangaGoSyncManager"
    private const val PREF_NAME = "mangago_sync_prefs"
    private const val KEY_VERSION = "synced_version"
    private const val KEY_COMMIT_SHA = "synced_commit_sha"
    private const val KEY_LAST_SYNC = "last_sync_timestamp"
    private const val KEY_AUTO_SYNC = "auto_sync_enabled"

    const val PINNED_COMMIT_REF = "6f345a81459739e35d8fc94c05455464e856f771"
    private const val REPO_PATH = "src/en/mangago/src/eu/kanade/tachiyomi/extension/en/mangago"
    private const val COMMITS_API_URL =
        "https://api.github.com/repos/keiyoushi/extensions-source/commits?path=$REPO_PATH&per_page=1"

    private fun rawFileUrl(ref: String, relativePath: String): String =
        "https://raw.githubusercontent.com/keiyoushi/extensions-source/$ref/$relativePath"

    @Volatile
    private var cachedConfig: MangaGoConfig? = null

    private fun getSyncDir(context: Context): File {
        val dir = File(context.filesDir, "mangago_sync")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getSyncedMangagoFile(context: Context): File =
        File(getSyncDir(context), "Mangago.kt")

    private fun getSyncedSoJsonFile(context: Context): File =
        File(getSyncDir(context), "SoJsonV4Deobfuscator.kt")

    fun getInstalledVersion(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_VERSION, "Bundled (v45 • 6f345a8)") ?: "Bundled (v45 • 6f345a8)"
    }

    fun getInstalledVersionInfo(context: Context): String = getInstalledVersion(context)

    fun isAutoSyncEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_SYNC, true)
    }

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    private fun loadSourcePair(context: Context): Pair<String, String> {
        val mangagoFile = getSyncedMangagoFile(context)
        val sojsonFile = getSyncedSoJsonFile(context)
        val mangagoText = if (mangagoFile.exists() && mangagoFile.length() > 100) {
            runCatching { mangagoFile.readText() }.getOrNull()
        } else null
        val sojsonText = if (sojsonFile.exists() && sojsonFile.length() > 50) {
            runCatching { sojsonFile.readText() }.getOrNull()
        } else null

        val finalMangago = mangagoText ?: runCatching {
            context.assets.open("mangago_keiyoushi/Mangago.kt").bufferedReader().use { it.readText() }
        }.getOrDefault("")

        val finalSojson = sojsonText ?: runCatching {
            context.assets.open("mangago_keiyoushi/SoJsonV4Deobfuscator.kt").bufferedReader().use { it.readText() }
        }.getOrDefault("")

        return Pair(finalMangago, finalSojson)
    }

    fun getConfig(context: Context?): MangaGoConfig {
        cachedConfig?.let { return it }
        if (context == null) return MangaGoConfig()
        val (mangagoKt, sojsonKt) = loadSourcePair(context)
        val parsed = parseConfigFromKotlinSources(mangagoKt, sojsonKt)
        cachedConfig = parsed
        return parsed
    }

    fun parseConfigFromKotlinSources(mangagoKt: String, sojsonKt: String): MangaGoConfig {
        val def = MangaGoConfig()
        if (mangagoKt.isBlank() && sojsonKt.isBlank()) return def

        // 1. Parse SoJsonV4Deobfuscator.kt
        val sojsonHeader = Regex("""startsWith\("([^"]+)"\)""")
            .find(sojsonKt)?.groupValues?.get(1) ?: def.sojsonHeader

        val sojsonSplitRegex = Regex("""splitRegex[\s\S]*?Regex\((?:""${'"'}(.*?)""${'"'}|"([^"]+)")\)""")
            .find(sojsonKt)?.let { m ->
                m.groupValues[1].ifEmpty { m.groupValues[2] }
            }?.takeIf { it.isNotBlank() } ?: def.sojsonSplitRegex

        val subMatch = Regex("""substring\(\s*(\d+)\s*,\s*\w+\.length\s*-\s*(\d+)\s*\)""").find(sojsonKt)
        val sojsonHeadCut = subMatch?.groupValues?.get(1)?.toIntOrNull() ?: def.sojsonHeadCut
        val sojsonTailCut = subMatch?.groupValues?.get(2)?.toIntOrNull() ?: def.sojsonTailCut

        // 2. Parse Mangago.kt regex constants
        fun extractTripleQuoteRegex(name: String, fallback: String): String {
            val pattern = Regex("""val\s+$name\s*=\s*Regex\(\s*""${'"'}([\s\S]*?)""${'"'}""")
            return pattern.find(mangagoKt)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: fallback
        }

        val keyLocationRegex = extractTripleQuoteRegex("KEY_LOCATION_REGEX", def.keyLocationRegex)
        val imgSrcsRegex = extractTripleQuoteRegex("IMG_SRCS_REGEX", def.imgSrcsRegex)
        val colsRegex = extractTripleQuoteRegex("COLS_REGEX", def.colsRegex)
        val totalPagesRegex = extractTripleQuoteRegex("TOTAL_PAGES_REGEX", def.totalPagesRegex)
        val hexVariableRegex = extractTripleQuoteRegex("HEX_VARIABLE_REGEX", def.hexVariableRegex)

        val jsFilters = Regex("""val\s+JS_FILTERS\s*=\s*listOf\(([\s\S]*?)\)""")
            .find(mangagoKt)?.groupValues?.get(1)?.let { block ->
                Regex(""""([^"]+)"""").findAll(block).map { it.groupValues[1] }.toList()
            }?.takeIf { it.isNotEmpty() } ?: def.jsFilters

        val replacePosJs = Regex("""val\s+REPLACE_POS_BYTECODE[\s\S]*?""${'"'}([\s\S]*?)""${'"'}""")
            .find(mangagoKt)?.groupValues?.get(1)?.trimIndent()?.takeIf { it.isNotBlank() } ?: def.replacePosJs

        val getChapterUrlsBlock = mangagoKt.substringAfter("fun getChapterImageUrls", "")
            .substringBefore("private fun findHexEncodedVariable", "")

        val renImgStart = Regex("""\.substringAfter\("([^"]+)"\)""")
            .find(getChapterUrlsBlock)?.groupValues?.get(1) ?: def.renImgStart

        val renImgEnd = Regex("""\.substringBefore\("([^"]+)"\)""")
            .find(getChapterUrlsBlock)?.groupValues?.get(1) ?: def.renImgEnd

        val scrambledKeyword = Regex("""imageUrl\.contains\("([^"]+)"\)""")
            .find(getChapterUrlsBlock)?.groupValues?.get(1) ?: def.scrambledKeyword

        val unscrambleBlock = mangagoKt.substringAfter("fun unscrambleImage(", "")
            .substringBefore("private fun Element.imgAttr", "")

        val keySplitDelimiter = Regex("""key\.split\("([^"]+)"\)""")
            .find(unscrambleBlock)?.groupValues?.get(1) ?: def.keySplitDelimiter

        val cookieMatch = Regex("""addCookie\("([^"]+)"\s+to\s+"([^"]+)"\)""").find(mangagoKt)
        val superCookieName = cookieMatch?.groupValues?.get(1) ?: def.superCookieName
        val superCookieValue = cookieMatch?.groupValues?.get(2) ?: def.superCookieValue

        return MangaGoConfig(
            sojsonHeader = sojsonHeader,
            sojsonSplitRegex = sojsonSplitRegex,
            sojsonHeadCut = sojsonHeadCut,
            sojsonTailCut = sojsonTailCut,
            keyLocationRegex = keyLocationRegex,
            imgSrcsRegex = imgSrcsRegex,
            colsRegex = colsRegex,
            totalPagesRegex = totalPagesRegex,
            hexVariableRegex = hexVariableRegex,
            jsFilters = jsFilters,
            replacePosJs = replacePosJs,
            renImgStart = renImgStart,
            renImgEnd = renImgEnd,
            scrambledKeyword = scrambledKeyword,
            keySplitDelimiter = keySplitDelimiter,
            superCookieName = superCookieName,
            superCookieValue = superCookieValue
        )
    }

    private fun fetchUrlText(urlStr: String, acceptHeader: String? = null): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AstralStitch/1.5.8 (Android; Keiyoushi-Sync)")
                if (acceptHeader != null) {
                    setRequestProperty("Accept", acceptHeader)
                }
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching $urlStr: ${e.message}")
            null
        }
    }

    suspend fun checkAndSyncScript(
        context: Context,
        forceManual: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!forceManual && !isAutoSyncEnabled(context)) {
                return@withContext Result.success(getInstalledVersion(context))
            }

            onProgress("Checking keiyoushi/extensions-source for MangaGo updates...")

            // Determine latest commit SHA on keiyoushi/extensions-source
            var targetRef = "main"
            var shortSha = PINNED_COMMIT_REF.take(7)

            val commitsJson = fetchUrlText(COMMITS_API_URL, "application/vnd.github+json")
            if (!commitsJson.isNullOrBlank()) {
                runCatching {
                    val arr = JSONArray(commitsJson)
                    if (arr.length() > 0) {
                        val fullSha = arr.getJSONObject(0).optString("sha", "")
                        if (fullSha.length >= 7) {
                            targetRef = fullSha
                            shortSha = fullSha.take(7)
                        }
                    }
                }
            }

            onProgress("Fetching Mangago.kt & SoJsonV4Deobfuscator.kt ($shortSha)...")

            val mangagoRelPath = "$REPO_PATH/Mangago.kt"
            val sojsonRelPath = "$REPO_PATH/SoJsonV4Deobfuscator.kt"
            val gradleRelPath = "src/en/mangago/build.gradle.kts"

            val mangagoKt = fetchUrlText(rawFileUrl(targetRef, mangagoRelPath))
                ?: fetchUrlText(rawFileUrl(PINNED_COMMIT_REF, mangagoRelPath))
                ?: return@withContext Result.failure(Exception("Failed to fetch Mangago.kt from GitHub"))

            val sojsonKt = fetchUrlText(rawFileUrl(targetRef, sojsonRelPath))
                ?: fetchUrlText(rawFileUrl(PINNED_COMMIT_REF, sojsonRelPath))
                ?: return@withContext Result.failure(Exception("Failed to fetch SoJsonV4Deobfuscator.kt from GitHub"))

            val gradleKt = fetchUrlText(rawFileUrl(targetRef, gradleRelPath))
                ?: fetchUrlText(rawFileUrl(PINNED_COMMIT_REF, gradleRelPath))
                ?: ""

            if (!mangagoKt.contains("getChapterImageUrls") || !sojsonKt.contains("SoJsonV4Deobfuscator")) {
                return@withContext Result.failure(Exception("Invalid source content from repository"))
            }

            val versionCode = Regex("""versionCode\s*=\s*(\d+)""")
                .find(gradleKt)?.groupValues?.get(1) ?: "45"

            getSyncedMangagoFile(context).writeText(mangagoKt)
            getSyncedSoJsonFile(context).writeText(sojsonKt)

            cachedConfig = parseConfigFromKotlinSources(mangagoKt, sojsonKt)

            val versionDisplay = "v$versionCode ($shortSha)"
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_VERSION, versionDisplay)
                .putString(KEY_COMMIT_SHA, shortSha)
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .apply()

            onProgress("Synced MangaGo logic $versionDisplay")
            Result.success(versionDisplay)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing MangaGo script", e)
            Result.failure(e)
        }
    }

    /**
     * Ported directly from keiyoushi SoJsonV4Deobfuscator.decode
     */
    fun decodeSoJsonV4(jsf: String, context: Context? = null): String {
        val cfg = getConfig(context)
        val trimmed = jsf.trim()
        if (!trimmed.startsWith(cfg.sojsonHeader)) {
            return jsf
        }
        val args = trimmed.substring(cfg.sojsonHeadCut, trimmed.length - cfg.sojsonTailCut)
            .split(Regex(cfg.sojsonSplitRegex))
        return args.mapNotNull { token ->
            token.toIntOrNull()?.toChar()
        }.joinToString("")
    }

    private fun String.decodeHex(): ByteArray {
        val clean = this.trim()
        val len = clean.length
        val out = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val hi = Character.digit(clean[i], 16)
            val lo = Character.digit(clean[i + 1], 16)
            out[i / 2] = ((hi shl 4) + lo).toByte()
            i += 2
        }
        return out
    }

    /**
     * Ported directly from keiyoushi Mangago.kt:
     * encryptedImages.aesDecrypt(newAesCipher("AES/CBC/ZEROBYTEPADDING", key, IvParameterSpec(iv))).decodeToString()
     */
    fun aesDecryptZeroPadding(base64Encrypted: String, keyHex: String, ivHex: String): String {
        val encryptedBytes = Base64.decode(base64Encrypted.trim(), Base64.DEFAULT)
        val keyBytes = keyHex.decodeHex()
        val ivBytes = ivHex.decodeHex()
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)

        val decryptedBytes = try {
            val cipher = Cipher.getInstance("AES/CBC/ZeroBytePadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(encryptedBytes)
        } catch (_: Exception) {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(encryptedBytes)
        }

        var endIndex = decryptedBytes.size
        while (endIndex > 0 && decryptedBytes[endIndex - 1] == 0.toByte()) {
            endIndex--
        }
        return String(decryptedBytes, 0, endIndex, Charsets.UTF_8)
    }

    private fun String.unscramble(keys: List<Int>): String {
        var result = this
        keys.reversed().forEach { key ->
            for (index in result.length - 1 downTo key) {
                if (index % 2 != 0) {
                    val previous = result[index - key]
                    result = result.replaceRange(index - key..index - key, result[index].toString())
                    result = result.replaceRange(index..index, previous.toString())
                }
            }
        }
        return result
    }

    /**
     * Ported directly from keiyoushi Mangago.unscrambleImageList
     */
    fun unscrambleImageList(imageList: String, js: String, context: Context? = null): String {
        val cfg = getConfig(context)
        var result = imageList
        try {
            val keyLocRegex = Regex(cfg.keyLocationRegex)
            val keyLocations = keyLocRegex.findAll(js)
                .map { it.groupValues[1].toInt() }
                .distinct()
                .toList()
            val keys = keyLocations.map { result[it].toString().toInt() }
            keyLocations.forEachIndexed { index, location ->
                result = result.removeRange(location - index..location - index)
            }
            result = result.unscramble(keys)
        } catch (_: NumberFormatException) {
            // The image list is already unscrambled.
        } catch (_: IndexOutOfBoundsException) {
            // The image list is already unscrambled.
        }
        return result
    }

    /**
     * Ported directly from keiyoushi Mangago.unscrambleImage:
     *
     * val unitWidth = bitmap.width / cols
     * val unitHeight = bitmap.height / cols
     * val keyArray = key.split("a")
     * for (index in 0 until cols * cols) {
     *     val keyValue = keyArray[index].ifEmpty { "0" }.toInt()
     *     val destinationRow = keyValue.floorDiv(cols)
     *     val sourceRow = index.floorDiv(cols)
     *     val sourceX = (index - sourceRow * cols) * unitWidth
     *     val sourceY = sourceRow * unitHeight
     *     val destinationX = (keyValue - destinationRow * cols) * unitWidth
     *     val destinationY = destinationRow * unitHeight
     *     canvas.drawBitmap(
     *         bitmap,
     *         Rect(sourceX, sourceY, sourceX + unitWidth, sourceY + unitHeight),
     *         Rect(destinationX, destinationY, destinationX + unitWidth, destinationY + unitHeight),
     *         null,
     *     )
     * }
     */
    fun unscrambleImage(path: File, key: String, colsParam: Int, context: Context? = null) {
        try {
            val cfg = getConfig(context)
            val bitmap = BitmapFactory.decodeFile(path.absolutePath) ?: return
            val keyArray = key.split(cfg.keySplitDelimiter)
            val cols = if (colsParam > 0) {
                colsParam
            } else {
                val count = key.trimEnd(*cfg.keySplitDelimiter.toCharArray())
                    .split(cfg.keySplitDelimiter).size
                sqrt(count.toDouble()).toInt()
            }
            if (cols <= 0) {
                bitmap.recycle()
                return
            }

            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            // Preserve any 1-2px remainder strips on right/bottom edges
            canvas.drawBitmap(bitmap, 0f, 0f, null)

            val unitWidth = bitmap.width / cols
            val unitHeight = bitmap.height / cols

            for (index in 0 until cols * cols) {
                val keyValue = keyArray.getOrNull(index).orEmpty().ifEmpty { "0" }.toIntOrNull() ?: 0
                val destinationRow = keyValue.floorDiv(cols)
                val sourceRow = index.floorDiv(cols)
                val sourceX = (index - sourceRow * cols) * unitWidth
                val sourceY = sourceRow * unitHeight
                val destinationX = (keyValue - destinationRow * cols) * unitWidth
                val destinationY = destinationRow * unitHeight
                canvas.drawBitmap(
                    bitmap,
                    Rect(sourceX, sourceY, sourceX + unitWidth, sourceY + unitHeight),
                    Rect(destinationX, destinationY, destinationX + unitWidth, destinationY + unitHeight),
                    null,
                )
            }

            path.outputStream().use { outs ->
                when (path.extension.lowercase(Locale.ROOT)) {
                    "png" -> result.compress(Bitmap.CompressFormat.PNG, 100, outs)
                    "webp" -> {
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            result.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, outs)
                        } else {
                            @Suppress("DEPRECATION")
                            result.compress(Bitmap.CompressFormat.WEBP, 100, outs)
                        }
                    }
                    else -> result.compress(Bitmap.CompressFormat.JPEG, 95, outs)
                }
            }
            bitmap.recycle()
            result.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error unscrambling MangaGo image ${path.name}", e)
        }
    }

    /**
     * Generates the WebView/GeckoView scraper script dynamically from the synced Keiyoushi MangagoConfig.
     */
    fun getScraperScript(context: Context?): String {
        val cfg = getConfig(context)
        val jsFiltersJson = JSONArray(cfg.jsFilters).toString()
        val sojsonHeaderEsc = JSONObject.quote(cfg.sojsonHeader)
        val sojsonSplitEsc = JSONObject.quote(cfg.sojsonSplitRegex)
        val keyLocEsc = JSONObject.quote(cfg.keyLocationRegex)
        val imgSrcsEsc = JSONObject.quote(cfg.imgSrcsRegex)
        val colsEsc = JSONObject.quote(cfg.colsRegex)
        val totalPagesEsc = JSONObject.quote(cfg.totalPagesRegex)
        val hexVarEsc = JSONObject.quote(cfg.hexVariableRegex)
        val renImgStartEsc = JSONObject.quote(cfg.renImgStart)
        val renImgEndEsc = JSONObject.quote(cfg.renImgEnd)
        val scrambledKwEsc = JSONObject.quote(cfg.scrambledKeyword)
        val superCookieAssign = JSONObject.quote("${cfg.superCookieName}=${cfg.superCookieValue}; path=/")

        return """
        (function() {
            'use strict';

            function log(msg) {
                console.log("[MangaGo Keiyoushi] " + msg);
                if (window.Android && window.Android.log) { window.Android.log(msg); }
            }

            try {
                document.cookie = $superCookieAssign;
            } catch (_) {}

            const SOJSON_HEADER = $sojsonHeaderEsc;
            const SOJSON_SPLIT_RE = new RegExp($sojsonSplitEsc);
            const SOJSON_HEAD_CUT = ${cfg.sojsonHeadCut};
            const SOJSON_TAIL_CUT = ${cfg.sojsonTailCut};

            const KEY_LOCATION_RE_STR = $keyLocEsc;
            const IMG_SRCS_RE = new RegExp($imgSrcsEsc);
            const COLS_RE = new RegExp($colsEsc);
            const TOTAL_PAGES_RE = new RegExp($totalPagesEsc);
            const HEX_VAR_RE_STR = $hexVarEsc;
            const JS_FILTERS = $jsFiltersJson;
            const REN_IMG_START = $renImgStartEsc;
            const REN_IMG_END = $renImgEndEsc;
            const SCRAMBLED_KEYWORD = $scrambledKwEsc;

            const loadCryptoJS = () => {
                return new Promise((resolve, reject) => {
                    if (typeof CryptoJS !== 'undefined') { resolve(); return; }
                    const script = document.createElement('script');
                    script.src = 'https://cdnjs.cloudflare.com/ajax/libs/crypto-js/4.1.1/crypto-js.min.js';
                    script.onload = () => resolve();
                    script.onerror = () => reject(new Error('Failed to load CryptoJS'));
                    document.head.appendChild(script);
                });
            };

            function decodeSoJsonV4(jsf) {
                if (!jsf) return "";
                if (window.Android && typeof window.Android.deobfuscateSoJsonV4 === 'function') {
                    try {
                        const res = window.Android.deobfuscateSoJsonV4(jsf);
                        if (res && res.length > 0 && !res.startsWith("ERROR:")) return res;
                    } catch (_) {}
                }
                const trimmed = jsf.trim();
                if (!trimmed.startsWith(SOJSON_HEADER)) return jsf;
                const args = trimmed.substring(SOJSON_HEAD_CUT, trimmed.length - SOJSON_TAIL_CUT).split(SOJSON_SPLIT_RE);
                return args.map(c => String.fromCharCode(parseInt(c, 10))).join('');
            }

            function findHexEncodedVariable(input, variable) {
                const re = new RegExp(HEX_VAR_RE_STR, 'g');
                let m;
                while ((m = re.exec(input)) !== null) {
                    if (m[1] === variable) return m[2];
                }
                return '';
            }

            function unscrambleImageList(imageList, js) {
                if (window.Android && typeof window.Android.unscrambleMangagoList === 'function') {
                    try {
                        const res = window.Android.unscrambleMangagoList(imageList, js);
                        if (res && res.length > 0) return res;
                    } catch (_) {}
                }
                let result = imageList;
                try {
                    const re = new RegExp(KEY_LOCATION_RE_STR, 'g');
                    const matches = [...js.matchAll(re)];
                    const keyLocations = [...new Set(matches.map(m => parseInt(m[1], 10)))];
                    const keys = [];
                    for (let i = 0; i < keyLocations.length; i++) {
                        const digit = parseInt(result.charAt(keyLocations[i]), 10);
                        if (isNaN(digit)) return imageList;
                        keys.push(digit);
                    }
                    keyLocations.forEach((location, index) => {
                        const pos = location - index;
                        result = result.slice(0, pos) + result.slice(pos + 1);
                    });
                    const arr = result.split('');
                    for (let k = keys.length - 1; k >= 0; k--) {
                        const key = keys[k];
                        for (let index = arr.length - 1; index >= key; index--) {
                            if (index % 2 !== 0) {
                                const prev = arr[index - key];
                                arr[index - key] = arr[index];
                                arr[index] = prev;
                            }
                        }
                    }
                    result = arr.join('');
                } catch (e) {
                    return imageList;
                }
                return result;
            }

            async function fetchText(targetUrl) {
                if (window.Android && typeof window.Android.fetchUrl === 'function') {
                    const res = window.Android.fetchUrl(targetUrl);
                    if (res && !res.startsWith("ERROR:")) return res;
                }
                const resp = await fetch(targetUrl, { credentials: 'include' });
                if (!resp.ok) throw new Error("HTTP " + resp.status + " fetching " + targetUrl);
                return await resp.text();
            }

            async function aesDecryptImgSrcs(encryptedBase64, keyHexStr, ivHexStr) {
                if (window.Android && typeof window.Android.aesDecryptMangago === 'function') {
                    try {
                        const dec = window.Android.aesDecryptMangago(encryptedBase64, keyHexStr, ivHexStr);
                        if (dec && !dec.startsWith("ERROR:")) return dec;
                    } catch (_) {}
                }
                await loadCryptoJS();
                const keyHex = CryptoJS.enc.Hex.parse(keyHexStr);
                const ivHex = CryptoJS.enc.Hex.parse(ivHexStr);
                const decrypted = CryptoJS.AES.decrypt(encryptedBase64, keyHex, {
                    iv: ivHex,
                    mode: CryptoJS.mode.CBC,
                    padding: CryptoJS.pad.ZeroPadding
                });
                return decrypted.toString(CryptoJS.enc.Utf8).replace(/\0+$/, '');
            }

            function getReaderPrefix(locHref, urlTemplate) {
                const loc = new URL(locHref);
                const templateSegment = urlTemplate.replace(/^\//, '').split('/')[0];
                const baseHost = "www.mangago.me";
                const isBaseHost = loc.hostname === baseHost || loc.hostname === baseHost.replace(/^www\./, '');
                const pathSegments = loc.pathname.split('/').filter(Boolean);

                if (isBaseHost && pathSegments.length > 3 && pathSegments[0] === 'read-manga' && pathSegments[2] === templateSegment) {
                    return loc.origin + '/read-manga/' + pathSegments[1];
                }
                if (!isBaseHost && pathSegments[0] === templateSegment) {
                    return loc.origin;
                }
                if (pathSegments[0] === 'read-manga' && pathSegments.length >= 2) {
                    return loc.origin + '/read-manga/' + pathSegments[1];
                }
                return loc.origin;
            }

            ${cfg.replacePosJs}

            window.runScraper = async function() {
                log("Starting MangaGo Scraper (Keiyoushi Sync)...");
                try {
                    const html = document.documentElement.innerHTML;
                    const scriptElem = document.querySelector('script[src*="chapter.js"]');
                    if (!scriptElem || !scriptElem.src) {
                        throw new Error("Could not find chapter.js script on this page. Make sure a chapter reader page is open.");
                    }
                    const chapterJsUrl = scriptElem.src;
                    log("Fetching chapter.js...");
                    const chapterJsRaw = await fetchText(chapterJsUrl);
                    const chapterJs = decodeSoJsonV4(chapterJsRaw);

                    const keyHexStr = findHexEncodedVariable(chapterJs, "key");
                    const ivHexStr = findHexEncodedVariable(chapterJs, "iv");
                    if (!keyHexStr || !ivHexStr) {
                        throw new Error("Failed to extract AES key/iv from chapter.js");
                    }

                    const colsMatch = COLS_RE.exec(chapterJs);
                    const cols = colsMatch ? colsMatch[1] : "";

                    let imageKeyScript = "";
                    const startIdx = chapterJs.indexOf(REN_IMG_START);
                    if (startIdx !== -1) {
                        const afterStart = chapterJs.substring(startIdx + REN_IMG_START.length);
                        const endIdx = afterStart.indexOf(REN_IMG_END);
                        if (endIdx !== -1) {
                            imageKeyScript = afterStart.substring(0, endIdx)
                                .split(/\r?\n/)
                                .filter(line => !JS_FILTERS.some(f => line.includes(f)))
                                .join("\n")
                                .replace(/img\.src/g, "url");
                        }
                    }

                    const getDescramblingKey = imageKeyScript
                        ? new Function("url", ${JSONObject.quote(cfg.replacePosJs)} + "\n" + imageKeyScript + "\nreturn String(key);")
                        : null;

                    async function getChapterImageUrlsFromHtml(pageHtml) {
                        const m = IMG_SRCS_RE.exec(pageHtml);
                        if (!m || !m[1]) return [];
                        const encryptedB64 = m[1];
                        let imageList = await aesDecryptImgSrcs(encryptedB64, keyHexStr, ivHexStr);
                        imageList = unscrambleImageList(imageList, chapterJs);

                        return imageList.split(",").map(rawUrl => {
                            let imageUrl = rawUrl.trim();
                            if (!imageUrl) return "";
                            if (imageUrl.startsWith("//")) imageUrl = "https:" + imageUrl;
                            if (imageUrl.includes(SCRAMBLED_KEYWORD) && getDescramblingKey) {
                                const descKey = getDescramblingKey(imageUrl);
                                return imageUrl + "#desckey=" + descKey + "&cols=" + cols;
                            }
                            return imageUrl;
                        });
                    }

                    const availableImages = await getChapterImageUrlsFromHtml(html);
                    const nonEmptyInitial = availableImages.filter(u => u && u.length > 0);
                    if (nonEmptyInitial.length === 0) {
                        throw new Error("No images found in chapter page");
                    }

                    const totalPagesMatch = TOTAL_PAGES_RE.exec(html);
                    const totalPages = totalPagesMatch ? parseInt(totalPagesMatch[1], 10) : availableImages.length;
                    const curlInput = document.querySelector('input#curl');
                    const urlTemplate = curlInput ? (curlInput.getAttribute('value') || curlInput.value || '') : '';
                    const batchSize = nonEmptyInitial.length;

                    const finalImages = [];
                    const batchCache = new Map();

                    if (totalPages > 0 && urlTemplate && urlTemplate.includes("{page}") && batchSize > 0) {
                        const readerPrefix = getReaderPrefix(location.href, urlTemplate);
                        for (let index = 0; index < totalPages; index++) {
                            let imgUrl = (index < availableImages.length && availableImages[index]) ? availableImages[index] : "";
                            if (!imgUrl) {
                                const pageNumber = index + 1;
                                const batchStart = Math.floor((pageNumber - 1) / batchSize) * batchSize + 1;
                                const relPath = urlTemplate.replace(/^\//, '').replace('{page}', String(batchStart));
                                const batchUrl = readerPrefix.replace(/\/$/, '') + '/' + relPath;

                                if (!batchCache.has(batchUrl)) {
                                    log("Fetching page batch starting at " + batchStart + "...");
                                    const batchHtml = await fetchText(batchUrl);
                                    const batchUrls = await getChapterImageUrlsFromHtml(batchHtml);
                                    batchCache.set(batchUrl, batchUrls);
                                }
                                const cachedBatch = batchCache.get(batchUrl) || [];
                                imgUrl = cachedBatch[index] || "";
                            }
                            if (imgUrl) {
                                finalImages.push(imgUrl);
                            }
                            if (window.Android && window.Android.updateProgress) {
                                window.Android.updateProgress(finalImages.length, totalPages, false);
                            }
                        }
                    } else {
                        for (const u of nonEmptyInitial) {
                            finalImages.push(u);
                        }
                    }

                    if (finalImages.length === 0) {
                        throw new Error("Decoded image list is empty");
                    }

                    if (window.Android && window.Android.updateProgress) {
                        window.Android.updateProgress(finalImages.length, finalImages.length, true);
                    }

                    const title = document.title
                        .replace(/- Read Free Manga Online.*$/i, "")
                        .replace(/- Read Manga Online Free.*$/i, "")
                        .replace(/Read Free Manga Online.*$/i, "")
                        .replace(/- Mangago.*$/i, "")
                        .trim() || "MangaGo Chapter";

                    log("Found " + finalImages.length + " images for " + title);
                    const result = {
                        title: title,
                        images: finalImages,
                        cookie: document.cookie
                    };
                    if (window.Android && window.Android.onImagesFound) {
                        window.Android.onImagesFound(JSON.stringify(result));
                    }
                } catch (e) {
                    log("MangaGo Scraper Error: " + e.message);
                }
            };
        })();
        """.trimIndent()
    }
}
