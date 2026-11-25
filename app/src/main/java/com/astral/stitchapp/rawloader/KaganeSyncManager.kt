package com.astral.stitchapp.rawloader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class KaganeConfig(
    val hubUrl: String = "https://kagane.org/",
    val apiPath: String = "/api/v2",
    val integrityPath: String = "/api/integrity",
    val integrityHeader: String = "x-integrity-token",
    val intent: String = "Read",
    val originalSegment: String = "original",
    val dataSaverSegment: String = "datasaver",
    val defaultImageExt: String = "jxl",
    val searchSize: Int = 35,
    val defaultLanguage: String = "en",
    val linkHosts: List<String> = listOf("kagane.cx", "kagane.moe", "kagane.net"),
    val mirrors: List<String> = listOf("https://kagane.moe")
) {
    val bookPagePath: String
        get() = "$apiPath/books/page"

    val defaultBaseUrl: String
        get() = mirrors.firstOrNull()?.trimEnd('/') ?: "https://kagane.moe"

    fun baseOrigin(url: String?): String? {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return null
        return runCatching {
            val parsed = URL(url)
            "${parsed.protocol}://${parsed.host}/"
        }.getOrNull()
    }
}

/**
 * Mirrors the Kagane source from keiyoushi/extensions-source.
 *
 * The site rotates its mirrors and its reader API fairly often, so instead of a frozen
 * `.asp` patch the source logic is synced from the official extension repository (same
 * approach as [MangaGoSyncManager]) and the scraper script is generated from the parsed
 * Keiyoushi constants.
 */
object KaganeSyncManager {
    private const val TAG = "KaganeSyncManager"
    private const val PREF_NAME = "kagane_sync_prefs"
    private const val KEY_VERSION = "synced_version"
    private const val KEY_COMMIT_SHA = "synced_commit_sha"
    private const val KEY_LAST_SYNC = "last_sync_timestamp"
    private const val KEY_AUTO_SYNC = "auto_sync_enabled"
    private const val KEY_LAST_BASE_URL = "last_base_url"

    const val PINNED_COMMIT_REF = "bac637ac333a03da0f8c5e7894713d782670b2d7"
    private const val PINNED_VERSION_CODE = "42"
    private const val DEFAULT_VERSION_LABEL = "Bundled (v$PINNED_VERSION_CODE • bac637a)"
    private const val REPO_PATH = "src/all/kagane/src/eu/kanade/tachiyomi/extension/all/kagane"
    private const val COMMITS_API_URL =
        "https://api.github.com/repos/keiyoushi/extensions-source/commits?path=$REPO_PATH&per_page=1"

    @Volatile
    private var cachedConfig: KaganeConfig? = null

    @Volatile
    private var integrityToken: String = ""

    @Volatile
    private var integrityExpMillis: Long = 0L

    private fun rawFileUrl(ref: String, relativePath: String): String =
        "https://raw.githubusercontent.com/keiyoushi/extensions-source/$ref/$relativePath"

    private fun getSyncDir(context: Context): File {
        val dir = File(context.filesDir, "kagane_sync")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getSyncedKaganeFile(context: Context): File =
        File(getSyncDir(context), "Kagane.kt")

    private fun getSyncedGradleFile(context: Context): File =
        File(getSyncDir(context), "build.gradle.kts")

    fun getInstalledVersion(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_VERSION, DEFAULT_VERSION_LABEL) ?: DEFAULT_VERSION_LABEL
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
        val kaganeFile = getSyncedKaganeFile(context)
        val gradleFile = getSyncedGradleFile(context)

        val kaganeText = if (kaganeFile.exists() && kaganeFile.length() > 200) {
            runCatching { kaganeFile.readText() }.getOrNull()
        } else null
        val gradleText = if (gradleFile.exists() && gradleFile.length() > 20) {
            runCatching { gradleFile.readText() }.getOrNull()
        } else null

        val finalKagane = kaganeText ?: runCatching {
            context.assets.open("kagane_keiyoushi/Kagane.kt").bufferedReader().use { it.readText() }
        }.getOrDefault("")

        val finalGradle = gradleText ?: runCatching {
            context.assets.open("kagane_keiyoushi/build.gradle.kts").bufferedReader().use { it.readText() }
        }.getOrDefault("")

        return Pair(finalKagane, finalGradle)
    }

