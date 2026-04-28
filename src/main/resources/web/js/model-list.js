 function t(key, fallback) {
    if (window.I18N && typeof window.I18N.t === 'function') {
        return window.I18N.t(key, fallback);
    }
    return fallback == null ? key : fallback;
}

function getNodeColor(nodeId) {
    if (!nodeId || nodeId === 'local') return '';
    var hash = 0;
    for (var i = 0; i < nodeId.length; i++) {
        hash = ((hash << 5) - hash) + nodeId.charCodeAt(i);
        hash |= 0;
    }
    var hue = ((Math.abs(hash) * 137.508) % 360).toFixed(1);
    return hue;
}

function formatBuildCreatedTime(createdTime) {
    if (!createdTime) return '';
    const date = new Date(createdTime);
    if (Number.isNaN(date.getTime())) return createdTime;
    return date.toLocaleString();
}

function renderBuildInfo(buildInfo) {
    const container = document.getElementById('appBuildInfo');
    const versionEl = document.getElementById('appVersionText');
    const createdTimeEl = document.getElementById('appBuildTimeText');
    if (!container || !versionEl || !createdTimeEl) return;

    const tag = buildInfo && buildInfo.tag ? String(buildInfo.tag).trim() : '';
    const version = buildInfo && buildInfo.version ? String(buildInfo.version).trim() : '';
    const createdTime = buildInfo && buildInfo.createdTime ? String(buildInfo.createdTime).trim() : '';
    const displayVersion = tag || (version ? (version.startsWith('v') ? version : `v${version}`) : '--');
    
    versionEl.textContent = displayVersion == '{tag}' ? 'unkown' : displayVersion;
    createdTimeEl.textContent = createdTime == '{createdTime}' ? 'unkown' : formatBuildCreatedTime(createdTime);

    const titleParts = [];
    if (tag) titleParts.push(`tag: ${tag}`);
    if (version) titleParts.push(`version: ${version}`);
    if (createdTime) titleParts.push(`created: ${createdTime}`);
    container.title = titleParts.join('\n');
}

function loadBuildInfo() {
    fetch('/api/sys/version')
        .then(response => response.json())
        .then(result => {
            if (!result || !result.success) {
                throw new Error(result && result.error ? result.error : 'load build info failed');
            }
            renderBuildInfo(result.data || {});
        })
        .catch(error => {
            console.error('Failed to load build info:', error);
            renderBuildInfo({});
        });
}

function loadModels() {
    const modelsList = document.getElementById('modelsList');
    fetch('/api/models/list')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const allModels = data.models || [];
                if (data.success) {
                    const totalCount = (allModels || []).length;
                    const el = document.getElementById('totalModelsCount');
                    if (el) el.textContent = totalCount;
                }
                return fetch('/api/models/loaded')
                    .then(response => response.json())
                    .then(loadedData => {
                        const loadedModels = loadedData.success ? (loadedData.models || []) : [];
                        const loadedByKey = {};
                        loadedModels.forEach(m => {
                            const key = modelCompositeKey(m.id, m.nodeId);
                            loadedByKey[key] = m;
                        });
                        const modelsWithStatus = allModels.map(model => {
                            const key = modelCompositeKey(model.id, model.nodeId);
                            const loadedModel = loadedByKey[key];
                            return {
                                ...model,
                                isLoading: !!model.isLoading,
                                isLoaded: !!loadedModel,
                                status: loadedModel ? (loadedModel.status || 'loaded') : 'stopped',
                                port: loadedModel ? loadedModel.port : null
                            };
                        });
                        currentModelsData = modelsWithStatus;
                        sortAndRenderModels();
                        populateNodeFilter();
                        if (loadedData.success) {
                            const loadedCount = (loadedData.models || []).length;
                            const el = document.getElementById('loadedModelsCount');
                            if (el) el.textContent = loadedCount;
                        }
                    });
            } else {
                throw new Error(data.error);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            modelsList.innerHTML = `
                        <div class="empty-state">
                            <div class="empty-state-icon"><i class="fas fa-exclamation-triangle"></i></div>
                            <div class="empty-state-title">${t('common.load_failed', '加载失败')}</div>
                            <div class="empty-state-text">${error.message || t('common.network_error', '网络错误')}</div>
                            <button class="btn btn-primary" onclick="loadModels()">${t('common.retry', '重试')}</button>
                        </div>
                    `;
        });
}

