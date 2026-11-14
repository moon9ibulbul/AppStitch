package com.astral.stitchapp.rawloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.Locale

object GeckoCookieExtractor {
    private const val TAG = "GeckoCookieExtractor"

    /**
     * Recursively scans common GeckoView directories for cookies.sqlite.
     * Sorts the results by lastModified descending so the most recently updated profile database is first.
     */
    fun findGeckoCookieDatabases(context: Context): List<File> {
        val found = mutableListOf<File>()
        val searchedDirs = mutableSetOf<String>()

        fun scanDir(dir: File?, maxDepth: Int) {
            if (dir == null || !dir.exists() || !dir.isDirectory || maxDepth < 0) return
            val canonicalPath = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
            if (searchedDirs.contains(canonicalPath)) return
            searchedDirs.add(canonicalPath)

            val files = dir.listFiles() ?: return
            for (f in files) {
                if (f.isFile && f.name == "cookies.sqlite") {
                    found.add(f)
                } else if (f.isDirectory && !f.name.startsWith("cache", ignoreCase = true)) {
                    scanDir(f, maxDepth - 1)
                }
            }
        }

        // 1. Files directory (standard Gecko profile location: files/mozilla/*.default/cookies.sqlite)
        val filesDir = context.filesDir
        val mozillaDir = File(filesDir, "mozilla")
        if (mozillaDir.exists()) {
            scanDir(mozillaDir, maxDepth = 4)
        }
        scanDir(filesDir, maxDepth = 3)

        // 2. Application dataDir (e.g. app_geckoview/ or other subdirectories)
        try {
            val dataDir = File(context.applicationInfo.dataDir)
            val appGeckoDir = File(dataDir, "app_geckoview")
            if (appGeckoDir.exists()) {
                scanDir(appGeckoDir, maxDepth = 4)
            }
            if (found.isEmpty()) {
                scanDir(dataDir, maxDepth = 3)
            }
        } catch (_: Exception) {}

        return found.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
    }

    /**
     * Extracts cookies from moz_cookies for domains matching the target URL or domain keywords.
     */
    fun extractCookies(context: Context, urlOrDomain: String? = null): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val dbFiles = findGeckoCookieDatabases(context)
        if (dbFiles.isEmpty()) {
            Log.d(TAG, "No Gecko cookies.sqlite found")
            return result
        }

        val domainKeywords = getDomainKeywords(urlOrDomain)
        Log.d(TAG, "Found ${dbFiles.size} Gecko cookie databases. Querying keywords: $domainKeywords")

        for (dbFile in dbFiles) {
            val cookiesFromDb = readCookiesFromDatabase(context, dbFile, domainKeywords)
            for ((k, v) in cookiesFromDb) {
                if (!result.containsKey(k) && v.isNotBlank()) {
                    result[k] = v
                }
            }
        }

