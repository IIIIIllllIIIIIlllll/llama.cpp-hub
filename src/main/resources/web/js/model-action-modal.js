function cssEscapeCompat(v) {
    if (v === null || v === undefined) return '';
    const s = String(v);
    if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(s);
    return s.replace(/["\\#.:()[\]>,+~=*$^|?{}!\s]/g, '\\$&');
}

function getLoadModelModal() {
    return document.getElementById('loadModelModal');
}

function getLoadModelForm(modal) {
    if (modal && modal.querySelector) {
        const f = modal.querySelector('form');
        if (f) return f;
    }
    return document.getElementById('loadModelForm');
}

function findInModal(modal, selector) {
    if (modal && modal.querySelector) {
        const el = modal.querySelector(selector);
        if (el) return el;
    }
    return document.querySelector(selector);
}

function findById(modal, id) {
    const safeId = cssEscapeCompat(id);
    if (modal && modal.querySelector) {
        const el = modal.querySelector('#' + safeId);
        if (el) return el;
    }
    return document.getElementById(id);
}

function findField(modal, nameOrId) {
    if (!nameOrId) return null;
    const byId = findById(modal, nameOrId);
    if (byId) return byId;
    const safeName = cssEscapeCompat(nameOrId);
    return findInModal(modal, `[name="${safeName}"]`);
}

function findFieldByName(modal, name) {
    if (!name) return null;
    const safeName = cssEscapeCompat(name);
    return findInModal(modal, `[name="${safeName}"]`);
}

function getFieldString(modal, keys) {
    const list = Array.isArray(keys) ? keys : [keys];
    for (let i = 0; i < list.length; i++) {
        const k = list[i];
        const el = findField(modal, k);
        if (el && 'value' in el) return String(el.value || '');
    }
    return '';
}

function setFieldValue(modal, keys, value) {
    const list = Array.isArray(keys) ? keys : [keys];
    for (let i = 0; i < list.length; i++) {
        const k = list[i];
        const el = findField(modal, k);
        if (!el) continue;
        if ('checked' in el && (el.type === 'checkbox' || el.type === 'radio')) {
            el.checked = !!value;
            return true;
        }
        if ('value' in el) {
            el.value = value === null || value === undefined ? '' : String(value);
            if (el.dataset && el.dataset.paramUi === 'ordered-multiselect' && typeof el.dispatchEvent === 'function') {
                try {
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                } catch (e) {
                    el.dispatchEvent(new Event('change'));
                }
            }
            return true;
        }
    }
    return false;
}

function setFieldBoolean01(modal, keys, boolValue) {
    const list = Array.isArray(keys) ? keys : [keys];
    for (let i = 0; i < list.length; i++) {
        const el = findField(modal, list[i]);
        if (!el) continue;
        if ('checked' in el && (el.type === 'checkbox' || el.type === 'radio')) {
            el.checked = !!boolValue;
            return true;
        }
        if ('value' in el) {
            el.value = boolValue ? '1' : '0';
            return true;
        }
    }
    return false;
}

function parseIntOrNull(v) {
    const n = parseInt(String(v || ''), 10);
    return Number.isFinite(n) ? n : null;
}

function parseFloatOrNull(v) {
    const n = parseFloat(String(v || ''));
    return Number.isFinite(n) ? n : null;
}

function getParamConfigListSafe() {
    try {
        const cfg = (window && window.paramConfig) ? window.paramConfig : (typeof paramConfig !== 'undefined' ? paramConfig : []);
        return Array.isArray(cfg) ? cfg : [];
    } catch (e) {
        return [];
    }
}

function fieldNameFromFullName(fullName) {
    const v = fullName === null || fullName === undefined ? '' : String(fullName);
    return v.replace(/^--/, '').replace(/^-/, '');
}

function sanitizeFieldKeyPart(v) {
    const s = v === null || v === undefined ? '' : String(v).trim();
    if (!s) return '';
    return s.replace(/[^a-zA-Z0-9_-]+/g, '_').replace(/^_+|_+$/g, '');
}

function fieldNameFromParamConfig(p) {
    if (!p) return '';
    const fullName = p.fullName === null || p.fullName === undefined ? '' : String(p.fullName).trim();
    if (fullName) return fieldNameFromFullName(fullName);
    const abbr = p.abbreviation === null || p.abbreviation === undefined ? '' : String(p.abbreviation).trim();
    if (abbr) return fieldNameFromFullName(abbr);
    const base = sanitizeFieldKeyPart(p.name);
    const sortRaw = p.sort === null || p.sort === undefined ? '' : String(p.sort).trim();
    return 'unnamed_' + (base || 'param') + (sortRaw ? '_' + sortRaw : '');
}

function isLoadModelParamEnabled(modal, fieldName) {
    const key = fieldName === null || fieldName === undefined ? '' : String(fieldName).trim();
    if (!key) return true;
    const el = findById(modal, 'param_enable_' + key);
    if (!el || !('checked' in el)) return true;
    return !!el.checked;
}

function setLoadModelParamEnabled(modal, fieldName, enabled) {
    const key = fieldName === null || fieldName === undefined ? '' : String(fieldName).trim();
    if (!key) return;
    const enableEl = findById(modal, 'param_enable_' + key);
    if (enableEl && 'checked' in enableEl) {
        enableEl.checked = !!enabled;
    }
    const valueEl = findById(modal, 'param_' + key);
    if (valueEl && 'value' in valueEl) {
        valueEl.value = enabled ? '1' : '0';
    }
}

function isTruthyLogicValue(value) {
    if (value === null || value === undefined) return false;
    const v = String(value).trim().toLowerCase();
    if (!v) return false;
    return v === '1' || v === 'true' || v === 'on' || v === 'yes';
}

function quoteArgIfNeeded(value) {
    const v = value === null || value === undefined ? '' : String(value);
    if (!v) return '';
    if (v.indexOf('"') !== -1) {
        return "'" + v.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + "'";
    }
    if (/\s/.test(v)) return '"' + v + '"';
    return v;
}

function splitCmdArgs(cmd) {
    const s = cmd === null || cmd === undefined ? '' : String(cmd);
    const tokens = [];
    let buf = '';
    let quoteChar = null;
    let escape = false;

    for (let i = 0; i < s.length; i++) {
        const ch = s[i];
        if (escape) {
            buf += ch;
            escape = false;
            continue;
        }
        if (ch === '\\' && quoteChar === "'") {
            escape = true;
            continue;
        }
        if (ch === '"' || ch === "'") {
            if (quoteChar === ch) {
                quoteChar = null;
            } else if (quoteChar === null) {
                quoteChar = ch;
            } else {
                buf += ch;
            }
            continue;
        }
        if (quoteChar === null && /\s/.test(ch)) {
            if (buf.length > 0) {
                tokens.push(buf);
                buf = '';
            }
            continue;
        }
        buf += ch;
    }
    if (buf.length > 0) tokens.push(buf);
    return tokens;
}

function buildOptionLookupFromParamConfig(cfgList) {
    const lookup = Object.create(null);
    for (let i = 0; i < cfgList.length; i++) {
        const p = cfgList[i];
        if (!p) continue;
        const fullName = p.fullName === null || p.fullName === undefined ? '' : String(p.fullName).trim();
        if (fullName) lookup[fullName] = p;
        const abbr = p.abbreviation === null || p.abbreviation === undefined ? '' : String(p.abbreviation).trim();
        if (abbr) lookup[abbr] = p;
    }
    return lookup;
}

function getParamUiType(p) {
    if (!p || p.uiType === null || p.uiType === undefined) return '';
    return String(p.uiType).trim().toLowerCase();
}

function getParamOptionValues(p) {
    if (!p || !Array.isArray(p.values)) return [];
    const out = [];
    for (let i = 0; i < p.values.length; i++) {
        const raw = p.values[i];
        let value = '';
        if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
            value = raw.value === null || raw.value === undefined ? '' : String(raw.value).trim();
        } else {
            value = raw === null || raw === undefined ? '' : String(raw).trim();
        }
        if (value) out.push(value);
    }
    return out;
}

function buildAllowedBareTokenSetFromParamConfig(cfgList) {
    const set = new Set();
    for (let i = 0; i < cfgList.length; i++) {
        const p = cfgList[i];
        if (!p) continue;
        const type = (p.type === null || p.type === undefined) ? 'STRING' : String(p.type);
        if (String(type).toUpperCase() !== 'STRING') continue;
        const fullName = p.fullName === null || p.fullName === undefined ? '' : String(p.fullName).trim();
        const abbr = p.abbreviation === null || p.abbreviation === undefined ? '' : String(p.abbreviation).trim();
        if (fullName || abbr) continue;
        const values = getParamOptionValues(p);
        for (let j = 0; j < values.length; j++) {
            const v = values[j];
            if (v && v.startsWith('-')) set.add(v);
        }
    }
    return set;
}

function isOptionLikeToken(token) {
    if (!token) return false;
    const t = String(token).trim();
    if (t.length < 2) return false;
    if (!t.startsWith('-')) return false;
    return /^-{1,2}\S+/.test(t);
}

function sanitizeExtraParamTokens(tokens, optionLookup, allowedBareTokens) {
    const out = [];
    for (let i = 0; i < tokens.length; i++) {
        const t = tokens[i] === null || tokens[i] === undefined ? '' : String(tokens[i]).trim();
        if (!t) continue;
        if (isOptionLikeToken(t) && !optionLookup[t] && !(allowedBareTokens && allowedBareTokens.has(t))) {
            const next = (i + 1) < tokens.length ? tokens[i + 1] : null;
            const nextStr = next === null || next === undefined ? '' : String(next).trim();
            if (nextStr && !isOptionLikeToken(nextStr)) i++;
            continue;
        }
        out.push(t);
    }
    return out;
}

function applyCmdToDynamicFields(modal, cmd) {
    const cfgList = getParamConfigListSafe();
    if (!cfgList.length) return;
    const optionLookup = buildOptionLookupFromParamConfig(cfgList);
    const allowedBareTokens = buildAllowedBareTokenSetFromParamConfig(cfgList);
    const tokens = splitCmdArgs(cmd);
    const consumed = new Array(tokens.length).fill(false);
    const valuesByField = Object.create(null);
    const enabledFields = new Set();

    function setParamEnabled(fieldName, enabled) {
        const key = fieldName === null || fieldName === undefined ? '' : String(fieldName).trim();
        if (!key) return;
        const el = findById(modal, 'param_enable_' + key);
        if (!el || !('checked' in el)) return;
        el.checked = !!enabled;
    }

    const cmdTrimmed = cmd === null || cmd === undefined ? '' : String(cmd).trim();
    if (!cmdTrimmed) {
        for (let i = 0; i < cfgList.length; i++) {
            const p = cfgList[i];
            if (!p) continue;
            const fieldName = fieldNameFromParamConfig(p);
            if (!fieldName) continue;
            const defaultEnabled = p.defaultEnabled === true;
            setParamEnabled(fieldName, defaultEnabled);

            const type = (p.type === null || p.type === undefined) ? 'STRING' : String(p.type);
            const typeUpper = String(type).toUpperCase();
            const values = getParamOptionValues(p);
            let defaultValue = p.defaultValue;
            if (defaultValue === null || defaultValue === undefined) {
                defaultValue = values.length ? values[0] : '';
            } else {
                defaultValue = String(defaultValue);
            }

            if (typeUpper === 'LOGIC') {
                if (defaultValue === '') defaultValue = '0';
            } else if (typeUpper === 'BOOLEAN') {
                if (defaultValue === '') defaultValue = '0';
            }

            if (defaultValue !== undefined && defaultValue !== null) {
                setFieldValue(modal, [fieldName, 'param_' + fieldName], String(defaultValue));
            }
        }
        return;
    }

    for (let i = 0; i < cfgList.length; i++) {
        const p = cfgList[i];
        if (!p) continue;
        const fieldName = fieldNameFromParamConfig(p);
        if (!fieldName) continue;
        setParamEnabled(fieldName, false);
    }

    function isKnownOption(token) {
        if (!token) return false;
        return !!optionLookup[token];
    }

    for (let i = 0; i < tokens.length; i++) {
        const raw = tokens[i];
        if (!raw) continue;
        let opt = raw;
        let inlineVal = null;
        const eqIdx = raw.indexOf('=');
        if (eqIdx > 0) {
            const left = raw.slice(0, eqIdx);
            if (isKnownOption(left)) {
                opt = left;
                inlineVal = raw.slice(eqIdx + 1);
            }
        }

        if (!isKnownOption(opt)) continue;
        const p = optionLookup[opt];
        consumed[i] = true;
        const fullName = p && p.fullName ? String(p.fullName) : opt;
        const fieldName = fieldNameFromFullName(fullName);
        if (!fieldName) continue;
        const type = (p && p.type ? String(p.type) : 'STRING').toUpperCase();

        if (type === 'LOGIC') {
            valuesByField[fieldName] = '1';
            enabledFields.add(fieldName);
            continue;
        }

        let v = inlineVal;
        if (v === null) {
            const next = (i + 1) < tokens.length ? tokens[i + 1] : null;
            if (next !== null && next !== undefined && !isKnownOption(next)) {
                v = next;
                consumed[i + 1] = true;
                i++;
            }
        }
        if (v !== null && v !== undefined) valuesByField[fieldName] = String(v);
        enabledFields.add(fieldName);
    }

    for (let i = 0; i < cfgList.length; i++) {
        const p = cfgList[i];
        if (!p) continue;
        const type = (p.type === null || p.type === undefined) ? 'STRING' : String(p.type);
        if (String(type).toUpperCase() !== 'STRING') continue;
        const fullName = p.fullName === null || p.fullName === undefined ? '' : String(p.fullName).trim();
        const abbr = p.abbreviation === null || p.abbreviation === undefined ? '' : String(p.abbreviation).trim();
        if (fullName || abbr) continue;
        const values = getParamOptionValues(p);
        if (!values.length) continue;
        const defaultValue = p.defaultValue === null || p.defaultValue === undefined ? values[0] : String(p.defaultValue).trim();

        let picked = defaultValue;
        let hasExplicitValue = false;
        for (let ti = 0; ti < tokens.length; ti++) {
            if (consumed[ti]) continue;
            const t = tokens[ti] === null || tokens[ti] === undefined ? '' : String(tokens[ti]).trim();
            if (!t) continue;
            if (values.includes(t)) {
                picked = t;
                consumed[ti] = true;
                hasExplicitValue = true;
                break;
            }
        }

        const fieldName = fieldNameFromParamConfig(p);
        if (fieldName) {
            valuesByField[fieldName] = picked;
            if (hasExplicitValue) enabledFields.add(fieldName);
        }
    }

    for (let i = 0; i < cfgList.length; i++) {
        const p = cfgList[i];
        if (!p) continue;
        const type = (p.type === null || p.type === undefined) ? 'STRING' : String(p.type);
        if (type.toUpperCase() !== 'LOGIC') continue;
        const fullName = p.fullName === null || p.fullName === undefined ? '' : String(p.fullName);
        const fieldName = fieldNameFromFullName(fullName);
        if (!fieldName) continue;
        if (valuesByField[fieldName] !== '1') valuesByField[fieldName] = '0';
    }

    const entries = Object.keys(valuesByField);
    for (let i = 0; i < entries.length; i++) {
        const k = entries[i];
        setFieldValue(modal, [k, 'param_' + k], valuesByField[k]);
    }

    for (let i = 0; i < cfgList.length; i++) {
        const p = cfgList[i];
        if (!p) continue;
        const fieldName = fieldNameFromParamConfig(p);
        if (!fieldName) continue;
        setParamEnabled(fieldName, enabledFields.has(fieldName));
    }

    const extras = [];
    for (let i = 0; i < tokens.length; i++) {
        if (consumed[i]) continue;
        const t = tokens[i] === null || tokens[i] === undefined ? '' : String(tokens[i]).trim();
        if (!t) continue;
        extras.push(quoteArgIfNeeded(t));
    }
    const extraStr = extras.join(' ').trim();
    if (extraStr) setFieldValue(modal, ['extraParams'], extraStr);
}

function isPlainObject(v) {
    return !!(v && typeof v === 'object' && !Array.isArray(v));
}

function getDefaultConfigName() {
    return '默认配置';
}

function normalizeLaunchConfigBundle(rawEntry) {
    const fallbackName = getDefaultConfigName();
    const sharedSources = (isPlainObject(rawEntry) && isPlainObject(rawEntry.sharedSources)) ? rawEntry.sharedSources : undefined;
    const sharedOutConfigs = (isPlainObject(rawEntry) && Array.isArray(rawEntry.sharedOutConfigs)) ? rawEntry.sharedOutConfigs : undefined;
    if (!isPlainObject(rawEntry)) {
        return {
            selectedConfig: fallbackName,
            configs: { [fallbackName]: {} }
        };
    }
    if (isPlainObject(rawEntry.configs)) {
        const configs = {};
        const names = Object.keys(rawEntry.configs);
        for (let i = 0; i < names.length; i++) {
            const name = names[i];
            const item = rawEntry.configs[name];
            configs[name] = isPlainObject(item) ? item : {};
        }
        if (!Object.keys(configs).length) {
            configs[fallbackName] = {};
        }
        const selectedRaw = rawEntry.selectedConfig === null || rawEntry.selectedConfig === undefined ? '' : String(rawEntry.selectedConfig).trim();
        const selectedConfig = selectedRaw && configs[selectedRaw] ? selectedRaw : Object.keys(configs)[0];
        const result = { selectedConfig, configs };
        if (sharedSources) result.sharedSources = sharedSources;
        if (sharedOutConfigs) result.sharedOutConfigs = sharedOutConfigs;
        return result;
    }
    const result = {
        selectedConfig: fallbackName,
        configs: { [fallbackName]: rawEntry }
    };
    if (sharedSources) result.sharedSources = sharedSources;
    if (sharedOutConfigs) result.sharedOutConfigs = sharedOutConfigs;
    return result;
}

function extractLaunchConfigBundleFromGetResponse(res, modelId) {
    if (!(res && res.success)) return normalizeLaunchConfigBundle(null);
    const data = res.data;
    if (!isPlainObject(data)) return normalizeLaunchConfigBundle(null);
    if (isPlainObject(data.configs)) {
        const bundle = normalizeLaunchConfigBundle(data);
        if (isPlainObject(data.sharedSources)) {
            bundle.sharedSources = data.sharedSources;
        }
        if (Array.isArray(data.sharedOutConfigs)) {
            bundle.sharedOutConfigs = data.sharedOutConfigs;
        }
        return bundle;
    }
    const direct = data[modelId];
    const bundle = normalizeLaunchConfigBundle(direct);
    if (isPlainObject(direct) && isPlainObject(direct.sharedSources)) {
        bundle.sharedSources = direct.sharedSources;
    }
    if (isPlainObject(direct) && Array.isArray(direct.sharedOutConfigs)) {
        bundle.sharedOutConfigs = direct.sharedOutConfigs;
    }
    return bundle;
}

function extractLaunchConfigFromGetResponse(res, modelId) {
    const bundle = extractLaunchConfigBundleFromGetResponse(res, modelId);
    const selected = bundle && bundle.selectedConfig ? bundle.selectedConfig : getDefaultConfigName();
    if (bundle && isPlainObject(bundle.configs) && isPlainObject(bundle.configs[selected])) {
        return bundle.configs[selected];
    }
    return {};
}

function renderModelConfigSelect(modal, bundle) {
    const select = findById(modal, 'modelConfigSelect');
    if (!select) return;
    const normalized = normalizeLaunchConfigBundle(bundle);
    const names = Object.keys(normalized.configs);
    const sharedSources = isPlainObject(normalized.sharedSources) ? normalized.sharedSources : {};
    const modelsData = (currentModelsData || []).map(m => ({ id: m && m.id, name: m && (m.alias || m.id) }));
    select.innerHTML = names.map(name => {
        const source = sharedSources[name];
        let label = escapeHtml(name);
        if (source && source.source) {
            const sourceModel = modelsData.find(m => m.id === source.source);
            const sourceName = sourceModel ? sourceModel.name : source.source;
            label = `[共享] ${escapeHtml(name)} <span class="config-source" title="${escapeHtml(String(source.sourceConfigName || ''))}">(${escapeHtml(String(sourceName))})</span>`;
        }
        return `<option value="${escapeHtml(name)}">${label}</option>`;
    }).join('');
    window.__modelConfigBundle = normalized;
    select.value = normalized.selectedConfig;
    updateShareCheckbox(modal, normalized);
}

function updateShareCheckbox(modal, bundle) {
    const cb = findById(modal, 'shareConfigCheckbox');
    if (!cb) return;
    const modelId = getFieldString(modal, ['modelId']);
    if (!modelId) {
        cb.checked = false;
        cb.disabled = true;
        return;
    }
    const selectedConfig = getSelectedModelConfigName(modal);
    const sharedOutConfigs = Array.isArray(bundle.sharedOutConfigs) ? bundle.sharedOutConfigs : [];
    const isSharedOut = sharedOutConfigs.includes(selectedConfig);
    const sharedSources = isPlainObject(bundle.sharedSources) ? bundle.sharedSources : {};
    const isFromOther = sharedSources[selectedConfig] != null;
    if (isFromOther) {
        cb.checked = false;
        cb.disabled = true;
    } else {
        cb.checked = isSharedOut;
        cb.disabled = false;
    }
}

// 关键注释：将当前UI中的启动参数整理为可持久化配置对象
function buildPersistableLaunchConfig(modal) {
    const base = buildLoadModelPayload(modal);
    const cmdLineTextarea = findById(modal, 'commandLineInput');
    return {
        llamaBinPath: base && base.llamaBinPathSelect ? base.llamaBinPathSelect : '',
        mg: base && base.mg !== undefined ? base.mg : -1,
        cmd: base && base.cmd ? base.cmd : '',
        extraParams: base && base.extraParams ? base.extraParams : '',
        envVars: base && base.envVars ? base.envVars : '',
        enableVision: base && base.enableVision !== undefined ? !!base.enableVision : true,
        device: base && Array.isArray(base.device) ? base.device : ['All'],
        mode: window.__paramMode === 'cmd' ? 'cmd' : 'form',
        paramMode: window.__paramMode === 'cmd' ? 'cmd' : 'form',
        cmdLine: cmdLineTextarea ? String(cmdLineTextarea.value || '').trim() : ''
    };
}

function getSelectedModelConfigName(modal) {
    const select = findById(modal, 'modelConfigSelect');
    if (!select || !select.value) return getDefaultConfigName();
    return String(select.value).trim() || getDefaultConfigName();
}

function buildConfigSetPayload(modelId, configName, cfg, nodeId) {
    const p = {
        modelId: modelId,
        configName: configName,
        setSelected: true,
        config: cfg
    };
    if (nodeId && nodeId !== 'local') p.nodeId = nodeId;
    return p;
}

function buildConfigDeletePayload(modelId, configName, nodeId) {
    const p = {
        modelId: modelId,
        configName: configName
    };
    if (nodeId && nodeId !== 'local') p.nodeId = nodeId;
    return p;
}

function setModelConfigControlsDisabled(modal, disabled) {
    const addBtn = findById(modal, 'addModelConfigBtn');
    const delBtn = findById(modal, 'deleteModelConfigBtn');
    const configSelect = findById(modal, 'modelConfigSelect');
    const shareCb = findById(modal, 'shareConfigCheckbox');
    if (addBtn) addBtn.disabled = !!disabled;
    if (delBtn) delBtn.disabled = !!disabled;
    if (configSelect) configSelect.disabled = !!disabled;
    if (shareCb) shareCb.disabled = !!disabled;
}

function applyLaunchConfigToModal(modal, config) {
    const cfg = isPlainObject(config) ? config : {};
    const paramMode = cfg.paramMode === 'cmd' || cfg.mode === 'cmd' ? 'cmd' : 'form';
    const cmdStr = cfg.cmd === null || cfg.cmd === undefined ? '' : String(cfg.cmd);
    let cmdLineStr = '';
    if (cfg.cmdLine !== undefined && cfg.cmdLine !== null) {
        cmdLineStr = String(cfg.cmdLine);
    } else if (paramMode === 'cmd' && cfg.cmd !== undefined && cfg.cmd !== null) {
        cmdLineStr = String(cfg.cmd);
    }

    // 先设置模式，确保后续表单/命令行内容写入到正确的面板
    window.__paramMode = paramMode;
    switchParamMode(paramMode);

    let applied = false;
    let attempts = 0;
    const maxAttempts = 60;
    const tryApply = () => {
        if (applied) return;
        attempts++;
        const cfgList = getParamConfigListSafe();
        const dyn = findById(modal, 'dynamicParamsContainer');
        const hasToggle = !!(dyn && dyn.querySelector && dyn.querySelector('input[type="checkbox"][id^="param_enable_"]'));
        const ready = cfgList && cfgList.length && hasToggle && findById(modal, 'extraParams');
        if (ready) {
            applied = true;
            if (paramMode === 'cmd') {
                const textarea = findById(modal, 'commandLineInput');
                if (textarea) textarea.value = cmdLineStr;
                window.__cmdLineDraft = cmdLineStr;
            } else {
                window.__cmdLineDraft = undefined;
                applyCmdToDynamicFields(modal, cmdStr);
                if (cfg.extraParams !== undefined && cfg.extraParams !== null && String(cfg.extraParams).trim()) {
                    setFieldValue(modal, ['extraParams'], String(cfg.extraParams));
                } else if (cfg.extraParams !== undefined) {
                    setFieldValue(modal, ['extraParams'], cfg.extraParams || '');
                }
                setFieldValue(modal, ['envVars'], cfg.envVars !== undefined && cfg.envVars !== null ? String(cfg.envVars) : '');
            }
            return;
        }
        if (attempts >= maxAttempts) return;
        setTimeout(tryApply, 60);
    };
    tryApply();

    const enableVisionEl = findField(modal, 'enableVision');
    if (enableVisionEl && 'checked' in enableVisionEl) {
        enableVisionEl.checked = cfg.enableVision !== undefined ? !!cfg.enableVision : true;
    }
    window.__loadModelSelectedDevices = normalizeDeviceSelection(cfg.device);
    window.__loadModelMainGpu = normalizeMainGpu(cfg.mg);
    window.__loadModelSelectionFromConfig = true;
}

function onModelConfigSelectionChange() {
    const modal = getLoadModelModal();
    if (!modal) return;
    const selected = getSelectedModelConfigName(modal);
    const bundle = normalizeLaunchConfigBundle(window.__modelConfigBundle);
    if (!bundle.configs[selected]) return;
    bundle.selectedConfig = selected;
    window.__modelConfigBundle = bundle;
    updateShareCheckbox(modal, bundle);
    applyLaunchConfigToModal(modal, bundle.configs[selected]);
}

function addModelConfigOption() {
    const modal = getLoadModelModal();
    if (!modal) return;
    const modelId = getFieldString(modal, ['modelId']);
    if (!modelId) return;
    const inputName = window.prompt(t('modal.model_action.config.prompt_new_name', '请输入新配置名称'));
    const name = inputName === null || inputName === undefined ? '' : String(inputName).trim();
    if (!name) return;
    const bundle = normalizeLaunchConfigBundle(window.__modelConfigBundle);
    if (!bundle.configs[name]) {
        bundle.configs[name] = buildPersistableLaunchConfig(modal);
    }
    bundle.selectedConfig = name;
    renderModelConfigSelect(modal, bundle);
    onModelConfigSelectionChange();
    const nodeId = modal && modal.__nodeId ? modal.__nodeId : '';
    const payload = buildConfigSetPayload(modelId, name, bundle.configs[name], nodeId);
    setModelConfigControlsDisabled(modal, true);
    fetch('/api/models/config/set', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    }).then(r => r.json()).then(res => {
        if (!(res && res.success)) {
            showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('common.save_failed', '保存失败'), 'error');
            return;
        }
        showToast(t('toast.success', '成功'), t('modal.model_action.config.saved', '启动参数已保存'), 'success');
    }).catch(() => {
        showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
    }).finally(() => {
        setModelConfigControlsDisabled(modal, false);
    });
}