const _modelIconMemoryCache = new Map();
const _modelIconObjectUrlCache = new Map();
let _modelIconObjectUrlRevokeBound = false;

function getCachedModelIconDataUrl(iconPath) {
    if (!iconPath) return null;
    if (_modelIconMemoryCache.has(iconPath)) return _modelIconMemoryCache.get(iconPath);
    return null;
}

function _bindModelIconObjectUrlRevokeOnce() {
    if (_modelIconObjectUrlRevokeBound) return;
    _modelIconObjectUrlRevokeBound = true;
    try {
        window.addEventListener('beforeunload', () => {
            try {
                for (const url of _modelIconObjectUrlCache.values()) {
                    try { URL.revokeObjectURL(url); } catch (e) {}
                }
                _modelIconObjectUrlCache.clear();
            } catch (e) {}
        }, { once: true });
    } catch (e) {}
}

function _dataUrlToBlob(dataUrl) {
    try {
        const s = String(dataUrl || '');
        const idx = s.indexOf(',');
        if (idx < 0) return null;
        const meta = s.slice(0, idx);
        const b64 = s.slice(idx + 1);
        const m = meta.match(/^data:([^;]+);base64$/i);
        if (!m) return null;
        const mime = m[1] || 'application/octet-stream';
        const bin = atob(b64);
        const len = bin.length;
        const bytes = new Uint8Array(len);
        for (let i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
        return new Blob([bytes], { type: mime });
    } catch (e) {
        return null;
    }
}

function getCachedModelIconObjectUrl(iconPath) {
    if (!iconPath) return null;
    if (_modelIconObjectUrlCache.has(iconPath)) return _modelIconObjectUrlCache.get(iconPath);
    const dataUrl = getCachedModelIconDataUrl(iconPath);
    if (!dataUrl) return null;
    const blob = _dataUrlToBlob(dataUrl);
    if (!blob) return null;
    try {
        const url = URL.createObjectURL(blob);
        _bindModelIconObjectUrlRevokeOnce();
        _modelIconObjectUrlCache.set(iconPath, url);
        return url;
    } catch (e) {
        return null;
    }
}

function _tryCacheModelIconFromImg(img, iconPath) {
    try {
        if (!img || !iconPath) return null;
        const w = img.naturalWidth;
        const h = img.naturalHeight;
        if (!w || !h) return null;

        const canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        if (!ctx) return null;
        ctx.drawImage(img, 0, 0, w, h);

        const dataUrl = canvas.toDataURL('image/png');
        if (typeof dataUrl === 'string' && dataUrl.startsWith('data:')) {
            _modelIconMemoryCache.set(iconPath, dataUrl);
            return getCachedModelIconObjectUrl(iconPath) || dataUrl;
        }
    } catch (e) {}
    return null;
}

function hydrateModelIcons(container) {
    try {
        if (!container) return;
        const imgs = container.querySelectorAll('img[data-model-icon-path]');
        if (!imgs || imgs.length === 0) return;
        imgs.forEach(img => {
            const iconPath = img.getAttribute('data-model-icon-path');
            if (!iconPath) return;
            const cachedUrl = getCachedModelIconObjectUrl(iconPath);
            if (cachedUrl) {
                if (img.src !== cachedUrl) img.src = cachedUrl;
                return;
            }
            if (img.getAttribute('data-model-icon-cache-bound') === '1') return;
            img.setAttribute('data-model-icon-cache-bound', '1');
            const handler = () => {
                const currentPath = img.getAttribute('data-model-icon-path');
                if (currentPath !== iconPath) return;
                const src = _tryCacheModelIconFromImg(img, iconPath);
                if (src && img.getAttribute('data-model-icon-path') === iconPath) {
                    img.src = src;
                }
            };
            img.addEventListener('load', handler, { once: true });
            if (img.complete && img.naturalWidth) handler();
        });
    } catch (e) {}
}

let currentModelsData = [];

function modelCompositeKey(id, nodeId) {
    return id + '::' + (nodeId || 'local');
}

function getParamsCount(name) {
    if (!name) return 0;
    name = name.toUpperCase();
    const moeMatch = name.match(/(\d+)X(\d+(?:\.\d+)?)B/);
    if (moeMatch) {
        return parseFloat(moeMatch[1]) * parseFloat(moeMatch[2]);
    }
    const match = name.match(/(\d+(?:\.\d+)?)B/);
    if (match) {
        return parseFloat(match[1]);
    }
    const matchM = name.match(/(\d+(?:\.\d+)?)M/);
    if (matchM) {
        return parseFloat(matchM[1]) / 1000;
    }
    return 0;
}

function populateNodeFilter() {
    var sel = document.getElementById('modelNodeFilter');
    if (!sel) return;
    var nodes = {};
    if (Array.isArray(currentModelsData)) {
        currentModelsData.forEach(function (m) {
            if (m && m.nodeId && m.nodeId !== 'local') {
                if (!nodes[m.nodeId]) {
                    nodes[m.nodeId] = m.nodeName || m.nodeId;
                }
            }
        });
    }
    var current = sel.value;
    while (sel.options.length > 2) sel.remove(2);
    var keys = Object.keys(nodes);
    keys.sort();
    for (var i = 0; i < keys.length; i++) {
        var opt = document.createElement('option');
        opt.value = keys[i];
        opt.textContent = nodes[keys[i]];
        sel.appendChild(opt);
    }
    if (current === 'all' || current === 'local' || nodes[current]) {
        sel.value = current;
    } else {
        sel.value = 'all';
    }
}

function sortAndRenderModels() {
    const sortType = document.getElementById('modelSortSelect').value;
    if (!currentModelsData) return;

    const nodeFilter = document.getElementById('modelNodeFilter').value;
    let filtered = currentModelsData;
    if (nodeFilter === 'local') {
        filtered = currentModelsData.filter(m => m && (!m.nodeId || m.nodeId === 'local'));
    } else if (nodeFilter && nodeFilter !== 'all') {
        filtered = currentModelsData.filter(m => m && m.nodeId === nodeFilter);
    }

    const comparator = getModelSortComparator(sortType);
    const all = [...filtered];

    const favourites = [];
    const nonFavourites = [];
    all.forEach(m => {
        if (m && m.favourite) favourites.push(m);
        else nonFavourites.push(m);
    });

    favourites.sort(comparator);
    nonFavourites.sort(comparator);

    renderModelsList([...favourites, ...nonFavourites]);
}

function getModelSortComparator(sortType) {
    return (a, b) => {
        const nameA = (a.alias || a.name || '').toLowerCase();
        const nameB = (b.alias || b.name || '').toLowerCase();
        const sizeA = a.size || 0;
        const sizeB = b.size || 0;
        const paramsA = getParamsCount(a.name);
        const paramsB = getParamsCount(b.name);

        switch (sortType) {
            case 'name_asc': return nameA.localeCompare(nameB);
            case 'name_desc': return nameB.localeCompare(nameA);
            case 'size_asc': return sizeA - sizeB;
            case 'size_desc': return sizeB - sizeA;
            case 'params_asc': return paramsA - paramsB;
            case 'params_desc': return paramsB - paramsA;
            default: return 0;
        }
    };
}

function renderModelsList(models) {
    const modelsList = document.getElementById('modelsList');
    if (!models || models.length === 0) {
        const nodeFilter = document.getElementById('modelNodeFilter').value;
        let emptyTitle, emptyText, emptyBtn = '';
        if (nodeFilter && nodeFilter !== 'all' && nodeFilter !== 'local') {
            var nodeName = '';
            var sel = document.getElementById('modelNodeFilter');
            if (sel) {
                var opt = sel.querySelector('option[value="' + nodeFilter + '"]');
                if (opt) nodeName = opt.textContent;
            }
            emptyTitle = nodeName ? t('page.model.empty_node_title', '节点 [{name}] 没有模型').replace('{name}', nodeName) : t('page.model.empty_node_title', '节点没有模型');
            emptyText = t('page.model.empty_node_desc', '该远程节点上没有发现模型');
        } else if (nodeFilter === 'local') {
            emptyTitle = t('page.model.empty_remote_title', '没有远程模型');
            emptyText = t('page.model.empty_remote_desc', '当前没有配置远程节点');
        } else if (nodeFilter === 'local') {
            emptyTitle = t('page.model.empty_title', '没有模型');
            emptyText = t('page.model.empty_local_desc', '本机没有发现模型');
            emptyBtn = `<button class="btn btn-primary" onclick="showModelPathSetting()">${t('page.model.empty_action', '去配置')}</button>`;
        } else {
            emptyTitle = t('page.model.empty_title', '没有模型');
            emptyText = t('page.model.empty_desc', '请先在"模型路径配置"中添加模型目录');
            emptyBtn = `<button class="btn btn-primary" onclick="showModelPathSetting()">${t('page.model.empty_action', '去配置')}</button>`;
        }
        modelsList.innerHTML = `
                    <div class="empty-state">
                        <div class="empty-state-icon"><i class="fas fa-box-open"></i></div>
                        <div class="empty-state-title">${emptyTitle}</div>
                        <div class="empty-state-text">${emptyText}</div>
                        ${emptyBtn}
                    </div>
                `;
        return;
    }

    let html = '';
    models.forEach(model => {
        const architecture = model.architecture || t('common.unknown', '未知');
        const quantization = model.quantization || '';
        const isLoading = !!model.isLoading;

        let status = model.status;
        let statusText = t('page.model.status.stopped', '已停止');
        let statusIcon = 'fa-stop-circle';
        let statusClass = 'status-stopped';

        if (isLoading) {
            statusText = t('page.model.status.loading', '加载中');
            statusIcon = 'fa-spinner fa-spin';
            statusClass = 'status-loading';
        } else if (model.isLoaded) {
            statusText = status === 'running' ? t('page.model.status.running', '运行中') : t('page.model.status.loaded', '已加载');
            statusIcon = status === 'running' ? 'fa-play-circle' : 'fa-check-circle';
            statusClass = status === 'running' ? 'status-running' : 'status-loaded';
        }

        const modelIconPath = getModelIcon(architecture);
        const modelIconSrc = modelIconPath ? (getCachedModelIconObjectUrl(modelIconPath) || modelIconPath) : null;
        const displayName = (model.alias && model.alias.trim()) ? model.alias : model.name;
        const isFavourite = !!model.favourite;
        const nodeId = model.nodeId || '';
        const nodeName = model.nodeName || '';
        const nodeColor = getNodeColor(nodeId);
        const nodeBadge = nodeId && nodeId !== 'local'
            ? '<span class="node-badge" style="color:hsl(' + nodeColor + ',65%,70%);background-color:hsl(' + nodeColor + ',50%,12%);" title="' + (nodeName || nodeId) + '"><i class="fas fa-server"></i> ' + (nodeName || nodeId) + '</span>'
            : '';

        let actionButtons = '';
        if (isLoading) {
            actionButtons = `<button class="btn-icon danger" onclick="stopModel('${model.id}', '${nodeId}')" title="${t('page.model.action.cancel_loading', '取消加载')}"><i class="fas fa-stop"></i></button>`;
        } else if (model.isLoaded) {
            if (status === 'running') {
                const isLocalModel = !nodeId || nodeId === 'local';
                actionButtons = `
                            <button class="btn-icon primary" onclick="loadModel('${model.id}', '${model.name}', '', '${nodeId}')" title="${t('modal.model_action.title.load', '加载模型')}"><i class="fas fa-sliders-h"></i></button>
                            <button class="btn-icon" onclick="viewModelDetails('${model.id}', '${nodeId}')" title="${t('page.model.action.details', '详情')}"><i class="fas fa-info-circle"></i></button>
                            ${isLocalModel ? `<button class="btn-icon disabled" disabled title="${t('page.model.action.benchmark', '性能测试')}"><i class="fas fa-rocket"></i></button>` : ''}
                            <button class="btn-icon" onclick="openModelBenchmarkList(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="${t('page.model.action.view_benchmark_results', '查看测试结果')}"><i class="fas fa-list"></i></button>

                        `;
            } else {
                actionButtons = `
                            <button class="btn-icon primary" onclick="loadModel('${model.id}', '${model.name}', '', '${nodeId}')" title="${t('modal.model_action.title.load', '加载模型')}"><i class="fas fa-sliders-h"></i></button>
                            <button class="btn-icon" onclick="openModelBenchmarkList(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="${t('page.model.action.view_benchmark_results', '查看测试结果')}"><i class="fas fa-list"></i></button>
                        `;
            }
        } else {
            const isRemote = nodeId && nodeId !== 'local';
            actionButtons = `
                        <button class="btn-icon primary" onclick="loadModel('${model.id}', '${model.name}', '', '${nodeId}')" title="${t('page.model.action.load', '加载')}"><i class="fas fa-play"></i></button>
                        <button class="btn-icon" onclick="viewModelDetails('${model.id}', '${nodeId}')" title="${t('page.model.action.details', '详情')}"><i class="fas fa-info-circle"></i></button>
                        ${!isRemote ? `<button class="btn-icon" onclick="openModelBenchmarkDialog(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="${t('page.model.action.benchmark', '性能测试')}"><i class="fas fa-rocket"></i></button>` : ''}
                        <button class="btn-icon" onclick="openModelBenchmarkList(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="${t('page.model.action.view_benchmark_results', '查看测试结果')}"><i class="fas fa-list"></i></button>
                    `;
        }

        const isRemote = nodeId && nodeId !== 'local';
        const borderStyle = isRemote ? ' style="border-left-color:hsl(' + nodeColor + ',65%,50%);"' : '';
        html += `
                    <div class="model-item"${borderStyle}>
                        <button class="model-fav-btn ${isFavourite ? 'active' : ''}" onclick="toggleFavouriteModel(event, decodeURIComponent('${encodeURIComponent(model.id)}'), '${nodeId || 'local'}')" title="${isFavourite ? t('page.model.fav.remove', '取消喜好') : t('page.model.fav.add', '标记喜好')}">
                            <i class="${isFavourite ? 'fas' : 'far'} fa-star"></i>
                        </button>
                        <div class="model-icon-wrapper">
                            ${modelIconPath ? `<img src="${modelIconSrc}" data-model-icon-path="${modelIconPath}" alt="${architecture}">` : `<i class="fas fa-brain"></i>`}
                        </div>
                        <div class="model-details">
                             <div class="model-name" title="${model.name}" onclick="openAliasModal(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(model.name)}'), decodeURIComponent('${encodeURIComponent(model.alias || '')}'), '${nodeId || 'local'}')">
                                ${displayName}
                                ${model.supportsVision ? '<span class="vision-badge"><i class="fas fa-image"></i></span>' : ''}
                                ${model.supportsAudio ? '<span class="audio-badge"><i class="fas fa-headphones"></i></span>' : ''}
                                ${nodeBadge}
                            </div>
                        <div class="model-meta">
                                <span><i class="fas fa-layer-group"></i> ${architecture}</span>
                                ${quantization ? `<span><i class="fas fa-microchip"></i> ${quantization}</span>` : ''}
                                <span><i class="fas fa-hdd"></i> ${formatFileSize(model.size)}</span>
                                ${model.port ? `<span><i class="fas fa-network-wired"></i> ${model.port}</span>` : ''}
                            </div>
							<span class="model-slots" id="slots-${encodeURIComponent(modelCompositeKey(model.id, model.nodeId))}" style="visibility:${Array.isArray(model.slots) && model.slots.length > 0 ? 'visible' : 'hidden'};">${renderSlotsSquaresInner(model.slots)}</span>
                        </div>
                        <div class="model-status-badge ${statusClass}">
                            <i class="fas ${statusIcon}"></i> <span>${statusText}</span>
                        </div>
                        <div class="model-actions">${actionButtons}</div>
                    </div>
                `;
    });
    modelsList.innerHTML = html;
    hydrateModelIcons(modelsList);
    const input = document.getElementById('modelSearchInput');
    if (input) filterModels(input.value);
}

function renderSlotsSquaresInner(slots) {
    try {
        if (!Array.isArray(slots) || slots.length === 0) return '';
        let s = '';
        for (let i = 0; i < slots.length; i++) {
            const it = slots[i];
            if (!it || typeof it !== 'object') continue;
            const id = Number.isFinite(it.id) ? it.id : i;
            const busy = !!it.is_processing;
            const speculative = !!it.speculative;
            const title = `slot ${id}${speculative ? ' (speculative)' : ''}`;
            s += `<span class="model-slot-square${busy ? ' busy' : ''}" title="${title}"></span>`;
        }
        return s;
    } catch (e) {
        return '';
    }
}

function updateModelSlotsDom(modelId, slots, nodeId) {
    try {
        const key = modelCompositeKey(modelId, nodeId);
        const el = document.getElementById(`slots-${encodeURIComponent(key)}`);
        if (!el) return;
        const hasSlots = Array.isArray(slots) && slots.length > 0;
        el.style.visibility = hasSlots ? 'visible' : 'hidden';
        el.innerHTML = renderSlotsSquaresInner(slots);
    } catch (e) {}
}
function toggleFavouriteModel(event, modelId, nodeId) {
    if (event) {
        event.preventDefault();
        event.stopPropagation();
    }
    const idx = (currentModelsData || []).findIndex(m => m && m.id === modelId && (m.nodeId || 'local') === (nodeId || 'local'));
    if (idx < 0) return;

    const prev = !!currentModelsData[idx].favourite;
    currentModelsData[idx].favourite = !prev;
    sortAndRenderModels();

    nodeId = nodeId || currentModelsData[idx].nodeId || '';
    const payload = { modelId };
    if (nodeId && nodeId !== 'local') payload.nodeId = nodeId;
    fetch('/api/models/favourite', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    })
        .then(r => r.json())
        .then(res => {
            if (!res || !res.success) {
                throw new Error((res && res.error) ? res.error : t('page.model.fav.set_failed', '设置喜好失败'));
            }
            const favourite = !!(res.data && res.data.favourite);
            const i = (currentModelsData || []).findIndex(m => m && m.id === modelId && (m.nodeId || 'local') === (nodeId || 'local'));
            if (i >= 0) {
                currentModelsData[i].favourite = favourite;
                sortAndRenderModels();
            }
        })
        .catch(err => {
            const i = (currentModelsData || []).findIndex(m => m && m.id === modelId && (m.nodeId || 'local') === (nodeId || 'local'));
            if (i >= 0) {
                currentModelsData[i].favourite = prev;
                sortAndRenderModels();
            }
            showToast(t('toast.error', '错误'), err && err.message ? err.message : t('common.network_error', '网络错误'), 'error');
        });
}

function refreshModels() {
    showToast(t('toast.info', '提示'), t('page.model.refreshing', '正在刷新模型列表'), 'info');
    fetch('/api/models/refresh')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                loadModels();
            } else {
                throw new Error(data.error || t('page.model.refresh_failed', '刷新模型列表失败'));
            }
        })
        .catch(error => {
            showToast(t('toast.error', '错误'), error.message || t('page.model.network_retry', '网络错误，请稍后重试'), 'error');
            loadModels();
        });
}

