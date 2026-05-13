(function() {
// == Shared state objects (completely independent) ==
const bench = {
  allModels: [], loadedModels: [], loadedModelIds: new Set(),
  selectedModelId: '', selectedNodeId: '', filterText: '',
  historyFiles: [], selectedFileName: '', selectedFileData: null,
  hardwareByModel: new Map(),   paramConfig: [], devices: [], paramCache: null,
  llamaPaths: [], abortController: null, isRunning: false
};
const server = {
  allModels: [], loadedModels: [], loadedModelIds: new Set(),
  selectedModelId: '', selectedNodeId: '', filterText: '', records: [], recordKeys: new Set(),
  hardwareByModel: new Map(),
  abortController: null, isRunning: false
};

// == DOM refs ==
const E = {
  bench: {
    select: document.getElementById('benchModelList'),
    selText: document.getElementById('benchSelModelText'),
    modelList: document.getElementById('benchModelList'),
    modelsMeta: document.getElementById('benchModelsMeta'),
    searchInput: document.getElementById('benchSearchInput'),
    output: document.getElementById('benchOutput'),
    outputMeta: document.getElementById('benchOutputMeta'),
    historyCount: document.getElementById('benchHistoryCount'),
    historyList: document.getElementById('benchHistoryList'),
    copyBtn: document.getElementById('benchCopyBtn'),
    exportBtn: document.getElementById('benchExportBtn'),
    paramsContainer: document.getElementById('benchParamsContainer'),
    paramsBtn: document.getElementById('benchParamsBtn'),
    paramsModal: document.getElementById('benchParamsModal'),
    paramsModalStatus: document.getElementById('benchParamsModalStatus'),
    paramsResetBtn: document.getElementById('benchParamsResetBtn'),
    paramsCloseBtn: document.getElementById('benchParamsCloseBtn'),
    paramsConfirmBtn: document.getElementById('benchParamsConfirmBtn'),
    paramsDeviceList: document.getElementById('benchParamsDeviceList'),
    mainGpuSelect: document.getElementById('benchMainGpuSelect'),
    runBtn: document.getElementById('runBenchBtn'),
    llamaBinSelect: document.getElementById('benchLlamaBinSelect'),
    nodeFilter: document.getElementById('benchNodeFilter')
  },
  server: {
    modelList: document.getElementById('serverModelList'),
    modelsMeta: document.getElementById('serverModelsMeta'),
    searchInput: document.getElementById('serverSearchInput'),
    resultBody: document.getElementById('serverResultBody'),
    emptyRow: document.getElementById('serverEmptyRow'),
    selText: document.getElementById('serverSelModelText'),
    status: document.getElementById('serverStatus'),
    promptTokens: document.getElementById('serverPromptTokens'),
    maxTokens: document.getElementById('serverMaxTokens'),
    concurrency: document.getElementById('serverConcurrency'),
    runBtn: document.getElementById('runServerBtn'),
    nodeFilter: document.getElementById('serverNodeFilter'),
    chartCanvas: document.getElementById('serverChartCanvas'),
    chartEmpty: document.getElementById('serverChartEmpty'),
    chartWrap: document.getElementById('serverChartWrap'),
    exportBtns: document.querySelectorAll('.server-export')
  }
};

// == Shared utils ==
function t(key, fallback) { return (window.I18N && typeof window.I18N.t === 'function') ? window.I18N.t(key, fallback) : (fallback == null ? key : fallback); }
function safeText(v) { return v == null ? '' : String(v); }
function formatNumber(v) { const n = Number(v); if (!Number.isFinite(n)) return '-'; if (n >= 100000) return '1'; return n.toFixed(1).replace(/\.?0+$/, ''); }
function formatTimestamp(v) {
  if (v == null) return '-'; const raw = String(v).trim();
  if (/^\d{8}_\d{6}$/.test(raw)) return raw.slice(0,4)+'-'+raw.slice(4,6)+'-'+raw.slice(6,8)+' '+raw.slice(9,11)+':'+raw.slice(11,13)+':'+raw.slice(13,15);
  const p = Date.parse(raw); if (!Number.isNaN(p)) return new Date(p).toLocaleString();
  return raw;
}
function getModelDisplayName(m) {
  const alias = m && typeof m.alias === 'string' ? m.alias.trim() : '';
  const name = m && typeof m.name === 'string' ? m.name.trim() : '';
  return alias || name || safeText(m && m.id);
}
function cloneDevices(v) {
  if (Array.isArray(v)) return v.map(x => safeText(x).trim()).filter(Boolean);
  const t = safeText(v).trim(); return t ? [t] : [];
}
function escapeHtml(text) {
  const div = document.createElement('div');
  div.appendChild(document.createTextNode(safeText(text)));
  return div.innerHTML;
}
async function fetchJson(url, options) {
  const resp = await fetch(url, options || {});
  const text = await resp.text();
  let json; try { json = JSON.parse(text); } catch(e) { json = { success: false, error: text || t('common.request_failed', '请求失败') }; }
  if (!resp.ok) throw new Error((json && json.error) ? json.error : t('common.request_failed', '请求失败'));
  return json;
}
function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a'); a.href = url; a.download = filename; a.click();
  URL.revokeObjectURL(url);
}
function effectiveNodeId(state) {
  return (state.selectedNodeId && state.selectedNodeId !== 'all' && state.selectedNodeId !== 'local') ? state.selectedNodeId : '';
}
function nodeQueryParam(nodeId) {
  return nodeId ? '&nodeId=' + encodeURIComponent(nodeId) : '';
}
function setNodeIdOnBody(payload, nodeId) {
  if (nodeId) payload.nodeId = nodeId;
}
function modelLoadedKey(id, nodeId) {
  return id + '::' + (nodeId || 'local');
}
function findModelButton(listEl, modelId, nodeId) {
  if (!listEl) return null;
  if (nodeId && nodeId !== 'all' && nodeId !== 'local') {
    return listEl.querySelector('button[data-model-id="' + modelId + '"][data-node-id="' + nodeId + '"]');
  }
  if (!nodeId || nodeId === 'local') {
    return listEl.querySelector('button[data-model-id="' + modelId + '"]:not([data-node-id])');
  }
  return listEl.querySelector('button[data-model-id="' + modelId + '"]');
}
function modelDisplayId(m) {
  const id = safeText(m && m.id).trim();
  const nd = safeText(m && (m.nodeId || m.node)).trim();
  if (nd && nd !== 'local') return '[' + nd + '] ' + id;
  return id;
}
function getNodeColor(nodeId) {
  if (!nodeId || nodeId === 'local') return '';
  var hash = 0;
  for (var i = 0; i < nodeId.length; i++) {
    hash = ((hash << 5) - hash) + nodeId.charCodeAt(i);
    hash |= 0;
  }
  return ((Math.abs(hash) * 137.508) % 360).toFixed(1);
}
function nodeBadgeHtml(nodeId, nodeName) {
  if (!nodeId || nodeId === 'local') return '';
  var hue = getNodeColor(nodeId);
  var label = nodeName || nodeId;
  return '<span class="node-badge" style="color:hsl(' + hue + ',65%,70%);background-color:hsl(' + hue + ',50%,12%);"><i class="fas fa-server"></i> ' + escapeHtml(label) + '</span>';
}
function modelNodeId(state, selectEl) {
  const opt = selectEl && selectEl.selectedOptions && selectEl.selectedOptions[0];
  if (opt && opt.dataset && opt.dataset.nodeId) return opt.dataset.nodeId;
  return effectiveNodeId(state);
}
function benchModelNodeId() {
  if (bench.selectedModelId && bench.selectedNodeId) {
    return bench.selectedNodeId === 'local' ? '' : bench.selectedNodeId;
  }
  if (bench.selectedModelId) {
    const btn = findModelButton(E.bench.modelList, bench.selectedModelId, bench.selectedNodeId);
    if (btn && btn.dataset && btn.dataset.nodeId) return btn.dataset.nodeId;
  }
  return effectiveNodeId(bench);
}
function serverModelNodeId() {
  if (server.selectedModelId && server.selectedNodeId) {
    return server.selectedNodeId === 'local' ? '' : server.selectedNodeId;
  }
  if (server.selectedModelId) {
    const btn = findModelButton(E.server.modelList, server.selectedModelId, server.selectedNodeId);
    if (btn && btn.dataset && btn.dataset.nodeId) return btn.dataset.nodeId;
  }
  return effectiveNodeId(server);
}

// == Model list rendering (shared between tabs) ==
function matchesFilter(state, model) {
  if (!state.filterText) return true;
  const q = state.filterText.toLowerCase();
  return safeText(model && model.id).toLowerCase().includes(q)
    || getModelDisplayName(model).toLowerCase().includes(q)
    || safeText(model && model.alias).toLowerCase().includes(q);
}
function updateModelsMeta(state, el, totalCount) {
  if (!el) return;
  const total = totalCount != null ? totalCount : (Array.isArray(state.allModels) ? state.allModels.length : 0);
  const loadedInList = state.allModels ? state.allModels.filter(m => state.loadedModelIds && state.loadedModelIds.has(modelLoadedKey(safeText(m && m.id).trim(), m && (m.nodeId || m.node).trim()))).length : 0;
  if (state.filterText && total > 0) {
    const filtered = (state.allModels || []).filter(m => matchesFilter(state, m)).length;
    el.textContent = t('page.benchmark_v3.model_count', '匹配 {filtered}/{total}，已加载 {loaded}').replace('{filtered}', filtered).replace('{total}', total).replace('{loaded}', loadedInList);
  } else {
    el.textContent = t('page.benchmark_v3.model_count_loaded', '总计 {total}，已加载 {loaded}').replace('{total}', total).replace('{loaded}', loadedInList);
  }
}
function updateActiveModelItem(state, listEl) {
  if (!listEl) return;
  const selId = state.selectedModelId;
  const selNode = state.selectedNodeId || '';
  listEl.querySelectorAll('button[data-model-id]').forEach(btn => {
    const btnModelId = btn.dataset.modelId;
    const btnNodeId = btn.dataset.nodeId || '';
    if (!selId || btnModelId !== selId) { btn.classList.remove('active'); return; }
    if (!selNode || selNode === 'all') { btn.classList.add('active'); return; }
    btn.classList.toggle('active', btnNodeId === selNode || (!btnNodeId && selNode === 'local'));
  });
}
function renderBenchModelList(state, listEl, metaEl) {
  if (!listEl) return;
  listEl.innerHTML = '';
  let list = (Array.isArray(state.allModels) ? state.allModels : []).slice();
  list = list.filter(m => matchesFilter(state, m));
  if (state.selectedNodeId && state.selectedNodeId !== 'all') {
    list = list.filter(m => {
      const nd = safeText(m && (m.nodeId || m.node)).trim();
      if (state.selectedNodeId === 'local') return !nd || nd === 'local';
      return nd === state.selectedNodeId;
    });
  }
  list.sort((a, b) => getModelDisplayName(a).toLowerCase().localeCompare(getModelDisplayName(b).toLowerCase()));
  if (!list.length) {
    const el = document.createElement('div');
    el.style.cssText = 'font-size:12px;color:var(--text-secondary);padding:8px;text-align:center;';
    el.textContent = state.allModels.length ? t('page.benchmark_v3.no_match', '没有匹配的模型') : t('page.benchmark_v3.no_models', '暂无模型');
    listEl.appendChild(el);
    updateModelsMeta(state, metaEl);
    return;
  }
  for (const m of list) {
    listEl.appendChild(buildModelItem(m, state));
  }
  updateModelsMeta(state, metaEl);
  updateActiveModelItem(state, listEl);
}
function renderServerModelList(state, listEl, metaEl) {
  if (!listEl) return;
  listEl.innerHTML = '';
  let list = (Array.isArray(state.allModels) ? state.allModels : []).slice();
  list = list.filter(m => matchesFilter(state, m));
  if (state.selectedNodeId && state.selectedNodeId !== 'all') {
    list = list.filter(m => {
      const nd = safeText(m && (m.nodeId || m.node)).trim();
      if (state.selectedNodeId === 'local') return !nd || nd === 'local';
      return nd === state.selectedNodeId;
    });
  }
  list.sort((a, b) => getModelDisplayName(a).toLowerCase().localeCompare(getModelDisplayName(b).toLowerCase()));
  if (!list.length) {
    const el = document.createElement('div');
    el.style.cssText = 'font-size:12px;color:var(--text-secondary);padding:8px;text-align:center;';
    el.textContent = state.allModels.length ? t('page.benchmark_v3.no_match', '没有匹配的模型') : t('page.benchmark_v3.no_models', '暂无模型');
    listEl.appendChild(el);
    updateModelsMeta(state, metaEl);
    return;
  }
  for (const m of list) {
    listEl.appendChild(buildModelItem(m, state));
  }
  updateModelsMeta(state, metaEl);
  updateActiveModelItem(state, listEl);
}
function buildModelItem(m, state) {
  const id = safeText(m && m.id).trim();
  const nd = safeText(m && (m.nodeId || m.node)).trim();
  const isRemote = nd && nd !== 'local';
  const hue = getNodeColor(nd);
  const key = modelLoadedKey(id, nd);
  const btn = document.createElement('button');
  btn.type = 'button'; btn.className = 'model-menu-item'; btn.dataset.modelId = id;
  if (isRemote) { btn.dataset.nodeId = nd; btn.style.borderLeft = '3px solid hsl(' + hue + ',65%,50%)'; }
  if (state.loadedModelIds && state.loadedModelIds.has(key)) btn.classList.add('loaded');
  const n = document.createElement('div'); n.className = 'name';
  n.textContent = getModelDisplayName(m);
  const loadedText = t('page.model.status.loaded', '已加载');
  const metaEl = document.createElement('div'); metaEl.className = 'meta';
  const statusText = state.loadedModelIds && state.loadedModelIds.has(key) ? loadedText : '';
  const parts = [];
  if (isRemote) parts.push(nodeBadgeHtml(nd, m.nodeName));
  if (statusText) parts.push(escapeHtml(statusText));
  metaEl.innerHTML = parts.join(' ');
  btn.appendChild(n);
  if (parts.length) btn.appendChild(metaEl);
  if (m && m.size) {
    const s = document.createElement('div'); s.className = 'meta';
    s.style.color = 'var(--text-secondary)';
    s.innerHTML = '<i class="fas fa-hdd"></i> ' + escapeHtml(formatFileSize(m.size));
    btn.appendChild(s);
  }
  return btn;
}

// == Tab switching ==
document.querySelectorAll('.tab-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
    document.getElementById(btn.dataset.tab === 'llama-bench' ? 'benchPanel' : 'serverPanel').classList.add('active');
    if (btn.dataset.tab === 'llama-bench') initBenchTab();
    else initServerTab();
  });
});

