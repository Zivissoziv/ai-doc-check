const API_DEFAULTS = {
    endpoint: 'https://api.deepseek.com/v1/chat/completions',
    model: 'deepseek-chat',
    auditRole: '专业文档审核专家'
};

const SETTINGS_DEFAULTS = {
    batchSize: 5,
    repeatPrompt: true,
    temperature: 0.1,
    topP: 1.0
};

class SmartDocApp {
    constructor() {
        this.template = null;
        this.document = null;
        this.excelData = null;
        this.ticketData = null;
        this.rules = [];
        this.settings = {
            auditRole: API_DEFAULTS.auditRole,
            batchSize: SETTINGS_DEFAULTS.batchSize,
            repeatPrompt: SETTINGS_DEFAULTS.repeatPrompt,
            temperature: SETTINGS_DEFAULTS.temperature,
            topP: SETTINGS_DEFAULTS.topP
        };
        this.currentEditingRule = null;
        this.auditResults = [];
        this.ruleGroups = [];
        this.currentRuleGroup = null;
        this.isAuditing = false;
        this.exactMatchMode = false;
        this._statsRequestToken = 0;
        this.ticketId = null;
        this.orderId = null;
        this.ts = null;
        this.auditMode = localStorage.getItem('smartdoc_audit_mode') || 'document';

        this.init();
    }
    
    getUrlParams() {
        const params = new URLSearchParams(window.location.search);
        return Object.fromEntries(params.entries());
    }

    _ruleGroupStorageKey() {
        return 'smartdoc_current_group';
    }

    _getSavedRuleGroup() {
        return localStorage.getItem(this._ruleGroupStorageKey()) || RulesManager.getCurrentGroup();
    }

    _setSavedRuleGroup(groupId) {
        if (groupId) {
            localStorage.setItem(this._ruleGroupStorageKey(), groupId);
            RulesManager.setCurrentGroup(groupId);
        }
    }
    
    async init() {
        const params = this.getUrlParams();
        if (params.orderId) {
            this.auditMode = 'ticket';
        } else if (params.ticketId) {
            this.auditMode = 'document';
        }

        await this.loadRuleGroups();
        await this.loadPresetConfig();
        this.loadSettings();
        this.updateApiStatus();

        if (params.orderId) {
            await this.loadFromOrder(params.orderId, params.ts);
        } else if (params.ticketId) {
            await this.loadFromTicket(params.ticketId, params.ts);
        }
        AuditMode.apply(this, this.auditMode);
    }

    _makeTs() {
        const d = new Date();
        const pad = n => String(n).padStart(2, '0');
        return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
    }
    
