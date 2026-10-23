package com.astral.stitchapp.rawloader

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Locale
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class WebtoonsSession(
    val isLoggedIn: Boolean = false,
    val email: String = "",
    val username: String = "",
    val coins: Int = 0,
    val token: String = "",
    val neoSes: String = "",
    val neoChk: String = "",
    val deviceKey: String = ""
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
            token = prefs.getString("token", "") ?: "",
            neoSes = prefs.getString("neo_ses", "") ?: "",
            neoChk = prefs.getString("neo_chk", "") ?: "",
            deviceKey = prefs.getString("device_key", "") ?: ""
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
            putString("neo_ses", session.neoSes)
            putString("neo_chk", session.neoChk)
            putString("device_key", session.deviceKey)
            apply()
        }
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    private fun generateRandomDeviceKey(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32).map { chars.random() }.joinToString("")
    }

    private fun getOrCreateDeviceKey(context: Context?): String {
        if (context == null) return generateRandomDeviceKey()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var key = prefs.getString("device_key", "") ?: ""
        if (key.isBlank()) {
            key = generateRandomDeviceKey()
            prefs.edit().putString("device_key", key).apply()
        }
        return key
    }

    private fun encryptCredentials(
        sessionKey: String,
        email: String,
        password: String,
        nHex: String,
        eHex: String
    ): String {
        val message = "${sessionKey.length.toChar()}$sessionKey${email.length.toChar()}$email${password.length.toChar()}$password"
        val modulus = BigInteger(nHex, 16)
        val exponent = BigInteger(eHex, 16)

        val keySpec = RSAPublicKeySpec(modulus, exponent)
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicKey = keyFactory.generatePublic(keySpec)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)

        val encryptedBytes = cipher.doFinal(message.toByteArray(Charsets.UTF_8))
        return encryptedBytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun login(context: Context, emailInput: String, passwordInput: String): Result<WebtoonsSession> {
        return login(emailInput, passwordInput, context)
    }

    suspend fun login(emailInput: String, passwordInput: String, context: Context? = null): Result<WebtoonsSession> {
        val email = emailInput.trim()
        val password = passwordInput.trim()

        if (email.isBlank() || password.isBlank()) {
            return Result.failure(IllegalArgumentException("Email dan password tidak boleh kosong"))
        }

        if (!email.contains("@")) {
            return Result.failure(IllegalArgumentException("Format email tidak valid"))
        }

        val deviceKey = getOrCreateDeviceKey(context)

        return try {
            val rsaUrl = "https://global.apis.naver.com/lineWebtoon/webtoon/getRsaKey?v=1&serviceZone=GLOBAL&language=en&locale=en&platform=APP_ANDROID"
            val rsaRes = sendApiRequest(rsaUrl, deviceKey = deviceKey)
                ?: return Result.failure(Exception("Gagal mengambil RSA Key dari server"))

            val rsaResultObj = JSONObject(rsaRes).optJSONObject("message")?.optJSONObject("result")
                ?: return Result.failure(Exception("Response RSA Key tidak valid"))

            val keyName = rsaResultObj.optString("keyName")
            val nvalue = rsaResultObj.optString("nvalue")
            val evalue = rsaResultObj.optString("evalue")
            val sessionKey = rsaResultObj.optString("sessionKey")

            val encryptedPw = encryptCredentials(sessionKey, email, password, nvalue, evalue)

            val loginUrl = "https://global.apis.naver.com/lineWebtoon/webtoon/loginById?method=POST&serviceZone=GLOBAL&encpw=$encryptedPw&loginType=EMAIL&v=3&language=en&encnm=$keyName&locale=en&platform=APP_ANDROID"
            val loginRes = sendApiRequest(loginUrl, method = "POST", deviceKey = deviceKey)
                ?: return Result.failure(Exception("Gagal menghubungi server login"))

            val loginMsg = JSONObject(loginRes).optJSONObject("message")
            val loginResultObj = loginMsg?.optJSONObject("result")
            val ses = loginResultObj?.optString("ses") ?: ""
            if (ses.isEmpty()) {
                val errMsg = loginMsg?.optString("message") ?: "Email atau password salah"
                return Result.failure(Exception(errMsg))
            }

            var chk = ""
            val devInfoUrl = "https://global.apis.naver.com/lineWebtoon/webtoon/setDeviceInfo?deviceKey=$deviceKey&appType=LINEWEBTOON&pushToken=&pushCode=FCMV1&serviceZone=GLOBAL&v=1&language=en&locale=en&platform=APP_ANDROID"

            val currTime = getCurrentTime()
            val signedUrl = buildSignedUrl(devInfoUrl, currTime)

            val conn = (URL(signedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", USER_AGENT_APP)
                setRequestProperty("wtu", deviceKey)
                setRequestProperty("Cookie", "NEO_SES=\"$ses\"")
            }

            val headerFields = conn.headerFields
            val setCookies = headerFields["Set-Cookie"] ?: headerFields["set-cookie"]
            if (setCookies != null) {
                for (c in setCookies) {
                    if (c.contains("NEO_CHK=")) {
                        val start = c.indexOf("NEO_CHK=\"")
                        if (start != -1) {
                            val sub = c.substring(start + 9)
                            val end = sub.indexOf("\"")
                            if (end != -1) chk = sub.substring(0, end)
                        }
                    }
                }
            }

            var session = WebtoonsSession(
                isLoggedIn = true,
                email = email,
                username = email.substringBefore("@"),
                coins = 0,
                token = ses,
                neoSes = ses,
                neoChk = chk,
                deviceKey = deviceKey
            )

            session = fetchUserInfo(session)
            if (context != null) {
                saveSession(context, session)
            }
            Result.success(session)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    fun fetchUserInfo(session: WebtoonsSession): WebtoonsSession {
        if (!session.isLoggedIn || session.neoSes.isBlank()) return session
        var username = session.username
        var coins = session.coins

        try {
            val memberUrl = "https://global.apis.naver.com/lineWebtoon/webtoon/getMemberInfo?v=1&serviceZone=GLOBAL&language=en&locale=en&platform=APP_ANDROID"
            val memberRes = sendApiRequest(memberUrl, session = session)
            if (memberRes != null) {
                val memberObj = JSONObject(memberRes).optJSONObject("message")?.optJSONObject("result")
                val nickname = memberObj?.optString("nickname") ?: ""
                if (nickname.isNotBlank()) {
                    username = nickname
                }
            }

            val coinUrl = "https://global.apis.naver.com/lineWebtoon/webtoon/coinBalance?v=1&serviceZone=GLOBAL&language=en&locale=en&platform=APP_ANDROID"
            val coinRes = sendApiRequest(coinUrl, session = session)
            if (coinRes != null) {
                val coinObj = JSONObject(coinRes).optJSONObject("message")?.optJSONObject("result")?.optJSONObject("balance")
                if (coinObj != null && coinObj.has("amount")) {
                    coins = coinObj.optInt("amount", 0)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return session.copy(username = username, coins = coins)
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

    private fun sendApiRequest(
        unsignedUrl: String,
        method: String = "GET",
        jsonBody: String? = null,
        session: WebtoonsSession? = null,
        deviceKey: String? = null
    ): String? {
        val currTime = getCurrentTime()
        val signedUrl = buildSignedUrl(unsignedUrl, currTime)
        val effectiveDeviceKey = session?.deviceKey?.ifBlank { null } ?: deviceKey ?: DEVICE_KEY
        return try {
            val conn = (URL(signedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", USER_AGENT_APP)
                setRequestProperty("wtu", effectiveDeviceKey)
                if (jsonBody == null && method.equals("GET", ignoreCase = true)) {
                    setRequestProperty("Content-Length", "0")
                }
                if (session != null && session.neoSes.isNotBlank() && session.neoChk.isNotBlank()) {
                    setRequestProperty("Cookie", "NEO_SES=\"${session.neoSes}\"; NEO_CHK=\"${session.neoChk}\"")
                }
                if (jsonBody != null || method.equals("POST", ignoreCase = true)) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true
                    outputStream.use { it.write((jsonBody ?: "").toByteArray(Charsets.UTF_8)) }
                }
            }
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getImageSecureToken(session: WebtoonsSession? = null) {
        val url = "https://global.apis.naver.com/lineWebtoon/webtoon/getImageSecureToken?v=1&serviceZone=GLOBAL&language=en&locale=en&platform=APP_ANDROID"
        sendApiRequest(url, "GET", session = session)
    }

    private fun buyProduct(
        productId: String,
        saleUnitId: String,
        price: Int,
        session: WebtoonsSession? = null
    ): Boolean {
        return try {
            val encodedProductId = URLEncoder.encode(productId, "UTF-8")
            val encodedSaleUnitId = URLEncoder.encode(saleUnitId, "UTF-8")
            val url = "https://global.apis.naver.com/lineWebtoon/webtoon/buyProduct?method=POST&productId=$encodedProductId&productSaleUnitId=$encodedSaleUnitId&price=$price&v=1&serviceZone=GLOBAL&language=en&locale=en&platform=APP_ANDROID"
            val body = JSONObject().apply {
                put("method", "POST")
                put("productId", productId)
                put("productSaleUnitId", saleUnitId)
                put("price", price)
            }.toString()
            val res = sendApiRequest(url, "POST", jsonBody = body, session = session)
            if (res != null) {
                val jsonObj = JSONObject(res)
                val errCode = jsonObj.optJSONObject("message")?.optString("error_code")
                errCode.isNullOrBlank()
            } else false
        } catch (e: Exception) {
            e.printStackTrace()
            false
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

    fun fetchSeriesInfo(inputUrlOrTitleNo: String, lang: String = "id", context: Context? = null): WebtoonsSeriesInfo {
        val (titleNo, detectedLang) = extractTitleNoAndLang(inputUrlOrTitleNo)
        var effectiveLang = if (inputUrlOrTitleNo.startsWith("http")) detectedLang else lang
        val session = if (context != null) loadSession(context) else null

        if (titleNo != null) {
            val apiInfo = fetchSeriesInfoFromApi(titleNo, effectiveLang, session)
            if (apiInfo != null && apiInfo.chapters.isNotEmpty()) {
                return apiInfo
            }
            if (effectiveLang != "en") {
                val apiInfoEn = fetchSeriesInfoFromApi(titleNo, "en", session)
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

    private fun fetchSeriesInfoFromApi(titleNo: Int, lang: String, session: WebtoonsSession? = null): WebtoonsSeriesInfo? {
        try {
            val infoUrl = "https://global.apis.naver.com/lineWebtoon/webtoon/titleInfo?titleNo=$titleNo&v=1&serviceZone=GLOBAL&language=$lang&locale=$lang&platform=APP_ANDROID"
            val infoJsonStr = sendApiRequest(infoUrl, session = session) ?: return null
            val infoObj = JSONObject(infoJsonStr)
            val titleInfo = infoObj.optJSONObject("message")?.optJSONObject("result")?.optJSONObject("titleInfo") ?: return null

            val seriesTitle = titleInfo.optString("title").ifBlank { titleInfo.optString("titleName").ifBlank { "Webtoons Series $titleNo" } }
            val totalEpisodes = titleInfo.optInt("totalEpisodeCount", 1000)

            val pageSize = maxOf(totalEpisodes, 500)
            val listUrl = "https://global.apis.naver.com/lineWebtoon/webtoon/episodeList?titleNo=$titleNo&startIndex=0&pageSize=$pageSize&v=6&serviceZone=GLOBAL&language=$lang&locale=$lang&platform=APP_ANDROID"
            val listJsonStr = sendApiRequest(listUrl, session = session) ?: return null
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

    fun scrapeChapterImages(episodeUrl: String): List<String> = scrapeChapterImages(null, episodeUrl)

    fun scrapeChapterImages(context: Context? = null, episodeUrl: String): List<String> {
        val session = if (context != null) loadSession(context) else null
        val images = mutableListOf<String>()

        val titleNoPattern = Pattern.compile("title_no=(\\d+)", Pattern.CASE_INSENSITIVE)
        val epNoPattern = Pattern.compile("episode_no=(\\d+)", Pattern.CASE_INSENSITIVE)
        val tMatcher = titleNoPattern.matcher(episodeUrl)
        val eMatcher = epNoPattern.matcher(episodeUrl)

        if (tMatcher.find() && eMatcher.find()) {
            val titleNo = tMatcher.group(1)?.toIntOrNull()
            val episodeNo = eMatcher.group(1)?.toIntOrNull()
            if (titleNo != null && episodeNo != null) {
                val lang = if (episodeUrl.contains("/id/", ignoreCase = true)) "id" else "en"
                val apiImages = scrapeChapterImagesFromApi(titleNo, episodeNo, lang, session)
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

    private fun scrapeChapterImagesFromApi(
        titleNo: Int,
        episodeNo: Int,
        lang: String,
        session: WebtoonsSession? = null
    ): List<String> {
        val images = mutableListOf<String>()
        try {
            val apiUrl = "https://global.apis.naver.com/lineWebtoon/webtoon/episodeInfoWithLogin?titleNo=$titleNo&episodeNo=$episodeNo&v=4&serviceZone=GLOBAL&language=$lang&locale=$lang&platform=APP_ANDROID"
            val jsonStr = sendApiRequest(apiUrl, session = session) ?: return images
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

    suspend fun simulateAdView(episodeUrl: String, onProgressUpdate: suspend (Double) -> Unit) {
        simulateAdView(null, episodeUrl, onProgressUpdate)
    }

    suspend fun simulateAdView(
        context: Context? = null,
        episodeUrl: String = "",
        onProgressUpdate: suspend (Double) -> Unit = {}
    ) {
        val session = if (context != null) loadSession(context) else null

        val totalSteps = 30
        for (step in 1..totalSteps) {
            delay(1000)
            onProgressUpdate(step.toDouble() / totalSteps)
        }

        val titleNoPattern = Pattern.compile("title_no=(\\d+)", Pattern.CASE_INSENSITIVE)
        val epNoPattern = Pattern.compile("episode_no=(\\d+)", Pattern.CASE_INSENSITIVE)
        val tMatcher = titleNoPattern.matcher(episodeUrl)
        val eMatcher = epNoPattern.matcher(episodeUrl)

        if (tMatcher.find() && eMatcher.find()) {
            val titleNo = tMatcher.group(1)?.toIntOrNull()
            val epNo = eMatcher.group(1)?.toIntOrNull()
            if (titleNo != null && epNo != null) {
                try {
                    getImageSecureToken(session)
                    val productId = "linewebtoon-WEBTOON-$titleNo-$epNo"
                    val adSaleUnitId = "$productId-reward_ad-1"
                    val adClaimed = buyProduct(productId, adSaleUnitId, 0, session)
                    if (!adClaimed) {
                        val passSaleUnitId = "$productId-complete_daily_pass-1"
                        buyProduct(productId, passSaleUnitId, 0, session)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
