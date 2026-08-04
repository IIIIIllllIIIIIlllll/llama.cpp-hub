/* ================= usage-report.js — 用量报表（Token 概览 / 请求记录） ================= */
'use strict';

const UsageReport = {
    PAGE_SIZE: 30,

    tokenSummary: [],
    requestLogs: [],
    dailyTokens: [],
    logTotal: 0,
    page: 1,
    selectedModelId: '',

    // 图表 hover 状态
    tokenBars: [],
    dailyBars: [],
    tokenHoverIdx: -1,
    dailyHoverIdx: -1,

    _inited: false,

    init() {
        if (this._inited) return;
        this._inited = true;

        // TAB 切换
        $$('#usageTabs .chip').forEach(c => c.addEventListener('click', () => this.switchTab(c.dataset.utab)));

        // 请求记录：筛选 / 分页
        $('#urModelFilter').addEventListener('change', () => {
            const v = $('#urModelFilter').value;
            if (this.selectedModelId !== v) {
                this.selectedModelId = v;
                this.page = 1;
                this.fetchRequestLogs();
            }
        });
        $('#urPrev').addEventListener('click', () => { if (this.page > 1) { this.page--; this.fetchRequestLogs(); } });
        $('#urNext').addEventListener('click', () => { this.page++; this.fetchRequestLogs(); });

        // 每日图表：年月切换
        $('#urYearSel').addEventListener('change', () => this.fetchDailyTokens());
        $('#urMonthSel').addEventListener('change', () => this.fetchDailyTokens());

        // 容器尺寸变化时重绘图表
        const observer = new ResizeObserver(() => {
            if (App.currentPage === 'usage' && $('[data-upane="summary"]').classList.contains('active')) {
                this.renderTokenChart();
                this.renderDailyChart();
            }
        });
        observer.observe($('#urTokenChartWrap'));
        observer.observe($('#urDailyChartWrap'));
    },

    async load() {
        await this.initMonthSelectors();
        await Promise.all([this.fetchTokenSummary(), this.fetchRequestLogs(), this.fetchDailyTokens()]);
        // 隐藏面板中的图表在显示后需要重绘一次（display:none 时量不到尺寸）
        if ($('[data-upane="summary"]').classList.contains('active')) {
            setTimeout(() => { this.renderTokenChart(); this.renderDailyChart(); }, 50);
        }
    },

    switchTab(name) {
        $$('#usageTabs .chip').forEach(c => c.classList.toggle('active', c.dataset.utab === name));
        $$('#page-usage .usage-pane').forEach(p => p.classList.toggle('active', p.dataset.upane === name));
        if (name === 'summary') {
            setTimeout(() => { this.renderTokenChart(); this.renderDailyChart(); }, 50);
        }
    },

    /* ================= 数据获取 ================= */

    async fetchTokenSummary() {
        try {
            const r = await api('/api/report/token-summary');
            this.tokenSummary = (r && r.success) ? (r.data || []) : [];
        } catch (e) {
            this.tokenSummary = [];
        }
        this.renderTokenSummary();
        this.buildModelFilter();
    },

    async fetchRequestLogs() {
        let url = '/api/report/request-logs?page=' + this.page + '&pageSize=' + this.PAGE_SIZE;
        if (this.selectedModelId) url += '&modelId=' + encodeURIComponent(this.selectedModelId);
        try {
            const r = await api(url);
            if (r && r.success) {
                this.requestLogs = (r.data && r.data.records) || [];
                this.logTotal = r.data ? (r.data.total || 0) : 0;
            } else {
                this.requestLogs = [];
                this.logTotal = 0;
            }
        } catch (e) {
            this.requestLogs = [];
            this.logTotal = 0;
        }
        this.renderRequestLogs();
    },

    async fetchDailyTokens() {
        const year = $('#urYearSel').value;
        const month = $('#urMonthSel').value;
        let url = '/api/report/daily-tokens?year=' + year + '&month=' + month;
        if (this.selectedModelId) url += '&modelId=' + encodeURIComponent(this.selectedModelId);
        try {
            const r = await api(url);
            this.dailyTokens = (r && r.success) ? (r.data || []) : [];
        } catch (e) {
            this.dailyTokens = [];
        }
        this.updateDailyTitle(year, month);
        this.renderDailyChart();
    },

    async deleteModelRecords(modelId) {
        try {
            const r = await post('/api/report/records', { modelId: modelId });
            if (r && r.success) {
                if (this.selectedModelId === modelId) {
                    this.selectedModelId = '';
                    this.page = 1;
                    this.fetchRequestLogs();
                }
                toast('已删除 ' + ((r.data && r.data.deletedCount) || 0) + ' 条记录', 'success');
            } else {
                toast('删除记录失败：' + (r ? (r.error || r.message || '') : ''), 'error');
            }
        } catch (e) {
            toast('删除记录失败：' + e.message, 'error');
        }
        await this.fetchTokenSummary();
        this.fetchDailyTokens();
    },

    async initMonthSelectors() {
        const yearSel = $('#urYearSel');
        const monthSel = $('#urMonthSel');

        monthSel.innerHTML = '';
        for (let m = 1; m <= 12; m++) {
            const opt = document.createElement('option');
            opt.value = m;
            opt.textContent = m + '月';
            monthSel.appendChild(opt);
        }

        try {
            const r = await api('/api/report/available-years');
            yearSel.innerHTML = '';
            const years = (r && r.success && Array.isArray(r.data) && r.data.length) ? r.data : [new Date().getFullYear()];
            for (const y of years) {
                const opt = document.createElement('option');
                opt.value = y;
                opt.textContent = y + '年';
                yearSel.appendChild(opt);
            }
        } catch (e) {
            yearSel.innerHTML = '';
            const opt = document.createElement('option');
            opt.value = new Date().getFullYear();
            opt.textContent = new Date().getFullYear() + '年';
            yearSel.appendChild(opt);
        }

        const now = new Date();
        yearSel.value = now.getFullYear();
        monthSel.value = now.getMonth() + 1;
    },

    updateDailyTitle(year, month) {
        const base = year + '年' + month + '月用量';
        $('#urDailyTitle').textContent = this.selectedModelId ? '[' + this.selectedModelId + '] ' + base : base;
    },

    buildModelFilter() {
        const sel = $('#urModelFilter');
        const models = Array.from(new Set(this.tokenSummary.map(r => r.modelId).filter(Boolean))).sort();
        const cur = this.selectedModelId;
        sel.innerHTML = '<option value="">全部模型</option>' +
            models.map(m => '<option value="' + esc(m) + '"' + (m === cur ? ' selected' : '') + '>' + esc(m) + '</option>').join('');
    },

    /* ================= Token 概览渲染 ================= */

    renderTokenSummary() {
        const stats = $('#urStats');
        const list = $('#urModelList');

        let totalPrompt = 0, totalPredicted = 0, totalCache = 0, totalDraft = 0, totalDraftAcc = 0;
        const sorted = this.tokenSummary.slice().sort((a, b) => (b.totalTokens || 0) - (a.totalTokens || 0));
        sorted.forEach(m => {
            totalPrompt += m.totalPromptTokens || 0;
            totalPredicted += m.totalPredictedTokens || 0;
            totalCache += m.totalCacheTokens || 0;
            totalDraft += m.totalDraftTokens || 0;
            totalDraftAcc += m.totalDraftAccepted || 0;
        });

        stats.innerHTML =
            '<div class="ur-stat"><div class="v">' + sorted.length + '</div><div class="k">有记录的模型</div></div>' +
            '<div class="ur-stat"><div class="v">' + totalPrompt.toLocaleString() + '</div><div class="k">总输入 Token</div></div>' +
            '<div class="ur-stat"><div class="v">' + totalPredicted.toLocaleString() + '</div><div class="k">总输出 Token</div></div>' +
            '<div class="ur-stat"><div class="v">' + totalCache.toLocaleString() + '</div><div class="k">总缓存命中 Token</div></div>' +
            (totalDraft > 0 ? '<div class="ur-stat"><div class="v">' + totalDraftAcc.toLocaleString() + '/' + totalDraft.toLocaleString() + '</div><div class="k">总投机解码</div></div>' : '');

        if (!sorted.length) {
            list.innerHTML = '<div class="ur-models-empty">暂无数据</div>';
        } else {
            list.innerHTML = sorted.map((m, idx) => {
                const id = m.modelId || '';
                const isActive = this.selectedModelId === id;
                let draftHtml = '';
                if (m.totalDraftTokens > 0) {
                    const pct = ((m.totalDraftAccepted || 0) / m.totalDraftTokens * 100).toFixed(1);
                    draftHtml = '<span>投机 ' + (m.totalDraftAccepted || 0) + '/' + m.totalDraftTokens + ' (' + pct + '%)</span>';
                }
                return '<div class="ur-model-item' + (isActive ? ' active' : '') + '" data-model-id="' + esc(id) + '" title="' + (isActive ? '点击取消选择' : '点击选择此模型') + '">' +
                    '<div class="um-name"><span class="um-rank">' + (idx + 1) + '</span>' + esc(id) +
                    (isActive ? ' <i class="fas fa-check-circle" style="color:var(--primary);margin-left:4px;font-size:12px"></i>' : '') + '</div>' +
                    '<div class="um-tokens">' +
                    '<span>输入 ' + (m.totalPromptTokens || 0).toLocaleString() + '</span>' +
                    '<span>输出 ' + (m.totalPredictedTokens || 0).toLocaleString() + '</span>' +
                    '<span>缓存 ' + (m.totalCacheTokens || 0).toLocaleString() + '</span>' +
                    draftHtml +
                    '</div>' +
                    '<button class="um-del" data-model-id="' + esc(id) + '" title="删除该模型的全部记录"><i class="fas fa-trash-can"></i></button>' +
                    '</div>';
            }).join('');
        }

        // 点击选中/取消选中模型 → 联动每日图表与请求记录筛选
        $$('#urModelList .ur-model-item').forEach(item => item.addEventListener('click', () => {
            const id = item.dataset.modelId || '';
            this.selectedModelId = (this.selectedModelId === id) ? '' : id;
            this.page = 1;
            this.buildModelFilter();
            this.renderTokenSummary();
            this.fetchDailyTokens();
            this.fetchRequestLogs();
        }));

        // 删除按钮
        $$('#urModelList .um-del').forEach(btn => btn.addEventListener('click', e => {
            e.stopPropagation();
            const id = btn.dataset.modelId || '';
            if (!id) return;
            if (!confirm('确认删除模型「' + id + '」的全部记录吗？')) return;
            this.deleteModelRecords(id);
        }));

        this.renderTokenChart();
    },

    /* ================= 请求记录渲染 ================= */

    renderRequestLogs() {
        const body = $('#urLogBody');
        const emptyEl = $('#urLogEmpty');
        const totalPages = Math.ceil(this.logTotal / this.PAGE_SIZE) || 1;
        if (this.page > totalPages) this.page = totalPages;

        $('#urPageInfo').textContent = '第 ' + this.page + ' / ' + totalPages + ' 页';
        $('#urPrev').disabled = this.page <= 1;
        $('#urNext').disabled = this.page >= totalPages;

        if (!this.requestLogs.length) {
            body.innerHTML = '';
            emptyEl.style.display = '';
            return;
        }
        emptyEl.style.display = 'none';
        body.innerHTML = this.requestLogs.map(r => {
            const draftDisplay = r.draftTokens > 0 ? (r.draftAccepted || 0) + '/' + r.draftTokens : '-';
            return '<tr>' +
                '<td>' + this.formatTime(r.startTime) + '</td>' +
                '<td>' + esc(r.modelId || '') + '</td>' +
                '<td>' + esc(r.endpoint || '') + '</td>' +
                '<td class="num">' + (r.cacheTokens || 0).toLocaleString() + '</td>' +
                '<td class="num">' + (r.promptTokens || 0).toLocaleString() + '</td>' +
                '<td class="num">' + (r.predictedTokens || 0).toLocaleString() + '</td>' +
                '<td class="num"><strong>' + (r.totalTokens || 0).toLocaleString() + '</strong></td>' +
                '<td class="num">' + (r.elapsedMs || 0).toLocaleString() + '</td>' +
                '<td class="num">' + this.formatNum(r.promptPerSecond) + '</td>' +
                '<td class="num">' + this.formatNum(r.predictedPerSecond) + '</td>' +
                '<td class="num">' + esc(draftDisplay) + '</td>' +
                '</tr>';
        }).join('');
    },

    /* ================= 图表（canvas 手绘，叠加柱状 + hover tooltip） ================= */

    chartColors(wrapEl) {
        const style = getComputedStyle(wrapEl);
        return {
            tc: style.getPropertyValue('--text-2').trim() || '#999',
            bc: style.getPropertyValue('--border').trim() || '#333',
            colors: ['rgba(99,102,241,0.8)', 'rgba(16,185,129,0.8)']
        };
    },

    setupCanvas(canvasEl, wrapEl) {
        const ctx = canvasEl.getContext('2d');
        const rect = wrapEl.getBoundingClientRect();
        const dpr = window.devicePixelRatio || 1;
        const w = rect.width, h = rect.height;
        if (w <= 0 || h <= 0) return null;
        canvasEl.width = w * dpr;
        canvasEl.height = h * dpr;
        canvasEl.style.width = w + 'px';
        canvasEl.style.height = h + 'px';
        ctx.scale(dpr, dpr);
        return { ctx, w, h };
    },

    renderTokenChart() {
        const canvasEl = $('#urTokenChart');
        const emptyEl = $('#urTokenChartEmpty');
        const wrapEl = $('#urTokenChartWrap');
        const setup = this.setupCanvas(canvasEl, wrapEl);
        if (!setup) return;
        const { ctx, w, h } = setup;

        if (!this.tokenSummary.length) {
            ctx.clearRect(0, 0, w, h);
            emptyEl.style.display = 'flex';
            this.tokenBars = [];
            canvasEl.onmousemove = null;
            canvasEl.onmouseleave = null;
            return;
        }
        emptyEl.style.display = 'none';

        const data = this.tokenSummary.slice().sort((a, b) => (b.totalTokens || 0) - (a.totalTokens || 0));
        const count = data.length;
        const pad = { top: 14, bottom: 30, left: 64, right: 14 };
        const cw = w - pad.left - pad.right;
        const ch = h - pad.top - pad.bottom;
        const barW = Math.min(cw / count * 0.45, 28);
        const gw = barW;

        let maxV = 0;
        data.forEach(d => maxV = Math.max(maxV, d.totalPromptTokens || 0, d.totalPredictedTokens || 0));
        maxV = Math.ceil(Math.max(maxV * 1.15, 100));

        const { tc, bc, colors } = this.chartColors(wrapEl);

        this.tokenBars = [];
        for (let i = 0; i < count; i++) {
            const d = data[i];
            const pf = d.totalPromptTokens || 0;
            const dg = d.totalPredictedTokens || 0;
            const x = pad.left + (cw / count) * i + (cw / count - gw) / 2;
            const pfH = (pf / maxV) * ch;
            const dgH = (dg / maxV) * ch;
            this.tokenBars.push({
                x: x, y: pad.top + ch - Math.max(pfH, dgH), w: gw, h: Math.max(pfH, dgH),
                barW: barW, gw: gw,
                label: d.modelId || ('#' + (i + 1)),
                promptVal: pf, predictedVal: dg
            });
        }

        const self = this;
        const labelFn = i => i + 1;
        const valFn = (b, which) => which === 0 ? b.promptVal : b.predictedVal;
        const redraw = () => self.drawBarsChart(ctx, w, h, pad, cw, ch, self.tokenBars, maxV, colors, tc, bc, self.tokenHoverIdx, labelFn, valFn);
        redraw();

        canvasEl.onmousemove = e => {
            const r = canvasEl.getBoundingClientRect();
            const mx = e.clientX - r.left;
            const my = e.clientY - r.top;
            let found = -1;
            const slotW = cw / count;
            for (let i = 0; i < self.tokenBars.length; i++) {
                const slotX = pad.left + slotW * i;
                if (mx >= slotX && mx <= slotX + slotW && my >= pad.top && my <= pad.top + ch) { found = i; break; }
            }
            if (found !== self.tokenHoverIdx) { self.tokenHoverIdx = found; redraw(); }
        };
        canvasEl.onmouseleave = () => {
            if (self.tokenHoverIdx !== -1) { self.tokenHoverIdx = -1; redraw(); }
        };
    },

    renderDailyChart() {
        const canvasEl = $('#urDailyChart');
        const emptyEl = $('#urDailyChartEmpty');
        const wrapEl = $('#urDailyChartWrap');
        const setup = this.setupCanvas(canvasEl, wrapEl);
        if (!setup) return;
        const { ctx, w, h } = setup;

        const hasData = this.dailyTokens.some(d => d.promptTokens > 0 || d.predictedTokens > 0);
        if (!this.dailyTokens.length || !hasData) {
            ctx.clearRect(0, 0, w, h);
            emptyEl.style.display = 'flex';
            this.dailyBars = [];
            canvasEl.onmousemove = null;
            canvasEl.onmouseleave = null;
            return;
        }
        emptyEl.style.display = 'none';

        const data = this.dailyTokens;
        const count = data.length;
        const pad = { top: 14, bottom: 30, left: 64, right: 14 };
        const cw = w - pad.left - pad.right;
        const ch = h - pad.top - pad.bottom;
        const barW = Math.min(cw / count * 0.25, 18);
        const gw = barW;

        let maxV = 0;
        data.forEach(d => maxV = Math.max(maxV, d.promptTokens || 0, d.predictedTokens || 0));
        maxV = Math.ceil(Math.max(maxV * 1.15, 100));

        const { tc, bc, colors } = this.chartColors(wrapEl);

        this.dailyBars = [];
        for (let i = 0; i < count; i++) {
            const d = data[i];
            const pf = d.promptTokens || 0;
            const dg = d.predictedTokens || 0;
            const x = pad.left + (cw / count) * i + (cw / count - gw) / 2;
            const pfH = (pf / maxV) * ch;
            const dgH = (dg / maxV) * ch;
            this.dailyBars.push({
                x: x, y: pad.top + ch - Math.max(pfH, dgH), w: gw, h: Math.max(pfH, dgH),
                barW: barW, gw: gw,
                label: d.date ? d.date.substring(5) : '',
                promptVal: pf, predictedVal: dg
            });
        }

        // X 轴标签只画 首/中/尾
        const midIdx = Math.floor(count / 2);
        const self = this;
        const labelFn = i => (i === 0 || i === midIdx || i === count - 1) ? self.dailyBars[i].label : '';
        const valFn = (b, which) => which === 0 ? b.promptVal : b.predictedVal;
        const redraw = () => self.drawBarsChart(ctx, w, h, pad, cw, ch, self.dailyBars, maxV, colors, tc, bc, self.dailyHoverIdx, labelFn, valFn);
        redraw();

        canvasEl.onmousemove = e => {
            const r = canvasEl.getBoundingClientRect();
            const mx = e.clientX - r.left;
            const my = e.clientY - r.top;
            let found = -1;
            const slotW = cw / count;
            for (let i = 0; i < self.dailyBars.length; i++) {
                const slotX = pad.left + slotW * i;
                if (mx >= slotX && mx <= slotX + slotW && my >= pad.top && my <= pad.top + ch) { found = i; break; }
            }
            if (found !== self.dailyHoverIdx) { self.dailyHoverIdx = found; redraw(); }
        };
        canvasEl.onmouseleave = () => {
            if (self.dailyHoverIdx !== -1) { self.dailyHoverIdx = -1; redraw(); }
        };
    },

    // 叠加柱状图：大值在后、小值在前，含坐标轴 / 图例 / hover tooltip
    drawBarsChart(ctx, w, h, pad, cw, ch, bars, maxV, colors, tc, bc, hoverIdx, labelFn, valFn) {
        const barW = bars[0] ? bars[0].barW : 0;
        const gw = bars[0] ? bars[0].gw : 0;
        const count = bars.length;

        ctx.clearRect(0, 0, w, h);

        // 坐标轴
        ctx.strokeStyle = bc; ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(pad.left, pad.top); ctx.lineTo(pad.left, pad.top + ch); ctx.lineTo(pad.left + cw, pad.top + ch); ctx.stroke();

        // Y 轴刻度
        ctx.fillStyle = tc; ctx.font = '10px sans-serif'; ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
        for (let i = 0; i <= 4; i++) {
            const val = Math.round((maxV / 4) * i);
            const y = pad.top + ch - (ch / 4) * i;
            ctx.fillText(val.toLocaleString(), pad.left - 6, y);
            ctx.globalAlpha = 0.3; ctx.strokeStyle = bc; ctx.lineWidth = 0.5;
            ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(pad.left + cw, y); ctx.stroke();
            ctx.globalAlpha = 1;
        }

        // 柱（叠加：大值画在后，小值画在前）
        for (let i = 0; i < count; i++) {
            const b = bars[i];
            const pf = valFn(b, 0);
            const dg = valFn(b, 1);
            const x = b.x;
            const pfH = (pf / maxV) * ch;
            const dgH = (dg / maxV) * ch;

            if (pf === 0 && dg === 0) {
                ctx.fillStyle = tc; ctx.font = 'bold 10px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
                ctx.fillText(labelFn(i), x + gw / 2, pad.top + ch + 4);
                continue;
            }

            if (i === hoverIdx) {
                ctx.fillStyle = 'rgba(99,102,241,0.15)';
                ctx.fillRect(x - 2, pad.top, gw + 4, ch);
            }

            if (pf >= dg) {
                ctx.fillStyle = colors[0];
                ctx.fillRect(x, pad.top + ch - pfH, barW, pfH);
                ctx.strokeStyle = colors[0].replace('0.8', '1');
                ctx.lineWidth = 1.5;
                ctx.strokeRect(x, pad.top + ch - pfH, barW, pfH);
                ctx.fillStyle = colors[1];
                ctx.fillRect(x, pad.top + ch - dgH, barW, dgH);
                ctx.strokeStyle = colors[1].replace('0.8', '1');
                ctx.lineWidth = 1.5;
                ctx.strokeRect(x, pad.top + ch - dgH, barW, dgH);
            } else {
                ctx.fillStyle = colors[1];
                ctx.fillRect(x, pad.top + ch - dgH, barW, dgH);
                ctx.strokeStyle = colors[1].replace('0.8', '1');
                ctx.lineWidth = 1.5;
                ctx.strokeRect(x, pad.top + ch - dgH, barW, dgH);
                ctx.fillStyle = colors[0];
                ctx.fillRect(x, pad.top + ch - pfH, barW, pfH);
                ctx.strokeStyle = colors[0].replace('0.8', '1');
                ctx.lineWidth = 1.5;
                ctx.strokeRect(x, pad.top + ch - pfH, barW, pfH);
            }

            ctx.fillStyle = tc; ctx.font = 'bold 10px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
            ctx.fillText(labelFn(i), x + gw / 2, pad.top + ch + 4);
        }

        // 图例
        ctx.font = '10px sans-serif'; ctx.textAlign = 'left'; ctx.textBaseline = 'top';
        ctx.fillStyle = colors[0]; ctx.fillRect(w - 92, 2, 9, 9);
        ctx.fillStyle = tc; ctx.fillText('输入', w - 79, 1);
        ctx.fillStyle = colors[1]; ctx.fillRect(w - 42, 2, 9, 9);
        ctx.fillStyle = tc; ctx.fillText('输出', w - 29, 1);

        // tooltip
        if (hoverIdx >= 0 && hoverIdx < count) {
            const b = bars[hoverIdx];
            const lines = [b.label, '输入: ' + b.promptVal.toLocaleString(), '输出: ' + b.predictedVal.toLocaleString()];
            ctx.font = '11px sans-serif';
            const lineH = 15;
            const padX = 8, padY = 5;
            let maxTW = 0;
            for (const l of lines) {
                const tw = ctx.measureText(l).width;
                if (tw > maxTW) maxTW = tw;
            }
            const tw = maxTW + padX * 2;
            const th = lineH * lines.length + padY * 2;
            let tx = b.x + b.barW + 10;
            let ty = b.y - th - 6;
            if (tx + tw > w - 4) tx = b.x - tw - 6;
            if (ty < 2) ty = b.y + 10;

            ctx.fillStyle = 'rgba(0,0,0,0.82)';
            ctx.beginPath();
            this.roundRect(ctx, tx, ty, tw, th, 4);
            ctx.fill();
            ctx.strokeStyle = 'rgba(255,255,255,0.15)';
            ctx.lineWidth = 1;
            ctx.beginPath();
            this.roundRect(ctx, tx, ty, tw, th, 4);
            ctx.stroke();

            ctx.fillStyle = '#fff';
            ctx.font = 'bold 11px sans-serif';
            ctx.textAlign = 'left';
            ctx.textBaseline = 'top';
            ctx.fillText(lines[0], tx + padX, ty + padY);
            ctx.font = '10px sans-serif';
            ctx.fillStyle = colors[0].replace('0.8', '1');
            ctx.fillText('P: ' + b.promptVal.toLocaleString(), tx + padX, ty + padY + lineH);
            ctx.fillStyle = colors[1].replace('0.8', '1');
            ctx.fillText('O: ' + b.predictedVal.toLocaleString(), tx + padX, ty + padY + lineH * 2);
        }
    },

    roundRect(ctx, x, y, w, h, r) {
        ctx.moveTo(x + r, y);
        ctx.lineTo(x + w - r, y);
        ctx.quadraticCurveTo(x + w, y, x + w, y + r);
        ctx.lineTo(x + w, y + h - r);
        ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
        ctx.lineTo(x + r, y + h);
        ctx.quadraticCurveTo(x, y + h, x, y + h - r);
        ctx.lineTo(x, y + r);
        ctx.quadraticCurveTo(x, y, x + r, y);
        ctx.closePath();
    },

    /* ================= 工具 ================= */

    formatNum(val) {
        if (val == null || isNaN(val)) return '0';
        if (typeof val === 'number') {
            if (Number.isInteger(val)) return val.toLocaleString();
            return val.toFixed(2);
        }
        return String(val);
    },

    formatTime(wallTime) {
        if (!wallTime) return '-';
        const d = new Date(wallTime);
        const pad2 = n => String(n).padStart(2, '0');
        return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
            + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    }
};