// == Node filter ==
function populateNodeFilter(state, selectEl) {
  if (!selectEl) return;
  const prevVal = selectEl.value || '';
  selectEl.innerHTML = '';
  const opts = [
    { value: 'all', text: t('page.model.filter.all', '全部节点') },
    { value: 'local', text: t('page.model.filter.local', '本机') }
  ];
  const seenNodeIds = new Set();
  const all = Array.isArray(state.allModels) ? state.allModels : [];
  for (const m of all) {
    const nd = safeText(m && (m.nodeId || m.node)).trim();
    if (nd && nd !== 'local' && !seenNodeIds.has(nd)) {
      seenNodeIds.add(nd);
      opts.push({ value: nd, text: nd });
    }
  }
  for (const o of opts) {
    const el = document.createElement('option');
    el.value = o.value; el.textContent = o.text;
    selectEl.appendChild(el);
  }
  if (prevVal && opts.some(o => o.value === prevVal)) selectEl.value = prevVal;
  else selectEl.value = 'all';
}
function populateServerNodeFilter() {
  const select = E.server.nodeFilter;
  if (!select) return;
  const prevVal = select.value || '';
  select.innerHTML = '';
  const opts = [
    { value: 'all', text: t('page.model.filter.all', '全部节点') },
    { value: 'local', text: t('page.model.filter.local', '本机') }
  ];
  const seenNodeIds = new Set();
  const benchModels = Array.isArray(bench.allModels) ? bench.allModels : [];
  const serverModels = Array.isArray(server.allModels) ? server.allModels : [];
  for (const m of benchModels.concat(serverModels)) {
    const nd = safeText(m && (m.nodeId || m.node)).trim();
    if (nd && nd !== 'local' && !seenNodeIds.has(nd)) {
      seenNodeIds.add(nd);
      opts.push({ value: nd, text: m.nodeName || nd });
    }
  }
  for (const o of opts) {
    const el = document.createElement('option');
    el.value = o.value; el.textContent = o.text;
    select.appendChild(el);
  }
  if (prevVal && opts.some(o => o.value === prevVal)) select.value = prevVal;
  else select.value = 'all';
}

