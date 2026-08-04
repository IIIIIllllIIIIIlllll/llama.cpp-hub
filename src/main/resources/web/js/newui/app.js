/* ================= app.js — 壳：工具/i18n/主题/导航/弹层/WS ================= */
'use strict';

const $ = s => document.querySelector(s);
const $$ = s => Array.from(document.querySelectorAll(s));
const api = (url, opts) => fetch(url, opts).then(r => r.json());
const post = (url, body) => api(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
const put = (url, body) => api(url, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmtSize = b => { b = Number(b) || 0; if (!b) return '—'; const u = ['B', 'KB', 'MB', 'GB', 'TB']; let i = 0; while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; } return b.toFixed(b >= 100 || i < 2 ? 0 : 1) + ' ' + u[i]; };
const fmtSpeed = v => v ? v.toFixed(v >= 100 ? 0 : 1) : null;

function toast(msg, level) {
    const el = document.createElement('div');
    el.className = 'toast ' + (level || '');
    el.textContent = msg;
    $('#toasts').appendChild(el);
    setTimeout(() => { el.style.opacity = '0'; el.style.transition = 'opacity .3s'; setTimeout(() => el.remove(), 320); }, 3400);
}

/* ================= i18n-lite（直接复用 /i18n/*.json 扁平 key） ================= */
const I18n = {
    lang: localStorage.getItem('newui-lang') || 'zh-CN',
    bundle: {},
    async load() {
        try {
            this.bundle = await fetch('/i18n/' + this.lang + '.json').then(r => r.json());
        } catch (e) { this.bundle = {}; }
    },
    t(key, fallback) {
        const v = this.bundle[key];
        if (typeof v === 'string' && v) return v;
        return fallback !== undefined ? fallback : key;
    },
    toggle() {
        this.lang = this.lang === 'zh-CN' ? 'en-US' : 'zh-CN';
        localStorage.setItem('newui-lang', this.lang);
        location.reload();
    }
};

/* ================= 主题 ================= */
const Theme = {
    mode: localStorage.getItem('newui-theme') || 'auto',
    apply() {
        const dark = this.mode === 'dark' || (this.mode === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
        document.documentElement.dataset.theme = dark ? 'dark' : '';
        const btn = $('#themeToggle');
        if (btn) btn.textContent = { auto: '跟随系统', dark: '夜间', light: '日间' }[this.mode];
        const hdr = $('#themeHdrBtn');
        if (hdr) hdr.innerHTML = '<i class="fas ' + (dark ? 'fa-sun' : 'fa-moon') + '"></i>';
    },
    cycle() { this.mode = { auto: 'dark', dark: 'light', light: 'auto' }[this.mode]; localStorage.setItem('newui-theme', this.mode); this.apply(); }
};

/* ================= 弹层 ================= */
const UI = {
    openSheet(id) { $('#sheetMask').classList.add('open'); $(id).classList.add('open'); },
    closeSheet() { $('#sheetMask').classList.remove('open'); $$('.sheet').forEach(s => s.classList.remove('open')); },
    /* 小型输入对话框 */
    prompt(title, initial, onOk) {
        $('#promptTitle').textContent = title;
        const input = $('#promptInput');
        input.value = initial || '';
        this.openSheet('#promptSheet');
        setTimeout(() => input.focus(), 250);
        // 只关闭 prompt 自身；若底层还有其他弹窗（如详情/配置），遮罩保留
        const closeSelf = () => {
            $('#promptSheet').classList.remove('open');
            if (!document.querySelector('.sheet.open')) $('#sheetMask').classList.remove('open');
        };
        const ok = $('#promptOk');
        const cancel = $$('#promptSheet .sheet-foot .btn:not(.primary), #promptSheet .sheet-head .icon-btn');
        const okHandler = () => { cleanup(); const v = input.value; closeSelf(); onOk(v); };
        const cancelHandler = () => { cleanup(); closeSelf(); };
        const cleanup = () => {
            ok.removeEventListener('click', okHandler);
            cancel.forEach(b => b.removeEventListener('click', cancelHandler));
        };
        ok.addEventListener('click', okHandler);
        cancel.forEach(b => b.addEventListener('click', cancelHandler));
    }
};

/* ================= 应用壳 ================= */
const App = {
    currentPage: 'models',
    PAGE_TITLES: { models: '模型', hf: '模型搜索', downloads: '下载', bench: '性能测试', usage: '用量报表', sysinfo: '系统信息', logs: '系统日志', settings: '设置' },

    async start() {
        await I18n.load();
        Theme.apply();
        matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => Theme.apply());
        this.bindShell();
        Models.init();
        ModelConfig.init();
        ModelDetail.init();
        HfSearch.init();
        Benchmark.init();
        UsageReport.init();
        Logs.init();
        MiscPages.init();
        Settings.init();
        WS.connect();
        Models.load();
        api('/api/sys/version').then(r => {
            let v = r && r.data && r.data.version;
            if (v && /^\{.*\}$/.test(v)) v = 'dev';
            if (v) $('#versionVal').textContent = v;
        }).catch(() => { $('#versionVal').textContent = '未知'; });
    },

    bindShell() {
        $$('.bottom-nav button, .sb-nav button').forEach(b =>
            b.addEventListener('click', () => this.switchPage(b.dataset.page)));
        $('#sheetMask').addEventListener('click', () => UI.closeSheet());
        $('#drawerBtn').addEventListener('click', () => document.body.classList.toggle('drawer-open'));
        $('#drawerMask').addEventListener('click', () => document.body.classList.remove('drawer-open'));
        $('#themeToggle').addEventListener('click', () => Theme.cycle());
        $('#themeHdrBtn').addEventListener('click', () => Theme.cycle());
        $('#langToggle').addEventListener('click', () => I18n.toggle());
        $('#langToggle').textContent = I18n.lang === 'zh-CN' ? '中文' : 'English';
        $('#chatBtn').addEventListener('click', () => location.href = 'chat/index.html');
        $('#refreshBtn').addEventListener('click', () => {
            if (this.currentPage === 'models') Models.load(true);
            else if (this.currentPage === 'hf') HfSearch.refresh();
            else if (this.currentPage === 'bench') Benchmark.load();
            else if (this.currentPage === 'usage') UsageReport.load();
            else if (this.currentPage === 'downloads') Downloads.load();
            else if (this.currentPage === 'sysinfo') SysInfo.load();
            else if (this.currentPage === 'logs') Logs.refresh();
            else if (this.currentPage === 'settings') Settings.load();
        });
    },

    switchPage(name) {
        if (this.currentPage === 'sysinfo' && name !== 'sysinfo') SysInfo.stop();
        if (this.currentPage === 'hf' && name !== 'hf') HfSearch.cancelDetail();
        this.currentPage = name;
        $$('.bottom-nav button, .sb-nav button').forEach(b => b.classList.toggle('active', b.dataset.page === name));
        $$('.page').forEach(p => p.classList.toggle('active', p.id === 'page-' + name));
        $('#headerTitle').textContent = this.PAGE_TITLES[name];
        document.body.classList.remove('drawer-open');
        if (name === 'downloads') Downloads.load();
        if (name === 'bench') Benchmark.load();
        if (name === 'usage') UsageReport.load();
        if (name === 'sysinfo') SysInfo.load();
        if (name === 'logs') Logs.load();
        if (name === 'models') Models.renderCount();
        if (name === 'settings') Settings.load();
    }
};

/* ================= WebSocket ================= */
const WS = {
    ws: null, retry: 0,
    connect() {
        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        try { this.ws = new WebSocket(proto + '//' + location.host + '/ws'); } catch (e) { return this.schedule(); }
        this.ws.onopen = () => { this.retry = 0; };
        this.ws.onmessage = ev => this.onMsg(ev.data);
        this.ws.onclose = () => this.schedule();
        this.ws.onerror = () => {};
    },
    schedule() { clearTimeout(this.t); this.t = setTimeout(() => this.connect(), Math.min(15000, 2000 * ++this.retry)); },
    onMsg(raw) {
        let d; try { d = JSON.parse(raw); } catch (e) { return; }
        switch (d.type) {
            case 'modelLoadStart':
                if (d.modelId) {
                    Models.busyIds.add(d.modelId);
                    Models.patch(d.modelId, { status: 'stopped', isLoaded: false, port: d.port != null ? d.port : null }, d.nodeId);
                }
                break;
            case 'modelLoad': {
                if (d.modelId) Models.busyIds.delete(d.modelId);
                const label = (d.nodeId ? '[' + d.nodeId + '] ' : '') + (d.modelId || '');
                if (d.success) {
                    toast('模型 ' + label + ' 加载成功', 'success');
                    Models.patch(d.modelId, { isLoaded: true, status: 'running', port: d.port != null ? d.port : null }, d.nodeId);
                } else {
                    toast('模型 ' + label + ' 加载失败', 'error');
                    Models.patch(d.modelId, { isLoaded: false, status: 'stopped', port: null }, d.nodeId);
                }
                Logs.onModelsChanged();
                break;
            }
            case 'modelStop': {
                if (d.modelId) Models.busyIds.delete(d.modelId);
                const label = (d.nodeId ? '[' + d.nodeId + '] ' : '') + (d.modelId || '');
                if (d.success) toast('模型 ' + label + ' 已停止', 'success');
                else toast('模型 ' + label + ' 停止失败', 'error');
                Models.patch(d.modelId, { isLoaded: false, status: 'stopped', port: null, busy: false }, d.nodeId);
                Logs.onModelsChanged();
                break;
            }
            case 'model_status':
                if (d.modelId && d.status) Models.patch(d.modelId, { status: d.status }, d.nodeId);
                break;
            case 'model_busy':
                if (d.modelId) Models.patch(d.modelId, { busy: !!d.busy }, d.nodeId);
                break;
            case 'nodeStatus': {
                // 节点上下线：同步模型列表分组头与设置面板的状态
                if (!d.nodeId) break;
                if (Models.nodes && Models.nodes[d.nodeId]) {
                    Models.nodes[d.nodeId].status = d.status;
                    if (App.currentPage === 'models') Models.render();
                }
                const sn = SettingsNodes.nodes.find(n => n.nodeId === d.nodeId);
                if (sn) {
                    sn.status = d.status;
                    if (App.currentPage === 'settings') SettingsNodes.renderNodes();
                }
                break;
            }
            case 'model_slots':
                // slots 细节暂不展示，忽略
                break;
            case 'notification':
                if (d.message) toast(d.message, d.level === 'error' ? 'error' : '');
                break;
            case 'download_progress':
            case 'download_update':
                if (App.currentPage === 'downloads') Downloads.silent();
                SettingsUpdate.onLlamaProgress(d);
                break;
            case 'app_update':
                SettingsUpdate.onAppUpdate(d);
                break;
            case 'console':
                Logs.onConsoleMsg(d);
                break;
        }
    }
};
