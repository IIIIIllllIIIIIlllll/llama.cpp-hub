/* ================= model-detail.js — 模型详情弹窗（概览/启动配置/聊天模板/Kwargs） ================= */
'use strict';

const ModelDetail = {
    model: null,

    init() {
        $$('#detailTabs button').forEach(b => b.addEventListener('click', () => {
            $$('#detailTabs button').forEach(x => x.classList.toggle('active', x === b));
            $$('#detailSheet .detail-pane').forEach(p => p.classList.toggle('active', p.dataset.dpane === b.dataset.dtab));
            if (b.dataset.dtab === 'template') this.loadTemplate();
            if (b.dataset.dtab === 'kwargs') this.loadKwargs();
            if (b.dataset.dtab === 'sampling') this.loadSampling();
        }));
        $('#tplSaveBtn').addEventListener('click', () => this.saveTemplate());
        $('#tplDeleteBtn').addEventListener('click', () => this.deleteTemplate());
        $('#tplDefaultBtn').addEventListener('click', () => this.defaultTemplate());
        $('#kwSaveBtn').addEventListener('click', () => this.saveKwargs());
        $('#kwClearBtn').addEventListener('click', () => this.clearKwargs());
        $('#sampSelect').addEventListener('change', () => this.bindSampling());
        $('#sampAddBtn').addEventListener('click', () => this.addSampling());
        $('#sampDelBtn').addEventListener('click', () => this.deleteSampling());
    },

    nodeQ(m) { const nid = Models.nodeParam(m); return nid ? '&nodeId=' + encodeURIComponent(nid) : ''; },
    nodeBody(m, body) { const nid = Models.nodeParam(m); if (nid) body.nodeId = nid; return body; },

    open(key) {
        const m = Models.find(key); if (!m) return;
        this.model = m;
        $('#detailTitle').textContent = m.alias || m.name;
        $$('#detailTabs button').forEach((x, i) => x.classList.toggle('active', i === 0));
        $$('#detailSheet .detail-pane').forEach((p, i) => p.classList.toggle('active', i === 0));
        $('#detailOverview').innerHTML = '<div class="skeleton" style="height:200px"></div>';
        $('#detailConfig').innerHTML = '<div class="skeleton" style="height:160px"></div>';
        UI.openSheet('#detailSheet');
        this.loadOverview();
        this.loadConfigPane();
    },

    /* ---------------- 概览 ---------------- */
    loadOverview() {
        const m = this.model;
        const q = '?modelId=' + encodeURIComponent(m.id) + this.nodeQ(m);
        const icon = (typeof getModelIcon === 'function') ? getModelIcon(m.architecture || '') : null;
        const badges = (m.supportsVision ? '<span>视觉</span>' : '') + (m.supportsAudio ? '<span>音频</span>' : '') + (m.hasMtp ? '<span>MTP</span>' : '') + (m.isClone ? '<span>克隆</span>' : '');
        const statusChip = m.isLoaded
            ? '<span class="status-chip loaded"><i class="fas fa-circle" style="font-size:7px"></i>运行中' + (m.port ? ' · ' + m.port : '') + '</span>'
            : '<span class="status-chip stopped">未启动</span>';
        const hero = '<div class="detail-hero">' +
            (icon ? '<img src="' + icon + '" alt="">' : '') +
            '<div style="flex:1;min-width:0"><div class="dh-name">' + esc(m.alias || m.name) + '</div>' +
            '<div class="dh-sub">' + statusChip + '<span class="mc-badges">' + badges + '</span></div></div></div>';
        Promise.all([
            api('/api/models/details' + q),
            api('/api/models/record?modelId=' + encodeURIComponent(m.id)).catch(() => null)
        ]).then(([d, rec]) => {
            if (!d.success) throw new Error(d.error || '加载失败');
            const model = d.model || {};
            let usage = '无记录';
            if (rec && rec.success && rec.data) {
                const r = rec.data;
                usage = '处理 ' + (r.prompt_n || 0) + ' · 生成 ' + (r.predicted_n || 0) + ' tokens';
                if ((r.draft_n || 0) > 0) {
                    usage += ' · 投机 ' + (r.draft_n_accepted || 0) + '/' + r.draft_n +
                        ' (' + (r.draft_n_accepted / r.draft_n * 100).toFixed(1) + '%)';
                }
            }
            const nodeName = m.nodeId === 'local' || !m.nodeId ? '本地' : ((Models.nodes[m.nodeId] || {}).name || m.nodeId);
            const stats = '<div class="stat-grid">' +
                this.statBox('fa-hdd', fmtSize(model.size || m.size), '文件大小') +
                this.statBox('fa-layer-group', esc(m.architecture || '未知'), '架构') +
                this.statBox('fa-microchip', esc(m.quantization || '—'), '量化') +
                this.statBox('fa-server', esc(nodeName), '节点') +
                '</div>';
            const rows = [
                this.infoRow('fa-folder-open', '路径', esc(model.path || '—')),
                this.infoRow('fa-chart-simple', '用量', esc(usage))
            ];
            if (model.startCmd) rows.push(this.infoRow('fa-terminal', '启动命令', '<span class="mono">' + esc(model.startCmd) + '</span>', true));
            if (model.metadata && typeof model.metadata === 'object') {
                Object.entries(model.metadata).forEach(([k, v]) => rows.push(this.infoRow('fa-tag', k, esc(v))));
            }
            $('#detailOverview').innerHTML = hero + stats + '<div class="info-list">' + rows.join('') + '</div>';
        }).catch(e => {
            $('#detailOverview').innerHTML = '<div class="empty"><i class="fas fa-triangle-exclamation"></i>' + esc(e.message) + '</div>';
        });
    },

    statBox(icon, val, label) {
        return '<div class="stat-box"><div class="sb-icon"><i class="fas ' + icon + '"></i></div>' +
            '<div class="sb-val" title="' + val + '">' + val + '</div><div class="sb-label">' + label + '</div></div>';
    },

    infoRow(icon, k, v, mono) {
        return '<div class="info-row"><span class="ir-icon"><i class="fas ' + icon + '"></i></span>' +
            '<span class="ir-k">' + k + '</span><span class="ir-v' + (mono ? ' mono' : '') + '">' + v + '</span></div>';
    },

    /* ---------------- 启动配置（只读） ---------------- */
    loadConfigPane() {
        const m = this.model;
        Promise.all([
            Models.fetchConfig(m).catch(() => null),
            api('/api/models/capabilities/get?modelId=' + encodeURIComponent(m.id) + this.nodeQ(m)).catch(() => null)
        ]).then(([cfgBundle, caps]) => {
            let html = '';
            if (cfgBundle && cfgBundle.cfg && Object.keys(cfgBundle.cfg).length) {
                const c = cfgBundle.cfg;
                html += '<div class="section-title"><i class="fas fa-sliders"></i> 当前配置：' + esc(cfgBundle.selected) + '</div>' +
                    '<div class="info-list">' +
                    this.infoRow('fa-cube', '运行库', esc(c.llamaBinPathSelect || c.llamaBinPath || '—')) +
                    this.infoRow('fa-microchip', '设备', esc(Array.isArray(c.device) ? c.device.join(', ') : '—')) +
                    this.infoRow('fa-display', '主 GPU', Number.isFinite(c.mg) && c.mg >= 0 ? 'GPU ' + c.mg : '默认') +
                    this.infoRow('fa-eye', '视觉', c.enableVision === false ? '关闭' : '开启') +
                    this.infoRow('fa-terminal', '命令行', '<span class="mono">' + esc(c.cmd || '—') + '</span>', true) +
                    (c.extraParams ? this.infoRow('fa-plus', '额外参数', '<span class="mono">' + esc(c.extraParams) + '</span>', true) : '') +
                    '</div>';
            } else {
                html += '<div class="empty"><i class="fas fa-file-circle-xmark"></i>尚未保存启动配置</div>';
            }
            if (caps && caps.success && caps.data) {
                const d = caps.data;
                const names = { thinking: 'Thinking', tools: '工具调用', rerank: 'Rerank', embedding: 'Embedding', vision: '视觉', audio: '音频' };
                const on = Object.keys(names).filter(k => d[k]);
                html += '<div class="section-title"><i class="fas fa-wand-magic-sparkles"></i> 模型能力</div><div>' +
                    (on.length ? on.map(k => '<span class="chip" style="margin:0 6px 6px 0">' + names[k] + '</span>').join('') : '<span class="muted">未启用任何能力</span>') + '</div>';
            }
            $('#detailConfig').innerHTML = html;
        });
    },

    /* ---------------- 聊天模板 ---------------- */
    loadTemplate() {
        const m = this.model;
        $('#tplText').value = '';
        api('/api/model/template/get?modelId=' + encodeURIComponent(m.id) + this.nodeQ(m)).then(r => {
            const tpl = r && r.data && (r.data.chatTemplate || r.data.template || '');
            if (tpl) $('#tplText').value = tpl;
            else toast('该模型暂无已保存的聊天模板');
        }).catch(() => {});
    },
    saveTemplate() {
        const text = $('#tplText').value;
        if (!confirm('确认保存该聊天模板吗？')) return;
        post('/api/model/template/set', this.nodeBody(this.model, { modelId: this.model.id, chatTemplate: text })).then(r => {
            if (r.success) toast('聊天模板已保存', 'success');
            else toast(r.error || '保存失败', 'error');
        });
    },
    deleteTemplate() {
        if (!confirm('确认删除已保存的聊天模板？')) return;
        post('/api/model/template/delete', this.nodeBody(this.model, { modelId: this.model.id })).then(r => {
            if (r.success) { $('#tplText').value = ''; toast('已删除', 'success'); }
            else toast(r.error || '删除失败', 'error');
        });
    },
    defaultTemplate() {
        if (!confirm('恢复为该模型 GGUF 自带的默认模板？')) return;
        post('/api/model/template/default', this.nodeBody(this.model, { modelId: this.model.id })).then(r => {
            if (r.success) { toast('已恢复默认', 'success'); this.loadTemplate(); }
            else toast(r.error || '操作失败', 'error');
        });
    },

    /* ---------------- Kwargs ---------------- */
    loadKwargs() {
        const m = this.model;
        $('#kwText').value = '';
        api('/api/model/chat_template_kwargs/get?modelId=' + encodeURIComponent(m.id) + this.nodeQ(m)).then(r => {
            const kw = r && r.data && r.data.chat_template_kwargs;
            if (kw && Object.keys(kw).length) $('#kwText').value = JSON.stringify(kw, null, 2);
        }).catch(() => {});
    },
    saveKwargs() {
        let obj;
        try { obj = JSON.parse($('#kwText').value || '{}'); }
        catch (e) { toast('JSON 格式错误：' + e.message, 'error'); return; }
        post('/api/model/chat_template_kwargs/set', this.nodeBody(this.model, { modelId: this.model.id, chat_template_kwargs: obj })).then(r => {
            if (r.success) toast('Kwargs 已保存', 'success');
            else toast(r.error || '保存失败', 'error');
        });
    },
    clearKwargs() {
        if (!confirm('清空该模型的 Kwargs？')) return;
        post('/api/model/chat_template_kwargs/delete', this.nodeBody(this.model, { modelId: this.model.id })).then(r => {
            if (r.success) { $('#kwText').value = ''; toast('已清空', 'success'); }
            else toast(r.error || '操作失败', 'error');
        });
    }
};