function deleteModelConfigOption() {
    const modal = getLoadModelModal();
    if (!modal) return;
    const modelId = getFieldString(modal, ['modelId']);
    if (!modelId) return;
    const name = getSelectedModelConfigName(modal);
    if (!name) return;
    const ok = window.confirm(t('modal.model_action.config.delete_confirm', '确认删除配置「{name}」吗？').replace('{name}', name));
    if (!ok) return;
    const nodeId = modal && modal.__nodeId ? modal.__nodeId : '';
    const payload = buildConfigDeletePayload(modelId, name, nodeId);
    setModelConfigControlsDisabled(modal, true);
    fetch('/api/models/config/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    }).then(r => r.json()).then(res => {
        if (!(res && res.success)) {
            showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('modal.model_action.config.delete_failed', '删除配置失败'), 'error');
            return;
        }
        const nextBundle = extractLaunchConfigBundleFromGetResponse(res, modelId);
        renderModelConfigSelect(modal, nextBundle);
        const selected = nextBundle.selectedConfig;
        const nextConfig = nextBundle.configs[selected] || {};
        applyLaunchConfigToModal(modal, nextConfig);
        showToast(t('toast.success', '成功'), t('modal.model_action.config.deleted', '配置已删除'), 'success');
    }).catch(() => {
        showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
    }).finally(() => {
        setModelConfigControlsDisabled(modal, false);
    });
}

