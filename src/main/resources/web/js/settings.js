(function () {
    const byId = (id) => document.getElementById(id);

    let llamaCppItems = [];
    let editingLlamaCppPath = null;
    let modelPathItems = [];
    let editingModelPath = null;

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

    function switchUpdateSubTab(subName) {
        document.querySelectorAll('.update-sub-tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.getAttribute('data-sub-tab') === subName);
        });
        document.querySelectorAll('.update-sub-panel').forEach(panel => {
            panel.classList.toggle('active', panel.getAttribute('data-sub-panel') === subName);
        });
        if (subName === 'llamacpp-download' && !_llamacppLoaded) {
            loadLlamaCppReleases();
        }
    }

    let _llamacppLoaded = false;
    let _llamacppReleaseData = null;
    let _llamacppDownloading = {};
    let _llamacppLocalBackends = [];
    let _llamacppLocalCudarts = [];

    function escapeHtml(s) {
        if (!s) return '';
        return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }

    /**
     * Get the directory name created after extracting a release archive.
     * The ZIP is extracted into llamacpp/, keeping its top-level directory name.
     */
    function populateSettingsNodeSelect(selectId) {
        var sel = document.getElementById(selectId);
        if (!sel) return;
        while (sel.options.length > 1) sel.remove(1);
        getNodeList()
            .then(function (data) {
                var nodes = data && data.data ? data.data : [];
                nodes.forEach(function (n) {
                    if (n && n.nodeId && n.nodeId !== 'local' && n.enabled !== false) {
                        var opt = document.createElement('option');
                        opt.value = n.nodeId;
                        opt.textContent = n.name || n.nodeId;
                        sel.appendChild(opt);
                    }
                });
            })
            .catch(function () {});
    }

    function getLlamaCppNodeId() {
        var sel = document.getElementById('llamacppNodeSelect');
        return sel ? sel.value : 'local';
    }
    function getModelPathNodeId() {
        var sel = document.getElementById('modelPathNodeSelect');
        return sel ? sel.value : 'local';
    }
    function getProxyNodeId() {
        var sel = document.getElementById('proxyNodeSelect');
        return sel ? sel.value : 'local';
    }

    function appendNodeId(url, nodeId) {
        if (nodeId && nodeId !== 'local') {
            url += (url.indexOf('?') === -1 ? '?' : '&') + 'nodeId=' + encodeURIComponent(nodeId);
        }
        return url;
    }

    function parseLlamaCppBackendName(fileName) {
        if (!fileName) return '';
        var base = fileName;
        var dotIdx = base.lastIndexOf('.');
        if (dotIdx > 0) base = base.substring(0, dotIdx);
        if (base.endsWith('.tar')) {
            dotIdx = base.lastIndexOf('.');
            if (dotIdx > 0) base = base.substring(0, dotIdx);
        }
        return base;
    }

    async function loadLlamaCppReleases() {
        var loadingEl = byId('llamacppLoadingArea');
        var errorEl = byId('llamacppErrorArea');
        var listEl = byId('llamacppAssetList');
        var emptyEl = byId('llamacppEmptyState');
        var refreshBtn = byId('llamacppRefreshBtn');

        if (loadingEl) loadingEl.style.display = '';
        if (errorEl) errorEl.style.display = 'none';
        if (listEl) listEl.innerHTML = '';
        if (emptyEl) emptyEl.style.display = 'none';
        if (refreshBtn) refreshBtn.disabled = true;

        try {
            var proxy = byId('llamacppProxySelect');
            var proxyVal = proxy ? proxy.value : '';
            var url = '/api/llamacpp/release/latest';
            if (proxyVal) url += '?proxy=' + encodeURIComponent(proxyVal);

            var resp = await fetch(url, { method: 'GET' });
            var result = await resp.json();

            if (loadingEl) loadingEl.style.display = 'none';
            if (refreshBtn) refreshBtn.disabled = false;

            if (!result || !result.success || !result.data) {
                if (errorEl) {
                    errorEl.style.display = '';
                    errorEl.textContent = (result && result.error) ? result.error : t('page.settings.llamacpp.load_failed', '加载失败');
                }
                if (emptyEl) emptyEl.style.display = '';
                return;
            }

            _llamacppLoaded = true;
            _llamacppReleaseData = result.data;
            _llamacppLocalBackends = Array.isArray(result.data.localBackends) ? result.data.localBackends : [];
            _llamacppLocalCudarts = Array.isArray(result.data.localCudarts) ? result.data.localCudarts : [];
            renderLlamaCppRelease(result.data);

        } catch (e) {
            if (loadingEl) loadingEl.style.display = 'none';
            if (refreshBtn) refreshBtn.disabled = false;
            if (errorEl) {
                errorEl.style.display = '';
                errorEl.textContent = t('common.network_request_failed', '网络请求失败');
            }
            if (emptyEl) emptyEl.style.display = '';
        }
    }

    function renderLlamaCppRelease(data) {
        var tagEl = byId('llamacppVersionTag');
        if (tagEl) tagEl.textContent = data.tag_name || '-';

        var pubEl = byId('llamacppPublishedAt');
        if (pubEl) {
            if (data.published_at) {
                try {
                    var d = new Date(data.published_at);
                    pubEl.textContent = d.toLocaleString();
                } catch (e) {
                    pubEl.textContent = data.published_at;
                }
            } else {
                pubEl.textContent = '';
            }
        }

        var urlEl = byId('llamacppReleaseUrl');
        if (urlEl && data.html_url) {
            urlEl.href = data.html_url;
            urlEl.style.display = '';
        } else if (urlEl) {
            urlEl.style.display = 'none';
        }

        var listEl = byId('llamacppAssetList');
        var emptyEl = byId('llamacppEmptyState');
        if (!listEl) return;

        var assets = data.assets || [];
        if (!assets.length) {
            listEl.innerHTML = '';
            if (emptyEl) emptyEl.style.display = '';
            return;
        }
        if (emptyEl) emptyEl.style.display = 'none';

        var html = '';
        for (var i = 0; i < assets.length; i++) {
            var a = assets[i];
            var sizeText = formatBytes(a.size);
            var dlCount = a.download_count || 0;
            var isDownloading = _llamacppDownloading[a.name];
            var backendName = parseLlamaCppBackendName(a.name);
            var isInstalled = _llamacppLocalBackends.indexOf(backendName) >= 0 || _llamacppLocalCudarts.indexOf(backendName) >= 0;

            html += '<div class="llamacpp-asset-row" data-asset-name="' + escapeHtml(a.name) + '">';
            html += '  <div class="llamacpp-asset-info">';
            html += '    <span class="llamacpp-asset-name">' + escapeHtml(a.name) + '</span>';
            html += '    <span class="llamacpp-asset-meta">';
            html += '      <span class="llamacpp-asset-size">' + sizeText + '</span>';
            html += '      <span class="llamacpp-asset-downloads">↓ ' + dlCount + '</span>';
            html += '    </span>';
            html += '  </div>';

            if (isDownloading) {
                html += '  <div class="llamacpp-asset-progress" style="display:flex;">';
                html += '    <div class="update-progress-bar-outer"><div class="update-progress-bar-inner" style="width:0%;"></div></div>';
                html += '    <span class="llamacpp-progress-text">0%</span>';
                html += '  </div>';
            } else {
                html += '  <div class="llamacpp-asset-progress" style="display:none;">';
                html += '    <div class="update-progress-bar-outer"><div class="update-progress-bar-inner" style="width:0%;"></div></div>';
                html += '    <span class="llamacpp-progress-text">0%</span>';
                html += '  </div>';
            }

            html += '  <div class="llamacpp-asset-actions">';
            if (isDownloading) {
                html += '    <button class="btn btn-secondary btn-sm" disabled><i class="fas fa-spinner fa-spin"></i> ' + t('page.settings.llamacpp.downloading', '下载中...') + '</button>';
            } else if (isInstalled) {
                html += '    <span class="llamacpp-installed-badge"><i class="fas fa-check-circle"></i> ' + t('page.settings.llamacpp.installed', '已安装') + '</span>';
            } else {
                html += '    <button class="btn btn-primary btn-sm" onclick="SettingsPage.downloadLlamaCppAsset(\'' + escapeHtml(a.name) + '\', \'' + escapeHtml(a.browser_download_url) + '\')"><i class="fas fa-download"></i> ' + t('page.settings.llamacpp.download_btn', '下载') + '</button>';
            }
            html += '  </div>';
            html += '</div>';
        }
        listEl.innerHTML = html;
    }

    async function downloadLlamaCppAsset(assetName, downloadUrl) {
        var proxy = byId('llamacppProxySelect');
        var proxyVal = proxy ? proxy.value : '';
        if (proxyVal) {
            downloadUrl = proxyVal + downloadUrl;
        }

        _llamacppDownloading[assetName] = true;

        try {
            var resp = await fetch('/api/downloads/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    url: downloadUrl,
                    path: 'llamacpp',
                    fileName: assetName
                })
            });
            var result = await resp.json();

            if (result && result.success && result.taskId) {
                _llamacppDownloading[assetName] = result.taskId;
                if (_llamacppReleaseData) renderLlamaCppRelease(_llamacppReleaseData);
            } else {
                delete _llamacppDownloading[assetName];
                toast(t('toast.error', '错误'), (result && result.error) || t('page.settings.llamacpp.download_failed', '下载创建失败'), 'error');
            }
        } catch (e) {
            delete _llamacppDownloading[assetName];
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    function onLlamaCppDownloadProgress(eventData) {
        if (!eventData || !eventData.taskId) return;
        var taskId = eventData.taskId;
        for (var name in _llamacppDownloading) {
            if (_llamacppDownloading[name] === taskId) {
                updateAssetProgress(name, eventData);
                break;
            }
        }
    }

    function updateAssetProgress(assetName, eventData) {
        var row = document.querySelector('.llamacpp-asset-row[data-asset-name="' + assetName + '"]');
        if (!row) return;

        var progressEl = row.querySelector('.llamacpp-asset-progress');
        var barInner = row.querySelector('.update-progress-bar-inner');
        var textEl = row.querySelector('.llamacpp-progress-text');

        if (progressEl) progressEl.style.display = 'flex';

        if (eventData.progress !== undefined && barInner) {
            var pct = Math.round(eventData.progress * 100);
            barInner.style.width = pct + '%';
            if (textEl) textEl.textContent = pct + '%';
        } else if (eventData.progressRatio !== undefined && barInner) {
            var pct2 = Math.round(eventData.progressRatio * 100);
            barInner.style.width = pct2 + '%';
            if (textEl) textEl.textContent = pct2 + '%';
        }

        if (eventData.state === 'COMPLETED' || eventData.state === 'FAILED') {
            delete _llamacppDownloading[assetName];
            if (eventData.state === 'COMPLETED') {
                // Reload after a delay to let the backend finish extraction and flatten
                setTimeout(function () {
                    loadLlamaCppReleases();
                }, 1500);
            } else {
                if (_llamacppReleaseData) renderLlamaCppRelease(_llamacppReleaseData);
            }
        }
    }

    function formatBytes(bytes) {
        if (bytes <= 0) return '0 B';
        var units = ['B', 'KB', 'MB', 'GB'];
        var i = 0;
        var val = bytes;
        while (val >= 1024 && i < units.length - 1) {
            val /= 1024;
            i++;
        }
        return val.toFixed(i === 0 ? 0 : 1) + ' ' + units[i];
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
            const mcpToggle = byId('toggleMcpServer');
            if (mcpToggle && c.mcpServer) mcpToggle.checked = !!c.mcpServer.enabled;
        }

        // Security
        const sec = data.security;
        if (sec) {
            const apiKeyToggle = byId('toggleApiKeyEnabled');
            if (apiKeyToggle) apiKeyToggle.checked = !!sec.apiKeyEnabled;
            // 不再从服务端读取明文 apiKey，只显示是否已配置
            const apiKeyInput = byId('apiKeyInput');
            if (apiKeyInput) {
                apiKeyInput.placeholder = sec.apiKeyConfigured ? '****** (已配置，留空保持不变)' : 'Enter API Key';
                apiKeyInput.value = '';
            }
            updateApiKeyInputState();
        }

        // HTTPS
        const https = data.https;
        if (https) {
            const httpsToggle = byId('toggleHttpsEnabled');
            if (httpsToggle) httpsToggle.checked = !!https.enabled;
            loadHttpsCertStatus();
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
            const bodyFile = byId('toggleLogRequestBodyToFile');
            if (bodyFile) bodyFile.checked = !!log.logRequestBodyToFile;
        }

        // Download
        const dl = data.download;
        if (dl) {
            const dir = byId('downloadDirInput');
            if (dir && dl.directory) dir.value = dl.directory;
        }

        // Node role
        const roleSel = byId('nodeRoleSelect');
        if (roleSel) {
            const role = data.nodeRole ? String(data.nodeRole).toLowerCase() : 'slave';
            roleSel.value = (role === 'master') ? 'master' : 'slave';
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

    let currentHttpsCertPassword = '';

    async function loadHttpsCertStatus() {
        try {
            const resp = await fetch('/api/cert/status', { method: 'GET' });
            const json = await resp.json();
            if (!json || !json.success || !json.data) return;
            const status = json.data;
            currentHttpsCertPassword = status.password || '';
            const noCertState = byId('httpsNoCertState');
            const hasCertState = byId('httpsHasCertState');
            if (!noCertState || !hasCertState) return;
            if (status.exists) {
                noCertState.style.display = 'none';
                hasCertState.style.display = 'block';
                renderHttpsCertInfo(status);
            } else {
                noCertState.style.display = 'block';
                hasCertState.style.display = 'none';
            }
        } catch (e) {
            // ignore
        }
    }

    function renderHttpsCertInfo(status) {
        const infoEl = byId('httpsCertInfo');
        const guideEl = byId('httpsInstallGuide');
        const downloadBtn = byId('httpsCertDownloadBtn');
        if (!infoEl) return;
        const path = escapeHtml(status.path || '');
        const passwordConfigured = !!status.passwordConfigured;
        const sizeText = formatBytes(status.size);
        if (downloadBtn && status.path) {
            const fileName = status.path.replace(/\\/g, '/').split('/').pop() || 'keystore.p12';
            downloadBtn.setAttribute('download', fileName);
        }
        infoEl.innerHTML =
            '<div style="display:flex;justify-content:space-between;gap:0.5rem;padding:0.35rem 0;border-bottom:1px solid var(--border-color);"><span style="color:var(--text-secondary);">' + t('page.settings.https.cert_path', '文件路径') + '</span><code style="word-break:break-all;">' + path + '</code></div>' +
            '<div style="display:flex;justify-content:space-between;gap:0.5rem;padding:0.35rem 0;border-bottom:1px solid var(--border-color);"><span style="color:var(--text-secondary);">' + t('page.settings.https.password', '密码') + '</span><code>' + (passwordConfigured ? '******' : '-') + '</code></div>' +
            '<div style="display:flex;justify-content:space-between;gap:0.5rem;padding:0.35rem 0;"><span style="color:var(--text-secondary);">' + t('page.settings.https.cert_size', '文件大小') + '</span><span>' + sizeText + '</span></div>';
        if (guideEl) {
            guideEl.innerHTML =
                '<div style="margin-bottom:0.5rem;"><strong>' + t('page.settings.https.install_windows_title', 'Windows') + '</strong><br>' + t('page.settings.https.install_windows', '下载 keystore.p12 后双击，选择“本地计算机”→“受信任的根证书颁发机构”完成导入。') + '</div>' +
                '<div style="margin-bottom:0.5rem;"><strong>' + t('page.settings.https.install_macos_title', 'macOS') + '</strong><br>' + t('page.settings.https.install_macos', '双击下载的 .p12 文件，钥匙串访问会打开；在“登录”或“系统”中找到该证书，双击后展开“信任”并选择“始终信任”。') + '</div>' +
                '<div><strong>' + t('page.settings.https.install_linux_title', 'Linux') + '</strong><br>' + t('page.settings.https.install_linux', '将证书导出为 PEM 格式后复制到 /usr/local/share/ca-certificates/，然后运行 sudo update-ca-certificates。') + '</div>';
        }
    }

    function formatBytes(bytes) {
        if (bytes == null || isNaN(bytes)) return '-';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
    }

    function copyHttpsCertPassword() {
        if (!currentHttpsCertPassword) return;
        navigator.clipboard.writeText(currentHttpsCertPassword).then(() => {
            toast(t('toast.success', '成功'), t('common.copied', '已复制'), 'success');
        }).catch(() => {
            toast(t('toast.error', '错误'), t('common.copy_failed', '复制失败'), 'error');
        });
    }

    function showHttpsRegenerate() {
        const noCertState = byId('httpsNoCertState');
        const hasCertState = byId('httpsHasCertState');
        if (noCertState && hasCertState) {
            noCertState.style.display = 'block';
            hasCertState.style.display = 'none';
        }
        const warnEl = byId('httpsCertGenWarn');
        if (warnEl) warnEl.style.display = 'none';
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

    async function saveNodeRole() {
        const roleSel = byId('nodeRoleSelect');
        const btn = byId('saveNodeRoleBtn');
        if (!roleSel) return;
        const role = roleSel.value === 'master' ? 'master' : 'slave';
        if (btn) btn.disabled = true;
        try {
            var resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ nodeRole: role })
            });
            var data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存') + '，' + t('page.settings.nodes.role.hint', '需重启服务生效'), 'success');
            loadSettings();
            if (typeof loadNodes === 'function') { loadNodes(); }
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        } finally {
            if (btn) btn.disabled = false;
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
        const payload = {};
        if (toggle) payload.httpsEnabled = toggle.checked;
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

    let lastHttpsCertResult = null;

    async function generateHttpsCert() {
        const btn = byId('httpsCertGenBtn');
        const status = byId('httpsCertGenStatus');
        const errorEl = byId('httpsCertGenError');
        const resultEl = byId('httpsCertGenResult');
        const resultContent = byId('httpsCertGenResultContent');
        const fillHint = byId('httpsCertGenFillHint');

        if (errorEl) { errorEl.style.display = 'none'; errorEl.textContent = ''; }
        if (resultEl) resultEl.style.display = 'none';

        const ips = (byId('httpsCertGenIps')?.value || '').split('\n').map(s => s.trim()).filter(s => s.length > 0);
        const hostnames = (byId('httpsCertGenHostnames')?.value || '').split('\n').map(s => s.trim()).filter(s => s.length > 0);
        const cn = (byId('httpsCertGenCn')?.value || '').trim();
        const validity = parseInt(byId('httpsCertGenValidity')?.value) || 3650;
        const password = (byId('httpsCertGenPassword')?.value || '').trim();
        const keysize = parseInt(byId('httpsCertGenKeysize')?.value) || 2048;

        if (ips.length === 0 && hostnames.length === 0) {
            if (errorEl) {
                errorEl.textContent = t('page.settings.https.cert_gen_error_empty', '请至少输入一个 IP 地址或主机名');
                errorEl.style.display = 'block';
            }
            return;
        }

        if (btn) btn.disabled = true;
        if (status) status.innerHTML = '<i class="fas fa-spinner fa-spin"></i> ' + t('page.settings.https.cert_gen_running', '正在生成证书...');

        try {
            const resp = await fetch('/api/cert/generate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    ips,
                    hostnames,
                    cn: cn || undefined,
                    validity,
                    password: password || undefined,
                    keysize
                })
            });
            const json = await resp.json();

            if (!json || !json.success) {
                if (errorEl) {
                    errorEl.textContent = (json && json.error) ? json.error : t('page.settings.https.cert_gen_failed', '生成失败');
                    errorEl.style.display = 'block';
                }
                if (status) status.innerHTML = '';
                return;
            }

            lastHttpsCertResult = json.data;

            if (resultContent) {
                const cmdEscaped = (json.data.command || '').replace(/</g, '&lt;').replace(/>/g, '&gt;');
                resultContent.innerHTML =
                    '<div style="display:flex;justify-content:space-between;gap:0.5rem;padding:0.35rem 0;border-bottom:1px solid var(--border-color);"><span style="color:var(--text-secondary);">' + t('page.settings.https.cert_gen_result_path', '文件路径') + '</span><code style="word-break:break-all;">' + escapeHtml(json.data.path || '') + '</code></div>' +
                    '<div style="display:flex;justify-content:space-between;gap:0.5rem;padding:0.35rem 0;border-bottom:1px solid var(--border-color);"><span style="color:var(--text-secondary);">' + t('page.settings.https.cert_gen_result_password', '密码') + '</span><code style="word-break:break-all;">' + escapeHtml(json.data.password || '') + '</code></div>' +
                    '<div style="display:flex;justify-content:space-between;gap:0.5rem;padding:0.35rem 0;border-bottom:1px solid var(--border-color);"><span style="color:var(--text-secondary);">' + t('page.settings.https.cert_gen_result_san', 'SAN') + '</span><code style="word-break:break-all;max-width:60%;font-size:0.65rem;">' + escapeHtml(json.data.san || '') + '</code></div>' +
                    '<div style="display:flex;justify-content:space-between;gap:0.5rem;padding:0.35rem 0;border-bottom:1px solid var(--border-color);"><span style="color:var(--text-secondary);">' + t('page.settings.https.cert_gen_result_expire', '过期日期') + '</span><span>' + escapeHtml(json.data.expireDate || '') + '</span></div>' +
                    '<div style="padding:0.35rem 0;"><span style="color:var(--text-secondary);display:block;margin-bottom:0.25rem;">' + t('page.settings.https.cert_gen_result_command', '执行命令') + '</span><code style="display:block;word-break:break-all;font-size:0.65rem;padding:0.4rem;background:var(--sidebar-bg);border-radius:0.3rem;">' + cmdEscaped + '</code></div>';
            }
            if (fillHint) {
                fillHint.innerHTML = '<i class="fas fa-info-circle"></i> ' + t('page.settings.https.cert_gen_fill_hint', '证书已保存至配置，点击「启用」并保存后重启服务即可生效。');
            }
            var warnEl = byId('httpsCertGenWarn');
            if (warnEl) {
                warnEl.innerHTML = '<i class="fas fa-exclamation-triangle"></i> ' + t('page.settings.https.cert_gen_download_warn', '证书已开始下载。请在安装证书并重启浏览器后，再启用 HTTPS 并重启服务，否则浏览器会拦截访问。');
                warnEl.style.display = 'flex';
            }
            var downloadBtn = byId('httpsCertDownloadBtn');
            if (downloadBtn) {
                var certFileName = (json.data.path || '').replace(/\\/g, '/').split('/').pop() || 'keystore.p12';
                downloadBtn.setAttribute('download', certFileName);
                downloadBtn.click();
            }
            if (resultEl) resultEl.style.display = 'block';
            if (status) status.innerHTML = '<i class="fas fa-check-circle" style="color:#10b981;"></i> ' + t('page.settings.https.cert_gen_success', '生成成功');
            loadHttpsCertStatus();
            toast(t('toast.success', '成功'), t('page.settings.https.cert_gen_success', '证书生成成功'), 'success');
        } catch (e) {
            if (errorEl) {
                errorEl.textContent = t('common.network_request_failed', '网络请求失败') + ': ' + e.message;
                errorEl.style.display = 'block';
            }
            if (status) status.innerHTML = '';
        } finally {
            if (btn) btn.disabled = false;
        }
    }

    function escapeHtml(str) {
        if (str == null) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    function copyHttpsCertCommand() {
        if (!lastHttpsCertResult || !lastHttpsCertResult.command) return;
        navigator.clipboard.writeText(lastHttpsCertResult.command).then(() => {
            toast(t('toast.success', '成功'), t('common.copied', '已复制'), 'success');
        }).catch(() => {
            toast(t('toast.error', '错误'), t('common.copy_failed', '复制失败'), 'error');
        });
    }

    async function saveLogging() {
        const url = byId('toggleLogRequestUrl');
        const header = byId('toggleLogRequestHeader');
        const body = byId('toggleLogRequestBody');
        const bodyFile = byId('toggleLogRequestBodyToFile');
        const payload = {};
        if (url) payload.logRequestUrl = url.checked;
        if (header) payload.logRequestHeader = header.checked;
        if (body) payload.logRequestBody = body.checked;
        if (bodyFile) payload.logRequestBodyToFile = bodyFile.checked;
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
    let _lastNodes = null;

    function getRoleMaster() {
        const sel = byId('nodeRoleSelect');
        if (sel) return sel.value === 'master';
        return _isMaster;
    }

    function renderNodeList() {
        const listEl = byId('nodeList');
        const emptyEl = byId('nodeEmptyState');
        if (!listEl) return;
        const isMaster = getRoleMaster();
        const nodes = _lastNodes || [];
        listEl.innerHTML = nodes.map(function (n) { return buildNodeRow(n, isMaster); }).join('');
        if (emptyEl) emptyEl.style.display = nodes.length === 0 ? '' : 'none';
    }

    function applyNodeRoleState() {
        const sel = byId('nodeRoleSelect');
        const toolbarEl = byId('nodeToolbar');
        const bannerEl = byId('nodeMasterBanner');
        const addBtn = byId('addNodeBtn');
        const isMaster = sel ? sel.value === 'master' : _isMaster;
        if (toolbarEl) toolbarEl.style.display = isMaster ? '' : 'none';
        if (bannerEl) bannerEl.style.display = isMaster ? 'none' : '';
        if (addBtn) addBtn.disabled = !isMaster;
        // Re-render cards so that non-master role immediately freezes existing
        // nodes to read-only (no edit/delete/test/enable actions), without removing
        // the configs themselves. Switching back to master restores the actions.
        renderNodeList();
    }

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
            const addBtn = byId('addNodeBtn');
            if (addBtn) addBtn.disabled = !_isMaster;
            if (emptyEl) emptyEl.style.display = 'none';

            const result = await getNodeList();
            if (!result || !result.success) {
                _lastNodes = [];
                renderNodeList();
                return;
            }
            _lastNodes = result.data || [];
            renderNodeList();
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
                + '<button class="btn btn-sm btn-secondary" onclick="SettingsPage.testNode(\'' + nid + '\',this)" title="' + t('page.settings.nodes.test_connectivity', 'Test Connectivity') + '"><i class="fas fa-plug"></i></button>'
                + '<button class="btn btn-sm btn-secondary" onclick="SettingsPage.editNode(\'' + nid + '\')" title="' + t('common.edit', 'Edit') + '"><i class="fas fa-edit"></i></button>'
                + '<button class="btn btn-sm btn-danger" onclick="SettingsPage.removeNode(\'' + nid + '\')" title="' + t('common.delete', 'Delete') + '"><i class="fas fa-trash"></i></button>'
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
        const sel = byId('nodeRoleSelect');
        if (sel && sel.value !== 'master') {
            toast(t('toast.error', 'error'), t('api.error.node.master.only', 'Current node is not in master mode, cannot manage remote nodes'), 'error');
            return;
        }
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
            toast(t('toast.error', '错误'), t('modal.node.error.url_format', 'URL must start with http:// or https://'), 'error'); return;
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
            try { await refreshNodeList(); } catch (e) {}
            loadNodes();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function editNode(nodeId) {
        try {
            var result = await getNodeList();
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
            toast(t('toast.success', '成功'), t('common.deleted', 'Deleted'), 'success');
            try { await refreshNodeList(); } catch (e) {}
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
                var newStatus = d.connected ? 'online' : 'offline';
                var newLabel = t('page.settings.nodes.status.' + newStatus, d.connected ? '在线' : '离线');
                if (d.connected) {
                    toast(t('toast.success', '成功'), t('page.settings.nodes.test_success', { nodeId: nodeId, latency: d.latency, version: d.version || 'unknown' }), 'success');
                } else {
                    toast(t('toast.error', '错误'), t('page.settings.nodes.test_failed', { nodeId: nodeId, reason: d.statusCode || 'timeout' }), 'error');
                }
                if (btn) {
                    var card = btn.closest('.node-card');
                    if (card) {
                        var badge = card.querySelector('.node-status-badge');
                        if (badge) { badge.className = 'node-status-badge ' + newStatus; badge.textContent = newLabel; }
                        var dot = card.querySelector('.status-dot');
                        if (dot) { dot.className = 'status-dot ' + newStatus; dot.title = newLabel; }
                    }
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
            try { await refreshNodeList(); } catch (e) {}
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
                    errorEl.textContent = (result && result.error) ? t(result.error, '检查失败') : t('page.settings.update.status.check_failed', '检查失败');
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
                if (typeof marked !== 'undefined' && typeof marked.parse === 'function') {
                    bodyEl.innerHTML = marked.parse(bodyText);
                } else {
                    bodyEl.textContent = bodyText;
                }
            } else if (bodyEl) {
                bodyEl.textContent = '-';
            }

            var downloadBtn = byId('updateDownloadBtn');
            var applyBtn = byId('updateApplyBtn');
            var proxyRow = byId('updateProxyRow');
            if (downloadBtn) downloadBtn.style.display = 'none';
            if (applyBtn) applyBtn.style.display = 'none';
            if (proxyRow) proxyRow.style.display = 'none';

            var statusResp = await fetch('/api/sys/update/status', { method: 'GET' });
            var statusResult = await statusResp.json();
            var sd = statusResult && statusResult.success ? (statusResult.data || {}) : {};

            if (sd.status === 'ready' && applyBtn) {
                applyBtn.style.display = 'inline-flex';
                if (sd.pendingVersion) {
                    applyBtn.innerHTML = '<i class="fas fa-check"></i> ' + t('page.settings.update.action.apply_version', '应用 {version}').replace('{version}', sd.pendingVersion);
                } else {
                    applyBtn.innerHTML = '<i class="fas fa-check"></i> ' + t('page.settings.update.action.apply', '应用更新');
                }
            }

            if (sd.status === 'downloading') {
                if (downloadBtn) downloadBtn.style.display = 'none';
                if (applyBtn) applyBtn.style.display = 'none';
                if (errorEl) errorEl.style.display = 'none';
                var progressEl2 = byId('updateProgressArea');
                var progressBar2 = byId('updateProgressBar');
                var progressText2 = byId('updateProgressText');
                var progressPercent2 = byId('updateProgressPercent');
                var progressSize2 = byId('updateProgressSize');
                if (progressEl2) progressEl2.style.display = '';
                if (progressBar2 && sd.progressRatio >= 0) {
                    progressBar2.style.width = (sd.progressRatio * 100) + '%';
                }
                if (progressPercent2 && sd.progressRatio >= 0) {
                    progressPercent2.textContent = Math.round(sd.progressRatio * 100) + '%';
                }
                if (progressText2) progressText2.textContent = t('page.settings.update.progress.downloading', '下载更新包...');
                if (progressSize2) {
                    var dl = formatBytes(sd.downloadedBytes || 0);
                    var total = sd.totalBytes > 0 ? formatBytes(sd.totalBytes) : '?';
                    progressSize2.textContent = dl + ' / ' + total;
                }
            } else if (!isPlaceholder && data.hasUpdate && release && sd.status !== 'applying') {
                if (downloadBtn) {
                    downloadBtn.style.display = 'inline-flex';
                    downloadBtn.dataset.tagName = release.tag_name;
                    downloadBtn.disabled = false;
                    downloadBtn.innerHTML = '<i class="fas fa-download"></i> ' + t('page.settings.update.action.download_version', '下载 {version}').replace('{version}', release.tag_name);
                }
                if (proxyRow) proxyRow.style.display = '';
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
        var progressBar = byId('updateProgressBar');
        var progressText = byId('updateProgressText');
        var progressPercent = byId('updateProgressPercent');
        var progressSize = byId('updateProgressSize');
        var resultEl = byId('updateResultArea');

        var tagName = downloadBtn ? (downloadBtn.dataset.tagName || '') : '';
        if (!tagName || tagName === '{tag}') {
            if (errorEl) {
                errorEl.style.display = '';
                errorEl.textContent = t('page.settings.update.error.self_compiled', '当前版本不支持自动更新');
            }
            return;
        }
        var zipFileName = 'llama.cpp-hub-' + tagName + '.zip';
        var downloadUrl = 'https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub/releases/download/' + tagName + '/' + zipFileName;
        var proxySelect = byId('updateProxySelect');
        if (proxySelect && proxySelect.value) {
            downloadUrl = proxySelect.value + downloadUrl;
        }

        if (downloadBtn) downloadBtn.style.display = 'none';
        if (applyBtn) applyBtn.style.display = 'none';
        if (errorEl) errorEl.style.display = 'none';
        if (resultEl) resultEl.style.display = 'none';
        if (progressEl) progressEl.style.display = '';
        if (progressBar) progressBar.style.width = '0%';
        if (progressPercent) progressPercent.textContent = '0%';
        if (progressText) progressText.textContent = t('page.settings.update.progress.preparing', '准备下载...');
        if (progressSize) progressSize.textContent = '';

        try {
            var resp = await fetch('/api/sys/update/download', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ url: downloadUrl, version: tagName })
            });
            var result = await resp.json();
            if (!result || !result.success) {
                if (progressEl) progressEl.style.display = 'none';
                if (errorEl) {
                    errorEl.style.display = '';
                    errorEl.textContent = (result && result.error) ? t(result.error, '下载失败') : t('page.settings.update.error.download_failed', '下载失败');
                }
                if (downloadBtn) {
                    downloadBtn.style.display = 'inline-flex';
                    downloadBtn.disabled = false;
                }
                return;
            }
        } catch (e) {
            if (progressEl) progressEl.style.display = 'none';
            if (errorEl) {
                errorEl.style.display = '';
                errorEl.textContent = t('common.network_request_failed', '网络请求失败');
            }
            if (downloadBtn) {
                downloadBtn.style.display = 'inline-flex';
                downloadBtn.disabled = false;
            }
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
                    errorEl.textContent = (result && result.error) ? t(result.error, '应用更新失败') : '应用更新失败';
                }
                if (applyBtn) applyBtn.disabled = false;
                return;
            }
            if (applyBtn) applyBtn.style.display = 'none';
            showToast('更新成功', result.data ? (result.data.message || '更新已应用，请重启程序生效') : '更新已应用，请重启程序生效', 'success');
        } catch (e) {
            if (errorEl) {
                errorEl.style.display = '';
                errorEl.textContent = t('common.network_request_failed', '网络请求失败');
            }
            if (applyBtn) applyBtn.disabled = false;
        }
    }

    function handleAppUpdateEvent(data) {
        if (!data || !data.status) return;

        var progressEl = byId('updateProgressArea');
        var progressBar = byId('updateProgressBar');
        var progressText = byId('updateProgressText');
        var progressPercent = byId('updateProgressPercent');
        var progressSize = byId('updateProgressSize');
        var downloadBtn = byId('updateDownloadBtn');
        var applyBtn = byId('updateApplyBtn');
        var resultEl = byId('updateResultArea');
        var errorEl = byId('updateErrorArea');
        var proxyRow = byId('updateProxyRow');

        if (data.status === 'downloading') {
            if (progressEl) progressEl.style.display = '';
            if (errorEl) errorEl.style.display = 'none';
            if (proxyRow) proxyRow.style.display = 'none';

            var ratio = data.progressRatio;
            if (ratio >= 0 && progressBar) {
                progressBar.style.width = (ratio * 100) + '%';
            }
            if (progressPercent) {
                progressPercent.textContent = (ratio >= 0 ? Math.round(ratio * 100) : 0) + '%';
            }
            if (progressText) {
                progressText.textContent = t('page.settings.update.progress.downloading', '下载更新包...');
            }
            if (progressSize) {
                var dl = formatBytes(data.downloadedBytes || 0);
                var total = data.totalBytes > 0 ? formatBytes(data.totalBytes) : '?';
                progressSize.textContent = dl + ' / ' + total;
            }
        } else if (data.status === 'completed') {
            if (progressEl) progressEl.style.display = 'none';
            if (downloadBtn) downloadBtn.style.display = 'none';
            if (proxyRow) proxyRow.style.display = 'none';
            if (resultEl) resultEl.style.display = '';
            if (applyBtn) {
                applyBtn.style.display = 'inline-flex';
                var applyLabel = data.version ? t('page.settings.update.action.apply_version', '应用 {version}').replace('{version}', data.version) : t('page.settings.update.action.apply', '应用更新');
                applyBtn.innerHTML = '<i class="fas fa-check"></i> ' + applyLabel;
            }
        } else if (data.status === 'failed') {
            if (progressEl) progressEl.style.display = 'none';
            if (applyBtn) applyBtn.style.display = 'none';
            if (errorEl) {
                errorEl.style.display = '';
                errorEl.textContent = data.errorMessage ? t(data.errorMessage, '下载失败') : t('page.settings.update.error.download_failed', '下载失败');
            }
            if (downloadBtn) {
                downloadBtn.style.display = 'inline-flex';
                downloadBtn.disabled = false;
            }
            if (proxyRow) proxyRow.style.display = '';
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
            var progressEl = byId('updateProgressArea');
            var progressBar = byId('updateProgressBar');
            var progressText = byId('updateProgressText');
            var progressPercent = byId('updateProgressPercent');
            var progressSize = byId('updateProgressSize');
            var proxyRow = byId('updateProxyRow');
            var currentVerEl = byId('updateCurrentVersion');
            if (currentVerEl) currentVerEl.textContent = sd.currentVersion || '-';

            if (sd.status === 'ready' && applyBtn) {
                if (resultEl) resultEl.style.display = '';
                applyBtn.style.display = 'inline-flex';
                if (sd.pendingVersion) {
                    applyBtn.innerHTML = '<i class="fas fa-check"></i> ' + t('page.settings.update.action.apply_version', '应用 {version}').replace('{version}', sd.pendingVersion);
                } else {
                    applyBtn.innerHTML = '<i class="fas fa-check"></i> ' + t('page.settings.update.action.apply', '应用更新');
                }
                if (downloadBtn) downloadBtn.style.display = 'none';
                if (proxyRow) proxyRow.style.display = 'none';
                if (errorEl) errorEl.style.display = 'none';
                if (progressEl) progressEl.style.display = 'none';
            } else if (sd.status === 'downloading') {
                if (downloadBtn) downloadBtn.style.display = 'none';
                if (proxyRow) proxyRow.style.display = 'none';
                if (applyBtn) applyBtn.style.display = 'none';
                if (resultEl) resultEl.style.display = 'none';
                if (errorEl) errorEl.style.display = 'none';
                if (progressEl) progressEl.style.display = '';
                if (progressBar && sd.progressRatio >= 0) {
                    progressBar.style.width = (sd.progressRatio * 100) + '%';
                }
                if (progressPercent && sd.progressRatio >= 0) {
                    progressPercent.textContent = Math.round(sd.progressRatio * 100) + '%';
                }
                if (progressText) progressText.textContent = t('page.settings.update.progress.downloading', '下载更新包...');
                if (progressSize) {
                    var dl = formatBytes(sd.downloadedBytes || 0);
                    var total = sd.totalBytes > 0 ? formatBytes(sd.totalBytes) : '?';
                    progressSize.textContent = dl + ' / ' + total;
                }
            } else if (sd.status === 'applying') {
                if (downloadBtn) {
                    downloadBtn.style.display = 'inline-flex';
                    downloadBtn.disabled = true;
                }
                if (proxyRow) proxyRow.style.display = 'none';
                if (applyBtn) applyBtn.style.display = 'none';
                if (progressEl) progressEl.style.display = 'none';
            } else {
                if (downloadBtn) {
                    downloadBtn.style.display = 'none';
                    downloadBtn.disabled = false;
                }
                if (proxyRow) proxyRow.style.display = 'none';
                if (applyBtn) applyBtn.style.display = 'none';
                if (progressEl) progressEl.style.display = 'none';
            }
        } catch (e) {}
    }

    // --- Llama.cpp path management ---
    function toggleLlamaCppSection(header) {
        const content = byId('llamacppSectionContent');
        if (!content) return;
        const collapsed = !content.classList.contains('active');
        content.classList.toggle('active', collapsed);
        if (!header) {
            header = document.querySelector('.settings-section-header[onclick*="toggleLlamaCppSection"]');
        }
        if (header) header.setAttribute('aria-expanded', String(collapsed));
    }

    // --- Model path management ---
    function toggleModelPathSection(header) {
        const content = byId('modelPathSectionContent');
        if (!content) return;
        const collapsed = !content.classList.contains('active');
        content.classList.toggle('active', collapsed);
        if (!header) {
            header = document.querySelector('.settings-section-header[onclick*="toggleModelPathSection"]');
        }
        if (header) header.setAttribute('aria-expanded', String(collapsed));
    }

    function loadModelPathList() {
        const container = byId('modelPathListSettings');
        if (!container) return;
        container.innerHTML = '<div class="loading-spinner"><div class="spinner"></div></div>';

        var mListUrl = appendNodeId('/api/model/path/list', getModelPathNodeId());
        fetch(mListUrl)
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    modelPathItems = data.data.items || [];
                    const countEl = byId('modelPathCountSettings');
                    if (countEl) countEl.textContent = data.data.count || modelPathItems.length;
                    renderModelPathList();
                } else {
                    showToast(t('toast.error', '错误'), data.error || t('common.load_failed', '加载失败'), 'error');
                    container.innerHTML = '<div class="empty-state"><div class="empty-state-icon"><i class="fas fa-exclamation-triangle"></i></div><div class="empty-state-title">' + t('common.load_failed', '加载失败') + '</div><div class="empty-state-text">' + (data.error || t('common.unknown_error', '未知错误')) + '</div></div>';
                }
            })
            .catch(error => {
                console.error(t('log.model_path.load_error', '加载模型路径列表出错:'), error);
                showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
                container.innerHTML = '<div class="empty-state"><div class="empty-state-icon"><i class="fas fa-exclamation-triangle"></i></div><div class="empty-state-title">' + t('common.network_error', '网络错误') + '</div><div class="empty-state-text">' + t('common.unable_connect_server', '无法连接到服务器') + '</div></div>';
            });
    }

    function renderModelPathList() {
        const container = byId('modelPathListSettings');
        if (!container) return;

        if (!modelPathItems || modelPathItems.length === 0) {
            container.innerHTML = '<div class="empty-state"><div class="empty-state-icon"><i class="fas fa-folder-open"></i></div><div class="empty-state-title">' + t('page.model_path.empty_title', '暂无路径') + '</div><div class="empty-state-text">' + t('page.model_path.empty_desc', '尚未配置任何模型路径') + '</div></div>';
            return;
        }

        var html = '';
        modelPathItems.forEach(function(item) {
            const path = item.path || '';
            const name = item.name || '';
            const desc = item.description || '';
            const displayName = name || path;
            const escapedPath = path.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
            const escapedName = name ? name.replace(/'/g, "\\'") : '';
            const escapedDesc = desc ? desc.replace(/'/g, "\\'") : '';

            html += ''
                + '<div class="model-item">'
                + '<div class="model-icon-wrapper">'
                + '<i class="fas fa-folder-open"></i>'
                + '</div>'
                + '<div class="model-details">'
                + '<div class="model-name" title="' + displayName.replace(/"/g, '&quot;') + '">' + displayName.replace(/"/g, '&quot;') + '</div>'
                + '<div class="model-meta">'
                + '<span><i class="fas fa-folder"></i> ' + path.replace(/"/g, '&quot;') + '</span>'
                + '</div>'
                + (desc ? '<div class="model-desc" title="' + desc.replace(/"/g, '&quot;') + '"><i class="fas fa-info-circle"></i> ' + desc.replace(/"/g, '&quot;') + '</div>' : '')
                + '</div>'
                + '<div class="model-actions">'
                + '<button class="btn-icon" onclick="window.editModelPath(\'' + escapedPath + '\', \'' + escapedName + '\', \'' + escapedDesc + '\')" title="' + t('common.edit', '编辑') + '">'
                + '<i class="fas fa-edit"></i>'
                + '</button>'
                + '<button class="btn-icon danger" onclick="window.removeModelPath(\'' + escapedPath + '\')" title="' + t('common.delete', '删除') + '">'
                + '<i class="fas fa-trash"></i>'
                + '</button>'
                + '</div>'
                + '</div>';
        });
        container.innerHTML = html;
    }

    function openAddModelPathModal() {
        editingModelPath = null;
        const nameInput = byId('addModelPathNameInput');
        const pathInput = byId('addModelPathInput');
        const descInput = byId('addModelPathDescInput');
        if (nameInput) nameInput.value = '';
        if (pathInput) pathInput.value = '';
        if (descInput) descInput.value = '';
        const titleEl = document.querySelector('#addModelPathModal .modal-title');
        if (titleEl) titleEl.innerHTML = '<i class="fas fa-plus"></i> ' + t('modal.model_path_add.title.add', '添加模型目录');
        const modal = byId('addModelPathModal');
        if (modal) modal.classList.add('show');
    }

    function editModelPath(path, name, desc) {
        editingModelPath = path;
        const nameInput = byId('addModelPathNameInput');
        const pathInput = byId('addModelPathInput');
        const descInput = byId('addModelPathDescInput');
        if (nameInput) nameInput.value = name || '';
        if (pathInput) pathInput.value = path || '';
        if (descInput) descInput.value = desc || '';
        const titleEl = document.querySelector('#addModelPathModal .modal-title');
        if (titleEl) titleEl.innerHTML = '<i class="fas fa-edit"></i> ' + t('modal.model_path_add.title.edit', '编辑模型目录');
        const modal = byId('addModelPathModal');
        if (modal) modal.classList.add('show');
    }

    async function addModelPath() {
        const path = byId('addModelPathInput') ? String(byId('addModelPathInput').value).trim() : '';
        const name = byId('addModelPathNameInput') ? String(byId('addModelPathNameInput').value).trim() : '';
        const description = byId('addModelPathDescInput') ? String(byId('addModelPathDescInput').value).trim() : '';

        if (!path) {
            showToast(t('toast.error', '错误'), t('page.model_path.path_required', '目录路径不能为空'), 'error');
            return;
        }

        const payload = { path: path };
        if (name) payload.name = name;
        if (description) payload.description = description;

        var mNodeId = getModelPathNodeId();
        try {
            if (editingModelPath) {
                var mUpdateUrl = appendNodeId('/api/model/path/update', mNodeId);
                const resp = await fetch(mUpdateUrl, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ originalPath: editingModelPath, ...payload })
                });
                const data = await resp.json();
                if (data.success) {
                    showToast(t('toast.success', '成功'), t('toast.model_path.update_success', '更新成功'), 'success');
                    editingModelPath = null;
                    if (typeof window.closeModal === 'function') window.closeModal('addModelPathModal');
                    loadModelPathList();
                    if (typeof window.loadModels === 'function') window.loadModels();
                } else {
                    showToast(t('toast.error', '错误'), data.error || t('toast.model_path.update_failed', '更新失败'), 'error');
                }
                return;
            }

            var mAddUrl = appendNodeId('/api/model/path/add', mNodeId);
            const resp = await fetch(mAddUrl, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (data.success) {
                showToast(t('toast.success', '成功'), t('toast.model_path.add_success', '添加成功'), 'success');
                editingModelPath = null;
                if (typeof window.closeModal === 'function') window.closeModal('addModelPathModal');
                loadModelPathList();
                if (typeof window.loadModels === 'function') window.loadModels();
            } else {
                showToast(t('toast.error', '错误'), data.error || t('toast.model_path.add_failed', '添加失败'), 'error');
            }
        } catch (e) {
            console.error(t('log.model_path.save_error', '保存模型路径出错:'), e);
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    function removeModelPath(path) {
        if (!confirm(t('confirm.model_path.remove', '确定要删除此路径吗？'))) return;

        var mRemoveUrl = appendNodeId('/api/model/path/remove', getModelPathNodeId());
        fetch(mRemoveUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: path })
        })
        .then(function(response) { return response.json(); })
        .then(function(data) {
            if (data.success) {
                showToast(t('toast.success', '成功'), t('page.model_path.removed', '路径已删除'), 'success');
                loadModelPathList();
                if (typeof window.loadModels === 'function') window.loadModels();
            } else {
                showToast(t('toast.error', '错误'), data.error || t('common.delete_failed', '删除失败'), 'error');
            }
        })
        .catch(function(error) {
            console.error(t('log.model_path.delete_error', '删除模型路径出错:'), error);
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
    }

    function loadLlamaCppList() {
        const container = byId('llamacppListSettings');
        if (!container) return;
        container.innerHTML = '<div class="loading-spinner"><div class="spinner"></div></div>';

        var listUrl = appendNodeId('/api/llamacpp/list', getLlamaCppNodeId());
        fetch(listUrl)
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    llamaCppItems = data.data.items || [];
                    const countEl = byId('llamacppCountSettings');
                    if (countEl) countEl.textContent = llamaCppItems.length;
                    renderLlamaCppList();
                } else {
                    showToast(t('toast.error', '错误'), data.error || t('common.load_failed', '加载失败'), 'error');
                    container.innerHTML = '<div class="empty-state"><div class="empty-state-icon"><i class="fas fa-exclamation-triangle"></i></div><div class="empty-state-title">' + t('common.load_failed', '加载失败') + '</div><div class="empty-state-text">' + (data.error || t('common.unknown_error', '未知错误')) + '</div></div>';
                }
            })
            .catch(error => {
                console.error(t('log.llamacpp.load_error', '加载 Llama.cpp 列表出错:'), error);
                showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
                container.innerHTML = '<div class="empty-state"><div class="empty-state-icon"><i class="fas fa-exclamation-triangle"></i></div><div class="empty-state-title">' + t('common.network_error', '网络错误') + '</div><div class="empty-state-text">' + t('common.unable_connect_server', '无法连接到服务器') + '</div></div>';
            });
    }

    function renderLlamaCppList() {
        const container = byId('llamacppListSettings');
        if (!container) return;

        if (!llamaCppItems || llamaCppItems.length === 0) {
            container.innerHTML = '<div class="empty-state"><div class="empty-state-icon"><i class="fas fa-folder-open"></i></div><div class="empty-state-title">' + t('page.llamacpp.empty_title', '暂无配置') + '</div><div class="empty-state-text">' + t('page.llamacpp.empty_desc', '尚未配置任何 Llama.cpp 路径') + '</div></div>';
            return;
        }

        var html = '';
        llamaCppItems.forEach(function(item) {
            const path = item.path || '';
            const name = item.name || '';
            const desc = item.description || '';
            const source = item.source || 'configured';
            const displayName = name || path;
            const escapedPath = path.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
            const escapedName = name ? name.replace(/'/g, "\\'") : '';
            const escapedDesc = desc ? desc.replace(/'/g, "\\'") : '';
            const isScanned = source === 'scanned';
            html += ''
                + '<div class="model-item">'
                + '<div class="model-icon-wrapper">'
                + '<i class="fas fa-microchip"></i>'
                + '</div>'
                + '<div class="model-details">'
                + '<div class="model-name" title="' + displayName.replace(/"/g, '&quot;') + '">' + displayName.replace(/"/g, '&quot;') + '</div>'
                + '<div class="model-meta">'
                + '<span><i class="fas fa-folder"></i> ' + path.replace(/"/g, '&quot;') + '</span>'
                + '</div>'
                + (desc ? '<div class="model-desc" title="' + desc.replace(/"/g, '&quot;') + '"><i class="fas fa-info-circle"></i> ' + desc.replace(/"/g, '&quot;') + '</div>' : '')
                + '</div>'
                + '<div class="model-actions">'
                + '<button class="btn-icon" onclick="window.testLlamaCpp(\'' + escapedPath + '\', \'' + escapedName + '\', \'' + escapedDesc + '\')" title="' + t('common.test', '测试') + '">'
                + '<i class="fas fa-vial"></i>'
                + '</button>'
                + (isScanned ? '' : '<button class="btn-icon" onclick="window.editLlamaCpp(\'' + escapedPath + '\', \'' + escapedName + '\', \'' + escapedDesc + '\')" title="' + t('common.edit', '编辑') + '">'
                + '<i class="fas fa-edit"></i>'
                + '</button>')
                + '<button class="btn-icon danger" onclick="window.removeLlamaCpp(\'' + escapedPath + '\', ' + isScanned + ')" title="' + t('common.delete', '删除') + '">'
                + '<i class="fas fa-trash"></i>'
                + '</button>'
                + '</div>'
                + '</div>';
        });
        container.innerHTML = html;
    }

    function openAddLlamaCppModal() {
        editingLlamaCppPath = null;
        const pathInput = byId('addLlamaCppPathInput');
        const nameInput = byId('addLlamaCppNameInput');
        const descInput = byId('addLlamaCppDescInput');
        if (pathInput) pathInput.value = '';
        if (nameInput) nameInput.value = '';
        if (descInput) descInput.value = '';
        const titleEl = document.querySelector('#addLlamaCppModal .modal-title');
        if (titleEl) titleEl.innerHTML = '<i class="fas fa-plus"></i> ' + t('modal.llamacpp_add.title', '添加 Llama.cpp');
        const modal = byId('addLlamaCppModal');
        if (modal) modal.classList.add('show');
    }

    function editLlamaCpp(path, name, desc) {
        editingLlamaCppPath = path;
        const pathInput = byId('addLlamaCppPathInput');
        const nameInput = byId('addLlamaCppNameInput');
        const descInput = byId('addLlamaCppDescInput');
        if (pathInput) pathInput.value = path;
        if (nameInput) nameInput.value = name;
        if (descInput) descInput.value = desc;
        const titleEl = document.querySelector('#addLlamaCppModal .modal-title');
        if (titleEl) titleEl.innerHTML = '<i class="fas fa-edit"></i> ' + t('modal.llamacpp_add.title.edit', '编辑 Llama.cpp 路径');
        const modal = byId('addLlamaCppModal');
        if (modal) modal.classList.add('show');
    }

    async function addLlamaCpp() {
        const path = byId('addLlamaCppPathInput') ? String(byId('addLlamaCppPathInput').value).trim() : '';
        const name = byId('addLlamaCppNameInput') ? String(byId('addLlamaCppNameInput').value).trim() : '';
        const desc = byId('addLlamaCppDescInput') ? String(byId('addLlamaCppDescInput').value).trim() : '';

        if (!path) {
            showToast(t('toast.error', '错误'), t('modal.llamacpp_add.path_required', '目录路径不能为空'), 'error');
            return;
        }

        const payload = { path: path };
        if (name) payload.name = name;
        if (desc) payload.description = desc;

        var nodeId = getLlamaCppNodeId();
        try {
            if (editingLlamaCppPath) {
                var removeUrl = appendNodeId('/api/llamacpp/remove', nodeId);
                await fetch(removeUrl, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: editingLlamaCppPath })
                }).catch(function() {});
            }

            var addUrl = appendNodeId('/api/llamacpp/add', nodeId);
            const resp = await fetch(addUrl, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (data.success) {
                showToast(t('toast.success', '成功'), editingLlamaCppPath ? t('common.updated', '更新成功') : t('common.added', '添加成功'), 'success');
                if (typeof window.closeModal === 'function') window.closeModal('addLlamaCppModal');
                loadLlamaCppList();
            } else {
                showToast(t('toast.error', '错误'), data.error || t('common.save_failed', '保存失败'), 'error');
            }
        } catch (e) {
            console.error(t('log.llamacpp.save_error', '保存 Llama.cpp 出错:'), e);
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    function removeLlamaCpp(path, isScanned) {
        if (isScanned) {
            showDeleteDirectoryConfirm(path, function() {
                doRemoveLlamaCpp(path);
            });
        } else {
            if (!confirm(t('confirm.llamacpp.remove', '确定要删除此路径吗？'))) return;
            doRemoveLlamaCpp(path);
        }
    }

    function doRemoveLlamaCpp(path) {
        var removeUrl = appendNodeId('/api/llamacpp/remove', getLlamaCppNodeId());
        fetch(removeUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: path })
        })
        .then(function(response) { return response.json(); })
        .then(function(data) {
            if (data.success) {
                showToast(t('toast.success', '成功'), t('common.deleted', '已删除'), 'success');
                loadLlamaCppList();
            } else {
                showToast(t('toast.error', '错误'), data.error || t('common.delete_failed', '删除失败'), 'error');
            }
        })
        .catch(function(error) {
            console.error(t('log.llamacpp.delete_error', '删除 Llama.cpp 出错:'), error);
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
    }

    function showDeleteDirectoryConfirm(path, onConfirm) {
        const existingModal = byId('llamacppDeleteConfirmModal');
        if (existingModal) existingModal.remove();

        const modal = document.createElement('div');
        modal.id = 'llamacppDeleteConfirmModal';
        modal.className = 'modal';
        modal.innerHTML = ''
            + '<div class="modal-content" style="max-width: 520px;">'
            + '<div class="modal-header" style="border-bottom: 1px solid rgba(239,68,68,0.2);">'
            + '<h3 class="modal-title" style="color: rgb(239,68,68);"><i class="fas fa-exclamation-triangle"></i> ' + t('confirm.llamacpp.remove_directory.title', '删除磁盘目录') + '</h3>'
            + '<button class="modal-close" onclick="closeModal(\'llamacppDeleteConfirmModal\')">&times;</button>'
            + '</div>'
            + '<div class="modal-body">'
            + '<p style="margin-bottom: 0.75rem;">' + t('confirm.llamacpp.remove_directory.warning', '此操作将永久删除以下磁盘目录及其所有内容，且不可恢复：') + '</p>'
            + '<div style="background: rgba(239,68,68,0.08); border: 1px solid rgba(239,68,68,0.25); border-radius: 0.5rem; padding: 0.6rem 0.75rem; word-break: break-all; font-family: monospace; font-size: 0.875rem; color: rgb(239,68,68);">' + path.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') + '</div>'
            + '</div>'
            + '<div class="modal-footer">'
            + '<button class="btn btn-secondary" onclick="closeModal(\'llamacppDeleteConfirmModal\')">' + t('common.cancel', 'Cancel') + '</button>'
            + '<button class="btn btn-danger" id="llamacppDeleteConfirmBtn" style="background: rgb(239,68,68); border-color: rgb(239,68,68);">' + t('confirm.llamacpp.remove_directory.confirm_btn', '确认删除') + '</button>'
            + '</div>'
            + '</div>';

        const root = byId('dynamicModalRoot') || document.body;
        root.appendChild(modal);
        modal.classList.add('show');

        const confirmBtn = byId('llamacppDeleteConfirmBtn');
        if (confirmBtn) {
            confirmBtn.addEventListener('click', function() {
                closeModal('llamacppDeleteConfirmModal');
                onConfirm();
            });
        }
    }

    function ensureLlamaCppTestModal() {
        const modalId = 'llamaCppTestModal';
        let modal = byId(modalId);
        if (modal) return modal;

        modal = document.createElement('div');
        modal.id = modalId;
        modal.className = 'modal';
        modal.innerHTML = [
            '<div class="modal-content" style="max-width: 980px; height: 85vh;">',
            '<div class="modal-header">',
            '<h3 class="modal-title" id="llamaCppTestModalTitle"><i class="fas fa-vial"></i> ' + t('page.llamacpp.test_modal_title', 'Llama.cpp 测试') + '</h3>',
            '<button class="modal-close" onclick="closeModal(\'' + modalId + '\')">&times;</button>',
            '</div>',
            '<div class="modal-body" style="height: calc(85vh - 132px); overflow: auto;">',
            '<div style="margin-bottom: 14px;">',
            '<div style="font-weight: 600; margin-bottom: 6px;">llama-cli --version</div>',
            '<div style="font-size: 0.875rem; color: var(--text-secondary); margin-bottom: 6px;">',
            '<span id="llamaCppTestVersionCmd"></span>',
            '<span style="margin-left: 10px;">exitCode: <span id="llamaCppTestVersionExit"></span></span>',
            '</div>',
            '<pre id="llamaCppTestVersionOut" style="white-space: pre-wrap; padding: 10px; border: 1px solid var(--border-color); border-radius: 10px; background: #0b1220; color: #e5e7eb;"></pre>',
            '<pre id="llamaCppTestVersionErr" style="white-space: pre-wrap; padding: 10px; border: 1px solid var(--border-color); border-radius: 10px; background: #1f2937; color: #fca5a5;"></pre>',
            '</div>',
            '<div style="margin-bottom: 14px;">',
            '<div style="font-weight: 600; margin-bottom: 6px;">llama-cli --list-devices</div>',
            '<div style="font-size: 0.875rem; color: var(--text-secondary); margin-bottom: 6px;">',
            '<span id="llamaCppTestDevicesCmd"></span>',
            '<span style="margin-left: 10px;">exitCode: <span id="llamaCppTestDevicesExit"></span></span>',
            '</div>',
            '<pre id="llamaCppTestDevicesOut" style="white-space: pre-wrap; padding: 10px; border: 1px solid var(--border-color); border-radius: 10px; background: #0b1220; color: #e5e7eb;"></pre>',
            '<pre id="llamaCppTestDevicesErr" style="white-space: pre-wrap; padding: 10px; border: 1px solid var(--border-color); border-radius: 10px; background: #1f2937; color: #fca5a5;"></pre>',
            '</div>',
            '<div style="font-size: 0.875rem; color: var(--text-secondary);">' + t('common.raw_response', '原始响应') + '</div>',
            '<pre id="llamaCppTestRaw" style="white-space: pre-wrap; padding: 10px; border: 1px solid var(--border-color); border-radius: 10px; background: #111827; color: #d1d5db;"></pre>',
            '</div>',
            '<div class="modal-footer">',
            '<button class="btn btn-secondary" onclick="closeModal(\'' + modalId + '\')">' + t('common.close', '关闭') + '</button>',
            '</div>',
            '</div>'
        ].join('');

        const root = byId('dynamicModalRoot') || document.body;
        root.appendChild(modal);
        return modal;
    }

    function setLlamaCppTestModalLoading(titleText) {
        const titleEl = byId('llamaCppTestModalTitle');
        if (titleEl) titleEl.textContent = titleText || t('page.llamacpp.test_modal_title', 'Llama.cpp 测试');
        var ids = [
            'llamaCppTestVersionCmd', 'llamaCppTestVersionExit', 'llamaCppTestVersionOut', 'llamaCppTestVersionErr',
            'llamaCppTestDevicesCmd', 'llamaCppTestDevicesExit', 'llamaCppTestDevicesOut', 'llamaCppTestDevicesErr',
            'llamaCppTestRaw'
        ];
        ids.forEach(function(id) {
            var el = byId(id);
            if (el) el.textContent = (id.indexOf('Out') >= 0 || id.indexOf('Err') >= 0 || id.indexOf('Raw') >= 0) ? t('common.loading', '加载中...') : '';
        });
    }

    function fillLlamaCppTestModal(res) {
        var data = res && res.data ? res.data : null;
        var version = data && data.version ? data.version : null;
        var listDevices = data && data.listDevices ? data.listDevices : null;

        function setText(id, v) {
            var el = byId(id);
            if (el) el.textContent = v == null ? '' : String(v);
        }

        setText('llamaCppTestVersionCmd', version ? version.command : '');
        setText('llamaCppTestVersionExit', version ? version.exitCode : '');
        setText('llamaCppTestVersionOut', version ? (version.output || '') : '');
        setText('llamaCppTestVersionErr', version ? (version.error || '') : '');

        setText('llamaCppTestDevicesCmd', listDevices ? listDevices.command : '');
        setText('llamaCppTestDevicesExit', listDevices ? listDevices.exitCode : '');
        setText('llamaCppTestDevicesOut', listDevices ? (listDevices.output || '') : '');
        setText('llamaCppTestDevicesErr', listDevices ? (listDevices.error || '') : '');

        setText('llamaCppTestRaw', JSON.stringify(res, null, 2));
    }

    async function testLlamaCpp(path, name, desc) {
        var modal = ensureLlamaCppTestModal();
        modal.classList.add('show');

        var displayName = (name && name.trim()) ? name.trim() : path;
        setLlamaCppTestModalLoading(t('page.llamacpp.test_modal_title', 'Llama.cpp 测试') + ' - ' + displayName);

        try {
            var payload = { path: path };
            if (name) payload.name = name;
            if (desc) payload.description = desc;

            var testUrl = appendNodeId('/api/llamacpp/test', getLlamaCppNodeId());
            var resp = await fetch(testUrl, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            var data = await resp.json();

            if (!data || !data.success) {
                var rawEl = byId('llamaCppTestRaw');
                if (rawEl) rawEl.textContent = JSON.stringify(data, null, 2);
                showToast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.test_failed', '测试失败'), 'error');
                fillLlamaCppTestModal(data || { success: false, error: 'Test failed' });
                return;
            }

            fillLlamaCppTestModal(data);
        } catch (e) {
            var rawEl = byId('llamaCppTestRaw');
            if (rawEl) rawEl.textContent = String(e && e.message ? e.message : e);
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
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

        // Update sub-tab switching
        document.querySelector('.update-sub-tab-bar')?.addEventListener('click', function (e) {
            var btn = e.target.closest('.update-sub-tab-btn');
            if (!btn) return;
            switchUpdateSubTab(btn.getAttribute('data-sub-tab'));
        });

        // Server tab - load llama.cpp list on switch
        const serverTab = document.querySelector('.settings-tab[data-tab="server"]');
        if (serverTab) {
            serverTab.addEventListener('click', function() {
                loadLlamaCppList();
                loadModelPathList();
            });
        }

        // Server tab
        const saveServerBtn = byId('saveServerPortsBtn');
        if (saveServerBtn) saveServerBtn.addEventListener('click', saveServerPorts);

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

        const httpsCertGenBtn = byId('httpsCertGenBtn');
        if (httpsCertGenBtn) httpsCertGenBtn.addEventListener('click', generateHttpsCert);

        const httpsCertGenCopyCmdBtn = byId('httpsCertGenCopyCmdBtn');
        if (httpsCertGenCopyCmdBtn) httpsCertGenCopyCmdBtn.addEventListener('click', copyHttpsCertCommand);

        const httpsCertCopyPasswordBtn = byId('httpsCertCopyPasswordBtn');
        if (httpsCertCopyPasswordBtn) httpsCertCopyPasswordBtn.addEventListener('click', copyHttpsCertPassword);

        const httpsCertRegenerateBtn = byId('httpsCertRegenerateBtn');
        if (httpsCertRegenerateBtn) httpsCertRegenerateBtn.addEventListener('click', showHttpsRegenerate);

        // Logging tab
        const saveLoggingBtn = byId('saveLoggingBtn');
        if (saveLoggingBtn) saveLoggingBtn.addEventListener('click', saveLogging);

        // Download tab
        const saveDownloadBtn = byId('saveDownloadBtn');
        if (saveDownloadBtn) saveDownloadBtn.addEventListener('click', saveDownload);

        // Nodes tab
        const nodesTab = document.querySelector('.settings-tab[data-tab="nodes"]');
        if (nodesTab) nodesTab.addEventListener('click', loadNodes);

        const saveNodeRoleBtn = byId('saveNodeRoleBtn');
        if (saveNodeRoleBtn) saveNodeRoleBtn.addEventListener('click', saveNodeRole);

        const nodeRoleSel = byId('nodeRoleSelect');
        if (nodeRoleSel) nodeRoleSel.addEventListener('change', applyNodeRoleState);

        // Update tab
        const checkBtn = byId('checkUpdateBtn');
        if (checkBtn) checkBtn.addEventListener('click', checkUpdate);

        // Sync proxy selects
        const updateProxy = byId('updateProxySelect');
        const llamacppProxy = byId('llamacppProxySelect');
        if (updateProxy && llamacppProxy) {
            updateProxy.addEventListener('change', function () { llamacppProxy.value = this.value; });
            llamacppProxy.addEventListener('change', function () { updateProxy.value = this.value; });
        }

        // Populate node selects for each section
        populateSettingsNodeSelect('llamacppNodeSelect');
        populateSettingsNodeSelect('modelPathNodeSelect');
        populateSettingsNodeSelect('proxyNodeSelect');
    }

    let _initialized = false;
    function load() {
        if (!_initialized) {
            init();
            _initialized = true;
        }
        loadSettings();
        loadNodes();
        loadLlamaCppList();
        loadModelPathList();
        loadProxyConfig();
    }

    // --- HTTP Proxy ---
    function toggleProxySection(header) {
        const content = byId('proxySectionContent');
        if (!content) return;
        const collapsed = !content.classList.contains('active');
        content.classList.toggle('active', collapsed);
        if (!header) {
            header = document.querySelector('.settings-section-header[onclick*="toggleProxySection"]');
        }
        if (header) header.setAttribute('aria-expanded', String(collapsed));
    }

    function loadProxyConfig() {
        var pUrl = appendNodeId('/api/proxy/get', getProxyNodeId());
        fetch(pUrl)
            .then(response => response.json())
            .then(data => {
                if (data.success && data.data) {
                    const d = data.data;
                    const toggle = byId('toggleProxyEnabled');
                    const host = byId('proxyHostInput');
                    const port = byId('proxyPortInput');
                    const username = byId('proxyUsernameInput');
                    if (toggle) toggle.checked = !!d.enabled;
                    if (host) host.value = d.host || '';
                    if (port) port.value = d.port || '';
                    if (username) username.value = '';
                    const pw = byId('proxyPasswordInput');
                    if (pw) pw.value = '';
                }
            })
            .catch(error => {
                console.error(t('log.proxy.load_error', '加载代理配置出错:'), error);
            });
    }

    function saveProxyConfig() {
        const toggle = byId('toggleProxyEnabled');
        const host = byId('proxyHostInput');
        const port = byId('proxyPortInput');
        const username = byId('proxyUsernameInput');
        const password = byId('proxyPasswordInput');

        const payload = {
            enabled: toggle ? toggle.checked : false,
            host: host ? host.value.trim() : '',
            port: port ? parseInt(port.value, 10) || 0 : 0,
            username: username ? username.value.trim() : '',
            password: password ? password.value : ''
        };

        var pSaveUrl = appendNodeId('/api/proxy/save', getProxyNodeId());
        fetch(pSaveUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                toast(t('common.save_success', '保存成功'), data.data.message || t('common.saved', '已保存'), 'success');
            } else {
                toast(t('toast.error', '错误'), data.error || t('common.save_failed', '保存失败'), 'error');
            }
        })
        .catch(error => {
            console.error(t('log.proxy.save_error', '保存代理配置出错:'), error);
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
    }

    function testProxyConnection() {
        const host = byId('proxyHostInput');
        const port = byId('proxyPortInput');
        const username = byId('proxyUsernameInput');
        const password = byId('proxyPasswordInput');

        const payload = {
            host: host ? host.value.trim() : '',
            port: port ? parseInt(port.value, 10) || 0 : 0,
            username: username ? username.value.trim() : '',
            password: password ? password.value : ''
        };

        if (!payload.host) {
            toast(t('toast.error', '错误'), t('page.settings.server.proxy_host_required', '代理主机不能为空'), 'error');
            return;
        }
        if (payload.port <= 0 || payload.port > 65535) {
            toast(t('toast.error', '错误'), t('page.settings.server.proxy_port_invalid', '代理端口必须在 1-65535 之间'), 'error');
            return;
        }

        toast(t('page.settings.server.proxy_testing', '测试中'), t('page.settings.server.proxy_testing_desc', '正在测试代理连接...'), 'info');

        var pTestUrl = appendNodeId('/api/proxy/test', getProxyNodeId());
        fetch(pTestUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        .then(response => response.json())
        .then(data => {
            if (data.success && data.data) {
                if (data.data.success) {
                    toast(t('page.settings.server.proxy_test_success', '代理测试成功'), data.data.message || '', 'success');
                } else {
                    toast(t('page.settings.server.proxy_test_failed', '代理测试失败'), data.data.message || '', 'error');
                }
            } else {
                toast(t('toast.error', '错误'), data.error || t('common.test_failed', '测试失败'), 'error');
            }
        })
        .catch(error => {
            console.error(t('log.proxy.test_error', '测试代理连接出错:'), error);
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        init();
        _initialized = true;
    });

    window.downloadUpdate = downloadUpdate;
    window.applyUpdate = applyUpdate;
    window.cancelUpdateDownload = cancelUpdateDownload;
    window.onAppUpdateEvent = handleAppUpdateEvent;
    window.SettingsPage = { init, load, switchTab, switchUpdateSubTab, openNodeForm, saveNodeForm, editNode, removeNode, testNode, toggleNode, saveNodeRole, loadLlamaCppReleases, downloadLlamaCppAsset, onLlamaCppDownloadProgress };
    window.loadLlamaCppList = loadLlamaCppList;
    window.addLlamaCpp = addLlamaCpp;
    window.editLlamaCpp = editLlamaCpp;
    window.removeLlamaCpp = removeLlamaCpp;
    window.testLlamaCpp = testLlamaCpp;
    window.openAddLlamaCppModal = openAddLlamaCppModal;
    window.toggleLlamaCppSection = toggleLlamaCppSection;
    window.loadModelPathList = loadModelPathList;
    window.addModelPath = addModelPath;
    window.editModelPath = editModelPath;
    window.removeModelPath = removeModelPath;
    window.openAddModelPathModal = openAddModelPathModal;
    window.toggleModelPathSection = toggleModelPathSection;
    window.toggleProxySection = toggleProxySection;
    window.loadProxyConfig = loadProxyConfig;
    window.saveProxyConfig = saveProxyConfig;
    window.testProxyConnection = testProxyConnection;
    window.populateSettingsNodeSelect = populateSettingsNodeSelect;
})();
