package com.astral.stitchapp.rawloader

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class Patch(
    val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val script: String
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("type", type)
            put("baseUrl", baseUrl)
            put("script", script)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): Patch? {
            val id = json.optString("id", "").takeIf { it.isNotBlank() } ?: return null
            val name = json.optString("name", id)
            val type = json.optString("type", id)
            val baseUrl = json.optString("baseUrl", "")
            val script = json.optString("script", "")
            return Patch(id = id, name = name, type = type, baseUrl = baseUrl, script = script)
        }

        fun parseAsp(content: String): Patch? {
            return try {
                val json = JSONObject(content)
                fromJsonObject(json)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}

object PatchManager {
    private const val PREFS_NAME = "stitch_patches"
    private const val KEY_PATCHES = "installed_patches"

    fun loadAll(context: Context): List<Patch> {
        initDefaultPatches(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PATCHES, null) ?: return emptyList()
        val list = mutableListOf<Patch>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val p = Patch.fromJsonObject(arr.getJSONObject(i))
                if (p != null) list.add(p)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getPatch(context: Context, typeOrId: String): Patch? {
        return loadAll(context).find { it.id.equals(typeOrId, ignoreCase = true) || it.type.equals(typeOrId, ignoreCase = true) }
    }

    fun savePatch(context: Context, patch: Patch) {
        val current = loadAll(context).toMutableList()
        current.removeAll { it.id.equals(patch.id, ignoreCase = true) }
        current.add(patch)
        persist(context, current)
    }

    fun deletePatch(context: Context, patchId: String) {
        val current = loadAll(context).toMutableList()
        current.removeAll { it.id.equals(patchId, ignoreCase = true) }
        persist(context, current)
    }

    fun importFromStream(context: Context, inputStream: InputStream): Patch? {
        return try {
            val content = inputStream.bufferedReader().use { it.readText() }
            val patch = Patch.parseAsp(content)
            if (patch != null) {
                savePatch(context, patch)
            }
            patch
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun importFromUri(context: Context, uri: Uri): Patch? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                importFromStream(context, ins)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun persist(context: Context, list: List<Patch>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJsonObject()) }
        prefs.edit().putString(KEY_PATCHES, arr.toString()).apply()
    }

    private fun initDefaultPatches(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initialized = prefs.getBoolean("default_initialized_v3", false)
        if (initialized) return

        try {
            val assetManager = context.assets
            val patchFiles = assetManager.list("patches") ?: emptyArray()
            val list = mutableListOf<Patch>()
            for (fName in patchFiles) {
                if (fName.endsWith(".asp")) {
                    assetManager.open("patches/$fName").use { ins ->
                        val content = ins.bufferedReader().readText()
                        val p = Patch.parseAsp(content)
                        if (p != null) list.add(p)
                    }
                }
            }
            val current = mutableListOf<Patch>()
            val existingStr = prefs.getString(KEY_PATCHES, null)
            if (existingStr != null) {
                try {
                    val arr = JSONArray(existingStr)
                    for (i in 0 until arr.length()) {
                        val p = Patch.fromJsonObject(arr.getJSONObject(i))
                        if (p != null && !p.id.equals("mangago", true) && !p.id.equals("comix", true) && !p.type.equals("mangago", true) && !p.type.equals("comix", true)) {
                            current.add(p)
                        }
                    }
                } catch (_: Exception) {}
            }
            list.forEach { defaultP ->
                if (current.none { it.id.equals(defaultP.id, ignoreCase = true) }) {
                    current.add(defaultP)
                }
            }
            persist(context, current)
            prefs.edit().putBoolean("default_initialized_v3", true).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
