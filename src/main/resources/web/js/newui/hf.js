/* ================= hf.js — 模型搜索页（HF 在线搜索，移植自旧版 js/hf.js） ================= */
/* 后端接口与旧版一致：/api/hf/search、/api/hf/gguf、/api/hf/readme、/api/downloads/model/create */
'use strict';

const HfSearch = {
    hits: [], query: '', base: 'mirror',
    nextStartPage: 0, maxPages: 1, loading: false,
    PAGE_SIZE: 30,          // 后端单页固定 30 条
    COLLAPSE_LIMIT: 3,      // GGUF 列表默认折叠条数
    selected: null, ggufGroups: [], mmprojGroups: [], ggufCollapsed: true,
    ggufAbort: null, readmeAbort: null,

    init() {
        $('#hfSearchBtn').addEventListener('click', () => this.search());
        $('#hfLoadMoreBtn').addEventListener('click', () => this.loadMore());
        $('#hfQuery').addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); this.search(); } });
        $('#hfGgufToggle').addEventListener('click', () => { this.ggufCollapsed = !this.ggufCollapsed; this.renderGguf(); });
        $('#hfBackBtn').addEventListener('click', () => $('#hfSplit').classList.remove('show-detail'));
        if (localStorage.getItem('newui-hf-note-off') !== '1') $('#hfNote').style.display = '';
        $('#hfNoteClose').addEventListener('click', () => {
            $('#hfNote').style.display = 'none';
            localStorage.setItem('newui-hf-note-off', '1');
        });
    },

    /* ================= 搜索 / 分页 ================= */
    search() {
        const q = $('#hfQuery').value.trim();
        if (!q) { toast('请输入搜索关键字'); return; }
        this.query = q;
        this.base = $('#hfBase').value || 'mirror';
        const n = parseInt($('#hfLimit').value, 10);
        const limit = isFinite(n) && n > 0 ? Math.min(200, n) : 30;
        this.maxPages = Math.max(1, Math.ceil(limit / this.PAGE_SIZE));
        this.nextStartPage = 0;
        this.hits = [];
        $('#hfHitsList').innerHTML = '<div class="hf-placeholder"><i class="fas fa-spinner fa-spin"></i> 正在搜索…</div>';
        this.setFooter(false);
        this.loading = true;
        this.fetchPage(limit)
            .then(newHits => {
                this.hits = hfMergeHits(this.hits, newHits);
                this.nextStartPage += this.maxPages;
                this.renderHits();
                this.setFooter(newHits.length >= limit);
            })
            .catch(e => {
                this.hits = [];
                $('#hfHitsList').innerHTML = '<div class="empty"><i class="fas fa-triangle-exclamation"></i>搜索失败</div>';
                this.setFooter(false);
                toast(e.message || '网络请求失败', 'error');
            })
            .finally(() => { this.loading = false; });
    },

    /* 顶栏刷新按钮：重跑当前搜索 */
    refresh() { if (this.query) this.search(); },

    fetchPage(limit) {
        const url = '/api/hf/search?query=' + encodeURIComponent(this.query)
            + '&limit=' + limit + '&startPage=' + this.nextStartPage + '&maxPages=' + this.maxPages
            + '&base=' + encodeURIComponent(this.base);
        return api(url).then(d => {
            if (!d || d.success !== true) {
                throw new Error((d && d.error) || '搜索失败：hf-mirror.com 存在访问频率限制，请稍候重试；huggingface.co 在中国大陆需要科学上网。');
            }
            const hits = d.data && d.data.hits;
            return Array.isArray(hits) ? hits : [];
        });
    },

    loadMore() {
        if (this.loading || !this.query) return;
        const n = parseInt($('#hfLimit').value, 10);
        const limit = isFinite(n) && n > 0 ? Math.min(200, n) : 30;
        this.maxPages = Math.max(1, Math.ceil(limit / this.PAGE_SIZE));
        this.setFooter(true, '加载中…');
        this.loading = true;
        const before = this.hits.length;
        this.fetchPage(limit)
            .then(newHits => {
                this.hits = hfMergeHits(this.hits, newHits);
                this.nextStartPage += this.maxPages;
                this.renderHits();
                const added = this.hits.length - before;
                if (added <= 0) { this.setFooter(false); toast('没有更多结果了'); }
                else this.setFooter(newHits.length >= limit);
            })
            .catch(e => {
                this.setFooter(true);
                toast(e.message || '网络请求失败', 'error');
            })
            .finally(() => { this.loading = false; });
    },

    setFooter(visible, text) {
        $('#hfHitsFooter').style.display = visible ? '' : 'none';
        const btn = $('#hfLoadMoreBtn');
        btn.disabled = !!text;
        btn.textContent = text || '加载更多';
    },

    renderHits() {
        const c = $('#hfHitsList');
        if (!this.hits.length) {
            c.innerHTML = '<div class="empty"><i class="fas fa-inbox"></i>未找到结果</div>';
            return;
        }
        c.innerHTML = this.hits.map(h => {
            const repoId = String(h.repoId || '');
            let meta = '';
            if (h.downloads != null) meta += '<span><i class="fas fa-download"></i>' + hfFmtNum(h.downloads) + '</span>';
            if (h.likes != null) meta += '<span><i class="fas fa-thumbs-up"></i>' + hfFmtNum(h.likes) + '</span>';
            if (h.parameters) meta += '<span><i class="fas fa-sliders"></i>' + esc(h.parameters) + '</span>';
            if (h.pipelineTag) meta += '<span><i class="fas fa-tag"></i>' + esc(h.pipelineTag) + '</span>';
            const date = hfFmtDate(h.lastModified);
            if (date) meta += '<span><i class="fas fa-clock"></i>' + date + '</span>';
            return '<div class="hf-hit' + (repoId === this.selected ? ' active' : '') + '" onclick="HfSearch.select(\'' + esc(repoId) + '\')">' +
                '<div class="hh-name">' + esc(repoId) + '</div>' +
                (meta ? '<div class="hh-meta">' + meta + '</div>' : '') +
                '</div>';
        }).join('');
    },

    /* ================= 详情 ================= */
    select(repoId) {
        const hit = this.hits.find(h => String(h.repoId || '') === repoId);
        if (!hit) return;
        this.cancelDetail();
        this.selected = repoId;
        this.renderHits(); // 刷新列表高亮
        $('#hfSplit').classList.add('show-detail'); // 移动端切换到详情视图
        $('#hfDetailEmpty').style.display = 'none';
        $('#hfDetailBody').style.display = '';
        $('#hfDetail').scrollTop = 0;
        $('#hfDetailName').textContent = repoId;
        const home = $('#hfHomeLink');
        if (hit.modelUrl) { home.href = hit.modelUrl; home.style.display = ''; }
        else { home.style.display = 'none'; home.removeAttribute('href'); }

        let stats = '';
        if (hit.downloads != null) stats += '<span><i class="fas fa-download"></i>' + hfFmtNum(hit.downloads) + ' 下载</span>';
        if (hit.likes != null) stats += '<span><i class="fas fa-thumbs-up"></i>' + hfFmtNum(hit.likes) + ' 赞</span>';
        if (hit.pipelineTag) stats += '<span><i class="fas fa-tag"></i>' + esc(hit.pipelineTag) + '</span>';
        if (hit.parameters) stats += '<span><i class="fas fa-sliders"></i>' + esc(hit.parameters) + '</span>';
        const date = hfFmtDate(hit.lastModified);
        if (date) stats += '<span><i class="fas fa-clock"></i>' + date + '</span>';
        $('#hfDetailStats').innerHTML = stats;

        this.fetchGguf(repoId);
        this.fetchReadme(repoId);
    },

    /* 中止进行中的详情请求（切换仓库或离开页面时调用） */
    cancelDetail() {
        if (this.ggufAbort) { this.ggufAbort.abort(); this.ggufAbort = null; }
        if (this.readmeAbort) { this.readmeAbort.abort(); this.readmeAbort = null; }
        this.selected = null;
        this.ggufGroups = [];
        this.mmprojGroups = [];
        this.ggufCollapsed = true;
    },

    fetchGguf(repoId) {
        const list = $('#hfGgufList');
        list.innerHTML = '<div class="hf-placeholder"><i class="fas fa-spinner fa-spin"></i> 正在解析 GGUF 文件…</div>';
        this.renderGgufToolbar();
        const controller = new AbortController();
        this.ggufAbort = controller;
        fetch('/api/hf/gguf?model=' + encodeURIComponent(repoId) + '&base=' + encodeURIComponent(this.base), { signal: controller.signal })
            .then(r => r.json())
            .then(d => {
                if (controller.signal.aborted || this.selected !== repoId) return;
                if (!d || d.success !== true) throw new Error((d && d.error) || '解析失败');
                const files = (d.data && d.data.ggufFiles) || [];
                const all = hfGroupGgufFiles(files);
                this.mmprojGroups = all.filter(hfIsMmprojGroup);
                this.ggufGroups = all.filter(g => !hfIsMmprojGroup(g));
                this.ggufCollapsed = this.ggufGroups.length > this.COLLAPSE_LIMIT;
                this.renderGguf();
                if (d.data && d.data.treeError) toast(d.data.treeError);
            })
            .catch(e => {
                if (e && e.name === 'AbortError') return;
                if (this.selected !== repoId) return;
                this.ggufGroups = [];
                this.mmprojGroups = [];
                this.renderGgufToolbar();
                list.innerHTML = '<div class="hf-placeholder">' + esc(e.message || '解析失败') + '</div>';
            })
            .finally(() => { if (this.ggufAbort === controller) this.ggufAbort = null; });
    },

    renderGgufToolbar() {
        const total = this.ggufGroups.length;
        const visible = this.ggufCollapsed ? Math.min(total, this.COLLAPSE_LIMIT) : total;
        const mmproj = this.mmprojGroups.length;
        $('#hfGgufSummary').textContent = total <= 0 ? ''
            : '显示 ' + visible + '/' + total + ' 个 GGUF' + (mmproj ? '，下载时自动附带 ' + mmproj + ' 个 mmproj' : '');
        const btn = $('#hfGgufToggle');
        btn.style.display = total > this.COLLAPSE_LIMIT ? '' : 'none';
        btn.innerHTML = this.ggufCollapsed
            ? '<i class="fas fa-chevron-down"></i> 展开全部'
            : '<i class="fas fa-chevron-up"></i> 收起';
    },

    renderGguf() {
        const list = $('#hfGgufList');
        this.renderGgufToolbar();
        if (!this.ggufGroups.length) {
            list.innerHTML = '<div class="hf-placeholder">未找到 GGUF 文件</div>';
            return;
        }
        const shown = this.ggufCollapsed ? this.ggufGroups.slice(0, this.COLLAPSE_LIMIT) : this.ggufGroups;
        list.innerHTML = shown.map(g => {
            const idx = this.ggufGroups.indexOf(g);
            let badges = '';
            const sizeText = g.totalSize != null ? fmtSize(g.totalSize) : '';
            if (sizeText && sizeText !== '—') badges += '<span class="badge"><i class="fas fa-hdd"></i> ' + sizeText + '</span>';
            if (g.hasLfs) badges += '<span class="badge"><i class="fas fa-database"></i> LFS</span>';
            if (g.isSplit) badges += '<span class="badge"><i class="fas fa-table-cells-large"></i> 分片 ' + g.partCount + '/' + g.partTotal + '</span>';
            return '<div class="hf-gguf-item">' +
                '<div class="hf-gguf-main">' +
                    '<span class="hf-gguf-name" title="点击复制下载链接" onclick="HfSearch.copyLinks(' + idx + ')">' + esc(g.displayPath || '') + '</span>' +
                    (badges ? '<span class="hf-gguf-badges">' + badges + '</span>' : '') +
                '</div>' +
                '<div class="hf-gguf-actions">' +
                    '<button class="btn mini" onclick="HfSearch.copyLinks(' + idx + ')"><i class="fas fa-copy"></i> 复制链接</button>' +
                    '<button class="btn mini primary" onclick="HfSearch.download(' + idx + ')"><i class="fas fa-download"></i> 下载</button>' +
                '</div>' +
            '</div>';
        }).join('');
    },

    fetchReadme(repoId) {
        const body = $('#hfReadmeBody');
        body.innerHTML = '<div class="hf-placeholder"><i class="fas fa-spinner fa-spin"></i> 加载中…</div>';
        $('#hfReadmeLink').style.display = 'none';
        const controller = new AbortController();
        this.readmeAbort = controller;
        fetch('/api/hf/readme?model=' + encodeURIComponent(repoId) + '&base=' + encodeURIComponent(this.base), { signal: controller.signal })
            .then(r => r.json())
            .then(d => {
                if (controller.signal.aborted || this.selected !== repoId) return;
                if (!d || d.success !== true || !d.data) throw new Error((d && d.error) || 'README 加载失败');
                this.renderReadme(d.data.markdown, d.data.readmeUrl);
                if (d.data.readmeUrl) {
                    const link = $('#hfReadmeLink');
                    link.href = d.data.readmeUrl;
                    link.style.display = '';
                }
            })
            .catch(e => {
                if (e && e.name === 'AbortError') return;
                if (this.selected !== repoId) return;
                body.innerHTML = '<div class="hf-placeholder">' + esc(e.message || 'README 加载失败') + '</div>';
            })
            .finally(() => { if (this.readmeAbort === controller) this.readmeAbort = null; });
    },

    renderReadme(markdown, readmeUrl) {
        const body = $('#hfReadmeBody');
        const input = markdown == null ? '' : String(markdown);
        if (!input.trim()) { body.innerHTML = '<div class="hf-placeholder">README 为空</div>'; return; }
        if (window.marked && typeof window.marked.parse === 'function') {
            try {
                const rawHtml = window.marked.parse(input, { gfm: true, breaks: false, mangle: false, headerIds: false });
                body.innerHTML = '<div class="hf-md">' + hfSanitizeHtml(rawHtml) + '</div>';
                hfRewriteLinks(body, readmeUrl);
                return;
            } catch (e) { /* 落到纯文本渲染 */ }
        }
        body.innerHTML = '<pre class="hf-readme-plain">' + esc(input) + '</pre>';
    },

    /* ================= 下载 / 复制 ================= */
    download(idx) {
        const g = this.ggufGroups[idx];
        if (!g) return;
        const repo = hfParseRepoId(this.selected);
        if (!repo) { toast('RepoId 无效，无法解析 author/modelId', 'error'); return; }
        let urls = (g.files || []).map(f => f && f.downloadUrl ? String(f.downloadUrl).trim() : '').filter(Boolean);
        if (!urls.length) { toast('下载链接为空'); return; }
        // 自动附带最佳 mmproj（视觉投影文件）
        const best = hfPickBestMmproj(this.mmprojGroups);
        let withMmproj = false;
        if (best) {
            const mu = (best.files || []).map(f => f && f.downloadUrl ? String(f.downloadUrl).trim() : '').filter(Boolean);
            if (mu.length) { urls = Array.from(new Set(urls.concat(mu))); withMmproj = true; }
        }
        const ggufPath = g.displayPath || (g.files && g.files[0] && g.files[0].path != null ? String(g.files[0].path) : '') || g.key || '';
        const body = { author: repo.author, modelId: repo.modelId, downloadUrl: urls };
        const fileName = hfFileName(ggufPath);
        if (fileName) body.name = fileName;
        if (ggufPath) body.path = ggufPath;
        post('/api/downloads/model/create', body)
            .then(d => {
                if (!d || d.success !== true) throw new Error((d && d.error) || '创建下载任务失败');
                const count = Array.isArray(d.tasks) ? d.tasks.length : urls.length;
                toast('已创建 ' + count + ' 个下载任务' + (withMmproj ? '（已自动包含 mmproj）' : '') + '，请到「下载」页查看', 'success');
            })
            .catch(e => toast(e.message || '网络请求失败', 'error'));
    },

    copyLinks(idx) {
        const g = this.ggufGroups[idx];
        if (!g) return;
        const links = (g.files || []).map(f => f && f.downloadUrl ? String(f.downloadUrl) : '').filter(Boolean);
        if (!links.length) { toast('没有可复制的下载链接'); return; }
        hfCopyText(links.join('\n')).then(ok =>
            toast(ok ? '已复制 ' + links.length + ' 条链接' : '无法写入剪贴板', ok ? 'success' : 'error'));
    }
};

