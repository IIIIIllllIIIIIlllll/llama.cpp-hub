/**
 * Shared cache for /api/node/list.
 *
 * The node list rarely changes, but it was being fetched from the network by
 * many pages and UI panels. This module caches the response in memory and in
 * localStorage so subsequent callers reuse the same data without extra requests.
 *
 * Behaviour:
 * - If a cache exists (memory or localStorage), it is returned immediately.
 * - On the first request of a page session, a background network refresh is
 *   triggered so that backend config changes (e.g. copied config files) are
 *   picked up without blocking the UI.
 * - If there is no cache at all, the network is awaited once.
 *
 * - getNodeList()      returns the cached list, refreshing in the background
 *                      on the first call of the session if a cache exists.
 * - refreshNodeList()  forces a network refresh and updates the cache.
 * - invalidateNodeListCache() clears both in-memory and persisted cache.
 */
(function () {
    'use strict';

    const STORAGE_KEY = 'llamacpp_node_list_cache_v1';

    let memoryCache = null;
    let inFlightPromise = null;
    let backgroundRefreshedThisSession = false;

    function readStorage() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (!raw) return null;
            const parsed = JSON.parse(raw);
            if (parsed && parsed.success === true && Array.isArray(parsed.data)) {
                return parsed;
            }
        } catch (e) {
            // Ignore malformed storage data.
        }
        return null;
    }

    function writeStorage(result) {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(result));
        } catch (e) {
            // Ignore storage errors (e.g. private mode).
        }
    }

    function clearStorage() {
        try {
            localStorage.removeItem(STORAGE_KEY);
        } catch (e) {
            // Ignore.
        }
    }

    async function fetchFromNetwork() {
        const resp = await fetch('/api/node/list');
        if (!resp.ok) {
            throw new Error('HTTP ' + resp.status);
        }
        const result = await resp.json();
        if (!result || result.success !== true || !Array.isArray(result.data)) {
            throw new Error('invalid node list response');
        }
        memoryCache = result;
        writeStorage(result);
        return result;
    }

    function triggerBackgroundRefresh() {
        if (backgroundRefreshedThisSession) return;
        backgroundRefreshedThisSession = true;
        fetchFromNetwork().catch(function () {
            // Background refresh failures are silent; the existing cache stays usable.
        });
    }

    window.getNodeList = async function (options) {
        options = options || {};
        if (options.refresh) {
            inFlightPromise = null;
            backgroundRefreshedThisSession = true;
            return fetchFromNetwork();
        }
        if (memoryCache) {
            triggerBackgroundRefresh();
            return memoryCache;
        }
        const stored = readStorage();
        if (stored) {
            memoryCache = stored;
            triggerBackgroundRefresh();
            return memoryCache;
        }
        if (inFlightPromise) {
            return inFlightPromise;
        }
        backgroundRefreshedThisSession = true;
        inFlightPromise = fetchFromNetwork().finally(function () {
            inFlightPromise = null;
        });
        return inFlightPromise;
    };

    window.refreshNodeList = async function () {
        return window.getNodeList({ refresh: true });
    };

    window.invalidateNodeListCache = function () {
        memoryCache = null;
        backgroundRefreshedThisSession = false;
        clearStorage();
    };
})();