function toggleShareConfig() {
    const modal = getLoadModelModal();
    if (!modal) return;
    const cb = findById(modal, 'shareConfigCheckbox');
    if (!cb) return;
    const modelId = getFieldString(modal, ['modelId']);
    if (!modelId) return;
    const configName = getSelectedModelConfigName(modal);
    if (!configName) return;
    const nodeId = modal && modal.__nodeId ? modal.__nodeId : '';
    const sharedName = configName;
    setModelConfigControlsDisabled(modal, true);
    if (cb.checked) {
        const payload = { modelId: modelId, configName: configName, sharedName: sharedName };
        if (nodeId && nodeId !== 'local') payload.nodeId = nodeId;
        fetch('/api/models/config/shared/set', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        }).then(r => r.json()).then(res => {
            if (!(res && res.success)) {
                cb.checked = false;
                showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('modal.model_action.config.share_failed', '共享配置失败'), 'error');
                return;
            }
            showToast(t('toast.success', '成功'), t('modal.model_action.config.shared', '配置已共享'), 'success');
            refreshModelConfigBundle(modal, configName);
        }).catch(() => {
            cb.checked = false;
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }).finally(() => {
            setModelConfigControlsDisabled(modal, false);
        });
    } else {
        const payload = { sharedName: sharedName };
        if (nodeId && nodeId !== 'local') payload.nodeId = nodeId;
        fetch('/api/models/config/shared/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        }).then(r => r.json()).then(res => {
            if (!(res && res.success)) {
                cb.checked = true;
                showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('modal.model_action.config.unshare_failed', '取消共享失败'), 'error');
                return;
            }
            showToast(t('toast.success', '成功'), t('modal.model_action.config.unshared', '已取消共享'), 'success');
            refreshModelConfigBundle(modal, configName);
        }).catch(() => {
            cb.checked = true;
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }).finally(() => {
            setModelConfigControlsDisabled(modal, false);
        });
    }
}

function refreshModelConfigBundle(modal, preserveSelected) {
    const modelId = getFieldString(modal, ['modelId']);
    if (!modelId) return;
    const nodeId = modal && modal.__nodeId ? modal.__nodeId : '';
    const configUrl = nodeId && nodeId !== 'local'
        ? `/api/models/config/get?modelId=${encodeURIComponent(modelId)}&nodeId=${encodeURIComponent(nodeId)}`
        : `/api/models/config/get?modelId=${encodeURIComponent(modelId)}`;
    fetch(configUrl)
        .then(r => r.json())
        .then(data => {
            const bundle = extractLaunchConfigBundleFromGetResponse(data, modelId);
            if (preserveSelected && bundle.configs[preserveSelected]) {
                bundle.selectedConfig = preserveSelected;
            }
            renderModelConfigSelect(modal, bundle);
            const selected = bundle.selectedConfig;
            const config = bundle.configs[selected] || {};
            applyLaunchConfigToModal(modal, config);
        })
        .catch(() => {});
}

function parseBooleanLike(v, fallback = false) {
    if (v === null || v === undefined) return fallback;
    if (typeof v === 'boolean') return v;
    if (typeof v === 'number') return v !== 0;
    const s = String(v).trim().toLowerCase();
    if (!s) return fallback;
    if (s === 'true' || s === '1' || s === 'yes' || s === 'y' || s === 'on') return true;
    if (s === 'false' || s === '0' || s === 'no' || s === 'n' || s === 'off') return false;
    return fallback;
}

function getModelCapabilitiesEls(modal) {
    return {
        group: findById(modal, 'modelCapabilitiesGroup'),
        thinking: findById(modal, 'capabilityThinking'),
        tools: findById(modal, 'capabilityTools'),
        rerank: findById(modal, 'capabilityRerank'),
        embedding: findById(modal, 'capabilityEmbedding'),
        vision: findById(modal, 'capabilityVision'),
        audio: findById(modal, 'capabilityAudio')
    };
}

function normalizeModelCapabilities(input) {
    const base = input && typeof input === 'object' && input.capabilities && typeof input.capabilities === 'object'
        ? input.capabilities
        : input;
    return {
        thinking: parseBooleanLike(base && base.thinking, false),
        tools: parseBooleanLike(base && base.tools, false),
        rerank: parseBooleanLike(base && base.rerank, false),
        embedding: parseBooleanLike(base && base.embedding, false),
        vision: parseBooleanLike(base && base.vision, false),
        audio: parseBooleanLike(base && base.audio, false)
    };
}

