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
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader

object ScraperScripts {
    private val COMMON_SCRIPTS = """
        function log(msg) {
            console.log("[Scraper] " + msg);
            if (window.Android && window.Android.log) { window.Android.log(msg); }
        }

        const loadCryptoJS = () => {
            return new Promise((resolve, reject) => {
                if (typeof CryptoJS !== 'undefined') { resolve(); return; }
                const script = document.createElement('script');
                script.src = 'https://cdnjs.cloudflare.com/ajax/libs/crypto-js/4.1.1/crypto-js.min.js';
                script.onload = () => resolve();
                script.onerror = () => reject(new Error('Failed to load CryptoJS'));
                document.head.appendChild(script);
            });
        };

        const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));
    """.trimIndent()


    val RIDIBOOKS = """
    (function() {
        'use strict';
        $COMMON_SCRIPTS

        window.runScraper = async function() {
            log("Starting Ridibooks Scraper...");
            try {
                const bookIdMatch = location.pathname.match(/\/(?:books|webtoon)\/(\d+)/);
                if (!bookIdMatch) { log("Could not detect Book ID from URL"); return; }
                const bookId = bookIdMatch[1];
                log("Book ID: " + bookId);

                const response = await fetch('https://ridibooks.com/api/web-viewer/generate', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ book_id: String(bookId) })
                });

                if (!response.ok) throw new Error("HTTP " + response.status);
                const json = await response.json();
                if (!json.success || !json.data || !json.data.pages) throw new Error("Invalid API response");

                const images = json.data.pages.map(p => p.src);
                const result = {
                    title: document.title.replace(" - Ridibooks", "").trim(),
                    images: images,
                    cookie: document.cookie
                };
                Android.onImagesFound(JSON.stringify(result));
            } catch(e) { log("Error: " + e.message); }
        };
    })();
    """.trimIndent()

    val KAKAOPAGE = """
    (function() {
        'use strict';
        $COMMON_SCRIPTS

        let capturedData = null;

        // Intercept Fetch
        const originalFetch = window.fetch;
        window.fetch = async function(...args) {
            const response = await originalFetch.apply(this, args);
            const url = args[0] && typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : "");
            if (url.includes('/api/gateway/api/v1/viewer/data')) {
                response.clone().json().then(data => {
                    if (data && data.viewerData && data.viewerData.imageDownloadData) {
                        capturedData = data;
                        log("Viewer data captured via fetch!");
                    }
                }).catch(() => {});
            }
            return response;
        };

        // Intercept XHR
        const originalOpen = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url) {
            this._url = url;
            return originalOpen.apply(this, arguments);
        };
        const originalSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.send = function() {
            this.addEventListener('load', function() {
                if (this._url && this._url.includes('/api/gateway/api/v1/viewer/data')) {
                    try {
                        const data = JSON.parse(this.responseText);
                        if (data && data.viewerData && data.viewerData.imageDownloadData) {
                            capturedData = data;
                            log("Viewer data captured via XHR!");
                        }
                    } catch(e) {}
                }
            });
            return originalSend.apply(this, arguments);
        };

        window.runScraper = function() {
            log("Starting KakaoPage Scraper...");
            if (!capturedData) {
                log("No data captured yet. Please scroll or wait for the page to load.");
                return;
            }
            try {
                const files = capturedData.viewerData.imageDownloadData.files;
                const images = files.map(f => f.secureUrl);
                const result = {
                    title: document.title.trim(),
                    images: images,
                    cookie: document.cookie
                };
                Android.onImagesFound(JSON.stringify(result));
            } catch(e) { log("Error: " + e.message); }
        };
    })();
    """.trimIndent()

