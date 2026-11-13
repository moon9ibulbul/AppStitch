(function() {
    'use strict';

    function requestCookies() {
        try {
            browser.runtime.sendMessage({
                action: "getCookies",
                domain: window.location.hostname
            }).then(response => {
                if (response && response.cookieString) {
                    const str = response.cookieString;
                    if (document.documentElement) {
                        document.documentElement.setAttribute('data-astral-cookies', str);
                    }
                    try {
                        window.__ASTRAL_COOKIES__ = str;
                    } catch(e) {}
                    try {
                        window.dispatchEvent(new CustomEvent('astral:cookies-ready', { detail: str }));
                    } catch(e) {}
                }
            }).catch(() => {});
        } catch(e) {}
    }

    requestCookies();

    window.addEventListener('astral:request-cookies', function() {
        requestCookies();
    });

    setInterval(requestCookies, 2000);
})();
