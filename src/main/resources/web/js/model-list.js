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

function updateModelCountBadge(loadedCount, totalCount) {
    const badge = document.getElementById('modelCountBadge');
    if (badge) {
        badge.textContent = loadedCount + '/' + totalCount;
    }
}

function loadModels() {
    const modelsList = document.getElementById('modelsList');
    fetch('/api/models/list')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const allModels = data.models || [];
                const totalCount = allModels.length;
                updateModelCountBadge(0, totalCount);
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
                            const slotNum = loadedModel ? (Number.isFinite(loadedModel.slotNum) ? loadedModel.slotNum : 0) : 0;
                            const busy = loadedModel ? !!loadedModel.busy : false;
                            // 初次渲染：根据 slotNum 合成全空闲（或 1 个 busy）的小方块数组，
                            // 让 slot 区域在首次渲染即出现，无需等待首个 model_busy 事件。
                            const initialSlots = [];
                            for (let i = 0; i < slotNum; i++) {
                                initialSlots.push({ id: i, is_processing: false });
                            }
                            return {
                                ...model,
                                isLoading: !!model.isLoading,
                                isLoaded: !!loadedModel,
                                status: loadedModel ? (loadedModel.status || 'loaded') : 'stopped',
                                port: loadedModel ? loadedModel.port : null,
                                busy: busy,
                                slotNum: slotNum,
                                slots: initialSlots
                            };
                        });
                        fetch('/api/models/record/speed')
                            .then(r => r.json())
                            .then(speedData => {
                                const speedMap = {};
                                if (speedData.success && Array.isArray(speedData.data)) {
                                    speedData.data.forEach(s => { speedMap[s.modelId] = s; });
                                }
                                currentModelsData = modelsWithStatus.map(m => {
                                    var speedEntry = speedMap[m.id];
                                    if (!speedEntry && m.alias) speedEntry = speedMap[m.alias];
                                    return {
                                        ...m,
                                        averagePredictedPerSecond: speedEntry ? speedEntry.averagePredictedPerSecond : 0,
                                        averagePromptPerSecond: speedEntry ? speedEntry.averagePromptPerSecond : 0
                                    };
                                });
                                sortAndRenderModels();
                                populateNodeFilter();
                                if (typeof populateLogFilter === 'function') populateLogFilter();
                                if (loadedData.success) {
                                    const loadedCount = (loadedData.models || []).length;
                                    updateModelCountBadge(loadedCount, totalCount);
                                }
                            })
                            .catch(() => {
                                currentModelsData = modelsWithStatus;
                                sortAndRenderModels();
                                populateNodeFilter();
                                if (typeof populateLogFilter === 'function') populateLogFilter();
                                if (loadedData.success) {
                                    const loadedCount = (loadedData.models || []).length;
                                    updateModelCountBadge(loadedCount, totalCount);
                                }
                            });
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

