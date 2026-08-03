/* ================= benchmark.js — 性能测试页（移植自旧版 js/benchmark-v3.js） ================= */
/* 后端接口不变：
   API 压测   POST /api/v2/models/benchmark · GET /api/v2/models/benchmark/get · POST /api/v2/models/benchmark/delete
   llama-bench POST /api/models/benchmark · GET /api/models/benchmark/list|get · POST /api/models/benchmark/delete
   参数定义   GET /api/models/param/benchmark/list · 设备 GET /api/model/device/list */
'use strict';

const Benchmark = {
    mode: 'server',
    allModels: [], loadedKeys: new Set(), nodes: {},
    selId: '', selNode: '', filterText: '', nodeFilter: 'all',
    /* API 压测 */
    svRunning: false, svAbort: null, svRecords: [], hwByModel: new Map(),
    /* llama-bench */
    bRunning: false, bAbort: null, llamaPaths: [],
    historyFiles: [], selFile: '', fileData: null,
    /* 参数设置 */
    params: [], paramLoaded: false, enabled: {}, values: {}, devices: [],

    init() {
        $('#benchSearch').addEventListener('input', e => { this.filterText = e.target.value.trim(); this.renderModelList(); });
        $('#benchNodeFilter').addEventListener('change', e => { this.nodeFilter = e.target.value; this.renderModelList(); });
        $$('#benchModeChips .chip').forEach(c => c.addEventListener('click', () => {
            this.mode = c.dataset.mode;
            $$('#benchModeChips .chip').forEach(x => x.classList.toggle('active', x === c));
            $$('.bench-pane').forEach(p => p.classList.toggle('active', p.dataset.pane === this.mode));
        }));
        /* API 压测 */
        $('#svRunBtn').addEventListener('click', () => this.runServer());
        $('#svExpCsv').addEventListener('click', () => this.exportRecords('csv'));
        $('#svExpJson').addEventListener('click', () => this.exportRecords('json'));
        $('#svExpMd').addEventListener('click', () => this.exportRecords('md'));
        /* llama-bench */
        $('#benchRunBtn').addEventListener('click', () => this.runBench());
        $('#benchLlamaBin').addEventListener('change', () => this.loadDevices());
        $('#benchParamsBtn').addEventListener('click', () => this.openParams());
        $('#bpResetBtn').addEventListener('click', () => { this.resetParams(); this.refreshParamCount(); });
        $('#benchCopyBtn').addEventListener('click', () => this.copyOutput());
        $('#benchExportBtn').addEventListener('click', () => this.exportOutput());
        window.addEventListener('resize', () => { if (App.currentPage === 'bench') this.renderChart(); });
    },

    /* 每次进入页面刷新模型数据 */
    load() {
        Promise.all([
            api('/api/models/list'),
            api('/api/models/loaded').catch(() => null),
            api('/api/node/list').catch(() => null)
        ]).then(([list, loaded, nodes]) => {
            this.allModels = (list && list.success && Array.isArray(list.models)) ? list.models : [];
            this.loadedKeys = new Set();
            if (loaded && loaded.success && Array.isArray(loaded.models)) {
                loaded.models.forEach(m => this.loadedKeys.add(m.id + '@' + (m.nodeId || 'local')));
            }
            this.nodes = {};
            if (nodes && nodes.success && Array.isArray(nodes.data)) nodes.data.forEach(n => { this.nodes[n.nodeId] = n; });
            this.renderNodeFilter();
            this.renderModelList();
            if (this.selId && !this.findModel(this.selId, this.selNode)) this.selectModel('', '');
        });
    },

    /* ================= 模型列表 ================= */
    findModel(id, node) {
        return this.allModels.find(m => m.id === id && (m.nodeId || 'local') === (node || 'local'));
    },
    modelNode(m) { return (m.nodeId && m.nodeId !== 'local') ? m.nodeId : ''; },
    /* 选中模型的 nodeId 参数（本地/全部 → 空） */
    nodeId() { return this.selNode || ''; },
    nodeQuery() { const n = this.nodeId(); return n ? '&nodeId=' + encodeURIComponent(n) : ''; },

    renderNodeFilter() {
        const sel = $('#benchNodeFilter');
        const prev = sel.value || 'all';
        const seen = new Set();
        const opts = ['<option value="all">全部节点</option>', '<option value="local">本地</option>'];
        this.allModels.forEach(m => {
            const nd = this.modelNode(m);
            if (nd && !seen.has(nd)) { seen.add(nd); opts.push('<option value="' + esc(nd) + '">' + esc((this.nodes[nd] || {}).name || nd) + '</option>'); }
        });
        sel.innerHTML = opts.join('');
        sel.value = opts.some(o => o.includes('value="' + prev + '"')) ? prev : 'all';
        this.nodeFilter = sel.value;
        sel.style.display = seen.size ? '' : 'none';
    },

    renderModelList() {
        const q = this.filterText.toLowerCase();
        let list = this.allModels.filter(m => {
            const name = (m.alias || m.name || '').toLowerCase();
            if (q && !name.includes(q) && !(m.id || '').toLowerCase().includes(q)) return false;
            if (this.nodeFilter === 'local') return !this.modelNode(m);
            if (this.nodeFilter !== 'all') return this.modelNode(m) === this.nodeFilter;
            return true;
        });
        list.sort((a, b) => (a.alias || a.name).localeCompare(b.alias || b.name, 'zh-CN'));
        const box = $('#benchModelList');
        if (!list.length) { box.innerHTML = '<div class="bench-model-empty">' + (this.allModels.length ? '没有匹配的模型' : '暂无模型') + '</div>'; return; }
        box.innerHTML = list.map(m => {
            const node = this.modelNode(m);
            const key = m.id + '|' + (node || 'local');
            const loaded = this.loadedKeys.has(m.id + '@' + (node || 'local'));
            let meta = '';
            if (loaded) meta += '<span class="bm-loaded"><i class="fas fa-circle"></i>已加载</span>';
            if (m.size) meta += '<span><i class="fas fa-hdd"></i>' + fmtSize(m.size) + '</span>';
            if (node) meta += '<span class="bm-node"><i class="fas fa-server"></i>' + esc((this.nodes[node] || {}).name || node) + '</span>';
            const cls = 'bm-item' + (node ? ' remote' : '') + (this.selId === m.id && this.selNode === node ? ' active' : '');
            const style = node ? ' style="--nh:' + benchNodeHue(node) + '"' : '';
            return '<div class="' + cls + '" data-key="' + esc(key) + '"' + style + '>' +
                '<div class="bm-name">' + esc(m.alias || m.name) + '</div>' +
                (meta ? '<div class="bm-meta">' + meta + '</div>' : '') + '</div>';
        }).join('');
        $$('#benchModelList .bm-item').forEach(el => el.addEventListener('click', () => {
            const [id, node] = el.dataset.key.split('|');
            this.selectModel(id, node === 'local' ? '' : node);
        }));
    },

    selectModel(id, node) {
        this.selId = id;
        this.selNode = node || '';
        this.reqSeq = (this.reqSeq || 0) + 1; // 使进行中的加载响应作废（防快速切换模型时旧响应覆盖新状态）
        this.renderModelList();
        /* API 压测侧 */
        const loaded = id && this.loadedKeys.has(id + '@' + (node || 'local'));
        $('#svRunBtn').disabled = !loaded;
        $('#svRunBtn').title = loaded ? '' : '模型未加载，无法运行测试';
        $('#svStatus').textContent = '';
        this.loadRecords();
        /* llama-bench 侧 */
        this.loadLlamaPaths();
        this.loadHistory();
    },

    /* ================= API 压测 ================= */
    async runServer() {
        if (this.svRunning) { this.cancelServer(); return; }
        const modelId = this.selId;
        if (!modelId) { $('#svStatus').textContent = '请选择模型'; return; }
        const promptTokens = parseInt($('#svPromptTokens').value, 10);
        const maxTokens = parseInt($('#svMaxTokens').value, 10);
        const concurrency = parseInt($('#svConcurrency').value, 10);
        if (!Number.isFinite(promptTokens) || promptTokens <= 0) { $('#svStatus').textContent = '提示词长度必须大于 0'; return; }
        if (!Number.isFinite(maxTokens) || maxTokens <= 0) { $('#svStatus').textContent = '输出长度必须大于 0'; return; }
        if (!Number.isFinite(concurrency) || concurrency <= 0) { $('#svStatus').textContent = '并发数必须大于 0'; return; }
        this.svRunning = true;
        this.svAbort = new AbortController();
        const btn = $('#svRunBtn');
        btn.innerHTML = '<i class="fas fa-stop"></i> 取消';
        btn.classList.add('danger-soft');
        $('#svStatus').textContent = concurrency > 1 ? '并发压测中（' + concurrency + '）…' : '压测中…';
        const payload = { modelId, promptTokens, maxTokens };
        const nid = this.nodeId(); if (nid) payload.nodeId = nid;
        const requestOnce = () => fetch('/api/v2/models/benchmark', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload), signal: this.svAbort.signal
        }).then(r => r.json());
        try {
            if (concurrency === 1) {
                const d = await requestOnce();
                if (d && d.success) {
                    this.saveHw(d.data, modelId);
                    await this.loadRecords();
                    $('#svStatus').textContent = '完成';
                } else $('#svStatus').textContent = (d && d.error) || '测试失败';
            } else {
                const results = await Promise.allSettled(Array.from({ length: concurrency }, () => requestOnce()));
                if (this.svAbort && this.svAbort.signal.aborted) return;
                const okData = [];
                results.forEach(r => { if (r.status === 'fulfilled' && r.value && r.value.success) okData.push(r.value.data); });
                if (okData.length) {
                    okData.forEach(d => this.saveHw(d, modelId));
                    await this.loadRecords();
                    // 统计行须在记录重载后插入，否则会被重渲染冲掉
                    if (okData.length > 1) this.prependStatsRow(this.computeStats(okData));
                    $('#svStatus').textContent = '完成 ' + okData.length + '/' + concurrency;
                } else {
                    const firstErr = results.map(r => r.status === 'fulfilled' ? (r.value && r.value.error) : '').find(e => e);
                    $('#svStatus').textContent = '全部失败' + (firstErr ? '：' + firstErr : '');
                }
            }
        } catch (e) {
            if (e && e.name === 'AbortError') $('#svStatus').textContent = '已取消';
            else $('#svStatus').textContent = e.message || '请求失败';
        } finally {
            this.svRunning = false;
            this.svAbort = null;
            btn.innerHTML = '<i class="fas fa-play"></i> 运行';
            btn.classList.remove('danger-soft');
        }
    },

    cancelServer() {
        if (!this.svRunning) return;
        if (this.svAbort) this.svAbort.abort();
        $('#svStatus').textContent = '取消中…';
    },

    computeStats(results) {
        const pick = k => results.map(r => Number(r.timings && r.timings[k])).filter(Number.isFinite);
        const pf = pick('prompt_per_second'), dg = pick('predicted_per_second');
        const pn = pick('prompt_n'), dn = pick('predicted_n');
        const drn = pick('draft_n'), dra = pick('draft_n_accepted');
        const avg = a => a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0;
        const min = a => a.length ? Math.min(...a) : 0;
        const max = a => a.length ? Math.max(...a) : 0;
        const pct = (a, p) => { if (!a.length) return 0; const s = a.slice().sort((x, y) => x - y); return s[Math.max(0, Math.min(Math.ceil(p / 100 * s.length) - 1, s.length - 1))]; };
        return {
            isStats: true, modelId: results[0] && results[0].modelId, timestamp: new Date().toISOString(), count: results.length,
            prefill: { avg: avg(pf), min: min(pf), max: max(pf), p50: pct(pf, 50), p95: pct(pf, 95) },
            decode: { avg: avg(dg), min: min(dg), max: max(dg), p50: pct(dg, 50), p95: pct(dg, 95) },
            promptN: { avg: avg(pn) }, predictedN: { avg: avg(dn) },
            draftN: { avg: avg(drn) }, draftAccepted: { avg: avg(dra) }
        };
    },

    saveHw(source, fallbackId) {
        const modelId = String((source && source.modelId) || fallbackId || '').trim();
        if (!modelId) return;
        const prev = this.hwByModel.get(modelId) || {};
        const devs = d => Array.isArray(d) ? d.map(x => String(x).trim()).filter(Boolean) : (d ? [String(d).trim()] : []);
        this.hwByModel.set(modelId, {
            cpu: String(source.cpu || prev.cpu || ''),
            ram: String(source.ram != null ? source.ram : (prev.ram || '')),
            devices: devs(source.devices).length ? devs(source.devices) : (prev.devices || []),
            llamaBinPath: String(source.llamaBinPath || prev.llamaBinPath || ''),
            cmd: String(source.cmd || prev.cmd || '')
        });
    },
    hwFallback(r) {
        const out = Object.assign({}, r);
        const p = this.hwByModel.get(out.modelId || this.selId);
        if (!p) return out;
        if (!out.cpu && p.cpu) out.cpu = p.cpu;
        if ((out.ram == null || out.ram === '') && p.ram) out.ram = p.ram;
        if (!out.devices && p.devices) out.devices = p.devices;
        if (!out.llamaBinPath && p.llamaBinPath) out.llamaBinPath = p.llamaBinPath;
        if (!out.cmd && p.cmd) out.cmd = p.cmd;
        return out;
    },

    loadRecords() {
        const id = this.selId;
        if (!id) { this.svRecords = []; this.renderRecords(); return; }
        const seq = this.reqSeq;
        return api('/api/v2/models/benchmark/get?modelId=' + encodeURIComponent(id) + this.nodeQuery())
            .then(d => {
                if (seq !== this.reqSeq) return;
                if (!d || d.success !== true) {
                    this.svRecords = [];
                    const err = d && d.error;
                    $('#svStatus').textContent = (!err || err === '文件不存在' || err === 'api.error.file.notfound') ? '暂无记录' : err;
                    this.renderRecords();
                    return;
                }
                const records = (d.data && Array.isArray(d.data.records)) ? d.data.records : [];
                records.sort((a, b) => String(b && b.timestamp).localeCompare(String(a && a.timestamp)));
                records.forEach(r => this.saveHw(r, id));
                this.svRecords = records;
                $('#svStatus').textContent = records.length ? '共 ' + records.length + ' 条记录' : '暂无记录';
                this.renderRecords();
            })
            .catch(e => { if (seq !== this.reqSeq) return; this.svRecords = []; this.renderRecords(); $('#svStatus').textContent = e.message || '记录加载失败'; });
    },

    renderRecords() {
        const body = $('#svResultBody');
        const has = this.svRecords.length > 0;
        $('#svEmpty').style.display = has ? 'none' : '';
        body.innerHTML = this.svRecords.map((r, i) => this.recordRowHtml(r, i)).join('');
        body.querySelectorAll('.sv-del').forEach(btn => btn.addEventListener('click', () => {
            this.deleteRecord(this.svRecords[Number(btn.dataset.idx)]);
        }));
        this.renderChart();
    },

    recordRowHtml(r, idx) {
        const t = r.timings || {};
        const pf = t.prompt_per_second, dg = t.predicted_per_second;
        const draft = (t.draft_n != null && t.draft_n > 0) ? (t.draft_n_accepted || 0) + '/' + t.draft_n : '';
        const speedCell = (v, isPrefill) => {
            if (!Number.isFinite(Number(v))) return '<td class="num">-</td>';
            const n = Number(v);
            const cls = isPrefill ? (n > 200 ? 'sp-fast' : n > 100 ? 'sp-mid' : 'sp-slow') : (n > 50 ? 'sp-fast' : n > 20 ? 'sp-mid' : 'sp-slow');
            return '<td class="num ' + cls + '">' + benchFmtNum(n) + '</td>';
        };
        let html = '<tr>' +
            '<td>' + esc(benchFmtTs(r.timestamp)) + '</td>' +
            '<td class="num">' + (r.promptTokens != null ? r.promptTokens : (t.prompt_n != null ? t.prompt_n : '-')) + '</td>' +
            speedCell(pf, true) +
            '<td class="num">' + (t.predicted_n != null ? t.predicted_n : (r.maxTokens != null ? r.maxTokens : '-')) + '</td>' +
            speedCell(dg, false) +
            '<td>' + (draft || '') + '</td>' +
            '<td>' + (r._lineNumber ? '<button class="icon-btn sv-del" data-idx="' + idx + '" title="删除记录"><i class="fas fa-trash"></i></button>' : '') + '</td>' +
        '</tr>';
        /* 硬件 / 命令行详情行：第一行 CPU+GPU 硬件信息，之后 cmd/path/output 各自成行（与旧版一致） */
        const m = this.hwFallback(r);
        const lines = [];
        const cpuRam = m.cpu ? (m.ram ? m.cpu + ' / ' + m.ram + 'GB' : m.cpu) : (m.ram ? m.ram + 'GB' : '');
        const gpu = Array.isArray(m.devices) ? m.devices.filter(Boolean).join(' | ') : '';
        const hw = [cpuRam, gpu].filter(Boolean).join('  ');
        if (hw) lines.push(hw);
        if (m.cmd) lines.push('cmd: ' + m.cmd);
        if (m.llamaBinPath) lines.push('path: ' + m.llamaBinPath);
        if (r.rawOutput) lines.push('output: ' + (r.rawOutput.length > 500 ? r.rawOutput.slice(0, 500) + '…' : r.rawOutput));
        if (lines.length) html += '<tr class="rec-detail"><td colspan="7">' + esc(lines.join('\n')) + '</td></tr>';
        return html;
    },

    prependStatsRow(s) {
        $('#svEmpty').style.display = 'none';
        const pf = s.prefill, dg = s.decode;
        const fmt = o => o ? o.avg.toFixed(1) + '（min ' + o.min.toFixed(1) + ' · P50 ' + o.p50.toFixed(1) + ' · P95 ' + o.p95.toFixed(1) + ' · max ' + o.max.toFixed(1) + '）' : '-';
        const tr = document.createElement('tr');
        tr.className = 'stats-row';
        tr.innerHTML = '<td>并发×' + s.count + '</td>' +
            '<td class="num">' + Math.round(s.promptN.avg || 0) + '</td>' +
            '<td class="num">' + fmt(pf) + '</td>' +
            '<td class="num">' + Math.round(s.predictedN.avg || 0) + '</td>' +
            '<td class="num">' + fmt(dg) + '</td>' +
            '<td>' + (s.draftN.avg > 0 ? Math.round(s.draftAccepted.avg) + '/' + Math.round(s.draftN.avg) : '') + '</td><td></td>';
        const body = $('#svResultBody');
        body.insertBefore(tr, body.firstChild);
    },

    deleteRecord(r) {
        if (!r || !r._lineNumber) { $('#svStatus').textContent = '无法删除该记录'; return; }
        if (!confirm('确定删除该记录吗？')) return;
        const modelId = r.modelId || this.selId;
        const body = { modelId, lineNumber: Number(r._lineNumber) };
        const nid = this.nodeId(); if (nid) body.nodeId = nid;
        post('/api/v2/models/benchmark/delete', body).then(d => {
            if (d && d.success) { $('#svStatus').textContent = '已删除'; this.loadRecords(); }
            else $('#svStatus').textContent = (d && d.error) || '删除失败';
        }).catch(e => $('#svStatus').textContent = e.message || '删除失败');
    },

    /* ---------------- 图表（canvas 手绘柱状图，移植旧版逻辑） ---------------- */
    renderChart() {
        const canvas = $('#svChart'), empty = $('#svChartEmpty'), wrap = $('#svChartWrap');
        const data = this.svRecords.filter(r => !r.isStats && r.timings && r.timings.prompt_per_second != null);
        if (!data.length) { empty.style.display = 'flex'; return; }
        empty.style.display = 'none';
        const ctx = canvas.getContext('2d');
        const rect = wrap.getBoundingClientRect();
        const dpr = window.devicePixelRatio || 1;
        const w = rect.width - 4, h = rect.height - 4;
        if (w <= 0 || h <= 0) return;
        canvas.width = w * dpr; canvas.height = h * dpr;
        canvas.style.width = w + 'px'; canvas.style.height = h + 'px';
        ctx.scale(dpr, dpr);
        const pad = { top: 10, bottom: 18, left: 40, right: 10 };
        const cw = w - pad.left - pad.right, ch = h - pad.top - pad.bottom;
        const count = Math.min(data.length, 30);
        const barW = Math.min(cw / count * 0.35, 16);
        const gap = barW * 0.5, gw = barW * 2 + gap;
        const val = v => { const n = Number(v) || 0; return n >= 100000 ? 0 : n; };
        let maxV = 0;
        data.slice(0, count).forEach(d => { maxV = Math.max(maxV, val(d.timings.prompt_per_second), val(d.timings.predicted_per_second)); });
        maxV = Math.ceil(Math.max(maxV * 1.15, 10));
        const cs = getComputedStyle(wrap);
        const tc = cs.getPropertyValue('--text-2').trim() || '#8b93a3';
        const bc = cs.getPropertyValue('--border').trim() || '#e5e7eb';
        ctx.clearRect(0, 0, w, h);
        ctx.strokeStyle = bc; ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(pad.left, pad.top); ctx.lineTo(pad.left, pad.top + ch); ctx.lineTo(pad.left + cw, pad.top + ch); ctx.stroke();
        ctx.fillStyle = tc; ctx.font = '9px sans-serif'; ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
        for (let i = 0; i <= 4; i++) {
            const y = pad.top + ch - (ch / 4) * i;
            ctx.fillText(benchFmtNum(maxV / 4 * i), pad.left - 5, y);
            ctx.globalAlpha = 0.35; ctx.strokeStyle = bc; ctx.lineWidth = 0.5;
            ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(pad.left + cw, y); ctx.stroke();
            ctx.globalAlpha = 1;
        }
        const colors = ['rgba(99,102,241,0.85)', 'rgba(16,185,129,0.85)'];
        for (let i = 0; i < count; i++) {
            const d = data[i];
            const pf = val(d.timings.prompt_per_second), dg = val(d.timings.predicted_per_second);
            const x = pad.left + (cw / count) * i + (cw / count - gw) / 2;
            ctx.fillStyle = colors[0];
            ctx.fillRect(x, pad.top + ch - (pf / maxV) * ch, barW, (pf / maxV) * ch);
            ctx.fillStyle = colors[1];
            ctx.fillRect(x + barW + gap, pad.top + ch - (dg / maxV) * ch, barW, (dg / maxV) * ch);
        }
    },

    /* ---------------- 导出 ---------------- */
    exportRecords(format) {
        const records = this.svRecords.filter(r => !r.isStats);
        if (!records.length) { toast('没有可导出的数据'); return; }
        const data = records.map(r => {
            const t = r.timings || {};
            return {
                mode: r.mode, timestamp: r.timestamp, modelId: r.modelId,
                promptTokens: r.promptTokens != null ? r.promptTokens : (t.prompt_n || ''),
                maxTokens: r.maxTokens != null ? r.maxTokens : (t.predicted_n || ''),
                prefillSpeed: t.prompt_per_second, decodeSpeed: t.predicted_per_second,
                draftTokens: t.draft_n, draftAccepted: t.draft_n_accepted,
                cpu: r.cpu || '', ram: r.ram || '', devices: r.devices || [],
                cmd: r.cmd || '', llamaBinPath: r.llamaBinPath || ''
            };
        });
        let content = '';
        if (format === 'csv') {
            const rows = data.map(r => [r.timestamp, r.modelId, r.promptTokens, benchFmtNum(r.prefillSpeed), r.maxTokens, benchFmtNum(r.decodeSpeed),
                r.cpu ? (r.ram ? r.cpu + ' / ' + r.ram + 'GB' : r.cpu) : (r.ram ? r.ram + 'GB' : ''),
                Array.isArray(r.devices) ? r.devices.join(' | ') : r.devices, r.cmd, r.llamaBinPath
            ].map(v => '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"').join(','));
            content = '时间,模型,提示词长度,预填充速度(token/s),输出长度,输出速度(token/s),CPU/RAM,GPU,CMD,路径\n' + rows.join('\n');
        } else if (format === 'json') {
            content = JSON.stringify(data, null, 2);
        } else {
            content = '# Benchmark 测试结果\n\n| 时间 | 模型 | 提示词长度 | 预填充速度 | 输出长度 | 输出速度 |\n|------|------|-----------|-----------|---------|---------|\n'
                + data.map(r => '| ' + [r.timestamp, r.modelId, r.promptTokens, benchFmtNum(r.prefillSpeed), r.maxTokens, benchFmtNum(r.decodeSpeed)].join(' | ') + ' |').join('\n');
        }
        const mime = { csv: 'text/csv', json: 'application/json', md: 'text/markdown' }[format];
        benchDownload(new Blob([content], { type: mime + ';charset=utf-8;' }), 'benchmark_' + (this.selId || 'server') + '.' + format);
        toast('已导出 ' + data.length + ' 条记录', 'success');
    },

    /* ================= llama-bench ================= */
    loadLlamaPaths() {
        const sel = $('#benchLlamaBin');
        const nid = this.nodeId();
        const seq = this.reqSeq;
        api('/api/llamacpp/list' + (nid ? '?nodeId=' + encodeURIComponent(nid) : '')).then(d => {
            if (seq !== this.reqSeq) return;
            this.llamaPaths = (d && d.success && d.data && Array.isArray(d.data.items)) ? d.data.items : [];
            if (!this.llamaPaths.length) {
                sel.innerHTML = '<option value="">未配置 Llama.cpp 路径</option>';
                sel.disabled = true;
                return;
            }
            sel.disabled = false;
            sel.innerHTML = this.llamaPaths.map(it => {
                const p = String(it.path || '').trim();
                if (!p) return '';
                const name = String(it.name || '').trim();
                return '<option value="' + esc(p) + '" title="' + esc([name, p, it.description].filter(Boolean).join('\n')) + '">' + esc(name ? name + ' (' + p + ')' : p) + '</option>';
            }).join('');
        }).catch(() => { if (seq !== this.reqSeq) return; this.llamaPaths = []; sel.innerHTML = '<option value="">加载失败</option>'; sel.disabled = true; });
    },

    async runBench() {
        if (this.bRunning) { this.cancelBench(); return; }
        const modelId = this.selId;
        if (!modelId) { $('#benchStatus').textContent = '请选择模型'; return; }
        let cmd = this.buildCmd();
        const devArg = this.buildDeviceArg();
        if (devArg) cmd = (cmd ? cmd + ' ' : '') + devArg;
        let llamaBinPath = ($('#benchLlamaBin').value || '').trim();
        if (!llamaBinPath) {
            const m = this.findModel(modelId, this.selNode);
            if (m && m.llamaBinPath) llamaBinPath = m.llamaBinPath;
        }
        this.bRunning = true;
        this.bAbort = new AbortController();
        const btn = $('#benchRunBtn');
        btn.innerHTML = '<i class="fas fa-stop"></i> 取消';
        btn.classList.add('danger-soft');
        $('#benchStatus').textContent = '运行中（可能需要数分钟）…';
        const payload = { modelId, cmd };
        if (llamaBinPath) payload.llamaBinPath = llamaBinPath;
        const nid = this.nodeId(); if (nid) payload.nodeId = nid;
        try {
            const d = await fetch('/api/models/benchmark', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload), signal: this.bAbort.signal
            }).then(r => r.json());
            if (d && d.success) {
                $('#benchStatus').textContent = '完成';
                const savedName = d.data && d.data.savedPath ? d.data.savedPath.replace(/\\/g, '/').split('/').pop() : '';
                this.loadHistory(savedName);
            } else {
                $('#benchStatus').textContent = (d && d.error) || '失败';
            }
        } catch (e) {
            $('#benchStatus').textContent = (e && e.name === 'AbortError') ? '已取消' : (e.message || '请求失败');
        } finally {
            this.bRunning = false;
            this.bAbort = null;
            btn.innerHTML = '<i class="fas fa-play"></i> 运行';
            btn.classList.remove('danger-soft');
        }
    },

    cancelBench() {
        if (!this.bRunning) return;
        if (this.bAbort) this.bAbort.abort();
        $('#benchStatus').textContent = '取消中…';
    },

    loadHistory(preferFile) {
        const id = this.selId;
        if (!id) { this.historyFiles = []; this.selFile = ''; this.fileData = null; this.renderHistory(); this.showOutput(); return; }
        const seq = this.reqSeq;
        api('/api/models/benchmark/list?modelId=' + encodeURIComponent(id) + this.nodeQuery())
            .then(d => {
                if (seq !== this.reqSeq) return;
                this.historyFiles = (d && d.success && d.data && Array.isArray(d.data.files)) ? d.data.files : [];
                if (preferFile && this.historyFiles.some(f => f.name === preferFile)) this.selFile = preferFile;
                else if (this.historyFiles.length && !this.historyFiles.some(f => f.name === this.selFile)) this.selFile = this.historyFiles[0].name;
                else if (!this.historyFiles.length) this.selFile = '';
                this.renderHistory();
                if (this.selFile) this.loadFile(this.selFile);
                else { this.fileData = null; this.showOutput(); }
            })
            .catch(e => { if (seq !== this.reqSeq) return; this.historyFiles = []; this.renderHistory(); $('#benchStatus').textContent = e.message || '记录加载失败'; });
    },

    renderHistory() {
        const box = $('#benchHistory');
        if (!this.historyFiles.length) {
            box.innerHTML = '<div class="bench-model-empty">' + (this.selId ? '暂无记录' : '请选择模型') + '</div>';
            return;
        }
        box.innerHTML = this.historyFiles.map(f => {
            const m = (f.name || '').match(/(\d{8}_\d{6})/);
            const ts = m ? benchFmtTs(m[1]) : (f.modified || f.name);
            return '<div class="bh-item' + (f.name === this.selFile ? ' active' : '') + '" data-name="' + esc(f.name) + '">' +
                '<div class="bh-info"><div class="bh-ts">' + esc(ts) + '</div><div class="bh-size">' + fmtSize(f.size) + '</div></div>' +
                '<button class="bh-del" title="删除"><i class="fas fa-xmark"></i></button></div>';
        }).join('');
        $$('#benchHistory .bh-item').forEach(el => {
            const name = el.dataset.name;
            el.addEventListener('click', () => { this.selFile = name; this.renderHistory(); this.loadFile(name); });
            el.querySelector('.bh-del').addEventListener('click', e => { e.stopPropagation(); this.deleteFile(name); });
        });
    },

    loadFile(fileName) {
        $('#benchStatus').textContent = '加载中…';
        api('/api/models/benchmark/get?fileName=' + encodeURIComponent(fileName) + this.nodeQuery())
            .then(d => {
                if (d && d.success && d.data) { this.fileData = d.data; $('#benchStatus').textContent = ''; }
                else { this.fileData = null; $('#benchStatus').textContent = (d && d.error) || '加载失败'; }
                this.showOutput();
            })
            .catch(e => { this.fileData = null; this.showOutput(); $('#benchStatus').textContent = e.message || '加载失败'; });
    },

    showOutput() {
        const d = this.fileData;
        $('#benchOutput').textContent = d && d.rawOutput ? d.rawOutput : (this.selFile ? '(无输出)' : '选择左侧模型查看历史记录');
        const has = !!(d && d.rawOutput);
        $('#benchCopyBtn').disabled = !has;
        $('#benchExportBtn').disabled = !has;
    },

    deleteFile(fileName) {
        if (!confirm('确定删除该测试记录吗？')) return;
        post('/api/models/benchmark/delete?fileName=' + encodeURIComponent(fileName) + this.nodeQuery()).then(d => {
            if (d && d.success) {
                if (fileName === this.selFile) { this.selFile = ''; this.fileData = null; }
                this.loadHistory();
            } else $('#benchStatus').textContent = (d && d.error) || '删除失败';
        }).catch(e => $('#benchStatus').textContent = e.message || '删除失败');
    },

    copyOutput() {
        const text = $('#benchOutput').textContent;
        if (!text) return;
        benchCopyText(text).then(ok => toast(ok ? '已复制到剪贴板' : '复制失败', ok ? 'success' : 'error'));
    },

    exportOutput() {
        const text = $('#benchOutput').textContent;
        if (!text) { toast('没有可导出的数据'); return; }
        const fn = this.selFile || 'benchmark';
        benchDownload(new Blob([text], { type: 'text/plain;charset=utf-8' }), fn.endsWith('.txt') ? fn : fn + '.txt');
    },

    /* ================= 参数设置弹层 ================= */
    openParams() {
        const done = () => {
            this.renderParamForm();
            this.loadDevices();
            UI.openSheet('#benchParamsSheet');
        };
        if (this.paramLoaded) { done(); return; }
        api('/api/models/param/benchmark/list').then(d => {
            this.params = (d && d.success && Array.isArray(d.params)) ? d.params.filter(p => (p.fullName || '') !== '--main-gpu') : [];
            this.paramLoaded = true;
            this.resetParamsState();
            done();
        }).catch(() => toast('参数加载失败', 'error'));
    },

    fieldName(p) {
        let n = (p.fullName || '').trim() || (p.abbreviation || '').trim();
        if (n) return n.replace(/^--?/, '');
        return 'unnamed_' + String(p.name || '').replace(/\W+/g, '_') + '_' + (p.sort || 0);
    },

    paramGroups() {
        const g = {};
        this.params.forEach(p => {
            const key = p.group || 'other';
            if (!g[key]) g[key] = { key, order: p.groupOrder || 99, collapsed: p.groupCollapsed !== false, items: [] };
            g[key].items.push(p);
        });
        Object.values(g).forEach(x => x.items.sort((a, b) => (a.sort || 0) - (b.sort || 0)));
        return Object.values(g).sort((a, b) => a.order - b.order);
    },

    renderParamForm() {
        const box = $('#bpParamForm');
        box.innerHTML = this.paramGroups().map(g => {
            const items = g.items.map(p => {
                const fn = this.fieldName(p);
                const label = I18n.t(p.name, (p.fullName || p.abbreviation || p.name));
                const flag = (p.fullName || p.abbreviation || '').trim();
                return '<div class="param-item disabled" data-fn="' + esc(fn) + '">' +
                    '<input type="checkbox" class="p-check">' +
                    '<div class="p-label">' + esc(label) + '<span class="p-flag">' + esc(flag) + '</span></div>' +
                    '<div class="p-input">' + this.paramInputHtml(p) + '</div></div>';
            }).join('');
            return '<details class="param-group"' + (g.collapsed ? '' : ' open') + '><summary><span>' +
                esc(I18n.t(g.key, g.key)) + '</span><span class="cnt"></span></summary><div class="param-items">' + items + '</div></details>';
        }).join('');
        $$('#bpParamForm .param-item').forEach(row => {
            const fn = row.dataset.fn;
            const chk = row.querySelector('.p-check');
            const ctl = row.querySelector('.p-input input, .p-input select');
            chk.addEventListener('change', () => {
                this.enabled[fn] = chk.checked;
                row.classList.toggle('disabled', !chk.checked);
                this.refreshParamCount();
            });
            if (ctl) {
                ctl.addEventListener(ctl.tagName === 'SELECT' ? 'change' : 'input', () => { this.values[fn] = ctl.value; });
            }
        });
        this.syncParamForm();
        this.refreshParamCount();
    },

    paramInputHtml(p) {
        const type = String(p.type || 'STRING').toUpperCase();
        const values = Array.isArray(p.values) ? p.values : [];
        if (type === 'LOGIC' || type === 'BOOLEAN') return '<span class="muted" style="font-size:12px">开关</span>';
        if (values.length) {
            return '<select>' + values.map(v => {
                const val = (v && typeof v === 'object') ? String(v.value != null ? v.value : '') : String(v);
                const lab = (v && typeof v === 'object' && v.label) ? I18n.t(v.label, val) : val;
                return '<option value="' + esc(val) + '">' + esc(lab) + '</option>';
            }).join('') + '</select>';
        }
        const dv = p.defaultValue != null ? String(p.defaultValue) : '';
        const inputType = (type === 'INTEGER' || type === 'FLOAT') ? 'number' : 'text';
        return '<input type="' + inputType + '" placeholder="' + esc(dv) + '" value="' + esc(dv) + '">';
    },

    resetParamsState() {
        this.params.forEach(p => {
            const fn = this.fieldName(p);
            const type = String(p.type || '').toUpperCase();
            this.enabled[fn] = p.defaultEnabled !== undefined ? !!p.defaultEnabled : (p.defaultValue != null && p.defaultValue !== '');
            let v = p.defaultValue != null ? String(p.defaultValue) : '';
            if (!v && Array.isArray(p.values) && p.values.length) {
                const first = p.values[0];
                v = (first && typeof first === 'object') ? String(first.value != null ? first.value : '') : String(first);
            }
            if ((type === 'LOGIC' || type === 'BOOLEAN') && !v) v = '0';
            this.values[fn] = v;
        });
    },

    resetParams() { this.resetParamsState(); this.syncParamForm(); },

    syncParamForm() {
        $$('#bpParamForm .param-item').forEach(row => {
            const fn = row.dataset.fn;
            const chk = row.querySelector('.p-check');
            const ctl = row.querySelector('.p-input input, .p-input select');
            chk.checked = !!this.enabled[fn];
            row.classList.toggle('disabled', !chk.checked);
            if (ctl) ctl.value = this.values[fn] != null ? this.values[fn] : '';
        });
    },

    refreshParamCount() {
        const total = this.params.filter(p => (p.fullName || '').trim()).length;
        const enabled = this.params.filter(p => (p.fullName || '').trim() && this.enabled[this.fieldName(p)]).length;
        $('#bpCount').textContent = '已启用 ' + enabled + ' / ' + total + ' 个参数';
        $('#benchParamsBtn').innerHTML = '<i class="fas fa-sliders"></i> 参数设置' + (enabled ? ' (' + enabled + ')' : '');
    },

    loadDevices() {
        const list = $('#bpDevices');
        if (!$('#benchParamsSheet').classList.contains('open') && !this._devicesEverLoaded) { /* 首次打开弹层时才加载 */ }
        const llamaBinPath = ($('#benchLlamaBin').value || '').trim();
        if (!llamaBinPath) { list.innerHTML = '<span class="muted">请先选择 Llama.cpp 版本</span>'; this.devices = []; this.renderMainGpu(); return; }
        list.innerHTML = '<span class="muted">加载中…</span>';
        const nid = this.nodeId();
        api('/api/model/device/list?llamaBinPath=' + encodeURIComponent(llamaBinPath) + (nid ? '&nodeId=' + encodeURIComponent(nid) : ''))
            .then(d => {
                this.devices = (d && d.success && d.data && Array.isArray(d.data.devices)) ? d.data.devices : [];
                if (!this.devices.length) { list.innerHTML = '<span class="muted">未发现可用设备</span>'; this.renderMainGpu(); return; }
                list.innerHTML = this.devices.map((dev, i) =>
                    '<label class="check-line"><input type="checkbox" checked data-idx="' + i + '"> ' + esc(benchDeviceLabel(dev)) + '</label>').join('');
                list.querySelectorAll('input[type=checkbox]').forEach(c => c.addEventListener('change', () => this.renderMainGpu()));
                this.renderMainGpu();
            })
            .catch(() => { list.innerHTML = '<span class="muted">获取设备列表失败</span>'; this.devices = []; this.renderMainGpu(); });
    },

    renderMainGpu() {
        const sel = $('#bpMainGpu');
        const prev = parseInt(sel.value, 10);
        const checked = $$('#bpDevices input[type=checkbox]:checked').map(c => Number(c.dataset.idx));
        sel.innerHTML = '<option value="-1">默认</option>' + checked.map((devIdx, pos) =>
            '<option value="' + pos + '">' + esc(benchDeviceLabel(this.devices[devIdx])) + '</option>').join('');
        sel.value = (Number.isFinite(prev) && prev >= 0 && prev < checked.length) ? String(prev) : '-1';
    },

    buildCmd() {
        const parts = [];
        this.params.slice().sort((a, b) => (a.sort || 0) - (b.sort || 0)).forEach(p => {
            const fn = this.fieldName(p);
            if (!this.enabled[fn]) return;
            const flag = (p.fullName || p.abbreviation || '').trim();
            if (!flag) return;
            const type = String(p.type || 'STRING').toUpperCase();
            const v = String(this.values[fn] != null ? this.values[fn] : '').trim();
            if (type === 'LOGIC' || type === 'BOOLEAN') {
                if (/^(1|true|on|yes)$/i.test(v)) parts.push(flag);
                return;
            }
            if (v) parts.push(flag + ' ' + v);
        });
        return parts.join(' ');
    },

    buildDeviceArg() {
        const checks = $$('#bpDevices input[type=checkbox]');
        if (!checks.length || !this.devices.length) {
            const mg = parseInt($('#bpMainGpu').value, 10);
            return (Number.isFinite(mg) && mg >= 0) ? '--main-gpu ' + mg : '';
        }
        const enabled = checks.filter(c => c.checked).map(c => benchDeviceArg(this.devices[Number(c.dataset.idx)]));
        const parts = [];
        if (enabled.length < checks.length) parts.push('-dev ' + enabled.join('/'));
        const mg = parseInt($('#bpMainGpu').value, 10);
        if (Number.isFinite(mg) && mg >= 0) parts.push('--main-gpu ' + mg);
        return parts.join(' ');
    }
};

