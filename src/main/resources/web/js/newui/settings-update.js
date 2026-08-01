/* ================= settings-update.js — 设置：应用更新 + llama.cpp release 下载 ================= */
'use strict';

const SettingsUpdate = {
    release: null,        // 应用更新 check 到的 release
    hasUpdate: false,
    lcData: null,         // llama.cpp release 数据
    lcInstalled: [],      // localBackends
    lcCudarts: [],        // localCudarts
    lcDownloading: {},    // assetName -> taskId

    init() {
        $('#updCheck').addEventListener('click', () => this.check());
        $('#updDownload').addEventListener('click', () => this.download());
        $('#updApply').addEventListener('click', () => this.apply());
        $('#updCancel').addEventListener('click', () => this.cancel());
        $('#lcRelLoad').addEventListener('click', () => this.loadLcReleases());
    },

    load() { this.restore(); },

    proxy() { return $('#updProxy').value; },

    updError(msg) {
        const el = $('#updErr');
        el.textContent = msg;
        el.style.display = msg ? '' : 'none';
    },

    /* ================= 应用更新 ================= */
    check() {
        const btn = $('#updCheck');
        btn.disabled = true;
        this.updError('');
        api('/api/sys/update/check').then(r => {
            btn.disabled = false;
            if (!r || !r.success) { this.updError((r && r.error) || '检查失败'); return; }
            const d = r.data || {};
            this.release = d.release || null;
            this.hasUpdate = !!d.hasUpdate;
            const cur = d.currentTag || '-';
            const placeholder = !cur || cur.indexOf('{tag}') >= 0;
            $('#updCurrent').textContent = placeholder ? '自编译版本' : cur;
            if (d.error) { this.updError(d.error); return; }
            $('#updBox').style.display = '';
            $('#updLatest').textContent = this.release ? (this.release.tag_name || '-') : '-';
            $('#updTime').textContent = this.release && this.release.published_at
                ? new Date(this.release.published_at).toLocaleString() : '-';
            $('#updBadge').innerHTML = this.hasUpdate
                ? '<span class="badge" style="color:var(--primary)">发现新版本</span>'
                : '<span class="badge">已是最新版本</span>';
            const link = $('#updUrl');
            if (this.release && this.release.html_url) {
                link.href = this.release.html_url;
                link.style.display = '';
            } else link.style.display = 'none';
            let body = (this.release && this.release.body) || '';
            if (body.length > 2000) body = body.substring(0, 2000) + '…';
            $('#updBody').textContent = body;
            // 下载按钮：非自编译 + 有新版本
            if (!placeholder && this.hasUpdate && this.release) {
                const dl = $('#updDownload');
                dl.dataset.tag = this.release.tag_name;
                dl.innerHTML = '<i class="fas fa-download"></i> 下载 ' + esc(this.release.tag_name);
            }
            this.restore();   // 状态机交给 restore（ready/downloading/idle）
        }).catch(() => { btn.disabled = false; this.updError('网络请求失败'); });
    },

    download() {
        const tag = $('#updDownload').dataset.tag || '';
        if (!tag || tag.indexOf('{tag}') >= 0) { this.updError('当前版本不支持自动更新'); return; }
        let url = 'https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub/releases/download/' + tag +
            '/llama.cpp-hub-' + tag + '.zip';
        if (this.proxy()) url = this.proxy() + url;
        $('#updDownload').style.display = 'none';
        this.showProgress(0, 0, 0);
        post('/api/sys/update/download', { url: url, version: tag }).then(r => {
            if (!r || !r.success) {
                $('#updProg').style.display = 'none';
                this.updError((r && r.error) || '下载失败');
                this.renderIdle();
            }
        }).catch(() => {
            $('#updProg').style.display = 'none';
            this.updError('网络请求失败');
            this.renderIdle();
        });
    },

    apply() {
        if (!confirm('应用更新将替换程序文件，确认继续？')) return;
        const btn = $('#updApply');
        btn.disabled = true;
        this.updError('');
        post('/api/sys/update/apply', {}).then(r => {
            if (r && r.success) {
                btn.style.display = 'none';
                toast((r.data && r.data.message) || '更新已应用，请重启程序生效', 'success');
            } else {
                this.updError((r && r.error) || '应用更新失败');
                btn.disabled = false;
            }
        }).catch(() => { this.updError('网络请求失败'); btn.disabled = false; });
    },

    cancel() {
        post('/api/sys/update/cancel', {}).then(r => {
            if (r && r.success) this.restore();
        }).catch(() => {});
    },

    /* 进入设置页时恢复更新状态机 */
    restore() {
        api('/api/sys/update/status').then(r => {
            if (!r || !r.success || !r.data) return;
            const sd = r.data;
            if (sd.currentVersion) {
                $('#updCurrent').textContent = sd.currentVersion.indexOf('{') >= 0 ? '自编译版本' : sd.currentVersion;
            }
            if (sd.status === 'ready') {
                $('#updProg').style.display = 'none';
                $('#updDownload').style.display = 'none';
                const ap = $('#updApply');
                ap.style.display = '';
                ap.disabled = false;
                ap.innerHTML = '<i class="fas fa-check"></i> 应用 ' + esc(sd.pendingVersion || '更新');
            } else if (sd.status === 'downloading') {
                $('#updApply').style.display = 'none';
                $('#updDownload').style.display = 'none';
                this.showProgress(sd.progressRatio, sd.downloadedBytes, sd.totalBytes);
            } else if (sd.status === 'applying') {
                $('#updProg').style.display = 'none';
                $('#updApply').style.display = 'none';
                $('#updDownload').style.display = 'none';
            } else {
                this.renderIdle();
            }
        }).catch(() => {});
    },

    /* idle：隐藏进度/应用，仅在已知有新版本时显示下载按钮 */
    renderIdle() {
        $('#updProg').style.display = 'none';
        $('#updApply').style.display = 'none';
        const dl = $('#updDownload');
        if (this.release && this.hasUpdate && dl.dataset.tag) {
            dl.style.display = '';
            dl.disabled = false;
        } else dl.style.display = 'none';
    },

    showProgress(ratio, dl, total) {
        $('#updProg').style.display = '';
        this.updError('');
        const pct = Math.round((ratio >= 0 ? ratio : 0) * 100);
        $('#updBar').style.width = pct + '%';
        $('#updProgPct').textContent = pct + '%';
        $('#updProgText').textContent = '下载更新包…';
        $('#updProgSize').textContent = total > 0 ? (fmtSize(dl) + ' / ' + fmtSize(total)) : '';
    },

    /* WS app_update 推送 */
    onAppUpdate(d) {
        if (!d || !d.status) return;
        if (d.status === 'downloading') {
            $('#updDownload').style.display = 'none';
            $('#updApply').style.display = 'none';
            this.showProgress(d.progressRatio, d.downloadedBytes, d.totalBytes);
        } else if (d.status === 'completed') {
            $('#updProg').style.display = 'none';
            $('#updDownload').style.display = 'none';
            const ap = $('#updApply');
            ap.style.display = '';
            ap.disabled = false;
            ap.innerHTML = '<i class="fas fa-check"></i> 应用 ' + esc(d.version || '更新');
            toast('更新包下载完成', 'success');
        } else if (d.status === 'failed') {
            $('#updProg').style.display = 'none';
            this.updError(d.errorMessage || '下载失败');
            this.renderIdle();
        }
    },

    /* ================= llama.cpp release 下载 ================= */
    loadLcReleases() {
        const btn = $('#lcRelLoad');
        btn.disabled = true;
        $('#lcRelInfo').textContent = '正在获取…';
        let url = '/api/llamacpp/release/latest';
        if (this.proxy()) url += '?proxy=' + encodeURIComponent(this.proxy());
        api(url).then(r => {
            btn.disabled = false;
            if (!r || !r.success || !r.data) {
                $('#lcRelInfo').textContent = (r && r.error) || '加载失败';
                return;
            }
            this.lcData = r.data;
            this.lcInstalled = Array.isArray(r.data.localBackends) ? r.data.localBackends : [];
            this.lcCudarts = Array.isArray(r.data.localCudarts) ? r.data.localCudarts : [];
            let info = '最新版本：' + (r.data.tag_name || '-');
            if (r.data.published_at) info += ' · ' + new Date(r.data.published_at).toLocaleString();
            $('#lcRelInfo').innerHTML = esc(info) +
                (r.data.html_url ? ' · <a class="link" target="_blank" rel="noopener" href="' + esc(r.data.html_url) + '">Release</a>' : '');
            this.renderLcAssets();
        }).catch(() => { btn.disabled = false; $('#lcRelInfo').textContent = '网络请求失败'; });
    },

    renderLcAssets() {
        const assets = (this.lcData && this.lcData.assets) || [];
        if (!assets.length) {
            $('#lcAssets').innerHTML = '<div class="empty"><i class="fas fa-file-zipper"></i>没有可用的下载文件</div>';
            return;
        }
        $('#lcAssets').innerHTML = assets.map(a => {
            const downloading = !!this.lcDownloading[a.name];
            const backend = this.parseBackendName(a.name);
            const installed = this.lcInstalled.indexOf(backend) >= 0 || this.lcCudarts.indexOf(backend) >= 0;
            let act;
            if (downloading) act = '<span class="muted"><i class="fas fa-spinner fa-spin"></i> 下载中</span>';
            else if (installed) act = '<span class="badge" style="color:var(--success)"><i class="fas fa-check"></i> 已安装</span>';
            else act = '<button class="btn mini primary" onclick="SettingsUpdate.downloadAsset(\'' + esc(a.name) + '\')"><i class="fas fa-download"></i> 下载</button>';
            return '<div class="set-item" data-asset="' + esc(a.name) + '">' +
                '<i class="fas fa-file-zipper"></i>' +
                '<div class="set-main">' +
                    '<div class="set-name">' + esc(a.name) + '</div>' +
                    '<div class="set-sub">' + fmtSize(a.size) + ' · 下载量 ' + (a.download_count || 0) + '</div>' +
                    '<div class="progress-track" style="display:' + (downloading ? '' : 'none') + ';margin-top:6px">' +
                        '<div class="progress-fill" style="width:0%"></div></div>' +
                '</div>' +
                '<div class="set-actions">' + act + '</div></div>';
        }).join('');
    },

    downloadAsset(name) {
        const a = ((this.lcData && this.lcData.assets) || []).find(x => x.name === name);
        if (!a) return;
        let url = a.browser_download_url;
        if (this.proxy()) url = this.proxy() + url;
        this.lcDownloading[name] = true;
        this.renderLcAssets();
        post('/api/downloads/create', { url: url, path: 'llamacpp', fileName: name }).then(r => {
            if (r && r.success && r.taskId) {
                this.lcDownloading[name] = r.taskId;
                this.renderLcAssets();
            } else {
                delete this.lcDownloading[name];
                this.renderLcAssets();
                toast((r && r.error) || '下载创建失败', 'error');
            }
        }).catch(() => {
            delete this.lcDownloading[name];
            this.renderLcAssets();
            toast('网络请求失败', 'error');
        });
    },

    /* WS download_progress/download_update 推送（与下载页共享事件） */
    onLlamaProgress(d) {
        if (!d || !d.taskId) return;
        let name = null;
        for (const k in this.lcDownloading) {
            if (this.lcDownloading[k] === d.taskId) { name = k; break; }
        }
        if (!name) return;
        const ratio = d.progress != null ? d.progress : d.progressRatio;
        const row = $('#lcAssets').querySelector('[data-asset="' + name + '"]');
        if (row && ratio != null) {
            const track = row.querySelector('.progress-track');
            track.style.display = '';
            track.querySelector('.progress-fill').style.width = Math.round(ratio * 100) + '%';
        }
        if (d.state === 'COMPLETED' || d.state === 'FAILED') {
            delete this.lcDownloading[name];
            if (d.state === 'COMPLETED') {
                toast(name + ' 下载完成', 'success');
                // 等后端解压整理完再刷新安装状态（同旧版 1.5s）
                setTimeout(() => this.loadLcReleases(), 1500);
            } else {
                toast(name + ' 下载失败', 'error');
                this.renderLcAssets();
            }
        }
    },

    /* llama-b1234-bin-win-vulkan-x64.zip → llama-b1234-bin-win-vulkan-x64（.tar 再去一层，同旧版） */
    parseBackendName(fileName) {
        if (!fileName) return '';
        let base = fileName;
        let dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        if (base.endsWith('.tar')) {
            dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
        }
        return base;
    }
};
