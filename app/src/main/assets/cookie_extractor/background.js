(function() {
    'use strict';

    let nativePort = null;

    function connectNativeApp() {
        try {
            nativePort = browser.runtime.connectNative("browser");
            nativePort.onDisconnect.addListener(() => {
                nativePort = null;
                setTimeout(connectNativeApp, 2000);
            });
        } catch(e) {
            nativePort = null;
        }
    }

    async function getAllDomainCookies(hostname) {
        const queries = [];
        const domain = (hostname || "").toLowerCase();

        if (domain.includes("kakao")) {
            queries.push({ domain: "kakao.com" });
            queries.push({ domain: "page.kakao.com" });
            queries.push({ domain: "bff-page.kakao.com" });
            queries.push({ domain: "daum.net" });
        } else if (domain.includes("ridi")) {
            queries.push({ domain: "ridibooks.com" });
            queries.push({ domain: "ridi.com" });
        } else if (domain.includes("bomtoon")) {
            queries.push({ domain: "bomtoon.com" });
        } else if (domain.includes("lezhin")) {
            queries.push({ domain: "lezhin.com" });
        } else if (domain.includes("mrblue")) {
            queries.push({ domain: "mrblue.com" });
        } else if (domain.includes("manta")) {
            queries.push({ domain: "manta.net" });
        } else if (domain) {
            queries.push({ domain: domain });
            const parts = domain.split('.');
            if (parts.length > 2) {
                queries.push({ domain: parts.slice(-2).join('.') });
            }
        } else {
            queries.push({});
        }

        const cookieMap = new Map();
        for (const q of queries) {
            try {
                const list = await browser.cookies.getAll(q);
                if (Array.isArray(list)) {
                    for (const c of list) {
                        if (c && c.name && c.value != null) {
                            cookieMap.set(c.name, c.value);
                        }
                    }
                }
            } catch(e) {}
        }

        const entries = [];
        for (const [k, v] of cookieMap.entries()) {
            entries.push(k + '=' + v);
        }
        return entries.join('; ');
    }

    // Listen to messages from content scripts
    browser.runtime.onMessage.addListener((request, sender, sendResponse) => {
        if (request && request.action === "getCookies") {
            const domain = request.domain || (sender && sender.tab && sender.tab.url ? new URL(sender.tab.url).hostname : "");
            getAllDomainCookies(domain).then(cookieString => {
                sendResponse({ cookieString: cookieString });
                // Also forward to native app
                if (nativePort && cookieString) {
                    try {
                        nativePort.postMessage({ type: "COOKIES_RESULT", cookieString: cookieString });
                    } catch(e) {}
                }
            }).catch(() => {
                sendResponse({ cookieString: "" });
            });
            return true; // Keep channel open for async response
        }
    });

    // Listen for cookie changes
    browser.cookies.onChanged.addListener((changeInfo) => {
        if (!changeInfo || !changeInfo.cookie) return;
        const d = (changeInfo.cookie.domain || "").toLowerCase();
        if (d.includes("kakao") || d.includes("ridi") || d.includes("bomtoon") || d.includes("lezhin") || d.includes("mrblue") || d.includes("manta")) {
            getAllDomainCookies(d).then(cookieString => {
                if (nativePort && cookieString) {
                    try {
                        nativePort.postMessage({ type: "COOKIE_UPDATE", cookieString: cookieString });
                    } catch(e) {}
                }
            }).catch(()=>{});
        }
    });

    connectNativeApp();
})();