    val BOMTOON = """
    (function() {
        'use strict';
        $COMMON_SCRIPTS

        async function getAuthToken() {
            const resp = await fetch("/api/auth/session");
            const data = await resp.json();
            return data.user ? data.user.accessToken.token : null;
        }

        async function apiReq(url, method, dataPost) {
            const authToken = await getAuthToken();
            const headers = {
                "x-balcony-id": location.host.includes(".tw") ? "BOMTOON_TW" : "BOMTOON_COM",
                "x-balcony-timezone": location.host.includes(".tw") ? "Asia/Taipei" : "Asia/Seoul",
                "x-platform": "WEB",
                "accept": "application/json"
            };
            if (method === "POST") headers["Content-Type"] = "application/json";
            if (authToken) headers.authorization = "Bearer " + authToken;
            const resp = await fetch(url, { headers, method, body: dataPost ? JSON.stringify(dataPost) : null });
            return await resp.json();
        }

        async function decryptScramble(cipherText, keyStr) {
            const ivStr = keyStr.substring(0, 16);
            const key = CryptoJS.enc.Utf8.parse(keyStr);
            const iv = CryptoJS.enc.Utf8.parse(ivStr);
            const decrypted = CryptoJS.AES.decrypt(cipherText, key, {
                iv: iv,
                mode: CryptoJS.mode.CBC,
                padding: CryptoJS.pad.Pkcs7
            });
            return JSON.parse(decrypted.toString(CryptoJS.enc.Utf8));
        }

        window.grabApiData = async function() {
            log("Bomtoon: Grabbing API data...");
            try {
                await loadCryptoJS();
                const nextData = JSON.parse(document.getElementById("__NEXT_DATA__").innerHTML);
                const buildId = nextData.buildId;
                const pathName = location.pathname;
                const urlParam = pathName.match(/\/viewer\/(.+?)\/(.+?)(\/|${"$"})/);
                if (!urlParam) throw new Error("Could not parse URL parameters");

                const queryParam = new URLSearchParams();
                queryParam.append("alias", urlParam[1]);
                queryParam.append("epAlias", urlParam[2]);

                const dataUrl = `/_next/data/${"$"}{buildId}${"$"}{pathName}.json?${"$"}{queryParam.toString()}`;
                const resp = await fetch(dataUrl, { headers: { "X-Nextjs-Data": 1 } });
                const json = await resp.json();
                const episodeData = json.pageProps.episodeData ? json.pageProps.episodeData.result : null;

                let episodeInfo;
                if (!episodeData) {
                    log("Fallback to direct API...");
                    const apiEndpoint = `/api/balcony-api-v2/contents${"$"}{pathName}?isNotLoginAdult=false`;
                    const apiResp = await apiReq(apiEndpoint, "GET");
                    if (!apiResp.data) throw new Error("API failed");
                    episodeInfo = apiResp.data;
                } else {
                    episodeInfo = episodeData;
                }

                // Get Scramble Key
                const keyApi = "/api/balcony-api-v2/contents/images/" + urlParam[1] + "/" + urlParam[2];
                const keyResp = await apiReq(keyApi, "POST", { line: episodeInfo.images[0].line });
                const scrambleKey = keyResp.data || "thisisBalconyScrambledKey1234!@#";

                const scrapedImages = [];
                const total = episodeInfo.images.length;
                for (let i = 0; i < total; i++) {
                    const img = episodeInfo.images[i];
                    log(`Processing index ${"$"}{i+1}/${"$"}{total}...`);
                    let url = img.imagePath;
                    if (img.point || img.line) {
                        try {
                            const scrambleIndex = await decryptScramble(img.point, scrambleKey);
                            url += "#scramble=" + encodeURIComponent(JSON.stringify({
                                scrambleIndex: scrambleIndex,
                                width: img.width,
                                defaultHeight: img.defaultHeight,
                                height: img.height
                            }));
                        } catch(e) { log(`Unscramble error on img ${"$"}{i+1}: ` + e.message); }
                    }
                    scrapedImages.push(url);
                }
                window._scrapedImages = scrapedImages;
                log("Bomtoon: " + window._scrapedImages.length + " images grabbed!");
                window.runScraper();
            } catch(e) { log("Error grabbing API data: " + e.message); }
        };

        window.runScraper = async function() {
            log("Starting Bomtoon Scraper...");
            if (!window._scrapedImages) {
                await window.grabApiData();
            }
            try {
                if (!window._scrapedImages || window._scrapedImages.length === 0) {
                    throw new Error("No images found. Try clicking 'Fetch' first.");
                }

                const result = {
                    title: document.title.trim(),
                    images: window._scrapedImages
                };
                Android.onImagesFound(JSON.stringify(result));
            } catch(e) { log("Error: " + e.message); }
        };
    })();
    """.trimIndent()


