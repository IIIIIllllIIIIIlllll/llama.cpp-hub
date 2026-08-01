/* ================= settings-nodes.js — 设置：Llama.cpp路径 / 模型路径 / HTTP代理 / 远程节点 ================= */
'use strict';

const SettingsNodes = {
    lcItems: [], mpItems: [], nodes: [], isMaster: false,
    editingNodeId: null,      // nodeSheet 编辑中的节点 ID（null=新增）
    pathMode: 'llamacpp',     // pathSheet 当前模式：'llamacpp' | 'model'
    editingPath: null,        // pathSheet 编辑中的原路径（null=新增）

    init() {
        // 节点下拉切换
        $('#lcNode').addEventListener('change', () => this.loadLlamaPaths());
        $('#mpNode').addEventListener('change', () => this.loadModelPaths());
        $('#proxyNode').addEventListener('change', () => this.loadProxy());
        // 路径弹层
        $('#lcAdd').addEventListener('click', () => this.openPathSheet('llamacpp', null));
        $('#mpAdd').addEventListener('click', () => this.openPathSheet('model', null));
        $('#pfSave').addEventListener('click', () => this.savePathSheet());
        $('#pfBrowse').addEventListener('click', () => {
            const nodeId = this.pathMode === 'llamacpp' ? $('#lcNode').value : $('#mpNode').value;
            DirBrowser.open($('#pfPath'), nodeId === 'local' ? '' : nodeId);
        });
        // 代理
        $('#proxySave').addEventListener('click', () => this.saveProxy());
        $('#proxyTest').addEventListener('click', () => this.testProxy());
        // 远程节点
        $('#nodeAdd').addEventListener('click', () => this.openNodeSheet(null));
        $('#nfSave').addEventListener('click', () => this.saveNodeSheet());
    },

    load() {
        this.fillNodeSelects();
        this.loadLlamaPaths();
        this.loadModelPaths();
        this.loadProxy();
        this.loadNodes();
    },

    /* 节点下拉：本地 + 远程节点（禁用节点不列出，与旧版一致）。Models.nodes 由 Models.load() 维护 */
    fillNodeSelects() {
        const opts = ['<option value="local">本地</option>']
            .concat(Object.keys(Models.nodes || {})
                .filter(id => (Models.nodes[id] || {}).enabled !== false)
                .map(id => '<option value="' + esc(id) + '">' + esc((Models.nodes[id] || {}).name || id) + '</option>'));
        ['#lcNode', '#mpNode', '#proxyNode'].forEach(sel => {
            const el = $(sel);
            const cur = el.value;
            el.innerHTML = opts.join('');
            if (Array.from(el.options).some(o => o.value === cur)) el.value = cur;
        });
    },

    /* 远程节点时拼 ?nodeId=（GET/POST 都拼，与旧版 appendNodeId 一致） */
    nodeQ(sel) {
        const v = $(sel).value;
        return (v && v !== 'local') ? '?nodeId=' + encodeURIComponent(v) : '';
    },

    /* ================= Llama.cpp 路径 ================= */
    loadLlamaPaths() {
        $('#lcPathList').innerHTML = '<div class="empty"><i class="fas fa-spinner fa-spin"></i> 加载中…</div>';
        api('/api/llamacpp/list' + this.nodeQ('#lcNode')).then(r => {
            if (!r || !r.success) throw new Error((r && r.error) || '加载失败');
            this.lcItems = (r.data && r.data.items) || [];
            this.renderLlamaPaths();
        }).catch(e => {
            this.lcItems = [];
            $('#lcPathList').innerHTML = '<div class="empty"><i class="fas fa-triangle-exclamation"></i>' + esc(e.message) + '</div>';
        });
    },

    renderLlamaPaths() {
        if (!this.lcItems.length) {
            $('#lcPathList').innerHTML = '<div class="empty"><i class="fas fa-folder-open"></i>尚未配置 Llama.cpp 路径</div>';
            return;
        }
        $('#lcPathList').innerHTML = this.lcItems.map((it, i) => {
            const scanned = it.source === 'scanned';
            return '<div class="set-item">' +
                '<i class="fas fa-microchip"></i>' +
                '<div class="set-main">' +
                    '<div class="set-name">' + esc(it.name || it.path) + (scanned ? ' <span class="badge">扫描</span>' : '') + '</div>' +
                    '<div class="set-sub">' + esc(it.path) + '</div>' +
                    (it.description ? '<div class="set-sub">' + esc(it.description) + '</div>' : '') +
                '</div>' +
                '<div class="set-actions">' +
                    '<button class="icon-btn" title="测试" onclick="SettingsNodes.testLlama(' + i + ')"><i class="fas fa-vial"></i></button>' +
                    (scanned ? '' : '<button class="icon-btn" title="编辑" onclick="SettingsNodes.openPathSheet(\'llamacpp\',' + i + ')"><i class="fas fa-pen"></i></button>') +
                    '<button class="icon-btn" title="删除" onclick="SettingsNodes.removeLlama(' + i + ')"><i class="fas fa-trash"></i></button>' +
                '</div></div>';
        }).join('');
    },

    testLlama(i) {
        const it = this.lcItems[i];
        if (!it) return;
        const body = { path: it.path };
        if (it.name) body.name = it.name;
        if (it.description) body.description = it.description;
        toast('正在测试 ' + (it.name || it.path) + ' …');
        post('/api/llamacpp/test' + this.nodeQ('#lcNode'), body).then(r => {
            if (!r || !r.success) { toast((r && r.error) || '测试失败', 'error'); return; }
            showMsg('测试：' + (it.name || it.path), this.fmtTestResult(r.data));
        }).catch(() => toast('网络请求失败', 'error'));
    },

    /* 测试结果：{version:{command,exitCode,output,error}, listDevices:{...}} → 纯文本 */
    fmtTestResult(d) {
        d = d || {};
        const sec = (title, c) => {
            if (!c) return '';
            let s = title + '\n命令: ' + (c.command || '') + '    退出码: ' + (c.exitCode == null ? '' : c.exitCode) + '\n';
            if (c.output) s += c.output + '\n';
            if (c.error) s += '[stderr] ' + c.error + '\n';
            return s;
        };
        const out = sec('llama-cli --version', d.version) + '\n' + sec('llama-cli --list-devices', d.listDevices);
        return out.trim() || JSON.stringify(d, null, 2);
    },

    removeLlama(i) {
        const it = this.lcItems[i];
        if (!it) return;
        // 旧版语义：删除「扫描到的」路径会物理删除磁盘目录，必须强警告
        const msg = it.source === 'scanned'
            ? '此操作将永久删除磁盘目录及其所有内容，且不可恢复：\n' + it.path + '\n\n确认删除？'
            : '确定要删除此路径吗？\n' + it.path;
        if (!confirm(msg)) return;
        post('/api/llamacpp/remove' + this.nodeQ('#lcNode'), { path: it.path }).then(r => {
            if (r && r.success) { toast('已删除', 'success'); this.loadLlamaPaths(); }
            else toast((r && r.error) || '删除失败', 'error');
        }).catch(() => toast('网络请求失败', 'error'));
    },

    /* ================= 模型路径 ================= */
    loadModelPaths() {
        $('#mpPathList').innerHTML = '<div class="empty"><i class="fas fa-spinner fa-spin"></i> 加载中…</div>';
        api('/api/model/path/list' + this.nodeQ('#mpNode')).then(r => {
            if (!r || !r.success) throw new Error((r && r.error) || '加载失败');
            this.mpItems = (r.data && r.data.items) || [];
            this.renderModelPaths();
        }).catch(e => {
            this.mpItems = [];
            $('#mpPathList').innerHTML = '<div class="empty"><i class="fas fa-triangle-exclamation"></i>' + esc(e.message) + '</div>';
        });
    },

    renderModelPaths() {
        if (!this.mpItems.length) {
            $('#mpPathList').innerHTML = '<div class="empty"><i class="fas fa-folder-open"></i>尚未配置模型路径</div>';
            return;
        }
        $('#mpPathList').innerHTML = this.mpItems.map((it, i) =>
            '<div class="set-item">' +
                '<i class="fas fa-folder-open"></i>' +
                '<div class="set-main">' +
                    '<div class="set-name">' + esc(it.name || it.path) + '</div>' +
                    '<div class="set-sub">' + esc(it.path) + '</div>' +
                    (it.description ? '<div class="set-sub">' + esc(it.description) + '</div>' : '') +
                '</div>' +
                '<div class="set-actions">' +
                    '<button class="icon-btn" title="编辑" onclick="SettingsNodes.openPathSheet(\'model\',' + i + ')"><i class="fas fa-pen"></i></button>' +
                    '<button class="icon-btn" title="删除" onclick="SettingsNodes.removeModelPath(' + i + ')"><i class="fas fa-trash"></i></button>' +
                '</div></div>').join('');
    },

    removeModelPath(i) {
        const it = this.mpItems[i];
        if (!it) return;
        if (!confirm('确定要删除此路径吗？\n' + it.path)) return;
        post('/api/model/path/remove' + this.nodeQ('#mpNode'), { path: it.path }).then(r => {
            if (r && r.success) { toast('已删除', 'success'); this.loadModelPaths(); Models.load(); }
            else toast((r && r.error) || '删除失败', 'error');
        }).catch(() => toast('网络请求失败', 'error'));
    },

    /* ================= 路径弹层（llamacpp / model 共用，新增 + 编辑） ================= */
    openPathSheet(mode, idx) {
        this.pathMode = mode;
        const it = idx == null ? null : (mode === 'llamacpp' ? this.lcItems[idx] : this.mpItems[idx]);
        this.editingPath = it ? it.path : null;
        $('#pfTitle').textContent = (it ? '编辑' : '添加') + (mode === 'llamacpp' ? ' Llama.cpp 路径' : '模型路径');
        $('#pfPath').value = it ? it.path : '';
        $('#pfName').value = it ? (it.name || '') : '';
        $('#pfDesc').value = it ? (it.description || '') : '';
        UI.openSheet('#pathSheet');
        setTimeout(() => $('#pfPath').focus(), 250);
    },

    savePathSheet() {
        const path = $('#pfPath').value.trim();
        const name = $('#pfName').value.trim();
        const desc = $('#pfDesc').value.trim();
        if (!path) { toast('路径不能为空', 'error'); return; }
        const body = { path: path };
        if (name) body.name = name;
        if (desc) body.description = desc;
        const isLc = this.pathMode === 'llamacpp';
        const q = this.nodeQ(isLc ? '#lcNode' : '#mpNode');

        let req;
        if (this.editingPath && isLc) {
            // 旧版语义：llamacpp 编辑 = 先删后加
            req = post('/api/llamacpp/remove' + q, { path: this.editingPath })
                .catch(() => ({}))
                .then(() => post('/api/llamacpp/add' + q, body));
        } else if (this.editingPath) {
            body.originalPath = this.editingPath;
            req = post('/api/model/path/update' + q, body);
        } else {
            req = post(isLc ? '/api/llamacpp/add' + q : '/api/model/path/add' + q, body);
        }
        req.then(r => {
            if (r && r.success) {
                UI.closeSheet();
                toast('已保存', 'success');
                if (isLc) this.loadLlamaPaths();
                else { this.loadModelPaths(); Models.load(); }
            } else toast((r && r.error) || '保存失败', 'error');
        }).catch(() => toast('网络请求失败', 'error'));
    },

    /* ================= HTTP 代理 ================= */
    loadProxy() {
        api('/api/proxy/get' + this.nodeQ('#proxyNode')).then(r => {
            const d = (r && r.success && r.data) || {};
            $('#proxyEnabled').checked = !!d.enabled;
            $('#proxyHost').value = d.host || '';
            $('#proxyPort').value = d.port || '';
            // 后端不返回凭据，与旧版一致留空
            $('#proxyUser').value = '';
            $('#proxyPass').value = '';
        }).catch(() => {});
    },

    proxyBody() {
        return {
            enabled: $('#proxyEnabled').checked,
            host: $('#proxyHost').value.trim(),
            port: parseInt($('#proxyPort').value, 10) || 0,
            username: $('#proxyUser').value.trim(),
            password: $('#proxyPass').value
        };
    },

    saveProxy() {
        post('/api/proxy/save' + this.nodeQ('#proxyNode'), this.proxyBody()).then(r => {
            if (r && r.success) toast('已保存', 'success');
            else toast((r && r.error) || '保存失败', 'error');
        }).catch(() => toast('网络请求失败', 'error'));
    },

    testProxy() {
        const body = this.proxyBody();
        if (body.port < 1 || body.port > 65535) { toast('代理端口必须在 1-65535 之间', 'error'); return; }
        toast('正在测试代理连接…');
        post('/api/proxy/test' + this.nodeQ('#proxyNode'), body).then(r => {
            if (r && r.success && r.data) {
                if (r.data.success) toast(r.data.message || '代理连接成功', 'success');
                else toast(r.data.message || '代理连接失败', 'error');
            } else toast((r && r.error) || '测试失败', 'error');
        }).catch(() => toast('网络请求失败', 'error'));
    },

    /* ================= 远程节点 ================= */
    loadNodes() {
        api('/api/node/info').then(r => {
            this.isMaster = !!(r && r.success && r.data && r.data.isMaster === true);
            $('#nodeMasterHint').style.display = this.isMaster ? 'none' : '';
            $('#nodeAdd').style.display = this.isMaster ? '' : 'none';
            this.renderNodes();
        }).catch(() => {});
        api('/api/node/list').then(r => {
            this.nodes = (r && r.success && Array.isArray(r.data)) ? r.data : [];
            this.renderNodes();
        }).catch(() => {
            this.nodes = [];
            $('#nodeList').innerHTML = '<div class="empty"><i class="fas fa-triangle-exclamation"></i>加载失败</div>';
        });
    },

    renderNodes() {
        if (!this.nodes.length) {
            $('#nodeList').innerHTML = '<div class="empty"><i class="fas fa-server"></i>暂无远程节点</div>';
            return;
        }
        $('#nodeList').innerHTML = this.nodes.map((n, i) => {
            const st = String(n.status || 'PENDING').toLowerCase();
            const stLabel = { online: '在线', offline: '离线', pending: '待定' }[st] || '待定';
            const tags = (n.tags || []).map(t => '<span class="badge">' + esc(t) + '</span>').join(' ');
            const actions = this.isMaster ?
                '<div class="set-actions">' +
                    '<button class="icon-btn" title="测试连接" onclick="SettingsNodes.testNode(' + i + ')"><i class="fas fa-plug"></i></button>' +
                    '<button class="icon-btn" title="编辑" onclick="SettingsNodes.openNodeSheet(' + i + ')"><i class="fas fa-pen"></i></button>' +
                    '<button class="icon-btn" title="删除" onclick="SettingsNodes.removeNode(' + i + ')"><i class="fas fa-trash"></i></button>' +
                    '<label class="check-line" style="margin:0"><input type="checkbox" ' + (n.enabled !== false ? 'checked' : '') +
                        ' onchange="SettingsNodes.toggleNode(' + i + ',this.checked)"> 启用</label>' +
                '</div>' : '';
            return '<div class="set-item">' +
                '<span class="ns-dot" style="background:var(--' + (st === 'online' ? 'success' : st === 'offline' ? 'danger' : 'text-2') + ')"></span>' +
                '<div class="set-main">' +
                    '<div class="set-name">' + esc(n.name || n.nodeId) + ' <span class="muted">(' + esc(n.nodeId) + ')</span> ' +
                        '<span class="badge">' + stLabel + '</span></div>' +
                    '<div class="set-sub">' + esc(n.baseUrl || '') + '</div>' +
                    (tags ? '<div class="set-sub">' + tags + '</div>' : '') +
                '</div>' + actions + '</div>';
        }).join('');
    },

    openNodeSheet(idx) {
        if (!this.isMaster) { toast('当前不是主节点（master），无法管理远程节点', 'error'); return; }
        const n = idx == null ? null : this.nodes[idx];
        this.editingNodeId = n ? n.nodeId : null;
        $('#nfTitle').textContent = n ? '编辑节点' : '添加节点';
        $('#nfId').value = n ? n.nodeId : '';
        $('#nfId').disabled = !!n;
        $('#nfName').value = n ? (n.name || '') : '';
        $('#nfUrl').value = n ? (n.baseUrl || '') : '';
        $('#nfKey').value = n ? (n.apiKey || '') : '';
        $('#nfTags').value = n ? (n.tags || []).join(', ') : '';
        $('#nfEnabled').checked = n ? (n.enabled !== false) : true;
        UI.openSheet('#nodeSheet');
    },

    saveNodeSheet() {
        const nodeId = $('#nfId').value.trim();
        const baseUrl = $('#nfUrl').value.trim();
        if (!nodeId) { toast('节点 ID 不能为空', 'error'); return; }
        if (!baseUrl) { toast('地址不能为空', 'error'); return; }
        if (!/^https?:\/\//i.test(baseUrl)) { toast('地址必须以 http:// 或 https:// 开头', 'error'); return; }
        const body = {
            nodeId: nodeId,
            name: $('#nfName').value.trim(),
            baseUrl: baseUrl,
            apiKey: $('#nfKey').value,
            tags: $('#nfTags').value.split(/\s*,\s*/).filter(Boolean),
            enabled: $('#nfEnabled').checked
        };
        post(this.editingNodeId ? '/api/node/update' : '/api/node/add', body).then(r => {
            if (r && r.success) {
                UI.closeSheet();
                toast('已保存', 'success');
                this.loadNodes();
                Models.load();   // 刷新 Models.nodes，下载页/模型页共用
            } else toast((r && r.error) || '保存失败', 'error');
        }).catch(() => toast('网络请求失败', 'error'));
    },

    testNode(i) {
        const n = this.nodes[i];
        if (!n) return;
        post('/api/node/test', { nodeId: n.nodeId }).then(r => {
            const d = r && r.success && r.data;
            if (d) {
                if (d.connected) toast('节点 ' + n.nodeId + ' 连接成功 · ' + (d.latency != null ? d.latency + 'ms' : '') + ' · ' + (d.version || 'unknown'), 'success');
                else toast('节点 ' + n.nodeId + ' 连接失败（' + (d.statusCode || 'timeout') + '）', 'error');
                this.loadNodes();
            } else toast((r && r.error) || '测试失败', 'error');
        }).catch(() => toast('网络请求失败', 'error'));
    },

    toggleNode(i, enabled) {
        const n = this.nodes[i];
        if (!n) return;
        post('/api/node/update', { nodeId: n.nodeId, enabled: enabled }).then(r => {
            if (r && r.success) { this.loadNodes(); Models.load(); }
            else { toast((r && r.error) || '保存失败', 'error'); this.loadNodes(); }
        }).catch(() => { toast('网络请求失败', 'error'); this.loadNodes(); });
    },

    removeNode(i) {
        const n = this.nodes[i];
        if (!n) return;
        if (!confirm('确认删除节点 "' + n.nodeId + '"？')) return;
        post('/api/node/remove', { nodeId: n.nodeId }).then(r => {
            if (r && r.success) {
                toast('已删除', 'success');
                this.loadNodes();
                Models.load();
            } else toast((r && r.error) || '删除失败', 'error');
        }).catch(() => toast('网络请求失败', 'error'));
    }
};

/* 通用文本结果弹层（llama.cpp 测试等） */
function showMsg(title, text) {
    $('#msgTitle').textContent = title;
    $('#msgPre').textContent = text;
    UI.openSheet('#msgSheet');
}
