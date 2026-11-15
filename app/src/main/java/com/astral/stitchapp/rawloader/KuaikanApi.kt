package com.astral.stitchapp.rawloader

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.Random
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class KuaikanSession(
    val isLoggedIn: Boolean = false,
    val uid: String = "",
    val username: String = "",
    val rawCookie: String = "",
    val netscapeCookie: String = "",
    val androidId: String = "",
    val kkdid: String = ""
)

data class KuaikanChapter(
    val id: Long,
    val topicId: Long,
    val number: Int,
    val title: String,
    val imageCount: Int = 0,
    val isFree: Boolean = false,
    val canView: Boolean = false
)

data class KuaikanSeriesInfo(
    val topicId: Long,
    val title: String,
    val cover: String = "",
    val comicsCount: Int = 0,
    val chapters: List<KuaikanChapter>
)

object KuaikanApi {
    private const val PREFS_NAME = "kuaikan_session"
    const val COOKIE_FILE_NAME = ".kuaikan_cookies"
    const val LOGIN_URL = "https://www.kuaikanmanhua.com/webs/loginh?redirect=https://www.kuaikanmanhua.com/?cps=box&cps=box"
    const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val SIGNING_SECRET = "71IENuDEPr9Iep46kS1z2dFiP75bv4dV"
    private const val RSA_PUB_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCz/dsaNKOZzEbiyHjGgDSIb/" +
        "Ymtdc3zp6th+WtTHs2GmEAg5ObTg30arprr3aNmKteLqvZj8WB2HMJOYuXiuk" +
        "kUJsg7Xr0lmob34XwZvFFb8ka1i9+oeRSZgtuLuoJn2lU+TZfgQLA1ylYHf0v7" +
        "lwi6sZRbeQ4rd4zwN6WOpsy1QIDAQAB"
    private val AES_IV = "0102030405060708".toByteArray(Charsets.UTF_8)
    private const val AES_KEY_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIGKLMN"

    private const val APP_USER_AGENT = "Kuaikan/8.19.0/819000(Android;14;Pixel 6;selfupdate;WIFI;1080*2224)"

    private val BASE_URLS = mapOf(
        "api" to "https://api.kkmh.com",
        "social" to "https://social.kkmh.com",
        "pay" to "https://pay.kkmh.com",
        "search" to "https://search.kkmh.com",
        "track" to "https://api.kkmh.com"
    )

    private val ENCRYPTED_PATHS = setOf(
        "/v2/passport/phone/login",
        "/v2/passport/phone/signup",
        "/v2/passport/oauth/signup",
        "/v2/passport/oauth/oauth_signup",
        "/v2/passport/user/update",
        "/v2/passport/phone/status",
        "/v1/pay/free_secret/pay"
    )

    @Volatile
    private var serverTime: Long = -1L

    private fun generateAndroidId(): String {
        val chars = "0123456789abcdef"
        val rnd = Random()
        return buildString(16) {
            repeat(16) { append(chars[rnd.nextInt(chars.length)]) }
        }
    }

    private fun generateKkdid(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = Random()
        return buildString(32) {
            repeat(32) { append(chars[rnd.nextInt(chars.length)]) }
        }
    }

    // ==================== NETSCAPE COOKIE CONVERSION & STORAGE ====================

    /**
     * Converts a raw browser cookie string ("k1=v1; k2=v2; ...") to Netscape HTTP Cookie File format
     * matching the reference Python logic:
     *
     * exp = int(time.time()) + 86400 * 365
     * lines = ["# Netscape HTTP Cookie File"]
     * for part in raw.split(";"):
     *     if "=" not in part:
     *         continue
     *     name, value = part.strip().split("=", 1)
     *     lines.append(f".kkmh.com\tTRUE\t/\tFALSE\t{exp}\t{name}\t{value}")
     * open(".kuaikan_cookies", "w").write("\n".join(lines) + "\n")
     */
    fun convertRawToNetscape(rawCookie: String): String {
        val raw = rawCookie.trim()
        val exp = (System.currentTimeMillis() / 1000L) + 86400L * 365L
        val lines = mutableListOf("# Netscape HTTP Cookie File")
        for (part in raw.split(";")) {
            if ("=" !in part) continue
            val splits = part.trim().split("=", limit = 2)
            val name = splits[0]
            val value = splits[1]
            lines.add(".kkmh.com\tTRUE\t/\tFALSE\t$exp\t$name\t$value")
        }
        return lines.joinToString("\n") + "\n"
    }

