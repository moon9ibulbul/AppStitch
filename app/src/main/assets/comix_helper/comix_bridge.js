/**
 * comix_bridge.js — runs at document_start in the page's MAIN world on comix.to / comix.ws.
 *
 * Fixes the "only 3 images downloaded" issue in Comix Chapter Downloader on GeckoView / Firefox Mobile:
 * 1. Forces reader settings (`reader.default.v3`, `reader.webtoon.v3`) to `preload: "all"` instead of
 *    the default `preload: "some"` (which only preloads 3 pages).
 * 2. Intercepts `JSON.parse`, `Response.prototype.json`, `TextDecoder.prototype.decode`, and `atob`
 *    using stealth Proxies (preserving `toString()`) to capture the full decrypted chapter page list
 *    (`result.pages.items`) on every chapter page regardless of `#cdlx` hash stripping or tab reload.
 * 3. Stamps `data-page="1..N"` onto all `.rpage-page` slots so Strategy B in Comix Downloader knows
 *    the true total page count `N` (instead of `dpMax = 0` -> `pageCount = 3` breaking on step 0).
 * 4. Answers `cdl:extract-pages` with the complete page list from the API or once all `.rpage-page`
 *    slots have mounted their images.
 */
(function () {
  'use strict';

  try {
    const BRIDGE_SYM = Symbol.for('astral.comix.bridge.v1');
    if (window[BRIDGE_SYM]) return;
    Object.defineProperty(window, BRIDGE_SYM, {
      value: true,
      configurable: false,
      enumerable: false,
      writable: false,
    });

    const origJsonParse = JSON.parse;
    const origJsonStringify = JSON.stringify;

    // If opened with #cdlx marker, strip it immediately so page scripts cannot see it
    const hadCdlxHash = location.hash === '#cdlx' || /cdlx/i.test(location.hash);
    if (hadCdlxHash) {
      try {
        history.replaceState(history.state, '', location.pathname + location.search);
      } catch (_) {}
    }

    // Helper to wrap native functions with a Proxy that preserves Function.prototype.toString
    function concealMethod(holder, propName, applyHandler) {
      try {
        const orig = holder && holder[propName];
        if (typeof orig !== 'function') return;
        const nativeStr = Function.prototype.toString.call(orig);
        const toStringProxy = new Proxy(Function.prototype.toString, {
          apply(target, thisArg, args) {
            if (thisArg === orig || thisArg === methodProxy) return nativeStr;
            return Reflect.apply(target, thisArg, args);
          },
        });
        const methodProxy = new Proxy(orig, {
          apply: applyHandler,
          get(target, prop) {
            if (prop === 'toString') return toStringProxy;
            return Reflect.get(target, prop, target);
          },
        });
        holder[propName] = methodProxy;
      } catch (_) {}
    }

    // ── 1. Force reader preload: "all" in localStorage ──────────────────────────
    const READER_KEYS = ['reader.default.v3', 'reader.webtoon.v3'];
    function withPreloadAll(raw) {
      try {
        const parsed = raw ? origJsonParse(raw) : null;
        const obj = parsed && typeof parsed === 'object' ? parsed : { state: {}, version: 0 };
        if (!obj.state || typeof obj.state !== 'object') obj.state = {};
        obj.state.preload = 'all';
        return origJsonStringify(obj);
      } catch (_) {
        return '{"state":{"preload":"all"},"version":0}';
      }
    }

    try {
      for (let i = 0; i < READER_KEYS.length; i++) {
        const key = READER_KEYS[i];
        const current = localStorage.getItem(key);
        localStorage.setItem(key, withPreloadAll(current));
      }
    } catch (_) {}

    if (window.Storage && window.Storage.prototype) {
      concealMethod(window.Storage.prototype, 'getItem', function (target, thisArg, args) {
        const raw = Reflect.apply(target, thisArg, args);
        const key = args && args[0];
        if (key === 'reader.default.v3' || key === 'reader.webtoon.v3') {
          return withPreloadAll(raw);
        }
        return raw;
      });
    }

    // ── 2. Report tab as visible & focused so background tabs never throttle ────
    const defProp = (obj, k, v) => {
      try {
        Object.defineProperty(obj, k, { configurable: true, enumerable: true, get: () => v });
      } catch (_) {}
    };
    defProp(document, 'hidden', false);
    defProp(document, 'visibilityState', 'visible');
    defProp(document, 'webkitHidden', false);
    defProp(document, 'webkitVisibilityState', 'visible');
    if (window.Document && window.Document.prototype) {
      concealMethod(window.Document.prototype, 'hasFocus', function () {
        return true;
      });
    }
    const swallowVis = (e) => {
      try {
        e.stopImmediatePropagation();
      } catch (_) {}
    };
    ['visibilitychange', 'webkitvisibilitychange', 'blur'].forEach((ev) => {
      try { document.addEventListener(ev, swallowVis, true); } catch (_) {}
      try { window.addEventListener(ev, swallowVis, true); } catch (_) {}
    });

    // ── 3. Capture decrypted chapter page list from API / JSON ──────────────────
    let capturedApiPages = null;
    let capturedPathname = '';

    function getActiveCapturedPages() {
      if (capturedApiPages && capturedPathname && capturedPathname !== location.pathname) {
        capturedApiPages = null;
        capturedPathname = '';
      }
      return capturedApiPages;
    }

    function extractPagesFromObject(obj) {
      if (!obj || typeof obj !== 'object') return null;
      const r = obj.result && typeof obj.result === 'object' ? obj.result : obj;
      const p = r.pages || obj.pages || (r.chapter && r.chapter.pages) || (r.data && r.data.pages);
      if (!p) return null;
      const items = Array.isArray(p)
        ? p
        : Array.isArray(p.items)
          ? p.items
          : Array.isArray(p.data)
            ? p.data
            : null;
      if (!items || !items.length) return null;
      const base = p && typeof p.baseUrl === 'string' ? p.baseUrl.replace(/\/+$/, '') : '';
      const out = [];
      for (let i = 0; i < items.length; i++) {
        const it = items[i];
        const u = typeof it === 'string' ? it : it && (it.url || it.src || it.image || it.path);
        if (typeof u === 'string' && u) {
          const full = /^https?:\/\//i.test(u)
            ? u
            : base
              ? base + '/' + u.replace(/^\/+/, '')
              : '';
          if (/^https?:\/\//i.test(full)) {
            out.push({ src: full, index: i + 1 });
          }
        }
      }
      return out.length > 0 ? out : null;
    }

    function recordCapturedPages(pages) {
      if (!Array.isArray(pages) || !pages.length) return;
      const existing = getActiveCapturedPages();
      if (!existing || pages.length >= existing.length) {
        capturedApiPages = pages;
        capturedPathname = location.pathname;
        try {
          window.__cdlPages = pages;
        } catch (_) {}
      }
    }

    function tryCaptureString(str) {
      if (typeof str !== 'string' || str.length < 60 || str.indexOf('"pages"') === -1) return;
      try {
        const parsed = origJsonParse(str);
        const pages = extractPagesFromObject(parsed);
        if (pages) recordCapturedPages(pages);
      } catch (_) {}
    }

    concealMethod(JSON, 'parse', function (target, thisArg, args) {
      const res = Reflect.apply(target, thisArg, args);
      try {
        const pages = extractPagesFromObject(res);
        if (pages) recordCapturedPages(pages);
      } catch (_) {}
      return res;
    });

    if (window.Response && window.Response.prototype) {
      concealMethod(window.Response.prototype, 'json', function (target, thisArg, args) {
        const p = Reflect.apply(target, thisArg, args);
        if (p && typeof p.then === 'function') {
          p.then(function (data) {
            try {
              const pages = extractPagesFromObject(data);
              if (pages) recordCapturedPages(pages);
            } catch (_) {}
          }).catch(function () {});
        }
        return p;
      });
    }

    if (window.TextDecoder && window.TextDecoder.prototype) {
      concealMethod(window.TextDecoder.prototype, 'decode', function (target, thisArg, args) {
        const str = Reflect.apply(target, thisArg, args);
        try {
          tryCaptureString(str);
        } catch (_) {}
        return str;
      });
    }

    if (typeof window.atob === 'function') {
      concealMethod(window, 'atob', function (target, thisArg, args) {
        const str = Reflect.apply(target, thisArg, args);
        try {
          tryCaptureString(str);
        } catch (_) {}
        return str;
      });
    }

    // ── 4. Stamp data-page="1..N" on .rpage-page slots & collect mounted DOM pages
    function syncReaderDomSlots() {
      try {
        const slots = document.querySelectorAll('.rpage-page');
        if (!slots || !slots.length) return null;
        const total = slots.length;
        const mounted = [];
        let allHaveImg = true;
        let hasCanvas = false;

        for (let i = 0; i < total; i++) {
          const slot = slots[i];
          const pageNum = i + 1;
          const pageStr = String(pageNum);
          if (slot.getAttribute('data-page') !== pageStr) {
            slot.setAttribute('data-page', pageStr);
          }
          if (slot.querySelector('canvas')) {
            hasCanvas = true;
          }
          const img = slot.querySelector('img.rpage-page__img') || slot.querySelector('img');
          if (img) {
            const alt = img.getAttribute('alt') || '';
            if (!/\d+/.test(alt)) {
              img.setAttribute('alt', 'Page ' + pageNum);
            }
            const src = img.currentSrc || img.src || img.getAttribute('data-src') || '';
            if (/^https?:\/\//i.test(src) && !/^data:/i.test(src)) {
              mounted.push({ src: src, index: pageNum });
            } else {
              allHaveImg = false;
            }
          } else {
            allHaveImg = false;
          }
        }

        // Only return DOM pages directly if there are no canvas-scrambled slots and
        // EVERY .rpage-page slot in the chapter has mounted its image URL.
        if (!hasCanvas && allHaveImg && mounted.length === total && total > 0) {
          return mounted;
        }
      } catch (_) {}
      return null;
    }

    // Set up MutationObserver + periodic sync on chapter pages
    let domObserverStarted = false;
    function startDomSync() {
      if (domObserverStarted) return;
      const root = document.documentElement || document.body;
      if (!root) return;
      domObserverStarted = true;
      try {
        const obs = new MutationObserver(function () {
          syncReaderDomSlots();
        });
        obs.observe(root, { childList: true, subtree: true });
      } catch (_) {}
      setInterval(syncReaderDomSlots, 200);
    }

    if (document.documentElement) {
      startDomSync();
    } else {
      document.addEventListener('DOMContentLoaded', startDomSync, { once: true });
    }

    // ── 5. Answer cdl:extract-pages (Strategy 0 in Comix Chapter Downloader) ────
    let autoScrollRunning = false;
    function triggerBackgroundPreloadScroll() {
      if (autoScrollRunning) return;
      const slots = document.querySelectorAll('.rpage-page');
      if (!slots || slots.length <= 3) return;
      autoScrollRunning = true;
      (async function () {
        try {
          const sc =
            document.querySelector('.rpage-main') ||
            document.scrollingElement ||
            document.documentElement;
          if (!sc) return;
          try { sc.style.scrollBehavior = 'auto'; } catch (_) {}
          for (let i = 0; i < slots.length; i++) {
            if (getActiveCapturedPages()) break;
            const done = syncReaderDomSlots();
            if (done && done.length === slots.length) break;
            const slot = slots[i];
            if (slot && !slot.querySelector('img.rpage-page__img[src]:not([src=""])')) {
              try {
                slot.scrollIntoView({ behavior: 'instant', block: 'center' });
              } catch (_) {
                try { sc.scrollTop = slot.offsetTop; } catch (__) {}
              }
              await new Promise((r) => setTimeout(r, 60));
            }
          }
        } catch (_) {
        } finally {
          autoScrollRunning = false;
        }
      })();
    }

    window.addEventListener('cdl:extract-pages', function (event) {
      try {
        if (!event || !event.detail || typeof event.detail !== 'object') return;
        const apiPages = getActiveCapturedPages();
        if (apiPages && apiPages.length > 0) {
          event.detail.pages = apiPages.map(function (page, idx) {
            return { src: page.src, index: page.index || idx + 1 };
          });
          return;
        }
        const domPages = syncReaderDomSlots();
        const totalSlots = document.querySelectorAll('.rpage-page').length;
        if (domPages && domPages.length > 0 && domPages.length >= totalSlots && totalSlots > 3) {
          event.detail.pages = domPages.map(function (page, idx) {
            return { src: page.src, index: page.index || idx + 1 };
          });
          return;
        }
        // Trigger background slot scroll so preload: "all" mounts any remaining slots
        triggerBackgroundPreloadScroll();
      } catch (_) {}
    });
  } catch (_) {}
})();
