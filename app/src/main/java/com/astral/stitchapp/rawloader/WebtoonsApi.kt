package com.astral.stitchapp.rawloader

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.regex.Pattern

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
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

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
        return "https://www.webtoons.com/$lang/search?keyword=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }

    fun fetchSeriesInfo(inputUrlOrTitleNo: String, lang: String = "id"): WebtoonsSeriesInfo {
        var info = fetchSeriesInfoForUrl(buildSeriesUrl(inputUrlOrTitleNo, lang))
        if (info.chapters.isEmpty() && lang != "en" && inputUrlOrTitleNo.startsWith("http").not()) {
            info = fetchSeriesInfoForUrl(buildSeriesUrl(inputUrlOrTitleNo, "en"))
        }
        return info
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
                    setRequestProperty("User-Agent", USER_AGENT)
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
        try {
            val conn = (URL(episodeUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 20000
                setRequestProperty("User-Agent", USER_AGENT)
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

    suspend fun simulateAdView(onProgressUpdate: (Double) -> Unit = {}) {
        val totalSteps = 5
        for (step in 1..totalSteps) {
            delay(600)
            onProgressUpdate(step.toDouble() / totalSteps)
        }
    }
}
