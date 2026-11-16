(function() {
    'use strict';

    if (window._tappyHooksInstalled) return;
    window._tappyHooksInstalled = true;

    window._tappyCapturedData = window._tappyCapturedData || null;

    function getEpisodeIdFromUrl() {
        const match = window.location.pathname.match(/\/chapters\/(\d+)/) ||
                      window.location.pathname.match(/\/(?:chapter[s]?|book\/[^\/]+)\/(\d+)/i);
        return match ? match[1] : null;
    }

    function findChapterData(obj, url) {
        if (!obj || typeof obj !== 'object') return null;

        const episodeId = getEpisodeIdFromUrl();
        if (episodeId && url && !url.includes(episodeId)) {
            return null;
        }

        const filesArray = Array.isArray(obj.files)
            ? obj.files
            : (obj.data && Array.isArray(obj.data.files) ? obj.data.files : null);

        if (filesArray) {
            const validFiles = filesArray.filter(f => f && f.url && f.sortKey !== undefined);
            if (validFiles.length > 0) {
                const sortedImages = [...validFiles].sort((a, b) => a.sortKey - b.sortKey);
                return {
                    images: sortedImages.map(f => ({
                        ord: f.sortKey,
                        downloadUrl: f.url
                    })),
                    title: document.title || (`Chapter ${episodeId || ''}`).trim(),
                    episodeId: episodeId
                };
            }
        }
        return null;
    }

    function onChapterDataFound(found) {
        if (!found || !found.images || found.images.length === 0) return;
        window._tappyCapturedData = found;
        try {
            if (window.Android && window.Android.updateProgress) {
                window.Android.updateProgress(found.images.length, found.images.length, true);
            }
        } catch (e) {}
    }

    const originalOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
        this.addEventListener('load', function() {
            try {
                const res = JSON.parse(this.responseText);
                const reqUrl = typeof url === 'string' ? url : (url && url.toString ? url.toString() : '');
                const found = findChapterData(res, reqUrl);
                if (found) {
                    onChapterDataFound(found);
                }
            } catch (e) {}
        });
        return originalOpen.apply(this, arguments);
    };

    const originalFetch = window.fetch;
    window.fetch = async function(...args) {
        const response = await originalFetch.apply(this, args);
        try {
            const reqUrl = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
            const clone = response.clone();
            clone.json().then(data => {
                const found = findChapterData(data, reqUrl);
                if (found) {
                    onChapterDataFound(found);
                }
            }).catch(() => {});
        } catch (e) {}
        return response;
    };

    let lastUrl = location.href;
    new MutationObserver(() => {
        const currentUrl = location.href;
        if (currentUrl !== lastUrl) {
            lastUrl = currentUrl;
            window._tappyCapturedData = null;
        }
    }).observe(document, { subtree: true, childList: true });
})();