function readModelCapabilitiesFromUi(modal) {
    const els = getModelCapabilitiesEls(modal);
    if (!els.thinking || !els.tools || !els.rerank || !els.embedding) {
        return { thinking: false, tools: false, rerank: false, embedding: false };
    }
    return {
        thinking: !!els.thinking.checked,
        tools: !!els.tools.checked,
        rerank: !!els.rerank.checked,
        embedding: !!els.embedding.checked,
        vision: !!els.vision.checked,
        audio: !!els.audio.checked
    };
}

function enforceModelCapabilitiesRules(modal, changedKey) {
    const els = getModelCapabilitiesEls(modal);
    if (!els.thinking || !els.tools || !els.rerank || !els.embedding) return;

    if (els.rerank.checked && els.embedding.checked) {
        if (changedKey === 'rerank') {
            els.embedding.checked = false;
        } else {
            els.rerank.checked = false;
        }
    }

    if (changedKey === 'rerank' && els.rerank.checked) els.embedding.checked = false;
    if (changedKey === 'embedding' && els.embedding.checked) els.rerank.checked = false;

    if ((changedKey === 'thinking' || changedKey === 'tools') && (els.thinking.checked || els.tools.checked)) {
        els.rerank.checked = false;
        els.embedding.checked = false;
    }

    const isNonChat = !!(els.rerank.checked || els.embedding.checked);
    if (isNonChat) {
        els.thinking.checked = false;
        els.tools.checked = false;
        els.thinking.disabled = true;
        els.tools.disabled = true;
    } else {
        els.thinking.disabled = false;
        els.tools.disabled = false;
    }

    const hasVisionOrAudio = !!(els.vision && els.vision.checked || els.audio && els.audio.checked);
    if (hasVisionOrAudio) {
        els.rerank.checked = false;
        els.embedding.checked = false;
    }
}

function applyModelCapabilitiesToUi(modal, caps) {
    const els = getModelCapabilitiesEls(modal);
    if (!els.thinking || !els.tools || !els.rerank || !els.embedding) return;
    const c = normalizeModelCapabilities(caps);
    els.thinking.checked = !!c.thinking;
    els.tools.checked = !!c.tools;
    els.rerank.checked = !!c.rerank;
    els.embedding.checked = !!c.embedding;
    if (els.vision) els.vision.checked = !!c.vision;
    if (els.audio) els.audio.checked = !!c.audio;
    enforceModelCapabilitiesRules(modal, '');

    // 自动关联启动参数：仅在首次加载 capabilities 时触发，避免覆盖用户手动操作
    if (window.__capabilitiesApplying) {
        setLoadModelParamEnabled(modal, 'embedding', !!c.embedding);
        setLoadModelParamEnabled(modal, 'rerank', !!c.rerank);
    }
}

function saveModelCapabilitiesNow(modelId, caps, modal) {
    const payload = Object.assign({ modelId: modelId }, normalizeModelCapabilities(caps));
    const nodeId = modal && modal.__nodeId && modal.__nodeId !== 'local' ? modal.__nodeId : '';
    if (nodeId) payload.nodeId = nodeId;
    fetch('/api/models/capabilities/set', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    }).then(r => r.json()).then(res => {
        if (!(res && res.success)) {
            showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('modal.model_action.capabilities.save_failed', '保存模型能力失败'), 'error');
        }
    }).catch(() => {
        showToast(t('toast.error', '错误'), t('modal.model_action.capabilities.save_failed', '保存模型能力失败'), 'error');
    });
}

function scheduleSaveModelCapabilities(modelId, modal) {
    const mid = modelId === null || modelId === undefined ? '' : String(modelId).trim();
    if (!mid) return;
    const caps = readModelCapabilitiesFromUi(modal);
    if (!window.__capabilitiesSaveTimers) window.__capabilitiesSaveTimers = {};
    if (window.__capabilitiesSaveTimers[mid]) {
        clearTimeout(window.__capabilitiesSaveTimers[mid]);
    }
    window.__capabilitiesSaveTimers[mid] = setTimeout(() => {
        window.__capabilitiesSaveTimers[mid] = null;
        saveModelCapabilitiesNow(mid, caps, modal);
    }, 350);
}

function ensureModelCapabilitiesWired(modal) {
    const els = getModelCapabilitiesEls(modal);
    if (!els.group || !els.thinking || !els.tools || !els.rerank || !els.embedding) return;
    if (els.group.getAttribute('data-wired') === '1') return;
    els.group.setAttribute('data-wired', '1');

    const onChange = (key) => () => {
        if (window.__capabilitiesApplying) return;
        enforceModelCapabilitiesRules(modal, key);
        const modelId = getFieldString(modal, ['modelId']);
        scheduleSaveModelCapabilities(modelId, modal);
    };

    els.thinking.addEventListener('change', onChange('thinking'));
    els.tools.addEventListener('change', onChange('tools'));
    els.rerank.addEventListener('change', onChange('rerank'));
    els.embedding.addEventListener('change', onChange('embedding'));
    if (els.vision) els.vision.addEventListener('change', onChange('vision'));
    if (els.audio) els.audio.addEventListener('change', onChange('audio'));
}

function loadModelCapabilities(modelId, modal) {
    const mid = modelId === null || modelId === undefined ? '' : String(modelId).trim();
    const els = getModelCapabilitiesEls(modal);
    if (!mid || !els.group) return;
    window.__capabilitiesApplying = true;
    var url = '/api/models/capabilities/get?modelId=' + encodeURIComponent(mid);
    if (modal && modal.__nodeId && modal.__nodeId !== 'local') {
        url += '&nodeId=' + encodeURIComponent(modal.__nodeId);
    }
    fetch(url)
        .then(r => r.json())
        .then(res => {
            const data = res && res.data ? res.data : null;
            applyModelCapabilitiesToUi(modal, data || {});
        })
        .catch(() => {
            applyModelCapabilitiesToUi(modal, {});
        })
        .finally(() => {
            window.__capabilitiesApplying = false;
        });
}

function ensureAutoLoadWired(modal) {
    const toggle = findById(modal, 'autoLoadToggle');
    if (!toggle) return;
    if (toggle.getAttribute('data-wired') === '1') return;
    toggle.setAttribute('data-wired', '1');

    toggle.addEventListener('change', () => {
        const modelId = getFieldString(modal, ['modelId']);
        if (!modelId) return;
        const mode = toggle.checked ? 'allow' : 'deny';
        const nodeId = modal.__nodeId || '';
        const body = { modelId: modelId, mode: mode };
        if (nodeId && nodeId !== 'local') body.nodeId = nodeId;
        fetch('/api/auto-load/policy', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).then(r => r.json()).then(d => {
            if (!d.success) {
                toggle.checked = !toggle.checked;
                showToast(t('toast.error', '错误'), d.error || t('modal.model_action.auto_load.failed', '设置自动加载策略失败'), 'error');
            }
        }).catch(() => {
            toggle.checked = !toggle.checked;
            showToast(t('toast.error', '错误'), t('modal.model_action.auto_load.failed', '设置自动加载策略失败'), 'error');
        });
    });
}

function loadAutoLoadPolicy(modelId, modal, nodeId) {
    const mid = modelId === null || modelId === undefined ? '' : String(modelId).trim();
    if (!mid) return;
    const nid = (nodeId && nodeId !== 'local') ? nodeId : '';
    const url = '/api/auto-load/policy' + (mid ? '?modelId=' + encodeURIComponent(mid) : '') + (nid ? '&nodeId=' + encodeURIComponent(nid) : '');
    fetch(url)
        .then(r => r.json())
        .then(res => {
            const data = res && res.data ? res.data : null;
            const policies = data && data.policies ? data.policies : {};
            const policy = policies[mid] || 'deny';
            const toggle = findById(modal, 'autoLoadToggle');
            if (toggle) {
                toggle.checked = policy === 'allow';
            }
        })
        .catch(() => {
            const toggle = findById(modal, 'autoLoadToggle');
            if (toggle) {
                toggle.checked = false;
            }
        });
}

function ensureAutoUnloadWired(modal) {
    const toggle = findById(modal, 'autoUnloadToggle');
    if (!toggle) return;
    if (toggle.getAttribute('data-wired') === '1') return;
    toggle.setAttribute('data-wired', '1');

    const timeoutRow = findById(modal, 'autoUnloadTimeoutRow');
    const timeoutInput = findById(modal, 'autoUnloadTimeoutInput');

    toggle.addEventListener('change', () => {
        if (timeoutRow) {
            timeoutRow.style.display = toggle.checked ? 'flex' : 'none';
        }
        const modelId = getFieldString(modal, ['modelId']);
        if (!modelId) return;
        const nodeId = modal.__nodeId || '';
        if (toggle.checked) {
            const minutes = timeoutInput ? parseInt(timeoutInput.value, 10) : 0;
            if (!(minutes > 0)) {
                toggle.checked = false;
                if (timeoutRow) timeoutRow.style.display = 'none';
                showToast(t('toast.error', '错误'), t('modal.model_action.auto_unload.timeout_required', '请先填写大于 0 的空闲超时时间'), 'error');
                if (timeoutInput) timeoutInput.focus();
                return;
            }
        }
        const mode = toggle.checked ? 'allow' : 'deny';
        const body = { modelId: modelId, autoUnload: mode };
        if (nodeId && nodeId !== 'local') body.nodeId = nodeId;
        if (toggle.checked && timeoutInput) {
            const minutes = parseInt(timeoutInput.value, 10);
            if (minutes > 0) {
                body.autoUnloadTimeoutMs = minutes * 60 * 1000;
            }
        }
        fetch('/api/auto-load/policy', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).then(r => r.json()).then(d => {
            if (!d.success) {
                toggle.checked = !toggle.checked;
                if (timeoutRow) timeoutRow.style.display = toggle.checked ? 'flex' : 'none';
                showToast(t('toast.error', '错误'), d.error || t('modal.model_action.auto_unload.failed', '设置自动卸载策略失败'), 'error');
            }
        }).catch(() => {
            toggle.checked = !toggle.checked;
            if (timeoutRow) timeoutRow.style.display = toggle.checked ? 'flex' : 'none';
            showToast(t('toast.error', '错误'), t('modal.model_action.auto_unload.failed', '设置自动卸载策略失败'), 'error');
        });
    });

    if (timeoutInput) {
        let debounceTimer = null;
        timeoutInput.addEventListener('input', () => {
            if (debounceTimer) clearTimeout(debounceTimer);
            debounceTimer = setTimeout(() => {
                if (!toggle.checked) return;
                const modelId = getFieldString(modal, ['modelId']);
                if (!modelId) return;
                const minutes = parseInt(timeoutInput.value, 10);
                if (minutes <= 0) return;
                const nodeId = modal.__nodeId || '';
                const body = { modelId: modelId, autoUnloadTimeoutMs: minutes * 60 * 1000 };
                if (nodeId && nodeId !== 'local') body.nodeId = nodeId;
                fetch('/api/auto-load/policy', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                }).then(r => r.json()).then(d => {
                    if (!d.success) {
                        showToast(t('toast.error', '错误'), d.error || t('modal.model_action.auto_unload.failed', '设置自动卸载策略失败'), 'error');
                    }
                }).catch(() => {
                    showToast(t('toast.error', '错误'), t('modal.model_action.auto_unload.failed', '设置自动卸载策略失败'), 'error');
                });
            }, 500);
        });
    }
}

