let websocket = null;
let reconnectAttempts = 0;
const reconnectInterval = 1000;
const wsDecoder = new TextDecoder('utf-8');
let reconnectTimer = null;

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
                case 'console':
                    {
                        const consoleMain = document.getElementById('main-console');
                        if (!consoleMain) break;
                        let text = '';
                        if (typeof data.line64 === 'string') {
                            const bin = atob(data.line64);
                            const bytes = new Uint8Array(bin.length);
                            for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                            text = wsDecoder.decode(bytes);
                        } else if (typeof data.line === 'string') {
                            text = data.line;
                        }
                        if (text && typeof appendLogLine === 'function') {
                            if (data.nodeId) {
                                const nodeKey = data.modelId ? '[' + data.nodeId + '/' + data.modelId + ']' : '[' + data.nodeId + ']';
                                text = nodeKey + ' ' + text;
                            }
                            appendLogLine(text);
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
            i = currentModelsData.findIndex(m => m && m.id === modelId && m.nodeId === nodeId);
        } else {
            i = currentModelsData.findIndex(m => m && m.id === modelId);
        }
        if (i < 0) return;
        const prev = currentModelsData[i] || {};
        currentModelsData[i] = Object.assign({}, prev, patch || {});
        if (typeof sortAndRenderModels === 'function') sortAndRenderModels();
        const loadedCountEl = document.getElementById('loadedModelsCount');
        if (loadedCountEl) {
            const loadedCount = currentModelsData.filter(m => m && m.isLoaded).length;
            loadedCountEl.textContent = loadedCount;
        }
    } catch (e) {}
}

function handleModelLoadStartEvent(data) {
    if (!data || !data.modelId) return;
    if (typeof showModelLoadingState === 'function') showModelLoadingState(data.modelId);
    applyModelPatch(data.modelId, { isLoading: true, isLoaded: false, status: 'stopped', port: data.port ?? null, slots: [] }, data.nodeId);
    if (typeof updateModelSlotsDom === 'function') {
        updateModelSlotsDom(data.modelId, []);
    }
}

function handleModelStatusUpdate(data) {
    if (data.modelId && data.status) {
        applyModelPatch(data.modelId, { status: data.status }, data.nodeId);
    }
}

function handleModelLoadEvent(data) {
    if (typeof removeModelLoadingState === 'function') removeModelLoadingState(data.modelId);
    const nodeLabel = data.nodeId ? `[${data.nodeId}]` : '';
    const action = data.success ? '成功' : '失败';
    showToast('模型加载', `模型 ${nodeLabel}${data.modelId} 加载${action}`, data.success ? 'success' : 'error');

    if (window.pendingModelLoad && window.pendingModelLoad.modelId === data.modelId) {
        window.pendingModelLoad = null;
    }
    if (data.success) {
        applyModelPatch(data.modelId, { isLoading: false, isLoaded: true, status: 'running', port: data.port ?? null, slots: [] }, data.nodeId);
    } else {
        applyModelPatch(data.modelId, { isLoading: false, isLoaded: false, status: 'stopped', port: null, slots: [] }, data.nodeId);
    }
    if (typeof updateModelSlotsDom === 'function') {
        updateModelSlotsDom(data.modelId, []);
    }
}

function handleModelStopEvent(data) {
    const nodeLabel = data.nodeId ? `[${data.nodeId}]` : '';
    showToast('模型停止', `模型 ${nodeLabel}${data.modelId} 停止${data.success ? '成功' : '失败'}`, data.success ? 'success' : 'error');
    if (data.success) {
        if (typeof removeModelLoadingState === 'function') removeModelLoadingState(data.modelId);
        applyModelPatch(data.modelId, { isLoading: false, isLoaded: false, status: 'stopped', port: null, slots: [] }, data.nodeId);
        if (typeof updateModelSlotsDom === 'function') {
            updateModelSlotsDom(data.modelId, []);
        }
    }
}

function handleModelSlotsUpdate(data) {
    if (!data || !data.modelId) return;
    const slots = Array.isArray(data.slots) ? data.slots : [];
    let i;
    if (data.nodeId) {
        i = Array.isArray(currentModelsData) ? currentModelsData.findIndex(m => m && m.id === data.modelId && m.nodeId === data.nodeId) : -1;
    } else {
        i = Array.isArray(currentModelsData) ? currentModelsData.findIndex(m => m && m.id === data.modelId) : -1;
    }
    if (i >= 0) {
        currentModelsData[i].slots = slots;
    }
    if (typeof updateModelSlotsDom === 'function') {
        updateModelSlotsDom(data.modelId, slots);
    }
}
