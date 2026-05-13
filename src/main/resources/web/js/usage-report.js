(function () {
    const PAGE_SIZE = 50;

    let tokenSummaryData = [];
    let requestLogsData = [];
    let filteredLogs = [];
    let currentPage = 1;

    function t(key, fallback) {
        if (typeof window.t === 'function') return window.t(key, fallback);
        return fallback || key;
    }

    function toast(title, msg, type) {
        if (typeof window.showToast === 'function') window.showToast(title, msg, type);
    }

    async function load() {
        await Promise.all([fetchTokenSummary(), fetchRequestLogs()]);
    }

    async function fetchTokenSummary() {
        try {
            const resp = await fetch('/api/report/token-summary');
            const result = await resp.json();
            if (!result || !result.success) {
                tokenSummaryData = [];
            } else {
                tokenSummaryData = result.data || [];
            }
        } catch (e) {
            tokenSummaryData = [];
        }
        renderTokenSummary();
    }

    async function fetchRequestLogs() {
        try {
            const resp = await fetch('/api/report/request-logs');
            const result = await resp.json();
            if (!result || !result.success) {
                requestLogsData = [];
            } else {
                requestLogsData = result.data || [];
            }
        } catch (e) {
            requestLogsData = [];
        }
        buildModelFilter();
        applyFilter();
    }

    function renderTokenSummary() {
        const canvasEl = document.getElementById('tokenChartCanvas');
        const emptyEl = document.getElementById('tokenChartEmpty');
        const wrapEl = document.getElementById('tokenChartWrap');
        const stats = document.getElementById('tokenSummaryStats');
        const body = document.getElementById('tokenSummaryBody');
        if (!body) return;

        let totalModels = tokenSummaryData.length;
        let totalPrompt = 0;
        let totalPredicted = 0;
        let totalCache = 0;
        let cardHtml = '';
        const sorted = tokenSummaryData.slice().sort((a, b) => (b.totalTokens || 0) - (a.totalTokens || 0));
        for (const m of sorted) {
            totalPrompt += m.totalPromptTokens || 0;
            totalPredicted += m.totalPredictedTokens || 0;
            totalCache += m.totalCacheTokens || 0;
            cardHtml += '<div class="token-card">'
                + '<div class="tk-model">' + escapeHtml(m.modelId || '') + '</div>'
                + '<div class="tk-tokens">'
                + '<span>输入 ' + (m.totalPromptTokens || 0).toLocaleString() + '</span>'
                + '<span>输出 ' + (m.totalPredictedTokens || 0).toLocaleString() + '</span>'
                + '<span class="tk-cache">' + t('report.cache_label', '缓存') + ' ' + (m.totalCacheTokens || 0).toLocaleString() + '</span>'
                + '</div>'
                + '</div>';
        }
        if (!cardHtml) {
            cardHtml = '<div class="empty">' + t('page.usage_report.chart_empty', '暂无数据') + '</div>';
        }
        body.innerHTML = cardHtml;

        stats.innerHTML = ''
            + '<div class="stat-card"><div class="stat-value">' + totalModels + '</div><div class="stat-label">' + t('page.usage_report.stats.models_with_records', '有记录的模型') + '</div></div>'
            + '<div class="stat-card"><div class="stat-value">' + totalPrompt.toLocaleString() + '</div><div class="stat-label">' + t('page.usage_report.stats.total_prompt', '总输入 Token') + '</div></div>'
            + '<div class="stat-card"><div class="stat-value">' + totalPredicted.toLocaleString() + '</div><div class="stat-label">' + t('page.usage_report.stats.total_predicted', '总输出 Token') + '</div></div>'
            + '<div class="stat-card"><div class="stat-value">' + totalCache.toLocaleString() + '</div><div class="stat-label">' + t('report.total_cache', '总缓存命中 Token') + '</div></div>';

        if (!canvasEl || !emptyEl || !wrapEl) return;
        if (!tokenSummaryData.length) {
            emptyEl.style.display = 'flex';
            return;
        }
        emptyEl.style.display = 'none';

        const data = tokenSummaryData.slice().sort((a, b) => (b.totalTokens || 0) - (a.totalTokens || 0));
        const ctx = canvasEl.getContext('2d');
        const rect = wrapEl.getBoundingClientRect();
        const dpr = window.devicePixelRatio || 1;
        const w = rect.width - 16, h = rect.height - 16;
        if (w <= 0 || h <= 0) return;
        canvasEl.width = w * dpr; canvasEl.height = h * dpr;
        canvasEl.style.width = w + 'px'; canvasEl.style.height = h + 'px';
        ctx.scale(dpr, dpr);

        const count = data.length;
        const pad = { top: 14, bottom: 30, left: 64, right: 14 };
        const cw = w - pad.left - pad.right;
        const ch = h - pad.top - pad.bottom;
        const barW = Math.min(cw / count * 0.28, 28);
        const gap = barW * 0.4;
        const gw = barW * 2 + gap;

        let maxV = 0;
        data.forEach(d => maxV = Math.max(maxV, d.totalPromptTokens || 0, d.totalPredictedTokens || 0));
        maxV = Math.ceil(Math.max(maxV * 1.15, 100));

        const style = getComputedStyle(wrapEl);
        const tc = style.getPropertyValue('--text-secondary').trim() || '#999';
        const bc = style.getPropertyValue('--border-color').trim() || '#333';

        ctx.clearRect(0, 0, w, h);

        // axis
        ctx.strokeStyle = bc; ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(pad.left, pad.top); ctx.lineTo(pad.left, pad.top + ch); ctx.lineTo(pad.left + cw, pad.top + ch); ctx.stroke();

        // y labels
        ctx.fillStyle = tc; ctx.font = '10px sans-serif'; ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
        for (let i = 0; i <= 4; i++) {
            const val = Math.round((maxV / 4) * i);
            const y = pad.top + ch - (ch / 4) * i;
            ctx.fillText(val.toLocaleString(), pad.left - 6, y);
            ctx.globalAlpha = 0.3; ctx.strokeStyle = bc; ctx.lineWidth = 0.5;
            ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(pad.left + cw, y); ctx.stroke();
            ctx.globalAlpha = 1;
        }

        // bars
        const colors = ['rgba(99,102,241,0.8)', 'rgba(16,185,129,0.8)'];
        for (let i = 0; i < count; i++) {
            const d = data[i];
            const pf = d.totalPromptTokens || 0;
            const dg = d.totalPredictedTokens || 0;
            const x = pad.left + (cw / count) * i + (cw / count - gw) / 2;

            ctx.fillStyle = colors[0];
            ctx.fillRect(x, pad.top + ch - (pf / maxV) * ch, barW, (pf / maxV) * ch);
            ctx.fillStyle = colors[1];
            ctx.fillRect(x + barW + gap, pad.top + ch - (dg / maxV) * ch, barW, (dg / maxV) * ch);

            ctx.fillStyle = tc; ctx.font = 'bold 10px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
            ctx.fillText(i + 1, x + gw / 2, pad.top + ch + 4);
        }

        // legend
        ctx.font = '10px sans-serif'; ctx.textAlign = 'left'; ctx.textBaseline = 'top';
        ctx.fillStyle = colors[0]; ctx.fillRect(w - 92, 2, 9, 9);
        ctx.fillStyle = tc; ctx.fillText('Prompt', w - 79, 1);
        ctx.fillStyle = colors[1]; ctx.fillRect(w - 42, 2, 9, 9);
        ctx.fillStyle = tc; ctx.fillText('Predicted', w - 29, 1);

        // marker legend
        const parentCol = wrapEl.parentNode;
        let legendEl = parentCol.querySelector('.chart-marker-legend');
        if (!legendEl) {
            legendEl = document.createElement('div');
            legendEl.className = 'chart-marker-legend';
            parentCol.appendChild(legendEl);
        }
        let legendHtml = '';
        for (let i = 0; i < count; i++) {
            legendHtml += '<span class="ml-item"><span class="ml-marker">' + (i + 1) + '</span> ' + escapeHtml(data[i].modelId || '#' + (i + 1)) + '</span>';
        }
        legendEl.innerHTML = legendHtml;
    }

    function buildModelFilter() {
        const select = document.getElementById('requestLogModelFilter');
        const modelSet = new Set();
        for (const r of requestLogsData) {
            if (r.modelId) modelSet.add(r.modelId);
        }
        const models = Array.from(modelSet).sort();
        select.innerHTML = '<option value="">' + t('page.model.filter.all', '全部') + '</option>';
        for (const m of models) {
            select.innerHTML += '<option value="' + escapeAttr(m) + '">' + escapeHtml(m) + '</option>';
        }
    }

    function filterByModel() {
        applyFilter();
    }

    function applyFilter() {
        const modelFilter = document.getElementById('requestLogModelFilter').value;
        if (modelFilter) {
            filteredLogs = requestLogsData.filter(r => r.modelId === modelFilter);
        } else {
            filteredLogs = requestLogsData.slice();
        }
        currentPage = 1;
        renderRequestLogs();
    }

    function prevPage() {
        if (currentPage > 1) {
            currentPage--;
            renderRequestLogs();
        }
    }

    function nextPage() {
        const totalPages = Math.ceil(filteredLogs.length / PAGE_SIZE) || 1;
        if (currentPage < totalPages) {
            currentPage++;
            renderRequestLogs();
        }
    }

    function renderRequestLogs() {
        const body = document.getElementById('requestLogBody');
        const pageInfo = document.getElementById('reqLogPageInfo');
        const prevBtn = document.getElementById('reqLogPrev');
        const nextBtn = document.getElementById('reqLogNext');

        const totalPages = Math.ceil(filteredLogs.length / PAGE_SIZE) || 1;
        if (currentPage > totalPages) currentPage = totalPages;
        const start = (currentPage - 1) * PAGE_SIZE;
        const end = Math.min(start + PAGE_SIZE, filteredLogs.length);
        const pageData = filteredLogs.slice(start, end);

        pageInfo.textContent = t('page.usage_report.page_info', '第 {current} / {total} 页').replace('{current}', currentPage).replace('{total}', totalPages);
        prevBtn.disabled = currentPage <= 1;
        nextBtn.disabled = currentPage >= totalPages;

        let html = '';
        for (const r of pageData) {
            html += '<tr>'
                + '<td>' + formatTime(r.startTime) + '</td>'
                + '<td>' + escapeHtml(r.modelId || '') + '</td>'
                + '<td>' + escapeHtml(r.endpoint || '') + '</td>'
                + '<td>' + (r.cacheTokens || 0).toLocaleString() + '</td>'
                + '<td>' + (r.promptTokens || 0).toLocaleString() + '</td>'
                + '<td>' + (r.predictedTokens || 0).toLocaleString() + '</td>'
                + '<td><strong>' + (r.totalTokens || 0).toLocaleString() + '</strong></td>'
                + '<td>' + (r.elapsedMs || 0).toLocaleString() + '</td>'
                + '<td>' + formatNum(r.promptPerSecond) + '</td>'
                + '<td>' + formatNum(r.predictedPerSecond) + '</td>'
                + '</tr>';
        }
        if (!html) {
            html = '<tr><td class="empty" colspan="10">' + t('page.usage_report.no_request_logs', '暂无请求记录') + '</td></tr>';
        }
        body.innerHTML = html;
    }

    function switchTab(tabName) {
        document.querySelectorAll('#main-usage-report .tab-bar button').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === tabName);
        });
        document.querySelectorAll('#main-usage-report .tab-panel').forEach(panel => {
            panel.classList.toggle('active', panel.id === 'panel-' + tabName);
        });
        if (tabName === 'token-summary') {
            setTimeout(renderTokenSummary, 50);
        }
    }

    function formatNum(val) {
        if (val == null || isNaN(val)) return '0';
        if (typeof val === 'number') {
            if (Number.isInteger(val)) return val.toLocaleString();
            return val.toFixed(2);
        }
        return String(val);
    }

    function formatTime(wallTime) {
        if (!wallTime) return '-';
        const d = new Date(wallTime);
        const pad2 = n => String(n).padStart(2, '0');
        return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
            + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    }

    function escapeHtml(str) {
        return String(str).replace(/[&<>"']/g, function(m) {
            return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]);
        });
    }

    function escapeAttr(str) {
        return escapeHtml(str).replace(/`/g, '&#96;');
    }

    let _initialized = false;
    function init() {
        if (_initialized) return;
        _initialized = true;
    }

    window.UsageReport = {
        init: init,
        load: load,
        switchTab: switchTab,
        filterByModel: filterByModel,
        prevPage: prevPage,
        nextPage: nextPage
    };
})();