function loadAutoUnloadPolicy(modelId, modal, nodeId) {
    const mid = modelId === null || modelId === undefined ? '' : String(modelId).trim();
    if (!mid) return;
    const nid = (nodeId && nodeId !== 'local') ? nodeId : '';
    const url = '/api/auto-load/policy' + (mid ? '?modelId=' + encodeURIComponent(mid) : '') + (nid ? '&nodeId=' + encodeURIComponent(nid) : '');
    fetch(url)
        .then(r => r.json())
        .then(res => {
            const data = res && res.data ? res.data : null;
            const unloadPolicies = data && data.autoUnload ? data.autoUnload : {};
            const unloadPolicy = unloadPolicies[mid] || 'deny';
            const toggle = findById(modal, 'autoUnloadToggle');
            const timeoutRow = findById(modal, 'autoUnloadTimeoutRow');
            const timeoutInput = findById(modal, 'autoUnloadTimeoutInput');
            if (toggle) {
                toggle.checked = unloadPolicy === 'allow';
            }
            if (timeoutRow) {
                timeoutRow.style.display = (toggle && toggle.checked) ? 'flex' : 'none';
            }
            if (timeoutInput && data && data.models && data.models[mid]) {
                const modelInfo = data.models[mid];
                if (modelInfo.autoUnloadTimeoutMs) {
                    const minutes = Math.round(modelInfo.autoUnloadTimeoutMs / 60000);
                    if (minutes > 0) {
                        timeoutInput.value = minutes;
                    }
                }
            }
        })
        .catch(() => {
            const toggle = findById(modal, 'autoUnloadToggle');
            const timeoutRow = findById(modal, 'autoUnloadTimeoutRow');
            if (toggle) {
                toggle.checked = false;
            }
            if (timeoutRow) {
                timeoutRow.style.display = 'none';
            }
        });
}

function getCurrentModelById(modelId, nodeId) {
    const mid = modelId === null || modelId === undefined ? '' : String(modelId).trim();
    if (!mid) return null;
    const list = Array.isArray(currentModelsData) ? currentModelsData : [];
    for (let i = 0; i < list.length; i++) {
        const model = list[i];
        if (model && String(model.id) === mid) {
            if (nodeId) {
                if ((model.nodeId || '') === nodeId) return model;
            } else {
                if (!model.nodeId || model.nodeId === 'local') return model;
            }
        }
    }
    return null;
}

function resolveModelActionSubmitIntent(mode, modelId, nodeId) {
    if (mode === 'benchmark') return 'benchmark';
    if (mode === 'config') return 'config';
    const model = getCurrentModelById(modelId, nodeId);
    if (model && !!model.isLoaded) return 'stop';
    return 'load';
}

function applyModelActionSubmitButtonState(modal, mode) {
    const submitBtn = findById(modal, 'modelActionSubmitBtn')
        || findInModal(modal, 'button[onclick*="submitModelAction"]')
        || findInModal(modal, '.modal-footer .btn-primary');
    if (!submitBtn) return;
    const modelId = getFieldString(modal, ['modelId']);
    const nodeId = modal && modal.__nodeId ? modal.__nodeId : '';
    const intent = resolveModelActionSubmitIntent(mode, modelId, nodeId);
    window.__modelActionSubmitIntent = intent;
    if (intent === 'benchmark') {
        submitBtn.classList.remove('btn-danger');
        submitBtn.classList.add('btn-primary');
        submitBtn.textContent = t('modal.model_action.submit.benchmark', '开始测试');
        return;
    }
    if (intent === 'config') {
        submitBtn.classList.remove('btn-danger');
        submitBtn.classList.add('btn-primary');
        submitBtn.textContent = t('common.save', '保存');
        return;
    }
    if (intent === 'stop') {
        submitBtn.classList.remove('btn-primary');
        submitBtn.classList.add('btn-danger');
        submitBtn.textContent = t('modal.model_action.submit.stop', '停止模型');
        return;
    }
    submitBtn.classList.remove('btn-danger');
    submitBtn.classList.add('btn-primary');
    submitBtn.textContent = t('modal.model_action.submit.load', '加载模型');
}

function toggleCmdModeHelp(event) {
    if (event) {
        event.preventDefault();
        event.stopPropagation();
    }
    const panel = document.getElementById('cmdModeHelpPanel');
    if (!panel) return;
    panel.style.display = panel.style.display === 'none' ? 'block' : 'none';
}

function switchParamMode(mode) {
    if (mode !== 'form' && mode !== 'cmd') return;
    const modal = getLoadModelModal();
    if (!modal) return;
    if (window.__modelActionMode === 'benchmark') return;

    const formPanel = findById(modal, 'dynamicParamsContainer');
    const cmdPanel = findById(modal, 'commandLineContainer');
    const toggleBtns = modal.querySelectorAll('.param-mode-btn');

    toggleBtns.forEach(function (btn) {
        btn.classList.toggle('active', btn.dataset.mode === mode);
    });

    if (formPanel) formPanel.classList.toggle('active', mode === 'form');
    if (cmdPanel) cmdPanel.classList.toggle('active', mode === 'cmd');

    if (mode === 'cmd') {
        const textarea = findById(modal, 'commandLineInput');
        // 优先恢复用户之前编辑的 cmdLine 草稿；没有草稿再从表单生成
        const draft = window.__cmdLineDraft !== undefined ? String(window.__cmdLineDraft) : '';
        if (textarea) textarea.value = draft || buildLoadModelPayload(modal).cmd || '';
    } else if (mode === 'form') {
        const textarea = findById(modal, 'commandLineInput');
        if (textarea) {
            // 暂存当前命令行内容，避免切回命令行时丢失
            window.__cmdLineDraft = String(textarea.value || '');
            if (typeof applyCmdToDynamicFields === 'function') {
                applyCmdToDynamicFields(modal, textarea.value || '');
            }
        }
    }

    window.__paramMode = mode;
}

// 移动端加载弹窗：「简要配置 / 详细配置」两个面板互斥切换（仅窄屏下 TAB 可见）
function switchLoadModelMobileTab(tab) {
    if (tab !== 'basic' && tab !== 'detail') return;
    const modal = getLoadModelModal();
    if (!modal) return;
    const layout = modal.querySelector('.load-model-layout');
    if (layout) layout.dataset.mobileTab = tab;
    modal.querySelectorAll('.load-model-mobile-tab').forEach(function (btn) {
        btn.classList.toggle('active', btn.dataset.lmtab === tab);
    });
}

function setModelActionMode(mode) {
    const resolved = mode === 'benchmark' ? 'benchmark' : 'load';
    window.__modelActionMode = resolved;
    const modal = getLoadModelModal();
    const titleText = findById(modal, 'modelActionModalTitleText') || findInModal(modal, '.modal-title span');
    const icon = findById(modal, 'modelActionModalIcon') || findInModal(modal, '.modal-title i');
    const saveBtn = findById(modal, 'modelActionSaveBtn');
    const dynamicParams = findById(modal, 'dynamicParamsContainer');
    const benchmarkParams = findById(modal, 'benchmarkParamsContainer');
    const mainGpuGroup = findById(modal, 'mainGpuGroup');
    const estimateBtn = findById(modal, 'estimateVramBtn');
    const resetBtn = findById(modal, 'modelActionResetBtn');
    const toggleBar = findById(modal, 'paramModeToggleBar');

    if (toggleBar) toggleBar.classList.toggle('benchmark', resolved === 'benchmark');

    if (dynamicParams) dynamicParams.style.display = resolved === 'benchmark' ? 'none' : '';
    if (benchmarkParams) benchmarkParams.style.display = resolved === 'benchmark' ? '' : 'none';
    if (mainGpuGroup) mainGpuGroup.style.display = '';
    if (estimateBtn) estimateBtn.style.display = resolved === 'benchmark' ? 'none' : '';
    if (resetBtn) resetBtn.style.display = resolved === 'benchmark' ? '' : 'none';
    if (saveBtn) saveBtn.style.display = resolved === 'benchmark' ? 'none' : '';

    if (resolved !== 'benchmark' && window.__paramMode !== 'form') {
        switchParamMode('form');
    }

    if (resolved === 'benchmark') {
        const hasBenchmarkFields = !!findInModal(modal, '#benchmarkParamsContainer input, #benchmarkParamsContainer select, #benchmarkParamsContainer textarea');
        if (!hasBenchmarkFields && typeof ensureBenchmarkParamsReady === 'function') {
            try { ensureBenchmarkParamsReady(); } catch (e) {}
        }
    }

    if (resolved === 'benchmark') {
        if (titleText) titleText.textContent = t('modal.model_action.title.benchmark', '模型性能测试');
        if (icon) icon.className = 'fas fa-tachometer-alt';
    } else {
        if (titleText) titleText.textContent = t('modal.model_action.title.load', '加载模型');
        if (icon) icon.className = 'fas fa-upload';
    }
    applyModelActionSubmitButtonState(modal, resolved);
}

