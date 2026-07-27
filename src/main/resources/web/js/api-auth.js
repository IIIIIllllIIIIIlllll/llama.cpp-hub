// API Key auto-injection: read from Cookie, inject as Bearer header on fetch
(function () {
    var apiKey = null;
    var cookies = document.cookie.split(';');
    for (var i = 0; i < cookies.length; i++) {
        var c = cookies[i].trim();
        if (c.indexOf('lh-api-key=') === 0) {
            try { apiKey = decodeURIComponent(c.substring(11)); } catch (e) { apiKey = c.substring(11); }
            break;
        }
    }
    if (!apiKey) return;
    var origFetch = window.fetch;
    window.fetch = function (input, init) {
        init = init || {};
        init.headers = init.headers || {};
        // Skip if Authorization already set
        if (typeof init.headers === 'string') return origFetch(input, init);
        if (init.headers instanceof Headers) {
            if (init.headers.get('Authorization')) return origFetch(input, init);
            init.headers.set('Authorization', 'Bearer ' + apiKey);
        } else {
            if (init.headers['Authorization']) return origFetch(input, init);
            init.headers['Authorization'] = 'Bearer ' + apiKey;
        }
        return origFetch(input, init);
    };
})();