// Model icons are served as static PNGs and cached by the browser's HTTP cache.
// The previous canvas/DataURL/ObjectURL cache has been removed to avoid double decoding,
// base64 overhead and ObjectURL memory management.

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
    const sortTypeEl = document.getElementById('modelSortSelect');
    if (!sortTypeEl || !currentModelsData) return;
    const sortType = sortTypeEl.value;

    const nodeFilterEl = document.getElementById('modelNodeFilter');
    const nodeFilter = nodeFilterEl ? nodeFilterEl.value : 'all';
    let filtered = currentModelsData;
    if (nodeFilter === 'local') {
        filtered = currentModelsData.filter(m => m && (!m.nodeId || m.nodeId === 'local'));
    } else if (nodeFilter && nodeFilter !== 'all') {
        filtered = currentModelsData.filter(m => m && m.nodeId === nodeFilter);
    }

    if (getLoadedOnly()) {
        filtered = filtered.filter(m => m && m.isLoaded);
    }

    const comparator = getModelSortComparator(sortType);
    const all = [...filtered];

    if (getRunningFirst()) {
        const loaded = [];
        const nonLoadedFav = [];
        const nonLoadedNonFav = [];
        all.forEach(m => {
            if (!m) return;
            if (m.isLoaded) {
                loaded.push(m);
            } else if (m.favourite) {
                nonLoadedFav.push(m);
            } else {
                nonLoadedNonFav.push(m);
            }
        });
        loaded.sort(comparator);
        nonLoadedFav.sort(comparator);
        nonLoadedNonFav.sort(comparator);
        renderModelsList([...loaded, ...nonLoadedFav, ...nonLoadedNonFav]);
    } else {
        const fav = [];
        const nonFav = [];
        all.forEach(m => {
            if (!m) return;
            if (m.favourite) fav.push(m);
            else nonFav.push(m);
        });
        fav.sort(comparator);
        nonFav.sort(comparator);
        renderModelsList([...fav, ...nonFav]);
    }
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
    const nodeFilterEl = document.getElementById('modelNodeFilter');
    const nodeFilter = nodeFilterEl ? nodeFilterEl.value : 'all';
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
    // 移动端（窄屏）强制列表视图，忽略 localStorage 中的网格视图偏好
    const isGridView = getModelView() === 'grid' && !isMobileListView();
    modelsList.classList.toggle('grid-view', isGridView);
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
        const modelIconSrc = modelIconPath || null;
        const displayName = (model.alias && model.alias.trim()) ? model.alias : model.name;
        const isFavourite = !!model.favourite;
        const nodeId = model.nodeId || '';
        const nodeName = model.nodeName || '';
        const nodeColor = getNodeColor(nodeId);
        const nodeBadge = nodeId && nodeId !== 'local'
            ? '<span class="node-badge" style="color:hsl(' + nodeColor + ',65%,70%);background-color:hsl(' + nodeColor + ',50%,12%);" title="' + (nodeName || nodeId) + '"><i class="fas fa-server"></i> ' + (nodeName || nodeId) + '</span>'
            : '';

        const isRemote = nodeId && nodeId !== 'local';
        let cloneOpButton = '';
        if (model.isClone) {
            cloneOpButton = `<button class="btn-icon danger" onclick="deleteCloneModel(event, decodeURIComponent('${encodeURIComponent(model.id)}'), '${nodeId || 'local'}')" title="${t('page.model.action.delete_clone', '删除克隆体')}"><i class="fas fa-trash-alt"></i></button>`;
        } else {
            cloneOpButton = `<button class="btn-icon" onclick="createCloneModel(event, decodeURIComponent('${encodeURIComponent(model.id)}'), '${nodeId || 'local'}')" title="${t('page.model.action.create_clone', '创建克隆体')}"><i class="fas fa-clone"></i></button>`;
        }

        let actionButtons = '';
        if (isLoading) {
            actionButtons = `<button class="btn-icon danger" onclick="stopModel('${model.id}', '${nodeId}')" title="${t('page.model.action.cancel_loading', '取消加载')}"><i class="fas fa-stop"></i></button>${cloneOpButton}`;
        } else if (model.isLoaded) {
            if (status === 'running') {
                actionButtons = `
                            <button class="btn-icon primary" onclick="loadModel(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(model.name)}'), '', '${nodeId}', decodeURIComponent('${encodeURIComponent(model.sourceModelId || '')}'))" title="${t('modal.model_action.title.load', '加载模型')}"><i class="fas fa-sliders-h"></i></button>
                            <button class="btn-icon" onclick="viewModelDetails('${model.id}', '${nodeId}')" title="${t('page.model.action.details', '详情')}"><i class="fas fa-info-circle"></i></button>
                            ${cloneOpButton}
                        `;
            } else {
                actionButtons = `
                            <button class="btn-icon primary" onclick="loadModel(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(model.name)}'), '', '${nodeId}', decodeURIComponent('${encodeURIComponent(model.sourceModelId || '')}'))" title="${t('modal.model_action.title.load', '加载模型')}"><i class="fas fa-sliders-h"></i></button>
                            ${cloneOpButton}
                        `;
            }
        } else {
            actionButtons = `
                        <button class="btn-icon primary" onclick="loadModel(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(model.name)}'), '', '${nodeId}', decodeURIComponent('${encodeURIComponent(model.sourceModelId || '')}'))" title="${t('page.model.action.load', '加载')}"><i class="fas fa-play"></i></button>
                        <button class="btn-icon" onclick="viewModelDetails('${model.id}', '${nodeId}')" title="${t('page.model.action.details', '详情')}"><i class="fas fa-info-circle"></i></button>
                        ${cloneOpButton}
                    `;
        }
        const borderStyle = isRemote ? ' style="border-left-color:hsl(' + nodeColor + ',65%,50%);"' : '';
        const iconWrapper = `<div class="model-icon-wrapper">
                            ${modelIconPath ? `<img src="${modelIconSrc}" alt="${architecture}" loading="lazy" decoding="async">` : `<i class="fas fa-brain"></i>`}
                        </div>`;
        const badges = `${model.supportsVision ? '<span class="vision-badge"><i class="fas fa-image"></i></span>' : ''}${model.supportsAudio ? '<span class="audio-badge"><i class="fas fa-headphones"></i></span>' : ''}${model.hasMtp ? '<span class="mtp-badge">MTP</span>' : ''}`;
        const hasBadges = badges.length > 0;
        const nameClickAttr = `onclick="openAliasModal(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(model.name)}'), decodeURIComponent('${encodeURIComponent(model.alias || '')}'), '${nodeId || 'local'}')"`;
