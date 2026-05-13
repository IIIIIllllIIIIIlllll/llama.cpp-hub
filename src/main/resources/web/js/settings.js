(function () {
    const byId = (id) => document.getElementById(id);

    function toast(title, msg, type) {
        if (typeof window.showToast === 'function') window.showToast(title, msg, type);
    }

    function t(key, fallback) {
        if (typeof window.t === 'function') return window.t(key, fallback);
        return fallback || key;
    }

    function switchTab(tabName) {
        document.querySelectorAll('.settings-tab').forEach(btn => {
            btn.classList.toggle('active', btn.getAttribute('data-tab') === tabName);
        });
        document.querySelectorAll('.settings-tab-panel').forEach(panel => {
            panel.classList.toggle('active', panel.getAttribute('data-tab-panel') === tabName);
        });
    }

    async function loadSettings() {
        try {
            const resp = await fetch('/api/sys/setting', { method: 'GET' });
            const result = await resp.json();
            if (!result || !result.success || !result.data) return;
            populate(result.data);
        } catch (e) {
        }
    }

    let _populating = false;

    function populate(data) {
        _populating = true;
        // Server
        const s = data.server;
        if (s) {
            const webPort = byId('webPortInput');
            if (webPort && s.webPort) webPort.value = s.webPort;
        }

        // Compatibility
        const c = data.compat;
        if (c) {
            const ollamaToggle = byId('toggleOllamaCompat');
            if (ollamaToggle && c.ollama) ollamaToggle.checked = !!c.ollama.enabled;
            const ollamaPort = byId('ollamaCompatPortInput');
            if (ollamaPort && c.ollama && c.ollama.port) ollamaPort.value = c.ollama.port;

            const lmstudioToggle = byId('toggleLmstudioCompat');
            if (lmstudioToggle && c.lmstudio) lmstudioToggle.checked = !!c.lmstudio.enabled;
            const lmstudioPort = byId('lmstudioCompatPortInput');
            if (lmstudioPort && c.lmstudio && c.lmstudio.port) lmstudioPort.value = c.lmstudio.port;

            const mcpToggle = byId('toggleMcpServer');
            if (mcpToggle && c.mcpServer) mcpToggle.checked = !!c.mcpServer.enabled;
        }

        // Security
        const sec = data.security;
        if (sec) {
            const apiKeyToggle = byId('toggleApiKeyEnabled');
            if (apiKeyToggle) apiKeyToggle.checked = !!sec.apiKeyEnabled;
            const apiKey = byId('apiKeyInput');
            if (apiKey && sec.apiKey) apiKey.value = sec.apiKey;
            updateApiKeyInputState();
        }

        // HTTPS
        const https = data.https;
        if (https) {
            const httpsToggle = byId('toggleHttpsEnabled');
            if (httpsToggle) httpsToggle.checked = !!https.enabled;
            const certPath = byId('httpsCertPathInput');
            if (certPath && https.keystorePath) certPath.value = https.keystorePath;
            const password = byId('httpsPasswordInput');
            if (password && https.keystorePassword) password.value = https.keystorePassword;
            updateHttpsInputState();
        }

        // Logging
        const log = data.logging;
        if (log) {
            const url = byId('toggleLogRequestUrl');
            if (url) url.checked = !!log.logRequestUrl;
            const header = byId('toggleLogRequestHeader');
            if (header) header.checked = !!log.logRequestHeader;
            const body = byId('toggleLogRequestBody');
            if (body) body.checked = !!log.logRequestBody;
        }

        // Download
        const dl = data.download;
        if (dl) {
            const dir = byId('downloadDirInput');
            if (dir && dl.directory) dir.value = dl.directory;
        }
        _populating = false;
    }

    // API Key toggle — enable/disable the input field
    function updateApiKeyInputState() {
        const toggle = byId('toggleApiKeyEnabled');
        const input = byId('apiKeyInput');
        if (toggle && input) {
            input.disabled = !toggle.checked;
        }
    }

    // HTTPS toggle — enable/disable the fields
    function updateHttpsInputState() {
        const toggle = byId('toggleHttpsEnabled');
        const pathInput = byId('httpsCertPathInput');
        const passInput = byId('httpsPasswordInput');
        if (toggle && pathInput && passInput) {
            pathInput.disabled = !toggle.checked;
            passInput.disabled = !toggle.checked;
        }
    }

    async function saveServerPorts() {
        const webPort = byId('webPortInput');
        const payload = {};
        if (webPort && webPort.value) payload.webPort = Number(webPort.value);
        if (!payload.webPort) {
            toast(t('toast.error', '错误'), '请填写端口', 'error');
            return;
        }
        try {
            const resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存'), 'success');
            loadSettings();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function saveCompatPorts() {
        const ollamaPort = byId('ollamaCompatPortInput');
        const lmstudioPort = byId('lmstudioCompatPortInput');
        const payload = {};
        if (ollamaPort && ollamaPort.value) payload.ollamaPort = Number(ollamaPort.value);
        if (lmstudioPort && lmstudioPort.value) payload.lmstudioPort = Number(lmstudioPort.value);
        if (!payload.ollamaPort && !payload.lmstudioPort) {
            toast(t('toast.error', '错误'), '请至少填写一个端口', 'error');
            return;
        }
        try {
            const resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存'), 'success');
            loadSettings();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function setCompatService(type, enable, toggleEl) {
        const endpoint = type === 'ollama' ? '/api/sys/ollama' : '/api/sys/lmstudio';
        const prev = !enable;
        if (toggleEl) toggleEl.disabled = true;
        try {
            const body = { enable: !!enable };
            const portInput = type === 'ollama' ? byId('ollamaCompatPortInput') : byId('lmstudioCompatPortInput');
            if (portInput && portInput.value) body.port = Number(portInput.value);
            const resp = await fetch(endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            const data = await resp.json();
            if (!data || !data.success) {
                if (toggleEl) toggleEl.checked = prev;
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.operation_failed', '操作失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), enable ? t('common.enabled', '已开启') : t('common.disabled', '已关闭'), 'success');
            loadSettings();
        } catch (e) {
            if (toggleEl) toggleEl.checked = prev;
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        } finally {
            if (toggleEl) toggleEl.disabled = false;
        }
    }

    async function setMcpServer(enable) {
        const toggleEl = byId('toggleMcpServer');
        const prev = !enable;
        if (toggleEl) toggleEl.disabled = true;
        try {
            const resp = await fetch('/api/sys/mcp', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enable: enable })
            });
            const data = await resp.json();
            if (!data || !data.success) {
                if (toggleEl) toggleEl.checked = prev;
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.operation_failed', '操作失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), enable ? t('common.enabled', '已开启') : t('common.disabled', '已关闭'), 'success');
        } catch (e) {
            if (toggleEl) toggleEl.checked = prev;
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        } finally {
            if (toggleEl) toggleEl.disabled = false;
        }
    }

    async function saveSecurity() {
        const toggle = byId('toggleApiKeyEnabled');
        const keyInput = byId('apiKeyInput');
        const payload = {};
        if (toggle) payload.apiKeyEnabled = toggle.checked;
        if (keyInput && keyInput.value) payload.apiKey = keyInput.value;
        try {
            const resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存'), 'success');
            loadSettings();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function saveHttps() {
        const toggle = byId('toggleHttpsEnabled');
        const certPath = byId('httpsCertPathInput');
        const password = byId('httpsPasswordInput');
        const payload = {};
        if (toggle) payload.httpsEnabled = toggle.checked;
        if (certPath && certPath.value) payload.httpsCertPath = certPath.value;
        if (password && password.value) payload.httpsPassword = password.value;
        try {
            const resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存'), 'success');
            loadSettings();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function loadHttpsSetupGuide() {
        const container = byId('httpsSetupDoc');
        if (!container) return;
        try {
            const lang = (window.I18N && window.I18N.lang) || 'zh-CN';
            const docPath = lang.startsWith('en') ? 'docs/HTTPS_SETUP.en.md' : 'docs/HTTPS_SETUP.zh.md';
            const resp = await fetch(docPath);
            if (!resp.ok) { container.textContent = 'Failed to load setup guide'; return; }
            const md = await resp.text();
            if (typeof marked !== 'undefined' && typeof marked.parse === 'function') {
                container.innerHTML = marked.parse(md);
            } else {
                container.textContent = md;
            }
        } catch (e) {
            container.textContent = 'Failed to load setup guide';
        }
    }

    async function saveLogging() {
        const url = byId('toggleLogRequestUrl');
        const header = byId('toggleLogRequestHeader');
        const body = byId('toggleLogRequestBody');
        const payload = {};
        if (url) payload.logRequestUrl = url.checked;
        if (header) payload.logRequestHeader = header.checked;
        if (body) payload.logRequestBody = body.checked;
        try {
            const resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存'), 'success');
            loadSettings();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function saveDownload() {
        const dir = byId('downloadDirInput');
        if (!dir || !dir.value.trim()) {
            toast(t('toast.error', '错误'), '请填写下载目录路径', 'error');
            return;
        }
        try {
            const resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ downloadDirectory: dir.value.trim() })
            });
            const data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存'), 'success');
            loadSettings();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    // --- Node management ---
    let _editingNodeId = null;
    let _isMaster = false;

    function escHtml(s) {
        if (!s) return '';
        return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }

    async function loadNodes() {
        const listEl = byId('nodeList');
        const emptyEl = byId('nodeEmptyState');
        const bannerEl = byId('nodeMasterBanner');
        const toolbarEl = byId('nodeToolbar');
        if (!listEl) return;
        try {
            var infoResp = await fetch('/api/node/info', { method: 'GET' });
            var infoResult = await infoResp.json();
            _isMaster = infoResult && infoResult.success && infoResult.data && infoResult.data.isMaster === true;

            if (bannerEl) bannerEl.style.display = _isMaster ? 'none' : '';
            if (toolbarEl) toolbarEl.style.display = _isMaster ? '' : 'none';
            if (emptyEl) emptyEl.style.display = 'none';

            if (!_isMaster) {
                listEl.innerHTML = '';
                return;
            }

            const resp = await fetch('/api/node/list', { method: 'GET' });
            const result = await resp.json();
            if (!result || !result.success) {
                listEl.innerHTML = '';
                if (emptyEl) emptyEl.style.display = '';
                return;
            }
            const nodes = result.data || [];
            listEl.innerHTML = nodes.map(function (n) { return buildNodeRow(n, _isMaster); }).join('');
            if (emptyEl) emptyEl.style.display = nodes.length === 0 ? '' : 'none';
        } catch (e) {
            listEl.innerHTML = '<div style="color:red;padding:1rem;text-align:center;">加载失败</div>';
        }
    }

    function buildNodeRow(node, isMaster) {
        const statusClass = (node.status || 'PENDING').toLowerCase();
        const statusLabels = { online: t('page.settings.nodes.status.online', '在线'), offline: t('page.settings.nodes.status.offline', '离线'), pending: t('page.settings.nodes.status.pending', '待定') };
        const statusLabel = statusLabels[statusClass] || t('page.settings.nodes.status.pending', '待定');
        const tagsHtml = (node.tags || []).map(function (t) { return '<span class="node-tag">' + escHtml(t) + '</span>'; }).join('');
        var nid = escHtml(node.nodeId);
        var actionsHtml = '';
        if (isMaster) {
            actionsHtml = '<div class="node-row-actions">'
                + '<button class="btn btn-sm btn-secondary" onclick="SettingsPage.testNode(\'' + nid + '\',this)" title="测试连通性"><i class="fas fa-plug"></i></button>'
                + '<button class="btn btn-sm btn-secondary" onclick="SettingsPage.editNode(\'' + nid + '\')" title="编辑"><i class="fas fa-edit"></i></button>'
                + '<button class="btn btn-sm btn-danger" onclick="SettingsPage.removeNode(\'' + nid + '\')" title="删除"><i class="fas fa-trash"></i></button>'
                + '<label class="node-enabled-toggle" title="' + t('common.enable', '启用') + '">'
                + '<span class="node-enabled-text">' + t('common.enable', '启用') + '</span>'
                + '<input class="node-enabled-checkbox" type="checkbox" ' + (node.enabled ? 'checked' : '') + ' onchange="SettingsPage.toggleNode(\'' + nid + '\',this.checked)">'
                + '<span class="node-enabled-switch" aria-hidden="true"></span>'
                + '</label>'
                + '</div>';
        }
        return '<div class="node-card">'
            + '<div class="node-card-main">'
            + '<div class="node-card-header">'
            + '<span class="status-dot ' + statusClass + '" title="' + statusLabel + '"></span>'
            + '<div class="node-card-title-group">'
            + '<div class="node-card-title">' + escHtml(node.name || node.nodeId) + ' <span class="node-card-id">(' + nid + ')</span></div>'
            + '<div class="node-card-url">' + escHtml(node.baseUrl) + '</div>'
            + '</div>'
            + '<span class="node-status-badge ' + statusClass + '">' + statusLabel + '</span>'
            + '</div>'
            + (tagsHtml ? ('<div class="node-card-tags">' + tagsHtml + '</div>') : '')
            + '</div>'
            + actionsHtml + '</div>';
    }

    function openNodeForm(data) {
        _editingNodeId = null;
        var titleEl = byId('nodeFormTitle');
        if (titleEl) titleEl.textContent = t('modal.node.add_title', '添加节点');
        byId('nodeFormId').value = '';
        byId('nodeFormId').disabled = false;
        byId('nodeFormName').value = '';
        byId('nodeFormUrl').value = '';
        byId('nodeFormKey').value = '';
        byId('nodeFormTags').value = '';
        byId('nodeFormEnabled').checked = true;
        if (data) {
            _editingNodeId = data.nodeId;
            if (titleEl) titleEl.textContent = t('modal.node.edit_title', '编辑节点');
            byId('nodeFormId').value = data.nodeId;
            byId('nodeFormId').disabled = true;
            byId('nodeFormName').value = data.name || '';
            byId('nodeFormUrl').value = data.baseUrl || '';
            byId('nodeFormKey').value = data.apiKey || '';
            byId('nodeFormTags').value = (data.tags || []).join(', ');
            byId('nodeFormEnabled').checked = data.enabled !== false;
        }
        var m = document.getElementById('nodeFormModal');
        if (m) m.classList.add('show');
    }

    async function saveNodeForm() {
        var nodeId = byId('nodeFormId').value.trim();
        var name = byId('nodeFormName').value.trim();
        var baseUrl = byId('nodeFormUrl').value.trim();
        var apiKey = byId('nodeFormKey').value;
        var tagsStr = byId('nodeFormTags').value.trim();
        var enabled = byId('nodeFormEnabled').checked;

        if (!nodeId) { toast(t('toast.error', '错误'), t('modal.node.error.id_required', '节点 ID 不能为空'), 'error'); return; }
        if (!baseUrl) { toast(t('toast.error', '错误'), t('modal.node.error.url_required', '地址不能为空'), 'error'); return; }
        if (baseUrl.indexOf('http://') !== 0 && baseUrl.indexOf('https://') !== 0) {
            toast(t('toast.error', '错误'), '地址必须以 http:// 或 https:// 开头', 'error'); return;
        }

        var tags = tagsStr ? tagsStr.split(/\s*,\s*/).filter(Boolean) : [];
        var payload = { nodeId: nodeId, name: name, baseUrl: baseUrl, apiKey: apiKey, tags: tags, enabled: enabled };
        var isEdit = !!_editingNodeId;
        var endpoint = isEdit ? '/api/node/update' : '/api/node/add';

        try {
            var resp = await fetch(endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            var result = await resp.json();
            if (!result || !result.success) {
                toast(t('toast.error', '错误'), (result && result.error) ? result.error : (isEdit ? t('modal.node.error.update_failed', '更新失败') : t('modal.node.error.add_failed', '添加失败')), 'error');
                return;
            }
            toast(t('toast.success', '成功'), isEdit ? t('modal.node.updated', '已更新') : t('modal.node.added', '已添加'), 'success');
            closeModal('nodeFormModal');
            loadNodes();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function editNode(nodeId) {
        try {
            var resp = await fetch('/api/node/list', { method: 'GET' });
            var result = await resp.json();
            if (result && result.success) {
                var nodes = result.data || [];
                for (var i = 0; i < nodes.length; i++) {
                    if (nodes[i].nodeId === nodeId) { openNodeForm(nodes[i]); return; }
                }
            }
            toast(t('toast.error', '错误'), t('modal.node.error.not_found', '节点不存在'), 'error');
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function removeNode(nodeId) {
        if (!confirm(t('confirm.node.delete', '确认删除节点 "' + nodeId + '" ？').replace('{nodeId}', nodeId))) return;
        try {
            var resp = await fetch('/api/node/remove', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ nodeId: nodeId })
            });
            var result = await resp.json();
            if (!result || !result.success) {
                toast(t('toast.error', '错误'), (result && result.error) ? result.error : t('modal.node.error.delete_failed', '删除失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), '已删除', 'success');
            loadNodes();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function testNode(nodeId, btn) {
        if (btn) btn.disabled = true;
        try {
            var resp = await fetch('/api/node/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ nodeId: nodeId })
            });
            var result = await resp.json();
            if (result && result.success && result.data) {
                var d = result.data;
                if (d.connected) {
                    toast(t('toast.success', '成功'), nodeId + ' 连通成功 (' + d.latency + 'ms) 版本: ' + (d.version || '未知'), 'success');
                } else {
                    toast(t('toast.error', '错误'), nodeId + ' 连接失败 (' + (d.statusCode || '超时') + ')', 'error');
                }
            } else {
                toast(t('toast.error', '错误'), '测试失败', 'error');
            }
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        } finally {
            if (btn) btn.disabled = false;
        }
    }

    async function toggleNode(nodeId, enabled) {
        try {
            await fetch('/api/node/update', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ nodeId: nodeId, enabled: enabled })
            });
            loadNodes();
        } catch (e) {}
    }

    async function checkUpdate() {
        var loadingEl = byId('updateLoadingArea');
        var resultEl = byId('updateResultArea');
        var errorEl = byId('updateErrorArea');
        var progressEl = byId('updateProgressArea');
        var btn = byId('checkUpdateBtn');

        if (loadingEl) loadingEl.style.display = '';
        if (resultEl) resultEl.style.display = 'none';
        if (errorEl) errorEl.style.display = 'none';
        if (progressEl) progressEl.style.display = 'none';
        if (btn) btn.disabled = true;

        try {
            var resp = await fetch('/api/sys/update/check', { method: 'GET' });
            var result = await resp.json();

            if (loadingEl) loadingEl.style.display = 'none';

            if (!result || !result.success) {
                if (errorEl) {
                    errorEl.style.display = '';
                    errorEl.textContent = (result && result.error) ? result.error : t('page.settings.update.status.check_failed', '检查失败');
                }
                return;
            }

            var data = result.data || {};
            var currentTag = data.currentTag || '-';
            var isPlaceholder = !currentTag || currentTag === '{tag}' || currentTag.indexOf('{tag}') >= 0;
            var currentVerEl = byId('updateCurrentVersion');
            if (currentVerEl) currentVerEl.textContent = isPlaceholder ? t('page.settings.update.self_compiled', '自编译版本') : currentTag;

            if (data.error) {
                if (errorEl) {
                    errorEl.style.display = '';
                    errorEl.textContent = data.error;
                }
                return;
            }

            if (resultEl) resultEl.style.display = '';

            var release = data.release;
            var latestVerEl = byId('updateLatestVersion');
            if (latestVerEl) latestVerEl.textContent = release ? (release.tag_name || '-') : '-';

            var publishedAtEl = byId('updatePublishedAt');
            if (publishedAtEl) {
                if (release && release.published_at) {
                    try {
                        var d = new Date(release.published_at);
                        publishedAtEl.textContent = d.toLocaleString();
                    } catch (e) {
                        publishedAtEl.textContent = release.published_at;
                    }
                } else {
                    publishedAtEl.textContent = '-';
                }
            }

            var badgeEl = byId('updateStatusBadge');
            if (badgeEl) {
                if (data.hasUpdate) {
                    badgeEl.textContent = t('page.settings.update.status.update_available', '发现新版本');
                    badgeEl.className = 'update-status-badge update-available';
                } else {
                    badgeEl.textContent = t('page.settings.update.status.up_to_date', '已是最新版本');
                    badgeEl.className = 'update-status-badge update-up-to-date';
                }
            }

            var releaseUrl = byId('updateReleaseUrl');
            if (releaseUrl && release && release.html_url) {
                releaseUrl.href = release.html_url;
                releaseUrl.style.display = '';
            } else if (releaseUrl) {
                releaseUrl.style.display = 'none';
            }

            var bodyEl = byId('updateReleaseBody');
            if (bodyEl && release && release.body) {
                var bodyText = release.body;
                if (bodyText.length > 2000) bodyText = bodyText.substring(0, 2000) + '...';
                bodyEl.textContent = bodyText;
            } else if (bodyEl) {
                bodyEl.textContent = '-';
            }

            var downloadBtn = byId('updateDownloadBtn');
            var applyBtn = byId('updateApplyBtn');
            if (downloadBtn) downloadBtn.style.display = 'none';
            if (applyBtn) applyBtn.style.display = 'none';

            var statusResp = await fetch('/api/sys/update/status', { method: 'GET' });
            var statusResult = await statusResp.json();
            var sd = statusResult && statusResult.success ? (statusResult.data || {}) : {};

            if (sd.status === 'ready' && applyBtn) {
                applyBtn.style.display = 'inline-flex';
                if (sd.pendingVersion) {
                    applyBtn.innerHTML = '<i class="fas fa-check"></i> 应用 ' + sd.pendingVersion;
                } else {
                    applyBtn.innerHTML = '<i class="fas fa-check"></i> 应用更新';
                }
            }

            if (!isPlaceholder && data.hasUpdate && release && sd.status !== 'downloading' && sd.status !== 'applying') {
                if (downloadBtn) {
                    downloadBtn.style.display = 'inline-flex';
                    downloadBtn.dataset.tagName = release.tag_name;
                    downloadBtn.disabled = false;
                    downloadBtn.innerHTML = '<i class="fas fa-download"></i> 下载 ' + release.tag_name;
                }
            }

        } catch (e) {
            if (loadingEl) loadingEl.style.display = 'none';
            if (errorEl) {
                errorEl.style.display = '';
                errorEl.textContent = t('common.network_request_failed', '网络请求失败');
            }
        } finally {
            if (btn) btn.disabled = false;
        }
    }

    async function downloadUpdate() {
        var downloadBtn = byId('updateDownloadBtn');
        var applyBtn = byId('updateApplyBtn');
        var errorEl = byId('updateErrorArea');
        var progressEl = byId('updateProgressArea');
        var resultEl = byId('updateResultArea');
        var tagName = downloadBtn ? (downloadBtn.dataset.tagName || '') : '';
        if (!tagName || tagName === '{tag}') {
            if (errorEl) {
                errorEl.style.display = '';
                errorEl.textContent = '当前版本不支持自动更新';
            }
            return;
        }
        var zipFileName = 'llama.cpp-hub-' + tagName + '.zip';
        var downloadUrl = 'https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub/releases/download/' + tagName + '/' + zipFileName;

        if (downloadBtn) downloadBtn.disabled = true;
        if (applyBtn) applyBtn.style.display = 'none';
        if (errorEl) errorEl.style.display = 'none';

        try {
            var resp = await fetch('/api/sys/update/download', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ url: downloadUrl, version: tagName })
            });
            var result = await resp.json();
            if (!result || !result.success) {
                if (errorEl) {
                    errorEl.style.display = '';
                    errorEl.textContent = (result && result.error) ? result.error : '下载失败';
                }
                if (downloadBtn) downloadBtn.disabled = false;
                return;
            }
            if (downloadBtn) downloadBtn.style.display = 'none';
            if (applyBtn) {
                applyBtn.style.display = 'inline-flex';
                applyBtn.innerHTML = '<i class="fas fa-check"></i> 应用 ' + tagName;
            }
            if (resultEl) resultEl.style.display = '';
        } catch (e) {
            if (errorEl) {
                errorEl.style.display = '';
                errorEl.textContent = '网络请求失败';
            }
            if (downloadBtn) downloadBtn.disabled = false;
        }
    }

    async function applyUpdate() {
        var applyBtn = byId('updateApplyBtn');
        var errorEl = byId('updateErrorArea');
        if (applyBtn) applyBtn.disabled = true;
        if (errorEl) errorEl.style.display = 'none';

        try {
            var resp = await fetch('/api/sys/update/apply', {
                method: 'POST'
            });
            var result = await resp.json();
            if (!result || !result.success) {
                if (errorEl) {
                    errorEl.style.display = '';
                    errorEl.textContent = (result && result.error) ? result.error : '应用更新失败';
                }
                if (applyBtn) applyBtn.disabled = false;
                return;
            }
            if (applyBtn) applyBtn.style.display = 'none';
            showToast('更新成功', result.data ? (result.data.message || '更新已应用，请重启程序生效') : '更新已应用，请重启程序生效', 'success');
        } catch (e) {
            if (errorEl) {
                errorEl.style.display = '';
                errorEl.textContent = '网络请求失败';
            }
            if (applyBtn) applyBtn.disabled = false;
        }
    }

    async function cancelUpdateDownload() {
        try {
            var resp = await fetch('/api/sys/update/cancel', {
                method: 'POST'
            });
            var result = await resp.json();
            if (result && result.success) {
                checkUpdate();
            }
        } catch (e) {}
    }

    async function restoreUpdateStatus() {
        try {
            var statusResp = await fetch('/api/sys/update/status', { method: 'GET' });
            var statusResult = await statusResp.json();
            if (!statusResult || !statusResult.success) return;
            var sd = statusResult.data;
            var applyBtn = byId('updateApplyBtn');
            var downloadBtn = byId('updateDownloadBtn');
            var resultEl = byId('updateResultArea');
            var errorEl = byId('updateErrorArea');
            var currentVerEl = byId('updateCurrentVersion');
            if (currentVerEl) currentVerEl.textContent = sd.currentVersion || '-';
            if (sd.status === 'ready' && applyBtn) {
                if (resultEl) resultEl.style.display = '';
                applyBtn.style.display = 'inline-flex';
                if (sd.pendingVersion) {
                    applyBtn.innerHTML = '<i class="fas fa-check"></i> 应用 ' + sd.pendingVersion;
                } else {
                    applyBtn.innerHTML = '<i class="fas fa-check"></i> 应用更新';
                }
                if (downloadBtn) downloadBtn.style.display = 'none';
                if (errorEl) errorEl.style.display = 'none';
            } else if (sd.status === 'downloading' || sd.status === 'applying') {
                if (downloadBtn) {
                    downloadBtn.style.display = 'inline-flex';
                    downloadBtn.disabled = true;
                }
                if (applyBtn) applyBtn.style.display = 'none';
            } else {
                if (downloadBtn) {
                    downloadBtn.style.display = 'none';
                    downloadBtn.disabled = false;
                }
                if (applyBtn) applyBtn.style.display = 'none';
            }
        } catch (e) {}
    }

    function init() {
        // Tab switching
        document.querySelectorAll('.settings-tab').forEach(tab => {
            tab.addEventListener('click', function () {
                var tabName = this.getAttribute('data-tab');
                switchTab(tabName);
                if (tabName === 'update') {
                    restoreUpdateStatus();
                }
            });
        });

        // Server tab
        const saveServerBtn = byId('saveServerPortsBtn');
        if (saveServerBtn) saveServerBtn.addEventListener('click', saveServerPorts);

        // Compatibility tab
        const saveCompatBtn = byId('saveCompatPortsBtn');
        if (saveCompatBtn) saveCompatBtn.addEventListener('click', saveCompatPorts);

        const ollamaToggle = byId('toggleOllamaCompat');
        if (ollamaToggle) ollamaToggle.addEventListener('change', () => { if (!_populating) setCompatService('ollama', ollamaToggle.checked, ollamaToggle); });

        const lmstudioToggle = byId('toggleLmstudioCompat');
        if (lmstudioToggle) lmstudioToggle.addEventListener('change', () => { if (!_populating) setCompatService('lmstudio', lmstudioToggle.checked, lmstudioToggle); });

        const mcpToggle = byId('toggleMcpServer');
        if (mcpToggle) mcpToggle.addEventListener('change', () => { if (!_populating) setMcpServer(mcpToggle.checked); });

        // Security tab
        const saveSecurityBtn = byId('saveSecurityBtn');
        if (saveSecurityBtn) saveSecurityBtn.addEventListener('click', saveSecurity);

        const apiKeyToggle = byId('toggleApiKeyEnabled');
        if (apiKeyToggle) apiKeyToggle.addEventListener('change', updateApiKeyInputState);

        // HTTPS tab
        const saveHttpsBtn = byId('saveHttpsBtn');
        if (saveHttpsBtn) saveHttpsBtn.addEventListener('click', saveHttps);

        const httpsToggle = byId('toggleHttpsEnabled');
        if (httpsToggle) httpsToggle.addEventListener('change', updateHttpsInputState);

        loadHttpsSetupGuide();

        // Logging tab
        const saveLoggingBtn = byId('saveLoggingBtn');
        if (saveLoggingBtn) saveLoggingBtn.addEventListener('click', saveLogging);

        // Download tab
        const saveDownloadBtn = byId('saveDownloadBtn');
        if (saveDownloadBtn) saveDownloadBtn.addEventListener('click', saveDownload);

        // Nodes tab
        const nodesTab = document.querySelector('.settings-tab[data-tab="nodes"]');
        if (nodesTab) nodesTab.addEventListener('click', loadNodes);

        // Update tab
        const checkBtn = byId('checkUpdateBtn');
        if (checkBtn) checkBtn.addEventListener('click', checkUpdate);
    }

    let _initialized = false;
    function load() {
        if (!_initialized) {
            init();
            _initialized = true;
        }
        loadSettings();
        loadNodes();
    }

    document.addEventListener('DOMContentLoaded', function () {
        init();
        _initialized = true;
    });

    window.downloadUpdate = downloadUpdate;
    window.applyUpdate = applyUpdate;
    window.cancelUpdateDownload = cancelUpdateDownload;
    window.SettingsPage = { init, load, openNodeForm, saveNodeForm, editNode, removeNode, testNode, toggleNode };
})();