function loadModel(modelId, modelName, param1, param2, sourceModelId) {
    let mode = 'load';
    let nodeId = '';
    const modes = ['load', 'benchmark', 'reconfigure'];
    for (const p of [param1, param2]) {
        if (typeof p === 'string' && p && modes.includes(p)) {
            mode = p;
        } else if (typeof p === 'string' && p && !modes.includes(p)) {
            nodeId = p;
        }
    }
    const modal = getLoadModelModal();
    if (modal) modal.classList.add('show');

    setModelActionMode(mode);
    setFieldValue(modal, ['modelId'], modelId);
    setFieldValue(modal, ['modelName'], modelName || modelId);
    if (nodeId) modal.__nodeId = nodeId;
    else delete modal.__nodeId;
    if (sourceModelId) modal.__sourceModelId = sourceModelId;
    else delete modal.__sourceModelId;
    applyModelActionSubmitButtonState(modal, mode === 'benchmark' ? 'benchmark' : 'load');
    const hint = findById(modal, 'ctxSizeVramHint');
    setVramHint(hint, '');
    ensureModelCapabilitiesWired(modal);
    loadModelCapabilities(modelId, modal);
    ensureAutoLoadWired(modal);
    loadAutoLoadPolicy(modelId, modal, nodeId);
    ensureAutoUnloadWired(modal);
    loadAutoUnloadPolicy(modelId, modal, nodeId);
    window.__loadModelSelectedDevices = ['All'];
    window.__loadModelSelectionFromConfig = true;
    const deviceChecklistEl = findById(modal, 'deviceChecklist');
    if (deviceChecklistEl) deviceChecklistEl.innerHTML = `<div class="settings-empty">${t('common.loading', '加载中...')}</div>`;
    window.__availableDevices = [];
    window.__availableDeviceCount = 0;
    renderMainGpuSelect([], window.__loadModelSelectedDevices || []);

    const currentModel = nodeId
        ? (currentModelsData || []).find(m => m && m.id === modelId && m.nodeId === nodeId)
        : (currentModelsData || []).find(m => m && m.id === modelId && (!m.nodeId || m.nodeId === 'local'));
    const isVisionModel = !!(currentModel && (currentModel.isMultimodal || currentModel.mmproj));
    const enableVisionGroup = findById(modal, 'enableVisionGroup');
    if (enableVisionGroup) enableVisionGroup.style.display = isVisionModel ? '' : 'none';

    const configNodeId = nodeId && nodeId !== 'local' ? nodeId : '';
    const configUrl = configNodeId
        ? `/api/models/config/get?modelId=${encodeURIComponent(modelId)}&nodeId=${encodeURIComponent(configNodeId)}`
        : `/api/models/config/get?modelId=${encodeURIComponent(modelId)}`;
    fetch(configUrl)
        .then(r => r.json()).then(data => {
            const bundle = extractLaunchConfigBundleFromGetResponse(data, modelId);
            renderModelConfigSelect(modal, bundle);
            const configSelect = findById(modal, 'modelConfigSelect');
            if (configSelect && !configSelect.__modelConfigChangeBound) {
                configSelect.__modelConfigChangeBound = true;
                configSelect.addEventListener('change', onModelConfigSelectionChange);
            }
            const selected = bundle.selectedConfig;
            const config = bundle.configs[selected] || {};
            applyLaunchConfigToModal(modal, config);

            const effectiveNodeId = modal.__nodeId || '';
            const llamaListUrl = (effectiveNodeId && effectiveNodeId !== 'local')
                ? `/api/llamacpp/list?nodeId=${encodeURIComponent(effectiveNodeId)}`
                : '/api/llamacpp/list';
            fetch(llamaListUrl).then(r => r.json()).then(listData => {
                const select = findById(modal, 'llamaBinPathSelect') || findFieldByName(modal, 'llamaBinPathSelect');
                const items = (listData && listData.success && listData.data) ? (listData.data.items || []) : [];
                if (select) {
                    const options = (Array.isArray(items) ? items : [])
                        .map(i => {
                            const p = i && i.path !== undefined && i.path !== null ? String(i.path).trim() : '';
                            if (!p) return '';
                            const name = i && i.name !== undefined && i.name !== null ? String(i.name).trim() : '';
                            const desc = i && i.description !== undefined && i.description !== null ? String(i.description).trim() : '';
                            const text = name ? `${name} (${p})` : p;
                            const title = [name, p, desc].filter(Boolean).join('\n');
                            return `<option value="${escapeHtml(p)}" title="${escapeHtml(title)}">${escapeHtml(text)}</option>`;
                        })
                        .filter(Boolean)
                        .join('');
                    select.innerHTML = options || `<option value="">${t('modal.model_action.llamacpp.not_configured', '未配置 Llama.cpp 路径')}</option>`;
                }

                const savedBinPath = config.llamaBinPathSelect || config.llamaBinPath;
                if (savedBinPath) {
                    if (select) select.value = savedBinPath;
                }

                if (select) select.onchange = function() { loadDeviceList(); };
                loadDeviceList();
            }).finally(() => {
                const modal2 = getLoadModelModal();
                if (modal2) modal2.classList.add('show');
            });
        });
}
// 在这动态构建要提交的参数
function buildLoadModelPayload(modal) {
    const modelId = getFieldString(modal, ['modelId']);
    const modelName = getFieldString(modal, ['modelName']);
    const nodeId = modal && modal.__nodeId ? modal.__nodeId : '';
    const llamaBinPathSelect = getFieldString(modal, ['llamaBinPathSelect']);
    const enableVisionEl = findField(modal, 'enableVision');
    const enableVision = enableVisionEl && 'checked' in enableVisionEl ? !!enableVisionEl.checked : true;

    const selectedDevices = getSelectedDevicesFromChecklist();
    const availableCount = window.__availableDeviceCount;
    const isAllSelected = Number.isFinite(availableCount) && availableCount > 0 && selectedDevices.length === availableCount;

    let cmd = '';
    let extraParams = '';
    let envVars = '';

    if (window.__paramMode === 'cmd') {
        const textarea = findById(modal, 'commandLineInput');
        cmd = textarea ? String(textarea.value || '').trim() : '';
        extraParams = '';
    } else {
        const cmdParts = [];

        const cfgList = getParamConfigListSafe().slice().sort((a, b) => (a && a.sort ? a.sort : 0) - (b && b.sort ? b.sort : 0));
        for (let i = 0; i < cfgList.length; i++) {
            const p = cfgList[i];
            if (!p) continue;
            const fullName = p.fullName === null || p.fullName === undefined ? '' : String(p.fullName);
            const abbr = p.abbreviation === null || p.abbreviation === undefined ? '' : String(p.abbreviation);
            const type = p.type === null || p.type === undefined ? 'STRING' : String(p.type);
            const typeUpper = String(type).toUpperCase();
            const uiType = getParamUiType(p);
            const fullNameTrimmed = fullName.trim();
            const abbrTrimmed = abbr.trim();

            if (uiType === 'ordered-multiselect') {
                if (!fullNameTrimmed) continue;
                const fieldName = fieldNameFromParamConfig(p);
                if (!fieldName) continue;
                if (!isLoadModelParamEnabled(modal, fieldName)) continue;
                const el = findFieldByName(modal, fieldName) || findById(modal, 'param_' + fieldName);
                if (!el || !('value' in el)) continue;
                const selected = String(el.value || '').trim();
                if (!selected) continue;
                cmdParts.push(fullNameTrimmed, quoteArgIfNeeded(selected));
                continue;
            }

            if (typeUpper === 'STRING' && !fullNameTrimmed && !abbrTrimmed) {
                const values = getParamOptionValues(p);
                if (!values.length) continue;
                const defaultValue = p.defaultValue === null || p.defaultValue === undefined ? (values.length ? String(values[0]) : '') : String(p.defaultValue);
                const fieldName = fieldNameFromParamConfig(p);
                if (!fieldName) continue;
                if (!isLoadModelParamEnabled(modal, fieldName)) continue;
                const el = findFieldByName(modal, fieldName) || findById(modal, 'param_' + fieldName);
                if (!el || !('value' in el)) continue;
                const selected = String(el.value || '').trim();
                if (!selected) continue;
                if (values.some(v => String(v).trim() === selected)) {
                    cmdParts.push(quoteArgIfNeeded(selected));
                }
                continue;
            }

            if (!fullNameTrimmed && !abbrTrimmed) continue;
            const effectiveName = fullNameTrimmed || abbrTrimmed;
            const fieldName = fieldNameFromFullName(effectiveName);
            if (!fieldName) continue;
            if (!isLoadModelParamEnabled(modal, fieldName)) continue;

            const el = findFieldByName(modal, fieldName);
            if (!el || !('value' in el)) continue;
            const rawValue = String(el.value || '');

            if (typeUpper === 'LOGIC') {
                if (isTruthyLogicValue(rawValue)) {
                    cmdParts.push(effectiveName);
                }
                continue;
            }

            const trimmed = rawValue.trim();
            if (!trimmed) continue;
            cmdParts.push(effectiveName, quoteArgIfNeeded(trimmed));
        }

        cmd = cmdParts.join(' ').trim();
        extraParams = getFieldString(modal, ['extraParams']).trim();
        envVars = getFieldString(modal, ['envVars']).trim();
    }

    const payload = {
        modelId,
        modelName,
        llamaBinPathSelect,
        enableVision,
        device: selectedDevices,
        mg: getSelectedMainGpu(),
        mode: window.__paramMode === 'cmd' ? 'cmd' : 'form',
        cmd: cmd,
        extraParams,
        envVars
    };

    // 克隆体加载需要带上 sourceModelId；优先使用 modal 上保存的，兜底查 currentModelsData
    const modalSourceModelId = modal && modal.__sourceModelId ? modal.__sourceModelId : '';
    if (modalSourceModelId) {
        payload.sourceModelId = modalSourceModelId;
    } else {
        const effectiveNodeId = nodeId || 'local';
        const currentModel = (window.currentModelsData || []).find(m => m && m.id === modelId && (m.nodeId || 'local') === effectiveNodeId);
        if (currentModel && currentModel.isClone && currentModel.sourceModelId) {
            payload.sourceModelId = currentModel.sourceModelId;
        }
    }

    return payload;
}

function submitModelAction() {
    const mode = window.__modelActionMode === 'config' ? 'config' : (window.__modelActionMode === 'benchmark' ? 'benchmark' : 'load');
    const modal = getLoadModelModal();
    if (mode === 'benchmark') {
        if (typeof submitModelBenchmark === 'function') {
            submitModelBenchmark();
            return;
        }
        showToast(t('toast.error', '错误'), t('modal.model_action.benchmark.missing_handler', '未找到模型性能测试函数'), 'error');
        return;
    }
    const submitIntent = resolveModelActionSubmitIntent(mode, getFieldString(modal, ['modelId']), modal.__nodeId || '');
    if (mode === 'load' && submitIntent === 'stop') {
        const modelIdForStop = getFieldString(modal, ['modelId']);
        const submitBtn = findById(modal, 'modelActionSubmitBtn')
            || findInModal(modal, 'button[onclick*="submitModelAction"]')
            || findInModal(modal, '.modal-footer .btn-primary');
        if (!modelIdForStop) {
            showToast(t('toast.error', '错误'), t('modal.model_action.missing_model_id', '缺少必需的modelId参数'), 'error');
            if (submitBtn) {
                submitBtn.disabled = false;
                applyModelActionSubmitButtonState(modal, mode);
            }
            return;
        }
        if (submitBtn) {
            submitBtn.disabled = true;
            submitBtn.innerHTML = `<i class="fas fa-spinner fa-spin"></i> ${t('common.processing', '处理中...')}`;
        }
        fetch('/api/models/stop', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ modelId: modelIdForStop })
        }).then(r => r.json()).then(res => {
            if (!res || !res.success) {
                showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('common.operation_failed', '操作失败'), 'error');
                if (submitBtn) {
                    submitBtn.disabled = false;
                    applyModelActionSubmitButtonState(modal, mode);
                }
                return;
            }
            if (typeof removeModelLoadingState === 'function') removeModelLoadingState(modelIdForStop);
            if (submitBtn) {
                submitBtn.disabled = false;
                applyModelActionSubmitButtonState(modal, mode);
            }
        }).catch(() => {
            showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
            if (submitBtn) {
                submitBtn.disabled = false;
                applyModelActionSubmitButtonState(modal, mode);
            }
        });
        return;
    }

    let payload = null;
    let configPayload = null;
    let modelIdForUi = getFieldString(modal, ['modelId']);
    if (mode === 'config') {
        const base = buildLoadModelPayload(modal);
        modelIdForUi = base && base.modelId ? base.modelId : modelIdForUi;
        const configName = getSelectedModelConfigName(modal);
        payload = buildConfigSetPayload(modelIdForUi, configName, buildPersistableLaunchConfig(modal));
    } else {
        payload = buildLoadModelPayload(modal);
        modelIdForUi = payload && payload.modelId ? payload.modelId : modelIdForUi;
        const configName = getSelectedModelConfigName(modal);
        configPayload = buildConfigSetPayload(modelIdForUi, configName, buildPersistableLaunchConfig(modal));
    }

    const submitBtn = findById(modal, 'modelActionSubmitBtn')
        || findInModal(modal, 'button[onclick*="submitModelAction"]')
        || findInModal(modal, '.modal-footer .btn-primary');
    if (!modelIdForUi) {
        showToast(t('toast.error', '错误'), t('modal.model_action.missing_model_id', '缺少必需的modelId参数'), 'error');
        if (submitBtn) {
            submitBtn.disabled = false;
            applyModelActionSubmitButtonState(modal, mode);
        }
        return;
    }
    if (mode !== 'config') {
        const llamaBinPathSelect = payload && payload.llamaBinPathSelect ? String(payload.llamaBinPathSelect).trim() : '';
        const cmd = payload && payload.cmd ? String(payload.cmd).trim() : '';
        const extraParams = payload && payload.extraParams ? String(payload.extraParams).trim() : '';
        if (!llamaBinPathSelect) {
            showToast(t('toast.error', '错误'), t('modal.model_action.missing_llama_bin_path', '未提供llamaBinPath'), 'error');
            if (submitBtn) {
                submitBtn.disabled = false;
                applyModelActionSubmitButtonState(modal, mode);
            }
            return;
        }
        if (!cmd && !extraParams) {
            showToast(t('toast.error', '错误'), t('modal.model_action.missing_launch_params', '缺少必需的启动参数'), 'error');
            if (submitBtn) {
                submitBtn.disabled = false;
                applyModelActionSubmitButtonState(modal, mode);
            }
            return;
        }
        payload.llamaBinPathSelect = llamaBinPathSelect;
        payload.cmd = cmd;
        payload.extraParams = extraParams;
    }
    if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.innerHTML = mode === 'config'
            ? `<i class="fas fa-spinner fa-spin"></i> ${t('common.saving', '保存中...')}`
            : `<i class="fas fa-spinner fa-spin"></i> ${t('common.processing', '处理中...')}`;
    }

    const saveBody = mode === 'config' ? payload : configPayload;
    const cfgNodeId = modal && modal.__nodeId ? modal.__nodeId : '';
    if (cfgNodeId && cfgNodeId !== 'local') saveBody.nodeId = cfgNodeId;
    const saveConfigRequest = () => fetch('/api/models/config/set', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(saveBody)
    }).then(r => r.json());

    const doLoadRequest = () => {
        const nodeId = modal && modal.__nodeId ? modal.__nodeId : '';
        if (nodeId && nodeId !== 'local') payload.nodeId = nodeId;
        return fetch('/api/models/load', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        }).then(r => r.json());
    };

    const isRemoteModel = !!(modal && modal.__nodeId && modal.__nodeId !== 'local');

    // 关键注释：启动模型前先落盘当前选中配置，保证“配置切换”所选项始终最新
    const requestChain = mode === 'config'
        ? saveConfigRequest()
        : isRemoteModel
            ? doLoadRequest()
            : saveConfigRequest().then(cfgRes => {
                if (!cfgRes || !cfgRes.success) {
                    return Promise.reject(new Error((cfgRes && cfgRes.error) ? cfgRes.error : t('common.save_failed', '保存失败')));
                }
                return doLoadRequest();
            });

    requestChain.then(res => {
        if (res.success) {
            if (mode === 'config') {
                showToast(t('toast.success', '成功'), t('modal.model_action.config.saved', '启动参数已保存'), 'success');
                if (submitBtn) {
                    submitBtn.disabled = false;
                    applyModelActionSubmitButtonState(modal, mode);
                }
            } else {
                if (res.data && res.data.async) {
                    window.pendingModelLoad = { modelId: modelIdForUi };
					closeModal('loadModelModal');
                } else {
                    if (res.data && res.data.processOnly) {
                        showToast(t('toast.success', '成功'), t('modal.model_action.load.process_only', '参数已接收（未加载模型）'), 'success');
                    } else {
                        showToast(t('toast.success', '成功'), t('modal.model_action.load.success', '模型加载成功'), 'success');
                    }
                    closeModal('loadModelModal');
                }
            }
        } else {
            showToast(t('toast.error', '错误'), res.error || (mode === 'config' ? t('common.save_failed', '保存失败') : t('common.load_failed', '加载失败')), 'error');
            if (submitBtn) {
                submitBtn.disabled = false;
                applyModelActionSubmitButtonState(modal, mode);
            }
        }
    }).catch((err) => {
        const msg = err && err.message ? err.message : t('common.network_request_failed', '网络请求失败');
        showToast(t('toast.error', '错误'), msg, 'error');
        if (submitBtn) {
            submitBtn.disabled = false;
            applyModelActionSubmitButtonState(modal, mode);
        }
    });
}

