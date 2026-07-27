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

        const swapPct = mem.swap_total_bytes > 0 ? Math.round((mem.swap_used_bytes || 0) / mem.swap_total_bytes * 100) : 0;
        let diskTotal = 0, diskUsed = 0;
        disks.forEach(d => { if (d.total_bytes > 0) { diskTotal += d.total_bytes; diskUsed += d.used_bytes; } });
        const diskPctTotal = diskTotal > 0 ? Math.round(diskUsed / diskTotal * 100) : 0;

        let html = '';

        // ===== 概览卡：系统信息表 + 资源用量条 =====
        html += '<div class="card">' + this.cardHead('fa-circle-info', 'blue', '系统概览',
            (os.hostname || '') + (os.uptime_seconds ? ' · 已运行 ' + this.formatUptime(os.uptime_seconds) : ''));

        html += '<div class="si-info">';
        html += this.kv('操作系统', os.name);
        html += this.kv('版本', os.version);
        html += this.kv('内核', os.kernel);
        html += this.kv('架构', os.arch);
        html += this.kv('主机名', os.hostname);
        html += this.kv('运行时长', this.formatUptime(os.uptime_seconds));
        html += this.kv('CPU 型号', cpu.name);
        html += this.kv('核心 / 线程', (cpu.cores || '?') + ' / ' + (cpu.threads || '?'));
        html += this.kv('最大频率', cpu.freq_max_mhz > 0 ? (cpu.freq_max_mhz / 1000).toFixed(2) + ' GHz' : '--');
        if (jvm) {
            html += this.kv('服务进程', jvm.name + ' ' + jvm.version);
            html += this.kv('Java 版本', jvm.javaVersion || '--');
            html += this.kv('JVM 内存', jvm.usedMemoryMB + ' / ' + jvm.maxMemoryMB + ' MB');
        }
        html += '</div>';

        html += '<div class="si-sec">';
        html += this.useRow('内存', memPct, fmtSize(mem.used_bytes) + ' / ' + fmtSize(mem.total_bytes));
        if (totalVram > 0) html += this.useRow('显存', vramPct, fmtSize(usedVram) + ' / ' + fmtSize(totalVram));
        if (mem.swap_total_bytes > 0) html += this.useRow('Swap', swapPct, fmtSize(mem.swap_used_bytes) + ' / ' + fmtSize(mem.swap_total_bytes));
        if (diskTotal > 0) html += this.useRow('磁盘', diskPctTotal, fmtSize(diskUsed) + ' / ' + fmtSize(diskTotal));
        html += '</div></div>';

        // ===== GPU 表：每行一个设备 =====
        html += '<div class="card">' + this.cardHead('fa-plug', 'orange', 'GPU', devices.length ? devices.length + ' 个设备' : '');
        if (!devices.length) {
            html += '<div class="empty" style="padding:12px">未检测到 GPU 设备</div>';
        } else {
            html += '<table class="si-table"><thead><tr>' +
                '<th>名称</th><th>显存</th>' +
                '<th class="num">温度</th><th class="num">利用率</th><th class="num">功耗</th><th class="num">风扇</th><th>驱动</th>' +
                '</tr></thead><tbody>';
            devices.forEach(gpu => {
                const sensors = gpu.sensors || {};
                const gmem = gpu.memory || {};
                const gpuTotal = sensors.memory_total_bytes || gmem.dedicated_vram_bytes || 0;
                const gpuUsed = sensors.memory_used_bytes || 0;
                const gpuVramPct = gpuTotal > 0 ? Math.round(gpuUsed / gpuTotal * 100) : 0;
                const power = sensors.power_watts != null ? sensors.power_watts + 'W' +
                    (sensors.power_limit_watts != null ? ' / ' + sensors.power_limit_watts + 'W' : '') : '--';

                html += '<tr>' +
                    '<td class="rh"><span class="si-name">' + esc(gpu.name) + '</span> <span class="si-dim">' + esc(gpu.vendor || '') + '</span></td>' +
                    '<td class="num" data-th="显存">' + (gpuTotal > 0 ? fmtSize(gpuUsed) + ' / ' + fmtSize(gpuTotal) + ' (' + gpuVramPct + '%)' : '--') + '</td>' +
                    '<td class="num" data-th="温度">' + (sensors.temperature_celsius != null ? sensors.temperature_celsius + '°C' : '--') + '</td>' +
                    '<td class="num" data-th="利用率">' + (sensors.utilization_gpu_pct != null ? sensors.utilization_gpu_pct + '%' : '--') + '</td>' +
                    '<td class="num" data-th="功耗">' + power + '</td>' +
                    '<td class="num" data-th="风扇">' + (sensors.fan_speed_pct != null ? sensors.fan_speed_pct + '%' : '--') + '</td>' +
                    '<td data-th="驱动">' + esc(sensors.driver_version_str || '--') + '</td>' +
                    '</tr>';
            });
            html += '</tbody></table>';
        }
        html += '</div>';

        // ===== 磁盘表：每行一个分区 =====
        html += '<div class="card">' + this.cardHead('fa-hdd', 'cyan', '磁盘', disks.length ? disks.length + ' 块' : '');
        if (!disks.length) {
            html += '<div class="empty" style="padding:12px">暂无磁盘信息</div>';
        } else {
            html += '<table class="si-table"><thead><tr>' +
                '<th>名称</th><th>文件系统</th><th>挂载点</th><th class="num">已用 / 总量</th><th class="bar-cell">用量</th>' +
                '</tr></thead><tbody>';
            disks.forEach(disk => {
                const diskPct = disk.total_bytes > 0 ? Math.round(disk.used_bytes / disk.total_bytes * 100) : 0;
                html += '<tr>' +
                    '<td class="rh"><span class="si-name">' + esc(disk.name) + '</span></td>' +
                    '<td data-th="文件系统">' + esc(disk.filesystem || '--') + '</td>' +
                    '<td data-th="挂载点">' + esc(disk.mount || '--') + (disk.is_external ? ' <span class="si-dim">[外部]</span>' : '') + '</td>' +
                    '<td class="num" data-th="已用 / 总量">' + fmtSize(disk.used_bytes) + ' / ' + fmtSize(disk.total_bytes) + '</td>' +
                    '<td class="bar-cell" data-th="用量"><div class="si-cellbar">' +
                        '<div class="si-bar"><div style="width:' + diskPct + '%;background:' + this.barColor(diskPct) + '"></div></div>' +
                        '<span>' + diskPct + '%</span></div></td>' +
                    '</tr>';
            });
            html += '</tbody></table>';
        }
        html += '</div>';

        $('#sysinfoBody').innerHTML = html;
    },

    barColor(pct) {
        return pct >= 90 ? '#ef4444' : pct >= 70 ? '#f59e0b' : '#10b981';
    },

    cardHead(icon, color, title, sub) {
        return '<div class="si-ch"><div class="si-ic ' + color + '"><i class="fas ' + icon + '"></i></div>' +
            '<div><div class="t">' + esc(title) + '</div>' +
            (sub ? '<div class="s">' + esc(sub) + '</div>' : '') + '</div></div>';
    },

    useRow(label, pct, text) {
        return '<div class="si-use"><span class="k">' + esc(label) + '</span>' +
            '<div class="si-bar"><div style="width:' + pct + '%;background:' + this.barColor(pct) + '"></div></div>' +
            '<span class="v"><b>' + pct + '%</b> ' + esc(text) + '</span></div>';
    },

    kv(label, value) {
        return '<div class="kv"><span class="k">' + esc(label) + '</span>' +
            '<span class="v">' + esc(String(value == null || value === '' ? '--' : value)) + '</span></div>';
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
