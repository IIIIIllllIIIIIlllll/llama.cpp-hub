(function () {
    const byId = (id) => document.getElementById(id);

    let timer = null;
    let pending = [];
    let scheduled = false;
    let snapshotInFlight = false;

    function getEls() {
        return {
            modal: byId('consoleModal'),
            content: byId('consoleContent'),
            container: byId('logContainer'),
            status: byId('consoleStatusText'),
            refreshBtn: byId('refreshConsoleBtn'),
            autoBox: byId('autoRefreshConsole'),
            intervalInput: byId('intervalConsoleInput')
        };
    }

    function nearBottom(container) {
        if (!container) return true;
        return Math.abs(container.scrollHeight - container.scrollTop - container.clientHeight) < 80;
    }

    function scrollBottom(container) {
        if (!container) return;
        container.scrollTop = container.scrollHeight;
    }

    async function fetchConsole() {
        const els = getEls();
        if (els.status) els.status.textContent = '加载中...';
        snapshotInFlight = true;
        try {
            const res = await fetch('/api/sys/console');
            const text = await res.text();
            const stay = nearBottom(els.container);
            if (els.content) els.content.textContent = text;
            snapshotInFlight = false;
            flushAppend();
            if (stay) scrollBottom(els.container);
            if (els.status) {
                els.status.textContent = `已更新 · ${new Date().toLocaleTimeString()} · ${text.length}`;
            }
        } catch (e) {
            snapshotInFlight = false;
            if (els.status) els.status.textContent = '加载失败';
        }
    }

    function stopConsoleAuto() {
        if (timer) {
            clearInterval(timer);
            timer = null;
        }
    }

    function startConsoleAuto() {
        stopConsoleAuto();
        const els = getEls();
        const ms = Math.max(500, parseInt((els.intervalInput && els.intervalInput.value) || '2000', 10) || 2000);
        timer = setInterval(fetchConsole, ms);
    }

    function openConsoleModal() {
        const els = getEls();
        if (!els.modal) return;
        els.modal.classList.add('show');
        fetchConsole().finally(() => {
            setTimeout(() => scrollBottom(els.container), 120);
        });
    }

    function flushAppend() {
        scheduled = false;
        if (snapshotInFlight) {
            return;
        }
        const els = getEls();
        if (!els.content) {
            pending = [];
            return;
        }
        if (!pending.length) return;
        const stay = nearBottom(els.container);
        const chunk = pending.join('');
        pending = [];
        els.content.textContent += chunk;
        if (stay) scrollBottom(els.container);
    }

    function appendLogLine(line) {
        const els = getEls();
        if (!els.modal || !els.modal.classList.contains('show')) return;
        const clean = (line == null ? '' : String(line)).replace(/\r/g, '');
        pending.push(clean.endsWith('\n') ? clean : clean + '\n');
        if (snapshotInFlight) return;
        if (!scheduled) {
            scheduled = true;
            requestAnimationFrame(flushAppend);
        }
    }

    function bind() {
        const els = getEls();
        if (els.refreshBtn) els.refreshBtn.addEventListener('click', fetchConsole);
        if (els.autoBox) {
            els.autoBox.addEventListener('change', function () {
                if (els.autoBox.checked) startConsoleAuto();
                else stopConsoleAuto();
            });
        }
        if (els.intervalInput) {
            els.intervalInput.addEventListener('change', function () {
                if (els.autoBox && els.autoBox.checked) startConsoleAuto();
            });
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        bind();
    });

    window.openConsoleModal = openConsoleModal;
    window.appendLogLine = appendLogLine;
    window.stopConsoleAuto = stopConsoleAuto;
})();
