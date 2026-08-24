/* ================= settings.js — 系统设置：服务器/安全/HTTPS/日志/下载 + 目录浏览器 ================= */
'use strict';

const Settings = {
    _pop: false,   // 回填期间不触发自动保存（同旧版 _populating）

    init() {
        // ===== 服务器 =====
        $('#sysWebPortSave').addEventListener('click', () => {
            const p = parseInt($('#sysWebPort').value, 10);
            if (!p || p < 1 || p > 65535) { toast('端口必须在 1-65535 之间', 'error'); return; }
            this.save({ webPort: p }, '已保存，重启服务后生效');
        });
        $('#sysListenAddressSave').addEventListener('click', () => {
            const addr = $('#sysListenAddress').value.trim();
            if (!addr) { toast('监听地址不能为空', 'error'); return; }
            this.save({ listenAddress: addr }, '已保存，重启服务后生效');
        });
        // ===== 独立 HTTP 专用端口（纯 HTTP，供不支持自签证书的应用） =====
        $('#sysHttpOnlyEnable').addEventListener('change', e => {
            if (this._pop) return;
            const on = e.target.checked;
            const p = parseInt($('#sysHttpOnlyPort').value, 10);
            const fields = { httpOnlyEnabled: on };
            if (p && p >= 1 && p <= 65535) fields.httpOnlyPort = p;
            this.save(fields, (on ? '已启用独立HTTP端口' : '已停用独立HTTP端口') + '，重启服务后生效');
        });
        $('#sysHttpOnlyPortSave').addEventListener('click', () => {
            const p = parseInt($('#sysHttpOnlyPort').value, 10);
            if (!p || p < 1 || p > 65535) { toast('端口必须在 1-65535 之间', 'error'); return; }
            this.save({ httpOnlyEnabled: $('#sysHttpOnlyEnable').checked, httpOnlyPort: p }, '已保存，重启服务后生效');
        });
        $('#sysMcpEnable').addEventListener('change', e => {
            if (this._pop) return;
            const enable = e.target.checked;
            post('/api/sys/mcp', { enable }).then(r => {
                if (r && r.success) toast(enable ? 'MCP Server 已启用' : 'MCP Server 已停用', 'success');
                else { toast((r && r.error) || '保存失败', 'error'); e.target.checked = !enable; }
            }).catch(() => { toast('网络请求失败', 'error'); e.target.checked = !enable; });
        });
        // ===== 安全 =====
        $('#sysApiKeyEnable').addEventListener('change', () => this.updateApiKeyState());
        $('#sysApiKeySave').addEventListener('click', () => {
            const enabled = $('#sysApiKeyEnable').checked;
            const apiKey = $('#sysApiKey').value;
            if (enabled && !apiKey.trim()) { toast('启用验证需要填写 API Key', 'error'); return; }
            this.save({ apiKeyEnabled: enabled, apiKey: apiKey });
        });
        // ===== HTTPS =====
        $('#sysHttpsEnable').addEventListener('change', e => {
            if (this._pop) return;
            const on = e.target.checked;
            this.save({ httpsEnabled: on }, (on ? '已启用 HTTPS' : '已停用 HTTPS') + '，重启服务后生效');
        });
        // ===== HTTPS 证书 =====
        $('#certGenBtn').addEventListener('click', () => UI.openSheet('#certSheet'));
        $('#cfGen').addEventListener('click', () => this.genCert());
        $('#certCopyPwd').addEventListener('click', () => {
            if (!this.certPassword) { toast('没有可复制的密码', 'error'); return; }
            navigator.clipboard.writeText(this.certPassword)
                .then(() => toast('密码已复制', 'success'))
                .catch(() => toast('复制失败', 'error'));
        });
        // ===== 请求日志 =====
        $('#sysLogSave').addEventListener('click', () => {
            this.save({
                logRequestUrl: $('#sysLogUrl').checked,
                logRequestHeader: $('#sysLogHeader').checked,
                logRequestBody: $('#sysLogBody').checked
            });
        });
        // ===== 下载目录 =====
        $('#sysDlDirSave').addEventListener('click', () => {
            const dir = $('#sysDlDir').value.trim();
            if (!dir) { toast('下载目录不能为空', 'error'); return; }
            this.save({ downloadDirectory: dir });
        });
        $('#sysDlDirBrowse').addEventListener('click', () => DirBrowser.open($('#sysDlDir'), ''));
        // ===== 节点角色 =====
        $('#sysNodeRole').addEventListener('change', e => {
            if (this._pop) return;
            this.save({ nodeRole: e.target.value }, '已保存，重启服务后生效');
        });

        DirBrowser.init();
        SettingsNodes.init();
        SettingsUpdate.init();
        this.initAnchor();
    },

    /* 分组切换：点击菜单仅显示对应分组卡片，其它隐藏。
       移动端点开分组后菜单隐藏（.set-layout.open），返回键回到菜单列表；桌面端菜单与内容常驻并排。 */
    initAnchor() {
        const nav = $('#setAnchor');
        if (!nav) return;
        const layout = nav.closest('.set-layout');
        const btns = Array.from(nav.querySelectorAll('button[data-anchor]'));
        const cards = Array.from(layout.querySelectorAll('.set-main > .card'));
        const select = id => {
            btns.forEach(x => x.classList.toggle('active', x.dataset.anchor === id));
            cards.forEach(c => c.classList.toggle('active', c.id === id));
        };
        nav.addEventListener('click', e => {
            const b = e.target.closest('button[data-anchor]');
            if (!b) return;
            select(b.dataset.anchor);
            layout.classList.add('open');
            const page = $('#page-settings');
            if (page) page.scrollTop = 0;
        });
        const back = $('#setBack');
        if (back) back.addEventListener('click', () => layout.classList.remove('open'));
        select('set-appearance');
    },

    load() {
        api('/api/sys/setting').then(r => {
            if (r && r.success && r.data) this.populate(r.data);
        }).catch(() => {});
        this.loadCertStatus();
        SettingsNodes.load();
        SettingsUpdate.load();
    },

    reload() {
        api('/api/sys/setting').then(r => {
            if (r && r.success && r.data) this.populate(r.data);
        }).catch(() => {});
    },

    populate(d) {
        this._pop = true;
        try {
            if (d.server) {
                if (d.server.webPort) $('#sysWebPort').value = d.server.webPort;
                if (d.server.listenAddress) {
                    const la = $('#sysListenAddress');
                    if (la) la.value = d.server.listenAddress;
                }
                const httpOnlyEnable = $('#sysHttpOnlyEnable');
                if (httpOnlyEnable) httpOnlyEnable.checked = !!d.server.httpOnlyEnabled;
                if (d.server.httpOnlyPort) {
                    const httpOnlyPort = $('#sysHttpOnlyPort');
                    if (httpOnlyPort) httpOnlyPort.value = d.server.httpOnlyPort;
                }
            }
            if (d.compat && d.compat.mcpServer) $('#sysMcpEnable').checked = !!d.compat.mcpServer.enabled;
            const sec = d.security || {};
            $('#sysApiKeyEnable').checked = !!sec.apiKeyEnabled;
            if (sec.apiKey) $('#sysApiKey').value = sec.apiKey;
            this.updateApiKeyState();
            if (d.https) $('#sysHttpsEnable').checked = !!d.https.enabled;
            const log = d.logging || {};
            $('#sysLogUrl').checked = !!log.logRequestUrl;
            $('#sysLogHeader').checked = !!log.logRequestHeader;
            $('#sysLogBody').checked = !!log.logRequestBody;
            if (d.download && d.download.directory) $('#sysDlDir').value = d.download.directory;
            const role = String(d.nodeRole || 'slave').toLowerCase();
            $('#sysNodeRole').value = role === 'master' ? 'master' : 'slave';
        } finally { this._pop = false; }
    },

    updateApiKeyState() {
        $('#sysApiKey').disabled = !$('#sysApiKeyEnable').checked;
    },

    /* ================= HTTPS 证书 ================= */
    certPassword: '',   // GET 明文返回的密码只存内存，页面一律掩码显示

    loadCertStatus() {
        api('/api/cert/status').then(r => {
            if (!r || !r.success || !r.data) return;
            const s = r.data;
            this.certPassword = s.password || '';
            if (s.exists) {
                $('#certNone').style.display = 'none';
                $('#certInfo').style.display = '';
                $('#certPath').textContent = s.path || '';
                $('#certPwdMask').textContent = s.password ? '••••••••' : '（无密码）';
                $('#certSize').textContent = fmtSize(s.size);
                $('#certDlBtn').style.display = '';
                $('#certGenBtn').innerHTML = '<i class="fas fa-rotate"></i> 重新生成';
            } else {
                $('#certNone').style.display = '';
                $('#certInfo').style.display = 'none';
                $('#certDlBtn').style.display = 'none';
                $('#certGenBtn').innerHTML = '<i class="fas fa-certificate"></i> 生成证书';
            }
        }).catch(() => {});
    },

    genCert() {
        const ips = $('#cfIps').value.split('\n').map(s => s.trim()).filter(Boolean);
        const hosts = $('#cfHosts').value.split('\n').map(s => s.trim()).filter(Boolean);
        if (!ips.length && !hosts.length) { toast('请至少输入一个 IP 地址或主机名', 'error'); return; }
        const body = {
            ips: ips,
            hostnames: hosts,
            validity: parseInt($('#cfDays').value, 10) || 3650,
            keysize: parseInt($('#cfKeySize').value, 10) || 2048
        };
        const cn = $('#cfCn').value.trim();
        if (cn) body.cn = cn;
        const pwd = $('#cfPwd').value.trim();
        if (pwd) body.password = pwd;
        const btn = $('#cfGen');
        btn.disabled = true;
        $('#cfStatus').innerHTML = '<i class="fas fa-spinner fa-spin"></i> 正在生成证书…';
        post('/api/cert/generate', body).then(r => {
            btn.disabled = false;
            $('#cfStatus').textContent = '';
            if (!r || !r.success) { toast((r && r.error) || '生成失败', 'error'); return; }
            UI.closeSheet();
            toast('证书生成成功，已开始下载', 'success');
            // 生成成功后自动下载证书文件
            const a = document.createElement('a');
            a.href = '/api/cert/download';
            a.download = ((r.data && r.data.path) || '').replace(/\\/g, '/').split('/').pop() || 'keystore.p12';
            document.body.appendChild(a);
            a.click();
            a.remove();
            this.loadCertStatus();
        }).catch(() => {
            btn.disabled = false;
            $('#cfStatus').textContent = '';
            toast('网络请求失败', 'error');
        });
    },

    /* POST /api/sys/setting 部分更新；无论成败都重新回填，保证开关状态与后端一致 */
    save(fields, msg) {
        post('/api/sys/setting', fields).then(r => {
            if (r && r.success) toast(msg || '已保存', 'success');
            else toast((r && r.error) || '保存失败', 'error');
            this.reload();
        }).catch(() => { toast('网络请求失败', 'error'); this.reload(); });
    }
};

