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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.AllowOrDeny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            token: (function(){ try { return localStorage.getItem('mrblue_token') || ''; } catch(e){ return ''; } })(),
            channel: 'AW',
            contentId: '',
            episode: '',
            data: null,
            agent: 'daddy-mobile/2.44.0',
            isHooked: false
        };
        const state = window._mrblueState;

        function hasItems(arr) {
            return Array.isArray(arr) && arr.length > 0;
        }

        function extractData(json) {
            if (!json) return null;
            if (hasItems(json.sd) || hasItems(json.hd)) return json;
            if (json.data && (hasItems(json.data.sd) || hasItems(json.data.hd))) return json.data;
            if (json.result && (hasItems(json.result.sd) || hasItems(json.result.hd))) return json.result;
            if (json.response && (hasItems(json.response.sd) || hasItems(json.response.hd))) return json.response;
            if (json.data) {
                const sub = extractData(json.data);
                if (sub) return sub;
            }
            if (json.result) {
                const sub = extractData(json.result);
                if (sub) return sub;
            }
            return null;
        }

        function getStoredToken() {
            if (state.token) return state.token;
            try {
                const cookies = document.cookie.split(';');
                for (const c of cookies) {
                    const parts = c.trim().split('=');
                    if (parts.length >= 2 && /token|auth/i.test(parts[0])) {
                        const v = parts.slice(1).join('=');
                        if (v && v.length > 10) return v;
                    }
                }
            } catch(e) {}
            try {
                const saved = localStorage.getItem('mrblue_token');
                if (saved) return saved;
            } catch(e) {}
            try {
                for (let i = 0; i < localStorage.length; i++) {
                    const k = localStorage.key(i);
                    if (/token|auth/i.test(k)) {
                        const v = localStorage.getItem(k);
                        if (v && v.length > 10) return v;
                    }
                }
                for (let i = 0; i < sessionStorage.length; i++) {
                    const k = sessionStorage.key(i);
                    if (/token|auth/i.test(k)) {
                        const v = sessionStorage.getItem(k);
                        if (v && v.length > 10) return v;
                    }
                }
            } catch(e) {}
            return '';
        }

        function parseHeadersObj(headers) {
            const h = {};
            if (!headers) return h;
            try {
                if (typeof headers.entries === 'function') {
                    for (const [k, v] of headers.entries()) {
                        if (k && v) h[String(k).toLowerCase()] = String(v);
                    }
                } else if (typeof headers.forEach === 'function') {
                    headers.forEach((v, k) => {
                        if (k && v) h[String(k).toLowerCase()] = String(v);
                    });
                } else if (typeof headers === 'object') {
                    for (const k in headers) {
                        if (Object.prototype.hasOwnProperty.call(headers, k)) {
                            const v = headers[k];
                            if (k && v) h[String(k).toLowerCase()] = String(v);
                        }
                    }
                }
            } catch(e) {}
            return h;
        }

        function onApiSeen(url, headers, json) {
            const m = String(url).match(API_RE);
            if (!m) return;
            state.contentId = m[1];
            state.episode = m[2];
            const h = parseHeadersObj(headers);
            const tok = h['mrblue-auth-token'] || h['x-auth-token'];
            if (tok) {
                state.token = tok;
                try { localStorage.setItem('mrblue_token', tok); } catch(e) {}
            }
            if (h['mrblue-channel']) state.channel = h['mrblue-channel'];
            if (h['x-client-agent']) state.agent = h['x-client-agent'];
            const extData = extractData(json);
            if (extData && (hasItems(extData.sd) || hasItems(extData.hd))) state.data = extData;
            log("MrBlue API seen: cid=" + state.contentId + ", ep=" + state.episode + ", tok=" + (state.token ? "yes" : "no"));
            if (window.Android && window.Android.updateProgress) {
                const activeList = state.data ? (hasItems(state.data.hd) ? state.data.hd : (hasItems(state.data.sd) ? state.data.sd : [])) : [];
                const count = activeList.length;
                if (count > 0) {
                    Android.updateProgress(count, count, true);
                }
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
            const tok = getStoredToken();
            const h = {
                'mrblue-channel': state.channel || 'AW',
                'x-client-agent': state.agent || 'daddy-mobile/2.44.0',
                'x-wasm-support': 'Y',
                'cache-control': 'no-cache',
                'pragma': 'no-cache',
                'Referer': location.href
            };
            if (tok) {
                h['mrblue-auth-token'] = tok;
                h['x-auth-token'] = tok;
            }
            return h;
        }

        async function fetchAccess(contentId, episode) {
            const url = `${"$"}{API_BASE}/home/api/gateway/api/v4/contents/access/${"$"}{contentId}/${"$"}{episode}?channel=${"$"}{state.channel}`;
            const resp = await fetch(url, { headers: authHeaders(), credentials: 'include' });
            if (!resp.ok) throw new Error("HTTP " + resp.status + " " + url);
            return await resp.json();
        }

        function parseUrlParams() {
            const href = location.href;
            const pathname = location.pathname;

            let m = href.match(/\/contents\/access\/([^/?#]+)\/(\d+)/);
            if (m) return { cid: m[1], ep: m[2] };

            m = pathname.match(/\/(wt_[a-zA-Z0-9_-]+)\/(\d+)/);
            if (m) return { cid: m[1], ep: m[2] };

            m = pathname.match(/\/([^\/]+)\/(\d+)/);
            if (m && m[1] !== 'viewer' && m[1] !== 'contents') return { cid: m[1], ep: m[2] };

            try {
                const urlParams = new URLSearchParams(location.search);
                const qCid = urlParams.get('contentId') || urlParams.get('cid') || urlParams.get('content_id');
                const qEp = urlParams.get('episode') || urlParams.get('ep') || urlParams.get('episode_no') || '1';
                if (qCid) return { cid: qCid, ep: qEp };
            } catch(e) {}

            m = href.match(/(wt_[a-zA-Z0-9_-]+)/);
            if (m) {
                const epMatch = href.match(/[\/=&](\d+)(?:[?#&]|$)/);
                return { cid: m[1], ep: epMatch ? epMatch[1] : '1' };
            }

            return { cid: state.contentId || '', ep: state.episode || '1' };
        }

        function findDataInDOM() {
            try {
                for (const key of Object.keys(window)) {
                    if (/data|state|content|viewer/i.test(key) && key !== '_mrblueState') {
                        const val = window[key];
                        const ext = extractData(val);
                        if (ext && (hasItems(ext.sd) || hasItems(ext.hd))) return ext;
                    }
                }
                const scripts = document.querySelectorAll('script');
                for (const s of scripts) {
                    if (s.textContent && (s.textContent.includes('"hd"') || s.textContent.includes('"sd"'))) {
                        try {
                            const json = JSON.parse(s.textContent);
                            const ext = extractData(json);
                            if (ext && (hasItems(ext.sd) || hasItems(ext.hd))) return ext;
                        } catch(e) {
                            const m = s.textContent.match(/(\{.*"(?:hd|sd)"\s*:\s*\[.*\})/);
                            if (m) {
                                try {
                                    const json = JSON.parse(m[1]);
                                    const ext = extractData(json);
                                    if (ext && (hasItems(ext.sd) || hasItems(ext.hd))) return ext;
                                } catch(e2) {}
                            }
                        }
                    }
                }
            } catch(e) {}
            return null;
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
                const parsed = parseUrlParams();
                let cid = state.contentId || parsed.cid;
                let ep = state.episode || parsed.ep;

                log("MrBlue target: cid=" + cid + ", ep=" + ep);

                if (!cid) throw new Error("Could not detect Content ID from page URL");

                let data = extractData(state.data);
                if (!data) {
                    data = findDataInDOM();
                }

                if (!data || (!hasItems(data.hd) && !hasItems(data.sd))) {
                    log("Fetching access data from API (" + cid + " / " + ep + ")...");
                    const rawData = await fetchAccess(cid, ep || '1');
                    data = extractData(rawData);
                }

                if (!data) throw new Error("No image list in API response");

                let list = (data.hd && data.hd.length > 0) ? data.hd : data.sd;
                let q = (data.hd && data.hd.length > 0) ? 'hd' : 'sd';

                if (!list || list.length === 0) {
                    throw new Error("Image list is empty in API response");
                }

                list = [...list].sort((a, b) => a.pn - b.pn);
                log("MrBlue API: Found " + list.length + " pages (" + q.toUpperCase() + ")");

                const dataUrls = new Array(list.length);
                const total = list.length;
                const CONCURRENCY = 6;
                let completed = 0;

                async function fetchAndDecode(i) {
                    const item = list[i];
                    const imgUrl = ENDPOINT[q] + item.path;
                    log("Downloading & deobfuscating page " + (i + 1) + "/" + total + "...");

                    const resp = await fetch(imgUrl, {
                        headers: { 'Referer': 'https://viewer.mrblue.com/', 'Origin': 'https://viewer.mrblue.com' }
                    });
                    if (!resp.ok) throw new Error("Failed to fetch image page " + item.pn + " (HTTP " + resp.status + ")");

                    const buffer = await resp.arrayBuffer();
                    const decodedBuffer = await decodeArrayBufferBuffer(buffer);
                    const mimeType = resp.headers.get('content-type') || 'image/jpeg';
                    const dataUrl = bufferToBase64(decodedBuffer, mimeType);
                    dataUrls[i] = dataUrl;

                    completed++;
                    if (window.Android && window.Android.updateProgress) {
                        Android.updateProgress(completed, total, false);
                    }
                }

                for (let i = 0; i < total; i += CONCURRENCY) {
                    const chunk = [];
                    for (let j = i; j < Math.min(i + CONCURRENCY, total); j++) {
                        chunk.push(fetchAndDecode(j));
                    }
                    await Promise.all(chunk);
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

    val COMIX = """
    (function() {
        'use strict';
        $COMMON_SCRIPTS

        window.runScraper = async function() {
            log("Starting Comix Scraper...");
            try {
                let pages = [];
                const extractEvent = new CustomEvent('cdl:extract-pages', { detail: {} });
                window.dispatchEvent(extractEvent);
                if (extractEvent.detail && extractEvent.detail.pages && extractEvent.detail.pages.length > 0) {
                    pages = extractEvent.detail.pages.map(p => p.src);
                    log("Extracted " + pages.length + " pages via cdl:extract-pages event!");
                }

                if (!pages || pages.length === 0) {
                    const imgs = Array.from(document.querySelectorAll('img')).map(img => img.src).filter(src => src && !src.includes('data:image') && !src.includes('avatar') && !src.includes('logo'));
                    pages = Array.from(new Set(imgs));
                    log("Fallback extracted " + pages.length + " image URLs from DOM.");
                }

                if (!pages || pages.length === 0) {
                    throw new Error("No images found. Open a chapter on comix.to first.");
                }

                const result = {
                    title: document.title.replace(" | Comix", "").trim() || "Comix Chapter",
                    images: pages,
                    cookie: document.cookie
                };

                if (window.Android && window.Android.onImagesFound) {
                    window.Android.onImagesFound(JSON.stringify(result));
                }
            } catch(e) {
                log("Error: " + e.message);
            }
        };
    })();
    """.trimIndent()

    val KAKAOPAGE = """
    (function() {
        'use strict';
        $COMMON_SCRIPTS

        const BFF_HOST = 'bff-page.kakao.com';
        const API_PATH = '/api/gateway/api/v1/viewer/data';

        window._kpState = window._kpState || {
            items: new Map(),
            orderedNos: [],
            titleHint: null
        };
        const state = window._kpState;

        function safeParse(jsonText) {
            try { return JSON.parse(jsonText); } catch(e) { return null; }
        }

        function extractFilesFromRestAPI(jsonObj) {
            try {
                if (!jsonObj) return null;
                let title = null;
                if (jsonObj.item && jsonObj.item.title) {
                    title = jsonObj.item.title;
                }
                const viewerData = jsonObj.viewer_data || jsonObj.viewerData;
                if (viewerData) {
                    if (viewerData.type === 'ImageViewerData' && viewerData.imageDownloadData && viewerData.imageDownloadData.files) {
                        const files = viewerData.imageDownloadData.files.map(f => ({
                            no: f.no,
                            url: f.secureUrl
                        }));
                        return { title, files };
                    }
                }
                return null;
            } catch (err) {
                return null;
            }
        }

        function addFiles(list) {
            let added = 0;
            for (const item of list) {
                if (!item || !item.no || !item.url) continue;
                const key = Number(item.no);
                if (!state.items.has(key)) {
                    state.items.set(key, { no: key, url: item.url });
                    added++;
                }
            }
            state.orderedNos = Array.from(state.items.keys()).sort((a, b) => a - b);
            if (window.Android && window.Android.updateProgress) {
                window.Android.updateProgress(state.items.size, state.items.size, true);
            }
        }

        function detectAndIngest(text) {
            if (!text) return;
            if (text.indexOf('"viewer_data"') === -1 && text.indexOf('"viewerData"') === -1 && text.indexOf('"imageDownloadData"') === -1) {
                return;
            }
            const obj = safeParse(text);
            if (!obj) return;
            const result = extractFilesFromRestAPI(obj);
            if (result && result.files && result.files.length) {
                if (result.title) state.titleHint = result.title;
                addFiles(result.files);
                log("Detected KakaoPage viewer data: " + result.files.length + " files. Title: " + (result.title || "Unknown"));
            }
        }

        function installHooks() {
            if (window._kpHooksInstalled) return;
            window._kpHooksInstalled = true;

            const _fetch = window.fetch;
            window.fetch = async function(input, init) {
                try {
                    const url = (typeof input === 'string') ? input : (input?.url || '');
                    const isTarget = url.includes(BFF_HOST) && url.includes(API_PATH);
                    const resp = await _fetch.apply(this, arguments);
                    if (isTarget) {
                        resp.clone().text().then(detectAndIngest).catch(()=>{});
                    }
                    return resp;
                } catch (e) {
                    return _fetch.apply(this, arguments);
                }
            };

            const origOpen = XMLHttpRequest.prototype.open;
            const origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method, url) {
                this._kakaoTarget = (typeof url === 'string' && url.includes(BFF_HOST) && url.includes(API_PATH));
                return origOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                if (this._kakaoTarget) {
                    this.addEventListener('readystatechange', function() {
                        try {
                            if (this.readyState === 4 && (this.status >= 200 && this.status < 300)) {
                                detectAndIngest(this.responseText);
                            }
                        } catch(e) {}
                    });
                }
                return origSend.apply(this, arguments);
            };
        }

        installHooks();

        window.runScraper = async function() {
            log("Starting KakaoPage Scraper...");
            try {
                if (!state.items || state.items.size === 0) {
                    log("No images detected yet. Opening/browsing viewer episode required.");
                    throw new Error("No images detected. Please open an episode in KakaoPage viewer first.");
                }
                const orderedUrls = state.orderedNos.map(no => state.items.get(no).url);
                const title = state.titleHint || document.title.replace(" - 카카오페이지", "").trim() || "KakaoPage Episode";
                const result = {
                    title: title,
                    images: orderedUrls,
                    cookie: document.cookie
                };
                log("KakaoPage: Successfully scraped " + orderedUrls.length + " images!");
                if (window.Android && window.Android.onImagesFound) {
                    window.Android.onImagesFound(JSON.stringify(result));
                }
            } catch(e) {
                log("Error: " + e.message);
            }
        };
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
    val useGecko = url.contains("manta", ignoreCase = true) || script.contains("Manta", ignoreCase = true) || script.contains("manta", ignoreCase = true) ||
                   url.contains("kakao", ignoreCase = true) || script.contains("Kakao", ignoreCase = true) || script.contains("kakao", ignoreCase = true) ||
                   url.contains("ridi", ignoreCase = true) || script.contains("Ridi", ignoreCase = true) || script.contains("ridi", ignoreCase = true) ||
                   url.contains("comix", ignoreCase = true) || script.contains("Comix", ignoreCase = true) || script.contains("comix", ignoreCase = true)

    if (useGecko) {
        GeckoScraperDialog(
            url = url,
            script = script,
            onDismiss = onDismiss,
            onScrapeSuccess = onScrapeSuccess
        )
    } else {
        StandardWebViewScraperDialog(
            url = url,
            script = script,
            onDismiss = onDismiss,
            onScrapeSuccess = onScrapeSuccess
        )
    }
}

@Composable
private fun GeckoScraperDialog(
    url: String,
    script: String,
    onDismiss: () -> Unit,
    onScrapeSuccess: (String, List<String>, String) -> Unit
) {
    val context = LocalContext.current
    val runtime = remember { GeckoRuntime.getDefault(context) }
    val session = remember { GeckoSession() }

    var status by remember { mutableStateOf("Loading page...") }

    var loadedCount by remember { mutableIntStateOf(0) }
    var totalCount by remember { mutableIntStateOf(0) }
    var isScrapeReady by remember { mutableStateOf(true) }
    var hasScraped by remember { mutableStateOf(false) }

    var popupSessionState by remember { mutableStateOf<GeckoSession?>(null) }

    val isComix = url.contains("comix", ignoreCase = true) || script.contains("Comix", ignoreCase = true) || script.contains("comix", ignoreCase = true)

    LaunchedEffect(runtime) {
        if (isComix) {
            try {
                val addonFile = com.astral.stitchapp.rawloader.ComixExtensionManager.ensureAddonFile(context)
                if (addonFile != null) {
                    val extension = com.astral.stitchapp.rawloader.ComixExtensionManager.installToGecko(context, runtime)
                    if (extension != null) {
                        Log.d("ScraperUi", "Installed Comix addon into GeckoRuntime (${extension.id}), reloading session...")
                        session.reload()
                    } else {
                        Log.w("ScraperUi", "Comix addon installation returned null extension")
                    }
                }
            } catch (e: Exception) {
                Log.e("ScraperUi", "Failed to install Comix addon into GeckoRuntime", e)
            }
        }
    }

    DisposableEffect(session) {
        onDispose {
            try { session.close() } catch (_: Exception) {}
        }
    }

    val androidVersion = android.os.Build.VERSION.RELEASE
    val model = android.os.Build.MODEL
    val realDeviceUserAgent = "Mozilla/5.0 (Linux; Android $androidVersion; $model) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    val buildJsUri: (Boolean) -> String = remember(script) {
        { autoRun ->
            val code = """
                (function() {
                    try {
                        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                        Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
                        Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
                    } catch(e) {}
                })();
                (function() {
                    if (!window.Android) {
                        window.Android = {
                            log: function(msg) { console.log("[Scraper] " + msg); },
                            updateProgress: function(loaded, total, ready) {
                                location.href = "astralstitch://progress?loaded=" + loaded + "&total=" + total + "&ready=" + ready;
                            },
                            onImagesFound: function(jsonStr) {
                                try {
                                    const parsed = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
                                    window._scrapedTitle = parsed.title || '';
                                    window._scrapedImages = parsed.images || [];
                                    window._scrapedCookie = parsed.cookie || document.cookie || '';
                                } catch(e) {
                                    window._scrapedTitle = 'Scraped Content';
                                    window._scrapedImages = [];
                                    window._scrapedCookie = document.cookie || '';
                                }
                                location.href = "astralstitch://result";
                            }
                        };
                    }
                })();
                $script
                ${if (autoRun) "if (window.runScraper) { window.runScraper(); }" else ""}
            """.trimIndent()
            "javascript:" + android.net.Uri.encode(code)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        GeckoView(ctx).apply {
                            setSession(session)
                            session.open(runtime)

                            session.navigationDelegate = object : GeckoSession.NavigationDelegate {
                                override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                                    val popup = GeckoSession()
                                    popup.settings.userAgentOverride = realDeviceUserAgent
                                    popup.contentDelegate = object : GeckoSession.ContentDelegate {
                                        override fun onCloseRequest(geckoSession: GeckoSession) {
                                            try { geckoSession.close() } catch (_: Exception) {}
                                            if (popupSessionState == geckoSession) {
                                                popupSessionState = null
                                            }
                                        }
                                    }
                                    popupSessionState = popup
                                    return GeckoResult.fromValue(popup)
                                }

                                override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                                    val reqUrl = request.uri
                                    if (reqUrl.startsWith("astralstitch://progress")) {
                                        val uri = android.net.Uri.parse(reqUrl)
                                        loadedCount = uri.getQueryParameter("loaded")?.toIntOrNull() ?: 0
                                        totalCount = uri.getQueryParameter("total")?.toIntOrNull() ?: 0
                                        isScrapeReady = uri.getQueryParameter("ready")?.toBooleanStrictOrNull() ?: true
                                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                                    } else if (reqUrl.startsWith("astralstitch://result")) {
                                        s.loadUri("javascript:location.href='astralstitch://data?title=' + encodeURIComponent(window._scrapedTitle||'') + '&cookie=' + encodeURIComponent(window._scrapedCookie||'') + '&images=' + encodeURIComponent(JSON.stringify(window._scrapedImages||[]));")
                                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                                    } else if (reqUrl.startsWith("astralstitch://data")) {
                                        val uri = android.net.Uri.parse(reqUrl)
                                        val title = uri.getQueryParameter("title") ?: "Scraped Content"
                                        val jsCookie = uri.getQueryParameter("cookie") ?: ""
                                        val imagesJson = uri.getQueryParameter("images") ?: "[]"
                                        val list = mutableListOf<String>()
                                        try {
                                            val arr = org.json.JSONArray(imagesJson)
                                            for (i in 0 until arr.length()) {
                                                list.add(arr.getString(i))
                                            }
                                        } catch (_: Exception) {}

                                        val cm = CookieManager.getInstance()
                                        cm.flush()
                                        val cm4 = cm.getCookie(url) ?: ""

                                        val cookieMap = mutableMapOf<String, String>()
                                        fun parseIntoMap(cStr: String) {
                                            if (cStr.isBlank()) return
                                            for (p in cStr.split(";")) {
                                                val trimmed = p.trim()
                                                if (trimmed.contains("=")) {
                                                    val k = trimmed.substringBefore("=").trim()
                                                    val v = trimmed.substringAfter("=").trim()
                                                    if (k.isNotBlank()) cookieMap[k] = v
                                                } else if (trimmed.isNotBlank()) {
                                                    cookieMap[trimmed] = ""
                                                }
                                            }
                                        }

                                        parseIntoMap(jsCookie)
                                        parseIntoMap(cm4)

                                        val finalCookieString = cookieMap.entries.joinToString("; ") { (k, v) ->
                                            if (v.isBlank()) k else "$k=$v"
                                        }

                                        if (!hasScraped && list.isNotEmpty()) {
                                            hasScraped = true
                                            onScrapeSuccess(title, list, finalCookieString)
                                        }
                                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                                    }
                                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                                }
                            }

                            session.progressDelegate = object : GeckoSession.ProgressDelegate {
                                override fun onPageStart(s: GeckoSession, pageUrl: String) {
                                    status = "Loading page..."
                                }

                                override fun onPageStop(s: GeckoSession, success: Boolean) {
                                    status = if (success) "Page loaded" else "Failed to load"
                                    s.loadUri(buildJsUri(false))
                                }
                            }

                            session.settings.userAgentOverride = realDeviceUserAgent
                            session.loadUri(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize().padding(bottom = 120.dp)
                )

                popupSessionState?.let { popup ->
                    Dialog(onDismissRequest = {
                        try { popup.close() } catch (_: Exception) {}
                        popupSessionState = null
                    }) {
                        Card(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            AndroidView(
                                factory = { ctx ->
                                    GeckoView(ctx).apply {
                                        setSession(popup)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)).padding(8.dp)
                ) {
                    if (!isComix && totalCount > 0) {
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

                    if (!isComix) {
                        Text(text = status, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.padding(8.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }

                        OutlinedButton(
                            onClick = {
                                status = "Reloading page..."
                                session.reload()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Reload") }

                        if (!isComix) {
                            Button(
                                onClick = {
                                    status = "Scraping started..."
                                    session.loadUri(buildJsUri(true))
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
}

@Composable
private fun StandardWebViewScraperDialog(
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
    var isScrapeReady by remember { mutableStateOf(true) }
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

                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    view?.evaluateJavascript(script, null)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
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
                                    val parentWebView = view ?: return false
                                    val newWebView = WebView(parentWebView.context).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.databaseEnabled = true
                                        settings.setSupportMultipleWindows(true)
                                        settings.javaScriptCanOpenWindowsAutomatically = true
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        settings.userAgentString = parentWebView.settings.userAgentString

                                        val cm = CookieManager.getInstance()
                                        cm.setAcceptCookie(true)
                                        cm.setAcceptThirdPartyCookies(this, true)

                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(v: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                                val popupUrl = request?.url?.toString() ?: return false
                                                if (popupUrl.startsWith("http://") || popupUrl.startsWith("https://")) {
                                                    return false
                                                }
                                                return try {
                                                    val intent = android.content.Intent.parseUri(popupUrl, android.content.Intent.URI_INTENT_SCHEME)
                                                    val ctx = v?.context ?: return true
                                                    if (intent.resolveActivity(ctx.packageManager) != null) {
                                                        ctx.startActivity(intent)
                                                    } else {
                                                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                                        if (!fallbackUrl.isNullOrEmpty()) {
                                                            parentWebView.loadUrl(fallbackUrl)
                                                        }
                                                    }
                                                    true
                                                } catch (e: Exception) {
                                                    true
                                                }
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

                        OutlinedButton(
                            onClick = {
                                status = "Reloading page..."
                                webView?.reload()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Reload") }

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
