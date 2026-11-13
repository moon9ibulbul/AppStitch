package com.astral.stitchapp.rawloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class BilibiliSession(
    val isLoggedIn: Boolean = false,
    val username: String = "",
    val mid: Long = 0,
    val sessData: String = "",
    val biliJct: String = "",
    val dedeUserId: String = "",
    val dedeUserIdCkMd5: String = "",
    val sid: String = "",
    val buvid3: String = "",
    val buvid4: String = "",
    val gold: Int = 0,
    val coupon: Int = 0
) {
    fun toCookieHeader(): String {
        val parts = mutableListOf<String>()
        if (sessData.isNotBlank()) parts.add("SESSDATA=$sessData")
        if (biliJct.isNotBlank()) parts.add("bili_jct=$biliJct")
        if (dedeUserId.isNotBlank()) parts.add("DedeUserID=$dedeUserId")
        if (dedeUserIdCkMd5.isNotBlank()) parts.add("DedeUserID__ckMd5=$dedeUserIdCkMd5")
        if (sid.isNotBlank()) parts.add("sid=$sid")
        if (buvid3.isNotBlank()) parts.add("buvid3=$buvid3")
        if (buvid4.isNotBlank()) parts.add("buvid4=$buvid4")
        return parts.joinToString("; ")
    }
}

data class BilibiliChapter(
    val id: Long,
    val comicId: Long,
    val number: Float,
    val title: String,
    val shortTitle: String = "",
    val isLocked: Boolean = false,
    val isInFree: Boolean = false,
    val payGold: Int = 0,
    val imageCount: Int = 0
)

data class BilibiliSeriesInfo(
    val comicId: Long,
    val title: String,
    val cover: String = "",
    val chapters: List<BilibiliChapter>
)

data class BilibiliQrData(
    val url: String,
    val qrcodeKey: String
)

sealed class BilibiliQrPollStatus {
    data class Success(val session: BilibiliSession) : BilibiliQrPollStatus()
    object WaitingScan : BilibiliQrPollStatus()
    object ScannedWaitingConfirmation : BilibiliQrPollStatus()
    object Expired : BilibiliQrPollStatus()
    data class Error(val message: String) : BilibiliQrPollStatus()
}

object BilibiliApi {
    private const val PREFS_NAME = "bilibili_session"
    private const val MANGA_HOST = "https://manga.bilibili.com"
    private const val PASSPORT_HOST = "https://passport.bilibili.com"
    private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
    private const val FINGER_URL = "https://api.bilibili.com/x/frontend/finger/spi"