function submitLoadModel() { submitModelAction(); }

function saveModelConfigAction() {
    const modal = getLoadModelModal();
    const base = buildLoadModelPayload(modal);
    const modelIdForUi = base && base.modelId ? String(base.modelId).trim() : '';
    if (!modelIdForUi) {
        showToast(t('toast.error', '错误'), t('modal.model_action.missing_model_id', '缺少必需的modelId参数'), 'error');
        return;
    }
    const configName = getSelectedModelConfigName(modal);
    const cfgNodeId = modal && modal.__nodeId ? modal.__nodeId : '';
    const payload = buildConfigSetPayload(modelIdForUi, configName, buildPersistableLaunchConfig(modal), cfgNodeId);

    const saveBtn = findById(modal, 'modelActionSaveBtn');
    const submitBtn = findById(modal, 'modelActionSubmitBtn');
    if (saveBtn) {
        saveBtn.disabled = true;
        saveBtn.innerHTML = `<i class="fas fa-spinner fa-spin"></i> ${t('common.saving', '保存中...')}`;
    }
    if (submitBtn) submitBtn.disabled = true;

    fetch('/api/models/config/set', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    }).then(r => r.json()).then(res => {
        if (res && res.success) {
            showToast(t('toast.success', '成功'), t('modal.model_action.config.saved', '启动参数已保存'), 'success');
            if (saveBtn) {
                saveBtn.disabled = false;
                saveBtn.textContent = t('common.save', '保存');
            }
            if (submitBtn) submitBtn.disabled = false;
            return;
        }
        showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('common.save_failed', '保存失败'), 'error');
        if (saveBtn) {
            saveBtn.disabled = false;
            saveBtn.textContent = t('common.save', '保存');
        }
        if (submitBtn) submitBtn.disabled = false;
    }).catch(() => {
        showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        if (saveBtn) {
            saveBtn.disabled = false;
            saveBtn.textContent = t('common.save', '保存');
        }
        if (submitBtn) submitBtn.disabled = false;
    });
}

// 估算显存的功能
function setVramHint(hint, text) {
    if (!hint) return;
    const icon = hint.querySelector('i');
    [...hint.childNodes].forEach(n => { if (n.nodeType === Node.TEXT_NODE) hint.removeChild(n); });
    if (text) {
        hint.appendChild(document.createTextNode(text));
        if (icon) icon.style.display = '';
    } else if (icon) {
        icon.style.display = 'none';
    }
}

function estimateVramAction() {
    const modal = getLoadModelModal();
    const payload = buildLoadModelPayload(modal);
    const modelId = payload && payload.modelId ? String(payload.modelId).trim() : '';
    if (!modelId) {
        showToast(t('toast.error', '错误'), t('modal.model_action.vram.select_model_first', '请先选择模型'), 'error');
        return;
    }
    const hint = findById(modal, 'ctxSizeVramHint');
    setVramHint(hint, t('common.calculating', '正在计算……'));

    const llamaBinPathSelect = payload && payload.llamaBinPathSelect ? String(payload.llamaBinPathSelect).trim() : '';
    const cmd = payload && payload.cmd ? String(payload.cmd).trim() : '';
    const extraParams = payload && payload.extraParams ? String(payload.extraParams).trim() : '';
    if (!llamaBinPathSelect) {
        showToast(t('toast.error', '错误'), t('modal.model_action.missing_llama_bin_path', '未提供llamaBinPath'), 'error');
        return;
    }
    if (!cmd && !extraParams) {
        showToast(t('toast.error', '错误'), t('modal.model_action.missing_launch_params', '缺少必需的启动参数'), 'error');
        return;
    }
    payload.modelId = modelId;
    payload.llamaBinPathSelect = llamaBinPathSelect;
    payload.cmd = cmd;
    payload.extraParams = extraParams;
    const vramNodeId = modal && modal.__nodeId && modal.__nodeId !== 'local' ? modal.__nodeId : '';
    if (vramNodeId) payload.nodeId = vramNodeId;
    fetch('/api/models/vram/estimate', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
    }).then(r => r.json()).then(res => {
        if (res && res.success) {
            const vram = res.data && res.data.vram !== undefined && res.data.vram !== null ? String(res.data.vram).trim() : '';
            if (vram) {
                const text = `${t('modal.model_action.vram.estimate', '预计显存')}：${vram} MiB`;
                setVramHint(hint, text);
            } else if(res.data.message) {
				showToast(t('toast.error', '错误'), t('modal.model_action.vram.estimate_error', '估算错误'), 'error');
				setVramHint(hint, res.data.message);
            } else{
				showToast(t('toast.error', '错误'), t('modal.model_action.vram.invalid_response', '返回数据格式不正确'), 'error');
			}
        } else {
            showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('modal.model_action.vram.estimate_failed', '估算失败'), 'error');
        }
    }).catch(() => {
        showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
    });
}

function estimateVram() { estimateVramAction(); }

function fitParamsAction() {
    const modal = getLoadModelModal();
    const payload = buildLoadModelPayload(modal);
    const modelId = payload && payload.modelId ? String(payload.modelId).trim() : '';
    if (!modelId) {
        showToast(t('toast.error', '错误'), t('modal.model_action.fit_params.select_model_first', '请先选择模型'), 'error');
        return;
    }
    const llamaBinPathSelect = payload && payload.llamaBinPathSelect ? String(payload.llamaBinPathSelect).trim() : '';
    const cmd = payload && payload.cmd ? String(payload.cmd).trim() : '';
    const extraParams = payload && payload.extraParams ? String(payload.extraParams).trim() : '';
    if (!llamaBinPathSelect) {
        showToast(t('toast.error', '错误'), t('modal.model_action.missing_llama_bin_path', '未提供llamaBinPath'), 'error');
        return;
    }
    if (!cmd && !extraParams) {
        showToast(t('toast.error', '错误'), t('modal.model_action.missing_launch_params', '缺少必需的启动参数'), 'error');
        return;
    }
    payload.modelId = modelId;
    payload.llamaBinPathSelect = llamaBinPathSelect;
    payload.cmd = cmd;
    payload.extraParams = extraParams;
    const nodeId = modal && modal.__nodeId && modal.__nodeId !== 'local' ? modal.__nodeId : '';
    if (nodeId) payload.nodeId = nodeId;
    console.log('[fitParams] 发送请求 payload:', payload);
    fetch('/api/models/fit-params', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
    }).then(r => r.json()).then(res => {
        console.log('[fitParams] 响应:', res);
        if (res && res.success) {
            const fitted = res.data && res.data.fittedParams;
            if (fitted) {
                console.log('[fitParams] 拟合结果:', fitted);
                window.__lastFittedParams = fitted;
                const content = document.getElementById('fitParamsResultContent');
                if (content) {
                    let html = '<table style="width:100%;border-collapse:collapse;">';
                    html += '<tr><th style="text-align:left;padding:6px 8px;border-bottom:1px solid var(--border-color);font-size:13px;">' + t('modal.model_action.fit_params.table.param', '参数') + '</th><th style="text-align:left;padding:6px 8px;border-bottom:1px solid var(--border-color);font-size:13px;">' + t('modal.model_action.fit_params.table.value', '值') + '</th></tr>';
                    for (const [key, value] of Object.entries(fitted)) {
                        html += '<tr><td style="padding:6px 8px;border-bottom:1px solid var(--border-color);font-family:monospace;white-space:nowrap;">' + key + '</td><td style="padding:6px 8px;border-bottom:1px solid var(--border-color);font-family:monospace;word-break:break-all;">' + value + '</td></tr>';
                    }
                    html += '</table>';
                    content.innerHTML = html;
                }
                document.getElementById('fitParamsResultModal').classList.add('show');
                // TODO: 将 fitted 参数自动填入表单
            } else {
                console.warn('[fitParams] 返回数据中未找到 fittedParams');
                showToast(t('toast.error', '错误'), t('modal.model_action.fit_params.invalid_response', '返回数据格式不正确'), 'error');
            }
        } else {
            showToast(t('toast.error', '错误'), (res && res.error) ? res.error : t('modal.model_action.fit_params.fetch_failed', '获取最佳配比失败'), 'error');
        }
    }).catch(err => {
        console.error('[fitParams] 请求失败:', err);
        showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
    });
}