/* ================= 简化版目录浏览器（/api/sys/fs/list，仅目录） ================= */
const DirBrowser = {
    target: null, nodeId: '', path: '', parent: null, busy: false, dirs: [],

    init() {
        $('#dbUp').addEventListener('click', () => {
            if (this.busy) return;
            // 有上级则回上级；已到根（parent 为空）则回到盘符列表
            this.load(this.parent || null, !this.parent);
        });
        $('#dbPick').addEventListener('click', () => {
            if (this.target && this.path) this.target.value = this.path;
            UI.closeSheet();
        });
        // 事件委托，避免路径字符串内联进 onclick 的转义问题
        $('#dbList').addEventListener('click', e => {
            const item = e.target.closest('.set-link');
            if (!item || this.busy) return;
            const d = this.dirs[Number(item.dataset.idx)];
            if (d) this.load(d.path);
        });
    },

    /* input: 目标输入框元素；nodeId: 远程节点（'' 表示本机） */
    open(input, nodeId) {
        this.target = input;
        this.nodeId = nodeId || '';
        UI.openSheet('#dirSheet');
        const cur = (input.value || '').trim();
        this.load(cur || null, !cur);
    },

    /* path 为空且 roots=true 时列盘符根目录 */
    load(path, roots) {
        this.busy = true;
        $('#dbList').innerHTML = '<div class="empty"><i class="fas fa-spinner fa-spin"></i> 加载中…</div>';
        let url = '/api/sys/fs/list?dirOnly=true';
        if (path) url += '&path=' + encodeURIComponent(path);
        if (roots) url += '&roots=true';
        if (this.nodeId) url += '&nodeId=' + encodeURIComponent(this.nodeId);
        api(url).then(r => {
            this.busy = false;
            if (!r || !r.success) {
                $('#dbList').innerHTML = '<div class="empty"><i class="fas fa-triangle-exclamation"></i>' + esc((r && r.error) || '加载失败') + '</div>';
                return;
            }
            const d = r.data || {};
            this.path = d.path || '';
            this.parent = d.parent || null;
            $('#dbCur').textContent = this.path || '根目录';
            $('#dbPick').disabled = !this.path;
            const dirs = d.directories || [];
            this.dirs = dirs;
            if (!dirs.length) {
                $('#dbList').innerHTML = '<div class="empty"><i class="fas fa-folder-open"></i>没有子目录</div>';
                return;
            }
            $('#dbList').innerHTML = dirs.map((it, i) =>
                '<div class="set-item set-link" data-idx="' + i + '">' +
                    '<i class="fas fa-folder"></i>' +
                    '<div class="set-main"><div class="set-name">' + esc(it.name || it.path) + '</div></div>' +
                    '<i class="fas fa-chevron-right muted"></i>' +
                '</div>').join('');
        }).catch(() => {
            this.busy = false;
            $('#dbList').innerHTML = '<div class="empty"><i class="fas fa-triangle-exclamation"></i>网络请求失败</div>';
        });
    }
};
