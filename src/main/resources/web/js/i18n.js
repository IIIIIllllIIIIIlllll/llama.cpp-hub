(function () {
    const SUPPORTED_LANGS = ['zh-CN', 'en-US'];
    const STORAGE_KEY = 'lang';

    function normalizeLang(lang) {
        if (!lang) return null;
        const normalized = String(lang).trim().replace('_', '-');
        if (!normalized) return null;
        if (SUPPORTED_LANGS.includes(normalized)) return normalized;
        const lower = normalized.toLowerCase();
        if (lower.startsWith('zh')) return 'zh-CN';
        if (lower.startsWith('en')) return 'en-US';
        return null;
    }

    function getLangFromQuery() {
        try {
            const params = new URLSearchParams(window.location.search || '');
            return params.get('lang');
        } catch (e) {
            return null;
        }
    }

    function pickLang() {
        const fromQuery = normalizeLang(getLangFromQuery());
        if (fromQuery) {
            try {
                localStorage.setItem(STORAGE_KEY, fromQuery);
            } catch (e) {
            }
            return fromQuery;
        }
        const fromStorage = normalizeLang((function () {
            try {
                return localStorage.getItem(STORAGE_KEY);
            } catch (e) {
                return null;
            }
        })());
        if (fromStorage) return fromStorage;
        const browser = normalizeLang((navigator && (navigator.language || (navigator.languages && navigator.languages[0]))) || null);
        return browser || 'zh-CN';
    }

    async function loadBundle(lang) {
        const response = await fetch(`/i18n/${encodeURIComponent(lang)}.json`, { cache: 'no-cache' });
        if (!response.ok) {
            throw new Error('Failed to load i18n bundle');
        }
        return await response.json();
    }

    function translate(bundle, key, fallback, params) {
        let result;
        if (bundle && Object.prototype.hasOwnProperty.call(bundle, key)) {
            const v = bundle[key];
            result = v == null ? '' : String(v);
        } else {
            result = fallback == null ? key : fallback;
        }

        if (params && typeof params === 'object') {
            result = result.replace(/{(\w+)}/g, (match, k) => (params[k] !== undefined ? params[k] : match));
        }
        return result;
    }

    function applyTranslations(bundle, lang) {
        window.I18N = {
            lang,
            bundle,
                t: function (key, arg2) {
                    let fallback = null;
                    let params = null;
                    if (typeof arg2 === 'string') {
                        fallback = arg2;
                    } else if (arg2 && typeof arg2 === 'object') {
                        params = arg2;
                    }
                    return translate(bundle, key, fallback, params);
                }
        };

        const nodes = document.querySelectorAll('[data-i18n]');
        nodes.forEach((node) => {
            const key = node.getAttribute('data-i18n');
            if (!key) return;
            const attr = node.getAttribute('data-i18n-attr');
            if (attr) {
                const attrs = attr.split(',').map(a => a.trim());
                for (const a of attrs) {
                    if (a) {
                        const current = node.getAttribute(a) || '';
                        node.setAttribute(a, translate(bundle, key, current));
                    }
                }
                return;
            }
            const currentText = node.textContent || '';
            node.textContent = translate(bundle, key, currentText);
        });

        if (lang) {
            document.documentElement.setAttribute('lang', lang);
        }
        try {
            window.dispatchEvent(new CustomEvent('i18n:ready', { detail: { lang } }));
        } catch (e) {
            window.dispatchEvent(new Event('i18n:ready'));
        }
        if (typeof applyTheme === 'function') {
            const saved = (function(){ try { return localStorage.getItem('llama-hub-theme'); } catch(e){} })();
            if (saved) applyTheme(saved);
            else if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) applyTheme('dark');
            else applyTheme('light');
        }
    }

    async function initI18n() {
        const lang = pickLang();
        try {
            const bundle = await loadBundle(lang);
            applyTranslations(bundle, lang);
        } catch (e) {
            if (lang !== 'zh-CN') {
                try {
                    const fallbackBundle = await loadBundle('zh-CN');
                    applyTranslations(fallbackBundle, 'zh-CN');
                    return;
                } catch (e2) {
                }
            }
            applyTranslations({}, lang);
        }
    }

    document.addEventListener('DOMContentLoaded', initI18n);

    window.setLang = function (lang) {
        const normalized = normalizeLang(lang) || 'zh-CN';
        try {
            localStorage.setItem(STORAGE_KEY, normalized);
        } catch (e) {
        }
        const params = new URLSearchParams(window.location.search || '');
        params.set('lang', normalized);
        const query = params.toString();
        const url = window.location.pathname + (query ? `?${query}` : '') + (window.location.hash || '');
        window.location.replace(url);
    };
})();
