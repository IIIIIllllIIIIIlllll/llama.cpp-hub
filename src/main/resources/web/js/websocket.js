let websocket = null;
let reconnectAttempts = 0;
const reconnectInterval = 1000;
const wsDecoder = new TextDecoder('utf-8');
let reconnectTimer = null;
let populateLogFilterGen = 0;

function interpolate(str, params) {
    if (!params || typeof params !== 'object') return str;
    return str.replace(/{(\w+)}/g, (m, k) => (params[k] !== undefined ? params[k] : m));
}

function wsT(key, fallback, params) {
    let result;
    if (window.I18N && typeof window.I18N.t === 'function') {
        result = window.I18N.t(key, fallback);
    } else {
        result = fallback == null ? key : fallback;
    }
    if (params && typeof params === 'object') {
        result = interpolate(result, params);
    }
    return result;
}

window.remoteNodes = [];

async function fetchRemoteNodes() {
    try {
        const result = await getNodeList();
        if (result && result.success && Array.isArray(result.data)) {
            window.remoteNodes = result.data;
        }
    } catch (e) {
        console.error('获取远程节点列表失败:', e);
    }
}

function triggerModelListLoad() {
    if (typeof loadModels !== 'function') return;
    if (window.I18N) {
        loadModels();
        return;
    }
    let done = false;
    const handler = () => {
        if (done) return;
        done = true;
        window.removeEventListener('i18n:ready', handler);
        loadModels();
    };
    window.addEventListener('i18n:ready', handler);
}

function initWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws`;

    try {
        websocket = new WebSocket(wsUrl);
        websocket.onopen = function(event) {
            console.log('WebSocket Connected');
            reconnectAttempts = 0;
            if (reconnectTimer) {
                clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
            websocket.send(JSON.stringify({ type: 'connect', message: 'Connected', timestamp: new Date().toISOString() }));
            triggerModelListLoad();
        };
        websocket.onmessage = function(event) {
            handleWebSocketMessage(event.data);
        };
        websocket.onclose = function(event) {
            console.log('WebSocket Closed');
            reconnectAttempts++;
            if (reconnectTimer) {
                clearTimeout(reconnectTimer);
            }
            reconnectTimer = setTimeout(initWebSocket, reconnectInterval);
        };
        websocket.onerror = function(error) { console.error('WebSocket Error:', error); };
    } catch (error) { console.error('WebSocket Init Failed:', error); }
}

function handleWebSocketMessage(message) {
    try {
        const data = JSON.parse(message);
        if (data.type) {
            switch (data.type) {
                case 'modelLoadStart': handleModelLoadStartEvent(data); break;
                case 'modelLoad': handleModelLoadEvent(data); break;
                case 'modelStop': handleModelStopEvent(data); break;
                case 'notification': showToast(data.title || '通知', data.message || '', data.level || 'info'); break;
                case 'model_status': handleModelStatusUpdate(data); break;
                case 'model_slots': handleModelSlotsUpdate(data); break;
                case 'model_busy': handleModelBusyEvent(data); break;
                case 'app_update':
                    if (typeof window.onAppUpdateEvent === 'function') {
                        window.onAppUpdateEvent(data);
                    }
                    break;
                case 'download_progress':
                case 'download_update':
                    if (typeof SettingsPage !== 'undefined' && typeof SettingsPage.onLlamaCppDownloadProgress === 'function') {
                        SettingsPage.onLlamaCppDownloadProgress(data);
                    }
                    break;
                case 'console':
                    {
                        if (!document.getElementById('main-console')) break;
                        let text = '';
                        if (typeof data.line64 === 'string') {
                            const bin = atob(data.line64);
                            const bytes = new Uint8Array(bin.length);
                            for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                            text = wsDecoder.decode(bytes);
                        } else if (typeof data.line === 'string') {
                            text = data.line;
                        }
                        if (!text) break;
                        if (data.nodeId && typeof appendRemoteLogLine === 'function') {
                            appendRemoteLogLine(data.nodeId, text, data.modelId || 'system');
                        } else if (typeof appendLogLine === 'function') {
                            appendLogLine(text, data.timestamp, data.modelId || 'system');
                        }
                    }
                    break;
            }
        }
    } catch (error) {}
}

function applyModelPatch(modelId, patch, nodeId) {
    try {
        if (!modelId) return;
        if (!Array.isArray(currentModelsData)) return;
        let i;
        if (nodeId) {
            i = currentModelsData.findIndex(m => m && (m.id === modelId || m.alias === modelId) && m.nodeId === nodeId);
        } else {
            i = currentModelsData.findIndex(m => m && (m.id === modelId || m.alias === modelId) && (!m.nodeId || m.nodeId === 'local'));
        }
        if (i < 0) return;
        const prev = currentModelsData[i] || {};
        currentModelsData[i] = Object.assign({}, prev, patch || {});
        if (typeof sortAndRenderModels === 'function') sortAndRenderModels();
        var activeTab = typeof window.getConsoleActiveNodeTab === 'function' ? window.getConsoleActiveNodeTab() : 'local';
        if (typeof populateLogFilter === 'function') populateLogFilter(activeTab);
        const loadedCount = currentModelsData.filter(m => m && m.isLoaded).length;
        if (typeof updateModelCountBadge === 'function') updateModelCountBadge(loadedCount, currentModelsData.length);
    } catch (e) {}
}

function handleModelLoadStartEvent(data) {
    if (!data || !data.modelId) return;
    if (typeof showModelLoadingState === 'function') showModelLoadingState(data.modelId, data.nodeId);
    applyModelPatch(data.modelId, { isLoading: true, isLoaded: false, status: 'stopped', port: data.port ?? null, slots: [] }, data.nodeId);
    if (typeof updateModelSlotsDom === 'function') {
        updateModelSlotsDom(data.modelId, [], data.nodeId);
    }
}

function handleModelStatusUpdate(data) {
    if (data.modelId && data.status) {
        applyModelPatch(data.modelId, { status: data.status }, data.nodeId);
    }
}

function handleModelLoadEvent(data) {
    if (typeof removeModelLoadingState === 'function') removeModelLoadingState(data.modelId, data.nodeId);
    const nodeLabel = data.nodeId ? `[${data.nodeId}]` : '';
    const msgKey = data.success ? 'api.model.load.message.success' : 'api.model.load.message.failure';
    const msgFallback = `模型 ${nodeLabel}${data.modelId} ${data.success ? '加载成功' : '加载失败'}`;
    const msg = wsT(msgKey, msgFallback, { nodeLabel, modelId: data.modelId });
    showToast(wsT('api.model.load.title', '模型加载'), msg, data.success ? 'success' : 'error');

    if (window.pendingModelLoad && window.pendingModelLoad.modelId === data.modelId) {
        window.pendingModelLoad = null;
    }
    if (data.success) {
        applyModelPatch(data.modelId, { isLoading: false, isLoaded: true, status: 'running', port: data.port ?? null, slots: [] }, data.nodeId);
    } else {
        applyModelPatch(data.modelId, { isLoading: false, isLoaded: false, status: 'stopped', port: null, slots: [] }, data.nodeId);
    }
    if (typeof updateModelSlotsDom === 'function') {
        updateModelSlotsDom(data.modelId, [], data.nodeId);
    }
}

function handleModelStopEvent(data) {
    const nodeLabel = data.nodeId ? `[${data.nodeId}]` : '';
    const msgKey = data.success ? 'api.model.stop.message.success' : 'api.model.stop.message.failure';
    const msgFallback = `模型 ${nodeLabel}${data.modelId} ${data.success ? '停止成功' : '停止失败'}`;
    const msg = wsT(msgKey, msgFallback, { nodeLabel, modelId: data.modelId });
    showToast(wsT('api.model.stop.title', '模型停止'), msg, data.success ? 'success' : 'error');
    if (typeof removeModelLoadingState === 'function') removeModelLoadingState(data.modelId, data.nodeId);
    applyModelPatch(data.modelId, { isLoading: false, isLoaded: false, status: 'stopped', port: null, slots: [] }, data.nodeId);
    if (typeof updateModelSlotsDom === 'function') {
        updateModelSlotsDom(data.modelId, [], data.nodeId);
    }
}

function handleModelSlotsUpdate(data) {
    if (!data || !data.modelId) return;
    const slots = Array.isArray(data.slots) ? data.slots : [];
    let i;
    if (data.nodeId) {
        i = Array.isArray(currentModelsData) ? currentModelsData.findIndex(m => m && m.id === data.modelId && m.nodeId === data.nodeId) : -1;
    } else {
        i = Array.isArray(currentModelsData) ? currentModelsData.findIndex(m => m && m.id === data.modelId && (!m.nodeId || m.nodeId === 'local')) : -1;
    }
    if (i >= 0) {
        currentModelsData[i].slots = slots;
    }
    if (typeof updateModelSlotsDom === 'function') {
        updateModelSlotsDom(data.modelId, slots, data.nodeId);
    }
}

function handleModelBusyEvent(data) {
    if (!data || !data.modelId) return;
    applyModelPatch(data.modelId, { busy: !!data.busy }, data.nodeId);
}

async function populateLogFilter(activeNodeId) {
    const gen = ++populateLogFilterGen;
    const container = document.getElementById('consoleModelListItems');
    if (!container) return;
    const currentFilter = window._consoleCurrentFilter || '';
    // 清除动态添加的模型项（保留 All Logs）
    while (container.children.length > 1) {
        container.removeChild(container.lastChild);
    }
    const seen = {};
    if (Array.isArray(currentModelsData)) {
        currentModelsData.slice().sort(function (a, b) {
            if (a && a.isLoaded && !(b && b.isLoaded)) return -1;
            if (!(a && a.isLoaded) && b && b.isLoaded) return 1;
            return 0;
        }).forEach(function (m) {
            if (m && m.id) {
                const mNodeId = m.nodeId || 'local';
                if (activeNodeId && mNodeId !== activeNodeId) return;
                const key = m.id + '|||' + mNodeId;
                seen[key] = true;
                const item = document.createElement('div');
                item.className = 'console-model-item' + (m.isLoaded ? ' loaded' : ' offline');
                item.dataset.filter = key;
                item.dataset.nodeId = mNodeId;
                const nameSpan = document.createElement('span');
                nameSpan.className = 'console-model-item-name';
                nameSpan.textContent = (m.alias || m.id) + (m.isLoaded ? '' : ' (offline)');
                item.appendChild(nameSpan);
                container.appendChild(item);
            }
        });
    }
    try {
        const fetchLogModels = async function (nodeId) {
            try {
                const url = nodeId ? '/api/sys/log-models?nodeId=' + encodeURIComponent(nodeId) : '/api/sys/log-models';
                const resp = await fetch(url);
                const data = await resp.json();
                if (data.success && Array.isArray(data.data)) {
                    return { ids: data.data, nodeId: nodeId || 'local' };
                }
            } catch (e) {}
            return null;
        };
        const nodesToFetch = activeNodeId
            ? (activeNodeId !== 'local' ? (window.remoteNodes || []).filter(function (n) { return n.nodeId === activeNodeId && n.enabled !== false; }).map(function (n) { return n.nodeId; }) : [null])
            : [null, ...(window.remoteNodes || []).filter(function (n) { return n.enabled !== false; }).map(function (n) { return n.nodeId; })];
        const results = await Promise.all(nodesToFetch.map(function (nid) { return fetchLogModels(nid); }));
        if (gen !== populateLogFilterGen) return;
        results.forEach(function (res) {
            if (!res) return;
            const nodeId = res.nodeId;
            res.ids.forEach(function (id) {
                const key = id + '|||' + nodeId;
                if (!seen[key]) {
                    seen[key] = true;
                    const item = document.createElement('div');
                    item.className = 'console-model-item offline';
                    item.dataset.filter = key;
                    item.dataset.nodeId = nodeId;
                    const nameSpan = document.createElement('span');
                    nameSpan.className = 'console-model-item-name';
                    nameSpan.textContent = id + ' (offline)';
                    item.appendChild(nameSpan);
                    container.appendChild(item);
                }
            });
        });
    } catch (e) {}
    if (gen === populateLogFilterGen) {
        container.querySelectorAll('.console-model-item').forEach(function (item) {
            item.classList.toggle('active', item.dataset.filter === currentFilter);
        });
    }
}

document.addEventListener('click', function (e) {
    var item = e.target.closest('.console-model-item');
    if (item) {
        if (typeof setLogFilter === 'function') {
            var filter = item.dataset.filter || '';
            var nodeId = item.dataset.nodeId || 'local';
            setLogFilter(filter, nodeId);
        }
        return;
    }
});

document.addEventListener('change', function (e) {
    if (e.target && e.target.id === 'logFilterSelect') {
        if (typeof setLogFilter === 'function') {
            const opt = e.target.options[e.target.selectedIndex];
            const nodeId = opt && opt.dataset.nodeId ? opt.dataset.nodeId : 'local';
            setLogFilter(e.target.value, nodeId);
        }
    }
});
