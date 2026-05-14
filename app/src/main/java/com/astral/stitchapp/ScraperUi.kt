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

    val MANGAGO = """
    (function() {
        'use strict';
        $COMMON_SCRIPTS

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
            } catch (e) { return obfuscatedJs; }
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
            } catch (e) { return imageListStr; }
        }

        function stringUnscramble(scrambledStr, keys) {
            let s = scrambledStr.split('');
            for (let j = keys.length - 1; j >= 0; j--) {
                const keyVal = keys[j];
                for (let i = s.length - 1; i >= keyVal; i--) {
                    if (i % 2 !== 0) {
                        const temp = s[i - keyVal];
                        s[i - keyVal] = s[i];
                        s[i] = temp;
                    }
                }
            }
            return s.join('');
        }

        async function scrapeByPagination() {
            let totalPages = 0;
            const pageTip = document.querySelector(".multi_pg_tip.left");
            if (pageTip) {
                const parts = pageTip.textContent.trim().split('/');
                if (parts.length > 1) {
                    const num = parseInt(parts[parts.length-1].replace(/[^\d]/g, ''));
                    if (!isNaN(num)) totalPages = num;
                }
            }
            if (totalPages === 0) {
                const select = document.getElementById('page-dropdown');
                if (select) totalPages = select.options.length;
            }
            if (totalPages === 0) totalPages = 1;

            const currentUrl = window.location.href;
            let baseUrl = currentUrl;
            let pattern = "pg";
            if (currentUrl.includes("/pg-")) {
                baseUrl = currentUrl.replace(/pg-\d+\/?/, "");
            } else {
                if (/\/(\d+)\/?${"$"}/.test(currentUrl)) {
                     baseUrl = currentUrl.replace(/\/(\d+)\/?${"$"}/, "/");
                     pattern = "slash";
                } else {
                     if (!baseUrl.endsWith('/')) baseUrl += '/';
                     pattern = "append";
                }
            }

            const allImages = [];
            for (let i = 1; i <= totalPages; i++) {
                let pageUrl = "";
                if (pattern === "pg") pageUrl = baseUrl + "pg-" + i + "/";
                else pageUrl = baseUrl + i + "/";

                try {
                    const response = await fetch(pageUrl);
                    const html = await response.text();
                    const parser = new DOMParser();
                    const doc = parser.parseFromString(html, "text/html");
                    const imgs = doc.querySelectorAll('img');
                    for (let img of imgs) {
                        if ((img.id && img.id.startsWith('page')) ||
                            (img.className && img.className.includes('page')) ||
                            (img.getAttribute('src') && img.getAttribute('src').includes('mangapicgallery'))) {
                            let src = img.getAttribute('src') || img.getAttribute('data-src');
                            if (src) {
                                if (src.startsWith("//")) src = "https:" + src;
                                if (!allImages.includes(src)) allImages.push(src);
                            }
                        }
                    }
                } catch(e) {}
            }
            return allImages;
        }

        window.runScraper = async function() {
            log("Starting MangaGo Scraper...");
            try {
                await loadCryptoJS();
                let method1Images = [];
                try {
                    const scripts = document.querySelectorAll('script:not([src])');
                    let imgsrcMatch = null;
                    for (const script of scripts) {
                        if (script.textContent.includes('imgsrcs')) {
                            imgsrcMatch = script.textContent.match(/imgsrcs\s*=\s*['"]([^'"]+)['"]/);
                            if (imgsrcMatch) break;
                        }
                    }
                    if (imgsrcMatch) {
                        const imgsrcB64 = imgsrcMatch[1];
                        const chapterJsUrl = document.querySelector('script[src*="chapter.js"]')?.src;
                        if (chapterJsUrl) {
                            const chapterJsText = Android.fetchUrl(chapterJsUrl);
                            if (chapterJsText && !chapterJsText.startsWith("ERROR")) {
                                 const deobfuscatedJs = soJsonV4Deobfuscator(chapterJsText);
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
                                    const decryptedImageList = decrypted.toString(CryptoJS.enc.Utf8).replace(/\0+${"$"}/, '');
                                    const finalImageList = unscrambleImageList(decryptedImageList, deobfuscatedJs);
                                    const rawImages = finalImageList.split(',').filter(u => u.trim());
                                    let cols = "";
                                    const colsMatch = deobfuscatedJs.match(/(?:var\s+)?widthnum\s*=\s*heightnum\s*=\s*(\d+);/);
                                    if (colsMatch) cols = colsMatch[1];
                                    const renImgMatch = deobfuscatedJs.match(/renImg\s*=\s*function\s*\([^{]+\{([\s\S]+?)key\s*=\s*key\.split\(/);
                                    if (renImgMatch && cols) {
                                        const keyLogic = renImgMatch[1].replace(/\b(?:jQuery|document|window|getContext|toDataURL|getImageData|width|height)\b/g, "undefined").replace(/img\.src/g, "url");
                                        const getDescramblingKey = new Function("url", "replacePos", "const document={}, window={}, img={}; " + keyLogic + "\nreturn key;");
                                        const replacePos = (strObj, pos, replacetext) => strObj.substr(0, pos) + replacetext + strObj.substring(pos + 1, strObj.length);
                                        method1Images = rawImages.map(u => {
                                            if (u.indexOf("cspiclink") !== -1) {
                                                try {
                                                    let descKey = getDescramblingKey(u, replacePos);
                                                    if (Array.isArray(descKey)) descKey = descKey.join(",");
                                                    return u + "#desckey=" + descKey + "&cols=" + cols;
                                                } catch(e) { return u; }
                                            }
                                            return u;
                                        });
                                    } else { method1Images = rawImages; }
                                 }
                            }
                        }
                    }
                } catch(e) {}

                let method2Images = [];
                if (method1Images.length < 2) {
                     method2Images = await scrapeByPagination();
                }
                let finalImages = method1Images.length >= method2Images.length ? method1Images : method2Images;
                if (finalImages.length === 0) { log("No images found!"); return; }
                const result = {
                    title: document.title.replace(" - Read Free Manga Online", "").trim(),
                    images: finalImages,
                    cookie: document.cookie
                };
                Android.onImagesFound(JSON.stringify(result));
            } catch(e) { log("Fatal Error: " + e.message); }
        };
    })();
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
                                defaultHeight: img.defaultHeight
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

    val NEWTOKI = """
    (function() {
        'use strict';
        $COMMON_SCRIPTS

        window.runScraper = function() {
            log("Starting Newtoki Scraper...");
            const images = [];
            const candidates = new Set();
            document.querySelectorAll('img').forEach(img => {
                let src = img.src || img.getAttribute('data-src') || img.getAttribute('data-original');
                if (src) candidates.add(new URL(src, location.href).href);
            });

            document.querySelectorAll('div, section, figure').forEach(el => {
                const bg = getComputedStyle(el).backgroundImage;
                if (bg && bg.includes('url(')) {
                    const m = bg.match(/url\(["']?(.+?)["']?\)/);
                    if (m) candidates.add(new URL(m[1], location.href).href);
                }
                // Custom data attributes often used in galleries
                for (let attr of el.attributes) {
                    if (attr.name.startsWith('data-') && /\.(jpe?g|png|webp|avif)/i.test(attr.value)) {
                        candidates.add(new URL(attr.value, location.href).href);
                    }
                }
            });

            const filtered = Array.from(candidates).filter(u => {
                if (u.includes('logo') || u.includes('avatar') || u.includes('banner')) return false;
                // Requirement 4: Skip .avif files
                if (u.toLowerCase().includes(".avif")) return false;
                return /\.(jpe?g|png|webp|gif|bmp)(?:${"$"}|\?)/i.test(u);
            });
            const result = {
                title: document.title.trim(),
                images: filtered,
                cookie: document.cookie
            };
            Android.onImagesFound(JSON.stringify(result));
        };
    })();
    """.trimIndent()

    val LEZHIN = """
    (function() {
        'use strict';
        $COMMON_SCRIPTS

        let baseTemplate = null;
        const availableIndexes = new Set();
        const shuffleKeys = new Map();

        function tryRecordUrl(url) {
            if (!url || typeof url !== 'string') return;
            const m = url.match(/rcdn\.lezhin\.com\/.*?\/(\d+)\.(webp|jpe?g|png)(\?.*)?${"$"}/i);
            if (!m) return;

            const idx = parseInt(m[1]);
            availableIndexes.add(idx);

            if (!baseTemplate) {
                const tm = url.match(/(.*\/)(\d+)(\.(?:webp|jpg|jpeg|png))(.*)${"$"}/i);
                if (tm) {
                    baseTemplate = tm[1] + '__IDX__' + tm[3] + tm[4];
                    log("Base URL captured!");
                }
            }
        }

        function installInterceptors() {
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
            shuffleKeys.clear();
            const patterns = [
                /\\"path\\":\\"([^"\\]+)\\",\\"cutType\\":\\"contents\\",\\"shuffleKey\\":(\d+|\\"?\$undefined\\"?)/g,
                /"path"\s*:\s*"([^"]+)",\s*"cutType"\s*:\s*"contents",\s*"shuffleKey"\s*:\s*(\d+|"\$undefined"|null)/g,
            ];
            for (const pat of patterns) {
                let m;
                while ((m = pat.exec(html)) !== null) {
                    const path = m[1].replace(/\\+/g, '');
                    const rawKey = m[2].replace(/["\\]/g, '').replace('${"$"}undefined', '').trim();
                    const idxMatch = path.split('/').pop().match(/^(\d+)/);
                    if (!idxMatch) continue;
                    const index = parseInt(idxMatch[1]);
                    shuffleKeys.set(index, rawKey || null);
                }
                if (shuffleKeys.size > 0) break;
            }
            return shuffleKeys.size;
        }

        installInterceptors();

        window.fetchAll = async function() {
            log("Lezhin: Scanning for images and keys...");
            const nKeys = extractShuffleKeys();
            if (nKeys === 0 && availableIndexes.size === 0) {
                log("No data found. Scroll down to trigger lazy loading.");
                return;
            }

            const maxIdx = shuffleKeys.size ? Math.max(...shuffleKeys.keys()) : Math.max(...availableIndexes);
            const images = [];
            for (let i = 1; i <= maxIdx; i++) {
                let url = baseTemplate ? baseTemplate.replace('__IDX__', String(i)) : null;
                const key = shuffleKeys.get(i);
                if (url) {
                    if (key) url += "#shuffleKey=" + key;
                    images.push(url);
                }
            }
            window._scrapedImages = images;
            log("Found " + images.length + " images.");
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