/* ================= 采样覆盖 ================= */
const SAMP_FIELDS = [
    ['temperature', '温度', '--temp'], ['top_p', 'Top P', '--top-p'], ['top_k', 'Top K', '--top-k'],
    ['min_p', 'Min P', '--min-p'], ['top_n_sigma', 'Top N Sigma', '--top-nsigma'],
    ['presence_penalty', 'Presence Penalty', '--presence-penalty'],
    ['repeat_penalty', 'Repeat Penalty', '--repeat-penalty'],
    ['frequency_penalty', 'Frequency Penalty', '--frequency-penalty'],
    ['dry_multiplier', 'DRY Multiplier', '--dry-multiplier'], ['dry_base', 'DRY Base', '--dry-base'],
    ['seed', '随机种子', '--seed']
];
const SAMPLER_OPTIONS = ['penalties', 'dry', 'top_n_sigma', 'top_k', 'typ_p', 'top_p', 'min_p', 'xtc', 'temperature'];
// 不在 UI 展示、但保存时必须保留的隐藏字段
const SAMP_HIDDEN_KEYS = ['dry_allowed_length', 'dry_penalty_last_n', 'dry_sequence_breakers'];

Object.assign(ModelDetail, {
    sampConfigs: {}, sampLoaded: false, sampOrder: [],

    loadSampling() {
        const m = this.model;
        const q = this.nodeQ(m);
        $('#sampBody').innerHTML = '<div class="skeleton" style="height:180px"></div>';
        Promise.all([
            api('/api/sys/model/sampling/setting/list' + (q ? '?' + q.slice(1) : '')),
            api('/api/sys/model/sampling/setting/get?modelId=' + encodeURIComponent(m.id) + q)
        ]).then(([list, bound]) => {
            this.sampConfigs = (list.success && list.data && list.data.configs) || {};
            const boundName = bound.success && bound.data ? (bound.data.samplingConfigName || '') : '';
            const sel = $('#sampSelect');
            sel.innerHTML = '<option value="">关闭功能</option>' +
                Object.keys(this.sampConfigs).map(n => '<option value="' + esc(n) + '">' + esc(n) + '</option>').join('');
            sel.value = this.sampConfigs[boundName] ? boundName : '';
            this.renderSampForm();
        }).catch(e => { $('#sampBody').innerHTML = '<div class="empty">' + esc(e.message) + '</div>'; });
    },

    renderSampForm() {
        const name = $('#sampSelect').value;
        if (!name) { $('#sampBody').innerHTML = '<div class="empty"><i class="fas fa-toggle-off"></i>采样覆盖已关闭，选择一个配置即可开启</div>'; return; }
        const cfg = this.sampConfigs[name] || {};
        // 采样器链：有序。兼容数组与 "a;b" 字符串两种存储形态（对应旧版 normalizeModelSamplingStringArray）
        let samplers = [];
        if (Array.isArray(cfg.samplers)) samplers = cfg.samplers.slice();
        else if (typeof cfg.samplers === 'string') samplers = cfg.samplers.split(/[;,]/).map(s => s.trim()).filter(Boolean);
        this.sampOrder = samplers;
        $('#sampBody').innerHTML =
            '<div class="samp-grid">' + SAMP_FIELDS.map(([k, label, flag]) =>
                '<div class="samp-field"><label>' + label + '<span class="sf-flag">' + flag + '</span></label>' +
                '<input type="text" inputmode="decimal" data-sk="' + k + '" value="' + esc(cfg[k] != null ? cfg[k] : '') + '"></div>'
            ).join('') + '</div>' +
            '<div class="section-title" style="margin-top:16px"><i class="fas fa-brain"></i> Thinking</div>' +
            '<label class="check-line"><input type="checkbox" data-sk="force_enable_thinking"' + (cfg.force_enable_thinking ? ' checked' : '') + '> 强制指定 thinking 开关</label>' +
            '<label class="check-line"><input type="checkbox" data-sk="enable_thinking"' + (cfg.enable_thinking ? ' checked' : '') + (cfg.force_enable_thinking ? '' : ' disabled') + '> 启用 thinking</label>' +
            '<div class="section-title" style="margin-top:16px"><i class="fas fa-list-ol"></i> 采样器链（按顺序生效）</div>' +
            '<div class="muted" style="font-size:12px;margin-bottom:10px">勾选表示启用该采样器；下方顺序为实际执行次序，最终值按分号拼接后传给 --samplers。</div>' +
            '<div id="sampChain"></div>';

        // 事件
        $$('#sampBody input[data-sk]').forEach(inp => {
            if (inp.dataset.sk === 'force_enable_thinking') {
                inp.addEventListener('change', () => {
                    const et = $('#sampBody input[data-sk="enable_thinking"]');
                    et.disabled = !inp.checked;
                    this.autoSaveSampling();
                });
            } else {
                inp.addEventListener(inp.type === 'checkbox' ? 'change' : 'input', () => this.autoSaveSampling());
            }
        });
        // 采样器链：事件委托挂在容器上，renderSampChain 只重建容器内部，绑定不失效
        $('#sampChain').addEventListener('click', e => this.onSampChainClick(e));
        this.renderSampChain();
    },

    /* 采样器链（有序多选，对应旧版 ordered-multiselect 的特殊处理）：
       点击备选加入链尾/从链中移除；↑↓ 调整执行顺序；最终值按 ; 拼接 */
    renderSampChain() {
        const el = $('#sampChain');
        if (!el) return;
        const order = this.sampOrder || [];
        const inChain = new Set(order);
        el.innerHTML =
            '<div class="sc-heading">点击添加采样器</div>' +
            '<div class="samp-chips">' + SAMPLER_OPTIONS.map(s =>
                '<button class="samp-chip' + (inChain.has(s) ? ' active' : '') + '" data-sc-opt="' + s + '">' + s + '</button>').join('') + '</div>' +
            '<div class="sc-heading" style="margin-top:12px">当前执行顺序</div>' +
            (order.length
                ? '<div class="sc-list">' + order.map((s, i) =>
                    '<div class="sc-item"><span class="sc-text">' + esc(s) + '</span><span class="sc-actions">' +
                    '<button class="sc-btn" data-sc-act="up" data-sc-i="' + i + '" title="上移"' + (i === 0 ? ' disabled' : '') + '><i class="fas fa-arrow-up"></i></button>' +
                    '<button class="sc-btn" data-sc-act="down" data-sc-i="' + i + '" title="下移"' + (i === order.length - 1 ? ' disabled' : '') + '><i class="fas fa-arrow-down"></i></button>' +
                    '<button class="sc-btn danger" data-sc-act="rm" data-sc-i="' + i + '" title="移除"><i class="fas fa-xmark"></i></button>' +
                    '</span></div>').join('') + '</div>'
                : '<div class="sc-empty">未选择</div>') +
            '<div class="sc-preview">最终值：' + esc(order.join(';')) + '</div>';
    },

    onSampChainClick(e) {
        const opt = e.target.closest('[data-sc-opt]');
        if (opt) {
            const s = opt.dataset.scOpt;
            const i = this.sampOrder.indexOf(s);
            if (i > -1) this.sampOrder.splice(i, 1); else this.sampOrder.push(s);
            this.renderSampChain();
            this.autoSaveSampling();
            return;
        }
        const btn = e.target.closest('[data-sc-act]');
        if (!btn || btn.disabled) return;
        const i = parseInt(btn.dataset.scI, 10);
        const order = this.sampOrder;
        const act = btn.dataset.scAct;
        if (act === 'rm') order.splice(i, 1);
        else if (act === 'up' && i > 0) { const t = order[i - 1]; order[i - 1] = order[i]; order[i] = t; }
        else if (act === 'down' && i < order.length - 1) { const t = order[i + 1]; order[i + 1] = order[i]; order[i] = t; }
        else return;
        this.renderSampChain();
        this.autoSaveSampling();
    },

    collectSampling() {
        const out = {};
        SAMP_FIELDS.forEach(([k]) => {
            const el = $('#sampBody input[data-sk="' + k + '"]');
            if (!el) return;
            const v = el.value.trim();
            if (v === '') return;
            const n = Number(v);
            if (!isNaN(n)) out[k] = n;
        });
        const force = $('#sampBody input[data-sk="force_enable_thinking"]');
        if (force) {
            out.force_enable_thinking = force.checked;
            if (force.checked) out.enable_thinking = $('#sampBody input[data-sk="enable_thinking"]').checked;
        }
        // 采样器链：保持用户设定的执行顺序（不再按 SAMPLER_OPTIONS 固定顺序重排）
        if (this.sampOrder && this.sampOrder.length) out.samplers = this.sampOrder.slice();
        // 隐藏字段从已存配置 merge 保留
        const cur = this.sampConfigs[$('#sampSelect').value] || {};
        SAMP_HIDDEN_KEYS.forEach(k => { if (cur[k] !== undefined) out[k] = cur[k]; });
        return out;
    },

    autoSaveSampling() {
        const name = $('#sampSelect').value;
        if (!name) return;
        clearTimeout(this._sampT);
        $('#sampSaving').textContent = '保存中…';
        this._sampT = setTimeout(() => {
            post('/api/sys/model/sampling/setting/add', { samplingConfigName: name, sampling: this.collectSampling() }).then(r => {
                if (r.success) {
                    this.sampConfigs[name] = this.collectSampling();
                    $('#sampSaving').textContent = '已自动保存';
                    setTimeout(() => { $('#sampSaving').textContent = ''; }, 1500);
                } else {
                    $('#sampSaving').textContent = '';
                    toast(r.error || '保存失败', 'error');
                }
            });
        }, 400);
    },

    bindSampling() {
        const name = $('#sampSelect').value;
        post('/api/sys/model/sampling/setting/set', this.nodeBody(this.model, { modelId: this.model.id, samplingConfigName: name })).then(r => {
            if (!r.success) toast(r.error || '设置失败', 'error');
            this.renderSampForm();
        });
    },

    addSampling() {
        UI.prompt('新采样配置名称（以当前配置为模板）', '', v => {
            const name = (v || '').trim(); if (!name) return;
            const base = this.sampConfigs[$('#sampSelect').value] || this.collectSampling();
            post('/api/sys/model/sampling/setting/add', { samplingConfigName: name, sampling: base }).then(r => {
                if (!r.success) { toast(r.error || '创建失败', 'error'); return; }
                toast('配置已创建', 'success');
                this.loadSampling();
                // 创建后绑定到新配置
                setTimeout(() => { $('#sampSelect').value = name; this.bindSampling(); }, 300);
            });
        });
    },

    deleteSampling() {
        const name = $('#sampSelect').value;
        if (!name) return;
        if (!confirm('删除采样配置「' + name + '」？引用它的模型绑定会一并清除。')) return;
        post('/api/sys/model/sampling/setting/delete', { samplingConfigName: name }).then(r => {
            if (r.success) { toast('已删除', 'success'); this.loadSampling(); }
            else toast(r.error || '删除失败', 'error');
        });
    }
});