/* ================= 模块私有工具函数 ================= */

function hfFmtDate(iso) {
    if (typeof iso !== 'string' || !iso.trim()) return '';
    const d = new Date(iso.trim());
    if (isNaN(d.getTime())) return '';
    const p = n => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

function hfFmtNum(n) {
    n = Number(n);
    if (!isFinite(n)) return '';
    if (n >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(n);
}

function hfMergeHits(existing, incoming) {
    const seen = new Set();
    const out = [];
    (Array.isArray(existing) ? existing : []).concat(Array.isArray(incoming) ? incoming : []).forEach(h => {
        const id = h && h.repoId != null ? String(h.repoId) : '';
        if (!id || seen.has(id)) return;
        seen.add(id);
        out.push(h);
    });
    return out;
}

/* 按 "-00001-of-00002.gguf" 分片规则聚合 GGUF 文件 */
function hfSplitInfo(path) {
    const p = path == null ? '' : String(path);
    const m = p.match(/-(\d{5})-?of-(\d{5})\.gguf$/i);
    if (!m) return null;
    const partIndex = Number(m[1]);
    const partTotal = Number(m[2]);
    if (!isFinite(partIndex) || !isFinite(partTotal)) return null;
    const key = p.replace(/-(\d{5})-?of-(\d{5})\.gguf$/i, '');
    return { key, displayPath: key + '.gguf', partIndex, partTotal };
}

function hfGroupGgufFiles(files) {
    const groups = new Map();
    const singles = [];
    (Array.isArray(files) ? files : []).forEach(f => {
        const path = f && f.path != null ? String(f.path) : '';
        const info = hfSplitInfo(path);
        if (!info) {
            singles.push({ isSplit: false, key: path, displayPath: path, files: [f], partCount: 1, partTotal: 1 });
            return;
        }
        const g = groups.get(info.key);
        if (!g) groups.set(info.key, { key: info.key, displayPath: info.displayPath, files: [{ file: f, partIndex: info.partIndex }], partTotal: info.partTotal });
        else {
            g.files.push({ file: f, partIndex: info.partIndex });
            if (isFinite(info.partTotal) && info.partTotal > g.partTotal) g.partTotal = info.partTotal;
        }
    });
    const merged = [];
    groups.forEach(g => {
        g.files.sort((a, b) => (a.partIndex || 0) - (b.partIndex || 0));
        const ordered = g.files.map(x => x.file);
        merged.push({ isSplit: true, key: g.key, displayPath: g.displayPath, files: ordered, partCount: ordered.length, partTotal: g.partTotal || ordered.length });
    });
    const all = singles.concat(merged).map(g => {
        let totalSize = null, hasAnySize = false, hasLfs = false;
        (g.files || []).forEach(f => {
            if (!f) return;
            if (f.lfsOid) hasLfs = true;
            const s = f.size != null ? Number(f.size) : (f.lfsSize != null ? Number(f.lfsSize) : NaN);
            if (isFinite(s) && s > 0) { totalSize = (totalSize || 0) + s; hasAnySize = true; }
        });
        return Object.assign({}, g, { totalSize: hasAnySize ? totalSize : null, hasLfs });
    });
    all.sort((a, b) => String(a.displayPath || '').localeCompare(String(b.displayPath || ''), 'zh-CN'));
    return all;
}

function hfFileName(path) {
    const p = path == null ? '' : String(path);
    const idx = p.lastIndexOf('/');
    return (idx >= 0 ? p.substring(idx + 1) : p).trim();
}

function hfIsMmprojPath(path) {
    const name = hfFileName(path).toLowerCase();
    return !!name && name.endsWith('.gguf') && name.includes('mmproj');
}

function hfIsMmprojGroup(g) {
    return !!g && hfIsMmprojPath(g.displayPath || g.key || '');
}

function hfGroupSize(g) {
    const n = g && g.totalSize != null ? Number(g.totalSize) : NaN;
    if (isFinite(n) && n > 0) return n;
    let total = 0;
    ((g && g.files) || []).forEach(f => {
        if (!f) return;
        const s = f.size != null ? Number(f.size) : (f.lfsSize != null ? Number(f.lfsSize) : NaN);
        if (isFinite(s) && s > 0) total += s;
    });
    return total;
}

function hfPickBestMmproj(groups) {
    let best = null, bestSize = -1;
    (Array.isArray(groups) ? groups : []).forEach(g => {
        if (!hfIsMmprojGroup(g)) return;
        const size = hfGroupSize(g);
        if (size > bestSize) { best = g; bestSize = size; }
    });
    return best;
}

function hfParseRepoId(repoId) {
    const s = repoId == null ? '' : String(repoId).trim();
    const idx = s.indexOf('/');
    if (idx <= 0 || idx === s.length - 1) return null;
    return { author: s.substring(0, idx), modelId: s.substring(idx + 1) };
}

async function hfCopyText(text) {
    const value = text == null ? '' : String(text);
    if (!value) return false;
    if (navigator.clipboard && navigator.clipboard.writeText) {
        try { await navigator.clipboard.writeText(value); return true; } catch (e) { /* 降级 */ }
    }
    try {
        const ta = document.createElement('textarea');
        ta.value = value;
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

/* README HTML 消毒（防 XSS，内容来自第三方仓库） */
function hfSanitizeHtml(html) {
    const template = document.createElement('template');
    template.innerHTML = html == null ? '' : String(html);
    ['script', 'style', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'textarea', 'select', 'meta', 'link', 'base']
        .forEach(tag => template.content.querySelectorAll(tag).forEach(node => node.remove()));
    template.content.querySelectorAll('*').forEach(node => {
        Array.from(node.attributes).forEach(attr => {
            const name = String(attr.name || '').toLowerCase();
            const value = String(attr.value || '').trim();
            if (name.startsWith('on') || name === 'srcdoc') { node.removeAttribute(attr.name); return; }
            if ((name === 'href' || name === 'src' || name === 'xlink:href') && !hfSafeUrl(value, node.tagName)) { node.removeAttribute(attr.name); return; }
            if (name === 'style' && /expression\s*\(|url\s*\(\s*['"]?\s*javascript:/i.test(value)) node.removeAttribute(attr.name);
        });
    });
    return template.innerHTML;
}

function hfSafeUrl(rawUrl, tagName) {
    const value = rawUrl == null ? '' : String(rawUrl).trim();
    if (!value || value.startsWith('#') || value.startsWith('/') || value.startsWith('./') || value.startsWith('../')) return true;
    const lower = value.toLowerCase();
    if (lower.startsWith('javascript:') || lower.startsWith('vbscript:')) return false;
    if (lower.startsWith('data:')) return String(tagName || '').toUpperCase() === 'IMG' && /^data:image\//i.test(value);
    return /^(https?:|mailto:|tel:)/i.test(value);
}

/* README 相对链接/图片基于原文地址补全，链接一律新窗口打开 */
function hfRewriteLinks(container, readmeUrl) {
    if (!container || !readmeUrl) return;
    container.querySelectorAll('a[href], img[src]').forEach(node => {
        const attr = node.tagName === 'IMG' ? 'src' : 'href';
        const raw = node.getAttribute(attr);
        if (!raw || raw.startsWith('#') || raw.startsWith('data:') || raw.startsWith('javascript:')) return;
        try { node.setAttribute(attr, new URL(raw, readmeUrl).toString()); } catch (e) { /* 保留原值 */ }
        if (node.tagName === 'A') {
            node.setAttribute('target', '_blank');
            node.setAttribute('rel', 'noopener noreferrer');
        }
    });
}