    private const val USER_AGENT = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"
    )

    private const val BILI_APP_UA = (
        "Mozilla/5.0 (Linux; Android 12; SM-G9730) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    )

    private val APP_QUERY = mapOf(
        "appkey" to "cc8617fd6961e070",
        "mobi_app" to "android_comic",
        "version" to "4.13.0",
        "build" to "36413002",
        "channel" to "bilicomic",
        "platform" to "android",
        "device" to "android"
    )

    // ==================== SESSION MANAGEMENT ====================

    fun loadSession(context: Context): BilibiliSession {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isLoggedIn = prefs.getBoolean("is_logged_in", false)
        if (!isLoggedIn) return BilibiliSession(isLoggedIn = false)
        return BilibiliSession(
            isLoggedIn = true,
            username = prefs.getString("username", "") ?: "",
            mid = prefs.getLong("mid", 0L),
            sessData = prefs.getString("sess_data", "") ?: "",
            biliJct = prefs.getString("bili_jct", "") ?: "",
            dedeUserId = prefs.getString("dede_user_id", "") ?: "",
            dedeUserIdCkMd5 = prefs.getString("dede_user_id_ck_md5", "") ?: "",
            sid = prefs.getString("sid", "") ?: "",
            buvid3 = prefs.getString("buvid3", "") ?: "",
            buvid4 = prefs.getString("buvid4", "") ?: "",
            gold = prefs.getInt("gold", 0),
            coupon = prefs.getInt("coupon", 0)
        )
    }

    fun saveSession(context: Context, session: BilibiliSession) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("is_logged_in", session.isLoggedIn)
            putString("username", session.username)
            putLong("mid", session.mid)
            putString("sess_data", session.sessData)
            putString("bili_jct", session.biliJct)
            putString("dede_user_id", session.dedeUserId)
            putString("dede_user_id_ck_md5", session.dedeUserIdCkMd5)
            putString("sid", session.sid)
            putString("buvid3", session.buvid3)
            putString("buvid4", session.buvid4)
            putInt("gold", session.gold)
            putInt("coupon", session.coupon)
            apply()
        }
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    fun parseCookieString(cookie: String): BilibiliSession {
        var sessData = ""
        var biliJct = ""
        var dedeUserId = ""
        var dedeUserIdCkMd5 = ""
        var sid = ""
        var buvid3 = ""
        var buvid4 = ""

        cookie.split(";").forEach { item ->
            val part = item.trim()
            if (part.contains("=")) {
                val key = part.substringBefore("=").trim()
                val value = part.substringAfter("=").trim()
                when (key) {
                    "SESSDATA" -> sessData = value
                    "bili_jct" -> biliJct = value
                    "DedeUserID" -> dedeUserId = value
                    "DedeUserID__ckMd5" -> dedeUserIdCkMd5 = value
                    "sid" -> sid = value
                    "buvid3" -> buvid3 = value
                    "buvid4" -> buvid4 = value
                }
            }
        }

        val isLoggedIn = sessData.isNotBlank()
        val mid = dedeUserId.toLongOrNull() ?: 0L
        return BilibiliSession(
            isLoggedIn = isLoggedIn,
            username = if (isLoggedIn) "User $mid" else "",
            mid = mid,
            sessData = sessData,
            biliJct = biliJct,
            dedeUserId = dedeUserId,
            dedeUserIdCkMd5 = dedeUserIdCkMd5,
            sid = sid,
            buvid3 = buvid3,
            buvid4 = buvid4
        )
    }

    // ==================== FINGERPRINT & BUVID ====================

    fun obtainBuvid(session: BilibiliSession? = null): Pair<String, String> {
        return try {
            val url = URL(FINGER_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", BILI_APP_UA)
                setRequestProperty("Referer", "$MANGA_HOST/")
            }
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val data = json.optJSONObject("data") ?: JSONObject()
            val b3 = data.optString("b_3", "")
            val b4 = data.optString("b_4", "")
            Pair(b3, b4)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(session?.buvid3 ?: "", session?.buvid4 ?: "")
        }
    }

    // ==================== QR LOGIN ====================

    suspend fun generateQrCode(): Result<BilibiliQrData> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$PASSPORT_HOST/x/passport-login/web/qrcode/generate"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://www.bilibili.com/")
            }
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            if (json.optInt("code", -1) != 0) {
                return@withContext Result.failure(Exception("Gagal membuat QR: ${json.optString("message")}"))
            }
            val data = json.optJSONObject("data") ?: throw Exception("Data QR kosong")
            val qrUrl = data.optString("url")
            val qrKey = data.optString("qrcode_key")
            if (qrUrl.isBlank() || qrKey.isBlank()) {
                throw Exception("Respon QR tidak lengkap")
            }
            Result.success(BilibiliQrData(url = qrUrl, qrcodeKey = qrKey))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun generateQrBitmap(url: String, size: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = QRCodeWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            size,
            size,
            hints
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    suspend fun pollQrCode(qrcodeKey: String, currentSession: BilibiliSession = BilibiliSession()): BilibiliQrPollStatus = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$PASSPORT_HOST/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://www.bilibili.com/")
            }

            // Extract Set-Cookie headers
            val setCookies = conn.headerFields["Set-Cookie"] ?: emptyList()
            val cookieMap = mutableMapOf<String, String>()
            setCookies.forEach { headerVal ->
                val cookieItem = headerVal.substringBefore(";")
                if (cookieItem.contains("=")) {
                    val k = cookieItem.substringBefore("=").trim()
                    val v = cookieItem.substringAfter("=").trim()
                    if (k.isNotBlank()) cookieMap[k] = v
                }
            }

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            if (json.optInt("code", -1) != 0) {
                return@withContext BilibiliQrPollStatus.Error(json.optString("message", "Error polling QR"))
            }

            val data = json.optJSONObject("data") ?: JSONObject()
            val status = data.optInt("code", -1)

            when (status) {
                86101 -> BilibiliQrPollStatus.WaitingScan
                86090 -> BilibiliQrPollStatus.ScannedWaitingConfirmation
                86038 -> BilibiliQrPollStatus.Expired
                0 -> {
                    // Success! Also check url query params as fallback
                    val sessionUrl = data.optString("url", "")
                    if (sessionUrl.isNotBlank()) {
                        try {
                            val uri = Uri.parse(sessionUrl)
                            uri.queryParameterNames.forEach { name ->
                                val v = uri.getQueryParameter(name)
                                if (!v.isNullOrBlank()) {
                                    cookieMap[name] = v
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    val sessData = cookieMap["SESSDATA"] ?: currentSession.sessData
                    val biliJct = cookieMap["bili_jct"] ?: currentSession.biliJct
                    val dedeUserId = cookieMap["DedeUserID"] ?: currentSession.dedeUserId
                    val dedeUserIdCkMd5 = cookieMap["DedeUserID__ckMd5"] ?: currentSession.dedeUserIdCkMd5
                    val sid = cookieMap["sid"] ?: currentSession.sid

                    if (sessData.isBlank()) {
                        return@withContext BilibiliQrPollStatus.Error("Login terkonfirmasi tetapi SESSDATA tidak ditemukan.")
                    }

                    // Obtain fingerprint buvid if not present
                    var buvid3 = currentSession.buvid3
                    var buvid4 = currentSession.buvid4
                    if (buvid3.isBlank() || buvid4.isBlank()) {
                        val buvids = obtainBuvid()
                        if (buvid3.isBlank()) buvid3 = buvids.first
                        if (buvid4.isBlank()) buvid4 = buvids.second
                    }

                    var tempSession = BilibiliSession(
                        isLoggedIn = true,
                        sessData = sessData,
                        biliJct = biliJct,
                        dedeUserId = dedeUserId,
                        dedeUserIdCkMd5 = dedeUserIdCkMd5,
                        sid = sid,
                        buvid3 = buvid3,
                        buvid4 = buvid4
                    )

                    // Validate and fetch user profile
                    val (valid, uname, mid) = validateSessionWithMid(tempSession)
                    if (!valid) {
                        return@withContext BilibiliQrPollStatus.Error("Validasi sesi login gagal: $uname")
                    }

                    // Fetch wallet balance (coins & reading tickets)
                    val wallet = fetchWallet(tempSession)

                    val finalSession = tempSession.copy(
                        username = uname,
                        mid = mid,
                        gold = wallet.first,
                        coupon = wallet.second
                    )
                    BilibiliQrPollStatus.Success(finalSession)
                }
                else -> BilibiliQrPollStatus.Error("Status polling tidak dikenal: $status")
            }
        } catch (e: Exception) {
            BilibiliQrPollStatus.Error(e.message ?: "Kesalahan jaringan saat polling QR")
        }
    }

    // ==================== USER INFO & WALLET ====================

    fun validateSession(session: BilibiliSession): Pair<Boolean, String> {
        val result = validateSessionWithMid(session)
        return Pair(result.first, result.second)
    }

    private fun validateSessionWithMid(session: BilibiliSession): Triple<Boolean, String, Long> {
        return try {
            val url = URL(NAV_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://www.bilibili.com/")
                val cookie = session.toCookieHeader()
                if (cookie.isNotBlank()) {
                    setRequestProperty("Cookie", cookie)
                }
            }
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val data = json.optJSONObject("data") ?: JSONObject()
            val isLogin = data.optBoolean("isLogin", false)
            if (isLogin) {
                val uname = data.optString("uname", "").ifBlank { "User ${data.optLong("mid", 0L)}" }
                val mid = data.optLong("mid", 0L)
                Triple(true, uname, mid)
            } else {
                Triple(false, "Sesi tidak terautentikasi atau kedaluwarsa", 0L)
            }
        } catch (e: Exception) {
            Triple(false, e.message ?: "Gagal memvalidasi sesi", 0L)
        }
    }

    fun fetchWallet(session: BilibiliSession): Pair<Int, Int> {
        return try {
            val url = URL("$MANGA_HOST/twirp/user.v1.User/GetWallet?platform=web")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "$MANGA_HOST/")
                setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                val cookie = session.toCookieHeader()
                if (cookie.isNotBlank()) {
                    setRequestProperty("Cookie", cookie)
                }
                doOutput = true
                outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            }
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            if (json.optInt("code", -1) == 0) {
                val data = json.optJSONObject("data") ?: JSONObject()
                val gold = data.optInt("remain_gold", 0)
                val coupon = data.optInt("remain_coupon", 0)
                Pair(gold, coupon)
            } else {
                Pair(0, 0)
            }
        } catch (e: Exception) {
            Pair(0, 0)
        }
    }

    // ==================== COMIC ID EXTRACTION ====================

    fun extractComicId(input: String): Long {
        val trimmed = input.trim()
        if (trimmed.all { it.isDigit() }) {
            val id = trimmed.toLongOrNull()
            if (id != null && id > 0) return id
        }

        val mcMatch = Pattern.compile("""mc(\d+)""", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        if (mcMatch.find()) {
            val id = mcMatch.group(1)?.toLongOrNull()
            if (id != null && id > 0) return id
        }

        val digitMatch = Pattern.compile("""detail/(\d+)""", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        if (digitMatch.find()) {
            val id = digitMatch.group(1)?.toLongOrNull()
            if (id != null && id > 0) return id
        }

        throw IllegalArgumentException("Tidak dapat menemukan Comic ID yang valid. Masukkan ID angka atau URL mc<ID>.")
    }

    // ==================== TWIRP API POST ====================

    private fun apiPost(
        method: String,
        payload: JSONObject,
        session: BilibiliSession,
        service: String = "comic.v1.Comic"
    ): JSONObject {
        var currentSession = session
        if (currentSession.buvid3.isBlank() || currentSession.buvid4.isBlank()) {
            val (b3, b4) = obtainBuvid(currentSession)
            currentSession = currentSession.copy(buvid3 = b3, buvid4 = b4)
        }

        var resultJson: JSONObject? = null
        for (attempt in 0..1) {
            val ts = System.currentTimeMillis() / 1000
            val queryBuilder = StringBuilder("?ts=").append(ts)
            APP_QUERY.forEach { (k, v) ->
                queryBuilder.append("&").append(k).append("=").append(v)
            }
            val fullUrl = "$MANGA_HOST/twirp/$service/$method$queryBuilder"

            val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30000
                readTimeout = 30000
                setRequestProperty("User-Agent", BILI_APP_UA)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                val cookie = currentSession.toCookieHeader()
                if (cookie.isNotBlank()) {
                    setRequestProperty("Cookie", cookie)
                }
                doOutput = true
                outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }

            val responseText = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: throw e
            }

            val json = JSONObject(responseText)
            val code = json.optString("code", "-1")

            if (code == "99" && attempt == 0) {
                // Refresh buvid and retry
                val (b3, b4) = obtainBuvid(currentSession)
                currentSession = currentSession.copy(buvid3 = b3, buvid4 = b4)
                continue
            }

            resultJson = json
            break
        }

        if (resultJson == null) {
            throw Exception("Gagal menerima respons dari $method")
        }

        val code = resultJson.optString("code", "-1")
        if (code != "0") {
            val msg = resultJson.optString("msg", resultJson.optString("message", "Error $code"))
            if (code == "99") {
                throw Exception("$method: Permintaan ditolak oleh server (Fingerprint buvid ditolak).")
            }
            throw Exception("$method gagal [code=$code]: $msg")
        }

        return resultJson.optJSONObject("data")
            ?: resultJson.optJSONArray("data")?.let { arr ->
                JSONObject().apply { put("list", arr) }
            }
            ?: JSONObject()
    }

    // ==================== COMIC DETAIL & CHAPTERS ====================

    fun fetchSeriesInfo(
        inputUrlOrId: String,
        context: Context? = null,
        session: BilibiliSession? = null
    ): BilibiliSeriesInfo {
        val comicId = extractComicId(inputUrlOrId)
        val activeSession = session
            ?: (if (context != null) loadSession(context) else BilibiliSession())

        val payload = JSONObject().apply {
            put("comicId", comicId)
        }
        val data = apiPost("ComicDetail", payload, activeSession)

        val title = data.optString("title", "Bilibili Comic $comicId")
        val cover = data.optString("vertical_cover", data.optString("cover", ""))
        val epListJson = data.optJSONArray("ep_list") ?: JSONArray()

        val chapters = mutableListOf<BilibiliChapter>()
        for (i in 0 until epListJson.length()) {
            val epObj = epListJson.optJSONObject(i) ?: continue
            val id = epObj.optLong("id", 0L)
            val ord = epObj.optDouble("ord", (i + 1).toDouble()).toFloat()
            val epTitle = epObj.optString("title", "")
            val shortTitle = epObj.optString("short_title", "")
            val isLocked = epObj.optBoolean("is_locked", false)
            val isInFree = epObj.optBoolean("is_in_free", false)
            val payGold = epObj.optInt("pay_gold", 0)
            val imageCount = epObj.optInt("image_count", 0)

            chapters.add(
                BilibiliChapter(
                    id = id,
                    comicId = comicId,
                    number = ord,
                    title = epTitle,
                    shortTitle = shortTitle,
                    isLocked = isLocked,
                    isInFree = isInFree,
                    payGold = payGold,
                    imageCount = imageCount
                )
            )
        }

        chapters.sortBy { it.number }
        return BilibiliSeriesInfo(
            comicId = comicId,
            title = title,
            cover = cover,
            chapters = chapters
        )
    }

    // ==================== IMAGE INDEX & TOKEN ====================

    fun getChapterImages(
        comicId: Long,
        epId: Long,
        session: BilibiliSession? = null
    ): List<String> {
        val activeSession = session ?: BilibiliSession()

        // 1. GetImageIndex
        val idxPayload = JSONObject().apply {
            put("epId", epId)
        }
        val idxData = apiPost("GetImageIndex", idxPayload, activeSession)
        val imagesArr = idxData.optJSONArray("images") ?: JSONArray()
        val paths = mutableListOf<String>()
        for (i in 0 until imagesArr.length()) {
            val imgObj = imagesArr.optJSONObject(i) ?: continue
            val p = imgObj.optString("path", "")
            if (p.isNotBlank()) paths.add(p)
        }

        if (paths.isEmpty()) {
            throw Exception("GetImageIndex tidak mengembalikan gambar untuk ep_id=$epId")
        }

        // 2. ImageToken
        val pathsJsonArray = JSONArray(paths)
        val tokPayload = JSONObject().apply {
            put("urls", pathsJsonArray.toString())
        }
        val tokData = apiPost("ImageToken", tokPayload, activeSession)
        val tokList = tokData.optJSONArray("list") ?: JSONArray()

        val fullUrls = mutableListOf<String>()
        for (i in 0 until tokList.length()) {
            val item = tokList.optJSONObject(i) ?: continue
            var completeUrl = item.optString("complete_url", "")
            if (completeUrl.isBlank()) {
                val baseUrl = item.optString("url", "")
                val token = item.optString("token", "")
                completeUrl = if (token.isNotBlank()) {
                    if (baseUrl.contains("?")) "$baseUrl&token=$token" else "$baseUrl?token=$token"
                } else {
                    baseUrl
                }
            }
            if (completeUrl.isNotBlank()) {
                fullUrls.add(completeUrl)
            }
        }

        if (fullUrls.isEmpty()) {
            throw Exception("ImageToken tidak mengembalikan token gambar yang valid")
        }

        return fullUrls
    }

    fun scrapeChapterImages(context: Context?, url: String, cookie: String = ""): List<String> {
        var comicId: Long = 0
        var epId: Long = 0

        val uri = Uri.parse(url)
        val fragment = uri.fragment ?: ""

        if (fragment.isNotBlank()) {
            val pairs = fragment.split("&")
            for (pair in pairs) {
                val parts = pair.split("=")
                if (parts.size == 2) {
                    val k = parts[0].trim()
                    val v = parts[1].trim()
                    if (k == "ep_id") epId = v.toLongOrNull() ?: 0
                    if (k == "comic_id") comicId = v.toLongOrNull() ?: 0
                }
            }
        }

        if (epId == 0L) {
            epId = uri.getQueryParameter("ep_id")?.toLongOrNull() ?: 0
        }
        if (comicId == 0L) {
            comicId = uri.getQueryParameter("comic_id")?.toLongOrNull() ?: 0
        }

        if (comicId == 0L) {
            val match = Regex("""mc(\d+)""", RegexOption.IGNORE_CASE).find(url)
            if (match != null) {
                comicId = match.groupValues[1].toLongOrNull() ?: 0
            }
        }

        if (epId == 0L || comicId == 0L) {
            throw IllegalArgumentException("URL Bilibili tidak valid atau tidak memiliki ep_id/comic_id: $url")
        }

        val session = if (context != null) {
            loadSession(context)
        } else if (cookie.isNotBlank()) {
            parseCookieString(cookie)
        } else {
            BilibiliSession()
        }

        return getChapterImages(comicId, epId, session)
    }

    // ==================== CPX DECRYPTION ====================

    fun isImage(data: ByteArray): Boolean {
        if (data.size < 4) return false
        // JPEG: FF D8 FF
        if ((data[0].toInt() and 0xFF) == 0xFF &&
            (data[1].toInt() and 0xFF) == 0xD8 &&
            (data[2].toInt() and 0xFF) == 0xFF
        ) return true
        // PNG: 89 50 4E 47
        if ((data[0].toInt() and 0xFF) == 0x89 &&
            data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() &&
            data[3] == 0x47.toByte()
        ) return true
        // WEBP: RIFF....WEBP
        if (data.size >= 12 &&
            data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() &&
            data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte()
        ) return true
        // GIF: GIF8
        if (data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == '8'.code.toByte()
        ) return true
        return false
    }

    fun isCpxPayload(data: ByteArray): Boolean {
        return data.size >= 5 && data[0].toInt() == 1 && !isImage(data)
    }

    fun decryptCpxImage(data: ByteArray, imageUrl: String): ByteArray {
        val uri = try { Uri.parse(imageUrl) } catch (_: Exception) { return data }
        val cpxParam = uri.getQueryParameter("cpx")
        if (cpxParam.isNullOrBlank() || isImage(data)) {
            return data
        }
        if (data.size < 5 || data[0].toInt() != 1) {
            return data
        }

        val dataLength = ((data[1].toInt() and 0xFF) shl 24) or
            ((data[2].toInt() and 0xFF) shl 16) or
            ((data[3].toInt() and 0xFF) shl 8) or
            (data[4].toInt() and 0xFF)

        val end = 5 + dataLength
        if (dataLength <= 0 || end > data.size) {
            return data
        }

        val cpxRaw = try {
            Base64.decode(cpxParam, Base64.DEFAULT)
        } catch (_: Exception) {
            return data
        }
        if (cpxRaw.size < 76) return data

        val iv = cpxRaw.copyOfRange(60, 76)
        val key = data.copyOfRange(end, data.size)
        val content = data.copyOfRange(5, end)

        if (iv.size != 16 || (key.size != 16 && key.size != 24 && key.size != 32)) {
            return data
        }

        val headLen = minOf(content.size, 20496)
        val encryptedHead = content.copyOfRange(0, headLen)
        if (encryptedHead.isEmpty() || encryptedHead.size % 16 != 0) {
            return data
        }

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        var decryptedHead = cipher.doFinal(encryptedHead)

        // If whole payload is <= 20496, check and strip PKCS#7 padding
        if (content.size <= 20496 && decryptedHead.isNotEmpty()) {
            val pad = decryptedHead.last().toInt() and 0xFF
            if (pad in 1..16 && decryptedHead.size >= pad) {
                var validPad = true
                for (i in (decryptedHead.size - pad) until decryptedHead.size) {
                    if ((decryptedHead[i].toInt() and 0xFF) != pad) {
                        validPad = false
                        break
                    }
                }
                if (validPad) {
                    decryptedHead = decryptedHead.copyOfRange(0, decryptedHead.size - pad)
                }
            }
        }

        val remaining = if (content.size > 20496) content.copyOfRange(20496, content.size) else byteArrayOf()
        val restored = ByteArray(decryptedHead.size + remaining.size).apply {
            System.arraycopy(decryptedHead, 0, this, 0, decryptedHead.size)
            System.arraycopy(remaining, 0, this, decryptedHead.size, remaining.size)
        }

        return if (isImage(restored)) restored else data
    }
}
