'use strict';

/* =========================================================
 * OpenAI Chat —— 纯前端聊天应用
 * 通过 CDN 加载 OpenAI 官方 SDK（浏览器模式），
 * 设置 / 对话记录 / 提示词全部保存在 localStorage。
 * ========================================================= */

/* ===================== 本地存储 ===================== */
const LS = {
  settings: 'oai_settings',
  conversations: 'oai_conversations',
  prompts: 'oai_prompts',
  currentId: 'oai_current_id',
};

const DEFAULT_SETTINGS = {
  apiKey: '',
  baseURL: 'https://api.openai.com/v1',
  model: 'gpt-4o-mini',
  systemPrompt: '',
  temperature: null, // null = 使用 API 默认值
  maxTokens: null,
  stream: true,
  theme: 'auto', // light | dark | auto
};

const DEFAULT_PROMPTS = [
  { id: 'p_translate', title: '翻译为英文', content: '请将以下内容翻译为地道的英文，只输出译文：\n', createdAt: Date.now() },
  { id: 'p_review', title: '代码审查', content: '请审查以下代码，指出潜在的 bug、性能问题和可改进之处：\n\n```\n\n```', createdAt: Date.now() },
];

function load(key, fallback) {
  try {
    const v = localStorage.getItem(key);
    return v === null ? fallback : JSON.parse(v);
  } catch {
    return fallback;
  }
}
function save(key, val) {
  try {
    localStorage.setItem(key, JSON.stringify(val));
  } catch (e) {
    console.warn('localStorage 保存失败：', e);
  }
}

let settings = { ...DEFAULT_SETTINGS, ...load(LS.settings, {}) };
let conversations = load(LS.conversations, []);
let prompts = load(LS.prompts, null) || DEFAULT_PROMPTS.slice();
let currentId = load(LS.currentId, null);

const saveSettings = () => save(LS.settings, settings);
const saveConversations = () => save(LS.conversations, conversations);
const savePrompts = () => save(LS.prompts, prompts);

const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
const $ = (sel) => document.querySelector(sel);

/* ===================== 依赖动态加载（CDN，失败自动降级） ===================== */
let marked = null;
let DOMPurify = null;
let hljs = null;
let OpenAISDK = null;

async function loadDeps() {
  const results = await Promise.allSettled([
    import('https://esm.sh/marked@12'),
    import('https://esm.sh/dompurify@3'),
    import('https://esm.sh/highlight.js@11/lib/common'),
  ]);
  if (results[0].status === 'fulfilled') marked = results[0].value.marked;
  if (results[1].status === 'fulfilled') DOMPurify = results[1].value.default;
  if (results[2].status === 'fulfilled') hljs = results[2].value.default;
}

async function makeClient(apiKey, baseURL) {
  if (!OpenAISDK) {
    const mod = await import('https://esm.sh/openai@4');
    OpenAISDK = mod.default;
  }
  return new OpenAISDK({
    apiKey,
    baseURL: baseURL || DEFAULT_SETTINGS.baseURL,
    dangerouslyAllowBrowser: true, // 纯前端应用，Key 仅存本地浏览器
  });
}

/* ===================== 渲染工具 ===================== */
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function renderMarkdown(text) {
  if (marked && DOMPurify) {
    return DOMPurify.sanitize(marked.parse(text, { breaks: true, gfm: true }));
  }
  // 降级：纯文本转义显示
  return escapeHtml(text).replace(/\n/g, '<br>');
}

// 给代码块加复制按钮；highlight 为 true 时做语法高亮（流式过程中跳过以节省性能）
function postProcess(el, { highlight = true } = {}) {
  el.querySelectorAll('pre').forEach((pre) => {
    if (pre.parentElement.classList.contains('code-block')) return;
    const wrap = document.createElement('div');
    wrap.className = 'code-block';
    pre.parentNode.insertBefore(wrap, pre);
    wrap.appendChild(pre);
    const btn = document.createElement('button');
    btn.className = 'copy-code';
    btn.type = 'button';
    btn.textContent = '复制';
    wrap.appendChild(btn);
  });
  if (highlight && hljs) {
    el.querySelectorAll('pre code').forEach((code) => {
      try { hljs.highlightElement(code); } catch { /* 未知语言，跳过 */ }
    });
  }
}