function fitParamsApplyAction() {
    const modal = getLoadModelModal();
    const fitted = window.__lastFittedParams;
    if (!fitted) {
        showToast(t('toast.error', '错误'), t('modal.model_action.fit_params.no_params', '没有可应用的参数'), 'error');
        return;
    }
    const extraParamsEl = document.getElementById('extraParams');
    let extraParts = [];

    for (const [key, value] of Object.entries(fitted)) {
        if (key === '-c') continue;

        if (key === '-ngl') {
            setFieldValue(modal, ['gpu-layers', 'param_gpu-layers'], value);
            const cb = findById(modal, 'param_enable_gpu-layers');
            if (cb) cb.checked = true;
        } else if (key === '-ts') {
            setFieldValue(modal, ['tensor-split', 'param_tensor-split'], value);
            const cb = findById(modal, 'param_enable_tensor-split');
            if (cb) cb.checked = true;
        } else if (key === '-ot') {
            extraParts.push('-ot', quoteArgIfNeeded(value));
        }
    }

    if (extraParts.length > 0 && extraParamsEl) {
        const current = extraParamsEl.value.trim();
        const append = extraParts.join(' ');
        extraParamsEl.value = current ? current + ' ' + append : append;
    }

    closeModal('fitParamsResultModal');
    showToast(t('common.success', '成功'), t('modal.model_action.fit_params.applied', '已应用最佳配比参数'), 'success');
}

function viewModelConfig(modelId, nodeId) {
    const currentModel = nodeId
        ? (currentModelsData || []).find(m => m && m.id === modelId && m.nodeId === nodeId)
        : (currentModelsData || []).find(m => m && m.id === modelId && (!m.nodeId || m.nodeId === 'local'));
    const resolvedNodeId = currentModel ? (currentModel.nodeId || '') : (nodeId || '');
    loadModel(modelId, currentModel ? currentModel.name : modelId, '', resolvedNodeId);
}

function normalizeDeviceSelection(device) {
    if (Array.isArray(device)) {
        const list = device
            .map(v => (v === null || v === undefined) ? '' : String(v))
            .map(v => v.trim())
            .filter(v => v.length > 0);
        const lower = list.map(v => v.toLowerCase());
        if (lower.includes('all') || lower.includes('-1')) return ['All'];
        return lower;
    }
    if (device === null || device === undefined || device === '') return [];
    const v = String(device).trim();
    if (!v) return [];
    const lower = v.toLowerCase();
    if (lower === 'all' || lower === '-1') return ['All'];
    return [lower];
}

function normalizeMainGpu(v) {
    const n = parseInt(v, 10);
    return Number.isFinite(n) ? n : -1;
}

function getSelectedMainGpu() {
    const modal = getLoadModelModal();
    const el = findById(modal, 'mainGpuSelect');
    if (!el) return -1;
    const n = parseInt(el.value, 10);
    return Number.isFinite(n) ? n : -1;
}

function renderMainGpuSelect(devices, selectedKeys) {
    const modal = getLoadModelModal();
    const select = findById(modal, 'mainGpuSelect');
    if (!select) return;
    const desired = normalizeMainGpu(window.__loadModelMainGpu);
    let effectiveDevices = Array.isArray(devices) ? devices.slice() : [];
    const keys = Array.isArray(selectedKeys) ? selectedKeys : null;
    if (keys && keys.length > 0 && !keys.includes('All') && !keys.includes('-1')) {
        const filtered = [];
        const normalized = keys.map(v => String(v).trim().toLowerCase()).filter(v => v.length > 0 && v !== 'all' && v !== '-1');
        for (let i = 0; i < effectiveDevices.length; i++) {
            if (deviceMatchesSelection(effectiveDevices[i], normalized)) filtered.push(effectiveDevices[i]);
        }
        if (filtered.length > 0) effectiveDevices = filtered;
    }
    const safe = (Array.isArray(effectiveDevices) && desired >= 0 && desired < effectiveDevices.length) ? desired : -1;
    const options = [`<option value="-1">${escapeHtml(t('common.default', '默认'))}</option>`];
    if (Array.isArray(effectiveDevices)) {
        for (let i = 0; i < effectiveDevices.length; i++) {
            options.push(`<option value="${i}">${escapeHtml(effectiveDevices[i])}</option>`);
        }
    }
    select.innerHTML = options.join('');
    select.value = String(safe);
}

function deviceKeyFromLabel(label) {
    if (label === null || label === undefined) return '';
    const s = String(label).trim();
    if (!s) return '';
    const colonIndex = s.indexOf(':');
    if (colonIndex > 0) {
        const explicitKey = s.slice(0, colonIndex).trim();
        if (explicitKey) return explicitKey.toLowerCase();
    }
    return s.toLowerCase();
}

function deviceMatchesSelection(deviceLabel, selectedEntries) {
    const label = (deviceLabel === null || deviceLabel === undefined) ? '' : String(deviceLabel).trim();
    const labelLower = label.toLowerCase();
    const key = deviceKeyFromLabel(label);
    const entries = Array.isArray(selectedEntries) ? selectedEntries : [];
    for (let i = 0; i < entries.length; i++) {
        const raw = entries[i];
        if (raw === null || raw === undefined) continue;
        const s = String(raw).trim().toLowerCase();
        if (!s || s === 'all' || s === '-1') continue;
        if (s === key) return true;
        if (s === labelLower) return true;
    }
    return false;
}

function getSelectedDevicesFromChecklist() {
    const modal = getLoadModelModal();
    const list = findById(modal, 'deviceChecklist');
    if (!list) return [];
    const values = Array.from(list.querySelectorAll('input[type="checkbox"][data-device-key]:checked'))
        .map(el => el.getAttribute('data-device-key'))
        .map(v => {
            if (v === null || v === undefined) return '';
            const trimmed = String(v).trim();
            return trimmed.split(':')[0];
        })
        .filter(v => v.length > 0 && v !== 'All' && v !== '-1' && v.toLowerCase() !== 'none');
    values.sort((a, b) => {
        const ai = parseInt(a, 10);
        const bi = parseInt(b, 10);
        if (Number.isFinite(ai) && Number.isFinite(bi)) return ai - bi;
        return a.localeCompare(b);
    });
    return values;
}

function updateSelectedDevicesCacheFromChecklist() {
    const modal = getLoadModelModal();
    const list = findById(modal, 'deviceChecklist');
    if (!list) return;
    const hasInputs = !!list.querySelector('input[type="checkbox"][data-device-key]');
    if (!hasInputs) return;
    const selectedKeys = getSelectedDevicesFromChecklist();
    const availableCount = window.__availableDeviceCount;
    const isAllSelected = Number.isFinite(availableCount) && availableCount > 0 && selectedKeys.length === availableCount;
    window.__loadModelSelectedDevices = isAllSelected ? ['All'] : selectedKeys;
}

function syncMainGpuSelectWithChecklist() {
    const modal = getLoadModelModal();
    const mainGpuEl = findById(modal, 'mainGpuSelect');
    if (mainGpuEl && !window.__loadModelSelectionFromConfig) {
        window.__loadModelMainGpu = getSelectedMainGpu();
    }
    updateSelectedDevicesCacheFromChecklist();
    renderMainGpuSelect(window.__availableDevices || [], window.__loadModelSelectedDevices || []);
    window.__loadModelSelectionFromConfig = false;
}

function loadDeviceList() {
    const modal = getLoadModelModal();
    const list = findById(modal, 'deviceChecklist');
    const requestToken = (window.__loadDeviceListRequestToken || 0) + 1;
    window.__loadDeviceListRequestToken = requestToken;
    const allowReadFromChecklist = !window.__loadModelSelectionFromConfig;
    if (allowReadFromChecklist && list && list.querySelector('input[type="checkbox"][data-device-key]')) {
        updateSelectedDevicesCacheFromChecklist();
    }
    const mainGpuEl = findById(modal, 'mainGpuSelect');
    if (!window.__loadModelSelectionFromConfig && mainGpuEl && mainGpuEl.options && mainGpuEl.options.length > 1) {
        window.__loadModelMainGpu = getSelectedMainGpu();
    }
    const llamaSelect = findById(modal, 'llamaBinPathSelect') || findFieldByName(modal, 'llamaBinPathSelect');
    const llamaBinPath = llamaSelect ? llamaSelect.value : '';

    if (!llamaBinPath) {
        if (list) list.innerHTML = `<div class="settings-empty">${t('common.select_llamacpp_first', '请先选择 Llama.cpp 版本')}</div>`;
        renderMainGpuSelect([], window.__loadModelSelectedDevices || []);
        return;
    }

    const deviceNodeId = modal && modal.__nodeId && modal.__nodeId !== 'local' ? modal.__nodeId : '';
    let deviceUrl = `/api/model/device/list?llamaBinPath=${encodeURIComponent(llamaBinPath)}`;
    if (deviceNodeId) deviceUrl += `&nodeId=${encodeURIComponent(deviceNodeId)}`;
    fetch(deviceUrl)
        .then(response => response.json())
        .then(data => {
            if (window.__loadDeviceListRequestToken !== requestToken) {
                return;
            }
            if (!list) return;
            if (!(data && data.success && data.data && Array.isArray(data.data.devices))) {
                list.innerHTML = `<div class="settings-empty">${t('common.devices_load_failed', '获取设备列表失败')}</div>`;
                renderMainGpuSelect([], window.__loadModelSelectedDevices || []);
                return;
            }
            const devices = data.data.devices;
            window.__availableDevices = devices;
            window.__availableDeviceCount = devices.length;
            const selected = window.__loadModelSelectedDevices || [];
            const defaultAll = selected.includes('All') || selected.includes('-1') || selected.length === 0;
            const items = devices.map((device) => {
                const key = deviceKeyFromLabel(device);
                const checked = (defaultAll || deviceMatchesSelection(device, selected)) ? 'checked' : '';
                return `<label style="display:flex; align-items:flex-start; gap:8px; padding:6px 6px; border-radius:8px; cursor:pointer;">
                    <input type="checkbox" ${checked} data-device-key="${escapeHtml(key)}" style="margin-top: 2px;">
                    <span style="font-size: 0.9rem; color: var(--text-primary);">${escapeHtml(device)}</span>
                </label>`;
            });
            list.innerHTML = items.length ? items.join('') : `<div class="settings-empty">${t('common.no_devices', '未发现可用设备')}</div>`;

            if (!window.__deviceChecklistChangeBound) {
                window.__deviceChecklistChangeBound = true;
                list.addEventListener('change', (e) => {
                    const t = e && e.target ? e.target : null;
                    if (!t) return;
                    if (t.matches && t.matches('input[type="checkbox"][data-device-key]')) {
                        syncMainGpuSelectWithChecklist();
                    }
                });
            }

            syncMainGpuSelectWithChecklist();
        })
        .catch(error => {
            if (window.__loadDeviceListRequestToken !== requestToken) {
                return;
            }
            if (list) list.innerHTML = `<div class="settings-empty">${t('common.devices_load_failed', '获取设备列表失败')}：${escapeHtml(error && error.message ? error.message : '')}</div>`;
            renderMainGpuSelect([], window.__loadModelSelectedDevices || []);
        });
}

function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, function(m) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]); });
}