// == llama-bench tab ==
async function initBenchTab() {
  if (!bench.allModels.length) await loadBenchAllModels();
  populateNodeFilter(bench, E.bench.nodeFilter);
  renderBenchModelList(bench, E.bench.modelList, E.bench.modelsMeta);
  loadBenchHistory();
  if (!bench.llamaPaths.length) loadBenchLlamaCppPaths('');
  if (!bench.paramConfig.length) loadBenchParamConfig();
}
async function loadBenchAllModels() {
  try {
    const data = await fetchJson('/api/models/list', { method: 'GET' });
    if (data && data.success && Array.isArray(data.models)) {
      bench.allModels = data.models;
      try {
        const ld = await fetchJson('/api/models/loaded', { method: 'GET' });
        const models = Array.isArray(ld && ld.models) ? ld.models : [];
        bench.loadedModels = models;
        bench.loadedModelIds = new Set(models.map(m => modelLoadedKey(safeText(m && m.id).trim(), m && m.nodeId)).filter(Boolean));
      } catch(e) { /* ignore */ }
    }
  } catch(e) { bench.allModels = []; }
}
async function loadBenchLlamaCppPaths(nodeId) {
  try {
    const url = '/api/llamacpp/list' + (nodeId ? '?nodeId=' + encodeURIComponent(nodeId) : '');
    const data = await fetchJson(url, { method: 'GET' });
    const items = (data && data.success && data.data) ? (data.data.items || []) : [];
    bench.llamaPaths = Array.isArray(items) ? items : [];
    populateBenchLlamaBinSelect();
  } catch(e) { bench.llamaPaths = []; populateBenchLlamaBinSelect(); }
}
function populateBenchLlamaBinSelect() {
  const sel = E.bench.llamaBinSelect;
  if (!sel) return;
  sel.innerHTML = '';
  const items = bench.llamaPaths;
  if (!items.length) {
    const o = document.createElement('option'); o.value = ''; o.textContent = '未配置 Llama.cpp 路径';
    sel.appendChild(o); sel.disabled = true; return;
  }
  sel.disabled = false;
  for (const item of items) {
    const p = safeText(item && item.path).trim();
    if (!p) continue;
    const name = safeText(item && item.name).trim();
    const desc = safeText(item && item.description).trim();
    const text = name ? name + ' (' + p + ')' : p;
    const title = [name, p, desc].filter(Boolean).join('\n');
    const o = document.createElement('option');
    o.value = p; o.textContent = text; o.title = title;
    sel.appendChild(o);
  }
  sel.onchange = function() {};
}
async function loadBenchParamConfig() {
  try {
    const data = await fetchJson('/api/models/param/benchmark/list', { method: 'GET' });
    if (data && data.success && Array.isArray(data.params)) {
      bench.paramConfig = data.params.filter(p => (p.fullName || '') !== '--main-gpu');
      renderBenchParamsOnce();
    }
  } catch(e) {
    if (E.bench.paramsContainer) E.bench.paramsContainer.innerHTML = '<div style="font-size:14px;color:var(--text-secondary);padding:24px;text-align:center;">参数加载失败</div>';
  }
}
function renderBenchParamsOnce() {
  if (bench._paramsRendered) return;
  renderBenchParams();
  bench._paramsRendered = true;
}
async function loadBenchParamConfigAndShow() {
  await loadBenchParamConfig();
  restoreBenchParamCache();
  updateBenchParamsCount();
  loadBenchParamsDevices();
  if (E.bench.paramsModal) E.bench.paramsModal.style.display = 'flex';
}
function fieldNameFromParamConfig(p) {
  const fn = (p.fullName || '').trim();
  if (fn) return fn.replace(/^-+/g, '').replace(/-/g, '_');
  const ab = (p.abbreviation || '').trim();
  if (ab) return ab.replace(/^-+/g, '').replace(/-/g, '_');
  const base = (p.name || '').trim().replace(/[^a-zA-Z0-9_-]+/g, '_').replace(/^_+|_+$/g, '');
  const sortRaw = p.sort == null ? '' : String(p.sort).trim();
  return 'unnamed_' + (base || 'param') + (sortRaw ? '_' + sortRaw : '');
}

function getParamGroupName(p) {
  const raw = p && p.group != null ? String(p.group).trim() : '';
  if (!raw) return 'page.params.group.default';
  if (raw.startsWith('page.params.group.')) return t(raw, raw);
  const legacyMap = {
    '基础参数': 'page.params.group.basic',
    '测试数据': 'page.params.group.test_data',
    '性能参数': 'page.params.group.performance',
    '缓存与存储': 'page.params.group.cache_storage',
    '高级功能': 'page.params.group.advanced'
  };
  return legacyMap[raw] || raw;
}

function getParamOptionItems(p) {
  const rawValues = p && Array.isArray(p.values) ? p.values : [];
  return rawValues.map(item => {
    if (item && typeof item === 'object' && !Array.isArray(item)) {
      const value = item.value == null ? '' : String(item.value).trim();
      const labelKey = item.label == null ? '' : String(item.label).trim();
      return { value, label: labelKey ? t(labelKey, value) : value };
    }
    const value = item == null ? '' : String(item).trim();
    return { value, label: value };
  }).filter(item => item.value != null);
}

function renderParamField(p) {
  const fullName = (p.fullName || '').trim();
  const abbr = (p.abbreviation || '').trim();
  if (!fullName && !abbr) return '';

  const fieldName = fieldNameFromParamConfig(p);
  const fieldId = 'param_bench_' + fieldName;
  const enableId = 'param_enable_bench_' + fieldName;
  const displayName = t(p.name, fullName || abbr || p.name);
  const description = t(p.description, '');
  const defaultValue = p.defaultValue || '';
  const type = (p.type || 'STRING').toUpperCase();
  const optionItems = getParamOptionItems(p);
  const defaultEnabled = p.defaultEnabled !== undefined ? p.defaultEnabled : (p.defaultValue != null && p.defaultValue !== '');

  let html = '';
  const flagText = abbr || fullName;
  const flagHtml = ' <span style="font-weight:400;color:var(--text-secondary);font-size:12px;">' + escapeHtml(flagText) + '</span>';
  const labelContent = description
    ? displayName + flagHtml + ' <i class="fas fa-question-circle" style="color:#DCDCDC;cursor:help;margin-left:4px;" title="' + escapeHtml(description) + '"></i>'
    : displayName + flagHtml;

  const labelHtml = '<div class="param-label-row" style="display:flex;align-items:center;gap:0.4rem;line-height:1.1;">' +
    '<input class="form-check-input param-enable-toggle" type="checkbox" id="' + escapeHtml(enableId) + '"' + (defaultEnabled ? ' checked' : '') + ' style="margin:0;width:14px;height:14px;flex:0 0 auto;transform:translateY(1px);">' +
    '<label class="form-label" for="' + escapeHtml(fieldId) + '" style="margin:0;font-size:14px;line-height:1.1;">' + labelContent + '</label>' +
    '</div>';

  if (optionItems.length > 0) {
    html += '<div class="form-group param-field">';
    html += labelHtml;
    html += '<select class="form-control" id="' + escapeHtml(fieldId) + '" name="' + escapeHtml(fieldName) + '">';
    for (const opt of optionItems) {
      const selected = opt.value === defaultValue ? ' selected' : '';
      html += '<option value="' + escapeHtml(opt.value) + '"' + selected + '>' + escapeHtml(opt.label) + '</option>';
    }
    html += '</select></div>';
  } else {
    html += '<div class="form-group param-field">';
    html += labelHtml;
    switch (type) {
      case 'INTEGER':
        html += '<input type="number" class="form-control" id="' + escapeHtml(fieldId) + '" name="' + escapeHtml(fieldName) + '" value="' + escapeHtml(defaultValue) + '">';
        break;
      case 'FLOAT':
        html += '<input type="number" step="any" class="form-control" id="' + escapeHtml(fieldId) + '" name="' + escapeHtml(fieldName) + '" value="' + escapeHtml(defaultValue) + '">';
        break;
      case 'LOGIC':
      case 'BOOLEAN':
        html += '<select class="form-control" id="' + escapeHtml(fieldId) + '" name="' + escapeHtml(fieldName) + '">';
        html += '<option value="0"' + (defaultValue === '0' || defaultValue === 'false' ? ' selected' : '') + '>false</option>';
        html += '<option value="1"' + (defaultValue === '1' || defaultValue === 'true' || defaultValue === 'on' ? ' selected' : '') + '>true</option>';
        html += '</select>';
        break;
      case 'STRING':
      default:
        html += '<input type="text" class="form-control" id="' + escapeHtml(fieldId) + '" name="' + escapeHtml(fieldName) + '" value="' + escapeHtml(defaultValue) + '">';
        break;
    }
    html += '</div>';
  }
  return html;
}

function renderParamGroup(group) {
  const groupTitle = escapeHtml(t(group.name, group.name));
  const openAttr = !group.collapsed ? ' open' : '';
  let html = '<details class="param-group"' + openAttr + '>';
  html += '<summary class="param-group-summary"><span class="param-group-title">' + groupTitle + '</span><span class="param-group-count">' + group.params.length + '</span></summary>';
  html += '<div class="param-group-body">';
  html += '<div class="param-group-grid" style="display:grid;grid-template-columns:1fr 1fr;gap:1rem;">';
  for (const p of group.params) {
    html += renderParamField(p);
  }
  html += '</div></div></details>';
  return html;
}

function renderBenchParams() {
  const configs = (Array.isArray(bench.paramConfig) ? bench.paramConfig : []).slice();
  configs.sort((a, b) => (a.sort || 999) - (b.sort || 999));

  const groupMap = new Map();
  const groups = [];
  for (const p of configs) {
    const groupName = getParamGroupName(p);
    let g = groupMap.get(groupName);
    if (!g) {
      g = {
        name: groupName,
        order: Number.isFinite(p.groupOrder) ? p.groupOrder : 999,
        collapsed: p.groupCollapsed === true,
        params: []
      };
      groupMap.set(groupName, g);
      groups.push(g);
    } else {
      if (Number.isFinite(p.groupOrder)) g.order = Math.min(g.order, p.groupOrder);
    }
    g.params.push(p);
  }
  groups.sort((a, b) => a.order - b.order || a.name.localeCompare(b.name));

  let html = '';
  if (groups.length > 0) {
    html += '<div class="param-groups">';
    for (const g of groups) {
      html += renderParamGroup(g);
    }
    html += '</div>';
  }

  E.bench.paramsContainer.innerHTML = html;
  updateBenchParamsCount();
}