function errMsg(e) {
  const m = e?.error?.message || e?.message || String(e);
  return m.length > 300 ? m.slice(0, 300) + '…' : m;
}

/* ===================== 主题 ===================== */
function effectiveTheme() {
  if (settings.theme === 'auto') {
    return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  return settings.theme;
}

function applyTheme() {
  const t = effectiveTheme();
  document.documentElement.dataset.theme = t;
  $('#hljs-light').disabled = t === 'dark';
  $('#hljs-dark').disabled = t !== 'dark';
  $('#toggle-theme').textContent = t === 'dark' ? '☀ 浅色' : '☾ 深色';
}

/* ===================== 状态栏 ===================== */
function updateStatus() {
  $('#model-badge').textContent = settings.model || '';
  $('#status-bar').textContent =
    `${settings.model || '未设置模型'} · 流式${settings.stream ? '开启' : '关闭'} · Enter 发送，Shift+Enter 换行`;
}

/* ===================== 侧栏 ===================== */
function renderSidebar() {
  const sorted = [...conversations].sort((a, b) => b.updatedAt - a.updatedAt);
  $('#conv-list').innerHTML = sorted.map((c) => `
    <div class="conv-item ${c.id === currentId ? 'active' : ''}" data-id="${c.id}">
      <span class="conv-title">${escapeHtml(c.title)}</span>
      <span class="conv-ops">
        <button type="button" data-op="rename" title="重命名">✎</button>
        <button type="button" data-op="del" title="删除">✕</button>
      </span>
    </div>`).join('') || '<div class="empty-hint">暂无对话</div>';

  $('#prompt-list').innerHTML = prompts.map((p) => `
    <button type="button" class="prompt-chip" data-id="${p.id}"
      title="${escapeHtml(p.content)}">${escapeHtml(p.title)}</button>`).join('')
    || '<div class="empty-hint">暂无提示词，点「管理」添加</div>';
}

/* ===================== 消息区 ===================== */
let generating = false;
let abortCtrl = null;
let liveStream = null; // { msgId, el } —— 正在流式输出的消息
let stickBottom = true;

const currentConv = () => conversations.find((c) => c.id === currentId) || null;

function messageHtml(m, isLast) {
  const isLive = liveStream && liveStream.msgId === m.id;
  const time = new Date(m.ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  let meta = time;
  if (m.model && m.role === 'assistant') meta += ` · ${escapeHtml(m.model)}`;
  if (m.usage) meta += ` · ${m.usage.total_tokens} tokens`;

  let body;
  if (isLive && !m.content) {
    body = '<span class="typing"><span></span><span></span><span></span></span>';
  } else {
    body = renderMarkdown(m.content);
  }

  const actions = [];
  if (m.content && !m.error) actions.push('<button type="button" data-act="copy">复制</button>');
  if (m.role === 'user') actions.push('<button type="button" data-act="edit">编辑</button>');
  if (!generating) actions.push('<button type="button" data-act="del">删除</button>');
  if (m.role === 'assistant' && isLast && !generating) {
    actions.push('<button type="button" data-act="regen">重新生成</button>');
  }

  return `
  <div class="msg ${m.role}${m.error ? ' error' : ''}" data-id="${m.id}">
    <div class="msg-body">
      <div class="msg-content" data-mid="${m.id}">${body}</div>
      <div class="msg-meta">${meta}</div>
      <div class="msg-actions">${actions.join('')}</div>
    </div>
  </div>`;
}

function renderMessages({ scroll = true } = {}) {
  const box = $('#messages');
  const conv = currentConv();
  $('#chat-title').textContent = conv ? conv.title : '新对话';

  if (!conv || conv.messages.length === 0) {
    box.innerHTML = `
      <div class="welcome">
        <h1>OpenAI Chat</h1>
        <p>基于 OpenAI 官方 SDK 的纯前端聊天应用，所有数据保存在浏览器本地。</p>
        ${settings.apiKey ? '<p>在下方输入消息开始对话。</p>' : '<p class="warn">尚未配置 API Key，请点击左下角「⚙ 设置」。</p>'}
      </div>`;
    return;
  }

  box.innerHTML = conv.messages
    .map((m, i) => messageHtml(m, i === conv.messages.length - 1))
    .join('');
  box.querySelectorAll('.msg-content').forEach((el) => {
    const isLive = liveStream && liveStream.msgId === el.dataset.mid;
    postProcess(el, { highlight: !isLive });
  });
  if (scroll) scrollToBottom();
}

function scrollToBottom() {
  const el = $('#messages');
  el.scrollTop = el.scrollHeight;
  stickBottom = true;
}
function maybeScroll() {
  if (stickBottom) {
    const el = $('#messages');
    el.scrollTop = el.scrollHeight;
  }
}

/* ===================== 发送与生成 ===================== */
function updateComposerButtons() {
  $('#send').hidden = generating;
  $('#stop').hidden = !generating;
}

async function doSend(text) {
  if (generating) return;
  text = text.trim();
  if (!text) return;
  if (!settings.apiKey) {
    alert('请先在「设置」中填写 API Key');
    openSettings();
    return;
  }

  let conv = currentConv();
  if (!conv) {
    conv = { id: uid(), title: '新对话', createdAt: Date.now(), updatedAt: Date.now(), messages: [] };
    conversations.push(conv);
    currentId = conv.id;
    save(LS.currentId, currentId);
  }
  conv.messages.push({ id: uid(), role: 'user', content: text, ts: Date.now() });
  if (conv.title === '新对话') {
    conv.title = text.slice(0, 24) + (text.length > 24 ? '…' : '');
  }
  conv.updatedAt = Date.now();
  saveConversations();
  renderSidebar();
  renderMessages();
  await generate(conv);
}

async function generate(conv) {
  const msg = { id: uid(), role: 'assistant', content: '', ts: Date.now(), model: settings.model };
  conv.messages.push(msg);
  conv.updatedAt = Date.now();
  generating = true;
  abortCtrl = new AbortController();
  updateComposerButtons();
  renderMessages();
  liveStream = { msgId: msg.id, el: document.querySelector(`.msg[data-id="${msg.id}"] .msg-content`) };

  // 组装发给 API 的消息（不含刚插入的空 assistant 占位）
  const apiMsgs = [];
  if (settings.systemPrompt.trim()) {
    apiMsgs.push({ role: 'system', content: settings.systemPrompt.trim() });
  }
  for (const m of conv.messages) {
    if (m.id === msg.id) break;
    if ((m.role === 'user' || m.role === 'assistant') && m.content && !m.error) {
      apiMsgs.push({ role: m.role, content: m.content });
    }
  }

  const params = {
    model: settings.model || DEFAULT_SETTINGS.model,
    messages: apiMsgs,
    stream: settings.stream,
  };
  if (settings.temperature !== null && settings.temperature !== '') {
    params.temperature = Number(settings.temperature);
  }
  if (settings.maxTokens) params.max_tokens = Number(settings.maxTokens);
  if (settings.stream) params.stream_options = { include_usage: true };

  // 流式期间节流刷新，避免每个 token 都重建 DOM
  let dirty = false;
  let timer = null;
  const flush = () => {
    timer = null;
    if (dirty && liveStream && liveStream.el && liveStream.el.isConnected) {
      dirty = false;
      liveStream.el.innerHTML = renderMarkdown(msg.content);
      postProcess(liveStream.el, { highlight: false });
      maybeScroll();
    }
  };

  try {
    const client = await makeClient(settings.apiKey, settings.baseURL);
    if (settings.stream) {
      const stream = await client.chat.completions.create(params, { signal: abortCtrl.signal });
      for await (const chunk of stream) {
        const delta = chunk.choices?.[0]?.delta?.content;
        if (delta) {
          msg.content += delta;
          dirty = true;
          if (!timer) timer = setTimeout(flush, 60);
        }
        if (chunk.usage) msg.usage = chunk.usage;
      }
    } else {
      const resp = await client.chat.completions.create(params, { signal: abortCtrl.signal });
      msg.content = resp.choices?.[0]?.message?.content ?? '';
      msg.usage = resp.usage;
    }
  } catch (err) {
    if (abortCtrl.signal.aborted) {
      // 用户主动停止：保留已生成的部分；若一字未出则移除占位消息
      if (!msg.content) {
        conv.messages = conv.messages.filter((m) => m.id !== msg.id);
      }
    } else {
      msg.error = true;
      msg.content = msg.content
        ? msg.content + `\n\n> ⚠️ 请求中断：${errMsg(err)}`
        : `⚠️ 请求失败：${errMsg(err)}`;
    }
  } finally {
    if (timer) clearTimeout(timer);
    generating = false;
    abortCtrl = null;
    liveStream = null;
    conv.updatedAt = Date.now();
    updateComposerButtons();
    saveConversations();
    renderSidebar();
    renderMessages();
  }
}

function regenerate() {
  const conv = currentConv();
  if (!conv || generating) return;
  while (conv.messages.length && conv.messages[conv.messages.length - 1].role === 'assistant') {
    conv.messages.pop();
  }
  if (!conv.messages.length) return;
  saveConversations();
  renderMessages();
  generate(conv);
}

/* ===================== 消息操作 ===================== */
function startEdit(mid) {
  const conv = currentConv();
  const m = conv?.messages.find((x) => x.id === mid);
  if (!m) return;
  const contentEl = document.querySelector(`.msg[data-id="${mid}"] .msg-content`);
  contentEl.innerHTML = `
    <textarea class="edit-area"></textarea>
    <div class="edit-ops">
      <button type="button" class="primary" data-edit="save">保存并发送</button>
      <button type="button" data-edit="cancel">取消</button>
    </div>`;
  const ta = contentEl.querySelector('textarea');
  ta.value = m.content; // 用 value 赋值，无需转义
  ta.focus();
  ta.setSelectionRange(ta.value.length, ta.value.length);
}

function submitEdit(mid, newText) {
  const conv = currentConv();
  if (!conv) return;
  const idx = conv.messages.findIndex((x) => x.id === mid);
  if (idx < 0) return;
  newText = newText.trim();
  if (!newText) return;
  conv.messages[idx].content = newText;
  conv.messages[idx].ts = Date.now();
  conv.messages = conv.messages.slice(0, idx + 1); // 丢弃其后的消息，重新生成
  conv.updatedAt = Date.now();
  saveConversations();
  renderMessages();
  generate(conv);
}

function copyText(text, btn) {
  navigator.clipboard.writeText(text).then(() => {
    if (!btn) return;
    const old = btn.textContent;
    btn.textContent = '已复制';
    setTimeout(() => { btn.textContent = old; }, 1200);
  }).catch(() => alert('复制失败，请手动选择文本复制'));
}

/* ===================== 设置弹窗 ===================== */
function openSettings() {
  $('#set-key').value = settings.apiKey;
  $('#set-baseurl').value = settings.baseURL;
  $('#set-model').value = settings.model;
  $('#set-system').value = settings.systemPrompt;
  $('#set-temp').value = settings.temperature ?? '';
  $('#set-maxtokens').value = settings.maxTokens ?? '';
  $('#set-stream').checked = settings.stream;
  $('#model-status').textContent = '';
  $('#settings-modal').hidden = false;
}

function saveSettingsFromModal() {
  settings.apiKey = $('#set-key').value.trim();
  settings.baseURL = $('#set-baseurl').value.trim() || DEFAULT_SETTINGS.baseURL;
  settings.model = $('#set-model').value.trim() || DEFAULT_SETTINGS.model;
  settings.systemPrompt = $('#set-system').value;
  const t = $('#set-temp').value;
  settings.temperature = t === '' ? null : Number(t);
  const mt = $('#set-maxtokens').value;
  settings.maxTokens = mt === '' ? null : Number(mt);
  settings.stream = $('#set-stream').checked;
  saveSettings();
  updateStatus();
  renderMessages({ scroll: false });
  closeModals();
}

async function fetchModels() {
  const btn = $('#fetch-models');
  btn.disabled = true;
  btn.textContent = '获取中…';
  try {
    const client = await makeClient(
      $('#set-key').value.trim(),
      $('#set-baseurl').value.trim() || DEFAULT_SETTINGS.baseURL
    );
    const ids = [];
    for await (const m of client.models.list()) ids.push(m.id);
    ids.sort();
    $('#model-list').innerHTML = ids.map((id) => `<option value="${escapeHtml(id)}">`).join('');
    $('#model-status').textContent = `已获取 ${ids.length} 个模型，可在输入框下拉选择`;
  } catch (e) {
    $('#model-status').textContent = '获取失败：' + errMsg(e);
  } finally {
    btn.disabled = false;
    btn.textContent = '获取列表';
  }
}

/* ===================== 提示词管理 ===================== */
let editingPromptId = null;

function openPromptManager() {
  editingPromptId = null;
  $('#prompt-title').value = '';
  $('#prompt-content').value = '';
  $('#cancel-prompt-edit').hidden = true;
  renderPromptRows();
  $('#prompts-modal').hidden = false;
}

function renderPromptRows() {
  $('#prompt-rows').innerHTML = prompts.map((p) => `
    <div class="prompt-row" data-id="${p.id}">
      <span class="t" title="${escapeHtml(p.content)}">${escapeHtml(p.title)}</span>
      <button type="button" data-pop="use">使用</button>
      <button type="button" data-pop="edit">编辑</button>
      <button type="button" data-pop="del">删除</button>
    </div>`).join('') || '<div class="empty-hint">暂无提示词</div>';
}

function insertPrompt(content) {
  const ta = $('#input');
  ta.value = ta.value ? ta.value.replace(/\n*$/, '\n') + content : content;
  autoResize();
  ta.focus();
}

/* ===================== 导入 / 导出 ===================== */
function download(filename, content, type) {
  const blob = new Blob([content], { type });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}

function exportMarkdown() {
  const conv = currentConv();
  if (!conv || !conv.messages.length) {
    alert('当前没有可导出的对话');
    return;
  }
  let md = `# ${conv.title}\n\n`;
  for (const m of conv.messages) {
    const who = m.role === 'user' ? '用户' : '助手';
    md += `## ${who}（${new Date(m.ts).toLocaleString('zh-CN')}）\n\n${m.content}\n\n`;
  }
  download(`${conv.title.replace(/[\\/:*?"<>|]/g, '_')}.md`, md, 'text/markdown;charset=utf-8');
}

function exportBackup() {
  const data = {
    app: 'openai-chat-frontend',
    version: 1,
    exportedAt: new Date().toISOString(),
    settings: { ...settings, apiKey: '' }, // 备份不含 Key
    conversations,
    prompts,
  };
  const date = new Date().toISOString().slice(0, 10);
  download(`openai-chat-backup-${date}.json`, JSON.stringify(data, null, 2), 'application/json');
}

function importBackup(file) {
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const data = JSON.parse(reader.result);
      if (!Array.isArray(data.conversations) || !Array.isArray(data.prompts)) {
        throw new Error('文件格式不正确');
      }
      if (!confirm('导入将覆盖当前的对话记录和提示词（不影响 API Key），确定继续？')) return;
      conversations = data.conversations;
      prompts = data.prompts;
      if (data.settings) {
        const { apiKey, ...rest } = data.settings; // 忽略备份里的 Key
        settings = { ...settings, ...rest };
      }
      currentId = null;
      saveConversations();
      savePrompts();
      saveSettings();
      save(LS.currentId, null);
      applyTheme();
      updateStatus();
      renderSidebar();
      renderMessages();
      closeModals();
      alert('导入完成');
    } catch (e) {
      alert('导入失败：' + errMsg(e));
    }
  };
  reader.readAsText(file);
}

