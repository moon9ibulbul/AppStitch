package com.astral.stitchapp.rawloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.astral.stitchapp.SmartStitcher
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

data class QueueItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val status: String = "pending",
    val progress: Double = 0.0,
    val addedAt: Long = System.currentTimeMillis(),
    val type: String = "ridi",
    val cookie: String = "",
    val preScrapedImages: List<String> = emptyList(),
    val retryCount: Int = 0
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("url", url)
            put("title", title)
            put("status", status)
            put("progress", progress)
            put("added_at", addedAt)
            put("type", type)
            put("cookie", cookie)
            put("pre_scraped_images", JSONArray(preScrapedImages))
            put("retry_count", retryCount)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): QueueItem {
            val scraped = mutableListOf<String>()
            val arr = json.optJSONArray("pre_scraped_images")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    scraped.add(arr.getString(i))
                }
            }
            return QueueItem(
                id = json.optString("id", UUID.randomUUID().toString()),
                url = json.optString("url", ""),
                title = json.optString("title", ""),
                status = json.optString("status", "pending"),
                progress = json.optDouble("progress", 0.0),
                addedAt = json.optLong("added_at", System.currentTimeMillis()),
                type = json.optString("type", "ridi"),
                cookie = json.optString("cookie", ""),
                preScrapedImages = scraped,
                retryCount = json.optInt("retry_count", 0)
            )
        }
    }
}

object BatoEngine {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    private val queueMutex = Mutex()

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    private fun getQueueFile(cacheDir: File): File {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val file = File(cacheDir, "queue.json")
        if (!file.exists()) {
            file.writeText("[]")
        }
        return file
    }