function updateBenchParamsCount() {
  const configs = Array.isArray(bench.paramConfig) ? bench.paramConfig : [];
  let total = 0, enabled = 0;
  for (const p of configs) {
    const fn = (p.fullName || '').trim();
    if (!fn) continue;
    const fName = fieldNameFromParamConfig(p);
    const chk = document.getElementById('param_enable_bench_' + fName);
    total++;
    if (chk && chk.checked) enabled++;
  }
  const text = t('page.benchmark_v3.params_count', '参数设置 ({enabled}/{total})').replace('{enabled}', enabled).replace('{total}', total);
  if (E.bench.paramsBtn) E.bench.paramsBtn.textContent = text;
  if (E.bench.paramsModalStatus) E.bench.paramsModalStatus.textContent = t('page.benchmark_v3.params_set_count', '已设置 {enabled} / {total} 个参数').replace('{enabled}', enabled).replace('{total}', total);
}
function openBenchParamsModal() {
  if (!bench.paramConfig.length) { loadBenchParamConfigAndShow(); return; }
  renderBenchParamsOnce();
  restoreBenchParamCache();
  updateBenchParamsCount();
  loadBenchParamsDevices();
  if (E.bench.paramsModal) E.bench.paramsModal.style.display = 'flex';
}
function resetBenchParams() {
  bench.paramCache = null;
  bench._paramsRendered = false;
  renderBenchParamsOnce();
  updateBenchParamsCount();
}
function saveBenchParamCache() {
  const configs = Array.isArray(bench.paramConfig) ? bench.paramConfig : [];
  const cache = {};
  for (const p of configs) {
    const fn = (p.fullName || '').trim();
    if (!fn) continue;
    const fName = fieldNameFromParamConfig(p);
    const enableChk = document.getElementById('param_enable_bench_' + fName);
    const ctrl = document.getElementById('param_bench_' + fName);
    if (ctrl) cache[fName] = { enabled: !!(enableChk && enableChk.checked), value: ctrl.value };
  }
  bench.paramCache = cache;
}
function restoreBenchParamCache() {
  const cache = bench.paramCache;
  if (!cache) return;
  for (const [fName, saved] of Object.entries(cache)) {
    const enableChk = document.getElementById('param_enable_bench_' + fName);
    if (enableChk) enableChk.checked = !!saved.enabled;
    const ctrl = document.getElementById('param_bench_' + fName);
    if (ctrl && saved.value != null) ctrl.value = saved.value;
  }
}
function confirmBenchParams() {
  saveBenchParamCache();
  if (typeof console !== 'undefined') console.log('paramCache saved, keys:', Object.keys(bench.paramCache || {}));
  updateBenchParamsCount();
  if (E.bench.paramsModal) E.bench.paramsModal.style.display = 'none';
}
function closeBenchParamsModal() {
  updateBenchParamsCount();
  if (E.bench.paramsModal) E.bench.paramsModal.style.display = 'none';
}
function buildBenchCmd() {
  const configs = Array.isArray(bench.paramConfig) ? bench.paramConfig : [];
  const cache = bench.paramCache || {};
  const parts = [];
  for (const p of configs) {
    const fn = (p.fullName || '').trim();
    if (!fn) continue;
    const fName = fieldNameFromParamConfig(p);
    const enableChk = document.getElementById('param_enable_bench_' + fName);
    const ctrl = document.getElementById('param_bench_' + fName);
    const cached = cache[fName];
    const enabled = (enableChk && enableChk.checked) || (cached && cached.enabled);
    if (!enabled) continue;
    let val = ctrl ? ctrl.value : (cached ? cached.value : '');
    if (val == null) val = '';
    const type = (p.type || 'STRING').toUpperCase();
    if (type === 'LOGIC') {
      if (val === '1' || val === 'true') parts.push(fn);
    } else {
      val = String(val).trim();
      if (val) parts.push(fn + ' ' + val);
    }
  }
  var result = parts.join(' ');
  if (typeof console !== 'undefined') console.log('buildBenchCmd result:', result, 'config count:', configs.length);
  return result;
}
function buildBenchDeviceArg() {
  const checks = E.bench.paramsDeviceList ? E.bench.paramsDeviceList.querySelectorAll('input[type=checkbox]') : [];
  const enabled = [];
  checks.forEach((chk, i) => { if (chk.checked) enabled.push(parseDeviceName(i)); });
  const parts = [];
  if (checks.length && enabled.length < checks.length) parts.push('-dev ' + enabled.join('/'));
  const mg = getBenchMainGpu();
  if (mg >= 0) parts.push('--main-gpu ' + mg);
  return parts.join(' ');
}
function parseDeviceName(idx) {
  const d = Array.isArray(bench.devices) ? bench.devices[idx] : null;
  if (d == null) return '';
  const raw = typeof d === 'string' ? d : safeText(d.name || d.brand || d.id || d).trim();
  return raw.split(':')[0].trim();
}
async function loadBenchParamsDevices() {
  const list = E.bench.paramsDeviceList;
  if (!list) return;
  const llamaBinPath = (E.bench.llamaBinSelect && E.bench.llamaBinSelect.value || '').trim();
  if (!llamaBinPath) {
    list.innerHTML = '<div class="device-placeholder">' + escapeHtml(t('page.benchmark_v3.select_llamacpp_version', '请先在主页面选择 llama.cpp 版本')) + '</div>';
    return;
  }
  list.innerHTML = '<div class="device-placeholder">' + escapeHtml(t('common.loading', '加载中…')) + '</div>';
  try {
    const nodeId = effectiveNodeId(bench);
    const url = '/api/model/device/list?llamaBinPath=' + encodeURIComponent(llamaBinPath) + nodeQueryParam(nodeId);
    const data = await fetchJson(url, { method: 'GET' });
    if (data && data.success && data.data && Array.isArray(data.data.devices)) {
      bench.devices = data.data.devices;
      renderBenchParamsDevices();
    } else {
      list.innerHTML = '<div class="device-placeholder">' + escapeHtml(t('common.devices_load_failed', '获取设备列表失败')) + '</div>';
      bench.devices = [];
    }
  } catch(e) {
    list.innerHTML = '<div class="device-placeholder">' + escapeHtml(t('common.devices_load_failed', '获取设备列表失败')) + '</div>';
    bench.devices = [];
  }
}
function deviceLabel(d) {
  if (d == null) return '';
  return typeof d === 'string' ? d.trim() : safeText(d.name || d.brand || d.id || d).trim();
}
function renderBenchParamsDevices() {
  const list = E.bench.paramsDeviceList;
  if (!list) return;
  const devices = bench.devices;
  if (!devices.length) {
    list.innerHTML = '<div class="device-placeholder">' + escapeHtml(t('common.no_devices', '未发现可用设备')) + '</div>';
    renderBenchMainGpuSelect();
    return;
  }
  let html = '';
  for (let i = 0; i < devices.length; i++) {
    const label = escapeHtml(deviceLabel(devices[i]));
    html += '<label><input type="checkbox" checked data-device-index="' + i + '"><span class="device-label-text">' + label + '</span></label>';
  }
  list.innerHTML = html;
  list.querySelectorAll('input[type=checkbox]').forEach(chk => {
    chk.addEventListener('change', () => renderBenchMainGpuSelect());
  });
  renderBenchMainGpuSelect();
}
function getCheckedDeviceIndices() {
  const checks = E.bench.paramsDeviceList ? E.bench.paramsDeviceList.querySelectorAll('input[type=checkbox]') : [];
  const indices = [];
  checks.forEach((chk, i) => { if (chk.checked) indices.push(i); });
  return indices;
}
function renderBenchMainGpuSelect() {
  const select = E.bench.mainGpuSelect;
  if (!select) return;
  const prevVal = select.value;
  const devices = bench.devices;
  const checked = getCheckedDeviceIndices();
  const options = ['<option value="-1">' + escapeHtml(t('common.default', '默认')) + '</option>'];
  if (Array.isArray(devices) && checked.length > 0) {
    for (let pos = 0; pos < checked.length; pos++) {
      const idx = checked[pos];
      if (idx >= 0 && idx < devices.length) {
        options.push('<option value="' + pos + '">' + escapeHtml(deviceLabel(devices[idx])) + '</option>');
      }
    }
  }
  select.innerHTML = options.join('');
  const prev = parseInt(prevVal, 10);
  if (Number.isFinite(prev) && prev >= 0 && prev < checked.length)
    select.value = prevVal;
}
function getBenchMainGpu() {
  const select = E.bench.mainGpuSelect;
  if (!select) return -1;
  const n = parseInt(select.value, 10);
  return Number.isFinite(n) && n >= 0 ? n : -1;
}
async function runBench() {
  if (bench.isRunning) { cancelBench(); return; }
  const modelId = (bench.selectedModelId || '').trim();
  if (!modelId) { setBenchMeta(t('page.benchmark_v3.select_model', '请选择模型'), true); return; }
  bench.isRunning = true;
  bench.abortController = new AbortController();
  E.bench.runBtn.textContent = t('page.benchmark_v3.running', '运行中…');
  E.bench.runBtn.classList.add('btn-danger');
  try {
    const cmd = buildBenchCmd();
    const devArg = buildBenchDeviceArg();
    const fullCmd = cmd + (devArg ? ' ' + devArg : '');
    let llamaBinPath = (E.bench.llamaBinSelect && E.bench.llamaBinSelect.value || '').trim();
    if (!llamaBinPath) {
      const m = (Array.isArray(bench.allModels) ? bench.allModels : []).find(x => x.id === modelId && (!bench.selectedNodeId || bench.selectedNodeId === 'all' || (x.nodeId || 'local') === bench.selectedNodeId));
      if (m && m.llamaBinPath) llamaBinPath = m.llamaBinPath;
    }
    const payload = { modelId, cmd: fullCmd };
    if (llamaBinPath) payload.llamaBinPath = llamaBinPath;
    setNodeIdOnBody(payload, benchModelNodeId());
    const data = await fetchJson('/api/models/benchmark', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload), signal: bench.abortController.signal
    });
    if (data && data.success) {
      loadBenchHistory().then(() => {
        if (data.data && data.data.savedPath) {
          const savedName = data.data.savedPath.replace(/\\/g, '/').split('/').pop();
          if (savedName && bench.historyFiles.find(f => f.name === savedName)) {
            bench.selectedFileName = savedName;
            renderBenchHistory();
            loadBenchHistoryFile(savedName);
          }
        }
      });
    } else {
      setBenchMeta((data && data.error) || '失败');
    }
  } catch(e) {
    if (e && e.name === 'AbortError') { setBenchMeta(t('page.benchmark_v3.cancelled', '已取消'), true); }
    else { setBenchMeta((e && e.message) || t('common.request_failed', '请求失败')); }
  } finally {
    bench.isRunning = false; bench.abortController = null;
    E.bench.runBtn.textContent = t('page.benchmark_v3.run', '运行');
    E.bench.runBtn.classList.remove('btn-danger');
  }
}
async function loadBenchHistory() {
  const id = bench.selectedModelId;
  if (!id) {
    bench.historyFiles = []; bench.selectedFileName = ''; bench.selectedFileData = null;
    renderBenchHistory(); showBenchOutput();
    return;
  }
  setBenchMeta('加载记录中…', true);
  try {
    const nodeId = benchModelNodeId();
    const data = await fetchJson('/api/models/benchmark/list?modelId=' + encodeURIComponent(id) + nodeQueryParam(nodeId), { method: 'GET' });
    if (data && data.success && data.data && Array.isArray(data.data.files)) {
      bench.historyFiles = data.data.files;
    } else {
      bench.historyFiles = [];
    }
  } catch(e) {
    bench.historyFiles = [];
    setBenchMeta('加载失败: ' + (e && e.message || ''));
  }
  if (bench.historyFiles.length && (!bench.selectedFileName || !bench.historyFiles.find(f => f.name === bench.selectedFileName))) {
    bench.selectedFileName = bench.historyFiles[0].name;
  } else if (!bench.historyFiles.length) {
    bench.selectedFileName = '';
  }
  renderBenchHistory();
  if (bench.selectedFileName) {
    await loadBenchHistoryFile(bench.selectedFileName);
  } else {
    bench.selectedFileData = null;
    showBenchOutput();
  }
}
function renderBenchHistory() {
  if (!E.bench.historyList) return;
  E.bench.historyList.innerHTML = '';
  if (E.bench.historyCount) E.bench.historyCount.textContent = bench.historyFiles.length + '';
  if (!bench.historyFiles.length) {
    const div = document.createElement('div'); div.className = 'history-empty';
    div.textContent = bench.selectedModelId ? t('page.benchmark_v3.no_records', '暂无记录') : t('page.benchmark_v3.select_model', '请选择模型');
    E.bench.historyList.appendChild(div);
    return;
  }
  for (const f of bench.historyFiles) {
    const item = document.createElement('button');
    item.type = 'button'; item.className = 'history-item';
    if (f.name === bench.selectedFileName) item.classList.add('active');
    const info = document.createElement('div'); info.className = 'info';
    const ts = document.createElement('div'); ts.className = 'ts';
    // f.name format: {safeModelId}_{yyyyMMdd_HHmmss}.txt
    const match = (f.name || '').match(/(\d{8}_\d{6})/);
    ts.textContent = match ? formatTimestamp(match[1]) : (f.modified || f.name);
    const cmd = document.createElement('div'); cmd.className = 'cmd';
    cmd.textContent = formatFileSize(f.size);
    info.appendChild(ts); info.appendChild(cmd);
    const del = document.createElement('button');
    del.type = 'button'; del.className = 'del-btn'; del.innerHTML = '<i class="fas fa-times"></i>';
    del.title = '删除'; del.addEventListener('click', e => { e.stopPropagation(); deleteBenchHistoryFile(f.name); });
    item.appendChild(info); item.appendChild(del);
    item.addEventListener('click', () => {
      bench.selectedFileName = f.name;
      renderBenchHistory();
      loadBenchHistoryFile(f.name);
    });
    E.bench.historyList.appendChild(item);
  }
}
async function loadBenchHistoryFile(fileName) {
  if (!fileName) { bench.selectedFileData = null; showBenchOutput(); return; }
  setBenchMeta(t('common.loading', '加载中…'), true);
  try {
    const nodeId = benchModelNodeId();
    const data = await fetchJson('/api/models/benchmark/get?fileName=' + encodeURIComponent(fileName) + nodeQueryParam(nodeId), { method: 'GET' });
    if (data && data.success && data.data) {
      bench.selectedFileData = data.data;
    } else {
      bench.selectedFileData = null;
      setBenchMeta((data && data.error) || '加载失败');
    }
  } catch(e) {
    bench.selectedFileData = null;
    setBenchMeta('加载失败: ' + (e && e.message || ''));
  }
  showBenchOutput();
}
function showBenchOutput() {
  const d = bench.selectedFileData;
  if (E.bench.output) E.bench.output.textContent = d && d.rawOutput ? d.rawOutput : (bench.selectedFileName ? t('page.benchmark_v3.no_output', '(无输出)') : t('page.benchmark_v3.no_results', '暂无结果'));
  if (E.bench.outputMeta) {
    const parts = [];
    if (d && d.fileName) parts.push('文件: ' + d.fileName);
    if (d && d.savedPath) parts.push('路径: ' + d.savedPath);
    if (bench.historyFiles.length) parts.push('共 ' + bench.historyFiles.length + ' 条记录');
    setBenchMeta(parts.join('  |  '));
  }
  const hasData = !!(d && d.rawOutput);
  if (E.bench.copyBtn) E.bench.copyBtn.disabled = !hasData;
  if (E.bench.exportBtn) E.bench.exportBtn.disabled = !hasData;
}
async function deleteBenchHistoryFile(fileName) {
  if (!fileName || !window.confirm(t('page.benchmark_v3.confirm_delete', '确定删除该测试记录吗？'))) return;
  try {
    const nodeId = benchModelNodeId();
    const data = await fetchJson('/api/models/benchmark/delete?fileName=' + encodeURIComponent(fileName) + nodeQueryParam(nodeId), { method: 'POST' });
    if (data && data.success) {
      if (fileName === bench.selectedFileName) {
        bench.selectedFileName = '';
        bench.selectedFileData = null;
      }
      loadBenchHistory();
    } else {
      setBenchMeta((data && data.error) || '删除失败');
    }
  } catch(e) {
    setBenchMeta('删除失败: ' + (e && e.message || ''));
  }
}
function benchCopyOutput() {
  const text = E.bench.output ? E.bench.output.textContent : '';
  if (!text || text === t('page.benchmark_v3.no_results', '暂无结果')) return;
  navigator.clipboard.writeText(text).then(() => {
    setBenchMeta(t('common.link_copied_to_clipboard', '已复制到剪贴板'), true);
  }).catch(() => { setBenchMeta(t('common.clipboard_write_failed', '复制失败')); });
}
function benchExportText() {
  const text = E.bench.output ? E.bench.output.textContent : '';
  if (!text || text === t('page.benchmark_v3.no_results', '暂无结果')) { setBenchMeta(t('page.benchmark_v3.no_export_data', '没有可导出的数据'), true); return; }
  const fn = bench.selectedFileName || 'benchmark';
  const filename = fn.endsWith('.txt') ? fn : fn + '.txt';
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  downloadBlob(blob, filename);
  setBenchMeta('已导出', true);
}
function formatFileSize(bytes) {
  if (bytes == null) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
  return (bytes / 1073741824).toFixed(2) + ' GB';
}
let _benchMetaTimer = null;
function setBenchMeta(msg, isTransient) {
  if (!E.bench.outputMeta) return;
  if (_benchMetaTimer) { clearTimeout(_benchMetaTimer); _benchMetaTimer = null; }
  if (isTransient) {
    const prev = E.bench.outputMeta.textContent;
    E.bench.outputMeta.textContent = msg;
    _benchMetaTimer = setTimeout(() => {
      if (E.bench.outputMeta) E.bench.outputMeta.textContent = prev;
      _benchMetaTimer = null;
    }, 2000);
  } else {
    E.bench.outputMeta.textContent = msg;
  }
}
function benchSelectModel(id, nodeId) {
  bench.selectedModelId = id;
  bench.selectedNodeId = nodeId || '';
  bench.selectedFileName = '';
  bench.selectedFileData = null;
  updateActiveModelItem(bench, E.bench.modelList);
  const nd = benchModelNodeId();
  loadBenchLlamaCppPaths(nd);
  if (E.bench.selText) {
    E.bench.selText.style.display = id ? '' : 'none';
    if (id) E.bench.selText.textContent = t('page.benchmark_v3.model_prefix', '模型：') + id;
  }
  if (E.bench.output) E.bench.output.textContent = t('page.benchmark_v3.no_results', '暂无结果');
  setBenchMeta('');
  loadBenchHistory();
}

