/* ================= models.js — 模型列表页 ================= */
'use strict';

const Models = {
    all: [], filter: 'all', sortKey: 'name', sortAsc: true, search: '', nodeFilter: 'all',
    busyIds: new Set(), loadedCount: 0, nodes: {},

    init() {
        $('#modelSearch').addEventListener('input', e => { this.search = e.target.value; this.render(); });
        $('#nodeFilter').addEventListener('change', e => { this.nodeFilter = e.target.value; this.render(); });
        $$('#modelChips .chip').forEach(c => c.addEventListener('click', () => {
            if (c.dataset.filter) {
                this.filter = c.dataset.filter;
                $$('#modelChips .chip[data-filter]').forEach(x => x.classList.toggle('active', x === c));
            } else if (c.dataset.sort) {
                if (this.sortKey === c.dataset.sort) this.sortAsc = !this.sortAsc;
                else { this.sortKey = c.dataset.sort; this.sortAsc = true; }
                c.textContent = (c.dataset.sort === 'name' ? '名称' : '大小') + (this.sortAsc ? ' ↑' : ' ↓');
            }
            this.render();
        }));
    },

    load(manual) {
        if (manual) toast('正在刷新…');
        if (!this.all.length) $('#modelList').innerHTML = '<div class="skeleton"></div>'.repeat(4);
        Promise.all([
            api('/api/models/list'),
            api('/api/models/loaded'),
            api('/api/models/record/speed').catch(() => null),
            api('/api/node/list').catch(() => null)
        ])
            .then(([list, loaded, speed, nodes]) => {
                if (!list.success) throw new Error(list.error || '加载失败');
                // 远程节点表（nodeId -> {name, status, baseUrl}）
                this.nodes = {};
                if (nodes && nodes.success && Array.isArray(nodes.data)) {
                    nodes.data.forEach(n => { this.nodes[n.nodeId] = n; });
                }
                const loadedMap = {};
                (loaded.success ? loaded.models || [] : []).forEach(m => { loadedMap[m.id + '@' + (m.nodeId || 'local')] = m; });
                const speedMap = {};
                if (speed && speed.success && Array.isArray(speed.data)) speed.data.forEach(s => { speedMap[s.modelId] = s; });
                this.loadedCount = Object.keys(loadedMap).length;
                this.all = (list.models || []).map(m => {
                    const L = loadedMap[m.id + '@' + (m.nodeId || 'local')];
                    const sp = speedMap[m.id] || (m.alias && speedMap[m.alias]);
                    return Object.assign({}, m, {
                        isLoaded: !!L, status: L ? (L.status || 'loaded') : 'stopped',
                        port: L ? L.port : null, busy: L ? !!L.busy : false,
                        inSpeed: sp ? sp.averagePromptPerSecond : 0,
                        outSpeed: sp ? sp.averagePredictedPerSecond : 0
                    });
                });
                this.renderNodeFilter();
                this.render();
            })
            .catch(e => {
                $('#modelList').innerHTML = '<div class="empty"><i class="fas fa-triangle-exclamation"></i>' + esc(e.message) + '</div>';
            });
    },

    renderNodeFilter() {
        // 节点分组标题已取代下拉过滤，保持隐藏
        $('#nodeFilter').style.display = 'none';
    },

    renderCount() {
        $('#headerTitle').innerHTML = '模型 <small>' + this.loadedCount + '/' + this.all.length + '</small>';
    },

    render() {
        const q = this.search.toLowerCase();
        let arr = this.all.filter(m => {
            const name = (m.alias || m.name || '').toLowerCase();
            if (q && !name.includes(q) && !(m.id || '').toLowerCase().includes(q)) return false;
            if (this.filter === 'loaded' && !m.isLoaded) return false;
            if (this.filter === 'fav' && !m.favourite) return false;
            if (this.nodeFilter !== 'all' && (m.nodeId || 'local') !== this.nodeFilter) return false;
            return true;
        });
        arr.sort((a, b) => {
            if (a.isLoaded !== b.isLoaded) return a.isLoaded ? -1 : 1;
            if (this.sortKey === 'size') return this.sortAsc ? a.size - b.size : b.size - a.size;
            return this.sortAsc ? (a.alias || a.name).localeCompare(b.alias || b.name) : (b.alias || b.name).localeCompare(a.alias || a.name);
        });
        this.renderCount();

        if (!arr.length) {
            $('#modelList').innerHTML = '<div class="empty"><i class="fas fa-inbox"></i>没有匹配的模型</div>';
            return;
        }
        // 按节点分组：本地在前，远程节点按 /api/node/list 顺序
        const groups = [];
        const byNode = {};
        arr.forEach(m => {
            const n = m.nodeId || 'local';
            (byNode[n] = byNode[n] || []).push(m);
        });
        const order = ['local', ...Object.keys(this.nodes).filter(n => byNode[n])];
        Object.keys(byNode).filter(n => n !== 'local' && !this.nodes[n]).forEach(n => order.push(n));
        order.forEach(n => { if (byNode[n]) groups.push({ nodeId: n, items: byNode[n] }); });

        const multiNode = groups.length > 1;
        $('#modelList').innerHTML = groups.map(g => {
            const header = multiNode ? this.nodeHeader(g.nodeId, g.items.length) : '';
            return header + g.items.map(m => this.card(m)).join('');
        }).join('');
    },

    nodeHeader(nodeId, count) {
        if (nodeId === 'local') {
            return '<div class="node-section"><i class="fas fa-hard-drive"></i> 本地 <span class="ns-count">' + count + '</span></div>';
        }
        const n = this.nodes[nodeId] || {};
        const online = n.status === 'ONLINE';
        return '<div class="node-section' + (online ? '' : ' offline') + '">' +
            '<span class="ns-dot" style="background:' + (online ? 'var(--success)' : 'var(--danger)') + '"></span>' +
            '<i class="fas fa-server"></i> ' + esc(n.name || nodeId) +
            '<span class="ns-count">' + count + '</span>' +
            (online ? '' : '<span class="ns-off">离线</span>') + '</div>';
    },

    card(m) {
        const key = esc(m.id) + '|' + esc(m.nodeId || 'local');
        const icon = (typeof getModelIcon === 'function') ? getModelIcon(m.architecture || '') : null;
        const badges = (m.supportsVision ? '<span>视觉</span>' : '') + (m.supportsAudio ? '<span>音频</span>' : '') + (m.hasMtp ? '<span>MTP</span>' : '') + (m.isClone ? '<span>克隆</span>' : '');
        const status = this.busyIds.has(m.id)
            ? '<span class="status-chip loading"><i class="fas fa-spinner fa-spin"></i>处理中</span>'
            : m.isLoaded
                ? '<span class="status-chip loaded"><i class="fas fa-circle" style="font-size:7px"></i>已加载' + (m.port ? ' · ' + m.port : '') + '</span>'
                : '<span class="status-chip stopped">已停止</span>';
        const speed = (m.inSpeed || m.outSpeed)
            ? '<span class="mc-speed"><i class="fas fa-bolt"></i>' + (fmtSpeed(m.inSpeed) || '—') + ' / ' + (fmtSpeed(m.outSpeed) || '—') + ' t/s</span>' : '';
        const mainBtn = this.busyIds.has(m.id)
            ? '<button class="btn btn-sq" disabled><i class="fas fa-spinner fa-spin"></i></button>'
            : m.isLoaded
                ? '<button class="btn primary btn-sq" onclick="Models.stop(\'' + key + '\')" title="停止"><i class="fas fa-stop"></i></button>' +
                  '<button class="btn btn-sq" onclick="ModelConfig.open(\'' + key + '\')" title="配置"><i class="fas fa-sliders"></i></button>'
                : '<button class="btn primary-soft btn-sq" onclick="ModelConfig.open(\'' + key + '\')" title="配置 / 启动"><i class="fas fa-sliders"></i></button>' +
                  '<button class="btn btn-sq" onclick="Models.quickStart(\'' + key + '\')" title="快速启动（按已保存配置直接启动）"><i class="fas fa-bolt"></i></button>';
        const cloneBtn = m.isClone
            ? '<button class="btn danger-soft" onclick="Models.deleteClone(\'' + key + '\')" title="删除克隆体"><i class="fas fa-trash"></i></button>'
            : '<button class="btn" onclick="Models.clone(\'' + key + '\')" title="创建克隆体"><i class="fas fa-clone"></i></button>';
        const nodeName = (m.nodeId && m.nodeId !== 'local') ? ((this.nodes[m.nodeId] || {}).name || m.nodeName || m.nodeId) : '';
        const nodeTag = nodeName ? '<span class="mc-node"><i class="fas fa-server"></i> ' + esc(nodeName) + '</span>' : '';
        return '<div class="model-card">' +
            '<div class="mc-head">' +
                '<button class="mc-fav' + (m.favourite ? ' active' : '') + '" onclick="Models.fav(\'' + key + '\')"><i class="' + (m.favourite ? 'fas' : 'far') + ' fa-star"></i></button>' +
                (icon ? '<img class="mc-icon" src="' + icon + '" loading="lazy" alt="">' : '') +
                '<div class="mc-name-wrap"><div class="mc-name">' + esc(m.alias || m.name) + '<span class="mc-badges">' + badges + '</span></div>' + nodeTag + '</div>' +
                status +
            '</div>' +
            '<div class="mc-meta">' +
                '<span><i class="fas fa-layer-group"></i>' + esc(m.architecture || '未知') + '</span>' +
                (m.quantization ? '<span><i class="fas fa-microchip"></i>' + esc(m.quantization) + '</span>' : '') +
                '<span><i class="fas fa-hdd"></i>' + fmtSize(m.size) + '</span>' + speed +
            '</div>' +
            '<div class="mc-foot">' + mainBtn +
                '<span class="spacer"></span>' +
                '<button class="icon-btn" onclick="ModelDetail.open(\'' + key + '\')" title="详情"><i class="fas fa-circle-info"></i></button>' +
                cloneBtn +
            '</div>' +
        '</div>';
    },

    find(key) { const [id, nodeId] = key.split('|'); return this.all.find(m => m.id === id && (m.nodeId || 'local') === nodeId); },
    nodeParam(m) { return (m.nodeId && m.nodeId !== 'local') ? m.nodeId : undefined; },

    /* WS 事件驱动的局部状态更新（不重拉列表） */
    patch(modelId, fields, nodeId) {
        const nid = nodeId || 'local';
        const m = this.all.find(x => x.id === modelId && (x.nodeId || 'local') === nid);
        if (m) {
            Object.assign(m, fields);
            this.loadedCount = this.all.filter(x => x.isLoaded).length;
        }
        this.render();
    },

    fav(key) {
        const m = this.find(key); if (!m) return;
        const body = { modelId: m.id, favourite: !m.favourite };
        if (this.nodeParam(m)) body.nodeId = this.nodeParam(m);
        post('/api/models/favourite', body)
            .then(r => { if (r.success) { m.favourite = !m.favourite; this.render(); } else toast(r.error || '操作失败', 'error'); });
    },

    fetchConfig(m) {
        const url = '/api/models/config/get?modelId=' + encodeURIComponent(m.id) + (this.nodeParam(m) ? '&nodeId=' + encodeURIComponent(this.nodeParam(m)) : '');
        return api(url).then(r => {
            if (!r.success) throw new Error(r.error || '读取配置失败');
            const data = r.data && (r.data.configs ? r.data : r.data[m.id]) || {};
            const configs = data.configs || {};
            const selected = data.selectedConfig || 'default';
            return { configs, selected, cfg: configs[selected] || {} };
        });
    },

    quickStart(key) {
        const m = this.find(key); if (!m) return;
        this.fetchConfig(m).then(({ cfg }) => {
            const payload = {
                modelId: m.id,
                llamaBinPathSelect: cfg.llamaBinPathSelect || cfg.llamaBinPath || '',
                cmd: cfg.cmd || '', extraParams: cfg.extraParams || ''
            };
            if (Array.isArray(cfg.device)) payload.device = cfg.device;
            if (Number.isFinite(cfg.mg)) payload.mg = cfg.mg;
            if (typeof cfg.enableVision === 'boolean') payload.enableVision = cfg.enableVision;
            if (!payload.llamaBinPathSelect && !payload.cmd) {
                toast('没有已保存的启动配置，请先设置', 'error');
                ModelConfig.open(key);
                return;
            }
            if (m.isClone && m.sourceModelId) payload.sourceModelId = m.sourceModelId;
            if (this.nodeParam(m)) payload.nodeId = this.nodeParam(m);
            this.doLoad(m.id, payload);
        }).catch(e => toast(e.message, 'error'));
    },

    doLoad(id, payload) {
        this.busyIds.add(id); this.render();
        post('/api/models/load', payload).then(r => {
            if (r.success) {
                if (r.data && r.data.async) {
                    toast('启动指令已提交，等待加载完成…', 'success');
                    // 保持 busy 状态，由 WS modelLoad/modelLoadStart 事件结算
                } else {
                    this.busyIds.delete(id);
                    toast('模型启动成功', 'success');
                    this.load();
                }
            } else { this.busyIds.delete(id); toast(r.error || '启动失败', 'error'); this.render(); }
        }).catch(e => { this.busyIds.delete(id); toast(e.message, 'error'); this.render(); });
    },

    stop(key) {
        const m = this.find(key); if (!m) return;
        this.busyIds.add(m.id); this.render();
        const body = { modelId: m.id };
        if (this.nodeParam(m)) body.nodeId = this.nodeParam(m);
        post('/api/models/stop', body).then(r => {
            this.busyIds.delete(m.id);
            if (r.success) { m.isLoaded = false; m.status = 'stopped'; m.port = null; toast('已停止', 'success'); }
            else toast(r.error || '停止失败', 'error');
            this.render();
        }).catch(e => { this.busyIds.delete(m.id); toast(e.message, 'error'); this.render(); });
    },

    clone(key) {
        const m = this.find(key); if (!m) return;
        UI.prompt('创建克隆体（输入克隆体 ID）', m.id + '-clone', v => {
            const cloneId = (v || '').trim();
            if (!cloneId) return;
            this.fetchConfig(m).then(({ cfg, selected }) => {
                const body = {
                    cloneId, sourceModelId: m.id,
                    llamaBinPathSelect: cfg.llamaBinPathSelect || cfg.llamaBinPath || '',
                    cmd: cfg.cmd || '', extraParams: cfg.extraParams || '',
                    device: Array.isArray(cfg.device) ? cfg.device : ['All'],
                    mg: Number.isFinite(cfg.mg) ? cfg.mg : -1,
                    enableVision: typeof cfg.enableVision === 'boolean' ? cfg.enableVision : true,
                    configName: selected
                };
                if (this.nodeParam(m)) body.nodeId = this.nodeParam(m);
                return post('/api/models/clone/create', body);
            }).then(r => {
                if (r.success) { toast('克隆体已创建', 'success'); this.load(); }
                else toast(r.error || '创建失败', 'error');
            }).catch(e => toast(e.message, 'error'));
        });
    },

    deleteClone(key) {
        const m = this.find(key); if (!m) return;
        if (!confirm('确定删除克隆体 ' + (m.alias || m.id) + '？')) return;
        const body = { modelId: m.id };
        if (this.nodeParam(m)) body.nodeId = this.nodeParam(m);
        post('/api/models/config/delete', body).then(r => {
            if (r.success) { toast('克隆体已删除', 'success'); this.load(); }
            else toast(r.error || '删除失败', 'error');
        });
    }
};