    val LEZHIN = """
    (function() {
        'use strict';
        $COMMON_SCRIPTS

        window._lzState = window._lzState || {
            baseTemplate: null,
            availableIndexes: new Set(),
            shuffleKeys: new Map()
        };
        const state = window._lzState;

        function tryRecordUrl(url) {
            if (!url || typeof url !== 'string') return;
            const m = url.match(/[a-z0-9]+cdn\.lezhin\.com\/.*?\/(\d+)\.(webp|jpe?g|png)(?:\?.*)?${"$"}/i);
            if (!m) return;

            const idx = parseInt(m[1]);
            state.availableIndexes.add(idx);

            if (!state.baseTemplate) {
                const tm = url.match(/(.*\/)(\d+)(\.(?:webp|jpg|jpeg|png))(.*)${"$"}/i);
                if (tm) {
                    state.baseTemplate = tm[1] + '__IDX__' + tm[3] + tm[4];
                    log("Base URL captured: " + state.baseTemplate);
                }
            }
        }

        function installInterceptors() {
            if (window._lzInterceptorsInstalled) return;
            window._lzInterceptorsInstalled = true;

            const imgSrcDesc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src');
            if (imgSrcDesc && imgSrcDesc.set) {
                const origSet = imgSrcDesc.set;
                Object.defineProperty(HTMLImageElement.prototype, 'src', {
                    set(v) { tryRecordUrl(v); return origSet.call(this, v); },
                    get: imgSrcDesc.get,
                    configurable: true
                });
            }
            const origFetch = window.fetch;
            window.fetch = function(input) {
                try { tryRecordUrl(typeof input === 'string' ? input : input && input.url); } catch {}
                return origFetch.apply(this, arguments);
            };
            const origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                try { tryRecordUrl(url); } catch {}
                return origOpen.apply(this, arguments);
            };
            document.querySelectorAll('img').forEach(img => tryRecordUrl(img.src || img.currentSrc));
        }

        function extractShuffleKeys() {
            const html = document.documentElement.innerHTML;
            const patterns = [
                /\\"path\\":\\"([^"\\]+)\\",\\"cutType\\":\\"contents\\",\\"shuffleKey\\":(\d+|\\"?\${"$"}${"$"}undefined\\"?)/g,
                /"path"\s*:\s*"([^"]+)",\s*"cutType"\s*:\s*"contents",\s*"shuffleKey"\s*:\s*(\d+|"\${"$"}${"$"}undefined"|null)/g,
            ];
            for (const pat of patterns) {
                let m;
                while ((m = pat.exec(html)) !== null) {
                    const path = m[1].replace(/\\+/g, '');
                    const rawKey = m[2].replace(/["\\]/g, '').replace('${"$"}${"$"}undefined', '').trim();
                    const idxMatch = path.split('/').pop().match(/^(\d+)/);
                    if (!idxMatch) continue;
                    const index = parseInt(idxMatch[1]);
                    state.shuffleKeys.set(index, rawKey || null);
                }
                if (state.shuffleKeys.size > 0) break;
            }
            log("Lezhin: Extraction found " + state.shuffleKeys.size + " keys.");
            return state.shuffleKeys.size;
        }

        installInterceptors();

        window.fetchAll = async function() {
            log("Lezhin: Scanning... " + state.shuffleKeys.size + " keys, " + state.availableIndexes.size + " indexes found.");
            const nKeys = extractShuffleKeys();
            if (nKeys === 0 && state.availableIndexes.size === 0) {
                log("No data found. Scroll down to trigger lazy loading.");
                return;
            }

            const maxIdx = Math.max(
                state.shuffleKeys.size ? Math.max(...state.shuffleKeys.keys()) : 0,
                state.availableIndexes.size ? Math.max(...state.availableIndexes) : 0
            );

            if (maxIdx === 0) {
                log("Could not determine max index.");
                return;
            }

            const images = [];
            for (let i = 1; i <= maxIdx; i++) {
                let url = state.baseTemplate ? state.baseTemplate.replace('__IDX__', String(i)) : null;
                const key = state.shuffleKeys.get(i);
                if (url) {
                    if (key) url += "#shuffleKey=" + key;
                    images.push(url);
                }
            }
            window._scrapedImages = images;
            log("Synthesized " + images.length + " images up to index " + maxIdx);
            window.runScraper();
        };

        window.runScraper = async function() {
            log("Starting Lezhin Scraper...");
            if (!window._scrapedImages) await window.fetchAll();
            try {
                if (!window._scrapedImages || window._scrapedImages.length === 0) {
                    throw new Error("No images found. Scroll page then click Fetch.");
                }
                const result = {
                    title: document.title.replace(/\s*-\s*Lezhin.*${"$"}/i, '').trim(),
                    images: window._scrapedImages,
                    cookie: document.cookie
                };
                Android.onImagesFound(JSON.stringify(result));
            } catch(e) { log("Error: " + e.message); }
        };
    })();
    """.trimIndent()
}