    private fun loadQueue(cacheDir: File): MutableList<QueueItem> {
        val file = getQueueFile(cacheDir)
        val list = mutableListOf<QueueItem>()
        try {
            val content = file.readText()
            val arr = JSONArray(content)
            for (i in 0 until arr.length()) {
                list.add(QueueItem.fromJsonObject(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {}
        return list
    }

    private fun saveQueue(cacheDir: File, queue: List<QueueItem>) {
        val file = getQueueFile(cacheDir)
        val arr = JSONArray()
        queue.forEach { arr.put(it.toJsonObject()) }
        file.writeText(arr.toString(2))
    }

    suspend fun getQueueJson(cacheDir: File): String = queueMutex.withLock {
        val list = loadQueue(cacheDir)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJsonObject()) }
        arr.toString()
    }

    suspend fun addUrl(cacheDir: File, url: String, sourceType: String = "ridi", cookie: String = ""): JSONObject = queueMutex.withLock {
        val queue = loadQueue(cacheDir)
        var added = 0

        try {
            if (sourceType == "ridi") {
                val bookId = getRidiBookId(url)
                if (bookId.isEmpty()) {
                    return JSONObject().apply { put("error", "Invalid Ridi URL (No Book ID found)") }
                }
                var title = "Ridi Book $bookId"
                try {
                    val html = fetchHtml(url, cookie)
                    val extracted = extractTitleFromHtml(html)
                    if (extracted.isNotBlank()) title = extracted
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val existingRidi = queue.filter { it.url == url }
                existingRidi.forEach { oldItem ->
                    if (oldItem.status == "done" || oldItem.status == "failed") {
                        val oldPrescrapedDir = File(cacheDir, "prescraped_cache/${oldItem.id}")
                        if (oldPrescrapedDir.exists()) oldPrescrapedDir.deleteRecursively()
                        queue.remove(oldItem)
                    }
                }
                if (queue.none { it.url == url }) {
                    queue.add(QueueItem(url = url, title = title, type = "ridi", cookie = cookie))
                    added = 1
                }
            } else if (sourceType == "naver") {
                val info = getNaverChapterInfo(url)
                val title = info.optString("title", "Naver Chapter")
                val existingNaver = queue.filter { it.url == url }
                existingNaver.forEach { oldItem ->
                    if (oldItem.status == "done" || oldItem.status == "failed") {
                        val oldPrescrapedDir = File(cacheDir, "prescraped_cache/${oldItem.id}")
                        if (oldPrescrapedDir.exists()) oldPrescrapedDir.deleteRecursively()
                        queue.remove(oldItem)
                    }
                }
                if (queue.none { it.url == url }) {
                    queue.add(QueueItem(url = url, title = title, type = "naver"))
                    added = 1
                }
            }

            if (added > 0) {
                saveQueue(cacheDir, queue)
            }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message ?: "Error adding URL") }
        }

        return JSONObject().apply { put("added", added) }
    }

    suspend fun addDirectJob(cacheDir: File, title: String, images: List<String>, cookie: String = "", sourceType: String = "ridi"): JSONObject = queueMutex.withLock {
        val queue = loadQueue(cacheDir)

        val sanitizedTitle = sanitizeFilename(title)
        val existingIndices = queue.indices.filter {
            val qTitle = queue[it].title
            qTitle.equals(title, ignoreCase = true) || sanitizeFilename(qTitle).equals(sanitizedTitle, ignoreCase = true)
        }
        for (i in existingIndices.reversed()) {
            val oldItem = queue[i]
            if (oldItem.status == "done" || oldItem.status == "failed") {
                val oldPrescrapedDir = File(cacheDir, "prescraped_cache/${oldItem.id}")
                if (oldPrescrapedDir.exists()) oldPrescrapedDir.deleteRecursively()
                queue.removeAt(i)
            }
        }

        val jobId = UUID.randomUUID().toString()
        val fakeUrl = "direct://${sanitizeFilename(title)}/${System.currentTimeMillis()}"

        val processedImages = mutableListOf<String>()
        val prescrapedDir = File(cacheDir, "prescraped_cache/$jobId")

        images.forEachIndexed { idx, img ->
            if (img.startsWith("data:")) {
                if (!prescrapedDir.exists()) prescrapedDir.mkdirs()
                val ext = when {
                    img.startsWith("data:image/png") -> ".png"
                    img.startsWith("data:image/webp") -> ".webp"
                    img.startsWith("data:image/jxl") -> ".jxl"
                    else -> ".jpg"
                }
                val filename = String.format(Locale.ROOT, "prescraped_%04d%s", idx + 1, ext)
                val targetFile = File(prescrapedDir, filename)
                try {
                    val base64Data = img.substringAfter("base64,")
                    val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    targetFile.writeBytes(bytes)
                    processedImages.add(targetFile.absolutePath)
                } catch (e: Exception) {
                    e.printStackTrace()
                    processedImages.add(img)
                }
            } else {
                processedImages.add(img)
            }
        }

        queue.add(
            QueueItem(
                id = jobId,
                url = fakeUrl,
                title = title,
                type = sourceType,
                cookie = cookie,
                preScrapedImages = processedImages
            )
        )
        saveQueue(cacheDir, queue)
        return JSONObject().apply { put("added", 1) }
    }

    suspend fun removeItem(cacheDir: File, itemId: String) = queueMutex.withLock {
        val queue = loadQueue(cacheDir)
        queue.removeAll { it.id == itemId }
        val prescrapedDir = File(cacheDir, "prescraped_cache/$itemId")
        if (prescrapedDir.exists()) prescrapedDir.deleteRecursively()
        saveQueue(cacheDir, queue)
    }

    suspend fun retryItem(cacheDir: File, itemId: String) = queueMutex.withLock {
        val queue = loadQueue(cacheDir)
        val idx = queue.indexOfFirst { it.id == itemId }
        if (idx != -1) {
            queue[idx] = queue[idx].copy(status = "pending", progress = 0.0)
            saveQueue(cacheDir, queue)
        }
    }

    suspend fun pauseItem(cacheDir: File, itemId: String) = queueMutex.withLock {
        val queue = loadQueue(cacheDir)
        val idx = queue.indexOfFirst { it.id == itemId }
        if (idx != -1) {
            queue[idx] = queue[idx].copy(status = "paused")
            saveQueue(cacheDir, queue)
        }
    }

    suspend fun clearCompleted(cacheDir: File) = queueMutex.withLock {
        val queue = loadQueue(cacheDir)
        val doneItems = queue.filter { it.status == "done" }
        doneItems.forEach { item ->
            val prescrapedDir = File(cacheDir, "prescraped_cache/${item.id}")
            if (prescrapedDir.exists()) prescrapedDir.deleteRecursively()
        }
        queue.removeAll { it.status == "done" }
        saveQueue(cacheDir, queue)
    }

    private suspend fun getAndLockNextPending(cacheDir: File): QueueItem? = queueMutex.withLock {
        val queue = loadQueue(cacheDir)
        val idx = queue.indexOfFirst { it.status == "pending" }
        if (idx != -1) {
            val locked = queue[idx].copy(status = "initializing")
            queue[idx] = locked
            saveQueue(cacheDir, queue)
            return locked
        }
        return null
    }

    private suspend fun updateStatus(cacheDir: File, itemId: String, status: String, progress: Double = 0.0) = queueMutex.withLock {
        val queue = loadQueue(cacheDir)
        val idx = queue.indexOfFirst { it.id == itemId }
        if (idx != -1) {
            if (queue[idx].status != "paused" || status == "paused") {
                var updatedItem = queue[idx].copy(status = status, progress = progress)
                if (status == "done" || status == "failed") {
                    updatedItem = updatedItem.copy(preScrapedImages = emptyList())
                    val prescrapedDir = File(cacheDir, "prescraped_cache/$itemId")
                    if (prescrapedDir.exists()) prescrapedDir.deleteRecursively()
                }
                queue[idx] = updatedItem
                saveQueue(cacheDir, queue)
            }
        }
    }

    private suspend fun checkAction(cacheDir: File, itemId: String): String = queueMutex.withLock {
        val queue = loadQueue(cacheDir)
        val item = queue.find { it.id == itemId } ?: return "removed"
        if (item.status == "paused") return "paused"
        return "ok"
    }

    private fun getRidiBookId(url: String): String {
        val matcher = Pattern.compile("/(?:books|webtoon)/(\\d{6,})").matcher(url)
        return if (matcher.find()) matcher.group(1) ?: "" else ""
    }

    private fun extractTitleFromHtml(html: String): String {
        val matcher = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (matcher.find()) {
            var title = matcher.group(1) ?: ""
            title = title.replace(" - Read Free Manga Online", "")
                .replace("Read Free Manga Online", "")
                .replace(" - Ridibooks", "")
                .replace(Regex("\\[.*?\\]$"), "")
                .trim()
            return sanitizeFilename(title)
        }
        return ""
    }

    private fun fetchHtml(urlStr: String, cookie: String = ""): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30000
            readTimeout = 30000
            setRequestProperty("User-Agent", USER_AGENT)
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    fun getNaverChapterInfo(urlStr: String): JSONObject {
        val urlsToTry = mutableListOf(urlStr)
        if (urlStr.contains("comic.naver.com") && !urlStr.contains("m.comic.naver.com")) {
            urlsToTry.add(urlStr.replace("https://comic.naver.com", "https://m.comic.naver.com"))
        } else if (urlStr.contains("m.comic.naver.com")) {
            urlsToTry.add(urlStr.replace("https://m.comic.naver.com", "https://comic.naver.com"))
        }

        for (u in urlsToTry) {
            try {
                val html = fetchHtml(u)
                if (html.isNotBlank()) {
                    var title = ""
                    val ogMatcher = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html)
                    if (ogMatcher.find()) {
                        title = ogMatcher.group(1) ?: ""
                    } else {
                        val titleMatcher = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (titleMatcher.find()) {
                            title = titleMatcher.group(1) ?: ""
                        }
                    }
                    if (title.isNotBlank()) {
                        title = title.replace(" - Naver Webtoon", "")
                            .replace(" - 네이버 웹툰", "")
                            .replace(" :: 네이버 웹툰", "")
                            .replace(" : 네이버 웹툰", "")
                            .trim()
                        return JSONObject().apply {
                            put("title", title)
                            put("url", urlStr)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return JSONObject().apply {
            put("title", "Naver Webtoon Chapter")
            put("url", urlStr)
        }
    }

    fun getNaverImages(urlStr: String): List<String> {
        val urlsToTry = mutableListOf(urlStr)
        if (urlStr.contains("comic.naver.com") && !urlStr.contains("m.comic.naver.com")) {
            urlsToTry.add(urlStr.replace("https://comic.naver.com", "https://m.comic.naver.com"))
        } else if (urlStr.contains("m.comic.naver.com")) {
            urlsToTry.add(urlStr.replace("https://m.comic.naver.com", "https://comic.naver.com"))
        }

        val pattern = Pattern.compile("(https?://image-comic\\.pstatic\\.net/(?:mobilewebimg|webtoon)/[^\"'\\s]+\\.(?:jpg|png|jpeg))", Pattern.CASE_INSENSITIVE)
        val ignoreKeywords = listOf("thumbnail", "banner", "display_ad", "agerate", "logo", "titledescimage", "bg_")

        for (u in urlsToTry) {
            try {
                val html = fetchHtml(u)
                if (html.isBlank()) continue

                val matcher = pattern.matcher(html)
                val images = mutableListOf<String>()
                val seen = mutableSetOf<String>()

                while (matcher.find()) {
                    val imgUrl = matcher.group(1) ?: continue
                    if (imgUrl in seen) continue
                    if (ignoreKeywords.any { imgUrl.contains(it, ignoreCase = true) }) continue
                    seen.add(imgUrl)
                    images.add(imgUrl)
                }

                if (images.isNotEmpty()) {
                    return images
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return emptyList()
    }

    private fun downloadImage(urlStr: String, destDir: File, idx: Int, cookie: String?, referer: String?): File {
        if (File(urlStr).exists()) {
            val srcFile = File(urlStr)
            val ext = if (srcFile.extension.isNotBlank()) ".${srcFile.extension}" else ".jpg"
            val filename = String.format(Locale.ROOT, "img_%04d%s", idx, ext)
            val target = File(destDir, filename)
            if (srcFile.absolutePath != target.absolutePath) {
                srcFile.copyTo(target, overwrite = true)
            }
            return fixImageExtension(target)
        }

        if (urlStr.startsWith("data:")) {
            val ext = when {
                urlStr.startsWith("data:image/png") -> ".png"
                urlStr.startsWith("data:image/webp") -> ".webp"
                urlStr.startsWith("data:image/jxl") -> ".jxl"
                else -> ".jpg"
            }
            val filename = String.format(Locale.ROOT, "img_%04d%s", idx, ext)
            val target = File(destDir, filename)
            if (target.exists() && target.length() > 0) {
                return fixImageExtension(target)
            }
            val base64Data = urlStr.substringAfter("base64,")
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            target.writeBytes(bytes)
            return fixImageExtension(target)
        }

        val uri = Uri.parse(urlStr)
        val cleanUrlStr = uri.buildUpon().fragment(null).build().toString()
        val pathPart = uri.path ?: ""
        var ext = if (pathPart.contains(".")) "." + pathPart.substringAfterLast('.') else ".jpg"
        if (ext.length > 5) ext = ".jpg"

        val filename = String.format(Locale.ROOT, "img_%04d%s", idx, ext)
        val target = File(destDir, filename)

        if (target.exists() && target.length() > 0) {
            return fixImageExtension(target)
        }

        var lastException: Exception? = null
        for (attempt in 0 until 3) {
            try {
                val conn = (URL(cleanUrlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 30000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", USER_AGENT)
                    if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                }

                val bytes = conn.inputStream.use { it.readBytes() }
                target.writeBytes(bytes)
                return fixImageExtension(target)
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: Exception("Failed to download image $urlStr")
    }

    private fun fixImageExtension(file: File): File {
        if (!file.exists()) return file
        val header = ByteArray(32)
        val read = try {
            file.inputStream().use { ins -> ins.read(header) }
        } catch (_: Exception) { return file }

        val headerStr = if (read >= 0) String(header, 0, read, Charsets.US_ASCII) else ""
        val isWebp = headerStr.length >= 12 && headerStr.startsWith("RIFF") && headerStr.substring(8, 12) == "WEBP"
        val isFakeJpg = headerStr.contains("Fake jpg")
        val isAvif = headerStr.length >= 12 && headerStr.substring(4, 12) == "ftypavif"
        val isJxl = (read >= 2 && header[0] == 0xFF.toByte() && header[1] == 0x0A.toByte()) ||
                (read >= 12 && (headerStr.substring(4, 8) == "JXL " || headerStr.contains("ftypjxl")))

        val ext = file.extension.lowercase(Locale.ROOT)
        val isJpgExt = ext in setOf("jpg", "jpeg", "jfif")

        if (isJpgExt && (isFakeJpg || isWebp || isAvif || isJxl)) {
            val targetExt = if (isJxl) "jxl" else "webp"
            val newFile = File(file.parentFile, file.nameWithoutExtension + "." + targetExt)
            if (file.renameTo(newFile)) {
                return newFile
            }
        }
        return file
    }

    private fun copyRectPixels(src: Bitmap, srcRect: Rect, dst: Bitmap, dstRect: Rect) {
        val safeSrcLeft = srcRect.left.coerceIn(0, src.width)
        val safeSrcTop = srcRect.top.coerceIn(0, src.height)
        val safeSrcRight = srcRect.right.coerceIn(safeSrcLeft, src.width)
        val safeSrcBottom = srcRect.bottom.coerceIn(safeSrcTop, src.height)

        val safeDstLeft = dstRect.left.coerceIn(0, dst.width)
        val safeDstTop = dstRect.top.coerceIn(0, dst.height)
        val safeDstRight = dstRect.right.coerceIn(safeDstLeft, dst.width)
        val safeDstBottom = dstRect.bottom.coerceIn(safeDstTop, dst.height)

        val srcW = safeSrcRight - safeSrcLeft
        val srcH = safeSrcBottom - safeSrcTop
        val dstW = safeDstRight - safeDstLeft
        val dstH = safeDstBottom - safeDstTop

        val w = minOf(srcW, dstW)
        val h = minOf(srcH, dstH)

        if (w <= 0 || h <= 0) return

        val buffer = IntArray(w * h)
        src.getPixels(buffer, 0, w, safeSrcLeft, safeSrcTop, w, h)
        dst.setPixels(buffer, 0, w, safeDstLeft, safeDstTop, w, h)
    }


    fun unscrambleLezhinImage(path: File, shuffleKey: String) {
        try {
            val bitmap = BitmapFactory.decodeFile(path.absolutePath) ?: return
            val w = bitmap.width
            val h = bitmap.height
            val G = 5
            val total = G * G
            var seed = shuffleKey.toLongOrNull() ?: 0L

            fun randVal(maxVal: Int): Int {
                seed = seed xor (seed ushr 12)
                seed = seed xor (seed shl 25)
                seed = seed xor (seed ushr 27)
                val res = (seed ushr 32) % maxVal
                return res.toInt()
            }

            val arr = IntArray(total) { it }
            for (i in 0 until total) {
                val r = randVal(total)
                val tmp = arr[i]
                arr[i] = arr[r]
                arr[r] = tmp
            }

            val tw = w / G
            val th = h / G

            fun getArea(idx: Int): Rect? {
                if (idx < total) return Rect((idx % G) * tw, (idx / G) * th, (idx % G) * tw + tw, (idx / G) * th + th)
                if (idx == total) {
                    if (w % G == 0) return null
                    return Rect(w - w % G, 0, w, h)
                }
                if (idx == total + 1) {
                    if (h % G == 0) return null
                    return Rect(0, h - h % G, w - w % G, h)
                }
                return null
            }

            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            for ((k, v) in arr.withIndex()) {
                val dstArea = getArea(k)
                val srcArea = getArea(v)
                if (dstArea != null && srcArea != null) {
                    copyRectPixels(bitmap, srcArea, result, dstArea)
                }
            }

            for (idx in listOf(total, total + 1)) {
                val area = getArea(idx)
                if (area != null) {
                    copyRectPixels(bitmap, area, result, area)
                }
            }

            path.outputStream().use { outs ->
                val ext = path.extension.lowercase(Locale.ROOT)
                if (ext == "png") {
                    result.compress(Bitmap.CompressFormat.PNG, 100, outs)
                } else {
                    result.compress(Bitmap.CompressFormat.JPEG, 100, outs)
                }
            }
            bitmap.recycle()
            result.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun unscrambleBomtoonImage(path: File, scrambleDataJson: String) {
        try {
            val json = JSONObject(scrambleDataJson)
            val scrambleIndex = json.optJSONArray("scrambleIndex") ?: return

            val mWidth = json.optInt("width", 0)
            val mHeight = json.optInt("height", 0)
            val mDefaultHeight = json.optInt("defaultHeight", 0)

            val bitmap = BitmapFactory.decodeFile(path.absolutePath) ?: return
            val width = if (mWidth > 0) mWidth else bitmap.width
            val height = if (mHeight > 0) mHeight else bitmap.height
            val defaultHeight = if (mDefaultHeight > 0) mDefaultHeight else bitmap.height

            val cols = 4
            val unitWidth = width / cols
            val unitHeight = defaultHeight / cols

            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            for (r in 0 until scrambleIndex.length()) {
                val i = scrambleIndex.getInt(r)
                val sx = (r % cols) * unitWidth
                val sy = (r / cols) * unitHeight
                val dx = (i % cols) * unitWidth
                val dy = (i / cols) * unitHeight

                val srcRect = Rect(sx, sy, sx + unitWidth, sy + unitHeight)
                val dstRect = Rect(dx, dy, dx + unitWidth, dy + unitHeight)
                copyRectPixels(bitmap, srcRect, result, dstRect)
            }

            path.outputStream().use { outs ->
                val ext = path.extension.lowercase(Locale.ROOT)
                if (ext == "png") {
                    result.compress(Bitmap.CompressFormat.PNG, 100, outs)
                } else {
                    result.compress(Bitmap.CompressFormat.JPEG, 100, outs)
                }
            }
            bitmap.recycle()
            result.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    suspend fun processNextItem(context: Context, stitchParamsJson: String): JSONObject = processNextItem(context.cacheDir, stitchParamsJson, context)

    suspend fun processNextItem(cacheDir: File, stitchParamsJson: String, context: Context? = null): JSONObject = withContext(Dispatchers.IO) {
        val item = getAndLockNextPending(cacheDir)
            ?: return@withContext JSONObject().apply { put("status", "empty") }

        val params = JSONObject(stitchParamsJson)
        val autoRetry = params.optBoolean("autoRetry", true)
        val skipStitching = params.optBoolean("skipStitching", false)
        val itemId = item.id
        val url = item.url
        val sourceType = item.type

        val dlDir = File(cacheDir, "temp_dl/$itemId")
        val outputParent = File(cacheDir, "temp_out/$itemId")
        val titleSafe = sanitizeFilename(item.title)
        val outputDir = File(outputParent, titleSafe)

        try {
            var images = mutableListOf<String>()
            var referer: String? = null

            if (sourceType == "naver") {
                images.addAll(getNaverImages(url))
                referer = "https://comic.naver.com/"
            } else {
                if (item.preScrapedImages.isNotEmpty()) {
                    images.addAll(item.preScrapedImages)
                } else {
                    throw Exception("Source $sourceType requires pre-scraped images (Scrape button)")
                }

                referer = when (sourceType) {
                    "ridi" -> "https://ridibooks.com/"
                    "bomtoon" -> "https://www.bomtoon.com/"
                    "lezhin" -> "https://www.lezhin.com/"
                    "mrblue" -> "https://www.mrblue.com/"
                    else -> {
                        if (context != null) {
                            PatchManager.getPatch(context, sourceType)?.baseUrl.takeIf { !it.isNullOrBlank() }
                        } else null
                    }
                }
            }

            if (images.isEmpty()) {
                throw Exception("No images found")
            }

            if (dlDir.exists()) dlDir.deleteRecursively()
            dlDir.mkdirs()
            val total = images.size

            // 1. DOWNLOADING PHASE
            updateStatus(cacheDir, itemId, "downloading", 0.0)
            val localFiles = java.util.Collections.synchronizedList(mutableListOf<Pair<String, File>>())
            val skippedCount = AtomicInteger(0)
            val completedCount = AtomicInteger(0)
            val downloadSemaphore = Semaphore(6)

            coroutineScope {
                val jobs = images.mapIndexed { i, imgUrl ->
                    async(Dispatchers.IO) {
                        val action = checkAction(cacheDir, itemId)
                        if (action == "paused" || action == "removed") return@async action

                        val targetPath = downloadSemaphore.withPermit {
                            downloadImage(imgUrl, dlDir, i + 1, item.cookie, referer)
                        }

                        if (sourceType == "naver") {
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(targetPath.absolutePath, opts)
                            if (opts.outWidth > 0 && opts.outWidth < 500) {
                                targetPath.delete()
                                skippedCount.incrementAndGet()
                                val done = completedCount.incrementAndGet()
                                if (done % 5 == 0 || done == total) {
                                    updateStatus(cacheDir, itemId, "downloading", done.toDouble() / total)
                                }
                                return@async "ok"
                            }
                        }

                        localFiles.add(Pair(imgUrl, targetPath))

                        val done = completedCount.incrementAndGet()
                        if (done % 5 == 0 || done == total) {
                            updateStatus(cacheDir, itemId, "downloading", done.toDouble() / total)
                        }
                        "ok"
                    }
                }

                val results = jobs.awaitAll()
                if (results.contains("paused")) return@coroutineScope JSONObject().apply { put("status", "paused") }
                if (results.contains("removed")) {
                    if (dlDir.exists()) dlDir.deleteRecursively()
                    return@coroutineScope JSONObject().apply { put("status", "removed") }
                }
                null
            }?.let { return@withContext it }

            localFiles.sortBy { it.second.name }

            // 2. UNSCRAMBLING PHASE
            val needsUnscramble = sourceType in listOf("bomtoon", "lezhin") ||
                images.any { Uri.parse(it).fragment?.contains("scramble=") == true || Uri.parse(it).fragment?.contains("shuffleKey=") == true }

            if (needsUnscramble) {
                updateStatus(cacheDir, itemId, "unscrambling", 0.0)
                val unscrambleCompleted = AtomicInteger(0)
                val unscrambleSemaphore = Semaphore(maxOf(2, Runtime.getRuntime().availableProcessors()))

                coroutineScope {
                    val jobs = localFiles.map { pair ->
                        async(Dispatchers.Default) {
                            val (imgUrl, targetPath) = pair
                            val action = checkAction(cacheDir, itemId)
                            if (action == "paused" || action == "removed") return@async action

                            val fragment = Uri.parse(imgUrl).fragment
                            if (!fragment.isNullOrBlank()) {
                                val paramsUnscram = mutableMapOf<String, String>()
                                fragment.split("&").forEach { itemUnscram ->
                                    if (itemUnscram.contains("=")) {
                                        val parts = itemUnscram.split("=", limit = 2)
                                        paramsUnscram[parts[0]] = parts[1]
                                    }
                                }

                                unscrambleSemaphore.withPermit {
                                    if (sourceType == "bomtoon" || paramsUnscram.containsKey("scramble")) {
                                        val scrambleData = paramsUnscram["scramble"]
                                        if (!scrambleData.isNullOrBlank()) {
                                            val decodedData = URLDecoder.decode(scrambleData, "UTF-8")
                                            unscrambleBomtoonImage(targetPath, decodedData)
                                        }
                                    } else if (sourceType == "lezhin" || paramsUnscram.containsKey("shuffleKey")) {
                                        val shuffleKey = paramsUnscram["shuffleKey"]
                                        if (!shuffleKey.isNullOrBlank()) {
                                            unscrambleLezhinImage(targetPath, shuffleKey)
                                        }
                                    }
                                }
                            }

                            val done = unscrambleCompleted.incrementAndGet()
                            if (done % 5 == 0 || done == localFiles.size) {
                                updateStatus(cacheDir, itemId, "unscrambling", done.toDouble() / localFiles.size)
                            }
                            "ok"
                        }
                    }

                    val results = jobs.awaitAll()
                    if (results.contains("paused")) return@coroutineScope JSONObject().apply { put("status", "paused") }
                    if (results.contains("removed")) {
                        if (dlDir.exists()) dlDir.deleteRecursively()
                        return@coroutineScope JSONObject().apply { put("status", "removed") }
                    }
                    null
                }?.let { return@withContext it }
            }

            // Verify integrity
            val finalFiles = dlDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val expectedCount = images.size - skippedCount.get()
            if (finalFiles.size < expectedCount) {
                throw Exception("Incomplete download: Expected $expectedCount, got ${finalFiles.size}")
            }

            // 3. STITCHING PHASE
            if (!skipStitching) {
                updateStatus(cacheDir, itemId, "stitching", 0.0)
            }
            if (outputParent.exists()) outputParent.deleteRecursively()
            outputDir.mkdirs()

            val finalPath = SmartStitcher.runAsync(
                inputFolder = dlDir.absolutePath,
                splitHeight = params.optInt("splitHeight", 5000),
                outputFilesType = params.optString("outputType", ".png"),
                batchMode = false,
                widthEnforceType = params.optInt("widthEnforce", 0),
                customWidth = params.optInt("customWidth", 720),
                sensitivity = params.optInt("sensitivity", 90),
                ignorablePixels = params.optInt("ignorable", 0),
                scanLineStep = params.optInt("scanStep", 5),
                lowRam = params.optBoolean("lowRam", false),
                unitImages = 20,
                outputFolder = outputDir.absolutePath,
                filenameTemplate = null,
                zipOutput = params.optString("packaging") == "ZIP",
                pdfOutput = params.optString("packaging") == "PDF",
                pdfPassword = params.optString("pdfPassword", "").takeIf { it.isNotBlank() },
                markDone = false,
                splitMode = params.optInt("splitMode", 2),
                quality = params.optInt("quality", 100),
                skipStitching = skipStitching
            )

            dlDir.deleteRecursively()
            updateStatus(cacheDir, itemId, "done", 1.0)

            return@withContext JSONObject().apply {
                put("status", "success")
                put("path", finalPath)
                put("title", item.title)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            var newStatus = "failed"
            if (autoRetry) {
                queueMutex.withLock {
                    val queue = loadQueue(cacheDir)
                    val idx = queue.indexOfFirst { it.id == itemId }
                    if (idx != -1) {
                        val qItem = queue[idx]
                        if (qItem.retryCount < 3) {
                            queue[idx] = qItem.copy(retryCount = qItem.retryCount + 1, status = "pending")
                            newStatus = "pending"
                        } else {
                            val prescrapedDir = File(cacheDir, "prescraped_cache/$itemId")
                            if (prescrapedDir.exists()) prescrapedDir.deleteRecursively()
                            queue[idx] = qItem.copy(status = "failed", preScrapedImages = emptyList())
                        }
                        saveQueue(cacheDir, queue)
                    }
                }
            } else {
                updateStatus(cacheDir, itemId, "failed", 0.0)
            }

            return@withContext JSONObject().apply {
                put("error", e.message ?: "Processing error")
                put("status", newStatus)
            }
        }
    }
}
