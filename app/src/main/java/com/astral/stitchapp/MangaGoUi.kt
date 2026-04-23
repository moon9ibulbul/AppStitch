package com.astral.stitchapp

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader

val MANGAGO_SCRIPT = """
(function() {
    'use strict';

    function log(msg) {
        console.log("[MangaGoScraper] " + msg);
        if (window.Android && window.Android.log) {
            window.Android.log(msg);
        }
    }

    // --- HELPER: Load CryptoJS ---
    const loadCryptoJS = () => {
        return new Promise((resolve, reject) => {
            if (typeof CryptoJS !== 'undefined') {
                resolve();
                return;
            }
            const script = document.createElement('script');
            script.src = 'https://cdnjs.cloudflare.com/ajax/libs/crypto-js/4.1.1/crypto-js.min.js';
            script.onload = () => resolve();
            script.onerror = () => reject(new Error('Failed to load CryptoJS'));
            document.head.appendChild(script);
        });
    };

    // --- HELPER: Deobfuscation (from mangago.txt) ---
    function soJsonV4Deobfuscator(obfuscatedJs) {
        if (!obfuscatedJs.includes("['sojson.v4']")) return obfuscatedJs;
        try {
            const s = obfuscatedJs.substring(240, obfuscatedJs.length - 60);
            let result = '';
            const matches = s.match(/\d+/g);
            if (matches) {
                for (const charCode of matches) {
                    result += String.fromCharCode(parseInt(charCode));
                }
            }
            return result;
        } catch (e) {
            console.error('Deobfuscation error:', e);
            return obfuscatedJs;
        }
    }

    function unscrambleImageList(imageListStr, deobfuscatedJs) {
        try {
            const keyLocations = [];
            const regex = /str\.charAt\s*\(\s*(\d+)\s*\)/g;
            let match;
            while ((match = regex.exec(deobfuscatedJs)) !== null) {
                keyLocations.push(parseInt(match[1]));
            }
            const uniqueLocations = [...new Set(keyLocations)];
            if (uniqueLocations.length === 0) return imageListStr;

            const unscrambleKey = [];
            for (const loc of uniqueLocations) {
                const digit = parseInt(imageListStr.charAt(loc));
                if (isNaN(digit)) return imageListStr;
                unscrambleKey.push(digit);
            }
            const imgListArray = imageListStr.split('');
            const cleanedImgList = imgListArray.filter((_, i) => !uniqueLocations.includes(i)).join('');
            return stringUnscramble(cleanedImgList, unscrambleKey);
        } catch (e) {
            console.error('Unscramble error:', e);
            return imageListStr;
        }
    }

    function stringUnscramble(scrambledStr, keys) {
        const sArray = scrambledStr.split('');
        for (let j = keys.length - 1; j >= 0; j--) {
            const keyVal = keys[j];
            for (let i = sArray.length - 1; i > keyVal; i--) {
                if ((i - 1) % 2 !== 0) {
                    const idx1 = i - keyVal - 1;
                    const idx2 = i - 1;
                    [sArray[idx1], sArray[idx2]] = [sArray[idx2], sArray[idx1]];
                }
            }
        }
        return sArray.join('');
    }

    // --- HELPER: Fetch Loop for Pagination ---
    async function scrapeByPagination() {
        log("Starting pagination scrape...");
        let totalPages = 0;

        // Try finding total pages
        const pageTip = document.querySelector(".multi_pg_tip.left");
        if (pageTip) {
            const txt = pageTip.textContent.trim(); // "Total pages: ( 1 / 15 )"
            const parts = txt.split('/');
            if (parts.length > 1) {
                const num = parseInt(parts[parts.length-1].replace(/[^\d]/g, ''));
                if (!isNaN(num)) totalPages = num;
            }
        }

        if (totalPages === 0) {
            const select = document.getElementById('page-dropdown');
            if (select) totalPages = select.options.length;
        }

        if (totalPages === 0) totalPages = 1; // Fallback
        log("Total pages detected: " + totalPages);

        const currentUrl = window.location.href;
        let baseUrl = currentUrl;

        // Determine URL pattern
        // Pattern 1: .../pg-1/
        // Pattern 2: .../1/
        let pattern = "pg";
        if (currentUrl.includes("/pg-")) {
            baseUrl = currentUrl.replace(/pg-\d+\/?/, "");
        } else {
            // Check if ends with digit
            if (/\/(\d+)\/?${'$'}/.test(currentUrl)) {
                 baseUrl = currentUrl.replace(/\/(\d+)\/?${'$'}/, "/");
                 pattern = "slash";
            } else {
                 if (!baseUrl.endsWith('/')) baseUrl += '/';
                 pattern = "append"; // e.g. /chapter-123/ -> /chapter-123/1/
            }
        }

        const allImages = [];

        for (let i = 1; i <= totalPages; i++) {
            let pageUrl = "";
            if (pattern === "pg") pageUrl = baseUrl + "pg-" + i + "/";
            else if (pattern === "slash") pageUrl = baseUrl + i + "/";
            else pageUrl = baseUrl + i + "/"; // append

            log("Fetching page " + i + ": " + pageUrl);

            try {
                // We use standard fetch since we are on the same domain (mostly)
                // But for subsequent pages, we might need cookie. Fetch sends cookies by default on same-origin.
                const response = await fetch(pageUrl);
                const html = await response.text();

                // Parse HTML
                const parser = new DOMParser();
                const doc = parser.parseFromString(html, "text/html");

                // Find image
                // Usually <img id="page1" src="..." >
                // Or any img that looks like the manga page
                const imgs = doc.querySelectorAll('img');
                let found = false;
                for (let img of imgs) {
                    // Heuristics
                    if ((img.id && img.id.startsWith('page')) ||
                        (img.className && img.className.includes('page')) ||
                        (img.getAttribute('src') && img.getAttribute('src').includes('mangapicgallery'))) {

                        let src = img.getAttribute('src') || img.getAttribute('data-src');
                        if (src) {
                            if (src.startsWith("//")) src = "https:" + src;
                            if (!allImages.includes(src)) {
                                allImages.push(src);
                                found = true;
                            }
                        }
                    }
                }
                if (!found) log("No image found on page " + i);
            } catch(e) {
                log("Error fetching page " + i + ": " + e.message);
            }
        }
        return allImages;
    }

    // --- MAIN EXTRACTION ---
    window.runMangaGoScraper = async function() {
        log("Starting Scraper...");
        try {
            await loadCryptoJS();

            // METHOD 1: Try userscript logic (imgsrcs + chapter.js)
            let method1Images = [];
            try {
                // Find imgsrcs
                const scripts = document.querySelectorAll('script:not([src])');
                let imgsrcMatch = null;
                for (const script of scripts) {
                    if (script.textContent.includes('imgsrcs')) {
                        imgsrcMatch = script.textContent.match(/var\s+imgsrcs\s*=\s*['"]([^'"]+)['"]/);
                        if (!imgsrcMatch) imgsrcMatch = script.textContent.match(/imgsrcs\s*=\s*['"]([^'"]+)['"]/);
                        if (imgsrcMatch) break;
                    }
                }

                if (imgsrcMatch) {
                    const imgsrcB64 = imgsrcMatch[1];
                    log("Found encrypted imgsrcs");

                    // Fetch chapter.js via Android interface to bypass CORS if needed
                    const chapterJsUrl = document.querySelector('script[src*="chapter.js"]')?.src;
                    if (chapterJsUrl) {
                        log("Fetching chapter.js: " + chapterJsUrl);
                        const chapterJsText = Android.fetchUrl(chapterJsUrl);
                        if (chapterJsText && !chapterJsText.startsWith("ERROR")) {
                             const deobfuscatedJs = soJsonV4Deobfuscator(chapterJsText);

                             // Key/IV
                             const keyMatch = deobfuscatedJs.match(/var\s+key\s*=\s*CryptoJS\.enc\.Hex\.parse\s*\(\s*["']([0-9a-fA-F]+)["']\s*\)/);
                             const ivMatch = deobfuscatedJs.match(/var\s+iv\s*=\s*CryptoJS\.enc\.Hex\.parse\s*\(\s*["']([0-9a-fA-F]+)["']\s*\)/);

                             if (keyMatch && ivMatch) {
                                 const key = CryptoJS.enc.Hex.parse(keyMatch[1]);
                                 const iv = CryptoJS.enc.Hex.parse(ivMatch[1]);
                                 const decrypted = CryptoJS.AES.decrypt(imgsrcB64, key, {
                                    iv: iv,
                                    mode: CryptoJS.mode.CBC,
                                    padding: CryptoJS.pad.ZeroPadding
                                });
                                const decryptedImageList = decrypted.toString(CryptoJS.enc.Utf8).replace(/\0+${'$'}/, '');
                                const finalImageList = unscrambleImageList(decryptedImageList, deobfuscatedJs);
                                const rawImages = finalImageList.split(',').filter(u => u.trim());

                                // --- Image Unscrambling Logic ---
                                let cols = "";
                                const colsMatch = deobfuscatedJs.match(/var\s+widthnum\s*=\s*heightnum\s*=\s*(\d+);/);
                                if (colsMatch) cols = colsMatch[1];

                                const renImgStart = deobfuscatedJs.indexOf("var renImg = function(img,width,height,id){");
                                const keySplitIndex = deobfuscatedJs.indexOf("key = key.split(", renImgStart);

                                if (renImgStart !== -1 && keySplitIndex !== -1 && cols) {
                                    const renImgBlock = deobfuscatedJs.substring(renImgStart, keySplitIndex);
                                    const bodyStart = renImgBlock.indexOf("{") + 1;
                                    const keyLogic = renImgBlock.substring(bodyStart)
                                        .split("\n")
                                        .filter(line => !["jQuery", "document", "getContext", "toDataURL", "getImageData", "width", "height"].some(f => line.includes(f)))
                                        .join("\n")
                                        .replace(/img\.src/g, "url");

                                    log("Extracted keyLogic length: " + keyLogic.length);
                                    const getDescramblingKey = new Function("url", "replacePos", keyLogic + "\nreturn key;");

                                    const replacePos = (strObj, pos, replacetext) => {
                                        return strObj.substr(0, pos) + replacetext + strObj.substring(pos + 1, strObj.length);
                                    };

                                    method1Images = rawImages.map(u => {
                                        if (u.indexOf("cspiclink") !== -1) {
                                            try {
                                                const descKey = getDescramblingKey(u, replacePos);
                                                return u + "#desckey=" + descKey + "&cols=" + cols;
                                            } catch(e) {
                                                log("Error calculating descKey for " + u.substring(0, 20) + ": " + e.message);
                                                return u;
                                            }
                                        }
                                        return u;
                                    });
                                } else {
                                    log("Could not find renImg or keySplit, skipping unscramble key extraction. renImgStart=" + renImgStart + ", keySplitIndex=" + keySplitIndex);
                                    method1Images = rawImages;
                                }
                                log("Method 1 (Crypto) found " + method1Images.length + " images");
                             }
                        }
                    }
                }
            } catch(e) {
                log("Method 1 failed: " + e.message);
            }

            // METHOD 2: Pagination Loop
            let method2Images = [];
            let isPaginationNeeded = false;
             const pageTip = document.querySelector(".multi_pg_tip.left");
             if (pageTip && pageTip.textContent.includes('/')) {
                 const parts = pageTip.textContent.trim().split('/');
                 const total = parseInt(parts[parts.length-1].replace(/[^\d]/g, ''));
                 if (total > method1Images.length) isPaginationNeeded = true;
             }

             if (method1Images.length < 2 || isPaginationNeeded) {
                 log("Method 1 insufficient, running Pagination Scrape...");
                 method2Images = await scrapeByPagination();
                 log("Method 2 found " + method2Images.length + " images");
             }

             let finalImages = [];
             if (method1Images.length > method2Images.length) finalImages = method1Images;
             else if (method2Images.length > 0) finalImages = method2Images;
             else finalImages = method1Images; // Fallback

             if (finalImages.length === 0) {
                 log("No images found!");
                 return;
             }

             // Prepare result
             const title = document.title.replace(" - Read Free Manga Online", "").trim();
             const result = {
                 title: title,
                 images: finalImages,
                 cookie: document.cookie
             };

             Android.onImagesFound(JSON.stringify(result));

        } catch(e) {
            log("Fatal Error: " + e.message);
        }
    };

})();
"""

