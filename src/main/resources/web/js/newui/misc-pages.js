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
    timer: null,

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
        this.startTimer();
    },

    stop() {
        if (this.timer) {
            clearInterval(this.timer);
            this.timer = null;
        }
    },

    startTimer() {
        this.stop();
        this.timer = setInterval(() => {
            if (App.currentPage === 'sysinfo') this.silent();
        }, 1000);
    },

    silent() {
        const q = this.nodeId ? '?nodeId=' + encodeURIComponent(this.nodeId) : '';
        api('/api/sys/sysinfo' + q).then(r => {
            if (r.success && r.data) this.render(r.data);
        }).catch(() => {});
    },

    loadBody() {
        $('#sysinfoBody').innerHTML = '<div class="skeleton" style="margin-bottom:10px;height:120px"></div>' +
            '<div class="skeleton" style="margin-bottom:10px;height:200px"></div>' +
            '<div class="skeleton" style="height:200px"></div>';
        const q = this.nodeId ? '?nodeId=' + encodeURIComponent(this.nodeId) : '';
        api('/api/sys/sysinfo' + q).then(r => {
            if (!r.success) throw new Error(r.error || '加载失败');
            if (!r.data) throw new Error('暂无数据');
            this.render(r.data);
        }).catch(e => { $('#sysinfoBody').innerHTML = '<div class="empty"><i class="fas fa-triangle-exclamation"></i>' + esc(e.message) + '</div>'; });
    },

    render(data) {
        const sys = (data.data && data.data.system) || data.system || {};
        const os = sys.os || {};
        const cpu = sys.cpu || {};
        const mem = sys.memory || {};
        const disks = sys.disks || [];
        const devices = (data.data && data.data.devices) || data.devices || [];
        const jvm = data.jvm;

        const memPct = mem.total_bytes > 0 ? Math.round(mem.used_bytes / mem.total_bytes * 100) : 0;

        let totalVram = 0, usedVram = 0;
        devices.forEach(gpu => {
            const sensors = gpu.sensors || {};
            const gmem = gpu.memory || {};
            const gpuTotal = sensors.memory_total_bytes || gmem.dedicated_vram_bytes || 0;
            const gpuUsed = sensors.memory_used_bytes || 0;
            if (gpuTotal > 0) { totalVram += gpuTotal; usedVram += gpuUsed; }
        });
        const vramPct = totalVram > 0 ? Math.round(usedVram / totalVram * 100) : 0;

        let html = '';

        // ===== 顶部：主机标识 + 环形用量仪表 =====
        const swapPct = mem.swap_total_bytes > 0 ? Math.round((mem.swap_used_bytes || 0) / mem.swap_total_bytes * 100) : 0;
        let diskTotal = 0, diskUsed = 0;
        disks.forEach(d => { if (d.total_bytes > 0) { diskTotal += d.total_bytes; diskUsed += d.used_bytes; } });
        const diskPctTotal = diskTotal > 0 ? Math.round(diskUsed / diskTotal * 100) : 0;

        const cpuLine = (cpu.name || '--') + ' · ' + (cpu.cores || '?') + '核/' + (cpu.threads || '?') + '线程' +
            (cpu.freq_max_mhz > 0 ? ' @ ' + (cpu.freq_max_mhz / 1000).toFixed(2) + ' GHz' : '');
        const osLine = [os.hostname, os.arch].filter(Boolean).join(' · ') +
            (os.uptime_seconds ? ' · 已运行 ' + this.formatUptime(os.uptime_seconds) : '');

        html += '<div class="card si-hero">';
        html += '<div class="si-host">' +
            '<div class="si-host-icon"><i class="fas fa-desktop"></i></div>' +
            '<div class="si-host-t">' +
            '<div class="si-host-name">' + esc(os.name || '--') + '</div>' +
            '<div class="si-host-sub" title="' + esc(osLine) + '">' + esc(osLine || '--') + '</div>' +
            '<div class="si-host-sub" title="' + esc(cpuLine) + '">' + esc(cpuLine) + '</div>' +
            '</div></div>';
        html += '<div class="si-gauges">';
        html += this.donut('内存', memPct, fmtSize(mem.used_bytes) + ' / ' + fmtSize(mem.total_bytes));
        if (totalVram > 0) html += this.donut('显存', vramPct, fmtSize(usedVram) + ' / ' + fmtSize(totalVram));
        if (mem.swap_total_bytes > 0) html += this.donut('Swap', swapPct, fmtSize(mem.swap_used_bytes) + ' / ' + fmtSize(mem.swap_total_bytes));
        if (diskTotal > 0) html += this.donut('磁盘', diskPctTotal, fmtSize(diskUsed) + ' / ' + fmtSize(diskTotal));
        html += '</div></div>';

        // ===== 详细卡片：自适应网格，宽屏多列、窄屏自动堆叠 =====
        html += '<div class="si-grid">';

        // CPU 卡片
        html += '<div class="card">' + this.cardHead('fa-microchip', 'violet', 'CPU', (cpu.cores || '?') + ' 核 / ' + (cpu.threads || '?') + ' 线程');
        html += this.kv('型号', cpu.name);
        html += this.kv('核心', cpu.cores);
        html += this.kv('线程', cpu.threads);
        html += this.kv('最大频率', cpu.freq_max_mhz > 0 ? (cpu.freq_max_mhz / 1000).toFixed(2) + ' GHz' : '--');
        html += '</div>';

        // 内存卡片
        html += '<div class="card">' + this.cardHead('fa-memory', 'green', '内存', '总量 ' + fmtSize(mem.total_bytes));
        html += this.barRow('已用 ' + fmtSize(mem.used_bytes), memPct);
        if (mem.swap_total_bytes > 0) {
            html += this.barRow('Swap ' + fmtSize(mem.swap_used_bytes) + ' / ' + fmtSize(mem.swap_total_bytes), swapPct);
        }
        html += '</div>';

        // GPU 卡片
        html += '<div class="card">' + this.cardHead('fa-plug', 'orange', 'GPU', devices.length ? devices.length + ' 个设备' : '');
        if (!devices.length) {
            html += '<div class="empty" style="padding:12px">未检测到 GPU 设备</div>';
        } else {
            devices.forEach(gpu => {
                const sensors = gpu.sensors || {};
                const gmem = gpu.memory || {};
                const gpuTotal = sensors.memory_total_bytes || gmem.dedicated_vram_bytes || 0;
                const gpuUsed = sensors.memory_used_bytes || 0;
                const gpuVramPct = gpuTotal > 0 ? Math.round(gpuUsed / gpuTotal * 100) : 0;

                html += '<div class="si-gpu">';
                html += '<div class="si-gpu-name">' + esc(gpu.name) + ' <span>' + esc(gpu.vendor || '') +
                    (gpu.type ? ' · ' + esc(gpu.type) : '') + '</span></div>';
                if (gpuTotal > 0) html += this.barRow('显存 ' + fmtSize(gpuUsed) + ' / ' + fmtSize(gpuTotal), gpuVramPct);
                html += '<div class="si-gpu-stats">';
                html += this.gpuStat('温度', sensors.temperature_celsius != null ? sensors.temperature_celsius + '°C' : '--');
                html += this.gpuStat('利用率', sensors.utilization_gpu_pct != null ? sensors.utilization_gpu_pct + '%' : '--');
                html += this.gpuStat('功耗', sensors.power_watts != null ? sensors.power_watts + 'W' : '--');
                if (sensors.power_limit_watts != null) html += this.gpuStat('功耗限制', sensors.power_limit_watts + 'W');
                html += this.gpuStat('风扇', sensors.fan_speed_pct != null ? sensors.fan_speed_pct + '%' : '--');
                html += this.gpuStat('驱动', sensors.driver_version_str || '--');
                html += '</div></div>';
            });
        }
        html += '</div>';

        // 磁盘卡片
        html += '<div class="card">' + this.cardHead('fa-hdd', 'cyan', '磁盘', disks.length ? disks.length + ' 块' : '');
        if (!disks.length) {
            html += '<div class="empty" style="padding:12px">暂无磁盘信息</div>';
        } else {
            disks.forEach(disk => {
                const diskPct = disk.total_bytes > 0 ? Math.round(disk.used_bytes / disk.total_bytes * 100) : 0;
                html += '<div class="si-disk">';
                html += '<div class="t"><span class="n">' + esc(disk.name) + ' (' + esc(disk.filesystem || '') + ')</span><span>' +
                    fmtSize(disk.used_bytes) + ' / ' + fmtSize(disk.total_bytes) + '</span></div>';
                html += '<div class="s">' + esc(disk.mount || '') + (disk.is_external ? ' [外部]' : '') + ' · ' + diskPct + '%</div>';
                html += '<div class="si-bar"><div style="width:' + diskPct + '%;background:' + this.barColor(diskPct) + '"></div></div>';
                html += '</div>';
            });
        }
        html += '</div>';

        // OS 卡片
        html += '<div class="card">' + this.cardHead('fa-info-circle', 'blue', '操作系统', os.hostname || '');
        html += this.kv('名称', os.name);
        html += this.kv('版本', os.version);
        html += this.kv('内核', os.kernel);
        html += this.kv('架构', os.arch);
        html += this.kv('主机名', os.hostname);
        html += this.kv('运行时长', this.formatUptime(os.uptime_seconds));
        html += '</div>';

        // JVM 卡片
        if (jvm) {
            html += '<div class="card">' + this.cardHead('fa-mug-hot', 'rose', '服务进程 (JVM)', 'Java ' + (jvm.javaVersion || '--'));
            html += this.kv('版本', jvm.name + ' ' + jvm.version);
            html += this.kv('Java版本', jvm.javaVersion || '--');
            html += this.kv('内存', jvm.usedMemoryMB + ' / ' + jvm.maxMemoryMB + ' MB');
            html += this.kv('CPU核心', jvm.availableProcessors || '--');
            html += '</div>';
        }

        html += '</div>';

        $('#sysinfoBody').innerHTML = html;
    },

    barColor(pct) {
        return pct >= 90 ? '#ef4444' : pct >= 70 ? '#f59e0b' : '#10b981';
    },

    donut(label, pct, sub) {
        const r = 24, c = 2 * Math.PI * r;
        const off = c * (1 - Math.min(Math.max(pct, 0), 100) / 100);
        return '<div class="si-gauge">' +
            '<svg viewBox="0 0 56 56">' +
            '<circle class="bg" cx="28" cy="28" r="' + r + '"/>' +
            '<circle class="fg" cx="28" cy="28" r="' + r + '" style="stroke:' + this.barColor(pct) +
                ';stroke-dasharray:' + c.toFixed(2) + ';stroke-dashoffset:' + off.toFixed(2) + '"/>' +
            '<text x="28" y="32">' + pct + '%</text>' +
            '</svg>' +
            '<div class="g-l">' + esc(label) + '</div>' +
            '<div class="g-s">' + esc(sub) + '</div></div>';
    },

    cardHead(icon, color, title, sub) {
        return '<div class="si-ch"><div class="si-ic ' + color + '"><i class="fas ' + icon + '"></i></div>' +
            '<div><div class="t">' + esc(title) + '</div>' +
            (sub ? '<div class="s">' + esc(sub) + '</div>' : '') + '</div></div>';
    },

    kv(label, value) {
        return '<div class="kv"><span class="k">' + esc(label) + '</span>' +
            '<span class="v">' + esc(String(value == null || value === '' ? '--' : value)) + '</span></div>';
    },

    barRow(text, pct) {
        return '<div class="si-barrow"><div class="t"><span>' + esc(text) + '</span><span>' + pct + '%</span></div>' +
            '<div class="si-bar"><div style="width:' + pct + '%;background:' + this.barColor(pct) + '"></div></div></div>';
    },

    gpuStat(label, value) {
        return '<div><span class="k">' + esc(label) + ' </span><span>' + esc(String(value)) + '</span></div>';
    },

    formatUptime(seconds) {
        if (!seconds || seconds <= 0) return '--';
        const d = Math.floor(seconds / 86400);
        const h = Math.floor((seconds % 86400) / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        const parts = [];
        if (d > 0) parts.push(d + '天');
        if (h > 0) parts.push(h + '小时');
        if (m > 0) parts.push(m + '分钟');
        return parts.join(' ') || '0分钟';
    }
};

const MiscPages = {
    init() { Downloads.init(); }
};