    _base64ToBlob(base64) {
        const binary = atob(base64);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
        }
        return new Blob([bytes]);
    }
    
    async loadFromTicket(ticketId, ts) {
        UiHelpers.setStatus(`正在加载工单 ${ticketId}...`, true);
        this.ticketId = ticketId;
        this.ts = ts || null;
        
        try {
            const ticketRes = await fetch(`/api/ticket/${ticketId}`);
            if (!ticketRes.ok) {
                const err = await ticketRes.json();
                throw new Error(err.error || '加载工单失败');
            }
            const ticketInfo = await ticketRes.json();
            
            let docBlob = null;
            if (ticketInfo.documentUrl) {
                const downloadRes = await fetch('/api/ticket/download', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ url: ticketInfo.documentUrl })
                });
                if (!downloadRes.ok) {
                    throw new Error('下载文档失败');
                }
                const arrayBuffer = await downloadRes.arrayBuffer();
                docBlob = new Blob([arrayBuffer]);
            } else if (ticketInfo.documentBase64) {
                docBlob = this._base64ToBlob(ticketInfo.documentBase64);
            }
            
            if (docBlob) {
                const fileName = ticketInfo.documentName || `工单_${ticketId}.docx`;
                const docFile = new File([docBlob], fileName, { type: docBlob.type || 'application/octet-stream' });
                this.document = await DocumentParser.parse(docFile);
                DocumentRenderer.render(this.document, 'docContent');
                if (this.auditMode === 'ticket') {
                    this.refreshTicketAuditView();
                } else {
                    TreeRenderer.render(this.document?.tree || [], 'structureTree');
                }
                if (this.auditMode === 'ticket') {
                    AuditMode.setText('wordCount', `字段: ${AuditMode.getTicketFieldCount(this.ticketData)}`);
                } else {
                    UiHelpers.updateWordCount(this.document.text?.length || 0);
                }
                this.updateDocBtn(true, this.document.name);
                this.compareStructure();
            }
            
            if (ticketInfo.data) {
                this.ticketData = ticketInfo.data;
                document.getElementById('excelLabel').textContent = '工单数据已加载';
                document.getElementById('excelIcon').className = 'fas fa-database text-blue-500 text-sm';
                this._updateDataPreviewButton();
                this.refreshTicketAuditView();
            }

            let templateBlob = null;
            if (ticketInfo.templateDocUrl) {
                const downRes = await fetch('/api/ticket/download', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ url: ticketInfo.templateDocUrl })
                });
                if (downRes.ok) {
                    const buf = await downRes.arrayBuffer();
                    templateBlob = new Blob([buf]);
                }
            } else if (ticketInfo.templateBase64) {
                templateBlob = this._base64ToBlob(ticketInfo.templateBase64);
            }

            if (templateBlob) {
                const tplName = ticketInfo.templateDocName || '手册模板.docx';
                const tplFile = new File([templateBlob], tplName, { type: templateBlob.type || 'application/octet-stream' });
                this.template = await DocumentParser.parse(tplFile);
                this._onTemplateLoaded(tplName);
            }

            UiHelpers.setStatus(`工单 ${ticketId} 已加载`);

            // 如果有 ts 参数，查询历史审核结果
            if (ts) {
                try {
                    const record = await FeedbackAPI.getAuditRecordByTicketIdAndTs(ticketId, ts);
                    if (record.exists && record.results && record.results.length > 0) {
                        this._displayHistoricalResults(record);
                    }
                } catch (err) {
                    console.error('查询历史审核记录失败:', err);
                }
            }
        } catch (err) {
            console.error('加载工单失败:', err);
            if (ts && await this._tryDisplayHistoricalTicketAudit(ticketId, ts)) {
                UiHelpers.setStatus(`工单 ${ticketId} 历史审核结果已加载`);
                return;
            }
            UiHelpers.setStatus(`工单加载失败: ${err.message}`);
            alert(`加载工单失败: ${err.message}`);
        }
    }

    async _tryDisplayHistoricalTicketAudit(ticketId, ts) {
        try {
            const record = await FeedbackAPI.getAuditRecordByTicketIdAndTs(ticketId, ts);
            if (record.exists && record.results && record.results.length > 0) {
                this._displayHistoricalResults(record);
                return true;
            }
        } catch (err) {
            console.error('查询历史审核记录失败:', err);
        }
        return false;
    }

    async loadFromOrder(orderId, ts) {
        UiHelpers.setStatus(`正在加载工单审核 ${orderId}...`, true);
        this.orderId = orderId;
        this.ts = ts || this._makeTs();
        this.auditMode = 'ticket';

        try {
            const orderRes = await fetch(`/api/order/${orderId}`);
            if (!orderRes.ok) {
                const err = await orderRes.json();
                throw new Error(err.error || '加载工单审核数据失败');
            }
            const orderInfo = await orderRes.json();
            this.ticketData = orderInfo.data || {};
            document.getElementById('excelLabel').textContent = '工单审核数据已加载';
            document.getElementById('excelIcon').className = 'fas fa-database text-blue-500 text-sm';
            this._updateDataPreviewButton();
            this.refreshTicketAuditView();

            UiHelpers.setStatus(`工单审核 ${orderId} 已加载`);
            if (ts) {
                try {
                    const record = await FeedbackAPI.getAuditRecordByOrderIdAndTs(orderId, ts);
                    if (record.exists && record.results && record.results.length > 0) {
                        this._displayHistoricalResults(record);
                    }
                } catch (err) {
                    console.error('查询工单审核历史记录失败:', err);
                }
            }
        } catch (err) {
            console.error('加载工单审核数据失败:', err);
            if (ts && await this._tryDisplayHistoricalOrderAudit(orderId, ts)) {
                UiHelpers.setStatus(`工单审核 ${orderId} 历史审核结果已加载`);
                return;
            }
            UiHelpers.setStatus(`工单审核数据加载失败: ${err.message}`);
            alert(`工单审核数据加载失败: ${err.message}`);
        }
    }

    async _tryDisplayHistoricalOrderAudit(orderId, ts) {
        try {
            const record = await FeedbackAPI.getAuditRecordByOrderIdAndTs(orderId, ts);
            if (record.exists && record.results && record.results.length > 0) {
                this._displayHistoricalResults(record);
                return true;
            }
        } catch (err) {
            console.error('查询工单审核历史记录失败:', err);
        }
        return false;
    }

    _displayHistoricalResults(record) {
        // 切换到审核结果 tab
        UiHelpers.switchTab('audit');

        const resultsContainer = document.getElementById('auditResults');
        const auditList = document.createElement('div');
        auditList.id = 'auditList';
        auditList.className = 'space-y-4';
        resultsContainer.innerHTML = '';

        // 添加历史审核提示横幅
        const auditedAt = record.auditedAt ? new Date(record.auditedAt).toLocaleString() : '未知时间';
        const banner = document.createElement('div');
        banner.className = 'bg-blue-50 border border-blue-200 rounded-xl p-4 mb-4 flex items-center justify-between';
        banner.innerHTML = `
            <div class="flex items-center gap-2 text-blue-700">
                <i class="fas fa-history"></i>
                <span>此工单已有 AI 审核结果（审核时间：${auditedAt}），如需重新审核请点击"重新审核"按钮</span>
            </div>
        `;
        resultsContainer.appendChild(banner);

        // 渲染历史结果
        this.auditResults = record.results || [];
        this.auditResults.forEach((result, i) => {
            const placeholder = document.createElement('div');
            placeholder.id = 'audit-rule-' + i;
            auditList.appendChild(placeholder);
            AiAudit.renderResult(result, placeholder, i);
        });
        resultsContainer.appendChild(auditList);

        // 修改按钮文案
        const btn = document.getElementById('runAuditBtn');
        btn.innerHTML = '<i class="fas fa-redo"></i> 重新审核';
    }
    
    async loadRuleGroups() {
        try {
            const config = await RulesManager.getGroupsFromServer();
            this.ruleGroups = config.groups || [];
            this.defaultRuleGroup = config.defaultGroup;
            
            const savedGroup = this._getSavedRuleGroup();
            const groupExists = this.ruleGroups.some(g => g.groupId === savedGroup);
            this.currentRuleGroup = groupExists ? savedGroup : this.defaultRuleGroup;
            
            RulesManager.renderGroupSelector(this.ruleGroups, this.currentRuleGroup, 'ruleGroupSelect');
            
            if (this.currentRuleGroup) {
                await this.loadCurrentGroupRules();
            }
        } catch (err) {
            console.error('加载规则组失败:', err);
            UiHelpers.setStatus('加载规则组失败: ' + err.message);
        }
    }
    
    async loadCurrentGroupRules(groupName = '') {
        const rules = await RulesManager.loadFromServer(this.currentRuleGroup, this.auditMode);
        this.rules = this._filterRulesForCurrentMode(rules || []);
        RulesManager.save(this.rules);
        this.renderRules();
        this.updateGroupLockUI();
        if (groupName) {
            UiHelpers.setStatus(`已加载规则组: ${groupName}`);
        }
    }

    _filterRulesForCurrentMode(rules) {
        const expectedScope = this.auditMode === 'ticket' ? 'ticket' : 'document';
        return (rules || []).filter(rule => {
            if (!rule.auditScope) return expectedScope === 'document';
            return String(rule.auditScope).toLowerCase() === expectedScope;
        });
    }
    
    _loadLocalSettings() {
        const localSettings = JSON.parse(localStorage.getItem('smartdoc_settings') || '{}');
        this.settings.batchSize = localSettings.batchSize ?? SETTINGS_DEFAULTS.batchSize;
        this.settings.repeatPrompt = localSettings.repeatPrompt ?? SETTINGS_DEFAULTS.repeatPrompt;
        this.settings.temperature = localSettings.temperature ?? SETTINGS_DEFAULTS.temperature;
        this.settings.topP = localSettings.topP ?? SETTINGS_DEFAULTS.topP;
    }
    
    _applyApiConfig(apiConfig) {
        if (!apiConfig) return;
        this.settings.endpoint = apiConfig.endpoint || API_DEFAULTS.endpoint;
        this.settings.model = apiConfig.model || API_DEFAULTS.model;
        this.settings.auditRole = apiConfig.auditRole || API_DEFAULTS.auditRole;
        this.settings.hasApiKey = apiConfig.hasApiKey || false;
    }
    
    async loadPresetConfig() {
        try {
            const apiConfig = await ConfigAPI.getApiConfig();
            this._applyApiConfig(apiConfig);
        } catch (err) {
            console.error('加载API配置失败:', err);
        }

        this._loadLocalSettings();
        await this.loadTemplateList();
    }
    
    async loadTemplateList() {
        const config = await ConfigLoader.loadTemplateList();
        this.templateList = config.templates || [];
        this.defaultTemplateName = config.defaultTemplate || '';
        this.renderTemplateListInModal();
    }
    
    renderTemplateListInModal() {
        const container = document.getElementById('templateList');
        if (!container) return;
        
        if (this.templateList.length === 0) {
            container.innerHTML = '<div class="text-center text-gray-400 text-sm py-4">暂无示例模板</div>';
            return;
        }
        
        container.innerHTML = this.templateList.map(t => `
            <button onclick="app.selectPresetTemplate('${t.name}')" 
                class="w-full flex items-center gap-3 p-3 border border-gray-200 rounded-lg hover:bg-blue-50 hover:border-blue-300 transition-all text-left">
                <i class="fas fa-file-alt text-blue-500"></i>
                <div>
                    <div class="font-medium text-sm text-gray-900">${t.name}</div>
                    ${t.description ? `<div class="text-xs text-gray-500">${t.description}</div>` : ''}
                </div>
            </button>
        `).join('');
    }
    
    _handleModal(show, modalId) {
        UiHelpers.toggleModal(modalId, show);
    }
    
    showTemplateModal() { this._handleModal(true, 'templateModal'); }
    closeTemplateModal() { this._handleModal(false, 'templateModal'); }
    closeRuleModal() {
        this._setRuleModalReadonly(false);
        this._handleModal(false, 'ruleModal');
    }
    closeGroupModal() { this._handleModal(false, 'groupModal'); }
    
    async selectPresetTemplate(fileName) {
        this.closeTemplateModal();
        await this.loadPresetTemplate(fileName);
    }
    
    async loadPresetTemplate(fileName) {
        UiHelpers.setStatus('正在加载预设模板...', true);
        const file = await ConfigLoader.loadPresetTemplate(fileName);
        if (file) {
            this.template = await DocumentParser.parse(file);
            this._onTemplateLoaded(fileName);
        } else {
            UiHelpers.setStatus('模板加载失败');
        }
    }
    
    async handleTemplateUpload(input) {
        const file = input.files[0];
        if (!file) return;
        
        this.closeTemplateModal();
        UiHelpers.setStatus('正在解析模板...', true);
        try {
            this.template = await DocumentParser.parse(file);
            this._onTemplateLoaded(file.name);
        } catch (err) {
            alert('解析失败: ' + err.message);
            UiHelpers.setStatus('就绪');
        }
        input.value = '';
    }
    
    _onTemplateLoaded(fileName) {
        if (this.auditMode === 'ticket') {
            this.refreshTicketAuditView();
        } else {
            TreeRenderer.render(this.template?.tree || [], 'structureTree');
        }
        UiHelpers.setStatus(`模板已加载: ${fileName}`);
        this.updateTemplateBtn(true, fileName);
        this.compareStructure();
    }
    
    async handleDocUpload(input) {
        const file = input.files[0];
        if (!file) return;
        
        UiHelpers.setStatus('正在解析文档...', true);
        try {
            this.document = await DocumentParser.parse(file);
            DocumentRenderer.render(this.document, 'docContent');
            if (this.auditMode === 'ticket') {
                this.refreshTicketAuditView();
            } else {
                TreeRenderer.render(this.document?.tree || [], 'structureTree');
            }
            if (this.auditMode === 'ticket') {
                AuditMode.setText('wordCount', `字段: ${AuditMode.getTicketFieldCount(this.ticketData)}`);
            } else {
                UiHelpers.updateWordCount(this.document.text?.length || 0);
            }
            UiHelpers.setStatus(`文档已加载: ${file.name}`);
            this.updateDocBtn(true, file.name);
            this.compareStructure();
        } catch (err) {
            alert('解析失败: ' + err.message);
            UiHelpers.setStatus('就绪');
        }
    }
    
    async handleExcelUpload(input) {
        const file = input.files[0];
        if (!file) return;
        
        UiHelpers.setStatus('正在解析Excel...', true);
        try {
            this.excelData = await DocumentParser.parseExcel(file);
            if (!this.ticketData) this.ticketData = {};
            Object.assign(this.ticketData, this.excelData.data);
            document.getElementById('excelLabel').textContent = file.name;
            document.getElementById('excelIcon').className = 'fas fa-table text-green-500 text-sm';
            this._updateDataPreviewButton();
            this.refreshTicketAuditView();
            UiHelpers.setStatus(`Excel已加载: ${file.name}，数据已合并到 {{data}}`);
        } catch (err) {
            alert('Excel解析失败: ' + err.message);
        }
    }
    
    _updateFileBtn(btnId, iconId, titleId, descId, options) {
        const btn = document.getElementById(btnId);
        const icon = document.getElementById(iconId);
        const title = document.getElementById(titleId);
        const desc = document.getElementById(descId);
        const color = options.loaded ? 'blue' : 'gray';
        
        btn.className = `w-full flex items-center gap-2 p-3 bg-${color}-50 border border-${color}-200 rounded-lg cursor-pointer hover:bg-${color}-100 transition-colors`;
        icon.className = `fas fa-${options.icon} text-${color}-600`;
        title.className = `font-medium text-${color}-900`;
        title.textContent = options.loadedTitle;
        desc.className = `text-xs text-${color}-600`;
        desc.textContent = options.fileName.length > 20 ? options.fileName.substring(0, 20) + '...' : options.fileName;
    }
    
    updateTemplateBtn(loaded, fileName = '') {
        this._updateFileBtn('templateBtn', 'templateBtnIcon', 'templateBtnTitle', 'templateBtnDesc', {
            loaded, fileName: fileName || '示例模板或上传文件',
            icon: 'file-import',
            loadedTitle: '模板已加载'
        });
    }
    
    updateDocBtn(loaded, fileName = '') {
        this._updateFileBtn('docBtn', 'docBtnIcon', 'docBtnTitle', 'docBtnDesc', {
            loaded, fileName: fileName || '需要检查的文件',
            icon: 'file-alt',
            loadedTitle: '文档已加载'
        });
    }
    
    compareStructure() {
        if (!this.template || !this.document) return;
        
        const result = StructureCompare.compare(this.template, this.document, this.exactMatchMode);
        if (result) {
            StructureCompare.renderDiffs(result.diffs, result.score);
            StructureCompare.renderCompareView(result.templateNodes, result.docNodes, result.matches);
            if (this.exactMatchMode && result.contentDiffs) {
                StructureCompare.renderContentDiffs(result.contentDiffs);
            } else {
                StructureCompare.hideContentDiffs();
            }
        }
    }
    
    toggleExactMatch(enabled) {
        this.exactMatchMode = enabled;
        const hint = document.getElementById('exactMatchHint');
        const track = document.querySelector('.toggle-track');
        const thumb = document.querySelector('.toggle-thumb');
        const structureContainer = document.getElementById('structureCompareContainer');
        
        if (enabled) {
            hint.textContent = '开启内容差异对比';
            track.classList.remove('bg-gray-300');
            track.classList.add('bg-blue-600');
            thumb.style.transform = 'translateX(20px)';
            if (structureContainer) {
                structureContainer.classList.add('hidden');
            }
        } else {
            hint.textContent = '关闭时仅校验结构';
            track.classList.remove('bg-blue-600');
            track.classList.add('bg-gray-300');
            thumb.style.transform = 'translateX(0)';
            if (structureContainer) {
                structureContainer.classList.remove('hidden');
            }
        }
        
        this.compareStructure();
    }
    
    renderRules() {
        RulesManager.renderList(this.rules, 'rulesList');
    }
    
    async switchRuleGroup(groupId) {
        if (groupId === this.currentRuleGroup) return;
        
        this.currentRuleGroup = groupId;
        this._setSavedRuleGroup(groupId);
        
        const group = this.ruleGroups.find(g => g.groupId === groupId);
        if (group) {
            UiHelpers.setStatus('正在加载规则组...', true);
            try {
                await this.loadCurrentGroupRules(group.name);
            } catch (err) {
                UiHelpers.setStatus(`规则组加载失败: ${err.message}`);
            }
        }
    }
    
    async toggleRuleStatus(idx) {
        this.rules[idx].enabled = !this.rules[idx].enabled;
        this.renderRules();
        await this._autoSave();
    }

    async deleteRule(idx) {
        const rule = this.rules[idx];
        if (!confirm(`确定要删除规则"${rule.name}"吗？此操作不可恢复！`)) return;
        this.rules.splice(idx, 1);
        this.renderRules();
        await this._autoSave();
        UiHelpers.setStatus('规则已删除');
    }
    
    _setRuleModalReadonly(readonly) {
        ['ruleName', 'rulePrompt', 'ruleSeverity', 'ruleTriggerCondition'].forEach(id => {
            const el = document.getElementById(id);
            if (!el) return;
            el.disabled = readonly;
            el.classList.toggle('bg-gray-50', readonly);
            el.classList.toggle('text-gray-600', readonly);
            el.classList.toggle('cursor-not-allowed', readonly);
        });

        const title = document.getElementById('ruleModalTitle');
        if (title) title.textContent = readonly ? '查看审核规则' : '编辑审核规则';

        const cancelBtn = document.getElementById('ruleModalCancelBtn');
        if (cancelBtn) cancelBtn.textContent = readonly ? '关闭' : '取消';

        const saveBtn = document.getElementById('ruleModalSaveBtn');
        if (saveBtn) saveBtn.classList.toggle('hidden', readonly);

        this._ruleModalReadonly = readonly;
    }

    _fillRuleModal(rule) {
        document.getElementById('ruleName').value = rule.name || '';
        document.getElementById('rulePrompt').value = rule.prompt || '';
        document.getElementById('ruleSeverity').value = rule.severity || 'warning';
        document.getElementById('ruleTriggerCondition').value = rule.triggerCondition || '';
    }

    addRule() {
        if (this._isCurrentGroupLocked()) {
            alert('规则组已上锁，无法新增规则');
            return;
        }
        this._setRuleModalReadonly(false);
        this.currentEditingRule = null;
        this._fillRuleModal({ severity: 'warning' });
        UiHelpers.toggleModal('ruleModal', true);
    }
    
    editRule(idx) {
        if (this._isCurrentGroupLocked()) {
            this.viewRule(idx);
            return;
        }
        this._setRuleModalReadonly(false);
        this.currentEditingRule = idx;
        const rule = this.rules[idx];
        this._fillRuleModal(rule);
        UiHelpers.toggleModal('ruleModal', true);
    }

    viewRule(idx) {
        this.currentEditingRule = null;
        const rule = this.rules[idx];
        if (!rule) return;
        this._fillRuleModal(rule);
        this._setRuleModalReadonly(true);
        UiHelpers.toggleModal('ruleModal', true);
    }
    
    async saveRule() {
        if (this._ruleModalReadonly || this._isCurrentGroupLocked()) {
            alert('规则组已上锁，无法编辑规则');
            return;
        }
        const name = document.getElementById('ruleName').value.trim();
        const prompt = document.getElementById('rulePrompt').value.trim();
        const severity = document.getElementById('ruleSeverity').value;
        
        if (!name || !prompt) {
            alert('请填写完整信息');
            return;
        }
        
        const triggerCondition = document.getElementById('ruleTriggerCondition').value.trim() || null;
        const isEditing = this.currentEditingRule !== null;
        const rule = { 
            name, prompt, severity, triggerCondition,
            id: isEditing ? this.rules[this.currentEditingRule].id : Date.now(),
            sortOrder: isEditing ? this.rules[this.currentEditingRule].sortOrder : this.rules.length,
            enabled: isEditing ? this.rules[this.currentEditingRule].enabled !== false : true
        };
        
        if (this.currentEditingRule !== null) {
            this.rules[this.currentEditingRule] = rule;
        } else {
            this.rules.push(rule);
        }
        
        this.renderRules();
        this.closeRuleModal();
        await this._autoSave();
    }
    
    async _autoSave() {
        if (!this.currentRuleGroup) return;
        
        const group = this.ruleGroups.find(g => g.groupId === this.currentRuleGroup);
        const groupName = group ? group.name : '';
        
        try {
            const savedRules = await RulesManager.saveToServer(this.currentRuleGroup, this.rules, groupName, this.auditMode);
            savedRules.forEach((sr, i) => {
                if (this.rules[i]) {
                    this.rules[i].id = sr.id;
                }
            });
            RulesManager.save(this.rules);
            UiHelpers.setStatus('规则已自动保存');
        } catch (err) {
            UiHelpers.setStatus(`保存失败: ${err.message}`);
        }
    }
    
    _setupGroupModal(mode) {
        this._groupModalMode = mode;
        const isCreate = mode === 'create';
        
        document.getElementById('groupModalTitle').textContent = isCreate ? '新建规则组' : '编辑规则组';
        document.getElementById('groupModalBtn').textContent = isCreate ? '创建' : '保存';
        document.getElementById('groupIdField').style.display = isCreate ? 'block' : 'none';
        
        if (isCreate) {
            document.getElementById('groupId').value = '';
            document.getElementById('groupId').disabled = false;
        } else {
            const group = this.ruleGroups.find(g => g.groupId === this.currentRuleGroup);
            document.getElementById('groupId').value = group?.groupId || '';
        }
        
        document.getElementById('groupName').value = isCreate ? '' : (this.ruleGroups.find(g => g.groupId === this.currentRuleGroup)?.name || '');
        UiHelpers.toggleModal('groupModal', true);
    }
    
    showCreateGroupModal() { this._setupGroupModal('create'); }
    showEditGroupModal() {
        if (this._isCurrentGroupLocked()) {
            alert('规则组已上锁，无法编辑');
            return;
        }
        if (!this.currentRuleGroup) {
            alert('请先选择规则组');
            return;
        }
        this._setupGroupModal('edit');
    }
    
    async saveGroupModal() {
        const groupId = document.getElementById('groupId').value.trim();
        const groupName = document.getElementById('groupName').value.trim();
        
        if (this._groupModalMode === 'create') {
            if (!groupId || !groupName) {
                alert('请填写完整信息');
                return;
            }
            if (!/^[a-zA-Z0-9_-]+$/.test(groupId)) {
                alert('规则组ID只能包含字母、数字、下划线和横线');
                return;
            }
            
            try {
                await RulesManager.createGroup(groupId, groupName, []);
                this.ruleGroups.push({ groupId: groupId, name: groupName });
                this.currentRuleGroup = groupId;
                this._setSavedRuleGroup(groupId);
                this.rules = [];
                this.renderRules();
                RulesManager.renderGroupSelector(this.ruleGroups, groupId, 'ruleGroupSelect');
                this.closeGroupModal();
                UiHelpers.setStatus('规则组创建成功');
            } catch (err) {
                alert('创建失败: ' + err.message);
            }
        } else {
            if (!groupName) {
                alert('请填写规则组名称');
                return;
            }
            
            try {
                const savedRules = await RulesManager.saveToServer(groupId, this.rules, groupName, this.auditMode);
                savedRules.forEach((sr, i) => {
                    if (this.rules[i]) {
                        this.rules[i].id = sr.id;
                    }
                });
                const group = this.ruleGroups.find(g => g.groupId === groupId);
                if (group) group.name = groupName;
                RulesManager.renderGroupSelector(this.ruleGroups, groupId, 'ruleGroupSelect');
                this.closeGroupModal();
                UiHelpers.setStatus('规则组名称已更新');
            } catch (err) {
                alert('保存失败: ' + err.message);
            }
        }
    }
    
    async deleteCurrentGroup() {
        if (this._isCurrentGroupLocked()) {
            alert('规则组已上锁，无法删除');
            return;
        }
        if (!this.currentRuleGroup) {
            alert('请先选择规则组');
            return;
        }
        
        if (this.ruleGroups.length <= 1) {
            alert('至少保留一个规则组');
            return;
        }
        
        const group = this.ruleGroups.find(g => g.groupId === this.currentRuleGroup);
        if (!confirm(`确定要删除规则组"${group?.name || this.currentRuleGroup}"吗？此操作不可恢复！`)) {
            return;
        }
        
        try {
            await RulesManager.deleteGroup(this.currentRuleGroup);
            this.ruleGroups = this.ruleGroups.filter(g => g.groupId !== this.currentRuleGroup);
            this.currentRuleGroup = this.ruleGroups[0]?.groupId;
            this._setSavedRuleGroup(this.currentRuleGroup);
            RulesManager.renderGroupSelector(this.ruleGroups, this.currentRuleGroup, 'ruleGroupSelect');
            await this.loadCurrentGroupRules();
            UiHelpers.setStatus('规则组已删除');
        } catch (err) {
            alert('删除失败: ' + err.message);
        }
    }
    
    exportRules() {
        const group = this.ruleGroups.find(g => g.groupId === this.currentRuleGroup);
        if (!group) {
            alert('未找到当前规则组');
            return;
        }
        
        const blob = new Blob([JSON.stringify(this.rules, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `${group.name}_规则_${new Date().toISOString().slice(0, 10)}.json`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        
        UiHelpers.setStatus(`已导出 ${this.rules.length} 条规则`);
    }
    
    importRules() {
        if (this._isCurrentGroupLocked()) {
            alert('规则组已上锁，无法导入规则');
            return;
        }
        const group = this.ruleGroups.find(g => g.groupId === this.currentRuleGroup);
        if (!group) {
            alert('请先选择规则组');
            return;
        }
        
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.json';
        input.onchange = async (e) => {
            const file = e.target.files[0];
            if (!file) return;
            
            try {
                const text = await file.text();
                const importData = JSON.parse(text);
                
                let rules = [];
                if (Array.isArray(importData)) {
                    rules = importData;
                } else if (importData.rules && Array.isArray(importData.rules)) {
                    rules = importData.rules;
                } else {
                    throw new Error('无效的规则文件格式');
                }
                
                const confirmMsg = `即将导入 ${rules.length} 条规则到当前规则组「${group.name}」。\n\n这将覆盖当前规则组的所有现有规则（共 ${this.rules.length} 条），是否继续？`;
                
                if (!confirm(confirmMsg)) {
                    return;
                }
                
                this.rules = rules;
                const savedRules = await RulesManager.saveToServer(this.currentRuleGroup, this.rules, group.name, this.auditMode);
                savedRules.forEach((sr, i) => {
                    if (this.rules[i]) {
                        this.rules[i].id = sr.id;
                    }
                });
                this.renderRules();
                
                UiHelpers.setStatus(`已导入 ${this.rules.length} 条规则到「${group.name}」`);
            } catch (err) {
                alert('导入失败: ' + err.message);
            }
        };
        input.click();
    }

    toggleGroupActions(event) {
        if (event) {
            event.stopPropagation();
        }
        const dropdown = document.getElementById('groupActionsDropdown');
        dropdown.classList.toggle('hidden');
        if (!dropdown.classList.contains('hidden')) {
            const hideDropdown = (e) => {
                if (!dropdown.contains(e.target)) {
                    dropdown.classList.add('hidden');
                    document.removeEventListener('click', hideDropdown);
                }
            };
            setTimeout(() => document.addEventListener('click', hideDropdown), 0);
        }
    }

    _isCurrentGroupLocked() {
        const group = this.ruleGroups.find(g => g.groupId === this.currentRuleGroup);
        return group?.locked === true;
    }

    updateGroupLockUI() {
        const group = this.ruleGroups.find(g => g.groupId === this.currentRuleGroup);
        const isLocked = group?.locked;
        const lockBtn = document.getElementById('lockGroupBtn');
        const unlockBtn = document.getElementById('unlockGroupBtn');
        const addBtn = document.getElementById('addRuleBtn');
        const lockedBadge = document.getElementById('lockedBadge');
        if (!lockBtn || !unlockBtn) return;
        if (isLocked) {
            lockBtn.classList.add('hidden');
            unlockBtn.classList.remove('hidden');
            if (addBtn) { addBtn.disabled = true; addBtn.classList.add('opacity-50', 'cursor-not-allowed'); }
            if (lockedBadge) lockedBadge.classList.remove('hidden');
        } else {
            lockBtn.classList.remove('hidden');
            unlockBtn.classList.add('hidden');
            if (addBtn) { addBtn.disabled = false; addBtn.classList.remove('opacity-50', 'cursor-not-allowed'); }
            if (lockedBadge) lockedBadge.classList.add('hidden');
        }
    }

    showLockGroupModal() {
        document.getElementById('lockPassword').value = '';
        document.getElementById('lockPasswordConfirm').value = '';
        UiHelpers.toggleModal('lockModal', true);
    }

    closeLockModal() {
        UiHelpers.toggleModal('lockModal', false);
    }

    async submitLockGroup() {
        const password = document.getElementById('lockPassword').value.trim();
        const confirm = document.getElementById('lockPasswordConfirm').value.trim();

        if (!password || !confirm) {
            alert('请填写密码');
            return;
        }
        if (password !== confirm) {
            alert('两次输入的密码不一致');
            return;
        }
        if (password.length < 4) {
            alert('密码长度不能少于4位');
            return;
        }

        try {
            await LockAPI.lockGroup(this.currentRuleGroup, password);
            const group = this.ruleGroups.find(g => g.groupId === this.currentRuleGroup);
            if (group) group.locked = true;
            this.closeLockModal();
            this.updateGroupLockUI();
            this.renderRules();
            UiHelpers.setStatus('规则组已上锁');
        } catch (err) {
            alert('上锁失败: ' + err.message);
        }
    }

    showUnlockGroupModal() {
        document.getElementById('unlockPassword').value = '';
        UiHelpers.toggleModal('unlockModal', true);
    }

    closeUnlockModal() {
        UiHelpers.toggleModal('unlockModal', false);
    }

    async submitUnlockGroup() {
        const password = document.getElementById('unlockPassword').value.trim();
        if (!password) {
            alert('请输入密码');
            return;
        }

        try {
            await LockAPI.unlockGroup(this.currentRuleGroup, password);
            const group = this.ruleGroups.find(g => g.groupId === this.currentRuleGroup);
            if (group) group.locked = false;
            this.closeUnlockModal();
            this.updateGroupLockUI();
            this.renderRules();
            UiHelpers.setStatus('规则组已解锁');
        } catch (err) {
            alert('解锁失败: ' + err.message);
        }
    }

    _renderAuditResults(batchResults, placeholders, startIdx) {
        batchResults.forEach((result, i) => {
            this.auditResults[startIdx + i] = result;
            AiAudit.renderResult(result, placeholders[startIdx + i], startIdx + i);
        });
    }
    
    async runAudit() {
        if (this.isAuditing) {
            alert('正在审核中，请稍候...');
            return;
        }
        
        const isTicketMode = this.auditMode === 'ticket';
        if (!isTicketMode && !this.document) {
            alert('请先上传待审文档');
            return;
        }

        if (isTicketMode && (!this.ticketData || Object.keys(this.ticketData).length === 0)) {
            alert('请先加载工单数据');
            return;
        }

        if (isTicketMode && !this.orderId) {
            alert('请通过 orderId 进入工单审核');
            return;
        }
        
        const activeRules = this.rules.filter(r => r.enabled !== false);
        
        if (activeRules.length === 0) {
            alert('请至少开启一条审核规则');
            return;
        }
        if (!this.settings.hasApiKey) {
            alert('请先配置API密钥');
            this.toggleSettings();
            return;
        }

        // 如果已有历史审核结果（从 ticket 加载的），确认是否重新审核
        if (this.ticketId && this.auditResults && this.auditResults.length > 0) {
            if (!confirm('该工单已有审核结果，重新审核将生成新的审核记录，并默认展示最新一次，是否继续？')) {
                return;
            }
        }

        const freshRules = await RulesManager.loadFromServer(this.currentRuleGroup, this.auditMode);
        if (freshRules && freshRules.length > 0) {
            this.rules = this._filterRulesForCurrentMode(freshRules);
        }
        const syncedRules = this.rules.filter(r => r.enabled !== false);
        if (syncedRules.length === 0) {
            alert('请至少开启一条审核规则');
            return;
        }
        
        this.isAuditing = true;
        this._auditStartTime = Date.now();
        const btn = document.getElementById('runAuditBtn');
        btn.disabled = true;
        btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 审核中...';
        
        UiHelpers.setStatus(isTicketMode ? '正在运行工单审核...' : '正在运行AI审核...', true);
        this.auditResults = [];
        const resultsContainer = document.getElementById('auditResults');
        resultsContainer.innerHTML = '<div class="space-y-4" id="auditList"></div>';
        
        try {
            const auditList = document.getElementById('auditList');
            const placeholders = syncedRules.map((rule, i) => {
                const div = document.createElement('div');
                div.id = 'audit-rule-' + i;
                div.innerHTML = `
                    <div class="bg-white rounded-xl border border-gray-200 p-6 animate-pulse">
                        <div class="flex items-center gap-3 mb-4">
                            <div class="w-10 h-10 rounded-full bg-gray-200 flex items-center justify-center">
                                <i class="fas fa-spinner fa-spin text-gray-400"></i>
                            </div>
                            <div class="flex-1">
                                <div class="h-4 bg-gray-200 rounded w-1/3 mb-2"></div>
                                <div class="flex items-center gap-2">
                                    <span class="px-2 py-0.5 rounded-full bg-gray-100 text-gray-400 text-xs">${rule.severity === 'error' ? '错误' : rule.severity === 'warning' ? '警告' : '信息'}</span>
                                    <span class="text-xs text-gray-300"><i class="fas fa-spinner fa-spin"></i> 审核中...</span>
                                </div>
                            </div>
                        </div>
                        <div class="space-y-2">
                            <div class="h-3 bg-gray-100 rounded w-full"></div>
                            <div class="h-3 bg-gray-100 rounded w-2/3"></div>
                        </div>
                    </div>`;
                auditList.appendChild(div);
                return div;
            });

            const auditRequest = {
                ruleGroupId: this.currentRuleGroup,
                documentText: isTicketMode
                    ? TicketAuditView.toAuditText(this.ticketData, this.orderId || this.ticketId, this.ts)
                    : this.document.text,
                documentType: 'txt',
                data: this.ticketData,
                auditMode: this.auditMode,
                ticketId: this.ticketId,
                orderId: this.orderId,
                ts: this.ts,
                settings: {
                    endpoint: this.settings.endpoint,
                    model: this.settings.model,
                    auditRole: this.settings.auditRole,
                    repeatPrompt: this.settings.repeatPrompt,
                    batchSize: this.settings.batchSize,
                    temperature: this.settings.temperature
                }
            };

            const batchSize = this.settings.batchSize || 0;
            let allResults = [];

            if (batchSize > 0) {
                const response = await fetch('/api/audit/stream', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(auditRequest)
                });

                if (!response.ok) {
                    throw new Error('审核请求失败');
                }

                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';

                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;

                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop();

                    for (const line of lines) {
                        if (!line.trim()) continue;
                        try {
                            const parsed = JSON.parse(line);
                            const idx = parsed.index;
                            const result = parsed.result;
                            if (idx != null && result) {
                                this.auditResults[idx] = result;
                                AiAudit.renderResult(result, placeholders[idx], idx);
                                allResults[idx] = result;
                            }
                        } catch (e) {
                            console.error('解析流式结果失败:', e);
                        }
                    }
                }
            } else {
                const response = await fetch('/api/audit', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(auditRequest)
                });

                if (!response.ok) {
                    const err = await response.json();
                    throw new Error(err.error || '审核请求失败');
                }

                const data = await response.json();
                allResults = data.results || [];

                allResults.forEach((result, i) => {
                    this.auditResults[i] = result;
                    AiAudit.renderResult(result, placeholders[i], i);
                });
            }

            try {
                const validResults = allResults.filter(Boolean);
                const needsSave = validResults.some(r => !r._feedbackId);
                if (needsSave) {
                    const saveResults = validResults.map(r => ({
                        ruleId: r.ruleId,
                        ruleName: r.ruleName,
                        severity: r.severity,
                        pass: r.pass,
                        skipped: r.skipped,
                        confidence: r.confidence,
                        issues: r.issues || [],
                        summary: r.summary || ''
                    }));
                    const auditDuration = Date.now() - (this._auditStartTime || Date.now());
                    const saveResponse = isTicketMode
                        ? await FeedbackAPI.saveOrderAuditResults(
                            saveResults, this.currentRuleGroup, auditDuration,
                            this.orderId, this.ts
                        )
                        : await FeedbackAPI.saveAuditResults(
                            saveResults, this.currentRuleGroup, auditDuration,
                            this.ticketId, this.ts
                        );
                    if (saveResponse.ts) {
                        this.ts = saveResponse.ts;
                    }
                    const feedbackIds = saveResponse.ids || [];
                    validResults.forEach((r, i) => {
                        r._feedbackId = feedbackIds[i];
                    });
                }
            } catch (err) {
                console.error('保存审核结果失败:', err);
            }

            UiHelpers.setStatus(`${isTicketMode ? '工单审核' : '审核'}完成，共检查 ${syncedRules.length} 条规则`);
            document.getElementById('auditBadge').classList.remove('hidden');
            UiHelpers.switchTab('audit');

        } catch (err) {
            UiHelpers.setStatus('审核失败: ' + err.message);
            console.error('审核失败:', err);
            alert('审核失败: ' + err.message);
        } finally {
            this.isAuditing = false;
            btn.disabled = false;
            btn.innerHTML = '<i class="fas fa-play"></i> <span id="runAuditBtnText">AI审核</span>';
        }
    }
    
    async toggleSettings() {
        const modal = document.getElementById('settingsModal');
        modal.classList.toggle('hidden');
        if (!modal.classList.contains('hidden')) {
            await this._loadApiConfig();
            this.loadSettings();
        }
    }

    async _loadApiConfig() {
        try {
            const apiConfig = await ConfigAPI.getApiConfig();
            this._applyApiConfig(apiConfig);
            this._loadLocalSettings();
        } catch (err) {
            console.error('加载API配置失败:', err);
        }
    }
    
    async saveSettings() {
        const apiKey = document.getElementById('apiKey').value;
        const config = {
            endpoint: document.getElementById('apiEndpoint').value,
            model: document.getElementById('apiModel').value,
            auditRole: document.getElementById('auditRole').value || API_DEFAULTS.auditRole
        };

        if (apiKey) config.apiKey = apiKey;

        try {
            await ConfigAPI.updateApiConfig(config);

            this.settings.endpoint = config.endpoint;
            this.settings.model = config.model;
            this.settings.auditRole = config.auditRole;
            if (apiKey) this.settings.hasApiKey = true;

            const batchSize = parseInt(document.getElementById('batchSize').value) || SETTINGS_DEFAULTS.batchSize;
            const repeatPrompt = document.getElementById('repeatPrompt').checked;
            const temperature = parseFloat(document.getElementById('temperature').value) || SETTINGS_DEFAULTS.temperature;
            const topP = parseFloat(document.getElementById('topP').value) || SETTINGS_DEFAULTS.topP;
            this.settings.batchSize = batchSize;
            this.settings.repeatPrompt = repeatPrompt;
            this.settings.temperature = temperature;
            this.settings.topP = topP;

            localStorage.setItem('smartdoc_settings', JSON.stringify({ batchSize, repeatPrompt, temperature, topP }));

            UiHelpers.toggleModal('settingsModal', false);
            this.updateApiStatus();
            alert('设置已保存到服务器');
        } catch (err) {
            alert('保存失败: ' + err.message);
        }
    }
    
    loadSettings() {
        document.getElementById('apiEndpoint').value = this.settings.endpoint || API_DEFAULTS.endpoint;
        document.getElementById('apiModel').value = this.settings.model || API_DEFAULTS.model;
        document.getElementById('auditRole').value = this.settings.auditRole || API_DEFAULTS.auditRole;
        document.getElementById('batchSize').value = this.settings.batchSize || SETTINGS_DEFAULTS.batchSize;
        document.getElementById('repeatPrompt').checked = this.settings.repeatPrompt ?? SETTINGS_DEFAULTS.repeatPrompt;
        document.getElementById('temperature').value = this.settings.temperature ?? SETTINGS_DEFAULTS.temperature;
        document.getElementById('topP').value = this.settings.topP ?? SETTINGS_DEFAULTS.topP;
        document.getElementById('apiKey').value = '';

        const apiKeyStatus = document.getElementById('apiKeyStatus');
        if (this.settings.hasApiKey) {
            apiKeyStatus.textContent = 'API密钥已设置（填入新值可更新）';
            apiKeyStatus.className = 'text-xs text-green-600 mt-1';
        } else {
            apiKeyStatus.textContent = 'API密钥未设置';
            apiKeyStatus.className = 'text-xs text-gray-500 mt-1';
        }
    }
    
    updateApiStatus() {
        UiHelpers.updateApiStatus(!!this.settings.hasApiKey);
    }
    
    async testConnection() {
        const btn = event.target;
        const originalText = btn.textContent;
        btn.textContent = '测试中...';
        btn.disabled = true;

        try {
            const endpoint = document.getElementById('apiEndpoint').value;
            const model = document.getElementById('apiModel').value;

            const response = await fetch('/api/proxy', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    endpoint: endpoint,
                    body: {
                        model: model || API_DEFAULTS.model,
                        messages: [{ role: 'user', content: '你好' }],
                        temperature: 0.7,
                        max_tokens: 10
                    }
                })
            });

            if (response.ok) {
                alert('连接成功！');
            } else {
                alert(`连接失败: ${response.status} - ${response.statusText}\n${await response.text()}`);
            }
        } catch (err) {
            alert('连接错误: ' + err.message);
        } finally {
            btn.textContent = originalText;
            btn.disabled = false;
        }
    }
    
    switchTab(tab) {
        if (this.auditMode === 'ticket') {
            if (tab === 'preview') {
                UiHelpers.switchTab('ticket');
                return;
            }
            if (tab === 'compare') return;
        }
        UiHelpers.switchTab(tab);
    }
    async switchAuditMode(mode) {
        const nextMode = mode === 'ticket' ? 'ticket' : 'document';
        AuditMode.apply(this, nextMode);
        RulesManager.renderGroupSelector(this.ruleGroups, this.currentRuleGroup, 'ruleGroupSelect');

        if (this.currentRuleGroup) {
            await this.loadCurrentGroupRules();
        } else {
            this.renderRules();
            this.updateGroupLockUI();
        }
    }

    refreshTicketAuditView() {
        if (typeof TicketAuditView !== 'undefined') {
            if (this.auditMode === 'ticket' && this.orderId) {
                TicketAuditView.render(this);
            } else if (this.auditMode === 'ticket') {
                TicketAuditView.render({ ...this, ticketData: null, ticketId: null });
            }
        }
        if (this.auditMode === 'ticket') {
            const fieldCount = this.orderId ? AuditMode.getTicketFieldCount(this.ticketData) : 0;
            AuditMode.setText('wordCount', `字段: ${fieldCount}`);
        }
    }

    scrollToTicketField(key) {
        UiHelpers.switchTab('ticket');
        setTimeout(() => TicketAuditView.scrollToField(key), 100);
    }

    scrollToNode(nodeId) { UiHelpers.switchTab('preview'); setTimeout(() => UiHelpers.scrollToNode(nodeId), 100); }
    setStatus(text, loading = false) { UiHelpers.setStatus(text, loading); }
    exportHtmlReport() { ReportExporter.exportHtml(this.document, this.template, this.excelData, this.auditResults); }
    
    showImportExportModal() { UiHelpers.toggleModal('importExportModal', true); }
    closeImportExportModal() { UiHelpers.toggleModal('importExportModal', false); }
    
    toggleAdvancedSettings() {
        const panel = document.getElementById('advancedSettingsPanel');
        const icon = document.getElementById('advancedSettingsIcon');
        panel.classList.toggle('hidden');
        if (panel.classList.contains('hidden')) {
            icon.classList.remove('rotate-180');
        } else {
            icon.classList.add('rotate-180');
        }
    }
    
    exportTicketData() {
        if (!this.ticketData) {
            alert('暂无数据可导出');
            return;
        }
        const json = JSON.stringify(this.ticketData, null, 2);
        const blob = new Blob([json], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `工单数据_${new Date().toISOString().slice(0, 10)}.json`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    showDataPreview() {
        if (!this.ticketData) {
            alert('暂无数据可预览');
            return;
        }
        
        const content = document.getElementById('dataPreviewContent');
        const formattedJson = this._formatDataForPreview(this.ticketData);
        content.innerHTML = `<pre class="whitespace-pre-wrap break-words">${formattedJson}</pre>`;
        UiHelpers.toggleModal('dataPreviewModal', true);
    }
    
    closeDataPreview() {
        UiHelpers.toggleModal('dataPreviewModal', false);
    }
    
    _formatDataForPreview(data, pathPrefix = '') {
        if (data === null || data === undefined) {
            return `<span class="text-gray-400">${data}</span>`;
        }
        
        if (typeof data !== 'object') {
            return `<span class="text-green-600">${JSON.stringify(data)}</span>`;
        }
        
        if (Array.isArray(data)) {
            if (data.length === 0) {
                return `<span class="text-gray-400">[]</span>`;
            }
            
            const indent = '  '.repeat(pathPrefix.split('.').filter(p => p).length);
            const items = data.map((item, idx) => {
                const itemStr = this._formatDataForPreview(item, pathPrefix);
                return `<span class="text-gray-500">${indent}  </span>${itemStr}`;
            }).join('\n');
            return `<span class="text-gray-400">[</span>\n${items}\n<span class="text-gray-400">${indent}]</span>`;
        }
        
        const entries = Object.entries(data);
        if (entries.length === 0) {
            return `<span class="text-gray-400">{}</span>`;
        }
        
        const indent = '  '.repeat(pathPrefix.split('.').filter(p => p).length);
        const lines = entries.map(([key, value]) => {
            const fullPath = pathPrefix ? `${pathPrefix}.${key}` : key;
            const valueStr = typeof value === 'object' && value !== null 
                ? this._formatDataForPreview(value, fullPath)
                : `<span class="text-green-600">${JSON.stringify(value)}</span>`;
            
            const copyBtn = typeof value !== 'object' || value === null
                ? `<button onclick="app.copyDataPath('${fullPath}')" class="ml-2 text-xs text-blue-500 hover:text-blue-700" title="复制路径"><i class="fas fa-copy"></i></button>`
                : '';
            
            return `<span class="text-gray-500">${indent}  </span><span class="text-purple-600 cursor-pointer hover:underline" onclick="app.copyDataPath('${fullPath}')" title="点击复制路径">${key}</span>${copyBtn}: ${valueStr}`;
        }).join('\n');
        
        return `<span class="text-gray-400">{</span>\n${lines}\n<span class="text-gray-400">${indent}}</span>`;
    }
    
    copyDataPath(path) {
        path = path.replace(/\[\d+\]/g, '');
        const text = `{{data.${path}}}`;
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(() => {
                UiHelpers.setStatus(`已复制: ${text}`);
            }).catch(err => {
                console.error('复制失败:', err);
                this._fallbackCopy(text);
            });
        } else {
            this._fallbackCopy(text);
        }
    }
    
    _fallbackCopy(text) {
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.position = 'fixed';
        textarea.style.left = '-9999px';
        document.body.appendChild(textarea);
        textarea.select();
        try {
            document.execCommand('copy');
            UiHelpers.setStatus(`已复制: ${text}`);
        } catch (err) {
            console.error('复制失败:', err);
            alert(`请手动复制: ${text}`);
        }
        document.body.removeChild(textarea);
    }
    
    _updateDataPreviewButton() {
        const btn = document.getElementById('dataPreviewBtn');
        if (this.ticketData && Object.keys(this.ticketData).length > 0) {
            btn.classList.remove('hidden');
        } else {
            btn.classList.add('hidden');
        }
    }

    resubmitFeedback(idx) {
        this.openFeedbackModal(idx);
    }

    openFeedbackModal(idx) {
        const result = this.auditResults[idx];
        if (!result || !result._feedbackId) return;

        this._feedbackModalIdx = idx;
        document.getElementById('feedbackModalRuleName').textContent = result.ruleName || '';

        document.querySelectorAll('input[name="feedbackType"]').forEach(r => r.checked = false);
        document.getElementById('feedbackReasonArea').classList.add('hidden');
        document.getElementById('feedbackReasonInput').value = '';

        UiHelpers.toggleModal('feedbackModal', true);
    }

    closeFeedbackModal() {
        this._feedbackModalIdx = null;
        UiHelpers.toggleModal('feedbackModal', false);
    }

    onFeedbackTypeChange() {
        const selected = document.querySelector('input[name="feedbackType"]:checked');
        if (selected && selected.value === 'INACCURATE') {
            document.getElementById('feedbackReasonArea').classList.remove('hidden');
        } else {
            document.getElementById('feedbackReasonArea').classList.add('hidden');
        }
    }

    async submitFeedbackFromModal() {
        const idx = this._feedbackModalIdx;
        if (idx === null || idx === undefined) return;

        const result = this.auditResults[idx];
        if (!result || !result._feedbackId) return;

        const selected = document.querySelector('input[name="feedbackType"]:checked');
        if (!selected) {
            alert('请选择反馈结果（准确/不准确）');
            return;
        }

        const feedbackType = selected.value;
        const reason = document.getElementById('feedbackReasonInput').value.trim();

        if (feedbackType === 'INACCURATE' && !reason) {
            alert('请填写不准确的原因');
            return;
        }

        try {
            if (this.auditMode === 'ticket') {
                await FeedbackAPI.submitOrderFeedback(result._feedbackId, feedbackType, reason);
            } else {
                await FeedbackAPI.submitFeedback(result._feedbackId, feedbackType, reason);
            }
            result._feedbackType = feedbackType;
            this.closeFeedbackModal();

            const label = document.getElementById('feedback-label-' + idx);
            if (label) {
                if (feedbackType === 'ACCURATE') {
                    label.innerHTML = '<span class="text-green-600"><i class="fas fa-check mr-1"></i>准确</span>';
                } else {
                    label.innerHTML = '<span class="text-red-600"><i class="fas fa-times mr-1"></i>不准确</span>';
                }
            }
        } catch (err) {
            alert('提交反馈失败: ' + err.message);
        }
    }

    _getDateRange() {
        const startEl = document.getElementById('statsStartDate');
        const endEl = document.getElementById('statsEndDate');
        if (startEl && endEl) {
            return { startDate: startEl.value, endDate: endEl.value };
        }
        if (this._statsDateRangeCache) {
            return { startDate: this._statsDateRangeCache.startDate, endDate: this._statsDateRangeCache.endDate };
        }
        return { startDate: '', endDate: '' };
    }

    _getStatsAuditType() {
        return this.auditMode === 'ticket' ? 'order' : 'document';
    }

    _buildStatsUrl(baseUrl, includeDate = true) {
        const { startDate, endDate } = this._getDateRange();
        const params = ['auditType=' + encodeURIComponent(this._getStatsAuditType())];
        if (includeDate && startDate) params.push('startDate=' + encodeURIComponent(startDate));
        if (includeDate && endDate) params.push('endDate=' + encodeURIComponent(endDate));
        return baseUrl + '?' + params.join('&');
    }

    _refreshStats() {
        const content = document.getElementById('statsAnalysisContent');
        content.innerHTML = '<div class="text-center text-gray-400 py-8"><i class="fas fa-spinner fa-spin text-3xl mb-2"></i><p class="text-sm">加载中...</p></div>';
        this.showStatsAnalysis();
    }

    _applyStatsDateFilter() {
        const startEl = document.getElementById('statsStartDate');
        const endEl = document.getElementById('statsEndDate');
        this._statsDateRangeCache = {
            startDate: startEl ? startEl.value : '',
            endDate: endEl ? endEl.value : '',
            _isManual: true
        };
        this._refreshStats();
    }

    _setQuickDate(days) {
        const today = new Date();
        const fmt = d => {
            const m = String(d.getMonth() + 1).padStart(2, '0');
            const day = String(d.getDate()).padStart(2, '0');
            return d.getFullYear() + '-' + m + '-' + day;
        };
        let startDate, endDate = fmt(today);
        if (days === -1) {
            startDate = fmt(new Date(today.getFullYear(), today.getMonth(), 1));
            this._statsDateRangeCache = { startDate, endDate, _quick: -1, _isManual: true };
        } else {
            const d = new Date(today);
            d.setDate(d.getDate() - days + 1);
            startDate = fmt(d);
            this._statsDateRangeCache = { startDate, endDate, _quick: days, _isManual: true };
        }
        this._refreshStats();
    }

    _clearStatsDate() {
        this._statsDateRangeCache = null;
        this._refreshStats();
    }

    async showStatsAnalysis() {
        UiHelpers.toggleModal('statsAnalysisModal', true);
        const content = document.getElementById('statsAnalysisContent');
        content.innerHTML = '<div class="text-center text-gray-400 py-8"><i class="fas fa-spinner fa-spin text-3xl mb-2"></i><p class="text-sm">加载中...</p></div>';

        const requestToken = ++this._statsRequestToken;

        try {
            const [statsRes, dailyRes, groupsRes, allTimeRes, sourceRes] = await Promise.all([
                fetch(this._buildStatsUrl('/api/stats')),
                fetch(this._buildStatsUrl('/api/stats/daily')),
                fetch('/api/config/rules'),
                fetch(this._buildStatsUrl('/api/stats', false)),
                fetch(this._buildStatsUrl('/api/stats/sources'))
            ]);

            const stats = statsRes.ok ? await statsRes.json() : { totalCount: 0, todayCount: 0 };
            const dailyData = dailyRes.ok ? await dailyRes.json() : [];
            const groupData = groupsRes.ok ? await groupsRes.json() : { groups: [] };
            const allTimeStats = allTimeRes.ok ? await allTimeRes.json() : { totalCount: 0 };
            const sourceStats = sourceRes.ok ? await sourceRes.json() : {};

            this._statsGroupData = groupData.groups || [];

            const { startDate, endDate } = this._getDateRange();

            let groupOptionsHtml = '<option value="">-- 请选择规则组 --</option>';
            this._statsGroupData.forEach(g => {
                groupOptionsHtml += `<option value="${g.groupId}">${g.name}</option>`;
            });

            const chartHtml = this._renderDailyChart(dailyData);

            const hasFilter = !!(startDate || endDate);

            // 丢弃过期请求的结果（防止并发覆盖）
            if (requestToken !== this._statsRequestToken) return;

            const allTimeTotal = (allTimeStats.totalCount || 0) + (this._getStatsAuditType() === 'order' ? 0 : 1000);
            const rangeTotal = stats.totalCount || 0;
            const statsScopeLabel = this._getStatsAuditType() === 'order' ? '工单审核统计' : '文档审核统计';

            content.innerHTML = `
                <div class="flex items-center justify-between mb-4">
                    <div class="text-xs text-gray-400">${statsScopeLabel}</div>
                    <div class="flex items-center gap-1">
                        <button onclick="app._setQuickDate(7)" class="px-2 py-1 text-xs rounded ${!hasFilter || (this._statsDateRangeCache && this._statsDateRangeCache._quick === 7) ? 'bg-blue-100 text-blue-700' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'}">近7天</button>
                        <button onclick="app._setQuickDate(30)" class="px-2 py-1 text-xs rounded ${this._statsDateRangeCache && this._statsDateRangeCache._quick === 30 ? 'bg-blue-100 text-blue-700' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'}">近30天</button>
                        <button onclick="app._setQuickDate(-1)" class="px-2 py-1 text-xs rounded ${this._statsDateRangeCache && this._statsDateRangeCache._quick === -1 ? 'bg-blue-100 text-blue-700' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'}">本月</button>
                        <span class="text-gray-300 mx-1">|</span>
                        <label class="text-xs text-gray-500">从</label>
                        <input type="date" id="statsStartDate" value="${startDate}"
                            class="w-32 px-2 py-1 border border-gray-300 rounded text-xs focus:ring-2 focus:ring-blue-500 focus:border-blue-500">
                        <label class="text-xs text-gray-500">至</label>
                        <input type="date" id="statsEndDate" value="${endDate}"
                            class="w-32 px-2 py-1 border border-gray-300 rounded text-xs focus:ring-2 focus:ring-blue-500 focus:border-blue-500">
                        <button onclick="app._applyStatsDateFilter()" class="px-3 py-1 text-xs rounded bg-blue-500 text-white hover:bg-blue-600"><i class="fas fa-search mr-1"></i>查询</button>
                        ${hasFilter ? '<button onclick="app._clearStatsDate()" class="px-2 py-1 text-xs rounded text-red-500 hover:bg-red-50"><i class="fas fa-times"></i></button>' : ''}
                    </div>
                </div>
                <div class="bg-gradient-to-r from-blue-50 to-purple-50 rounded-xl p-4">
                    <h3 class="text-sm font-semibold text-gray-700 mb-3"><i class="fas fa-chart-simple text-blue-500 mr-1"></i>审核统计</h3>
                    <div class="grid grid-cols-4 gap-3 mb-3">
                        <div class="bg-white rounded-lg p-3 shadow-sm border border-blue-100">
                            <div class="flex items-center gap-1.5 mb-1">
                                <i class="fas fa-chart-line text-blue-500 text-xs"></i>
                                <span class="text-xs text-gray-500">上线以来累计审核次数</span>
                            </div>
                            <div class="text-xl font-bold text-blue-600">${allTimeTotal}</div>
                        </div>
                        <div class="bg-white rounded-lg p-3 shadow-sm border border-purple-100">
                            <div class="flex items-center gap-1.5 mb-1">
                                <i class="fas fa-calendar-day text-purple-500 text-xs"></i>
                                <span class="text-xs text-gray-500">审核次数</span>
                            </div>
                            <div class="text-xl font-bold text-purple-600">${rangeTotal}</div>
                        </div>
                        <div class="bg-white rounded-lg p-3 shadow-sm border border-green-100">
                            <div class="flex items-center gap-1.5 mb-1">
                                <i class="fas fa-mouse-pointer text-green-500 text-xs"></i>
                                <span class="text-xs text-gray-500">页面点击</span>
                            </div>
                            <div class="text-xl font-bold text-green-600">${sourceStats.clickCount || 0}</div>
                        </div>
                        <div class="bg-white rounded-lg p-3 shadow-sm border border-orange-100">
                            <div class="flex items-center gap-1.5 mb-1">
                                <i class="fas fa-cloud-upload-alt text-orange-500 text-xs"></i>
                                <span class="text-xs text-gray-500">异步工单</span>
                            </div>
                            <div class="text-xl font-bold text-orange-600">${sourceStats.asyncCount || 0}</div>
                        </div>
                    </div>
                    ${chartHtml}
                </div>

                <div class="bg-white rounded-xl border border-gray-200 p-4">
                    <h3 class="text-sm font-semibold text-gray-700 mb-3"><i class="fas fa-layer-group text-blue-500 mr-1"></i>规则组统计</h3>
                    <div class="mb-3">
                        <select id="statsGroupSelect" onchange="app.onStatsGroupChange()" class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500">
                            ${groupOptionsHtml}
                        </select>
                    </div>
                    <div id="groupStatsPanel">
                        <div class="text-center text-gray-400 py-4 text-sm">
                            <i class="fas fa-hand-pointer mr-1"></i>请选择规则组查看统计信息
                        </div>
                    </div>
                </div>
            `;
        } catch (err) {
            if (requestToken !== this._statsRequestToken) return;
            content.innerHTML = '<div class="text-center text-red-500 py-8"><i class="fas fa-exclamation-triangle text-3xl mb-2"></i><p class="text-sm">加载失败: ' + err.message + '</p></div>';
        }
    }

    _renderDailyChart(dailyData) {
        if (!dailyData || dailyData.length === 0) {
            return '<div class="text-center text-gray-400 py-2 text-xs">暂无统计数据</div>';
        }
        const maxCount = Math.max(...dailyData.map(d => d.count), 1);
        let barsHtml = dailyData.map((d, i) => {
            const height = Math.max(Math.round(d.count / maxCount * 100), d.count > 0 ? 4 : 0);
            return `<div class="flex-1 flex flex-col items-center gap-1">
                <span class="text-xs font-medium text-gray-700">${d.count}</span>
                <div class="w-full bg-blue-100 rounded-t-md relative" style="height:${height}px;min-width:20px">
                    <div class="absolute inset-x-0 bottom-0 bg-blue-500 rounded-t-md" style="height:${height}px"></div>
                </div>
                <span class="text-xs text-gray-400">${d.date}</span>
            </div>`;
        }).join('');

        return `<div>
            <h4 class="text-xs font-medium text-gray-600 mb-2">每日调用量</h4>
            <div class="flex items-end gap-1 h-[140px] px-2">${barsHtml}</div>
        </div>`;
    }

    async onStatsGroupChange() {
        const groupId = document.getElementById('statsGroupSelect').value;
        const groupPanel = document.getElementById('groupStatsPanel');

        if (!groupId) {
            groupPanel.innerHTML = '<div class="text-center text-gray-400 py-4 text-sm"><i class="fas fa-hand-pointer mr-1"></i>请选择规则组查看统计信息</div>';
            return;
        }

        const group = this._statsGroupData.find(g => g.groupId === groupId);
        const expectedScope = this._getStatsAuditType() === 'order' ? 'ticket' : 'document';
        const rules = ((group && group.rules) || [])
            .filter(rule => {
                if (!rule.auditScope) return expectedScope === 'document';
                return String(rule.auditScope).toLowerCase() === expectedScope;
            })
            .slice()
            .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));

        groupPanel.innerHTML = '<div class="text-center text-gray-400 py-4"><i class="fas fa-spinner fa-spin text-xl mb-1"></i><p class="text-xs">加载中...</p></div>';

        try {
            const [gsRes, ...ruleStatsResults] = await Promise.all([
                fetch(this._buildStatsUrl(`/api/stats/group/${groupId}`)),
                ...rules.map(r => {
                    const { startDate, endDate } = this._getDateRange();
                    return FeedbackAPI.getRuleStats(r.id, startDate, endDate, this._getStatsAuditType()).catch(() => null);
                })
            ]);

            if (!gsRes.ok) throw new Error('获取规则组统计失败');
            const gs = await gsRes.json();

            const avgSec = gs.avgDurationMs > 0 ? (gs.avgDurationMs / 1000).toFixed(1) : '-';

            let tableRows = '';
            if (rules.length > 0) {
                tableRows = rules.map((rule, i) => {
                    const rs = ruleStatsResults[i];
                    const auditCount = rs ? rs.totalAuditCount || 0 : 0;
                    const passRate = rs && rs.passRate != null ? Math.round(rs.passRate) : '-';
                    const inaccurateCount = rs ? rs.inaccurateCount || 0 : 0;

                    const inaccurateList = (rs && rs.recentFeedbacks || [])
                        .filter(f => f.feedbackType === 'INACCURATE');

                    let reasonHtml = '';
                    if (inaccurateList.length > 0) {
                        reasonHtml = `<div class="min-w-[260px] max-w-[360px] space-y-1">
                            ${inaccurateList.map((f, idx) => this._renderFeedbackReasonItem(f, idx)).join('')}
                        </div>`;
                    } else {
                        reasonHtml = '<span class="text-xs text-gray-400">-</span>';
                    }

                    return `<tr class="border-b border-gray-100 hover:bg-gray-50 transition-colors">
                        <td class="py-2.5 px-3 text-sm text-gray-900 font-medium">${rule.name}</td>
                        <td class="py-2.5 px-3 text-sm text-gray-700 text-center">${auditCount}</td>
                        <td class="py-2.5 px-3 text-sm text-center ${passRate !== '-' ? (passRate >= 80 ? 'text-green-600' : passRate >= 50 ? 'text-orange-500' : 'text-red-600') : 'text-gray-400'}">${passRate}%</td>
                        <td class="py-2.5 px-3 text-sm text-center ${inaccurateCount > 0 ? 'text-red-600 font-medium' : 'text-gray-400'}">${inaccurateCount > 0 ? inaccurateCount : 0}</td>
                        <td class="py-2.5 px-3">${reasonHtml}</td>
                        <td class="py-2.5 px-3 text-center">
                            <button data-rule-prompt="${encodeURIComponent(rule.prompt || '')}" onclick="app.showRuleFailures(${rule.id},'${rule.name.replace(/'/g, "\\'")}',this)" class="text-xs text-blue-600 hover:text-blue-800 hover:underline ${auditCount > 0 ? '' : 'opacity-30 pointer-events-none'}">详情</button>
                        </td>
                    </tr>`;
                }).join('');
            }

            groupPanel.innerHTML = `
                <div class="grid grid-cols-3 gap-2 mb-4">
                    <div class="bg-blue-50 rounded-lg p-3 text-center">
                        <div class="text-xl font-bold text-blue-600">${gs.totalAuditCount || 0}</div>
                        <div class="text-xs text-blue-500">累计审核</div>
                    </div>
                    <div class="bg-red-50 rounded-lg p-3 text-center">
                        <div class="text-xl font-bold text-red-600">${gs.failCount || 0}</div>
                        <div class="text-xs text-red-500">审核不通过</div>
                    </div>
                    <div class="bg-purple-50 rounded-lg p-3 text-center">
                        <div class="text-xl font-bold text-purple-600">${avgSec === '-' ? '-' : avgSec + 's'}</div>
                        <div class="text-xs text-purple-500">平均耗时</div>
                    </div>
                </div>
                ${rules.length > 0 ? `
                <div class="overflow-x-auto">
                    <table class="w-full text-left border-collapse">
                        <thead>
                            <tr class="bg-gray-50 text-xs font-medium text-gray-500 uppercase tracking-wider">
                                <th class="py-2.5 px-3">规则名称</th>
                                <th class="py-2.5 px-3 text-center">累计审核</th>
                                <th class="py-2.5 px-3 text-center">通过率</th>
                                <th class="py-2.5 px-3 text-center">不准确反馈</th>
                                <th class="py-2.5 px-3">最近不准确原因</th>
                                <th class="py-2.5 px-3 text-center">详情</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${tableRows}
                        </tbody>
                    </table>
                </div>
                ` : '<div class="text-center text-gray-400 py-4 text-sm">该规则组暂无规则</div>'}
            `;
        } catch (err) {
            groupPanel.innerHTML = '<div class="text-center text-red-500 py-4 text-sm">加载失败: ' + err.message + '</div>';
        }
    }

    closeStatsAnalysis() {
        UiHelpers.toggleModal('statsAnalysisModal', false);
    }

    _renderFeedbackReasonItem(item, index = 0) {
        const reason = this._normalizeInlineStatsText(item.reason || '未提供原因');
        const link = item.orderId
            ? this._renderOrderAuditLink(item.orderId, item.ts, '查看结论', true)
            : this._renderTicketAuditLink(item.ticketId, item.ts, '查看结论', true);
        return `<div class="flex items-center gap-2 text-xs leading-5 text-red-700">
            <span class="shrink-0 text-red-400">${index + 1}.</span>
            <span class="min-w-0 flex-1 truncate" title="${this._escapeStatsText(reason)}">${this._escapeStatsText(reason)}</span>
            ${link ? `<span class="shrink-0">${link}</span>` : ''}
        </div>`;
    }

    _renderTicketAuditLink(ticketId, ts, label = '查看审核结论', asButton = false) {
        if (!ticketId || !ts) return '';
        const href = `/?ticketId=${encodeURIComponent(ticketId)}&ts=${encodeURIComponent(ts)}`;
        const className = asButton
            ? 'inline-flex items-center gap-1 rounded border border-blue-200 bg-white px-1.5 py-0.5 text-[11px] font-medium leading-4 text-blue-600 hover:border-blue-300 hover:bg-blue-50'
            : 'inline-flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800 hover:underline';
        return `<a href="${href}" target="_blank" rel="noopener noreferrer" class="${className}">
            <i class="fas fa-arrow-up-right-from-square"></i>${this._escapeStatsText(label)}
        </a>`;
    }

    _renderOrderAuditLink(orderId, ts, label = '查看审核结论', asButton = false) {
        if (!orderId || !ts) return '';
        const href = `/?orderId=${encodeURIComponent(orderId)}&ts=${encodeURIComponent(ts)}`;
        const className = asButton
            ? 'inline-flex items-center gap-1 rounded border border-blue-200 bg-white px-1.5 py-0.5 text-[11px] font-medium leading-4 text-blue-600 hover:border-blue-300 hover:bg-blue-50'
            : 'inline-flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800 hover:underline';
        return `<a href="${href}" target="_blank" rel="noopener noreferrer" class="${className}">
            <i class="fas fa-arrow-up-right-from-square"></i>${this._escapeStatsText(label)}
        </a>`;
    }

    _renderIssueDetail(issue, index) {
        return `<div class="mb-3 rounded-lg border border-red-100 bg-red-50/40 p-3">
            <div class="flex gap-2">
                <div class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-red-100 text-[11px] font-semibold text-red-700">${index + 1}</div>
                <div class="min-w-0 flex-1 space-y-2">
                    ${this._renderIssueField('位置', issue.location, 'text-gray-600')}
                    ${this._renderIssueField('问题', issue.problem, 'text-red-700')}
                    ${this._renderIssueField('建议', issue.suggestion, 'text-green-700')}
                </div>
            </div>
        </div>`;
    }

    _renderIssueField(label, value, colorClass) {
        const parts = this._splitIssueText(value);
        if (parts.length === 0) return '';
        const body = parts.length === 1
            ? this._escapeStatsText(parts[0])
            : `<div class="mt-1 space-y-1">${parts.map((part, i) => `
                <div class="flex gap-1.5">
                    <span class="shrink-0 text-gray-400">${i + 1}.</span>
                    <span>${this._escapeStatsText(part)}</span>
                </div>
            `).join('')}</div>`;
        return `<div class="text-xs ${colorClass}">
            <span class="font-medium">${label}：</span>${body}
        </div>`;
    }

    _splitIssueText(value) {
        const text = (value || '').toString().trim();
        if (!text) return [];
        return text
            .replace(/\r\n/g, '\n')
            .replace(/[；;]\s*/g, '\n')
            .replace(/\s+(?=(?:\d+[.、]|[一二三四五六七八九十]+[、.．]|[（(][一二三四五六七八九十\d]+[）)]))/g, '\n')
            .split('\n')
            .map(part => part.trim())
            .filter(Boolean);
    }

    _normalizeInlineStatsText(value) {
        return (value || '').toString().replace(/\s+/g, ' ').trim();
    }

    _escapeStatsText(value) {
        return (value || '').toString()
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    async showRuleFailures(ruleId, ruleName, btn) {
        const { startDate, endDate } = this._getDateRange();
        const params = [];
        if (startDate) params.push('startDate=' + encodeURIComponent(startDate));
        if (endDate) params.push('endDate=' + encodeURIComponent(endDate));
        params.push('auditType=' + encodeURIComponent(this._getStatsAuditType()));
        const qs = params.length > 0 ? '?' + params.join('&') : '';

        // 从按钮 data 属性读取规则描述
        const rulePrompt = btn ? decodeURIComponent(btn.getAttribute('data-rule-prompt') || '') : '';

        try {
            const res = await fetch(`/api/feedback/failures/${ruleId}${qs}`);
            if (!res.ok) throw new Error('获取失败');
            const data = await res.json();

            if (!data || data.length === 0) {
                alert('该规则暂无审核不通过的记录');
                return;
            }

            let issuesHtml = data.map((item, i) => {
                const time = item.createdAt ? new Date(item.createdAt).toLocaleString('zh-CN') : '';
                const confidence = item.confidence != null ? `${item.confidence}%` : '-';
                const summary = item.summary || '';
                const issues = item.issues || [];
                const ticketId = item.ticketId || '-';
                const orderId = item.orderId || '-';
                const ts = item.ts || '-';
                const auditBatchNo = item.auditBatchNo || '-';
                const ticketLink = item.orderId
                    ? this._renderOrderAuditLink(item.orderId, item.ts)
                    : this._renderTicketAuditLink(item.ticketId, item.ts);
                const feedbackReason = item.reason || '';
                const issuesList = issues.length > 0
                    ? issues.map((iss, issueIdx) => this._renderIssueDetail(iss, issueIdx)).join('')
                    : '<div class="text-xs text-gray-400 ml-3">暂无明细</div>';

                return `<div class="border border-gray-200 rounded-lg p-3 ${i > 0 ? 'mt-2' : ''}">
                    <div class="flex items-center justify-between mb-2">
                        <span class="text-xs text-gray-400">${time}</span>
                        <span class="text-xs text-gray-500">置信度：${confidence}</span>
                    </div>
                    <div class="mb-2 text-xs text-gray-500 bg-gray-50 rounded p-2">
                        <div class="grid grid-cols-1 sm:grid-cols-3 gap-1">
                            <div><span class="font-medium text-gray-600">${item.orderId ? 'orderId' : 'ticketId'}:</span> ${this._escapeStatsText(item.orderId ? orderId : ticketId)}</div>
                            <div><span class="font-medium text-gray-600">ts:</span> ${this._escapeStatsText(ts)}</div>
                            <div><span class="font-medium text-gray-600">batch:</span> ${this._escapeStatsText(auditBatchNo)}</div>
                        </div>
                        ${ticketLink ? `<div class="mt-2">${ticketLink}</div>` : ''}
                    </div>
                    ${feedbackReason ? `<div class="mb-2 rounded border border-orange-100 bg-orange-50 px-3 py-2 text-xs text-orange-800"><span class="font-medium">反馈意见：</span>${this._escapeStatsText(feedbackReason)}</div>` : ''}
                    ${summary ? `<div class="text-xs text-gray-700 mb-2">${this._escapeStatsText(summary)}</div>` : ''}
                    ${issuesList}
                </div>`;
            }).join('');

            const overlay = document.createElement('div');
            overlay.id = 'failureDetailModal';
            overlay.className = 'fixed inset-0 bg-black/50 backdrop-blur-sm z-[60] flex items-center justify-center p-4';
            overlay.innerHTML = `
                <div class="bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[80vh] flex flex-col">
                    <div class="p-4 border-b border-gray-100 flex justify-between items-center">
                        <h3 class="text-sm font-bold text-gray-900"><i class="fas fa-exclamation-triangle text-red-500 mr-2"></i>${this._escapeStatsText(ruleName)} - 不通过详情</h3>
                        <button onclick="document.getElementById('failureDetailModal').remove()" class="text-gray-400 hover:text-gray-600"><i class="fas fa-times"></i></button>
                    </div>
                    ${rulePrompt ? `<div class="px-4 pt-4 pb-0"><div class="bg-gray-50 rounded-lg p-3 text-xs text-gray-600 leading-relaxed border border-gray-200"><span class="font-medium text-gray-700">规则描述：</span>${this._escapeStatsText(rulePrompt)}</div></div>` : ''}
                    <div class="p-4 overflow-y-auto flex-1 space-y-2">${issuesHtml}</div>
                    <div class="p-3 border-t border-gray-100 flex justify-end">
                        <button onclick="document.getElementById('failureDetailModal').remove()" class="px-4 py-2 bg-gray-100 hover:bg-gray-200 rounded-lg text-xs">关闭</button>
                    </div>
                </div>`;
            document.body.appendChild(overlay);
        } catch (err) {
            alert('加载失败详情失败: ' + err.message);
        }
    }
}

const app = new SmartDocApp();