const speedHtml = (model.averagePromptPerSecond ? `<span class="model-meta-prompt-speed" title="平均预填充速度">In ${model.averagePromptPerSecond.toFixed(0)} t/s</span>` : '') + (model.averagePredictedPerSecond ? `<span class="model-meta-predicted-speed" title="平均解码速度">Out ${model.averagePredictedPerSecond.toFixed(1)} t/s</span>` : '');
        const hasSpeed = speedHtml.length > 0;
        const metaBlock = `<div class="model-meta">
                                  <span><i class="fas fa-layer-group"></i> ${architecture}</span>
                                  ${quantization ? `<span><i class="fas fa-microchip"></i> ${quantization}</span>` : ''}
                                  <span><i class="fas fa-hdd"></i> ${formatFileSize(model.size)}</span>
                                  ${model.port ? `<span><i class="fas fa-network-wired"></i> ${model.port}</span>` : ''}
                              </div>`;
        const slotsAndNode = `<span class="model-slots" id="slots-${encodeURIComponent(modelCompositeKey(model.id, model.nodeId))}">${renderSlotsSquaresInner(model.slots)}</span>${nodeBadge ? '<div class="model-node-badge-line">' + nodeBadge + '</div>' : ''}`;
        const detailsBlock = isGridView
            ? `<div class="model-details">
                              <div class="model-name" title="${model.name}" ${nameClickAttr}>${displayName}</div>
                             ${hasBadges ? `<div class="model-badges">${badges}</div>` : ''}
                             ${metaBlock}
                             ${hasSpeed ? `<div class="model-speed-row">${speedHtml}</div>` : ''}
 							${slotsAndNode}
                         </div>`
            : `<div class="model-details">
                              <div class="model-name" title="${model.name}" ${nameClickAttr}>
                                 ${displayName}
                                  ${badges}
                             </div>
                             ${metaBlock}
                             ${hasSpeed ? `<div class="model-speed-row">${speedHtml}</div>` : ''}
 							${slotsAndNode}
                         </div>`;
       let quickBtnHtml = '';
        if (isLoading) {
            quickBtnHtml = `<button class="model-quick-btn quick-stop" onclick="quickStopModel(event, decodeURIComponent('${encodeURIComponent(model.id)}'), '${nodeId || 'local'}')" title="${t('page.model.action.quick_stop', '快速停止')}"><i class="fas fa-stop"></i></button>`;
        } else if (model.isLoaded) {
            quickBtnHtml = `<button class="model-quick-btn quick-stop" onclick="quickStopModel(event, decodeURIComponent('${encodeURIComponent(model.id)}'), '${nodeId || 'local'}')" title="${t('page.model.action.quick_stop', '快速停止')}"><i class="fas fa-stop"></i></button>`;
        } else {
            quickBtnHtml = `<button class="model-quick-btn quick-start" onclick="quickStartModel(event, decodeURIComponent('${encodeURIComponent(model.id)}'), '${nodeId || 'local'}', decodeURIComponent('${encodeURIComponent(model.sourceModelId || '')}'))" title="${t('page.model.action.quick_start', '快速启动')}"><i class="fas fa-play"></i></button>`;
        }
        const statusBadge = `<div class="model-status-badge ${statusClass}">
                             <i class="fas ${statusIcon}"></i> <span>${statusText}</span>${quickBtnHtml}
                             ${model.busy && model.isLoaded ? '<span class="model-busy-indicator"><i class="fas fa-sync-alt fa-spin"></i> ' + t('page.model.status.busy', '工作中') + '</span>' : ''}
                         </div>`;
        const actionsBlock = `<div class="model-actions">${actionButtons}</div>`;
        const bottomContent = isGridView
            ? `<div class="model-card-bottom">${statusBadge}${actionsBlock}</div>`
            : `${statusBadge}${actionsBlock}`;
        const midContent = isGridView
            ? `<div class="model-card-top">${iconWrapper}${detailsBlock}</div>`
            : `${iconWrapper}${detailsBlock}`;
        html += `
                    <div class="model-item ${model.isClone ? 'model-clone' : ''}"${borderStyle}>
                        <button class="model-fav-btn ${isFavourite ? 'active' : ''}" onclick="toggleFavouriteModel(event, decodeURIComponent('${encodeURIComponent(model.id)}'), '${nodeId || 'local'}')" title="${isFavourite ? t('page.model.fav.remove', '取消喜好') : t('page.model.fav.add', '标记喜好')}">
                            <i class="${isFavourite ? 'fas' : 'far'} fa-star"></i>
                        </button>
                        ${midContent}
                        ${bottomContent}
                    </div>
                `;
    });
    modelsList.innerHTML = html;
    // Icons are rendered directly with <img>; the browser handles HTTP caching.
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
            const overload = !!it.overload;
            const speculative = !!it.speculative;
            const title = `slot ${id}${speculative ? ' (speculative)' : ''}`;
            const cls = overload ? ' overload' : (busy ? ' busy' : '');
            s += `<span class="model-slot-square${cls}" title="${title}"></span>`;
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
        el.style.display = hasSlots ? '' : 'none';
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