/* ================= 模块私有工具函数 ================= */

function benchFmtNum(v) {
    const n = Number(v);
    if (!Number.isFinite(n)) return '-';
    if (n >= 1000) return n.toFixed(0);
    return n.toFixed(1).replace(/\.0$/, '');
}

function benchFmtTs(v) {
    if (v == null) return '-';
    const raw = String(v).trim();
    if (/^\d{8}_\d{6}$/.test(raw)) {
        return raw.slice(0, 4) + '-' + raw.slice(4, 6) + '-' + raw.slice(6, 8) + ' ' + raw.slice(9, 11) + ':' + raw.slice(11, 13);
    }
    const p = Date.parse(raw);
    if (!Number.isNaN(p)) {
        const d = new Date(p);
        const pd = n => String(n).padStart(2, '0');
        return d.getFullYear() + '-' + pd(d.getMonth() + 1) + '-' + pd(d.getDate()) + ' ' + pd(d.getHours()) + ':' + pd(d.getMinutes());
    }
    return raw;
}

function benchDownload(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
}

async function benchCopyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
        try { await navigator.clipboard.writeText(text); return true; } catch (e) { /* 降级 */ }
    }
    try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        const ok = document.execCommand('copy');
        document.body.removeChild(ta);
        return ok;
    } catch (e) { return false; }
}

function benchDeviceLabel(d) {
    if (d == null) return '';
    return (typeof d === 'string' ? d : String(d.name || d.brand || d.id || d)).trim();
}

function benchDeviceArg(d) {
    return benchDeviceLabel(d).split(':')[0].trim();
}

/* 远程节点配色：与旧版 getNodeColor 同一算法，节点色相保持稳定且跨页面一致 */
function benchNodeHue(nodeId) {
    let hash = 0;
    for (let i = 0; i < nodeId.length; i++) {
        hash = ((hash << 5) - hash) + nodeId.charCodeAt(i);
        hash |= 0;
    }
    return ((Math.abs(hash) * 137.508) % 360).toFixed(1);
}