    fun getConfig(context: Context?): KaganeConfig {
        cachedConfig?.let { return it }
        if (context == null) return KaganeConfig()
        val (kaganeKt, gradleKt) = loadSourcePair(context)
        val parsed = parseConfigFromKotlinSources(kaganeKt, gradleKt)
        cachedConfig = parsed
        return parsed
    }

    fun getDefaultBaseUrl(context: Context?): String = getConfig(context).defaultBaseUrl

    /** Remembers the mirror the scraper last ran on, so image downloads can send a matching Origin. */
    fun setLastBaseUrl(context: Context, url: String?) {
        val origin = getConfig(context).baseOrigin(url) ?: return
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_BASE_URL, origin.trimEnd('/'))
            .apply()
    }

    fun getLastBaseUrl(context: Context?): String {
        if (context == null) return getConfig(null).defaultBaseUrl.trimEnd('/')
        val stored = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_BASE_URL, null)
        return stored?.takeIf { it.isNotBlank() }?.trimEnd('/')
            ?: getConfig(context).defaultBaseUrl.trimEnd('/')
    }

    /**
     * Extracts the values that the reader API flow depends on straight from the Keiyoushi source,
     * so a synced script always follows upstream changes.
     */
    fun parseConfigFromKotlinSources(kaganeKt: String, gradleKt: String): KaganeConfig {
        val def = KaganeConfig()
        if (kaganeKt.isBlank() && gradleKt.isBlank()) return def

        val hubUrl = Regex("HUB_URL\\s*=\\s*\"([^\"]+)\"")
            .find(kaganeKt)?.groupValues?.get(1) ?: def.hubUrl

        val apiPath = Regex("apiUrl\\s+get\\(\\)\\s*=\\s*\"\\\$baseUrl([^\"]*)\"")
            .find(kaganeKt)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: def.apiPath

        val integrityBlock = kaganeKt.substringAfter("private suspend fun getIntegrityToken", "")
            .substringBefore("private suspend fun getChallengeResponse", "")
        val integrityPath = Regex("\"\\\$baseUrl([^\"]*)\"")
            .find(integrityBlock)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: def.integrityPath

        val integrityHeader = Regex("""add\("([^"]+)",\s*integrityToken\)""")
            .find(kaganeKt)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: def.integrityHeader

        val intent = Regex("""addQueryParameter\("intent",\s*"([^"]+)"\)""")
            .find(kaganeKt)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: def.intent

        val pathSegmentMatch = Regex("""addPathSegment\(if \(dataSaver\) "([^"]+)" else "([^"]+)"\)""")
            .find(kaganeKt)
        val dataSaverSegment = pathSegmentMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: def.dataSaverSegment
        val originalSegment = pathSegmentMatch?.groupValues?.get(2)?.takeIf { it.isNotBlank() } ?: def.originalSegment

        val defaultImageExt = Regex("page\\.ext \\?: \"([^\"]+)\"")
            .find(kaganeKt)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: def.defaultImageExt

        val searchSize = Regex("""addQueryParameter\("size",\s*(\d+)\.toString\(\)\)""")
            .find(kaganeKt)?.groupValues?.get(1)?.toIntOrNull() ?: def.searchSize

        val linkHostsBlock = kaganeKt.substringAfter("LINK_HOSTS = setOf(", "")
            .substringBefore(")")
        val linkHosts = Regex("\"([^\"]+)\"")
            .findAll(linkHostsBlock).map { it.groupValues[1] }.toList()
            .ifEmpty { def.linkHosts }

        val mirrors = Regex("""mirrors\("([^"]+)"\)""")
            .findAll(gradleKt).map { it.groupValues[1] }.toList()
            .ifEmpty { def.mirrors }

        val defaultLanguage = Regex("listOf\\(\\s*\"([^\"]+)\"")
            .find(gradleKt)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: def.defaultLanguage

        return KaganeConfig(
            hubUrl = hubUrl,
            apiPath = apiPath,
            integrityPath = integrityPath,
            integrityHeader = integrityHeader,
            intent = intent,
            originalSegment = originalSegment,
            dataSaverSegment = dataSaverSegment,
            defaultImageExt = defaultImageExt,
            searchSize = searchSize,
            defaultLanguage = defaultLanguage,
            linkHosts = linkHosts,
            mirrors = mirrors
        )
    }

    private fun fetchUrlText(urlStr: String, acceptHeader: String? = null): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AstralStitch/1.5.9 (Android; Keiyoushi-Sync)")
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

            onProgress("Checking keiyoushi/extensions-source for Kagane updates...")

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

            onProgress("Fetching Kagane.kt ($shortSha)...")

            val kaganeRelPath = "$REPO_PATH/Kagane.kt"
            val gradleRelPath = "src/all/kagane/build.gradle.kts"

            val kaganeKt = fetchUrlText(rawFileUrl(targetRef, kaganeRelPath))
                ?: fetchUrlText(rawFileUrl(PINNED_COMMIT_REF, kaganeRelPath))
                ?: return@withContext Result.failure(Exception("Failed to fetch Kagane.kt from GitHub"))

            val gradleKt = fetchUrlText(rawFileUrl(targetRef, gradleRelPath))
                ?: fetchUrlText(rawFileUrl(PINNED_COMMIT_REF, gradleRelPath))
                ?: ""

            if (!kaganeKt.contains("getChallengeResponse") || !kaganeKt.contains("books/page")) {
                return@withContext Result.failure(Exception("Invalid Kagane source content from repository"))
            }

            val versionCode = Regex("""versionCode\s*=\s*(\d+)""")
                .find(gradleKt)?.groupValues?.get(1) ?: PINNED_VERSION_CODE

            val parsed = parseConfigFromKotlinSources(kaganeKt, gradleKt)
            if (parsed.apiPath.isBlank() || parsed.integrityPath.isBlank()) {
                return@withContext Result.failure(Exception("Parsed Kagane config is incomplete"))
            }

            getSyncedKaganeFile(context).writeText(kaganeKt)
            getSyncedGradleFile(context).writeText(gradleKt)

            cachedConfig = parsed
            integrityToken = ""
            integrityExpMillis = 0L

            val versionDisplay = "v$versionCode ($shortSha)"
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_VERSION, versionDisplay)
                .putString(KEY_COMMIT_SHA, shortSha)
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .apply()

            onProgress("Synced Kagane logic $versionDisplay")
            Result.success(versionDisplay)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing Kagane script", e)
            Result.failure(e)
        }
    }

    private fun urlEnc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun postJson(
        urlStr: String,
        body: String,
        cookie: String? = null,
        headers: Map<String, String> = emptyMap()
    ): JSONObject? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                )
                runCatching {
                    val origin = URL(urlStr)
                    setRequestProperty("Origin", "${origin.protocol}://${origin.host}")
                    setRequestProperty("Referer", "${origin.protocol}://${origin.host}/")
                }
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${conn.responseCode} for $urlStr")
                null
            } else {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST $urlStr failed: ${e.message}")
            null
        }
    }

    /**
     * Port of the Keiyoushi refreshToken flow: reader tokens are bound to a single book,
     * so a rejected token is renewed with a fresh integrity token + challenge request.
     */
    fun refreshAccessToken(
        context: Context?,
        baseUrl: String,
        chapterId: String,
        cookie: String? = null
    ): String? {
        val cfg = getConfig(context)
        val origin = baseUrl.trimEnd('/')
        val now = System.currentTimeMillis()

        if (integrityToken.isBlank() || now >= integrityExpMillis) {
            val integrity = postJson("$origin${cfg.integrityPath}", "", cookie) ?: return null
            val token = integrity.optString("token", "")
            if (token.isBlank()) return null
            integrityToken = token
            val exp = integrity.optLong("exp", 0L)
            integrityExpMillis = if (exp > 0) exp * 1000L else now + 5 * 60 * 1000L
        }

        val challengeUrl = "$origin${cfg.apiPath}/books/${urlEnc(chapterId)}?intent=${urlEnc(cfg.intent)}"
        val challenge = postJson(
            challengeUrl,
            "",
            cookie,
            mapOf(cfg.integrityHeader to integrityToken)
        ) ?: return null

        return challenge.optString("access_token", "").takeIf { it.isNotBlank() }
    }

    /** Extracts the book/chapter id from `/api/v2/books/page/{segment}/{chapterId}/{file}`. */
    fun parseChapterIdFromPageUrl(urlStr: String): String? {
        return try {
            val path = URL(urlStr).path ?: return null
            val segments = path.split('/').filter { it.isNotBlank() }
            if (segments.size < 2) return null
            segments[segments.size - 2].takeIf { it.length >= 16 }
        } catch (e: Exception) {
            null
        }
    }

    fun replaceToken(urlStr: String, token: String): String {
        val enc = urlEnc(token)
        return Regex("""([?&])token=[^&]*""").replace(urlStr) { match ->
            "${match.groupValues[1]}token=$enc"
        }
    }

    /**
     * Generates the WebView scraper script from the synced Keiyoushi Kagane config.
     */
    fun getScraperScript(context: Context?): String {
        val cfg = getConfig(context)
        val apiPathEsc = JSONObject.quote(cfg.apiPath)
        val pagePathEsc = JSONObject.quote(cfg.bookPagePath)
        val integrityPathEsc = JSONObject.quote(cfg.integrityPath)
        val integrityHeaderEsc = JSONObject.quote(cfg.integrityHeader)
        val intentEsc = JSONObject.quote(cfg.intent)
        val originalSegmentEsc = JSONObject.quote(cfg.originalSegment)
        val defaultExtEsc = JSONObject.quote(cfg.defaultImageExt)
        val langEsc = JSONObject.quote(cfg.defaultLanguage)
        val linkHostsJson = JSONArray(cfg.linkHosts).toString()

        return """
        (function() {
            'use strict';

            function log(msg) {
                console.log("[Kagane Keiyoushi] " + msg);
                if (window.Android && window.Android.log) { window.Android.log(msg); }
            }

            const API_PATH = $apiPathEsc;
            const PAGE_PATH = $pagePathEsc;
            const INTEGRITY_PATH = $integrityPathEsc;
            const INTEGRITY_HEADER = $integrityHeaderEsc;
            const INTENT = $intentEsc;
            const ORIGINAL_SEGMENT = $originalSegmentEsc;
            const DEFAULT_EXT = $defaultExtEsc;
            const CONTENT_LANG = $langEsc;
            const SEARCH_SIZE = ${cfg.searchSize};
            const LINK_HOSTS = $linkHostsJson;
            const UUID_REGEX = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

            function lastId(segment) {
                if (!segment) return '';
                let value = segment;
                try { value = decodeURIComponent(segment); } catch (_) {}
                return value.length > 36 ? value.slice(-36) : value;
            }

            async function postJson(path, body, headers) {
                const requestHeaders = Object.assign({
                    'Content-Type': 'application/json',
                    'Accept': 'application/json'
                }, headers || {});
                const target = new URL(path, location.origin).toString();
                const resp = await fetch(target, {
                    method: 'POST',
                    credentials: 'include',
                    headers: requestHeaders,
                    body: body === null || body === undefined ? '' : JSON.stringify(body)
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status + ' for ' + path);
                const text = await resp.text();
                if (!text) return {};
                return JSON.parse(text);
            }

            async function getJson(path) {
                const target = new URL(path, location.origin).toString();
                const resp = await fetch(target, {
                    credentials: 'include',
                    headers: { 'Accept': 'application/json' }
                });
                if (!resp.ok) throw new Error('HTTP ' + resp.status + ' for ' + path);
                return resp.json();
            }

            async function resolveSeriesId(slug) {
                if (!slug || UUID_REGEX.test(slug)) return slug;
                try {
                    const data = await postJson(API_PATH + '/search/series?page=0&size=' + SEARCH_SIZE, {
                        title: slug.replace(/-/g, ' '),
                        content_lang: [CONTENT_LANG],
                        source_type: ['Official', 'Unofficial', 'Mixed']
                    });
                    const content = data.content || [];
                    const exact = content.find(item => item.slug === slug || item.series_id === slug);
                    if (exact && exact.series_id) return exact.series_id;
                    if (content.length > 0 && content[0].series_id) return content[0].series_id;
                } catch (e) {
                    log('Series search failed: ' + e.message);
                }
                return null;
            }

            async function resolveLatestBookId(seriesId) {
                const data = await getJson(API_PATH + '/series/' + encodeURIComponent(seriesId) + '/books/index?fields=List');
                const fields = data.fields || [];
                const books = data.books || [];
                if (!books.length) return null;
                const bookIdIdx = fields.indexOf('book_id');
                const sortIdx = fields.indexOf('sort_no');
                let row = books[books.length - 1];
                if (sortIdx !== -1) {
                    row = books.reduce((best, candidate) =>
                        Number(candidate[sortIdx]) > Number(best[sortIdx]) ? candidate : best, books[0]);
                }
                const bookId = bookIdIdx !== -1 ? row[bookIdIdx] : row[0];
                return bookId ? lastId(String(bookId)) : null;
            }

            async function getChapterId() {
                const segments = location.pathname.split('/').filter(Boolean);
                if (!segments.length) return null;

                const readerIdx = segments.indexOf('reader');
                if (readerIdx !== -1 && readerIdx + 1 < segments.length) {
                    return lastId(segments[readerIdx + 1]);
                }

                const seriesIdx = segments.indexOf('series');
                if (seriesIdx !== -1 && seriesIdx + 1 < segments.length) {
                    let seriesId = lastId(segments[seriesIdx + 1]);
                    if (!UUID_REGEX.test(seriesId)) {
                        const resolved = await resolveSeriesId(segments[seriesIdx + 1]);
                        if (resolved) seriesId = resolved;
                    }
                    if (UUID_REGEX.test(seriesId)) {
                        log('Resolving latest book for series ' + seriesId + '...');
                        const bookId = await resolveLatestBookId(seriesId);
                        if (bookId) return bookId;
                    }
                }

                for (let i = segments.length - 1; i >= 0; i--) {
                    const match = lastId(segments[i]).match(UUID_REGEX);
                    if (match) return match[0];
                }
                return null;
            }

            function cleanTitle(raw) {
                return (raw || '')
                    .replace(/-\s*Kagane.*$/i, '')
                    .replace(/\|\s*Kagane.*$/i, '')
                    .trim();
            }

            window.runScraper = async function() {
                log('Starting Kagane Scraper (Keiyoushi Sync)...');
                try {
                    const chapterId = await getChapterId();
                    if (!chapterId) {
                        throw new Error('Could not resolve a valid chapter ID from ' + location.pathname);
                    }

                    log('Fetching integrity token...');
                    const integrity = await postJson(INTEGRITY_PATH, null, null);
                    const integrityToken = integrity.token;
                    if (!integrityToken) throw new Error('No integrity token received');

                    log('Requesting chapter pages for ID: ' + chapterId);
                    const challengePath = API_PATH + '/books/' + encodeURIComponent(chapterId) +
                        '?intent=' + encodeURIComponent(INTENT);
                    const headers = {};
                    headers[INTEGRITY_HEADER] = integrityToken;
                    const challenge = await postJson(challengePath, null, headers);

                    const accessToken = challenge.access_token;
                    const cacheUrl = challenge.cache_url;
                    const pages = (challenge.manifest && challenge.manifest.pages) ? challenge.manifest.pages : [];
                    if (!accessToken || !cacheUrl) throw new Error('Reader API did not return a page token');
                    if (!pages.length) throw new Error('No pages found in manifest');

                    const base = String(cacheUrl).replace(/\/+$/, '');
                    const images = pages.map(page => {
                        const ext = page.ext || DEFAULT_EXT;
                        return base + PAGE_PATH + '/' + ORIGINAL_SEGMENT + '/' +
                            encodeURIComponent(chapterId) + '/' +
                            encodeURIComponent(page.page_id) + '.' + ext +
                            '?token=' + encodeURIComponent(accessToken);
                    });

                    if (window.Android && window.Android.updateProgress) {
                        window.Android.updateProgress(images.length, images.length, true);
                    }

                    const title = cleanTitle(document.title) || 'Kagane Chapter';
                    log('Found ' + images.length + ' images for ' + title);

                    const result = {
                        title: title,
                        images: images,
                        cookie: document.cookie,
                        baseUrl: location.origin
                    };
                    if (window.Android && window.Android.onImagesFound) {
                        window.Android.onImagesFound(JSON.stringify(result));
                    } else {
                        log('Result: ' + JSON.stringify(result));
                    }
                } catch (e) {
                    log('Kagane Scraper Error: ' + e.message);
                }
            };
        })();
        """.trimIndent()
    }
}
