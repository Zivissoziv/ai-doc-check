pdfjsLib.GlobalWorkerOptions.workerSrc = './libs/pdf.worker.min.js';

const API_DEFAULTS = {
    endpoint: 'https://api.deepseek.com/v1/chat/completions',
    model: 'deepseek-chat',
    auditRole: '专业文档审核专家'
};

const SETTINGS_DEFAULTS = {
    batchSize: 5,
    repeatPrompt: true
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
            repeatPrompt: SETTINGS_DEFAULTS.repeatPrompt
        };
        this.currentEditingRule = null;
        this.auditResults = [];
        this.ruleGroups = [];
        this.currentRuleGroup = null;
        this.isAuditing = false;

        this.init();
    }
    
    getUrlParams() {
        const params = new URLSearchParams(window.location.search);
        return Object.fromEntries(params.entries());
    }
    
    async init() {
        await this.loadRuleGroups();
        await this.loadPresetConfig();
        this.loadSettings();
        this.updateApiStatus();
        
        const params = this.getUrlParams();
        if (params.ticketId) {
            await this.loadFromTicket(params.ticketId);
        }
    }
    
    _base64ToBlob(base64) {
        const binary = atob(base64);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
        }
        return new Blob([bytes]);
    }
    
    async loadFromTicket(ticketId) {
        UiHelpers.setStatus(`正在加载工单 ${ticketId}...`, true);
        
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
                TreeRenderer.render(this.document?.tree || [], 'structureTree');
                UiHelpers.updateWordCount(this.document.text?.length || 0);
                this.updateDocBtn(true, this.document.name);
                this.compareStructure();
            }
            
            if (ticketInfo.data) {
                this.ticketData = ticketInfo.data;
                document.getElementById('excelLabel').textContent = '工单数据已加载';
            }
            
            UiHelpers.setStatus(`工单 ${ticketId} 已加载`);
        } catch (err) {
            console.error('加载工单失败:', err);
            UiHelpers.setStatus(`工单加载失败: ${err.message}`);
            alert(`加载工单失败: ${err.message}`);
        }
    }
    
    async loadRuleGroups() {
        try {
            const config = await RulesManager.getGroupsFromServer();
            this.ruleGroups = config.groups || [];
            this.defaultRuleGroup = config.defaultGroup;
            
            const savedGroup = RulesManager.getCurrentGroup();
            const groupExists = this.ruleGroups.some(g => g.id === savedGroup);
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
        const rules = await RulesManager.loadFromServer(this.currentRuleGroup);
        this.rules = rules || [];
        RulesManager.save(this.rules);
        this.renderRules();
        if (groupName) {
            UiHelpers.setStatus(`已加载规则组: ${groupName}`);
        }
    }
    
    _loadLocalSettings() {
        const localSettings = JSON.parse(localStorage.getItem('smartdoc_settings') || '{}');
        this.settings.batchSize = localSettings.batchSize ?? SETTINGS_DEFAULTS.batchSize;
        this.settings.repeatPrompt = localSettings.repeatPrompt ?? SETTINGS_DEFAULTS.repeatPrompt;
    }
    
    _applyApiConfig(apiConfig) {
        if (!apiConfig) return;
        this.settings.endpoint = apiConfig.endpoint || API_DEFAULTS.endpoint;
        this.settings.model = apiConfig.model || API_DEFAULTS.model;
        this.settings.auditRole = apiConfig.auditRole || API_DEFAULTS.auditRole;
        this.settings.hasApiKey = apiConfig.hasApiKey || false;
        this.settings.ticketEndpoint = apiConfig.ticketEndpoint || '';
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
            <button onclick="app.selectPresetTemplate('${t.file}')" 
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
    closeRuleModal() { this._handleModal(false, 'ruleModal'); }
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
        TreeRenderer.render(this.template?.tree || [], 'structureTree');
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
            TreeRenderer.render(this.document?.tree || [], 'structureTree');
            UiHelpers.updateWordCount(this.document.text?.length || 0);
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
        
        const result = StructureCompare.compare(this.template, this.document);
        if (result) {
            StructureCompare.renderDiffs(result.diffs, result.score);
            StructureCompare.renderCompareView(result.templateNodes, result.docNodes, result.matches);
        }
    }
    
    renderRules() {
        RulesManager.renderList(this.rules, 'rulesList');
    }
    
    async switchRuleGroup(groupId) {
        if (groupId === this.currentRuleGroup) return;
        
        this.currentRuleGroup = groupId;
        RulesManager.setCurrentGroup(groupId);
        
        const group = this.ruleGroups.find(g => g.id === groupId);
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
    
    addRule() {
        this.currentEditingRule = null;
        document.getElementById('ruleName').value = '';
        document.getElementById('rulePrompt').value = '';
        document.getElementById('ruleSeverity').value = 'warning';
        UiHelpers.toggleModal('ruleModal', true);
    }
    
    editRule(idx) {
        this.currentEditingRule = idx;
        const rule = this.rules[idx];
        document.getElementById('ruleName').value = rule.name;
        document.getElementById('rulePrompt').value = rule.prompt;
        document.getElementById('ruleSeverity').value = rule.severity;
        UiHelpers.toggleModal('ruleModal', true);
    }
    
    async saveRule() {
        const name = document.getElementById('ruleName').value.trim();
        const prompt = document.getElementById('rulePrompt').value.trim();
        const severity = document.getElementById('ruleSeverity').value;
        
        if (!name || !prompt) {
            alert('请填写完整信息');
            return;
        }
        
        const rule = { 
            name, prompt, severity, 
            id: Date.now(),
            enabled: this.currentEditingRule !== null ? this.rules[this.currentEditingRule].enabled !== false : true
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
        
        try {
            await RulesManager.saveToServer(this.currentRuleGroup, this.rules);
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
            const group = this.ruleGroups.find(g => g.id === this.currentRuleGroup);
            document.getElementById('groupId').value = group?.id || '';
        }
        
        document.getElementById('groupName').value = isCreate ? '' : (this.ruleGroups.find(g => g.id === this.currentRuleGroup)?.name || '');
        UiHelpers.toggleModal('groupModal', true);
    }
    
    showCreateGroupModal() { this._setupGroupModal('create'); }
    showEditGroupModal() {
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
                this.ruleGroups.push({ id: groupId, name: groupName });
                this.currentRuleGroup = groupId;
                RulesManager.setCurrentGroup(groupId);
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
                await RulesManager.saveToServer(groupId, this.rules, groupName);
                const group = this.ruleGroups.find(g => g.id === groupId);
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
        if (!this.currentRuleGroup) {
            alert('请先选择规则组');
            return;
        }
        
        if (this.ruleGroups.length <= 1) {
            alert('至少保留一个规则组');
            return;
        }
        
        const group = this.ruleGroups.find(g => g.id === this.currentRuleGroup);
        if (!confirm(`确定要删除规则组"${group?.name || this.currentRuleGroup}"吗？此操作不可恢复！`)) {
            return;
        }
        
        try {
            await RulesManager.deleteGroup(this.currentRuleGroup);
            this.ruleGroups = this.ruleGroups.filter(g => g.id !== this.currentRuleGroup);
            this.currentRuleGroup = this.ruleGroups[0]?.id;
            RulesManager.setCurrentGroup(this.currentRuleGroup);
            RulesManager.renderGroupSelector(this.ruleGroups, this.currentRuleGroup, 'ruleGroupSelect');
            await this.loadCurrentGroupRules();
            UiHelpers.setStatus('规则组已删除');
        } catch (err) {
            alert('删除失败: ' + err.message);
        }
    }
    
    _renderAuditResults(batchResults, placeholders, startIdx) {
        batchResults.forEach((result, i) => {
            this.auditResults[startIdx + i] = result;
            AiAudit.renderResult(result, placeholders[startIdx + i]);
        });
    }
    
    async runAudit() {
        if (this.isAuditing) {
            alert('正在审核中，请稍候...');
            return;
        }
        
        if (!this.document) {
            alert('请先上传待审文档');
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
        
        this.isAuditing = true;
        const btn = document.getElementById('runAuditBtn');
        btn.disabled = true;
        btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 审核中...';
        
        const batchSize = this.settings.batchSize || SETTINGS_DEFAULTS.batchSize;
        const totalBatches = batchSize > 0 ? Math.ceil(activeRules.length / batchSize) : 1;
        
        UiHelpers.setStatus(`正在运行AI批量审核（将分${totalBatches}批处理）...`, true);
        this.auditResults = [];
        const resultsContainer = document.getElementById('auditResults');
        resultsContainer.innerHTML = '<div class="space-y-4" id="auditList"></div>';
        
        try {
            const auditList = document.getElementById('auditList');
            const placeholders = activeRules.map((_, i) => {
                const div = document.createElement('div');
                div.id = 'audit-rule-' + i;
                auditList.appendChild(div);
                return div;
            });

            if (batchSize === 0) {
                const batchPrompt = AiAudit.buildBatchPrompt(activeRules, this.document.text, this.ticketData, this.settings.repeatPrompt);
                const batchResults = await AiAudit.callBatchLLM(batchPrompt, activeRules, this.settings);
                this._renderAuditResults(batchResults, placeholders, 0);
            } else {
                for (let batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
                    const startIdx = batchIndex * batchSize;
                    const endIdx = Math.min(startIdx + batchSize, activeRules.length);
                    const batchRules = activeRules.slice(startIdx, endIdx);
                    
                    UiHelpers.setStatus(`正在审核第 ${batchIndex + 1}/${totalBatches} 批（规则 ${startIdx + 1}-${endIdx}）...`, true);
                    UiHelpers.updateProgress(Math.round((batchIndex / totalBatches) * 100));
                    
                    const batchPrompt = AiAudit.buildBatchPrompt(batchRules, this.document.text, this.ticketData, this.settings.repeatPrompt);
                    const batchResults = await AiAudit.callBatchLLM(batchPrompt, batchRules, this.settings);
                    this._renderAuditResults(batchResults, placeholders, startIdx);
                    
                    if (batchIndex < totalBatches - 1) {
                        await new Promise(resolve => setTimeout(resolve, 500));
                    }
                }
            }

            UiHelpers.hideProgress();
            UiHelpers.setStatus(`审核完成，共检查 ${activeRules.length} 条规则`);
            document.getElementById('auditBadge').classList.remove('hidden');
            UiHelpers.switchTab('audit');
        } catch (err) {
            UiHelpers.setStatus('审核失败: ' + err.message);
            console.error('批量审核失败:', err);
        } finally {
            this.isAuditing = false;
            btn.disabled = false;
            btn.innerHTML = '<i class="fas fa-play"></i> 运行AI审核';
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
            this.settings.batchSize = batchSize;
            this.settings.repeatPrompt = repeatPrompt;

            localStorage.setItem('smartdoc_settings', JSON.stringify({ batchSize, repeatPrompt }));

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
    
    switchTab(tab) { UiHelpers.switchTab(tab); }
    scrollToNode(nodeId) { UiHelpers.scrollToNode(nodeId); }
    setStatus(text, loading = false) { UiHelpers.setStatus(text, loading); }
    exportHtmlReport() { ReportExporter.exportHtml(this.document, this.template, this.excelData, this.auditResults); }
    showHelp() { UiHelpers.toggleModal('helpModal', true); }
    closeHelp() { UiHelpers.toggleModal('helpModal', false); }
}

const app = new SmartDocApp();