function getModelView() {
    try {
        return localStorage.getItem('modelView') || 'list';
    } catch (e) {
        return 'list';
    }
}

// 与 css/index.css 的移动端媒体查询断点保持一致
function isMobileListView() {
    return window.matchMedia && window.matchMedia('(max-width: 900px)').matches;
}

function setModelView(view) {
    try {
        localStorage.setItem('modelView', view);
    } catch (e) {}
}

function toggleModelView() {
    const current = getModelView();
    const next = current === 'grid' ? 'list' : 'grid';
    setModelView(next);
    applyModelView();
    sortAndRenderModels();
}

function applyModelView() {
    const view = getModelView();
    const modelsList = document.getElementById('modelsList');
    if (modelsList) {
        modelsList.classList.toggle('grid-view', view === 'grid' && !isMobileListView());
    }
    const btn = document.getElementById('modelViewToggle');
    if (btn) {
        const icon = btn.querySelector('i');
        if (icon) {
            icon.className = view === 'grid' ? 'fas fa-list' : 'fas fa-th-large';
        }
    }
}

function getRunningFirst() {
    try {
        const val = localStorage.getItem('runningFirst');
        if (val === null) return true;
        return val === 'true';
    } catch (e) {
        return true;
    }
}

function setRunningFirst(val) {
    try {
        localStorage.setItem('runningFirst', String(val));
    } catch (e) {}
}

function toggleRunningFirst() {
    const next = !getRunningFirst();
    setRunningFirst(next);
    applyRunningFirstUI();
    sortAndRenderModels();
}

function applyRunningFirstUI() {
    const btn = document.getElementById('runningFirstToggle');
    if (btn) {
        btn.classList.toggle('active', getRunningFirst());
    }
}

function getLoadedOnly() {
    try {
        return localStorage.getItem('loadedOnly') === 'true';
    } catch (e) {
        return false;
    }
}

function setLoadedOnly(val) {
    try {
        localStorage.setItem('loadedOnly', String(val));
    } catch (e) {}
}

function toggleLoadedOnly() {
    const next = !getLoadedOnly();
    setLoadedOnly(next);
    applyLoadedOnlyUI();
    sortAndRenderModels();
}

function applyLoadedOnlyUI() {
    const btn = document.getElementById('loadedOnlyToggle');
    if (btn) {
        btn.classList.toggle('active', getLoadedOnly());
    }
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


function quickStopModel(event, modelId, nodeId) {
    if (event) { event.preventDefault(); event.stopPropagation(); }
    stopModel(modelId, nodeId);
}

function createCloneModel(event, modelId, nodeId) {
    if (event) { event.preventDefault(); event.stopPropagation(); }
    const cloneId = window.prompt(t('modal.model_action.clone.prompt_id', '请输入克隆体 ID'), modelId + '-clone');
    if (!cloneId || !cloneId.trim()) return;
    const trimmedCloneId = cloneId.trim();

    var configNodeId = nodeId && nodeId !== 'local' ? nodeId : '';
    var configUrl = configNodeId
        ? '/api/models/config/get?modelId=' + encodeURIComponent(modelId) + '&nodeId=' + encodeURIComponent(configNodeId)
        : '/api/models/config/get?modelId=' + encodeURIComponent(modelId);
    fetch(configUrl)
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (!data || !data.success) {
                throw new Error(data && data.error ? data.error : 'load config failed');
            }
            var configs = data.data && data.data.configs ? data.data.configs : {};
            var selected = data.data && data.data.selectedConfig ? data.data.selectedConfig : 'default';
            var config = configs[selected] || {};
            var llamaBinPath = config.llamaBinPathSelect || config.llamaBinPath || '';
            var cmd = config.cmd || '';
            var extraParams = config.extraParams || '';
            if (!llamaBinPath && !cmd && !extraParams) {
                showToast(t('toast.warning', '提示'), t('page.model.clone.no_config', '源模型没有可用启动配置'), 'warning');
                return;
            }
            var payload = {
                cloneId: trimmedCloneId,
                sourceModelId: modelId,
                llamaBinPathSelect: llamaBinPath,
                cmd: cmd,
                extraParams: extraParams,
                device: config.device || [],
                mg: config.mg != null ? config.mg : null,
                enableVision: config.enableVision != null ? config.enableVision : true,
                configName: selected
            };
            if (nodeId && nodeId !== 'local') payload.nodeId = nodeId;
            return fetch('/api/models/clone/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
        })
        .then(function(r) { return r.json(); })
        .then(function(res) {
            if (res && res.success) {
                showToast(t('toast.success', '成功'), t('page.model.clone.success', '克隆体创建成功'), 'success');
                loadModels();
            } else {
                throw new Error(res && res.error ? res.error : t('page.model.clone.failed', '克隆体创建失败'));
            }
        })
        .catch(function(err) {
            showToast(t('toast.error', '错误'), err.message || t('page.model.clone.failed', '克隆体创建失败'), 'error');
        });
}

