(function () {
    const byId = (id) => document.getElementById(id);

    function toast(title, msg, type) {
        if (typeof window.showToast === 'function') window.showToast(title, msg, type);
    }

    function openModal(id) {
        const el = byId(id);
        if (el) el.classList.add('show');
    }

    async function shutdownService() {
        if (!confirm('确定要停止服务吗？')) return;
        try {
            const resp = await fetch('/api/shutdown', { method: 'POST' });
            const data = await resp.json();
            if (data && data.success) {
                document.body.innerHTML = '<div style="width:100%; height:100vh; display:flex; align-items:center; justify-content:center; padding: 1.5rem;"><h1 style="margin:0; font-size: 1.5rem;">服务已停止</h1></div>';
                return;
            }
            toast('错误', (data && data.error) ? data.error : '停止失败', 'error');
        } catch (e) {
            toast('错误', '网络请求失败', 'error');
        }
    }

    function go(page) {
        if (window.MobilePage && typeof window.MobilePage.show === 'function') window.MobilePage.show(page);
    }

    function readPortFromInput(id) {
        const el = byId(id);
        if (!el) return null;
        const raw = String(el.value || '').trim();
        if (!raw) return null;
        const n = Number(raw);
        if (!Number.isFinite(n)) return null;
        const port = Math.trunc(n);
        if (port <= 0 || port > 65535) return null;
        return port;
    }

    async function loadCompatServiceStatus() {
        try {
            const response = await fetch('/api/sys/compat/status', { method: 'GET' });
            const data = await response.json();
            if (!data || !data.success || !data.data) return;

            const ollama = byId('mobileToggleOllamaCompat');
            if (ollama && data.data.ollama) {
                const enabled = (typeof data.data.ollama.enabled === 'boolean')
                    ? !!data.data.ollama.enabled
                    : (typeof data.data.ollama.running === 'boolean' ? !!data.data.ollama.running : false);
                ollama.checked = enabled;
            }
            const ollamaPort = byId('mobileOllamaCompatPortInput');
            if (ollamaPort && data.data.ollama) {
                const p = data.data.ollama.configuredPort || data.data.ollama.port;
                if (typeof p === 'number' && p > 0) ollamaPort.value = String(p);
            }

            const lmstudio = byId('mobileToggleLmstudioCompat');
            if (lmstudio && data.data.lmstudio) {
                const enabled = (typeof data.data.lmstudio.enabled === 'boolean')
                    ? !!data.data.lmstudio.enabled
                    : (typeof data.data.lmstudio.running === 'boolean' ? !!data.data.lmstudio.running : false);
                lmstudio.checked = enabled;
            }
            const lmstudioPort = byId('mobileLmstudioCompatPortInput');
            if (lmstudioPort && data.data.lmstudio) {
                const p = data.data.lmstudio.configuredPort || data.data.lmstudio.port;
                if (typeof p === 'number' && p > 0) lmstudioPort.value = String(p);
            }
        } catch (e) {
        }
    }

    async function saveCompatPorts() {
        const ollamaPort = readPortFromInput('mobileOllamaCompatPortInput');
        const lmstudioPort = readPortFromInput('mobileLmstudioCompatPortInput');
        if (!ollamaPort && !lmstudioPort) {
            toast('错误', '请至少填写一个端口', 'error');
            return;
        }
        const payload = {};
        if (ollamaPort) payload.ollamaPort = ollamaPort;
        if (lmstudioPort) payload.lmstudioPort = lmstudioPort;
        try {
            const response = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await response.json();
            if (!data || !data.success) {
                toast('错误', (data && data.error) ? data.error : '保存失败', 'error');
                return;
            }
            toast('成功', '端口已保存', 'success');
            loadCompatServiceStatus();
        } catch (e) {
            toast('错误', '网络请求失败', 'error');
        }
    }

    async function setCompatServiceEnabled(type, enable, toggleEl) {
        const endpoint = type === 'ollama' ? '/api/sys/ollama' : '/api/sys/lmstudio';
        const prev = !enable;
        if (toggleEl) toggleEl.disabled = true;
        try {
            const body = { enable: !!enable };
            const port = type === 'ollama'
                ? readPortFromInput('mobileOllamaCompatPortInput')
                : readPortFromInput('mobileLmstudioCompatPortInput');
            if (port) body.port = port;
            const response = await fetch(endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            const data = await response.json();
            if (!data || !data.success) {
                if (toggleEl) toggleEl.checked = prev;
                toast('错误', (data && data.error) ? data.error : '操作失败', 'error');
                return;
            }
            toast('成功', enable ? '已开启' : '已关闭', 'success');
            loadCompatServiceStatus();
        } catch (e) {
            if (toggleEl) toggleEl.checked = prev;
            toast('错误', '网络请求失败', 'error');
        } finally {
            if (toggleEl) toggleEl.disabled = false;
        }
    }

    async function saveZhipuSearchApiKey() {
        const el = byId('mobileZhipuSearchApiKeyInput');
        const apiKey = el ? String(el.value || '').trim() : '';
        try {
            const response = await fetch('/api/search/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ zhipu_search_apikey: apiKey })
            });
            const data = await response.json();
            if (!data || !data.success) {
                toast('错误', (data && data.error) ? data.error : '保存失败', 'error');
                return;
            }
            toast('成功', '已保存', 'success');
            if (el) el.value = '';
            if (typeof window.closeModal === 'function') window.closeModal('mobileWebSearchSettingsModal');
        } catch (e) {
            toast('错误', '网络请求失败', 'error');
        }
    }

    function openGeneralSettingsModal() {
        openModal('mobileGeneralSettingsModal');
        loadCompatServiceStatus();
    }

    function openWebSearchModal() {
        openModal('mobileWebSearchSettingsModal');
    }

    function bind() {
        const generalBtn = byId('mobileSettingsGeneralBtn');
        const llamaBtn = byId('mobileSettingsLlamaCppBtn');
        const pathBtn = byId('mobileSettingsModelPathBtn');
        const consoleBtn = byId('mobileSettingsConsoleBtn');
        if (generalBtn) generalBtn.addEventListener('click', openGeneralSettingsModal);
        if (llamaBtn) llamaBtn.addEventListener('click', function () { go('llamacpp'); });
        if (pathBtn) pathBtn.addEventListener('click', function () { go('modelpaths'); });
        if (consoleBtn) consoleBtn.addEventListener('click', function () {
            if (typeof window.openConsoleModal === 'function') window.openConsoleModal();
        });

        const savePortsBtn = byId('mobileSaveCompatPortsBtn');
        if (savePortsBtn) savePortsBtn.addEventListener('click', saveCompatPorts);

        const saveZhipuBtn = byId('mobileSaveZhipuSearchApiKeyBtn');
        if (saveZhipuBtn) saveZhipuBtn.addEventListener('click', saveZhipuSearchApiKey);

        const ollama = byId('mobileToggleOllamaCompat');
        if (ollama) ollama.addEventListener('change', () => setCompatServiceEnabled('ollama', ollama.checked, ollama));

        const lmstudio = byId('mobileToggleLmstudioCompat');
        if (lmstudio) lmstudio.addEventListener('change', () => setCompatServiceEnabled('lmstudio', lmstudio.checked, lmstudio));
    }

    document.addEventListener('DOMContentLoaded', function () {
        bind();
    });

    window.MobileSettings = { shutdownService, openGeneralSettingsModal, openWebSearchModal, loadCompatServiceStatus };
})();