// == llama-server tab ==
async function initServerTab() {
  await loadServerModels();
  populateServerNodeFilter();
  renderServerModelList(server, E.server.modelList, E.server.modelsMeta);
  if (server.selectedModelId) loadServerRecords('replace');
}
async function loadServerModels() {
  try {
    const data = await fetchJson('/api/models/list', { method: 'GET' });
    const models = Array.isArray(data && data.models) ? data.models : [];
    server.allModels = models;
    try {
      const ld = await fetchJson('/api/models/loaded', { method: 'GET' });
      const loaded = Array.isArray(ld && ld.models) ? ld.models : [];
      server.loadedModels = loaded;
      server.loadedModelIds = new Set(loaded.map(m => modelLoadedKey(safeText(m && m.id).trim(), m && m.nodeId)).filter(Boolean));
    } catch(e) { server.loadedModels = []; server.loadedModelIds = new Set(); }
  } catch(e) { server.loadedModels = []; server.loadedModelIds = new Set(); server.allModels = []; }
}
function validateServerTokens() {
  const pt = parseInt(E.server.promptTokens.value, 10);
  const mt = parseInt(E.server.maxTokens.value, 10);
  if (!Number.isFinite(pt) || pt <= 0 || !Number.isFinite(mt) || mt <= 0) return false;
  return true;
}
async function runServer() {
  if (server.isRunning) { cancelServer(); return; }
  const modelId = (server.selectedModelId || '').trim();
  const promptTokens = parseInt(E.server.promptTokens.value, 10);
  const maxTokens = parseInt(E.server.maxTokens.value, 10);
  const concurrencyRaw = parseInt(E.server.concurrency.value, 10);
  const concurrency = Number.isFinite(concurrencyRaw) ? concurrencyRaw : NaN;
  if (!modelId) { E.server.status.textContent = t('page.benchmark_v3.select_model', '请选择模型'); return; }
  const nodeId = serverModelNodeId();
  if (!server.loadedModelIds.has(modelLoadedKey(modelId, nodeId || 'local'))) { E.server.status.textContent = t('page.benchmark_v3.model_not_loaded', '模型未加载'); return; }
  if (!Number.isFinite(promptTokens) || promptTokens <= 0) { E.server.status.textContent = t('page.benchmark_v3.validate.prompt_gt0', '提示词长度必须大于0'); return; }
  if (!Number.isFinite(maxTokens) || maxTokens <= 0) { E.server.status.textContent = t('page.benchmark_v3.validate.max_tokens_gt0', '输出最大token必须大于0'); return; }
  if (!Number.isFinite(concurrency) || concurrency <= 0) { E.server.status.textContent = t('page.benchmark_v3.validate.concurrency_gt0', '并发次数必须大于0'); return; }
  if (!validateServerTokens()) return;
  server.isRunning = true;
  server.abortController = new AbortController();
  E.server.runBtn.textContent = t('page.benchmark_v3.running', '运行中…');
  E.server.runBtn.classList.add('btn-danger');
  try {
    const payload = { modelId, promptTokens, maxTokens };
    setNodeIdOnBody(payload, serverModelNodeId());
    const requestOnce = () => fetchJson('/api/v2/models/benchmark', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload), signal: server.abortController.signal
    });
    if (concurrency === 1) {
      const data = await requestOnce();
      if (data && data.success) {
        saveHwProfile(server, data.data, modelId);
        loadServerRecords('replace');
      }
      return;
    }
    const tasks = Array.from({ length: concurrency }, () => requestOnce());
    const results = await Promise.allSettled(tasks);
    if (server.abortController && server.abortController.signal && server.abortController.signal.aborted) {
      return;
    }
    let ok = 0, fail = 0; const successData = [];
    for (const r of results) {
      if (r.status === 'fulfilled' && r.value && r.value.success) { ok++; successData.push(r.value.data); saveHwProfile(server, r.value.data, modelId); }
      else fail++;
    }
    if (ok > 0) {
      if (successData.length > 1) {
        const stats = computeStats(successData);
        server.records.unshift(stats);
        prependStatsRow(stats, E.server.resultBody, E.server.emptyRow);
      }
      loadServerRecords('replace');
    }
  } catch(e) {
    if (e && e.name === 'AbortError') { E.server.status.textContent = t('page.benchmark_v3.cancelled', '已取消'); return; }
    E.server.status.textContent = (e && e.message) || t('common.request_failed', '请求失败');
  } finally {
    server.isRunning = false; server.abortController = null;
    E.server.runBtn.textContent = t('page.benchmark_v3.run', '运行');
    E.server.runBtn.classList.remove('btn-danger');
  }
}
function computeStats(results) {
  const pf = results.map(r => Number(r.timings && r.timings.prompt_per_second)).filter(Number.isFinite);
  const dg = results.map(r => Number(r.timings && r.timings.predicted_per_second)).filter(Number.isFinite);
  const pn = results.map(r => Number(r.timings && r.timings.prompt_n)).filter(Number.isFinite);
  const dn = results.map(r => Number(r.timings && r.timings.predicted_n)).filter(Number.isFinite);
  const avg = a => a.length ? a.reduce((x,y) => x+y, 0) / a.length : 0;
  const min = a => a.length ? Math.min(...a) : 0;
  const max = a => a.length ? Math.max(...a) : 0;
  const pct = (a, p) => { if (!a.length) return 0; const s = a.slice().sort((x,y) => x-y); const i = Math.ceil(p/100*s.length)-1; return s[Math.max(0,Math.min(i,s.length-1))]; };
  return {
    isStats: true, modelId: results[0] && results[0].modelId, timestamp: new Date().toISOString(),
    count: results.length,
    prefill: { avg: avg(pf), min: min(pf), max: max(pf), p50: pct(pf,50), p95: pct(pf,95) },
    decode: { avg: avg(dg), min: min(dg), max: max(dg), p50: pct(dg,50), p95: pct(dg,95) },
    promptN: { avg: avg(pn), min: min(pn), max: max(pn) },
    predictedN: { avg: avg(dn), min: min(dn), max: max(dn) }
  };
}
function saveHwProfile(state, source, fallbackId) {
  const modelId = safeText(source && source.modelId ? source.modelId : fallbackId).trim();
  if (!modelId) return;
  const prev = state.hardwareByModel.get(modelId) || {};
  state.hardwareByModel.set(modelId, {
    cpu: safeText(source && source.cpu).trim() || safeText(prev.cpu).trim(),
    ram: (source && source.ram != null ? String(source.ram) : safeText(prev.ram)).trim(),
    devices: (source && cloneDevices(source.devices)).length ? cloneDevices(source.devices) : cloneDevices(prev.devices),
    llamaBinPath: safeText(source && source.llamaBinPath).trim() || safeText(prev.llamaBinPath).trim(),
    cmd: safeText(source && source.cmd).trim() || safeText(prev.cmd).trim()
  });
}
function hwFallback(record, state) {
  const out = record && typeof record === 'object' ? Object.assign({}, record) : {};
  const modelId = safeText(out.modelId ? out.modelId : (state.selectedModelId || '')).trim();
  const profile = modelId ? state.hardwareByModel.get(modelId) : null;
  if (!profile) return out;
  if (!safeText(out.cpu).trim() && safeText(profile.cpu).trim()) out.cpu = profile.cpu;
  if ((out.ram == null || String(out.ram).trim() === '') && safeText(profile.ram).trim()) out.ram = profile.ram;
  const d = cloneDevices(out.devices);
  const pd = cloneDevices(profile.devices);
  if (!d.length && pd.length) out.devices = pd;
  if (!safeText(out.llamaBinPath).trim() && safeText(profile.llamaBinPath).trim()) out.llamaBinPath = profile.llamaBinPath;
  if (!safeText(out.cmd).trim() && safeText(profile.cmd).trim()) out.cmd = profile.cmd;
  return out;
}
function clearServerChart() {
  if (E.server.chartEmpty) E.server.chartEmpty.style.display = 'flex';
  if (E.server.chartCanvas) {
    const ctx = E.server.chartCanvas.getContext('2d');
    ctx.clearRect(0, 0, E.server.chartCanvas.width, E.server.chartCanvas.height);
  }
}
async function loadServerRecords(mode) {
  const id = (server.selectedModelId || '').trim();
  if (!id) { clearResultRows(E.server.resultBody, E.server.emptyRow); clearServerChart(); return; }
  E.server.status.textContent = '加载记录中…';
  try {
    const nodeId = serverModelNodeId();
    const data = await fetchJson('/api/v2/models/benchmark/get?modelId=' + encodeURIComponent(id) + nodeQueryParam(nodeId), { method: 'GET' });
    if (!data || data.success !== true) {
      if (mode === 'replace') { clearResultRows(E.server.resultBody, E.server.emptyRow); clearServerChart(); }
      const err = data && data.error ? data.error : '';
      E.server.status.textContent = err === '文件不存在' ? t('page.benchmark_v3.no_records', '暂无记录') : (err || t('page.benchmark_v3.no_records', '暂无记录'));
      return;
    }
    const records = data && data.data && Array.isArray(data.data.records) ? data.data.records : [];
    if (!records.length) {
      if (mode === 'replace') { clearResultRows(E.server.resultBody, E.server.emptyRow); clearServerChart(); }
      E.server.status.textContent = t('page.benchmark_v3.no_records', '暂无记录');
      return;
    }
    const sorted = records.slice().sort((a, b) => String(b && b.timestamp).localeCompare(String(a && a.timestamp)));
    if (mode === 'replace') {
      clearResultRows(E.server.resultBody, E.server.emptyRow);
      server.records = [];
      server.recordKeys = new Set();
      for (const r of sorted) {
        saveHwProfile(server, r, id);
        server.records.push(r);
        server.recordKeys.add(r._lineNumber ? id + '|' + r._lineNumber : id + '|' + String(r.timestamp));
        appendResultRow(r, E.server.resultBody, E.server.emptyRow);
      }
      renderChart(E.server.chartCanvas, E.server.chartEmpty, E.server.chartWrap, server.records.filter(r => !r.isStats));
      E.server.status.textContent = '已加载 ' + sorted.length + ' 条记录';
    }
  } catch(e) {
    if (mode === 'replace') { clearResultRows(E.server.resultBody, E.server.emptyRow); clearServerChart(); }
    E.server.status.textContent = e.message || '记录加载失败';
  }
}
function serverSelectModel(id, nodeId) {
  server.selectedModelId = id;
  server.selectedNodeId = nodeId || '';
  updateActiveModelItem(server, E.server.modelList);
  const isLoaded = id && server.loadedModelIds.has(modelLoadedKey(id, nodeId));
  E.server.runBtn.disabled = !isLoaded;
  E.server.runBtn.title = isLoaded ? '' : t('page.benchmark_v3.cannot_run', '模型未加载，无法运行测试');
  const nd = serverModelNodeId();
  E.server.selText.style.display = id ? '' : 'none';
  if (id) E.server.selText.textContent = t('page.benchmark_v3.model_prefix', '模型：') + id;
  loadServerRecords('replace');
}
function cancelServer() {
  if (!server.isRunning) return;
  if (server.abortController) server.abortController.abort();
  E.server.runBtn.textContent = t('page.benchmark_v3.cancelling', '取消中…');
}
async function deleteServerRecord(record, btn) {
  const modelId = safeText(record.modelId || server.selectedModelId).trim();
  const lineNumber = record && record._lineNumber ? Number(record._lineNumber) : null;
  if (!modelId || !lineNumber) { E.server.status.textContent = '无法删除该记录'; return; }
  if (!window.confirm('确定删除该记录吗？')) return;
  if (btn) btn.disabled = true;
  E.server.status.textContent = '删除中…';
  try {
    const nodeId = serverModelNodeId();
    const payload = { modelId, lineNumber };
    setNodeIdOnBody(payload, nodeId);
    const data = await fetchJson('/api/v2/models/benchmark/delete', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (!data || data.success !== true) throw new Error((data && data.error) || '删除失败');
    loadServerRecords('replace');
    E.server.status.textContent = '已删除';
  } catch(e) { E.server.status.textContent = (e && e.message) || '删除失败'; }
  finally { if (btn) btn.disabled = false; }
}
function serverExport(format) {
  const records = server.records.filter(r => !r.isStats);
  if (!records.length) { E.server.status.textContent = t('page.benchmark_v3.no_export_data', '没有可导出的数据'); return; }
  doExport(records, format, server.selectedModelId || 'server', E.server.status);
}

// == Shared: result table rendering ==
function clearResultRows(bodyEl, emptyRow) {
  if (!bodyEl) return;
  bodyEl.innerHTML = '';
  if (emptyRow) { const clone = emptyRow.cloneNode(true); clone.id = emptyRow.id; bodyEl.appendChild(clone); }
}
function speedClass(value, isPrefill) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '';
  if (isPrefill) { if (n > 200) return 'speed-fast'; if (n > 100) return 'speed-mid'; return 'speed-slow'; }
  if (n > 50) return 'speed-fast'; if (n > 20) return 'speed-mid'; return 'speed-slow';
}
function createResultRow(record, deleteFn) {
  const tr = document.createElement('tr');
  const isStats = record && record.isStats;
  tr.className = isStats ? 'stats-row' : 'record-main';
  const timings = record && record.timings ? record.timings : null;
  const promptN = isStats ? (record.promptN ? Math.round(record.promptN.avg) : '-') : (record && record.promptTokens != null ? record.promptTokens : (timings ? timings.prompt_n : null));
  const predictedN = isStats ? (record.predictedN ? Math.round(record.predictedN.avg) : '-') : (timings && timings.predicted_n != null ? timings.predicted_n : (record ? record.maxTokens : null));
  const pfSpeed = isStats ? record.prefill : (timings ? timings.prompt_per_second : null);
  const dgSpeed = isStats ? record.decode : (timings ? timings.predicted_per_second : null);
  const cells = [
    formatTimestamp(record && record.timestamp),
    (record && record.modelId) || '-',
    isStats ? (record.promptN ? Math.round(record.promptN.avg) : '') : (promptN != null ? promptN : '-'),
    null, // prefill
    isStats ? (record.predictedN ? Math.round(record.predictedN.avg) : '') : (predictedN != null ? predictedN : '-'),
    null, // decode
    null  // action
  ];
  if (isStats) {
    const pf = record.prefill;
    cells[3] = pf ? pf.avg.toFixed(1) + ' (min:' + pf.min.toFixed(1) + ' P50:' + pf.p50.toFixed(1) + ' P95:' + pf.p95.toFixed(1) + ' max:' + pf.max.toFixed(1) + ')' : '-';
    const df = record.decode;
    cells[5] = df ? df.avg.toFixed(1) + ' (min:' + df.min.toFixed(1) + ' P50:' + df.p50.toFixed(1) + ' P95:' + df.p95.toFixed(1) + ' max:' + df.max.toFixed(1) + ')' : '-';
    cells[6] = '';
  } else {
    cells[3] = formatNumber(pfSpeed);
    cells[5] = formatNumber(dgSpeed);
    const delBtn = document.createElement('button');
    delBtn.type = 'button'; delBtn.className = 'btn record-action-btn'; delBtn.textContent = '删除';
    if (deleteFn && record._lineNumber) delBtn.addEventListener('click', () => deleteFn(record, delBtn));
    else delBtn.disabled = true;
    cells[6] = delBtn;
  }
  for (let i = 0; i < cells.length; i++) {
    const td = document.createElement('td');
    if (i === 3 || i === 5) {
      if (!isStats && cells[i] !== '-') td.className = speedClass(parseFloat(cells[i]), i === 3);
      td.textContent = cells[i];
    } else if (i === 6 && cells[i] instanceof HTMLElement) {
      td.className = 'action-cell'; td.appendChild(cells[i]);
    } else td.textContent = cells[i] == null ? '-' : String(cells[i]);
    tr.appendChild(td);
  }
  return tr;
}
function appendResultRow(record, bodyEl, emptyRow) {
  if (!bodyEl) return;
  const empty = bodyEl.querySelector('tr[id]');
  if (empty && empty.id && empty.id.includes('Empty')) empty.remove();
  const tr = createResultRow(record, deleteServerRecord);
  bodyEl.appendChild(tr);
  if (!record.isStats) {
    const merged = hwFallback(record, server);
    const details = [];
    const add = txt => { const r = document.createElement('tr'); r.className = 'record-detail'; const d = document.createElement('td'); d.colSpan = 7; d.textContent = txt; r.appendChild(d); details.push(r); };
    const cpuRam = (() => { const c = safeText(merged && merged.cpu).trim(); const r = safeText(merged && merged.ram).trim(); if (c && r) return c + ' / ' + r + 'GB'; if (c) return c; if (r) return r + 'GB'; return ''; })();
    const gpu = (() => { const r = merged ? merged.devices : null; if (Array.isArray(r)) return r.map(v => safeText(v).trim()).filter(Boolean).join(' | '); return safeText(r).trim(); })();
    const cmd = merged && merged.cmd ? String(merged.cmd) : '';
    const path = merged && merged.llamaBinPath ? String(merged.llamaBinPath) : '';
    const raw = record && record.rawOutput ? String(record.rawOutput) : '';
    if (cpuRam) add(cpuRam);
    if (gpu) add(gpu);
    if (cmd) add('cmd: ' + cmd);
    if (path) add('path: ' + path);
    if (raw) add('output: ' + (raw.length > 500 ? raw.slice(0, 500) + '…' : raw));
    for (const d of details) bodyEl.appendChild(d);
  }
}
function prependStatsRow(stats, bodyEl, emptyRow) {
  if (!bodyEl) return;
  const empty = bodyEl.querySelector('tr[id]');
  if (empty && empty.id && empty.id.includes('Empty')) empty.remove();
  const tr = createResultRow(stats, null);
  const first = bodyEl.firstChild;
  if (first) bodyEl.insertBefore(tr, first);
  else bodyEl.appendChild(tr);
}

