/* ================= misc-pages.js — 下载 / 系统信息 ================= */
'use strict';

const Downloads = {
    timer: null,
    init() {
        $('#newDownloadBtn').addEventListener('click', () => this.openCreate());
        $('#dlSubmitBtn').addEventListener('click', () => this.create());
    },
    load() {
        api('/api/downloads/list').then(r => {
            if (!r.success) throw new Error(r.error || '加载失败');
            this.render(r.downloads || []);
        }).catch(e => { $('#downloadList').innerHTML = '<div class="empty"><i class="fas fa-triangle-exclamation"></i>' + esc(e.message) + '</div>'; });
        clearInterval(this.timer);
        this.timer = setInterval(() => { if (App.currentPage === 'downloads') this.silent(); }, 3000);
    },
    silent() { api('/api/downloads/list').then(r => { if (r.success) this.render(r.downloads || []); }).catch(() => {}); },
    render(list) {
        if (!list.length) { $('#downloadList').innerHTML = '<div class="empty"><i class="fas fa-download"></i>暂无下载任务</div>'; return; }
        $('#downloadList').innerHTML = list.map(d => {
            const pct = Math.round((d.progressRatio || 0) * 100);
            const running = d.state === 'RUNNING' || d.state === 'DOWNLOADING';
            const done = d.state === 'COMPLETED';
            const statusText = { COMPLETED: '已完成', RUNNING: '下载中', DOWNLOADING: '下载中', PAUSED: '已暂停', FAILED: '失败', WAITING: '等待中' }[d.state] || d.state;
            const actions = done
                ? '<button class="btn danger-soft" onclick="Downloads.act(\'delete\',\'' + d.taskId + '\',\'' + esc(d.nodeId || '') + '\')"><i class="fas fa-trash"></i> 删除记录</button>'
                : (running
                    ? '<button class="btn" onclick="Downloads.act(\'pause\',\'' + d.taskId + '\',\'' + esc(d.nodeId || '') + '\')"><i class="fas fa-pause"></i> 暂停</button>'
                    : '<button class="btn primary" onclick="Downloads.act(\'resume\',\'' + d.taskId + '\',\'' + esc(d.nodeId || '') + '\')"><i class="fas fa-play"></i> 继续</button>') +
                  '<button class="btn danger-soft" onclick="Downloads.act(\'delete\',\'' + d.taskId + '\',\'' + esc(d.nodeId || '') + '\')"><i class="fas fa-trash"></i></button>';
            const nodeTag = (d.nodeId && d.nodeId !== 'local') ? '<span><i class="fas fa-server"></i> ' + esc(d.nodeName || d.nodeId) + '</span>' : '';
            return '<div class="card">' +
                '<div style="font-size:14px;font-weight:600;word-break:break-all">' + esc(d.fileName || d.url) + '</div>' +
                '<div class="mc-meta" style="margin-top:6px"><span>' + esc(statusText) + '</span>' + nodeTag + '<span>' + fmtSize(d.downloadedBytes) + ' / ' + fmtSize(d.totalBytes) + '</span><span>' + pct + '%</span></div>' +
                '<div class="progress-track"><div class="progress-fill" style="width:' + pct + '%"></div></div>' +
                '<div class="mc-foot">' + actions + '</div>' +
            '</div>';
        }).join('');
    },
    act(action, taskId, nodeId) {
        post('/api/downloads/' + action, { taskId, nodeId: nodeId || '' }).then(r => {
            if (r.success) this.load(); else toast(r.error || '操作失败', 'error');
        });
    },
    create() {
        const url = $('#dlUrl').value.trim();
        if (!url) { toast('请填写下载地址', 'error'); return; }
        const body = { url };
        const fn = $('#dlFileName').value.trim();
        if (fn) body.fileName = fn;
        const nodeId = $('#dlNode').value;
        if (nodeId && nodeId !== 'local') body.nodeId = nodeId;
        post('/api/downloads/create', body).then(r => {
            if (r.success) { UI.closeSheet(); $('#dlUrl').value = ''; $('#dlFileName').value = ''; toast('任务已创建', 'success'); this.load(); }
            else toast(r.error || '创建失败', 'error');
        });
    },
    /* 新建下载弹层的节点选择 */
    openCreate() {
        const sel = $('#dlNode');
        const opts = ['<option value="local">本地</option>']
            .concat(Object.keys(Models.nodes || {}).map(n =>
                '<option value="' + esc(n) + '">' + esc((Models.nodes[n] || {}).name || n) + '</option>'));
        sel.innerHTML = opts.join('');
        UI.openSheet('#downloadSheet');
    }
};