class MangaGoJsInterface(
    private val onResult: (String, List<String>, String) -> Unit,
    private val onLog: (String) -> Unit
) {

    @JavascriptInterface
    fun log(msg: String) {
        Log.d("MangaGoJS", msg)
        onLog(msg)
    }

    @JavascriptInterface
    fun fetchUrl(urlString: String): String {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            // Fake UA to avoid instant block
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

            val cookie = CookieManager.getInstance().getCookie(urlString)
            if (cookie != null) {
                conn.setRequestProperty("Cookie", cookie)
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val result = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                result.append(line).append("\n")
            }
            reader.close()
            result.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            "ERROR: " + e.message
        }
    }

    @JavascriptInterface
    fun onImagesFound(jsonStr: String) {
        Log.d("MangaGoUi", "onImagesFound called with: ${jsonStr.take(100)}...")
        try {
            val json = JSONObject(jsonStr)
            val title = json.optString("title", "MangaGo Scan")
            val imgArray = json.optJSONArray("images")
            val cookie = json.optString("cookie", "")

            val list = mutableListOf<String>()
            if (imgArray != null) {
                for (i in 0 until imgArray.length()) {
                    list.add(imgArray.getString(i))
                }
            }
            onResult(title, list, cookie)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun MangaGoWebViewDialog(
    url: String,
    onDismiss: () -> Unit,
    onScrapeSuccess: (String, List<String>, String) -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("Loading page...") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                            addJavascriptInterface(MangaGoJsInterface(
                                onResult = { t, i, c -> onScrapeSuccess(t, i, c) },
                                onLog = { msg -> status = msg }
                            ), "Android")

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    Log.d("MangaGoUi", "Injection onPageFinished: $url")
                                    view?.evaluateJavascript(MANGAGO_SCRIPT, null)
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    Log.d("MangaGoJS", consoleMessage?.message() ?: "")
                                    return true
                                }
                            }
                            loadUrl(url)
                            webView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize().padding(bottom = 100.dp) // Leave space for buttons
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .padding(8.dp)
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Close")
                        }
                        Button(
                            onClick = {
                                Log.d("MangaGoUi", "Scrape Button Clicked")
                                status = "Scraping started..."
                                if (webView == null) {
                                    status = "Error: WebView not ready"
                                } else {
                                    webView?.evaluateJavascript(MANGAGO_SCRIPT) {
                                        webView?.evaluateJavascript("window.runMangaGoScraper();", null)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Scrape")
                        }
                    }
                }
            }
        }
    }
}
