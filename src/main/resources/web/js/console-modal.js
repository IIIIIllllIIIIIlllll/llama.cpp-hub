(function () {
    const logEl = document.getElementById('consoleContent');
    const logContainer = document.getElementById('logContainer');
    const consoleStatusText = document.getElementById('consoleStatusText');
    const refreshConsoleBtn = document.getElementById('refreshConsoleBtn');
    const autoRefreshConsole = document.getElementById('autoRefreshConsole');
    const intervalConsoleInput = document.getElementById('intervalConsoleInput');

    let consoleTimer = null;
    let pendingLogs = [];
    let pendingLogsWithTs = [];
    let flushScheduled = false;
    let snapshotInFlight = false;
    let remoteLinesBuffer = [];
    let savedRemoteCount = 0;

    function nearBottom() {
        if (!logContainer) return true;
        return Math.abs(logContainer.scrollHeight - logContainer.scrollTop - logContainer.clientHeight) < 50;
    }

    function scrollBottom() {
        if (!logContainer) return;
        logContainer.scrollTop = logContainer.scrollHeight;
    }

    async function fetchConsole() {
        if (consoleStatusText) consoleStatusText.textContent = '加载中...';
        snapshotInFlight = true;
        savedRemoteCount = remoteLinesBuffer.length;
        try {
            const res = await fetch('/api/sys/console');
            const text = await res.text();
            const atBottom = nearBottom();
            if (logEl) logEl.textContent = text;
            snapshotInFlight = false;
            flushPendingLogs();
            var restoreChunk = '';
            for (var i = 0; i < savedRemoteCount; i++) {
                restoreChunk += remoteLinesBuffer[i].text;
            }
            if (restoreChunk) {
                logEl.textContent += restoreChunk;
            }
            if (atBottom) scrollBottom();
            if (consoleStatusText) {
                consoleStatusText.textContent = '已更新 · ' + new Date().toLocaleTimeString() + ' · Size: ' + text.length;
            }
        } catch (e) {
            snapshotInFlight = false;
            if (consoleStatusText) consoleStatusText.textContent = '加载失败';
        }
    }

    function openConsoleModal() {
        fetchConsole();
        setTimeout(function () {
            scrollBottom();
        }, 100);
        if (autoRefreshConsole && autoRefreshConsole.checked) startConsoleAuto();
    }

    function appendLogLine(line, timestamp) {
        if (!logEl) return;
        const clean = (line || '').replace(/\r/g, '');
        const withNl = clean.endsWith('\n') ? clean : clean + '\n';
        pendingLogs.push(withNl);
        pendingLogsWithTs.push({ text: withNl, ts: typeof timestamp === 'number' ? timestamp : 0 });
        if (line && line.charCodeAt(0) === 91) {
            remoteLinesBuffer.push({ text: withNl, ts: typeof timestamp === 'number' ? timestamp : 0 });
        }
        if (snapshotInFlight) return;
        scheduleFlush();
    }

    function scheduleFlush() {
        if (!flushScheduled) {
            flushScheduled = true;
            requestAnimationFrame(function () {
                flushPendingLogs();
            });
        }
    }

    function flushPendingLogs() {
        flushScheduled = false;
        if (snapshotInFlight || !pendingLogs.length || !logEl) return;
        const atBottom = nearBottom();
        pendingLogsWithTs.sort(function (a, b) { return a.ts - b.ts; });
        var chunk = '';
        for (var i = 0; i < pendingLogsWithTs.length; i++) {
            chunk += pendingLogsWithTs[i].text;
        }
        pendingLogs = [];
        pendingLogsWithTs = [];
        logEl.textContent += chunk;
        if (atBottom) scrollBottom();
    }

    function startConsoleAuto() {
        stopConsoleAuto();
        const interval = Math.max(500, parseInt((intervalConsoleInput && intervalConsoleInput.value) || '2000', 10));
        consoleTimer = setInterval(fetchConsole, interval);
    }

    function stopConsoleAuto() {
        if (consoleTimer) {
            clearInterval(consoleTimer);
            consoleTimer = null;
        }
    }

    if (refreshConsoleBtn) refreshConsoleBtn.addEventListener('click', fetchConsole);
    if (autoRefreshConsole) {
        autoRefreshConsole.addEventListener('change', function () {
            if (autoRefreshConsole.checked) startConsoleAuto();
            else stopConsoleAuto();
        });
    }

    window.openConsoleModal = openConsoleModal;
    window.appendLogLine = appendLogLine;
    window.stopConsoleAuto = stopConsoleAuto;
})();
