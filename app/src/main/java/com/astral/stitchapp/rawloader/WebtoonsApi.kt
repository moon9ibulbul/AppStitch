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
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

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

        // Generate username from email prefix
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

    fun buildSeriesUrl(input: String, lang: String = "en"): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        val titleNo = trimmed.filter { it.isDigit() }
        if (titleNo.isNotEmpty()) {
            return "https://www.webtoons.com/$lang/series/detail/list?title_no=$titleNo"
        }
        return "https://www.webtoons.com/$lang/search?keyword=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }

    fun fetchSeriesInfo(inputUrlOrTitleNo: String, lang: String = "en"): WebtoonsSeriesInfo {
        val fullUrl = buildSeriesUrl(inputUrlOrTitleNo, lang)
        val episodes = mutableListOf<WebtoonsChapter>()
        val scrapedNumbers = mutableSetOf<Int>()
        var page = 1
        var seriesTitle = "Webtoons Series"

        val currentBaseUrl = fullUrl.split("&page=")[0]

        while (page <= 10) {
            val pageUrl = if (currentBaseUrl.contains("?")) {
                "$currentBaseUrl&page=$page"
            } else {
                "$currentBaseUrl?page=$page"
            }

            try {
                val conn = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept-Language", lang)
                }

                if (conn.responseCode != 200) break
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                if (html.isBlank()) break

                // Extract title
                if (seriesTitle == "Webtoons Series") {
                    val titleMatcher = Pattern.compile("<h1[^>]*class=\"[^\"]*subj[^\"]*\"[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
                    if (titleMatcher.find()) {
                        seriesTitle = titleMatcher.group(1)?.replace(Regex("<[^>]*>"), "")?.trim() ?: seriesTitle
                    } else {
                        val ogMatcher = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (ogMatcher.find()) {
                            seriesTitle = ogMatcher.group(1)?.trim() ?: seriesTitle
                        }
                    }
                }

                // Parse episodes
                val episodeItemPattern = Pattern.compile("<li[^>]*data-episode-no=\"(\\d+)\"[^>]*>(.*?)</li>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
                val matcher = episodeItemPattern.matcher(html)
                var newCount = 0

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
                    val epSubj = if (subjMatcher.find()) subjMatcher.group(1)?.replace(Regex("<[^>]*>"), "")?.trim() ?: "Episode $epNum" else "Episode $epNum"

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
                            url = if (epUrl.startsWith("http")) epUrl else "https://www.webtoons.com$epUrl",
                            isAdRequired = isAd
                        )
                    )
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
