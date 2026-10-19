package com.astral.stitchapp.rawloader

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class WebtoonsSession(
    val isLoggedIn: Boolean = false,
    val email: String = "",
    val username: String = "",
    val coins: Int = 0,
    val token: String = ""
)

data class WebtoonsChapter(
    val number: Int,
    val title: String,
    val url: String,
    val isAdRequired: Boolean = false
)

data class WebtoonsSeriesInfo(
    val title: String,
    val chapters: List<WebtoonsChapter>
)

object WebtoonsApi {
    private const val PREFS_NAME = "webtoons_session"
    private const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    private const val USER_AGENT_APP = "nApps (Android 14; SM-A236B; linewebtoon; 3.3.2)"
    private const val DEVICE_KEY = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val API_KEY = "gUtPzJFZch4ZyAGviiyH94P99lQ3pFdRTwpJWDlSGFfwgpr6ses5ALOxWHOIT7R1".toByteArray(Charsets.UTF_8)

    fun loadSession(context: Context): WebtoonsSession {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isLoggedIn = prefs.getBoolean("is_logged_in", false)
        if (!isLoggedIn) return WebtoonsSession(isLoggedIn = false)
        return WebtoonsSession(
            isLoggedIn = true,
            email = prefs.getString("email", "") ?: "",
            username = prefs.getString("username", "") ?: "",
            coins = prefs.getInt("coins", 0),
            token = prefs.getString("token", "") ?: ""
        )
    }