        return result
    }

    private fun getDomainKeywords(urlOrDomain: String?): List<String> {
        if (urlOrDomain.isNullOrBlank()) return emptyList()
        val lower = urlOrDomain.lowercase(Locale.ROOT)
        return when {
            lower.contains("kakao") -> listOf("kakao", "daum")
            lower.contains("ridi") -> listOf("ridi")
            lower.contains("bomtoon") -> listOf("bomtoon")
            lower.contains("lezhin") -> listOf("lezhin")
            lower.contains("mrblue") -> listOf("mrblue")
            lower.contains("manta") -> listOf("manta")
            lower.contains("comix") -> listOf("comix")
            else -> {
                val host = try {
                    Uri.parse(urlOrDomain).host ?: urlOrDomain
                } catch (_: Exception) {
                    urlOrDomain
                }
                val parts = host.split(".").filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    listOf(parts.takeLast(2).joinToString("."))
                } else {
                    listOf(host)
                }
            }
        }
    }

    private fun readCookiesFromDatabase(context: Context, dbFile: File, domainKeywords: List<String>): Map<String, String> {
        // First try reading directly in read-only mode
        readDirect(dbFile, domainKeywords)?.let { return it }

        // If direct read fails (e.g. database locked by GeckoView), copy database + wal/shm snapshot to temp dir
        return readFromTempSnapshot(context, dbFile, domainKeywords)
    }

    private fun readDirect(dbFile: File, domainKeywords: List<String>): Map<String, String>? {
        if (!dbFile.exists() || !dbFile.canRead()) return null
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            return queryCookiesTable(db, domainKeywords)
        } catch (e: Exception) {
            return null
        } finally {
            try { db?.close() } catch (_: Exception) {}
        }
    }

    private fun readFromTempSnapshot(context: Context, dbFile: File, domainKeywords: List<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val tempDir = File(context.cacheDir, "gecko_cookie_snap_${System.currentTimeMillis()}").apply { mkdirs() }
        val tempDb = File(tempDir, "cookies.sqlite")
        val walFile = File(dbFile.parentFile, "${dbFile.name}-wal")
        val shmFile = File(dbFile.parentFile, "${dbFile.name}-shm")

        var db: SQLiteDatabase? = null
        try {
            dbFile.copyTo(tempDb, overwrite = true)
            if (walFile.exists()) {
                walFile.copyTo(File(tempDir, "cookies.sqlite-wal"), overwrite = true)
            }
            if (shmFile.exists()) {
                shmFile.copyTo(File(tempDir, "cookies.sqlite-shm"), overwrite = true)
            }

            db = SQLiteDatabase.openDatabase(tempDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val queried = queryCookiesTable(db, domainKeywords)
            result.putAll(queried)
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading Gecko cookies snapshot: ${e.message}", e)
        } finally {
            try { db?.close() } catch (_: Exception) {}
            try { tempDir.deleteRecursively() } catch (_: Exception) {}
        }
        return result
    }

    private fun queryCookiesTable(db: SQLiteDatabase, domainKeywords: List<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val tableCursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='moz_cookies'", null)
        val hasTable = tableCursor.use { it.moveToFirst() }
        if (!hasTable) return result

        val hasLastAccessed = try {
            db.rawQuery("PRAGMA table_info(moz_cookies)", null).use { pragmaCursor ->
                var found = false
                val colNameIdx = pragmaCursor.getColumnIndex("name")
                while (pragmaCursor.moveToNext()) {
                    if (colNameIdx >= 0 && pragmaCursor.getString(colNameIdx).equals("lastAccessed", ignoreCase = true)) {
                        found = true
                        break
                    }
                }
                found
            }
        } catch (_: Exception) { false }

        val selection: String?
        val selectionArgs: Array<String>?
        if (domainKeywords.isNotEmpty()) {
            selection = domainKeywords.joinToString(" OR ") { "host LIKE ?" }
            selectionArgs = domainKeywords.map { "%$it%" }.toTypedArray()
        } else {
            selection = null
            selectionArgs = null
        }

        val sql = "SELECT name, value, host FROM moz_cookies" +
                (if (selection != null) " WHERE $selection" else "") +
                (if (hasLastAccessed) " ORDER BY lastAccessed DESC" else "")

        db.rawQuery(sql, selectionArgs).use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            val valIdx = cursor.getColumnIndex("value")
            while (cursor.moveToNext()) {
                if (nameIdx >= 0 && valIdx >= 0) {
                    val name = cursor.getString(nameIdx)
                    val value = cursor.getString(valIdx)
                    if (!name.isNullOrBlank() && value != null && !result.containsKey(name)) {
                        result[name] = value
                    }
                }
            }
        }
        return result
    }

    /**
     * Extracts and returns cookies formatted as a single Cookie header string: "key1=val1; key2=val2"
     */
    fun extractCookieString(context: Context, urlOrDomain: String? = null): String {
        return extractCookies(context, urlOrDomain).entries.joinToString("; ") { (k, v) ->
            if (v.isBlank()) k else "$k=$v"
        }
    }

    /**
     * Intelligently merges an existing cookie string with newly captured cookies.
     * Prevents previously saved or user-pasted valid session tokens from being wiped out.
     */
    fun mergeCookieStrings(existing: String?, incoming: String?): String {
        val map = LinkedHashMap<String, String>()

        fun parse(str: String?) {
            if (str.isNullOrBlank()) return
            for (p in str.split(";")) {
                val trimmed = p.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.contains("=")) {
                    val k = trimmed.substringBefore("=").trim()
                    val v = trimmed.substringAfter("=").trim()
                    if (k.isNotEmpty()) {
                        map[k] = v
                    }
                } else {
                    map[trimmed] = ""
                }
            }
        }

        // First parse existing cookies as the base
        parse(existing)

        // Then overlay incoming cookies (updating or adding new keys)
        if (!incoming.isNullOrBlank()) {
            for (p in incoming.split(";")) {
                val trimmed = p.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.contains("=")) {
                    val k = trimmed.substringBefore("=").trim()
                    val v = trimmed.substringAfter("=").trim()
                    if (k.isNotEmpty()) {
                        if (v.isNotEmpty() || !map.containsKey(k)) {
                            map[k] = v
                        }
                    }
                } else if (!map.containsKey(trimmed)) {
                    map[trimmed] = ""
                }
            }
        }

        return map.entries.joinToString("; ") { (k, v) ->
            if (v.isBlank()) k else "$k=$v"
        }
    }
}