    fun getCookieFile(context: Context): File {
        return File(context.filesDir, COOKIE_FILE_NAME)
    }

    fun writeNetscapeCookieFile(context: Context, rawCookie: String): String {
        val netscape = convertRawToNetscape(rawCookie)
        try {
            File(context.filesDir, COOKIE_FILE_NAME).writeText(netscape, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            File(context.cacheDir, COOKIE_FILE_NAME).writeText(netscape, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return netscape
    }

    fun parseCookiesToMap(cookieText: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        val text = cookieText.trim()
        if (text.isEmpty()) return map

        if (text.startsWith("# Netscape HTTP Cookie File") || text.contains("\t")) {
            for (rawLine in text.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = rawLine.split("\t")
                if (parts.size >= 6) {
                    val name = parts[5].trim()
                    val value = if (parts.size >= 7) parts[6].trim() else ""
                    if (name.isNotEmpty()) {
                        map[name] = value
                    }
                }
            }
            if (map.isNotEmpty()) return map
        }

        val cleaned = if (text.lowercase(Locale.ROOT).startsWith("cookie:")) {
            text.substringAfter(":")
        } else {
            text
        }
        for (part in cleaned.replace("\n", ";").split(";")) {
            val trimmed = part.trim()
            if ("=" !in trimmed) continue
            val splits = trimmed.split("=", limit = 2)
            val name = splits[0].trim()
            val value = splits[1].trim()
            if (name.isNotEmpty()) {
                map[name] = value
            }
        }
        return map
    }

    fun loadCookiesMap(context: Context? = null, cacheDir: File? = null, fallbackCookie: String = ""): LinkedHashMap<String, String> {
        val candidates = mutableListOf<File>()
        if (context != null) {
            candidates.add(File(context.filesDir, COOKIE_FILE_NAME))
            candidates.add(File(context.cacheDir, COOKIE_FILE_NAME))
        }
        if (cacheDir != null) {
            candidates.add(File(cacheDir, COOKIE_FILE_NAME))
        }

        for (file in candidates) {
            if (file.exists() && file.canRead()) {
                try {
                    val text = file.readText(Charsets.UTF_8).trim()
                    if (text.isNotEmpty()) {
                        val parsed = parseCookiesToMap(text)
                        if (parsed.isNotEmpty()) {
                            return parsed
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (context != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedNetscape = prefs.getString("netscape_cookie", "") ?: ""
            if (savedNetscape.isNotBlank()) {
                val parsed = parseCookiesToMap(savedNetscape)
                if (parsed.isNotEmpty()) return parsed
            }
            val savedRaw = prefs.getString("raw_cookie", "") ?: ""
            if (savedRaw.isNotBlank()) {
                val parsed = parseCookiesToMap(savedRaw)
                if (parsed.isNotEmpty()) return parsed
            }
        }

        if (fallbackCookie.isNotBlank()) {
            return parseCookiesToMap(fallbackCookie)
        }

        return LinkedHashMap()
    }

    private fun saveCookiesMapToFiles(context: Context?, cacheDir: File?, cookieMap: Map<String, String>) {
        if (cookieMap.isEmpty()) return
        val exp = (System.currentTimeMillis() / 1000L) + 86400L * 365L
        val lines = mutableListOf("# Netscape HTTP Cookie File")
        for ((name, value) in cookieMap) {
            if (name.isNotBlank()) {
                lines.add(".kkmh.com\tTRUE\t/\tFALSE\t$exp\t$name\t$value")
            }
        }
        val netscape = lines.joinToString("\n") + "\n"
        try {
            context?.filesDir?.let { File(it, COOKIE_FILE_NAME).writeText(netscape, Charsets.UTF_8) }
        } catch (_: Exception) {}
        try {
            context?.cacheDir?.let { File(it, COOKIE_FILE_NAME).writeText(netscape, Charsets.UTF_8) }
        } catch (_: Exception) {}
        try {
            cacheDir?.let { File(it, COOKIE_FILE_NAME).writeText(netscape, Charsets.UTF_8) }
        } catch (_: Exception) {}
    }

    // ==================== SESSION MANAGEMENT ====================

    fun loadSession(context: Context): KuaikanSession {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var androidId = prefs.getString("android_id", "") ?: ""
        var kkdid = prefs.getString("kkdid", "") ?: ""
        if (androidId.isBlank()) {
            androidId = generateAndroidId()
            prefs.edit().putString("android_id", androidId).apply()
        }
        if (kkdid.isBlank()) {
            kkdid = generateKkdid()
            prefs.edit().putString("kkdid", kkdid).apply()
        }

        val cookieFile = File(context.filesDir, COOKIE_FILE_NAME)
        val fileNetscape = if (cookieFile.exists()) {
            try { cookieFile.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
        } else ""

        val netscapeCookie = fileNetscape.ifBlank { prefs.getString("netscape_cookie", "") ?: "" }
        val rawCookie = prefs.getString("raw_cookie", "") ?: ""
        val cookieMap = if (netscapeCookie.isNotBlank()) {
            parseCookiesToMap(netscapeCookie)
        } else {
            parseCookiesToMap(rawCookie)
        }

        val isLoggedIn = prefs.getBoolean("is_logged_in", false) && (netscapeCookie.isNotBlank() || cookieMap.isNotEmpty())
        val uid = cookieMap["uid"] ?: (prefs.getString("uid", "") ?: "")
        val username = prefs.getString("username", "") ?: ""

        if (netscapeCookie.isNotBlank()) {
            try {
                if (!cookieFile.exists()) {
                    cookieFile.writeText(netscapeCookie, Charsets.UTF_8)
                }
                val cacheCookieFile = File(context.cacheDir, COOKIE_FILE_NAME)
                if (!cacheCookieFile.exists()) {
                    cacheCookieFile.writeText(netscapeCookie, Charsets.UTF_8)
                }
            } catch (_: Exception) {}
        }

        return KuaikanSession(
            isLoggedIn = isLoggedIn,
            uid = uid,
            username = username,
            rawCookie = rawCookie,
            netscapeCookie = netscapeCookie,
            androidId = androidId,
            kkdid = kkdid
        )
    }

    fun saveSession(context: Context, session: KuaikanSession) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("is_logged_in", session.isLoggedIn)
            putString("uid", session.uid)
            putString("username", session.username)
            putString("raw_cookie", session.rawCookie)
            putString("netscape_cookie", session.netscapeCookie)
            if (session.androidId.isNotBlank()) putString("android_id", session.androidId)
            if (session.kkdid.isNotBlank()) putString("kkdid", session.kkdid)
            apply()
        }
        if (session.netscapeCookie.isNotBlank()) {
            try {
                File(context.filesDir, COOKIE_FILE_NAME).writeText(session.netscapeCookie, Charsets.UTF_8)
            } catch (_: Exception) {}
            try {
                File(context.cacheDir, COOKIE_FILE_NAME).writeText(session.netscapeCookie, Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        try {
            val f1 = File(context.filesDir, COOKIE_FILE_NAME)
            if (f1.exists()) f1.delete()
        } catch (_: Exception) {}
        try {
            val f2 = File(context.cacheDir, COOKIE_FILE_NAME)
            if (f2.exists()) f2.delete()
        } catch (_: Exception) {}
    }

    suspend fun generateSessionFromRawCookie(context: Context, rawCookie: String): KuaikanSession = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val androidId = (prefs.getString("android_id", "") ?: "").ifBlank { generateAndroidId() }
        val kkdid = (prefs.getString("kkdid", "") ?: "").ifBlank { generateKkdid() }

        val netscape = writeNetscapeCookieFile(context, rawCookie)
        val cookieMap = parseCookiesToMap(netscape)
        var uid = cookieMap["uid"] ?: ""
        var username = if (uid.isNotBlank()) "UID: $uid" else "Kuaikan User"

        val tempSession = KuaikanSession(
            isLoggedIn = true,
            uid = uid,
            username = username,
            rawCookie = rawCookie,
            netscapeCookie = netscape,
            androidId = androidId,
            kkdid = kkdid
        )
        saveSession(context, tempSession)

        // Try fetching user profile info from /v2/passport/user/me
        try {
            val meJson = request(
                method = "GET",
                path = "/v2/passport/user/me",
                context = context
            )
            val data = meJson.optJSONObject("data")
            val userObj = data?.optJSONObject("user") ?: data
            val nick = userObj?.optString("nickname", "")?.trim() ?: ""
            val fetchedUid = userObj?.optLong("id", 0L)?.takeIf { it > 0 }?.toString()
                ?: userObj?.optString("uid", "")?.trim()
                ?: ""
            if (fetchedUid.isNotBlank() && uid.isBlank()) {
                uid = fetchedUid
            }
            if (nick.isNotBlank()) {
                username = if (uid.isNotBlank()) "$nick (UID: $uid)" else nick
            }
        } catch (_: Exception) {}

        val finalSession = tempSession.copy(
            uid = uid,
            username = username
        )
        saveSession(context, finalSession)
        finalSession
    }

    // ==================== SIGNING & ENCRYPTION ====================

    private fun buildParamsBytes(params: Map<String, String>): ByteArray {
        val result = ByteArrayOutputStream()
        for (key in params.keys.sorted()) {
            val value = params[key] ?: continue
            val cleaned = StringBuilder()
            var count = 0
            for (ch in value) {
                if (!ch.isWhitespace() && count < 320) {
                    cleaned.append(ch)
                    count++
                }
            }
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            var valueBytes = cleaned.toString().toByteArray(Charsets.UTF_8)
            if (valueBytes.size > 80) {
                valueBytes = valueBytes.copyOfRange(0, 80)
            }
            result.write(keyBytes)
            result.write(valueBytes)
        }
        return result.toByteArray()
    }

    fun computeSignature(
        androidId: String,
        params: Map<String, String>,
        serverTimeVal: Long,
        clientTimeVal: Long
    ): String {
        val prefix = "A:$androidId".toByteArray(Charsets.UTF_8)
        val secret = SIGNING_SECRET.toByteArray(Charsets.UTF_8)

        val serverBytes = ByteArray(8)
        var st = serverTimeVal
        for (i in 7 downTo 0) {
            serverBytes[i] = (st and 0xFFL).toByte()
            st = st ushr 8
        }

        val clientBytes = ByteArray(8)
        var ct = clientTimeVal
        for (i in 7 downTo 0) {
            clientBytes[i] = (ct and 0xFFL).toByte()
            ct = ct ushr 8
        }

        val paramBytes = buildParamsBytes(params)

        val raw = ByteArrayOutputStream(prefix.size + secret.size + 16 + paramBytes.size).apply {
            write(prefix)
            write(secret)
            write(serverBytes)
            write(clientBytes)
            write(paramBytes)
        }.toByteArray()

        val numericStr = StringBuilder(raw.size * 3)
        for (b in raw) {
            // Kotlin Byte is signed (-128..127), identical to Python's `b if b < 128 else b - 256`
            numericStr.append(b.toInt())
        }

        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(numericStr.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildAppInfo(androidId: String, kkdid: String, params: Map<String, String>): String {
        val clientTime = System.currentTimeMillis()
        val effectiveServerTime = if (serverTime > 0) serverTime else clientTime
        val sign = computeSignature(androidId, params, effectiveServerTime, clientTime)

        val appInfo = JSONObject().apply {
            put("KKDID", kkdid)
            put("aegis_app_id", "1000001803")
            put("android_id", androidId)
            put("app_secret_sign", sign)
            put("bd", "Google")
            put("devt", 1)
            put("dpi", 420)
            put("gender", 0)
            put("height", 2224)
            put("imei", "")
            put("imsi", "")
            put("kk_c_t", clientTime)
            put("kk_s_t", effectiveServerTime)
            put("mac", "")
            put("model", "Pixel 6")
            put("oaid", "")
            put("ov", "34")
            put("phoneNumber", "")
            put("rec_forbid", false)
            put("user_group", "normal")
            put("visitor_sign", "")
            put("width", 1080)
            put("ca", 0)
            put("ct", 20)
        }
        val jsonStr = appInfo.toString()
        return Base64.encodeToString(jsonStr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun generateAesKey(): ByteArray {
        val rnd = Random()
        val sb = StringBuilder(16)
        repeat(16) { sb.append(AES_KEY_CHARS[rnd.nextInt(AES_KEY_CHARS.length)]) }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun aesEncrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(AES_IV))
        return cipher.doFinal(plaintext)
    }

    private fun aesDecrypt(ciphertext: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(AES_IV))
        return cipher.doFinal(ciphertext)
    }

    private fun rsaEncrypt(data: ByteArray, pubKeyPem: String = RSA_PUB_KEY): ByteArray {
        val keyBytes = Base64.decode(pubKeyPem, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance("RSA")
        val pubKey = kf.generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        return cipher.doFinal(data)
    }

    private fun encryptFormValue(value: String, aesKey: ByteArray): String {
        val encrypted = aesEncrypt(value.toByteArray(Charsets.UTF_8), aesKey)
        return Base64.encodeToString(encrypted, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun buildCipherKey(aesKey: ByteArray): String {
        val encryptedKey = rsaEncrypt(aesKey)
        return Base64.encodeToString(encryptedKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun decryptResponseData(dataStr: String, aesKey: ByteArray): JSONObject? {
        return try {
            val raw = Base64.decode(dataStr, Base64.URL_SAFE)
            val decrypted = aesDecrypt(raw, aesKey)
            JSONObject(String(decrypted, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun extractServerTimeAndCookies(
        conn: HttpURLConnection,
        body: JSONObject?,
        cookieMap: LinkedHashMap<String, String>
    ): Boolean {
        var cookiesChanged = false
        for ((headerKey, headerValues) in conn.headerFields) {
            if (headerKey != null && headerKey.equals("Set-Cookie", ignoreCase = true) && headerValues != null) {
                for (setCookie in headerValues) {
                    val parts = setCookie.split(";")
                    if (parts.isNotEmpty()) {
                        val firstPart = parts[0].trim()
                        if ("=" in firstPart) {
                            val k = firstPart.substringBefore("=").trim()
                            val v = firstPart.substringAfter("=").trim()
                            if (k.isNotEmpty()) {
                                cookieMap[k] = v
                                cookiesChanged = true
                            }
                            if (k == "kk_s_t") {
                                val st = v.toLongOrNull() ?: -1L
                                if (st > 0) serverTime = st
                            }
                        }
                    }
                    for (part in parts) {
                        val trimmed = part.trim()
                        if (trimmed.startsWith("kk_s_t=")) {
                            val st = trimmed.substringAfter("=").toLongOrNull() ?: -1L
                            if (st > 0) serverTime = st
                        }
                    }
                }
            }
        }

        if (body != null) {
            var ts = body.optLong("kk_s_t", -1L)
            if (ts <= 0) {
                ts = body.optJSONObject("data")?.optLong("kk_s_t", -1L) ?: -1L
            }
            if (ts > 0) {
                serverTime = ts
            }
        }
        return cookiesChanged
    }

    // ==================== HTTP CLIENT ====================

    fun request(
        method: String,
        path: String,
        params: Map<String, String>? = null,
        data: Map<String, String>? = null,
        base: String = "api",
        encrypted: Boolean = false,
        context: Context? = null,
        cacheDir: File? = null,
        cookieOverride: String = ""
    ): JSONObject {
        val baseUrl = BASE_URLS[base] ?: BASE_URLS["api"]!!
        val normPath = if (path.startsWith("/")) path else "/$path"

        val reqParams = params ?: emptyMap()
        val reqData = data ?: emptyMap()

        val session = if (context != null) loadSession(context) else null
        val androidId = session?.androidId?.takeIf { it.isNotBlank() } ?: generateAndroidId()
        val kkdid = session?.kkdid?.takeIf { it.isNotBlank() } ?: generateKkdid()

        val cookieMap = loadCookiesMap(context, cacheDir, cookieOverride)
        if (serverTime <= 0) {
            val cookieSt = cookieMap["kk_s_t"]?.toLongOrNull() ?: -1L
            if (cookieSt > 0) serverTime = cookieSt
        }

        val signingParams = if (method.equals("GET", ignoreCase = true)) {
            reqParams
        } else {
            reqData.ifEmpty { reqParams }
        }
        val appInfo = buildAppInfo(androidId, kkdid, signingParams)

        val queryStr = if (reqParams.isNotEmpty()) {
            reqParams.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
        } else ""

        val fullUrl = if (queryStr.isNotEmpty()) "$baseUrl$normPath?$queryStr" else "$baseUrl$normPath"

        var aesKey: ByteArray? = null
        val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method.uppercase(Locale.ROOT)
            connectTimeout = 30000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", APP_USER_AGENT)
            setRequestProperty("X-Device", "A:$androidId")
            setRequestProperty("Muid", "0")
            setRequestProperty("kkdid", kkdid)
            setRequestProperty("Package-Id", "com.kuaikan.comic")
            setRequestProperty("Lower-Flow", "No")
            setRequestProperty("kkFlowType", "1")
            setRequestProperty("App-Info", appInfo)

            if (cookieMap.isNotEmpty()) {
                val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                setRequestProperty("Cookie", cookieHeader)
            }

            if (!method.equals("GET", ignoreCase = true)) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val formMap = if (encrypted || normPath in ENCRYPTED_PATHS) {
                    val key = generateAesKey()
                    aesKey = key
                    setRequestProperty("CIPHER-KEY", buildCipherKey(key))
                    reqData.mapValues { (_, v) -> encryptFormValue(v, key) }
                } else {
                    reqData.ifEmpty { reqParams }
                }
                val formBody = formMap.entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }
                outputStream.use { os ->
                    os.write(formBody.toByteArray(Charsets.UTF_8))
                }
            }
        }

        val responseText = try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: throw e
        }

        val body = try {
            JSONObject(responseText)
        } catch (e: Exception) {
            throw Exception("Respons API Kuaikan tidak valid: ${responseText.take(200)}")
        }

        val cookiesChanged = extractServerTimeAndCookies(conn, body, cookieMap)
        if (cookiesChanged) {
            saveCookiesMapToFiles(context, cacheDir, cookieMap)
        }

        if (aesKey != null) {
            val dataStr = body.optString("data", "")
            if (dataStr.length > 4 && body.optJSONObject("data") == null) {
                val decrypted = decryptResponseData(dataStr, aesKey!!)
                if (decrypted != null) {
                    body.put("data", decrypted)
                }
            }
        }

        return body
    }

    // ==================== TOPIC & CHAPTER API ====================

    fun extractTopicId(input: String): Long {
        val trimmed = input.trim()
        if (trimmed.all { it.isDigit() }) {
            val id = trimmed.toLongOrNull()
            if (id != null && id > 0) return id
        }

        val patterns = listOf(
            """topic/(\d+)""",
            """mobile/(\d+)""",
            """topic_id=(\d+)""",
            """[?&]id=(\d+)"""
        )
        for (pat in patterns) {
            val matcher = Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(trimmed)
            if (matcher.find()) {
                val id = matcher.group(1)?.toLongOrNull()
                if (id != null && id > 0) return id
            }
        }

        val fallbackMatcher = Pattern.compile("""/(\d{2,})(?:[/?#]|$)""").matcher(trimmed)
        if (fallbackMatcher.find()) {
            val id = fallbackMatcher.group(1)?.toLongOrNull()
            if (id != null && id > 0) return id
        }

        throw IllegalArgumentException("Tidak dapat menemukan Topic ID KuaikanManhua yang valid dari '$input'. Contoh URL: https://www.kuaikanmanhua.com/web/topic/21881/?cps=box")
    }

    fun getTopicCatalog(
        topicId: Long,
        sort: Int = 0,
        sortAction: Int = 0,
        context: Context? = null,
        cacheDir: File? = null,
        cookieOverride: String = ""
    ): JSONObject {
        return request(
            method = "GET",
            path = "/v2/topic/topic_catalogue/list",
            params = mapOf(
                "topic_id" to topicId.toString(),
                "sort" to sort.toString(),
                "sort_action" to sortAction.toString()
            ),
            context = context,
            cacheDir = cacheDir,
            cookieOverride = cookieOverride
        )
    }

    fun getTopicDetail(
        topicId: Long,
        gender: Int = 0,
        isNewDevice: Boolean = false,
        context: Context? = null,
        cacheDir: File? = null,
        cookieOverride: String = ""
    ): JSONObject {
        return request(
            method = "GET",
            path = "/v2/topic/topic_detail",
            params = mapOf(
                "topic_id" to topicId.toString(),
                "gender" to gender.toString(),
                "is_new_device" to isNewDevice.toString().lowercase(Locale.ROOT)
            ),
            context = context,
            cacheDir = cacheDir,
            cookieOverride = cookieOverride
        )
    }

    fun getComicDetail(
        topicId: Long,
        comicId: Long,
        successive: Int = 0,
        converter: Boolean = false,
        originContent: Boolean = false,
        gender: Int = 0,
        context: Context? = null,
        cacheDir: File? = null,
        cookieOverride: String = ""
    ): JSONObject {
        return request(
            method = "GET",
            path = "/v2/comic/detail/get",
            params = mapOf(
                "topic_id" to topicId.toString(),
                "comic_id" to comicId.toString(),
                "successive" to successive.toString(),
                "converter" to converter.toString().lowercase(Locale.ROOT),
                "origin_content" to originContent.toString().lowercase(Locale.ROOT),
                "gender" to gender.toString()
            ),
            context = context,
            cacheDir = cacheDir,
            cookieOverride = cookieOverride
        )
    }

    private fun parseComicsArray(comicsArr: JSONArray?, topicId: Long): List<KuaikanChapter> {
        if (comicsArr == null || comicsArr.length() == 0) return emptyList()
        val list = mutableListOf<KuaikanChapter>()
        for (i in 0 until comicsArr.length()) {
            val c = comicsArr.optJSONObject(i) ?: continue
            val cid = c.optLong("id", c.optLong("comic_id", 0L))
            if (cid <= 0L) continue
            val chTitle = c.optString("title", c.optString("name", "")).trim()
            val imageCount = c.optInt("image_count", 0)
            val payInfo = c.optJSONObject("pay_info")
            val isFree = payInfo?.optBoolean("is_free", c.optBoolean("is_free", false))
                ?: c.optBoolean("is_free", false)
            val canView = payInfo?.optBoolean("can_view", c.optBoolean("can_view", isFree))
                ?: c.optBoolean("can_view", isFree)

            list.add(
                KuaikanChapter(
                    id = cid,
                    topicId = topicId,
                    number = list.size + 1,
                    title = chTitle,
                    imageCount = imageCount,
                    isFree = isFree,
                    canView = canView
                )
            )
        }
        return list
    }

    fun fetchSeriesInfo(
        inputUrlOrId: String,
        context: Context? = null,
        session: KuaikanSession? = null
    ): KuaikanSeriesInfo {
        val topicId = extractTopicId(inputUrlOrId)
        val cookieOverride = session?.netscapeCookie?.ifBlank { session.rawCookie } ?: ""

        // 1. Fetch catalog (/v2/topic/topic_catalogue/list)
        val catalogBody = getTopicCatalog(
            topicId = topicId,
            sort = 0,
            sortAction = 0,
            context = context,
            cookieOverride = cookieOverride
        )
        val catalogData = catalogBody.optJSONObject("data")
        val catalogTopic = catalogData?.optJSONObject("topic_info") ?: catalogData

        var title = catalogTopic?.optString("title", "")?.trim() ?: ""
        var cover = catalogTopic?.optString("cover_image_url", catalogTopic?.optString("vertical_image_url", "")) ?: ""
        var comicsCount = catalogTopic?.optInt("comics_count", 0) ?: 0

        var chapters = parseComicsArray(
            catalogData?.optJSONArray("comics") ?: catalogData?.optJSONArray("list"),
            topicId
        )

        // 2. Also check topic_detail (/v2/topic/topic_detail) if title or chapters are missing
        if (title.isBlank() || chapters.isEmpty()) {
            try {
                val detailBody = getTopicDetail(
                    topicId = topicId,
                    context = context,
                    cookieOverride = cookieOverride
                )
                val detailData = detailBody.optJSONObject("data")
                val detailTopic = detailData?.optJSONObject("topic_info") ?: detailData

                if (title.isBlank()) {
                    title = detailTopic?.optString("title", detailData?.optString("title", ""))?.trim() ?: ""
                }
                if (cover.isBlank()) {
                    cover = detailTopic?.optString("cover_image_url", detailTopic?.optString("vertical_image_url", "")) ?: ""
                }
                if (comicsCount <= 0) {
                    comicsCount = detailTopic?.optInt("comics_count", 0) ?: 0
                }

                if (chapters.isEmpty() && detailData != null) {
                    val directComics = parseComicsArray(detailData.optJSONArray("comics"), topicId)
                    if (directComics.isNotEmpty()) {
                        chapters = directComics
                    } else {
                        val seasons = detailData.optJSONArray("season")
                        if (seasons != null && seasons.length() > 0) {
                            val combined = JSONArray()
                            for (sIdx in 0 until seasons.length()) {
                                val sObj = seasons.optJSONObject(sIdx) ?: continue
                                val sItems = sObj.optJSONArray("comics") ?: sObj.optJSONArray("chapters") ?: continue
                                for (cIdx in 0 until sItems.length()) {
                                    combined.put(sItems.get(cIdx))
                                }
                            }
                            chapters = parseComicsArray(combined, topicId)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (title.isBlank()) {
            title = "Kuaikan Topic $topicId"
        }
        if (comicsCount <= 0) {
            comicsCount = chapters.size
        }

        return KuaikanSeriesInfo(
            topicId = topicId,
            title = title,
            cover = cover,
            comicsCount = comicsCount,
            chapters = chapters
        )
    }

    fun getChapterImages(
        topicId: Long,
        comicId: Long,
        context: Context? = null,
        cacheDir: File? = null,
        cookieOverride: String = ""
    ): List<String> {
        val body = getComicDetail(
            topicId = topicId,
            comicId = comicId,
            context = context,
            cacheDir = cacheDir,
            cookieOverride = cookieOverride
        )
        val data = body.optJSONObject("data")
        val urls = mutableListOf<String>()

        val imagesArr = data?.optJSONArray("images")
        if (imagesArr != null) {
            for (i in 0 until imagesArr.length()) {
                val item = imagesArr.opt(i)
                when (item) {
                    is String -> if (item.isNotBlank()) urls.add(item)
                    is JSONObject -> {
                        val u = item.optString("url", "")
                        if (u.isNotBlank()) urls.add(u)
                    }
                }
            }
        }

        if (urls.isEmpty()) {
            val infosArr = data?.optJSONArray("image_infos") ?: data?.optJSONArray("comic_images")
            if (infosArr != null) {
                for (i in 0 until infosArr.length()) {
                    val obj = infosArr.optJSONObject(i) ?: continue
                    val u = obj.optString("url", "")
                    if (u.isNotBlank()) urls.add(u)
                }
            }
        }

        if (urls.isEmpty()) {
            val reason = data?.optString("can_view_reason_msg", "")?.takeIf { it.isNotBlank() }
                ?: body.optString("message", body.optString("msg", "")).takeIf { it.isNotBlank() }
                ?: "Chapter terkunci atau tidak memiliki gambar"
            throw Exception("Gagal mengambil gambar chapter Kuaikan (comic_id=$comicId): $reason")
        }

        return urls
    }

    fun scrapeChapterImages(
        context: Context?,
        cacheDir: File?,
        url: String,
        cookie: String = ""
    ): List<String> {
        var topicId = 0L
        var comicId = 0L

        val uri = try { Uri.parse(url) } catch (_: Exception) { null }
        val fragment = uri?.fragment ?: ""

        if (fragment.isNotBlank()) {
            for (pair in fragment.split("&")) {
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    val k = parts[0].trim()
                    val v = parts[1].trim()
                    if (k == "topic_id") topicId = v.toLongOrNull() ?: 0L
                    if (k == "comic_id") comicId = v.toLongOrNull() ?: 0L
                }
            }
        }

        if (topicId == 0L && uri != null) {
            topicId = uri.getQueryParameter("topic_id")?.toLongOrNull() ?: 0L
        }
        if (comicId == 0L && uri != null) {
            comicId = uri.getQueryParameter("comic_id")?.toLongOrNull() ?: 0L
        }

        if (comicId == 0L) {
            val comicMatcher = Pattern.compile("""comic/(\d+)""", Pattern.CASE_INSENSITIVE).matcher(url)
            if (comicMatcher.find()) {
                comicId = comicMatcher.group(1)?.toLongOrNull() ?: 0L
            }
        }

        if (comicId == 0L) {
            throw IllegalArgumentException("URL KuaikanManhua tidak memiliki comic_id yang valid: $url")
        }

        return getChapterImages(
            topicId = topicId,
            comicId = comicId,
            context = context,
            cacheDir = cacheDir,
            cookieOverride = cookie
        )
    }
}