/* ===================== 输入框 ===================== */
function autoResize() {
  const ta = $('#input');
  ta.style.height = 'auto';
  ta.style.height = Math.min(ta.scrollHeight, 200) + 'px';
}

function closeModals() {
  document.querySelectorAll('.modal-mask').forEach((m) => { m.hidden = true; });
}

/* ===================== 事件绑定 ===================== */
function bindEvents() {
  // 发送 / 停止
  $('#send').addEventListener('click', () => {
    const ta = $('#input');
    const text = ta.value;
    ta.value = '';
    autoResize();
    doSend(text);
  });
  $('#stop').addEventListener('click', () => abortCtrl?.abort());
  $('#input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
      e.preventDefault();
      $('#send').click();
    }
  });
  $('#input').addEventListener('input', autoResize);

  // 新对话
  $('#new-chat').addEventListener('click', () => {
    currentId = null;
    save(LS.currentId, null);
    renderSidebar();
    renderMessages();
    $('#input').focus();
    $('#sidebar').classList.remove('open');
  });

  // 侧栏对话列表
  $('#conv-list').addEventListener('click', (e) => {
    const item = e.target.closest('.conv-item');
    if (!item) return;
    const id = item.dataset.id;
    const conv = conversations.find((c) => c.id === id);
    if (!conv) return;
    const op = e.target.dataset.op;
    if (op === 'del') {
      if (!confirm(`删除对话「${conv.title}」？`)) return;
      conversations = conversations.filter((c) => c.id !== id);
      if (currentId === id) {
        currentId = null;
        save(LS.currentId, null);
      }
      saveConversations();
      renderSidebar();
      renderMessages();
    } else if (op === 'rename') {
      const t = prompt('新的对话标题：', conv.title);
      if (t && t.trim()) {
        conv.title = t.trim();
        saveConversations();
        renderSidebar();
        renderMessages({ scroll: false });
      }
    } else {
      currentId = id;
      save(LS.currentId, currentId);
      renderSidebar();
      renderMessages();
      $('#sidebar').classList.remove('open');
    }
  });

  // 侧栏提示词
  $('#prompt-list').addEventListener('click', (e) => {
    const chip = e.target.closest('.prompt-chip');
    if (!chip) return;
    const p = prompts.find((x) => x.id === chip.dataset.id);
    if (p) insertPrompt(p.content);
    $('#sidebar').classList.remove('open');
  });
  $('#manage-prompts').addEventListener('click', openPromptManager);

  // 提示词管理弹窗
  $('#prompt-rows').addEventListener('click', (e) => {
    const row = e.target.closest('.prompt-row');
    if (!row) return;
    const p = prompts.find((x) => x.id === row.dataset.id);
    if (!p) return;
    const op = e.target.dataset.pop;
    if (op === 'use') {
      insertPrompt(p.content);
      closeModals();
    } else if (op === 'edit') {
      editingPromptId = p.id;
      $('#prompt-title').value = p.title;
      $('#prompt-content').value = p.content;
      $('#cancel-prompt-edit').hidden = false;
      $('#prompt-title').focus();
    } else if (op === 'del') {
      if (!confirm(`删除提示词「${p.title}」？`)) return;
      prompts = prompts.filter((x) => x.id !== p.id);
      savePrompts();
      renderPromptRows();
      renderSidebar();
    }
  });
  $('#save-prompt').addEventListener('click', () => {
    const title = $('#prompt-title').value.trim();
    const content = $('#prompt-content').value;
    if (!title || !content.trim()) {
      alert('标题和内容都不能为空');
      return;
    }
    if (editingPromptId) {
      const p = prompts.find((x) => x.id === editingPromptId);
      if (p) { p.title = title; p.content = content; }
    } else {
      prompts.push({ id: uid(), title, content, createdAt: Date.now() });
    }
    savePrompts();
    editingPromptId = null;
    $('#prompt-title').value = '';
    $('#prompt-content').value = '';
    $('#cancel-prompt-edit').hidden = true;
    renderPromptRows();
    renderSidebar();
  });
  $('#cancel-prompt-edit').addEventListener('click', () => {
    editingPromptId = null;
    $('#prompt-title').value = '';
    $('#prompt-content').value = '';
    $('#cancel-prompt-edit').hidden = true;
  });

  // 消息操作（事件委托）
  $('#messages').addEventListener('click', (e) => {
    const copyBtn = e.target.closest('.copy-code');
    if (copyBtn) {
      const code = copyBtn.parentElement.querySelector('pre code');
      if (code) copyText(code.textContent, copyBtn);
      return;
    }
    const msgEl = e.target.closest('.msg');
    if (!msgEl) return;
    const mid = msgEl.dataset.id;
    const conv = currentConv();
    if (!conv) return;

    const editOp = e.target.dataset.edit;
    if (editOp === 'save') {
      submitEdit(mid, msgEl.querySelector('.edit-area').value);
      return;
    }
    if (editOp === 'cancel') {
      renderMessages({ scroll: false });
      return;
    }

    const act = e.target.dataset.act;
    if (!act) return;
    const m = conv.messages.find((x) => x.id === mid);
    if (!m) return;
    if (act === 'copy') {
      copyText(m.content, e.target);
    } else if (act === 'edit' && !generating) {
      startEdit(mid);
    } else if (act === 'del' && !generating) {
      conv.messages = conv.messages.filter((x) => x.id !== mid);
      saveConversations();
      renderMessages({ scroll: false });
    } else if (act === 'regen') {
      regenerate();
    }
  });

  // 滚动跟随
  $('#messages').addEventListener('scroll', () => {
    const el = $('#messages');
    stickBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  });

  // 顶栏
  $('#export-md').addEventListener('click', exportMarkdown);
  $('#clear-chat').addEventListener('click', () => {
    const conv = currentConv();
    if (!conv || !conv.messages.length) return;
    if (!confirm('清空当前对话的所有消息？')) return;
    conv.messages = [];
    conv.updatedAt = Date.now();
    saveConversations();
    renderMessages();
  });

  // 设置
  $('#open-settings').addEventListener('click', openSettings);
  $('#save-settings').addEventListener('click', saveSettingsFromModal);
  $('#fetch-models').addEventListener('click', fetchModels);
  $('#export-backup').addEventListener('click', exportBackup);
  $('#import-backup').addEventListener('click', () => $('#import-file').click());
  $('#import-file').addEventListener('change', (e) => {
    if (e.target.files[0]) importBackup(e.target.files[0]);
    e.target.value = '';
  });
  $('#clear-data').addEventListener('click', () => {
    if (!confirm('确定清空所有本地数据（对话、提示词、设置）？此操作不可恢复！')) return;
    Object.values(LS).forEach((k) => localStorage.removeItem(k));
    location.reload();
  });

  // 弹窗关闭
  document.querySelectorAll('.modal-mask').forEach((mask) => {
    mask.addEventListener('click', (e) => { if (e.target === mask) mask.hidden = true; });
  });
  document.querySelectorAll('.modal-close').forEach((btn) => {
    btn.addEventListener('click', () => { btn.closest('.modal-mask').hidden = true; });
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeModals();
  });

  // 主题
  $('#toggle-theme').addEventListener('click', () => {
    settings.theme = effectiveTheme() === 'dark' ? 'light' : 'dark';
    saveSettings();
    applyTheme();
  });
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (settings.theme === 'auto') applyTheme();
  });

  // 移动端侧栏
  $('#toggle-sidebar').addEventListener('click', () => $('#sidebar').classList.toggle('open'));
  $('#sidebar-mask').addEventListener('click', () => $('#sidebar').classList.remove('open'));
}

/* ===================== 初始化 ===================== */
async function init() {
  bindEvents();
  applyTheme();
  updateStatus();
  renderSidebar();
  renderMessages();
  await loadDeps();
  // Markdown / 高亮库就绪后重绘一次
  renderMessages({ scroll: false });
}
init();
