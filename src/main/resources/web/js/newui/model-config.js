/* ================= model-config.js — 模型加载配置弹窗 ================= */
'use strict';

const ModelConfig = {
    key: null, model: null,
    params: [],            // 参数定义（/api/models/param/server/list）
    values: {},            // fieldName -> 当前值
    enabled: {},           // fieldName -> 是否启用
    cmdMode: false,
    devices: [],           // [{key, label}]
    paramListLoaded: false,

    init() {
        // 移动端 TAB
        $$('#configTabs button').forEach(b => b.addEventListener('click', () => {
            $$('#configTabs button').forEach(x => x.classList.toggle('active', x === b));
            $('.config-body').dataset.active = b.dataset.ctab;
        }));
        $('#paramModeBtn').addEventListener('click', () => this.toggleCmdMode());
        $('#cfgLlama').addEventListener('change', () => { this.loadDevices(); this.refreshCmd(); });
        $('#cfgMainGpu').addEventListener('change', () => this.refreshCmd());
        $('#cfgCmd').addEventListener('input', () => { if (this.cmdMode) this.refreshPreview($('#cfgCmd').value); });
        $('#cfgExtra').addEventListener('input', () => this.refreshCmd());
        $('#cfgSaveBtn').addEventListener('click', () => this.saveConfig(false));
        $('#cfgStartBtn').addEventListener('click', () => this.startModel());
        $('#cfgVramBtn').addEventListener('click', () => this.estimateVram());
        $('#cfgSelect').addEventListener('change', () => this.applySelectedConfig());
        $('#cfgAddBtn').addEventListener('click', () => this.addConfig());
        $('#cfgDelBtn').addEventListener('click', () => this.deleteConfig());
        $('#aliasSaveBtn').addEventListener('click', () => this.saveAlias());
        $('#autoLoadChk').addEventListener('change', () => this.saveAutoLoad());
        $('#autoUnloadChk').addEventListener('change', () => this.saveAutoLoad());
        // 能力开关：互斥规则 + 即时保存
        $$('#capGrid input[data-cap]').forEach(cb => cb.addEventListener('change', () => this.saveCapabilities()));
    },

    isDesktop() { return matchMedia('(min-width: 901px)').matches; },

    async open(key) {
        this.key = key;
        const m = Models.find(key); if (!m) return;
        this.model = m;
        this.cmdMode = false;
        $('#cmdField').style.display = 'none';
        $('#paramForm').style.display = '';
        $('#extraField').style.display = '';
        $('#paramHint').textContent = '参数改动实时生成命令行';
        $('#paramModeBtn').innerHTML = '<i class="fas fa-terminal"></i> 命令行';
        $('#configTitle').textContent = (m.isLoaded ? '配置 - ' : '加载 ') + (m.alias || m.name);
        $('#cfgModelName').value = m.id;
        $('#cfgAlias').value = m.alias || '';
        $('#cfgVramResult').textContent = '';
        $('.config-body').dataset.active = 'basic';
        $$('#configTabs button').forEach((x, i) => x.classList.toggle('active', i === 0));
        // 桌面端把「高级」栏内容并入左栏
        this.layoutCols();
        UI.openSheet('#configSheet');

        // 参数定义只拉一次
        if (!this.paramListLoaded) {
            const r = await api('/api/models/param/server/list');
            this.params = r.success ? (r.params || []) : [];
            this.paramListLoaded = true;
        }
        this.renderParamForm();

        // 并行拉取：配置方案 / llamacpp 列表 / 能力 / 自动加载策略
        this.loadConfigBundle();
        this.loadLlamaList();
        this.loadCapabilities();
        this.loadAutoLoadPolicy();
    },

    layoutCols() {
        const more = $('.config-col[data-ccol="more"]');
        const basic = $('.config-col[data-ccol="basic"]');
        if (this.isDesktop()) {
            // 桌面：高级栏内容追加到左栏末尾
            Array.from(more.children).forEach(c => basic.appendChild(c));
        } else if (more.children.length === 0) {
            // 移回高级栏
            Array.from(basic.children).filter(c => c.dataset.moreCol).forEach(c => more.appendChild(c));
        }
        // 标记属于 more 的块
        if (!this._marked) {
            Array.from(more.children).forEach(c => c.dataset.moreCol = '1');
            this._marked = true;
        }
    },

    /* ---------------- 配置方案 ---------------- */
    loadConfigBundle() {
        const m = this.model;
        Models.fetchConfig(m).then(({ configs, selected }) => {
            this.configs = configs;
            const sel = $('#cfgSelect');
            const names = Object.keys(configs);
            sel.innerHTML = (names.length ? names : ['默认配置']).map(n =>
                '<option value="' + esc(n) + '"' + (n === selected ? ' selected' : '') + '>' + esc(n) + '</option>').join('');
            this.applyConfig(configs[selected] || {});
        }).catch(e => toast(e.message, 'error'));
    },

    applySelectedConfig() {
        const n = $('#cfgSelect').value;
        this.applyConfig((this.configs || {})[n] || {});
    },

    applyConfig(cfg) {
        const binPath = cfg.llamaBinPathSelect || cfg.llamaBinPath || '';
        const trySet = () => {
            const sel = $('#cfgLlama');
            if ([...sel.options].some(o => o.value === binPath)) sel.value = binPath;
        };
        this._pendingBinPath = binPath;
        this._pendingDevice = Array.isArray(cfg.device) ? cfg.device : null;
        this._pendingMg = Number.isFinite(cfg.mg) ? cfg.mg : -1;
        trySet();
        this.loadDevices().then(() => {
            if (Array.isArray(cfg.device)) this.setDeviceChecks(cfg.device);
            this.setMainGpuOptions();
            $('#cfgMainGpu').value = String(Number.isFinite(cfg.mg) ? cfg.mg : -1);
        });
        this.applyCmdToForm(cfg.cmd || '');
        this.autoFillMmproj(cfg);
        $('#cfgExtra').value = cfg.extraParams || '';
        this.refreshCmd();
    },

    /* 模型自带 mmproj 且已保存配置未显式指定时，自动把 mmproj 绝对路径填入 --mmproj 参数并启用 */
    autoFillMmproj(cfg) {
        const path = this.model && this.model.mmproj;
        if (!path) return;
        if (String(this.values['mmproj'] || '').trim()) return;
        if (/--no-mmproj\b/.test((cfg && cfg.cmd) || '')) return;
        this.values['mmproj'] = path;
        this.enabled['mmproj'] = true;
        this.syncFormFromState();
        this.refreshGroupCounts();
    },

    addConfig() {
        UI.prompt('新配置方案名称', '', v => {
            const name = (v || '').trim(); if (!name) return;
            this.saveConfig(false, name);
        });
    },

    deleteConfig() {
        const name = $('#cfgSelect').value;
        if (!name) return;
        if (!confirm('删除配置「' + name + '」？')) return;
        const body = { modelId: this.model.id, configName: name };
        const nid = Models.nodeParam(this.model); if (nid) body.nodeId = nid;
        post('/api/models/config/delete', body).then(r => {
            if (r.success) { toast('已删除', 'success'); this.loadConfigBundle(); }
            else toast(r.error || '删除失败', 'error');
        });
    },

    /* ---------------- Llama.cpp / 设备 ---------------- */
    loadLlamaList() {
        const nid = Models.nodeParam(this.model);
        api('/api/llamacpp/list' + (nid ? '?nodeId=' + encodeURIComponent(nid) : '')).then(r => {
            const items = r.data && r.data.items || [];
            $('#cfgLlama').innerHTML = items.map(it =>
                '<option value="' + esc(it.path) + '">' + esc(it.name || it.path) + '</option>').join('') || '<option value="">无可用运行库</option>';
            if (this._pendingBinPath && [...$('#cfgLlama').options].some(o => o.value === this._pendingBinPath)) {
                $('#cfgLlama').value = this._pendingBinPath;
            }
            // 列表就绪后重新探测设备并恢复配置中的勾选（消除与 applyConfig 的时序竞争）
            this.loadDevices().then(() => {
                if (this._pendingDevice) this.setDeviceChecks(this._pendingDevice);
                this.setMainGpuOptions();
                $('#cfgMainGpu').value = String(this._pendingMg != null ? this._pendingMg : -1);
            });
        });
    },

    loadDevices() {
        const bin = $('#cfgLlama').value;
        const box = $('#cfgDevices');
        if (!bin) { box.innerHTML = '<span class="muted">无可用运行库</span>'; this.devices = []; return Promise.resolve(); }
        box.innerHTML = '<span class="muted">探测设备中…</span>';
        const nid = Models.nodeParam(this.model);
        return api('/api/model/device/list?llamaBinPath=' + encodeURIComponent(bin) + (nid ? '&nodeId=' + encodeURIComponent(nid) : ''))
            .then(r => {
                const arr = r.success && r.data && r.data.devices || [];
                // 后端探测失败时会把错误文本混在设备列表里返回，过滤出来单独展示
                const valid = arr.filter(l => typeof l === 'string' && l.indexOf(':') > 0 && !/失败|failed|error/i.test(l.split(':')[0]));
                const errs = arr.filter(l => !valid.includes(l));
                this.devices = valid.map(label => ({ key: String(label).split(':')[0].trim().toLowerCase(), label }));
                if (!this.devices.length) {
                    box.innerHTML = '<span class="muted" style="word-break:break-all">' + (errs.length ? esc(errs[0]) : '未检测到设备') + '</span>';
                    this.setMainGpuOptions();
                    return;
                }
                box.innerHTML = this.devices.map((d, i) =>
                    '<label class="check-line"><input type="checkbox" data-dev="' + esc(d.key) + '" checked> ' + esc(d.label) + '</label>').join('');
                $$('#cfgDevices input[data-dev]').forEach(cb => cb.addEventListener('change', () => { this.setMainGpuOptions(); this.refreshCmd(); }));
                this.setMainGpuOptions();
            }).catch(() => { box.innerHTML = '<span class="muted">设备探测失败</span>'; });
    },

    selectedDevices() {
        // 与旧 UI 保持一致：'All' 仅作为配置/UI 状态标记，不发送给后端；
        // 全选时发送具体设备 key 列表，后端不认识 'All' 这个设备值
        return $$('#cfgDevices input[data-dev]:checked').map(cb => cb.dataset.dev);
    },

    setDeviceChecks(devArr) {
        if (!devArr.length || devArr.includes('All')) {
            $$('#cfgDevices input[data-dev]').forEach(cb => cb.checked = true);
        } else {
            $$('#cfgDevices input[data-dev]').forEach(cb => cb.checked = devArr.includes(cb.dataset.dev));
        }
    },

    setMainGpuOptions() {
        const sel = $('#cfgMainGpu');
        const cur = sel.value;
        const n = $$('#cfgDevices input[data-dev]:checked').length;
        sel.innerHTML = '<option value="-1">默认</option>' + Array.from({ length: n }, (_, i) =>
            '<option value="' + i + '">GPU ' + i + '</option>').join('');
        sel.value = [...sel.options].some(o => o.value === cur) ? cur : '-1';
    },

    /* ---------------- 动态参数表单 ---------------- */
    fieldName(p) {
        let n = (p.fullName || '').trim() || (p.abbreviation || '').trim();
        if (n) return n.replace(/^--?/, '');
        return 'unnamed_' + String(p.name || '').replace(/\W+/g, '_') + '_' + (p.sort || 0);
    },

    groups() {
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
        const box = $('#paramForm');
        box.innerHTML = this.groups().map(g => {
            const items = g.items.map(p => {
                const fn = this.fieldName(p);
                const label = I18n.t(p.name, (p.fullName || p.abbreviation || p.name));
                const flag = (p.fullName || p.abbreviation || '').trim();
                const input = this.inputHtml(p, fn);
                return '<div class="param-item disabled" data-fn="' + esc(fn) + '">' +
                    '<input type="checkbox" class="p-check">' +
                    '<div class="p-label">' + esc(label) + '<span class="p-flag">' + esc(flag) + '</span></div>' +
                    '<div class="p-input">' + input + '</div></div>';
            }).join('');
            return '<details class="param-group"' + (g.collapsed ? '' : ' open') + '><summary><span>' +
                esc(I18n.t(g.key, g.key)) + '</span><span class="cnt"></span></summary><div class="param-items">' + items + '</div></details>';
        }).join('');

        // 绑定事件
        $$('#paramForm .param-item').forEach(row => {
            const fn = row.dataset.fn;
            const chk = row.querySelector('.p-check');
            const ctl = row.querySelector('.p-input input, .p-input select');
            chk.addEventListener('change', () => {
                this.enabled[fn] = chk.checked;
                row.classList.toggle('disabled', !chk.checked);
                this.refreshGroupCounts();
                this.refreshCmd();
            });
            if (ctl) {
                const evt = ctl.tagName === 'SELECT' ? 'change' : 'input';
                ctl.addEventListener(evt, () => { this.values[fn] = ctl.type === 'checkbox' ? (ctl.checked ? '1' : '0') : ctl.value; this.refreshCmd(); });
            }
        });
        // 初始化默认值
        this.resetParamsToDefault();
        this.refreshGroupCounts();
    },

    inputHtml(p, fn) {
        const type = String(p.type || 'STRING').toUpperCase();
        const values = Array.isArray(p.values) ? p.values : [];
        if (type === 'LOGIC' || type === 'BOOLEAN') return '<span class="muted" style="font-size:12px">开关</span>';
        if (values.length) {
            return '<select>' + values.map(v => '<option value="' + esc(v) + '">' + esc(v) + '</option>').join('') + '</select>';
        }
        const ph = p.defaultValue != null ? String(p.defaultValue) : '';
        const inputType = (type === 'INTEGER' || type === 'FLOAT') ? 'number' : 'text';
        return '<input type="' + inputType + '" placeholder="' + esc(ph) + '" value="' + esc(ph) + '">';
    },

    resetParamsToDefault() {
        this.params.forEach(p => {
            const fn = this.fieldName(p);
            const type = String(p.type || '').toUpperCase();
            this.enabled[fn] = !!p.defaultEnabled;
            let v = p.defaultValue != null ? String(p.defaultValue) : '';
            if (!v && Array.isArray(p.values) && p.values.length) v = String(p.values[0]);
            if ((type === 'LOGIC' || type === 'BOOLEAN') && !v) v = '0';
            this.values[fn] = v;
        });
        this.syncFormFromState();
    },

    syncFormFromState() {
        $$('#paramForm .param-item').forEach(row => {
            const fn = row.dataset.fn;
            const chk = row.querySelector('.p-check');
            const ctl = row.querySelector('.p-input input, .p-input select');
            chk.checked = !!this.enabled[fn];
            row.classList.toggle('disabled', !chk.checked);
            if (ctl && ctl.type !== 'checkbox') ctl.value = this.values[fn] != null ? this.values[fn] : '';
        });
    },

    refreshGroupCounts() {
        $$('#paramForm .param-group').forEach(g => {
            const items = g.querySelectorAll('.param-item');
            let n = 0;
            items.forEach(row => { if (this.enabled[row.dataset.fn]) n++; });
            g.querySelector('.cnt').textContent = n ? n + ' 项启用' : '';
        });
    },

    /* ---------------- cmd 生成与解析 ---------------- */
    quoteArg(v) {
        v = String(v);
        return /[\s"']/.test(v) ? '"' + v.replace(/"/g, '\\"') + '"' : v;
    },

    buildCmd() {
        const parts = [];
        const sorted = this.params.slice().sort((a, b) => (a.sort || 0) - (b.sort || 0));
        for (const p of sorted) {
            const fn = this.fieldName(p);
            if (!this.enabled[fn]) continue;
            const fullName = (p.fullName || '').trim();
            const abbr = (p.abbreviation || '').trim();
            const type = String(p.type || 'STRING').toUpperCase();
            const v = String(this.values[fn] != null ? this.values[fn] : '').trim();
            if (!fullName && !abbr) {
                // 无名枚举参数：仅当值在 values 中才输出值本身
                if (v && Array.isArray(p.values) && p.values.some(x => String(x).trim() === v)) parts.push(this.quoteArg(v));
                continue;
            }
            const flag = fullName || abbr;
            if (type === 'LOGIC' || type === 'BOOLEAN') {
                if (/^(1|true|on|yes)$/i.test(v)) parts.push(flag);
                continue;
            }
            if (!v) continue;
            parts.push(flag, this.quoteArg(v));
        }
        return parts.join(' ');
    },

    refreshCmd() {
        if (this.cmdMode) return; // cmd 模式下以文本框为准
        this.refreshPreview(this.buildCmd());
    },

    refreshPreview(cmd) {
        $('#cmdPreview').textContent = cmd || '（无参数）';
    },

    tokenize(cmd) {
        const tokens = [];
        let cur = '', quote = null;
        for (let i = 0; i < cmd.length; i++) {
            const c = cmd[i];
            if (quote) {
                if (c === quote) { quote = null; }
                else if (c === '\\' && quote === "'" && i + 1 < cmd.length) { cur += cmd[++i]; }
                else cur += c;
            } else if (c === '"' || c === "'") { quote = c; }
            else if (/\s/.test(c)) { if (cur) { tokens.push(cur); cur = ''; } }
            else cur += c;
        }
        if (cur) tokens.push(cur);
        return tokens;
    },

    applyCmdToForm(cmd) {
        // 先全部停用
        this.params.forEach(p => { this.enabled[this.fieldName(p)] = false; });
        if (!cmd || !cmd.trim()) { this.resetParamsToDefault(); this.refreshGroupCounts(); return; }

        const lookup = {};
        this.params.forEach(p => {
            [p.fullName, p.abbreviation].forEach(n => {
                if (n && n.trim()) lookup[n.trim()] = p;
            });
        });
        const tokens = this.tokenize(cmd);
        const consumed = new Array(tokens.length).fill(false);

        for (let i = 0; i < tokens.length; i++) {
            let t = tokens[i], inlineVal = null;
            if (t.startsWith('--') && t.includes('=')) {
                const idx = t.indexOf('=');
                const left = t.slice(0, idx);
                if (lookup[left]) { inlineVal = t.slice(idx + 1); t = left; }
            }
            const p = lookup[t];
            if (!p) continue;
            const fn = this.fieldName(p);
            const type = String(p.type || 'STRING').toUpperCase();
            consumed[i] = true;
            this.enabled[fn] = true;
            if (type === 'LOGIC' || type === 'BOOLEAN') {
                this.values[fn] = '1';
            } else if (inlineVal !== null) {
                this.values[fn] = inlineVal;
            } else {
                // 取下一个非 option token 作为值
                for (let j = i + 1; j < tokens.length; j++) {
                    if (consumed[j] || lookup[tokens[j]]) break;
                    consumed[j] = true;
                    this.values[fn] = tokens[j];
                    i = j;
                    break;
                }
            }
        }
        // 无名枚举参数：未消费 token 中匹配 values
        this.params.forEach(p => {
            if ((p.fullName || '').trim() || (p.abbreviation || '').trim()) return;
            if (!Array.isArray(p.values) || !p.values.length) return;
            const fn = this.fieldName(p);
            for (let i = 0; i < tokens.length; i++) {
                if (consumed[i]) continue;
                if (p.values.some(v => String(v).trim() === tokens[i])) {
                    consumed[i] = true;
                    this.enabled[fn] = true;
                    this.values[fn] = tokens[i];
                    break;
                }
            }
        });
        // LOGIC 未启用的补默认值 '0'
        this.params.forEach(p => {
            const type = String(p.type || '').toUpperCase();
            const fn = this.fieldName(p);
            if ((type === 'LOGIC' || type === 'BOOLEAN') && this.values[fn] !== '1') this.values[fn] = '0';
        });
        // 未消费 token 归入额外参数
        const extra = tokens.filter((t, i) => !consumed[i]).map(t => this.quoteArg(t)).join(' ');
        $('#cfgExtra').value = extra;

        this.syncFormFromState();
        this.refreshGroupCounts();
    },

    toggleCmdMode() {
        this.cmdMode = !this.cmdMode;
        if (this.cmdMode) {
            $('#cfgCmd').value = this.buildCmd();
            $('#cmdField').style.display = '';
            $('#paramForm').style.display = 'none';
            $('#extraField').style.display = 'none';
            $('#paramHint').textContent = '手动编辑命令行参数';
            $('#paramModeBtn').innerHTML = '<i class="fas fa-list"></i> 表单';
            this.refreshPreview($('#cfgCmd').value);
        } else {
            $('#cmdField').style.display = 'none';
            $('#paramForm').style.display = '';
            $('#extraField').style.display = '';
            $('#paramHint').textContent = '参数改动实时生成命令行';
            $('#paramModeBtn').innerHTML = '<i class="fas fa-terminal"></i> 命令行';
            this.applyCmdToForm($('#cfgCmd').value);
            this.refreshCmd();
        }
    },

    /* ---------------- payload / 保存 / 启动 ---------------- */
    buildPayload() {
        const m = this.model;
        const cmd = this.cmdMode ? $('#cfgCmd').value.trim() : this.buildCmd();
        const extra = this.cmdMode ? '' : $('#cfgExtra').value.trim();
        const payload = {
            modelId: m.id,
            modelName: m.name,
            llamaBinPathSelect: $('#cfgLlama').value,
            enableVision: this.deriveEnableVision(),
            device: this.selectedDevices(),
            mg: parseInt($('#cfgMainGpu').value, 10),
            mode: this.cmdMode ? 'cmd' : 'form',
            cmd, extraParams: extra
        };
        if (m.isClone && m.sourceModelId) payload.sourceModelId = m.sourceModelId;
        const nid = Models.nodeParam(m); if (nid) payload.nodeId = nid;
        return payload;
    },

    /* enableVision 由 --mmproj 参数状态派生：勾选并填值 → true；
       已知模型有 mmproj 但未勾选 → false（让用户能禁用视觉）；其余 → true（保持旧默认） */
    deriveEnableVision() {
        const mmVal = String(this.values['mmproj'] || '').trim();
        const mmOn = !!this.enabled['mmproj'] && !!mmVal;
        return mmOn ? true : (this.model && this.model.mmproj ? false : true);
    },

    saveConfig(thenStart, configName) {
        const p = this.buildPayload();
        const body = {
            modelId: p.modelId,
            configName: configName || $('#cfgSelect').value || '默认配置',
            setSelected: true,
            config: {
                llamaBinPath: p.llamaBinPathSelect,
                mg: p.mg,
                cmd: p.cmd,
                extraParams: p.extraParams,
                enableVision: p.enableVision,
                device: p.device,
                mode: 'form',
                paramMode: p.mode,
                cmdLine: this.cmdMode ? p.cmd : ''
            }
        };
        const nid = Models.nodeParam(this.model); if (nid) body.nodeId = nid;
        return post('/api/models/config/set', body).then(r => {
            if (!r.success) { toast(r.error || '保存失败', 'error'); return false; }
            if (!thenStart) toast('配置已保存', 'success');
            this.loadConfigBundle();
            return true;
        });
    },

    startModel() {
        this.saveConfig(true).then(ok => {
            if (!ok) return;
            UI.closeSheet();
            Models.doLoad(this.model.id, this.buildPayload());
        });
    },

    estimateVram() {
        const btn = $('#cfgVramBtn');
        const result = $('#cfgVramResult');
        btn.disabled = true;
        post('/api/models/vram/estimate', this.buildPayload()).then(r => {
            btn.disabled = false;
            if (!r.success) { toast(r.error || '估算失败', 'error'); return; }
            const d = r.data || {};
            if (d.vram) result.textContent = '预计显存占用：' + d.vram + ' MiB';
            else if (d.message) result.textContent = d.message;
            else result.textContent = '估算完成，无返回数据';
        }).catch(e => { btn.disabled = false; toast(e.message, 'error'); });
    },

    /* ---------------- 能力 ---------------- */
    loadCapabilities() {
        const nid = Models.nodeParam(this.model);
        api('/api/models/capabilities/get?modelId=' + encodeURIComponent(this.model.id) + (nid ? '&nodeId=' + encodeURIComponent(nid) : ''))
            .then(r => {
                const d = r.data || {};
                $$('#capGrid input[data-cap]').forEach(cb => { cb.checked = !!d[cb.dataset.cap]; });
            }).catch(() => {});
    },

    saveCapabilities() {
        // 互斥规则：embedding/rerank 互斥（embedding 优先）；rerank||embedding → thinking=tools=false；vision||audio → rerank=embedding=false
        const get = n => $('#capGrid input[data-cap="' + n + '"]').checked;
        const set = (n, v) => { $('#capGrid input[data-cap="' + n + '"]').checked = v; };
        if (get('embedding') && get('rerank')) set('rerank', false);
        if (get('rerank') || get('embedding')) { set('thinking', false); set('tools', false); }
        if (get('vision') || get('audio')) { set('rerank', false); set('embedding', false); }
        const body = { modelId: this.model.id };
        ['thinking', 'tools', 'rerank', 'embedding', 'vision', 'audio'].forEach(n => body[n] = get(n));
        const nid = Models.nodeParam(this.model); if (nid) body.nodeId = nid;
        post('/api/models/capabilities/set', body).then(r => {
            if (r.success) toast('能力设置已保存', 'success');
            else toast(r.error || '保存失败', 'error');
        });
    },

    /* ---------------- 自动加载策略 ---------------- */
    loadAutoLoadPolicy() {
        const nid = Models.nodeParam(this.model);
        api('/api/auto-load/policy?modelId=' + encodeURIComponent(this.model.id) + (nid ? '&nodeId=' + encodeURIComponent(nid) : ''))
            .then(r => {
                const d = r.data || {};
                const pol = (d.policies || {})[this.model.id];
                const un = (d.autoUnload || {})[this.model.id];
                $('#autoLoadChk').checked = pol === 'allow';
                $('#autoUnloadChk').checked = un === 'allow';
            }).catch(() => {});
    },

    saveAutoLoad() {
        const nid = Models.nodeParam(this.model);
        const base = { modelId: this.model.id };
        if (nid) base.nodeId = nid;
        put('/api/auto-load/policy', Object.assign({}, base, { mode: $('#autoLoadChk').checked ? 'allow' : 'deny' }))
            .then(r => { if (!r.success) toast(r.error || '保存失败', 'error'); });
        put('/api/auto-load/policy', Object.assign({}, base, { autoUnload: $('#autoUnloadChk').checked ? 'allow' : 'deny', autoUnloadTimeoutMs: 300000 }))
            .then(r => { if (!r.success) toast(r.error || '保存失败', 'error'); });
    },

    /* ---------------- 别名 ---------------- */
    saveAlias() {
        const body = { modelId: this.model.id, alias: $('#cfgAlias').value.trim() };
        const nid = Models.nodeParam(this.model); if (nid) body.nodeId = nid;
        post('/api/models/alias/set', body).then(r => {
            if (r.success) { toast('别名已保存', 'success'); Models.load(); }
            else toast(r.error || '保存失败', 'error');
        });
    }
};