function deleteCloneModel(event, modelId, nodeId) {
    if (event) { event.preventDefault(); event.stopPropagation(); }
    if (!window.confirm(t('page.model.clone.delete_confirm', '确定删除克隆体配置吗？'))) return;
    var payload = { modelId: modelId };
    if (nodeId && nodeId !== 'local') payload.nodeId = nodeId;
    fetch('/api/models/config/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    })
        .then(function(r) { return r.json(); })
        .then(function(res) {
            if (res && res.success) {
                showToast(t('toast.success', '成功'), t('page.model.clone.delete_success', '克隆体已删除'), 'success');
                loadModels();
            } else {
                throw new Error(res && res.error ? res.error : t('page.model.clone.delete_failed', '删除克隆体失败'));
            }
        })
        .catch(function(err) {
            showToast(t('toast.error', '错误'), err.message || t('page.model.clone.delete_failed', '删除克隆体失败'), 'error');
        });
}

function quickStartModel(event, modelId, nodeId, sourceModelId) {
    if (event) { event.preventDefault(); event.stopPropagation(); }
    var configNodeId = nodeId && nodeId !== 'local' ? nodeId : '';
    var configUrl = configNodeId
        ? '/api/models/config/get?modelId=' + encodeURIComponent(modelId) + '&nodeId=' + encodeURIComponent(configNodeId)
        : '/api/models/config/get?modelId=' + encodeURIComponent(modelId);
    fetch(configUrl)
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (!data || !data.success) {
                throw new Error(data && data.error ? data.error : 'load config failed');
            }
            var configs = data.data && data.data.configs ? data.data.configs : {};
            var selected = data.data && data.data.selectedConfig ? data.data.selectedConfig : 'default';
            var config = configs[selected] || {};
            var llamaBinPath = config.llamaBinPathSelect || config.llamaBinPath || '';
            var cmd = config.cmd || '';
            var extraParams = config.extraParams || '';
            if (!llamaBinPath && !cmd) {
                showToast(t('toast.warning', '提示'), t('page.model.quick_start.no_config', '未找到启动配置，请先通过加载对话框配置启动参数'), 'warning');
                return;
            }
            var payload = {
                modelId: modelId,
                llamaBinPathSelect: llamaBinPath,
                cmd: cmd,
                extraParams: extraParams
            };
            // 优先使用调用时传入的 sourceModelId，兜底查 currentModelsData
            if (sourceModelId) {
                payload.sourceModelId = sourceModelId;
            } else {
                var currentModel = (currentModelsData || []).find(m => m && m.id === modelId);
                if (currentModel && currentModel.isClone && currentModel.sourceModelId) {
                    payload.sourceModelId = currentModel.sourceModelId;
                }
            }
            if (nodeId && nodeId !== 'local') payload.nodeId = nodeId;
            fetch('/api/models/load', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res.success) {
                    if (res.data && res.data.async) {
                        window.pendingModelLoad = { modelId: modelId };
                    } else {
                        showToast(t('toast.success', '成功'), t('page.model.quick_start.success', '模型启动成功'), 'success');
                    }
                } else {
                    showToast(t('toast.error', '错误'), res.error || t('page.model.quick_start.failed', '启动失败'), 'error');
                }
            })
            .catch(function(err) {
                showToast(t('toast.error', '错误'), err.message || t('page.model.quick_start.failed', '启动失败'), 'error');
            });
        })
        .catch(function(err) {
            showToast(t('toast.error', '错误'), err.message || t('page.model.quick_start.failed', '启动失败'), 'error');
        });
}
