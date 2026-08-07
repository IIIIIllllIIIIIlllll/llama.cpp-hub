/* ================= logs.js — 系统日志页 ================= */
/* 移植自旧版 console-modal.js + websocket.js 的控制台日志逻辑：
 * 节点控制台快照(/api/sys/console) + WS 实时推送行,按节点/模型过滤渲染。
 * 与旧版差异:不为每个节点建独立 DOM 面板,状态按节点存 JS、视图只渲染当前节点。 */
'use strict';

const Logs = {
    nodeId: '',          // 当前节点,'' = 本地
    filter: '',          // '' = 全部, 'system' = 仅系统行, 其它 = modelId
    snaps: {},           // nodeId -> 控制台快照文本
    bufs: {},            // nodeId -> [{ text, modelId }] WS 实时行缓冲
    modelSnaps: {},      // 'modelId|||nodeId' -> 模型历史日志快照
    updatedAt: {},       // nodeId -> 快照更新时间(ms)
    inFlight: {},        // nodeId -> true 表示快照请求中(期间不渲染,避免半截内容)
    srcGen: 0,           // renderSources 异步代次守卫(对应旧版 populateLogFilterGen)
    raf: false,
    decoder: new TextDecoder('utf-8'),
    LOCAL_CAP: 10000,    // 本地行缓冲上限(同旧版 MAX_BUFFER)
    REMOTE_CAP: 5000,    // 远程行缓冲上限(同旧版 REMOTE_MAX_BUFFER)
    MS_CAP: 20,          // 模型快照缓存上限,超出修剪到 MS_TRIM(同旧版)
    MS_TRIM: 10,

    init() {
        $('#logNodes').addEventListener('click', e => {
            const c = e.target.closest('.chip');
            if (!c) return;
            this.nodeId = c.dataset.nid || '';
            this.filter = '';
            this.renderNodes();
            this.renderSources();
            this.ensureSnap();
        });
        const onSrc = e => {
            const it = e.target.closest('[data-filter]');
            if (it) this.setFilter(it.dataset.filter || '');
        };
        $('#logSources').addEventListener('click', onSrc);
        $('#logSrcSelect').addEventListener('change', e => this.setFilter(e.target.value));
    },

    /* 进入页面(App.switchPage 调用) */
    load() {
        this.renderNodes();
        this.renderSources();
        this.ensureSnap();
    },

    /* 有快照就直接渲染,没有则拉取 */
    ensureSnap() {
        const nid = this.nodeId;
        if (this.snaps[nid] == null && !this.inFlight[nid]) this.fetchSnap();
        else this.render();
    },

    /* 节点 chips:本地 + 远程节点(离线标灰);顺带清理已下线节点的缓存状态 */
    renderNodes() {
        const nodes = Object.values(Models.nodes || {});
        const bar = $('#logNodes');
        if (!nodes.length) { bar.style.display = 'none'; }
        else {
            bar.style.display = '';
            bar.innerHTML = '<button class="chip' + (this.nodeId === '' ? ' active' : '') + '" data-nid="">本地</button>' +
                nodes.map(n => {
                    const online = n.status === 'ONLINE';
                    return '<button class="chip' + (this.nodeId === n.nodeId ? ' active' : '') + '" data-nid="' + esc(n.nodeId) + '">' +
                        esc(n.name || n.nodeId) + (online ? '' : '（离线）') + '</button>';
                }).join('');
        }
        // 当前节点已失效则回落本地(对应旧版 cleanupStaleRemoteState)
        const valid = { '': true };
        nodes.forEach(n => { valid[n.nodeId] = true; });
        if (!valid[this.nodeId]) { this.nodeId = ''; this.filter = ''; }
        [this.snaps, this.bufs, this.inFlight, this.updatedAt].forEach(map => {
            Object.keys(map).forEach(k => { if (!valid[k]) delete map[k]; });
        });
        Object.keys(this.modelSnaps).forEach(k => {
            if (!valid[k.split('|||')[1] || '']) delete this.modelSnaps[k];
        });
    },

    /* 日志源列表:全部 / 系统 / 当前节点各模型(已加载在前) + 有历史日志的离线模型
     * 桌面竖排列表(#logSources)与移动下拉框(#logSrcSelect)同步重建 */
    async renderSources() {
        const gen = ++this.srcGen;
        const nid = this.nodeId;
        const nidKey = nid || 'local';
        const items = [];
        Models.all.filter(m => m && m.id && (m.nodeId || 'local') === nidKey)
            .sort((a, b) => (a.isLoaded === b.isLoaded ? 0 : a.isLoaded ? -1 : 1))
            .forEach(m => items.push({ filter: m.id, name: m.alias || m.id, loaded: !!m.isLoaded }));
        // 有日志文件但不在模型列表里的(离线)模型
        try {
            const r = await api('/api/sys/log-models' + (nid ? '?nodeId=' + encodeURIComponent(nid) : ''));
            if (gen !== this.srcGen) return;
            if (r && r.success && Array.isArray(r.data)) {
                const seen = new Set(items.map(i => i.filter));
                r.data.forEach(id => {
                    if (!seen.has(id)) items.push({ filter: id, name: id, loaded: false });
                });
            }
        } catch (e) { if (gen !== this.srcGen) return; }

        const fixed = [
            { filter: '', name: '全部日志', loaded: false, star: true },
            { filter: 'system', name: '系统', loaded: false, star: true }
        ];
        const all = fixed.concat(items);
        $('#logSources').innerHTML = all.map(i =>
            '<div class="log-src-item' + (i.filter === this.filter ? ' active' : '') + (i.loaded ? '' : ' offline') + '" data-filter="' + esc(i.filter) + '">' +
                (i.loaded ? '<span class="ls-dot"></span>' : '') +
                '<span class="ls-name">' + esc(i.name) + (i.star || i.loaded ? '' : '（未加载）') + '</span>' +
            '</div>').join('');
        const sel = $('#logSrcSelect');
        sel.innerHTML = all.map(i =>
            '<option value="' + esc(i.filter) + '">' + esc(i.name) + (i.star || i.loaded ? '' : '（未加载）') + '</option>').join('');
        sel.value = this.filter;
    },

    /* 拉取当前节点控制台快照(纯文本) */
    fetchSnap() {
        const nid = this.nodeId;
        this.inFlight[nid] = true;
        this.status('加载中…');
        fetch('/api/sys/console' + (nid ? '?nodeId=' + encodeURIComponent(nid) : ''))
            .then(r => r.text())
            .then(text => {
                this.snaps[nid] = text || '';
                this.updatedAt[nid] = Date.now();
                delete this.inFlight[nid];
                this.render();
            })
            .catch(() => {
                delete this.inFlight[nid];
                this.status('加载失败');
            });
    },

    /* 顶栏刷新按钮 */
    refresh() { this.fetchSnap(); },

    /* 切换日志源过滤;首次选某模型时拉取其历史日志快照(对应旧版 setLogFilter) */
    setFilter(f) {
        this.filter = f;
        $$('#logSources .log-src-item').forEach(it => it.classList.toggle('active', it.dataset.filter === f));
        $('#logSrcSelect').value = f;
        if (f && f !== 'system') {
            const key = f + '|||' + this.nodeId;
            if (this.modelSnaps[key] == null) {
                const url = '/api/sys/model-log?modelId=' + encodeURIComponent(f) +
                    (this.nodeId ? '&nodeId=' + encodeURIComponent(this.nodeId) : '');
                fetch(url).then(r => r.text()).then(text => {
                    this.modelSnaps[key] = text || '';
                    this.trimModelSnaps();
                    this.render();
                }).catch(() => {
                    this.modelSnaps[key] = '';
                    this.render();
                });
            }
        }
        this.render();
    },

    trimModelSnaps() {
        const keys = Object.keys(this.modelSnaps);
        if (keys.length <= this.MS_CAP) return;
        for (let i = 0; i < keys.length - this.MS_TRIM; i++) delete this.modelSnaps[keys[i]];
    },

    /* WS console 消息入口(app.js 调用):{ line64|line, timestamp, modelId, nodeId } */
    onConsoleMsg(d) {
        let text = '';
        if (typeof d.line64 === 'string') {
            try {
                const bin = atob(d.line64);
                const bytes = new Uint8Array(bin.length);
                for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                text = this.decoder.decode(bytes);
            } catch (e) { return; }
        } else if (typeof d.line === 'string') {
            text = d.line;
        }
        if (!text) return;
        const nid = d.nodeId || '';
        const clean = text.replace(/\r/g, '');
        const withNl = clean.endsWith('\n') ? clean : clean + '\n';
        const buf = this.bufs[nid] = this.bufs[nid] || [];
        buf.push({ text: withNl, modelId: d.modelId || 'system' });
        const cap = nid ? this.REMOTE_CAP : this.LOCAL_CAP;
        if (buf.length > cap) buf.splice(0, buf.length - cap);
        // 快照请求中不渲染,快照到达后会一次性渲染全量
        if (this.inFlight[nid]) return;
        if (App.currentPage !== 'logs' || nid !== this.nodeId) return;
        if (!this.raf) {
            this.raf = true;
            requestAnimationFrame(() => { this.raf = false; this.render(); });
        }
    },

    /* 模型加载/停止事件后重建日志源列表(app.js 调用) */
    onModelsChanged() {
        if (App.currentPage === 'logs') this.renderSources();
    },

    matchFilter(modelId) {
        if (!this.filter) return true;
        return (modelId || 'system') === this.filter;
    },

    render() {
        if (App.currentPage !== 'logs') return;
        const view = $('#logView');
        const nid = this.nodeId;
        const atBottom = Math.abs(view.scrollHeight - view.scrollTop - view.clientHeight) < 50;
        let chunk;
        if (!this.filter || this.filter === 'system') {
            chunk = this.snaps[nid] || '';
        } else {
            chunk = this.modelSnaps[this.filter + '|||' + nid] || '';
        }
        let matched = 0;
        const buf = this.bufs[nid] || [];
        for (let i = 0; i < buf.length; i++) {
            if (this.matchFilter(buf[i].modelId)) { chunk += buf[i].text; matched++; }
        }
        $('#logPre').textContent = chunk;
        if (atBottom) view.scrollTop = view.scrollHeight;
        const label = !this.filter ? '全部' : (this.filter === 'system' ? '系统' : this.filter);
        const t = this.updatedAt[nid] ? new Date(this.updatedAt[nid]).toLocaleTimeString() : '—';
        this.status(label + ' · 行 ' + matched + '/' + buf.length + ' · 快照 ' + fmtSize((this.snaps[nid] || '').length) + ' · 更新于 ' + t);
    },

    status(s) { $('#logStatus').textContent = s; }
};