    fun saveSession(context: Context, session: WebtoonsSession) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("is_logged_in", session.isLoggedIn)
            putString("email", session.email)
            putString("username", session.username)
            putInt("coins", session.coins)
            putString("token", session.token)
            apply()
        }
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    suspend fun login(emailInput: String, passwordInput: String): Result<WebtoonsSession> {
        val email = emailInput.trim()
        val password = passwordInput.trim()

        if (email.isBlank() || password.isBlank()) {
            return Result.failure(IllegalArgumentException("Email dan password tidak boleh kosong"))
        }

        if (!email.contains("@")) {
            return Result.failure(IllegalArgumentException("Format email tidak valid"))
        }

        delay(800) // Simulate network call

        val rawUser = email.substringBefore("@")
        val username = if (rawUser.isNotEmpty()) {
            rawUser.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        } else {
            "WebtoonsUser"
        }

        val coins = 50 // Default balance
        val token = "wbtn_token_" + System.currentTimeMillis()

        val session = WebtoonsSession(
            isLoggedIn = true,
            email = email,
            username = username,
            coins = coins,
            token = token
        )
        return Result.success(session)
    }

    private fun decodeHtmlEntities(input: String): String {
        return input.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&#x27;", "'")
            .replace("&#x2F;", "/")
            .replace("&#8217;", "’")
            .replace("&#8216;", "‘")
            .replace("&#8220;", "“")
            .replace("&#8221;", "”")
            .trim()
    }

    private fun getCurrentTime(): String {
        return try {
            val conn = (URL("https://global.apis.naver.com/currentTime").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", USER_AGENT_APP)
            }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }.trim()
            } else {
                System.currentTimeMillis().toString()
            }
        } catch (e: Exception) {
            System.currentTimeMillis().toString()
        }
    }

    private fun generateSignature(url: String, currentTime: String): String {
        val urlBytes = url.toByteArray(Charsets.UTF_8)
        val timeBytes = currentTime.toByteArray(Charsets.UTF_8)

        val prefixLength = if (urlBytes.size > 255) 255 else urlBytes.size
        val dataToSign = ByteArray(prefixLength + timeBytes.size)

        System.arraycopy(urlBytes, 0, dataToSign, 0, prefixLength)
        System.arraycopy(timeBytes, 0, dataToSign, prefixLength, timeBytes.size)

        val sha1Hmac = Mac.getInstance("HmacSHA1")
        val secretKey = SecretKeySpec(API_KEY, "HmacSHA1")
        sha1Hmac.init(secretKey)

        val macData = sha1Hmac.doFinal(dataToSign)
        return Base64.getEncoder().encodeToString(macData).trim()
    }

    private fun buildSignedUrl(unsignedUrl: String, currentTime: String): String {
        val signature = generateSignature(unsignedUrl, currentTime)
        val encodedSig = URLEncoder.encode(signature, "UTF-8")
        val separator = if (unsignedUrl.contains("?")) "&" else "?"
        return "$unsignedUrl${separator}msgpad=$currentTime&md=$encodedSig"
    }

    private fun sendApiRequest(unsignedUrl: String): String? {
        val currTime = getCurrentTime()
        val signedUrl = buildSignedUrl(unsignedUrl, currTime)
        return try {
            val conn = (URL(signedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", USER_AGENT_APP)
                setRequestProperty("wtu", DEVICE_KEY)
            }
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun extractTitleNoAndLang(input: String): Pair<Int?, String> {
        val trimmed = input.trim()
        var lang = "id"
        if (trimmed.contains("/en/", ignoreCase = true)) {
            lang = "en"
        } else if (trimmed.contains("/id/", ignoreCase = true)) {
            lang = "id"
        }

        val pattern = Pattern.compile("title_no=(\\d+)", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(trimmed)
        if (matcher.find()) {
            val titleNo = matcher.group(1)?.toIntOrNull()
            if (titleNo != null) return Pair(titleNo, lang)
        }

        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.isNotEmpty()) {
            val titleNo = digitsOnly.toIntOrNull()
            if (titleNo != null) return Pair(titleNo, lang)
        }

        return Pair(null, lang)
    }

    fun buildSeriesUrl(input: String, lang: String = "en"): String {
        var trimmed = input.trim()
        if (trimmed.contains("m.webtoons.com")) {
            trimmed = trimmed.replace("m.webtoons.com", "www.webtoons.com")
        }

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }

        val titleNo = trimmed.filter { it.isDigit() }
        if (titleNo.isNotEmpty()) {
            return "https://www.webtoons.com/$lang/a/b/list?title_no=$titleNo"
        }
        return "https://www.webtoons.com/$lang/search?keyword=" + URLEncoder.encode(trimmed, "UTF-8")
    }

    fun fetchSeriesInfo(inputUrlOrTitleNo: String, lang: String = "id"): WebtoonsSeriesInfo {
        val (titleNo, detectedLang) = extractTitleNoAndLang(inputUrlOrTitleNo)
        var effectiveLang = if (inputUrlOrTitleNo.startsWith("http")) detectedLang else lang

        if (titleNo != null) {
            val apiInfo = fetchSeriesInfoFromApi(titleNo, effectiveLang)
            if (apiInfo != null && apiInfo.chapters.isNotEmpty()) {
                return apiInfo
            }
            if (effectiveLang != "en") {
                val apiInfoEn = fetchSeriesInfoFromApi(titleNo, "en")
                if (apiInfoEn != null && apiInfoEn.chapters.isNotEmpty()) {
                    return apiInfoEn
                }
            }
        }

        // Fallback to HTML scraping
        var info = fetchSeriesInfoForUrl(buildSeriesUrl(inputUrlOrTitleNo, effectiveLang))
        if (info.chapters.isEmpty() && effectiveLang != "en" && !inputUrlOrTitleNo.startsWith("http")) {
            info = fetchSeriesInfoForUrl(buildSeriesUrl(inputUrlOrTitleNo, "en"))
        }
        return info
    }

    private fun fetchSeriesInfoFromApi(titleNo: Int, lang: String): WebtoonsSeriesInfo? {
        try {
            val infoUrl = "https://global.apis.naver.com/lineWebtoon/webtoon/titleInfo?titleNo=$titleNo&v=1&serviceZone=GLOBAL&language=$lang&locale=$lang&platform=APP_ANDROID"
            val infoJsonStr = sendApiRequest(infoUrl) ?: return null
            val infoObj = JSONObject(infoJsonStr)
            val titleInfo = infoObj.optJSONObject("message")?.optJSONObject("result")?.optJSONObject("titleInfo") ?: return null

            val seriesTitle = titleInfo.optString("title").ifBlank { titleInfo.optString("titleName").ifBlank { "Webtoons Series $titleNo" } }
            val totalEpisodes = titleInfo.optInt("totalEpisodeCount", 1000)

            val pageSize = maxOf(totalEpisodes, 500)
            val listUrl = "https://global.apis.naver.com/lineWebtoon/webtoon/episodeList?titleNo=$titleNo&startIndex=0&pageSize=$pageSize&v=6&serviceZone=GLOBAL&language=$lang&locale=$lang&platform=APP_ANDROID"
            val listJsonStr = sendApiRequest(listUrl) ?: return null
            val listObj = JSONObject(listJsonStr)
            val epArray = listObj.optJSONObject("message")?.optJSONObject("result")?.optJSONObject("episodeList")?.optJSONArray("episode") ?: return null

            val episodes = mutableListOf<WebtoonsChapter>()
            for (i in 0 until epArray.length()) {
                val epObj = epArray.optJSONObject(i) ?: continue
                val epNum = epObj.optInt("episodeNo", -1)
                if (epNum <= 0) continue

                val epSubj = epObj.optString("episodeTitle").ifBlank { "Episode $epNum" }
                val hasProdInfo = epObj.has("productInfo") && !epObj.isNull("productInfo") && epObj.opt("productInfo") != false
                val isDailyPass = epObj.optBoolean("dailyPassEpisode", false)
                val isRewardAd = epObj.optBoolean("rewardAdEpisode", false)
                val isPassRestrict = epObj.optBoolean("passUseRestrictEpisode", false)
                val allowsAd = epObj.optBoolean("allowsAd", false)
                val price = epObj.optInt("price", 0)

                val isPaid = hasProdInfo || isDailyPass || isRewardAd || isPassRestrict || allowsAd || price > 0

                val epUrl = "https://www.webtoons.com/$lang/a/b/viewer?title_no=$titleNo&episode_no=$epNum"
                episodes.add(
                    WebtoonsChapter(
                        number = epNum,
                        title = epSubj,
                        url = epUrl,
                        isAdRequired = isPaid
                    )
                )
            }

            if (episodes.isNotEmpty()) {
                episodes.sortBy { it.number }
                return WebtoonsSeriesInfo(title = seriesTitle, chapters = episodes)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun fetchSeriesInfoForUrl(targetUrl: String): WebtoonsSeriesInfo {
        var currentBaseUrl = targetUrl
        val episodes = mutableListOf<WebtoonsChapter>()
        val scrapedNumbers = mutableSetOf<Int>()
        var page = 1
        var seriesTitle = "Webtoons Series"

        while (page <= 15) {
            val cleanBase = currentBaseUrl.split("&page=")[0]
            val pageUrl = if (cleanBase.contains("?")) {
                "$cleanBase&page=$page"
            } else {
                "$cleanBase?page=$page"
            }

            try {
                val conn = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT_WEB)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
                }

                if (conn.responseCode != 200 && conn.responseCode != 301 && conn.responseCode != 302) break
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                if (html.isBlank()) break

                val redirectedUrl = conn.url.toString()
                if (redirectedUrl.isNotBlank() && redirectedUrl != pageUrl) {
                    currentBaseUrl = redirectedUrl
                }

                // Extract title
                if (seriesTitle == "Webtoons Series") {
                    val titleMatcher = Pattern.compile("<h1[^>]*class=\"[^\"]*subj[^\"]*\"[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
                    if (titleMatcher.find()) {
                        seriesTitle = decodeHtmlEntities(titleMatcher.group(1)?.replace(Regex("<[^>]*>"), "") ?: seriesTitle)
                    } else {
                        val ogMatcher = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (ogMatcher.find()) {
                            seriesTitle = decodeHtmlEntities(ogMatcher.group(1) ?: seriesTitle)
                        }
                    }
                }

                var newCount = 0

                val episodeItemPattern = Pattern.compile("<li[^>]*data-episode-no=\"(\\d+)\"[^>]*>(.*?)</li>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
                val matcher = episodeItemPattern.matcher(html)

                while (matcher.find()) {
                    val epNumStr = matcher.group(1) ?: continue
                    val epNum = epNumStr.toIntOrNull() ?: continue
                    val itemHtml = matcher.group(2) ?: ""

                    if (epNum in scrapedNumbers) continue
                    scrapedNumbers.add(epNum)
                    newCount++

                    val linkMatcher = Pattern.compile("<a[^>]*href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(itemHtml)
                    val epUrl = if (linkMatcher.find()) linkMatcher.group(1) ?: "" else ""

                    val subjMatcher = Pattern.compile("<span[^>]*class=\"[^\"]*subj[^\"]*\"[^>]*>(.*?)</span>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(itemHtml)
                    var epSubj = if (subjMatcher.find()) subjMatcher.group(1)?.replace(Regex("<[^>]*>"), "")?.trim() ?: "Episode $epNum" else "Episode $epNum"
                    epSubj = decodeHtmlEntities(epSubj)

                    val isAd = itemHtml.contains("ico_fast") ||
                            itemHtml.contains("ico_lock") ||
                            itemHtml.contains("ico_ad") ||
                            itemHtml.contains("tx_fastpass") ||
                            itemHtml.contains("ad_watch") ||
                            itemHtml.contains("fast_pass")

                    episodes.add(
                        WebtoonsChapter(
                            number = epNum,
                            title = epSubj,
                            url = if (epUrl.startsWith("http")) epUrl else "https://www.webtoons.com" + decodeHtmlEntities(epUrl),
                            isAdRequired = isAd
                        )
                    )
                }

                // Fallback parsing if data-episode-no LI tags were not present
                if (newCount == 0) {
                    val fallbackLinkPattern = Pattern.compile("<a[^>]*href=\"([^\"]*episode_no=(\\d+)[^\"]*)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
                    val fbMatcher = fallbackLinkPattern.matcher(html)
                    while (fbMatcher.find()) {
                        val epUrlRaw = fbMatcher.group(1) ?: continue
                        val epNum = fbMatcher.group(2)?.toIntOrNull() ?: continue
                        val innerHtml = fbMatcher.group(3) ?: ""

                        if (epNum in scrapedNumbers) continue
                        scrapedNumbers.add(epNum)
                        newCount++

                        val epUrl = decodeHtmlEntities(epUrlRaw)
                        val epSubj = decodeHtmlEntities(innerHtml.replace(Regex("<[^>]*>"), "").trim().ifBlank { "Episode $epNum" })
                        val isAd = innerHtml.contains("ico_fast") || innerHtml.contains("ico_lock") || innerHtml.contains("ico_ad")

                        episodes.add(
                            WebtoonsChapter(
                                number = epNum,
                                title = epSubj,
                                url = if (epUrl.startsWith("http")) epUrl else "https://www.webtoons.com$epUrl",
                                isAdRequired = isAd
                            )
                        )
                    }
                }

                if (newCount == 0) break
                page++
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }

        episodes.sortBy { it.number }
        return WebtoonsSeriesInfo(title = seriesTitle, chapters = episodes)
    }

    fun scrapeChapterImages(episodeUrl: String): List<String> {
        val images = mutableListOf<String>()

        // Try API extraction first
        val titleNoPattern = Pattern.compile("title_no=(\\d+)", Pattern.CASE_INSENSITIVE)
        val epNoPattern = Pattern.compile("episode_no=(\\d+)", Pattern.CASE_INSENSITIVE)
        val tMatcher = titleNoPattern.matcher(episodeUrl)
        val eMatcher = epNoPattern.matcher(episodeUrl)

        if (tMatcher.find() && eMatcher.find()) {
            val titleNo = tMatcher.group(1)?.toIntOrNull()
            val episodeNo = eMatcher.group(1)?.toIntOrNull()
            if (titleNo != null && episodeNo != null) {
                val lang = if (episodeUrl.contains("/id/", ignoreCase = true)) "id" else "en"
                val apiImages = scrapeChapterImagesFromApi(titleNo, episodeNo, lang)
                if (apiImages.isNotEmpty()) {
                    return apiImages
                }
            }
        }

        // Fallback to HTML scraping
        try {
            val conn = (URL(episodeUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 20000
                setRequestProperty("User-Agent", USER_AGENT_WEB)
                setRequestProperty("Referer", "https://www.webtoons.com/")
            }

            if (conn.responseCode != 200) return images
            val html = conn.inputStream.bufferedReader().use { it.readText() }

            // Extract images from _imageList
            val imgListMatcher = Pattern.compile("<div[^>]*id=\"_imageList\"[^>]*>(.*?)</div>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
            val containerHtml = if (imgListMatcher.find()) imgListMatcher.group(1) ?: html else html

            val imgTagPattern = Pattern.compile("<img[^>]*data-url=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE)
            val matcher = imgTagPattern.matcher(containerHtml)

            while (matcher.find()) {
                val imgUrl = matcher.group(1) ?: continue
                if (imgUrl.isNotBlank() && !images.contains(imgUrl)) {
                    images.add(imgUrl)
                }
            }

            // Fallback if data-url attribute isn't found
            if (images.isEmpty()) {
                val fallbackPattern = Pattern.compile("(https?://webtoon-phinf\\.pstatic\\.net/[^\"'\\s]+\\.(?:jpg|png|jpeg|webp))", Pattern.CASE_INSENSITIVE)
                val fbMatcher = fallbackPattern.matcher(containerHtml)
                while (fbMatcher.find()) {
                    val imgUrl = fbMatcher.group(1) ?: continue
                    if (!imgUrl.contains("thumbnail") && !imgUrl.contains("banner") && !images.contains(imgUrl)) {
                        images.add(imgUrl)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return images
    }

    private fun scrapeChapterImagesFromApi(titleNo: Int, episodeNo: Int, lang: String): List<String> {
        val images = mutableListOf<String>()
        try {
            val apiUrl = "https://global.apis.naver.com/lineWebtoon/webtoon/episodeInfoWithLogin?titleNo=$titleNo&episodeNo=$episodeNo&v=4&serviceZone=GLOBAL&language=$lang&locale=$lang&platform=APP_ANDROID"
            val jsonStr = sendApiRequest(apiUrl) ?: return images
            val jsonObj = JSONObject(jsonStr)
            val imgArray = jsonObj.optJSONObject("message")?.optJSONObject("result")?.optJSONObject("episodeInfo")?.optJSONArray("imageInfo") ?: return images

            for (i in 0 until imgArray.length()) {
                val imgObj = imgArray.optJSONObject(i) ?: continue
                val rawUrl = imgObj.optString("url")
                if (rawUrl.isBlank() || rawUrl.contains("warning", ignoreCase = true)) continue

                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "https://webtoon-phinf.pstatic.net$rawUrl"
                if (!images.contains(fullUrl)) {
                    images.add(fullUrl)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return images
    }

    suspend fun simulateAdView(onProgressUpdate: (Double) -> Unit = {}) {
        val totalSteps = 5
        for (step in 1..totalSteps) {
            delay(600)
            onProgressUpdate(step.toDouble() / totalSteps)
        }
    }
}
