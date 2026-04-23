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
            const anthropicPort = byId('anthropicPortInput');
            if (anthropicPort && s.anthropicPort) anthropicPort.value = s.anthropicPort;
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
        const anthropicPort = byId('anthropicPortInput');
        const payload = {};
        if (webPort && webPort.value) payload.webPort = Number(webPort.value);
        if (anthropicPort && anthropicPort.value) payload.anthropicPort = Number(anthropicPort.value);
        if (!payload.webPort && !payload.anthropicPort) {
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

    function init() {
        // Tab switching
        document.querySelectorAll('.settings-tab').forEach(tab => {
            tab.addEventListener('click', function () {
                switchTab(this.getAttribute('data-tab'));
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

        // Logging tab
        const saveLoggingBtn = byId('saveLoggingBtn');
        if (saveLoggingBtn) saveLoggingBtn.addEventListener('click', saveLogging);

        // Download tab
        const saveDownloadBtn = byId('saveDownloadBtn');
        if (saveDownloadBtn) saveDownloadBtn.addEventListener('click', saveDownload);
    }

    let _initialized = false;
    function load() {
        if (!_initialized) {
            init();
            _initialized = true;
        }
        loadSettings();
    }

    document.addEventListener('DOMContentLoaded', function () {
        init();
        _initialized = true;
    });

    window.SettingsPage = { init, load };
})();
