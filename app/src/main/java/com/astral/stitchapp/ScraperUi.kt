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
            } catch(e) { log("Error grabbing API data: " + e.message); }
        };

        window.runScraper = async function() {
            log("Starting Bomtoon Scraper...");
            if (!window._scrapedImages) {
                await window.grabApiData();
            }
            try {
                if (!window._scrapedImages || window._scrapedImages.length === 0) {
                    throw new Error("No images found.");
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
            if (url.includes('ccdn.lezhin.com') || url.includes('/banners/')) return;
            const m = url.match(/[a-z0-9]+cdn\.lezhin\.com\/.*?\/(\d+)\.(webp|jpe?g|png|jxl)(?:\?.*)?${"$"}/i);
            if (!m) return;

            const idx = parseInt(m[1]);
            state.availableIndexes.add(idx);

            if (!state.baseTemplate) {
                const tm = url.match(/(.*\/)(\d+)(\.(?:webp|jpg|jpeg|png|jxl))(.*)${"$"}/i);
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
                /\\"path\\":\\"([^"\\]+)\\",\\"cutType\\":\\"contents\\",\\"shuffleKey\\":(\d+|\\"?${'$'}undefined\\"?)/g,
                /"path"\s*:\s*"([^"]+)",\s*"cutType"\s*:\s*"contents",\s*"shuffleKey"\s*:\s*(\d+|"${'$'}undefined"|null)/g,
            ];
            for (const pat of patterns) {
                let m;
                while ((m = pat.exec(html)) !== null) {
                    const path = m[1].replace(/\\+/g, '');
                    const rawKey = m[2].replace(/["\\]/g, '').replace('${'$'}undefined', '').trim();
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
        };

        window.runScraper = async function() {
            log("Starting Lezhin Scraper...");
            if (!window._scrapedImages) await window.fetchAll();
            try {
                if (!window._scrapedImages || window._scrapedImages.length === 0) {
                    throw new Error("No images found.");
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

    val MRBLUE = """
    (function() {
        'use strict';
        $COMMON_SCRIPTS

        const ORDER = [29, 15, 24, 8, 4, 26, 0, 12, 14, 23, 17, 1, 9, 5, 20, 28,
        11, 6, 19, 2, 16, 21, 30, 27, 25, 13, 7, 18, 3, 10, 22, 31];

        function deobfuscate(data) {
            const n = data.length;
            const out = new Uint8Array(n);
            let f = 0;
            while (f < n - 32) {
                for (let e = 0; e < 32; e++) {
                    out[f + e] = data[f + ORDER[e]] ^ 0xFF;
                }
                f += 32;
            }
            for (let i = f; i < n; i++) {
                out[i] = data[i] ^ 0xFF;
            }
            return out;
        }

        function isKnownHeader(view) {
            const isJpeg = view[0] === 0xFF && view[1] === 0xD8;
            const isPng = view[0] === 0x89 && view[1] === 0x50 && view[2] === 0x4E && view[3] === 0x47;
            const isGif = view[0] === 0x47 && view[1] === 0x49 && view[2] === 0x46;
            const isWebp = view[0] === 0x52 && view[1] === 0x49 && view[2] === 0x46 && view[3] === 0x46 &&
                           view[8] === 0x57 && view[9] === 0x45 && view[10] === 0x42 && view[11] === 0x50;
            return isJpeg || isPng || isGif || isWebp;
        }

        async function decodeArrayBufferBuffer(buffer) {
            try {
                const view = new Uint8Array(buffer);
                if (isKnownHeader(view)) {
                    return buffer;
                }
                const decodedData = deobfuscate(view);
                const dView = new Uint8Array(decodedData.buffer);
                if (isKnownHeader(dView)) {
                    return decodedData.buffer;
                }
                return buffer;
            } catch (e) {
                log("Decode error: " + e.message);
                return buffer;
            }
        }

        function bufferToBase64(buffer, mimeType) {
            let binary = '';
            const bytes = new Uint8Array(buffer);
            const len = bytes.byteLength;
            const chunkSize = 0x8000;
            for (let i = 0; i < len; i += chunkSize) {
                binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
            }
            return "data:" + (mimeType || "image/jpeg") + ";base64," + btoa(binary);
        }

        const API_BASE = 'https://viewer.mrblue.com';
        const ENDPOINT = {
            sd: 'https://comics-c.mrblue.com',
            hd: 'https://comicshd-c.mrblue.com',
        };
        const API_RE = /\/contents\/access\/([^/?#]+)\/(\d+)/;

        window._mrblueState = window._mrblueState || {
            token: '',
            channel: 'AW',
            contentId: '',
            episode: '',
            data: null,
            agent: 'daddy-mobile/2.44.0',
            isHooked: false
        };
        const state = window._mrblueState;

        function onApiSeen(url, headers, json) {
            const m = String(url).match(API_RE);
            if (!m) return;
            state.contentId = m[1];
            state.episode = m[2];
            const h = {};
            if (headers) {
                for (const k in headers) h[k.toLowerCase()] = headers[k];
            }
            const tok = h['mrblue-auth-token'] || h['x-auth-token'];
            if (tok) state.token = tok;
            if (h['mrblue-channel']) state.channel = h['mrblue-channel'];
            if (h['x-client-agent']) state.agent = h['x-client-agent'];
            if (json && (json.sd || json.hd)) state.data = json;
            log("MrBlue API seen: cid=" + state.contentId + ", ep=" + state.episode + ", tok=" + (state.token ? "yes" : "no"));
            if (window.Android && window.Android.updateProgress) {
                const count = (state.data && (state.data.hd || state.data.sd)) ? (state.data.hd || state.data.sd).length : 0;
                Android.updateProgress(count, count, true);
            }
        }

        function installHooks() {
            if (state.isHooked) return;
            state.isHooked = true;

            const origFetch = window.fetch;
            window.fetch = function(input, init) {
                const url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                const headers = init && init.headers ? init.headers : (input && input.headers ? input.headers : null);
                const resPromise = origFetch.apply(this, arguments);
                if (url && API_RE.test(url)) {
                    resPromise.then(res => {
                        res.clone().json().then(json => {
                            onApiSeen(url, headers, json);
                        }).catch(() => {});
                    }).catch(() => {});
                }
                return resPromise;
            };

            const XHR = XMLHttpRequest;
            const origOpen = XHR.prototype.open;
            const origSetHeader = XHR.prototype.setRequestHeader;
            const origSend = XHR.prototype.send;

            XHR.prototype.open = function(method, url) {
                this.__mb = { url, headers: {} };
                return origOpen.apply(this, arguments);
            };
            XHR.prototype.setRequestHeader = function(header, value) {
                if (this.__mb) this.__mb.headers[header] = value;
                return origSetHeader.apply(this, arguments);
            };
            XHR.prototype.send = function() {
                if (this.__mb && API_RE.test(this.__mb.url)) {
                    this.addEventListener('load', () => {
                        let json = null;
                        try { json = JSON.parse(this.responseText); } catch (e) {}
                        onApiSeen(this.__mb.url, this.__mb.headers, json);
                    });
                }
                return origSend.apply(this, arguments);
            };
        }

        function authHeaders() {
            return {
                'mrblue-auth-token': state.token,
                'x-auth-token': state.token,
                'mrblue-channel': state.channel,
                'x-client-agent': state.agent,
                'x-wasm-support': 'Y',
                'cache-control': 'no-cache',
                'pragma': 'no-cache',
                'Referer': location.href
            };
        }

        async function fetchAccess(contentId, episode) {
            const url = `${"$"}{API_BASE}/home/api/gateway/api/v4/contents/access/${"$"}{contentId}/${"$"}{episode}?channel=${"$"}{state.channel}`;
            const resp = await fetch(url, { headers: authHeaders() });
            if (!resp.ok) throw new Error("HTTP " + resp.status + " " + url);
            return await resp.json();
        }

        function extractTitle() {
            const rawTitle = document.title;
            let cleanTitle = rawTitle;
            if (cleanTitle.includes('|')) {
                cleanTitle = cleanTitle.split('|')[0];
            }
            const parenIndex = cleanTitle.lastIndexOf('(');
            if (parenIndex !== -1) {
                cleanTitle = cleanTitle.substring(0, parenIndex);
            }
            cleanTitle = cleanTitle.replace(/\s+/g, ' ').trim();
            cleanTitle = cleanTitle.replace(/\s*\/\s*/g, ' - ');
            cleanTitle = cleanTitle.replace(/- MrBlue${"$"}/i, '').replace(/미스터블루/g, '').trim();
            return cleanTitle || 'MrBlueComic';
        }

        window.runScraper = async function() {
            log("Starting MrBlue API Scraper...");
            try {
                let cid = state.contentId;
                let ep = state.episode;

                if (!cid || !ep) {
                    const m = location.pathname.match(API_RE) || location.href.match(/contentId=([^&]+)/);
                    if (m) cid = m[1];
                }

                if (!cid) throw new Error("Could not detect Content ID from page");

                let data = state.data;
                if (!data) {
                    log("Fetching access data from API...");
                    data = await fetchAccess(cid, ep || '1');
                }

                let list = data.hd || data.sd;
                let q = (data.hd && data.hd.length > 0) ? 'hd' : 'sd';

                if (!list || list.length === 0) {
                    throw new Error("No image list in API response");
                }

                list = [...list].sort((a, b) => a.pn - b.pn);
                log("MrBlue API: Found " + list.length + " pages (" + q.toUpperCase() + ")");

                const dataUrls = [];
                const total = list.length;

                for (let i = 0; i < total; i++) {
                    const item = list[i];
                    const imgUrl = ENDPOINT[q] + item.path;
                    if (window.Android && window.Android.updateProgress) {
                        Android.updateProgress(i + 1, total, false);
                    }
                    log("Downloading & deobfuscating page " + (i + 1) + "/" + total + "...");

                    const resp = await fetch(imgUrl, {
                        headers: { 'Referer': 'https://viewer.mrblue.com/', 'Origin': 'https://viewer.mrblue.com' }
                    });
                    if (!resp.ok) throw new Error("Failed to fetch image page " + item.pn + " (HTTP " + resp.status + ")");

                    const buffer = await resp.arrayBuffer();
                    const decodedBuffer = await decodeArrayBufferBuffer(buffer);
                    const mimeType = resp.headers.get('content-type') || 'image/jpeg';
                    const dataUrl = bufferToBase64(decodedBuffer, mimeType);
                    dataUrls.push(dataUrl);
                }

                if (window.Android && window.Android.updateProgress) {
                    Android.updateProgress(total, total, true);
                }

                const pageTitle = extractTitle();
                const result = {
                    title: pageTitle,
                    images: dataUrls,
                    cookie: document.cookie
                };

                log("MrBlue: Successfully scraped " + dataUrls.length + " images via API!");
                if (window.Android && window.Android.onImagesFound) {
                    Android.onImagesFound(JSON.stringify(result));
                }
            } catch (e) {
                log("MrBlue Scraper Error: " + e.message);
            }
        };

        installHooks();
    })();
    """.trimIndent()
}

class ScraperJsInterface(
    private val onResult: (String, List<String>, String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onProgress: ((Int, Int, Boolean) -> Unit)? = null
) {
    @JavascriptInterface
    fun log(msg: String) {
        Log.d("ScraperJS", msg)
        onLog(msg)
    }

    @JavascriptInterface
    fun updateProgress(loaded: Int, total: Int, isReady: Boolean) {
        onProgress?.invoke(loaded, total, isReady)
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

    val isMrBlue = url.contains("mrblue") || script.contains("MrBlue") || script.contains("mrblue")
    var loadedCount by remember { mutableIntStateOf(0) }
    var totalCount by remember { mutableIntStateOf(0) }
    var isScrapeReady by remember { mutableStateOf(!isMrBlue) }
    var hasScraped by remember { mutableStateOf(false) }

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
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            addJavascriptInterface(ScraperJsInterface(
                                onResult = { t, i, _ ->
                                    if (!hasScraped) {
                                        hasScraped = true
                                        val cm = CookieManager.getInstance()
                                        val currentCookie = cm.getCookie(url) ?: ""
                                        onScrapeSuccess(t, i, currentCookie)
                                    }
                                },
                                onLog = { msg -> status = msg },
                                onProgress = { loaded, total, ready ->
                                    loadedCount = loaded
                                    totalCount = total
                                    isScrapeReady = ready
                                }
                            ), "Android")

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                    val reqUrl = request?.url?.toString() ?: return false
                                    if (reqUrl.startsWith("http://") || reqUrl.startsWith("https://")) {
                                        return false
                                    }
                                    return try {
                                        val intent = android.content.Intent.parseUri(reqUrl, android.content.Intent.URI_INTENT_SCHEME)
                                        val ctx = view?.context ?: return true
                                        if (intent.resolveActivity(ctx.packageManager) != null) {
                                            ctx.startActivity(intent)
                                        } else {
                                            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                            if (!fallbackUrl.isNullOrEmpty()) {
                                                view?.loadUrl(fallbackUrl)
                                            }
                                        }
                                        true
                                    } catch (e: Exception) {
                                        true
                                    }
                                }

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

                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: android.os.Message?
                                ): Boolean {
                                    val newWebView = WebView(view!!.context)
                                    newWebView.settings.javaScriptEnabled = true
                                    newWebView.settings.domStorageEnabled = true
                                    newWebView.settings.userAgentString = view.settings.userAgentString
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(newWebView, true)

                                    newWebView.webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(v: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                            val popupUrl = request?.url?.toString() ?: return false
                                            if (popupUrl.startsWith("http://") || popupUrl.startsWith("https://")) {
                                                view.loadUrl(popupUrl)
                                                return true
                                            }
                                            return try {
                                                val intent = android.content.Intent.parseUri(popupUrl, android.content.Intent.URI_INTENT_SCHEME)
                                                val ctx = v?.context ?: return true
                                                if (intent.resolveActivity(ctx.packageManager) != null) {
                                                    ctx.startActivity(intent)
                                                } else {
                                                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                                    if (!fallbackUrl.isNullOrEmpty()) {
                                                        view.loadUrl(fallbackUrl)
                                                    }
                                                }
                                                true
                                            } catch (e: Exception) {
                                                true
                                            }
                                        }

                                        override fun onPageStarted(v: WebView?, popupUrl: String?, favicon: android.graphics.Bitmap?) {
                                            super.onPageStarted(v, popupUrl, favicon)
                                            if (popupUrl != null && (popupUrl.startsWith("http://") || popupUrl.startsWith("https://"))) {
                                                v?.stopLoading()
                                                view.loadUrl(popupUrl)
                                            }
                                        }
                                    }

                                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                                    if (transport != null) {
                                        transport.webView = newWebView
                                        resultMsg.sendToTarget()
                                    }
                                    return true
                                }
                            }
                            loadUrl(url)
                            webView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize().padding(bottom = 120.dp)
                )

                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)).padding(8.dp)
                ) {
                    if (isMrBlue || totalCount > 0) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (!isScrapeReady && totalCount > 0) {
                                LinearProgressIndicator(
                                    progress = { loadedCount.toFloat() / totalCount.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(6.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Loading images: $loadedCount / $totalCount",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (!isScrapeReady && totalCount == 0) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Detecting viewer images...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            } else if (isScrapeReady && totalCount > 0) {
                                Text(
                                    text = "All $totalCount images loaded! Ready to scrape.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                    }

                    Text(text = status, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.padding(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }

                        Button(
                            onClick = {
                                status = "Scraping started..."
                                webView?.evaluateJavascript(script) {
                                    webView?.evaluateJavascript("window.runScraper();", null)
                                }
                            },
                            enabled = isScrapeReady,
                            modifier = Modifier.weight(1f)
                        ) { Text("Scrape") }
                    }
                }
            }
        }
    }
}