// == Shared: chart ==
function chartVal(v) { const n = Number(v) || 0; return n >= 100000 ? 0 : n; }
function renderChart(canvasEl, emptyEl, wrapEl, records) {
  if (!canvasEl || !emptyEl || !wrapEl) return;
  const data = records.filter(r => !r.isStats && r.timings && r.timings.prompt_per_second != null);
  if (!data.length) { emptyEl.style.display = 'flex'; return; }
  emptyEl.style.display = 'none';
  const ctx = canvasEl.getContext('2d');
  const rect = wrapEl.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  const w = rect.width - 16, h = rect.height - 16;
  if (w <= 0 || h <= 0) return;
  canvasEl.width = w * dpr; canvasEl.height = h * dpr;
  canvasEl.style.width = w + 'px'; canvasEl.style.height = h + 'px';
  ctx.scale(dpr, dpr);
  const pad = { top: 10, bottom: 18, left: 36, right: 10 };
  const cw = w - pad.left - pad.right, ch = h - pad.top - pad.bottom;
  const count = Math.min(data.length, 30);
  const barW = Math.min(cw / count * 0.35, 16);
  const gap = barW * 0.5;
  const gw = barW * 2 + gap;
  let maxV = 0;
  data.slice(0, count).forEach(d => { const p = chartVal(d.timings.prompt_per_second); const g = chartVal(d.timings.predicted_per_second); maxV = Math.max(maxV, p, g); });
  maxV = Math.ceil(Math.max(maxV * 1.15, 10));
  const style = getComputedStyle(wrapEl);
  const tc = style.getPropertyValue('--text-secondary').trim() || '#999';
  const bc = style.getPropertyValue('--border-color').trim() || '#333';
  ctx.clearRect(0, 0, w, h);
  ctx.strokeStyle = bc; ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(pad.left, pad.top); ctx.lineTo(pad.left, pad.top + ch); ctx.lineTo(pad.left + cw, pad.top + ch); ctx.stroke();
  ctx.fillStyle = tc; ctx.font = '9px sans-serif'; ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
  for (let i = 0; i <= 4; i++) {
    const val = (maxV / 4) * i;
    const y = pad.top + ch - (ch / 4) * i;
    ctx.fillText(formatNumber(val), pad.left - 4, y);
    ctx.globalAlpha = 0.3; ctx.strokeStyle = bc; ctx.lineWidth = 0.5;
    ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(pad.left + cw, y); ctx.stroke();
    ctx.globalAlpha = 1;
  }
  const colors = ['rgba(99,102,241,0.8)', 'rgba(16,185,129,0.8)'];
  for (let i = 0; i < count; i++) {
    const dp = data[i];
    const pf = chartVal(dp.timings.prompt_per_second);
    const dg = chartVal(dp.timings.predicted_per_second);
    const x = pad.left + (cw / count) * i + (cw / count - gw) / 2;
    ctx.fillStyle = colors[0];
    ctx.fillRect(x, pad.top + ch - (pf / maxV) * ch, barW, (pf / maxV) * ch);
    ctx.fillStyle = colors[1];
    ctx.fillRect(x + barW + gap, pad.top + ch - (dg / maxV) * ch, barW, (dg / maxV) * ch);
    if (i % Math.max(1, Math.floor(count / 8)) === 0) {
      ctx.fillStyle = tc; ctx.font = '7px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
      const label = (dp.modelId || '#' + (i + 1));
      ctx.fillText(label.length > 6 ? label.slice(0, 5) + '…' : label, x + gw / 2, pad.top + ch + 2);
    }
  }
  ctx.font = '9px sans-serif'; ctx.textAlign = 'left'; ctx.textBaseline = 'top';
  ctx.fillStyle = colors[0]; ctx.fillRect(w - 90, 4, 8, 8);
  ctx.fillStyle = tc; ctx.fillText('Prefill', w - 78, 3);
  ctx.fillStyle = colors[1]; ctx.fillRect(w - 42, 4, 8, 8);
  ctx.fillStyle = tc; ctx.fillText('Decode', w - 30, 3);
}