class ScraperJsInterface(
    private val onResult: (String, List<String>, String) -> Unit,
    private val onLog: (String) -> Unit
) {
    @JavascriptInterface
    fun log(msg: String) {
        Log.d("ScraperJS", msg)
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
            val cookie = CookieManager.getInstance().getCookie(urlString)
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val result = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                result.append(line).append("\n")
            }
            reader.close()
            result.toString()
        } catch (e: Exception) {
            "ERROR: " + e.message
        }
    }

    @JavascriptInterface
    fun onImagesFound(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val title = json.optString("title", "Scraped Content")
            val imgArray = json.optJSONArray("images")
            val list = mutableListOf<String>()
            if (imgArray != null) {
                for (i in 0 until imgArray.length()) {
                    list.add(imgArray.getString(i))
                }
            }
            onResult(title, list, "")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun ScraperWebViewDialog(
    url: String,
    script: String,
    onDismiss: () -> Unit,
    onScrapeSuccess: (String, List<String>, String) -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("Loading page...") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.setSupportMultipleWindows(true)
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

                            addJavascriptInterface(ScraperJsInterface(
                                onResult = { t, i, _ ->
                                    val cm = CookieManager.getInstance()
                                    val currentCookie = cm.getCookie(url) ?: ""
                                    onScrapeSuccess(t, i, currentCookie)
                                },
                                onLog = { msg -> status = msg }
                            ), "Android")

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    // Anti-bot: hide webdriver
                                    view?.evaluateJavascript("""
                                        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                                    """.trimIndent(), null)
                                    view?.evaluateJavascript(script, null)
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    Log.d("ScraperJS", consoleMessage?.message() ?: "")
                                    return true
                                }
                            }
                            loadUrl(url)
                            webView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize().padding(bottom = 100.dp)
                )

                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).padding(8.dp)
                ) {
                    Text(text = status, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.padding(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }

                        // Show "Fetch" button for Bomtoon and Lezhin
                        val showFetch = url.contains("bomtoon") || url.contains("lezhin")
                        if (showFetch) {
                            Button(
                                onClick = {
                                    status = "Fetching started..."
                                    val fetchFunc = if (url.contains("bomtoon")) "window.grabApiData()" else "window.fetchAll()"
                                    webView?.evaluateJavascript(script) {
                                        webView?.evaluateJavascript(fetchFunc, null)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Fetch") }
                        }

                        Button(
                            onClick = {
                                status = "Scraping started..."
                                webView?.evaluateJavascript(script) {
                                    webView?.evaluateJavascript("window.runScraper();", null)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Scrape") }
                    }
                }
            }
        }
    }
}