const SysInfo = {
    nodeId: '',
    load() {
        // 渲染节点 TAB（本地 + 各远程节点）
        api('/api/node/list').then(r => {
            const nodes = r.success && Array.isArray(r.data) ? r.data : [];
            const bar = $('#sysinfoNodes');
            if (!nodes.length) { bar.style.display = 'none'; return; }
            bar.style.display = '';
            bar.innerHTML = '<button class="chip' + (this.nodeId === '' ? ' active' : '') + '" data-nid="">本地</button>' +
                nodes.map(n => '<button class="chip' + (this.nodeId === n.nodeId ? ' active' : '') + '" data-nid="' + esc(n.nodeId) + '">' +
                    esc(n.name || n.nodeId) + (n.status === 'ONLINE' ? '' : '（离线）') + '</button>').join('');
            $$('#sysinfoNodes .chip').forEach(c => c.addEventListener('click', () => {
                this.nodeId = c.dataset.nid;
                $$('#sysinfoNodes .chip').forEach(x => x.classList.toggle('active', x === c));
                this.loadBody();
            }));
        }).catch(() => {});
        this.loadBody();
    },
    loadBody() {
        $('#sysinfoBody').innerHTML = '<div class="skeleton" style="margin-bottom:10px"></div>'.repeat(3);
        const q = this.nodeId ? '?nodeId=' + encodeURIComponent(this.nodeId) : '';
        const gpuQ = this.nodeId ? '?nodeId=' + encodeURIComponent(this.nodeId) : '';
        Promise.all([api('/api/sys/sysinfo' + q), api('/api/sys/gpu/info' + gpuQ).catch(() => null)]).then(([r, gpu]) => {
            if (!r.success) throw new Error(r.error || '加载失败');
            const sys = (r.data && (r.data.data && r.data.data.system || r.data.system)) || {};
            const jvm = r.data && r.data.jvm;
            let html = '';
            if (sys.os) html += this.card('fa-server', '操作系统', [
                ['名称', sys.os.name], ['版本', sys.os.version], ['主机名', sys.os.hostname],
                ['运行时长', sys.os.uptime_seconds ? Math.floor(sys.os.uptime_seconds / 3600) + ' 小时' : '—']]);
            if (sys.cpu) html += this.card('fa-microchip', 'CPU', [
                ['型号', sys.cpu.name], ['核心', (sys.cpu.cores || '?') + ' 核 / ' + (sys.cpu.threads || '?') + ' 线程']]);
            if (sys.memory) html += this.card('fa-memory', '内存', [
                ['总量', fmtSize(sys.memory.total_bytes)], ['已用', fmtSize(sys.memory.used_bytes)],
                ['使用率', sys.memory.total_bytes ? Math.round(sys.memory.used_bytes / sys.memory.total_bytes * 100) + '%' : '—']]);
            const gpus = gpu && gpu.success && gpu.data && (gpu.data.gpus || gpu.data.list || (Array.isArray(gpu.data) ? gpu.data : null));
            if (gpus && gpus.length) gpus.forEach((g, i) => {
                html += this.card('fa-display', 'GPU' + (gpus.length > 1 ? ' ' + i : ''), [
                    ['型号', g.name || g.model || '—'],
                    ['显存', g.memory_total_bytes ? fmtSize(g.memory_used_bytes || 0) + ' / ' + fmtSize(g.memory_total_bytes) : (g.vram || '—')],
                    ['利用率', g.utilization_percent != null ? g.utilization_percent + '%' : '—']]);
            });
            if (jvm) html += this.card('fa-mug-hot', '服务进程 (JVM)', [
                ['版本', jvm.name + ' ' + jvm.version], ['内存', jvm.usedMemoryMB + ' / ' + jvm.maxMemoryMB + ' MB']]);
            $('#sysinfoBody').innerHTML = html || '<div class="empty"><i class="fas fa-circle-info"></i>暂无数据</div>';
        }).catch(e => { $('#sysinfoBody').innerHTML = '<div class="empty"><i class="fas fa-triangle-exclamation"></i>' + esc(e.message) + '</div>'; });
    },
    card(icon, title, rows) {
        return '<div class="card"><h3><i class="fas ' + icon + '"></i> ' + title + '</h3>' +
            rows.filter(r => r[1] != null).map(r => '<div class="kv"><span class="k">' + esc(r[0]) + '</span><span class="v">' + esc(r[1]) + '</span></div>').join('') + '</div>';
    }
};

const MiscPages = {
    init() { Downloads.init(); }
};