// == Shared: export ==
function doExport(records, format, modelId, statusEl) {
  const data = records.map(r => {
    const t = r.timings || {};
    return {
      mode: r.mode, timestamp: r.timestamp, modelId: r.modelId,
      promptTokens: r.promptTokens != null ? r.promptTokens : (t.prompt_n || ''),
      maxTokens: r.maxTokens != null ? r.maxTokens : (t.predicted_n || ''),
      prefillSpeed: t.prompt_per_second, decodeSpeed: t.predicted_per_second,
      cpu: r.cpu || '', ram: r.ram || '', devices: r.devices || [],
      cmd: r.cmd || '', llamaBinPath: r.llamaBinPath || ''
    };
  });
  let content = '';
  if (format === 'csv') {
    const header = t('page.benchmark_v3.csv_header', '时间,模型,提示词长度,预填充速度(token/s),输出长度,输出速度(token/s),CPU/RAM,GPU,CMD,路径');
    const rows = data.map(r => [r.timestamp, r.modelId, r.promptTokens, formatNumber(r.prefillSpeed), r.maxTokens, formatNumber(r.decodeSpeed),
      (() => { const c = safeText(r.cpu); const ra = safeText(r.ram); if (c && ra) return c + ' / ' + ra + 'GB'; return c || (ra ? ra + 'GB' : ''); })(),
      Array.isArray(r.devices) ? r.devices.join(' | ') : safeText(r.devices),
      r.cmd.replace(/"/g, '""'), r.llamaBinPath.replace(/"/g, '""')
    ].map(v => '"' + String(v).replace(/"/g, '""') + '"').join(','));
    content = header + '\n' + rows.join('\n');
  } else if (format === 'json') {
    content = JSON.stringify(data, null, 2);
  } else if (format === 'md') {
    const lines = [t('page.benchmark_v3.md_title', '# Benchmark 测试结果'), '', t('page.benchmark_v3.md_header', '| 时间 | 模型 | 提示词长度 | 预填充速度 | 输出长度 | 输出速度 |'), '|------|------|-----------|-----------|---------|---------|'];
    data.forEach(r => {
      lines.push('| ' + [r.timestamp, r.modelId, r.promptTokens, formatNumber(r.prefillSpeed), r.maxTokens, formatNumber(r.decodeSpeed)].join(' | ') + ' |');
    });
    content = lines.join('\n');
  }
  const mime = format === 'csv' ? 'text/csv' : (format === 'json' ? 'application/json' : 'text/markdown');
  const blob = new Blob([content], { type: mime + ';charset=utf-8;' });
  downloadBlob(blob, 'benchmark_' + modelId + '.' + format);
  if (statusEl) statusEl.textContent = '已导出 ' + data.length + ' 条记录';
}

// == Cancel (llama-bench) ==
function cancelBench() {
  if (!bench.isRunning) return;
  if (bench.abortController) bench.abortController.abort();
  E.bench.runBtn.textContent = t('page.benchmark_v3.cancelling', '取消中…');
}

// == Event listeners ==
// llama-bench
E.bench.runBtn.addEventListener('click', runBench);
E.bench.modelList.addEventListener('click', e => {
  const t = e.target.closest('button[data-model-id]'); if (t) benchSelectModel(t.dataset.modelId, t.dataset.nodeId);
});
E.bench.searchInput.addEventListener('input', e => { bench.filterText = (e.target.value || '').trim(); renderBenchModelList(bench, E.bench.modelList, E.bench.modelsMeta); });
E.bench.nodeFilter.addEventListener('change', () => {
  bench.selectedNodeId = E.bench.nodeFilter.value;
  loadBenchLlamaCppPaths(effectiveNodeId(bench));
  renderBenchModelList(bench, E.bench.modelList, E.bench.modelsMeta);
  if (bench.selectedModelId) { benchSelectModel(bench.selectedModelId, bench.selectedNodeId); }
});
if (E.bench.copyBtn) E.bench.copyBtn.addEventListener('click', benchCopyOutput);
if (E.bench.exportBtn) E.bench.exportBtn.addEventListener('click', benchExportText);
if (E.bench.paramsBtn) E.bench.paramsBtn.addEventListener('click', openBenchParamsModal);
if (E.bench.paramsConfirmBtn) E.bench.paramsConfirmBtn.addEventListener('click', confirmBenchParams);
if (E.bench.paramsCloseBtn) E.bench.paramsCloseBtn.addEventListener('click', closeBenchParamsModal);
if (E.bench.paramsResetBtn) E.bench.paramsResetBtn.addEventListener('click', resetBenchParams);
if (E.bench.paramsModal) E.bench.paramsModal.addEventListener('click', e => { if (e.target === E.bench.paramsModal) closeBenchParamsModal(); });

// llama-server
E.server.runBtn.addEventListener('click', runServer);
E.server.promptTokens.addEventListener('input', validateServerTokens);
E.server.maxTokens.addEventListener('input', validateServerTokens);
E.server.searchInput.addEventListener('input', e => { server.filterText = (e.target.value || '').trim(); renderServerModelList(server, E.server.modelList, E.server.modelsMeta); });
E.server.nodeFilter.addEventListener('change', () => {
  server.selectedNodeId = E.server.nodeFilter.value;
  renderServerModelList(server, E.server.modelList, E.server.modelsMeta);
  if (server.selectedModelId) { serverSelectModel(server.selectedModelId, server.selectedNodeId); }
});
E.server.modelList.addEventListener('click', e => {
  const t = e.target.closest('button[data-model-id]'); if (t) {
    serverSelectModel(t.dataset.modelId, t.dataset.nodeId);
  }
});
E.server.exportBtns.forEach(btn => {
  btn.addEventListener('click', () => serverExport(btn.dataset.format));
});

window.addEventListener('resize', () => {
  renderChart(E.server.chartCanvas, E.server.chartEmpty, E.server.chartWrap, server.records);
});

// == Init ==
document.addEventListener('DOMContentLoaded', () => {
  if (!document.getElementById('sidebar')) initServerTab();
});

window.initServerTab = initServerTab;
window.initBenchTab = initBenchTab;
})();
