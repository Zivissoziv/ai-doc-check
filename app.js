pdfjsLib.GlobalWorkerOptions.workerSrc = './libs/pdf.worker.min.js';

class SmartDocApp {
    constructor() {
        this.template = null;
        this.document = null;
        this.excelData = null;
        this.rules = [];
        this.settings = JSON.parse(localStorage.getItem('smartdoc_settings') || '{"auditRole": "专业文档审核专家"}');
        this.currentEditingRule = null;
        this.auditResults = [];
        this.ruleGroups = [];
        this.currentRuleGroup = null;
        this.isAuditing = false;
        
        this.init();
    }
    
    async init() {
        await this.loadRuleGroups();
        await this.loadPresetConfig();
        this.loadSettings();
        this.updateApiStatus();
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
                const rules = await RulesManager.loadFromServer(this.currentRuleGroup);
                this.rules = rules || [];
                RulesManager.save(this.rules);
                this.renderRules();
            }
        } catch (err) {
            console.error('加载规则组失败:', err);
            UiHelpers.setStatus('加载规则组失败: ' + err.message);
        }
    }
    
    async loadPresetConfig() {
        const apiConfig = await ConfigLoader.loadApiConfig();
        if (apiConfig) {
            this.settings = apiConfig;
        }
        
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
    
    showTemplateModal() {
        UiHelpers.toggleModal('templateModal', true);
    }
    
    closeTemplateModal() {
        UiHelpers.toggleModal('templateModal', false);
    }
    
    async selectPresetTemplate(fileName) {
        this.closeTemplateModal();
        await this.loadPresetTemplate(fileName);
    }
    
    async loadPresetTemplate(fileName) {
        UiHelpers.setStatus('正在加载预设模板...', true);
        const file = await ConfigLoader.loadPresetTemplate(fileName);
        if (file) {
            this.template = await DocumentParser.parse(file);
            TreeRenderer.render(this.template?.tree || [], 'structureTree');
            UiHelpers.setStatus(`模板已加载: ${fileName}`);
            this.updateTemplateBtn(true, fileName);
            this.compareStructure();
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
            TreeRenderer.render(this.template?.tree || [], 'structureTree');
            UiHelpers.setStatus(`模板已加载: ${file.name}`);
            this.updateTemplateBtn(true, file.name);
            this.compareStructure();
        } catch (err) {
            alert('解析失败: ' + err.message);
            UiHelpers.setStatus('就绪');
        }
        input.value = '';
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
            document.getElementById('excelLabel').textContent = file.name;
            document.getElementById('insertVarBtn').style.display = 'inline';
            UiHelpers.setStatus(`Excel已加载: ${file.name}`);
        } catch (err) {
            alert('Excel解析失败: ' + err.message);
        }
    }
    
    updateTemplateBtn(loaded, fileName = '') {
        const btn = document.getElementById('templateBtn');
        const icon = document.getElementById('templateBtnIcon');
        const title = document.getElementById('templateBtnTitle');
        const desc = document.getElementById('templateBtnDesc');
        
        if (loaded) {
            btn.className = 'w-full flex items-center gap-2 p-3 bg-blue-50 border border-blue-200 rounded-lg cursor-pointer hover:bg-blue-100 transition-colors';
            icon.className = 'fas fa-file-import text-blue-600';
            title.className = 'font-medium text-blue-900';
            title.textContent = '模板已加载';
            desc.className = 'text-xs text-blue-600';
            desc.textContent = fileName.length > 20 ? fileName.substring(0, 20) + '...' : fileName;
        } else {
            btn.className = 'w-full flex items-center gap-2 p-3 bg-gray-50 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 transition-colors';
            icon.className = 'fas fa-file-import text-gray-400';
            title.className = 'font-medium text-gray-700';
            title.textContent = '选择模板';
            desc.className = 'text-xs text-gray-500';
            desc.textContent = '示例模板或上传文件';
        }
    }
    
    updateDocBtn(loaded, fileName = '') {
        const btn = document.getElementById('docBtn');
        const icon = document.getElementById('docBtnIcon');
        const title = document.getElementById('docBtnTitle');
        const desc = document.getElementById('docBtnDesc');
        
        if (loaded) {
            btn.className = 'flex items-center gap-2 p-3 bg-blue-50 border border-blue-200 rounded-lg cursor-pointer hover:bg-blue-100 transition-colors';
            icon.className = 'fas fa-file-alt text-blue-600';
            title.className = 'font-medium text-blue-900';
            title.textContent = '文档已加载';
            desc.className = 'text-xs text-blue-600';
            desc.textContent = fileName.length > 20 ? fileName.substring(0, 20) + '...' : fileName;
        } else {
            btn.className = 'flex items-center gap-2 p-3 bg-gray-50 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 transition-colors';
            icon.className = 'fas fa-file-alt text-gray-400';
            title.className = 'font-medium text-gray-700';
            title.textContent = '上传待审文档';
            desc.className = 'text-xs text-gray-500';
            desc.textContent = '需要检查的文件';
        }
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
                const rules = await RulesManager.loadFromServer(groupId);
                this.rules = rules || [];
                RulesManager.save(this.rules);
                this.renderRules();
                UiHelpers.setStatus(`已加载规则组: ${group.name}`);
            } catch (err) {
                UiHelpers.setStatus(`规则组加载失败: ${err.message}`);
            }
        }
    }
    
    async toggleRuleStatus(idx) {
        this.rules[idx].enabled = this.rules[idx].enabled === false ? true : false;
        this.renderRules();
        await this._autoSave();
    }

    async deleteRule(idx) {
        const rule = this.rules[idx];
        if (!confirm(`确定要删除规则"${rule.name}"吗？此操作不可恢复！`)) {
            return;
        }

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
    
    closeRuleModal() {
        UiHelpers.toggleModal('ruleModal', false);
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
            name, 
            prompt, 
            severity, 
            id: Date.now(),
            enabled: this.currentEditingRule !== null ? (this.rules[this.currentEditingRule].enabled !== false) : true
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
    
    showCreateGroupModal() {
        this._groupModalMode = 'create';
        document.getElementById('groupModalTitle').textContent = '新建规则组';
        document.getElementById('groupModalBtn').textContent = '创建';
        document.getElementById('groupIdField').style.display = 'block';
        document.getElementById('groupId').value = '';
        document.getElementById('groupId').disabled = false;
        document.getElementById('groupName').value = '';
        UiHelpers.toggleModal('groupModal', true);
    }
    
    showEditGroupModal() {
        if (!this.currentRuleGroup) {
            alert('请先选择规则组');
            return;
        }
        
        const group = this.ruleGroups.find(g => g.id === this.currentRuleGroup);
        if (!group) return;
        
        this._groupModalMode = 'edit';
        document.getElementById('groupModalTitle').textContent = '编辑规则组';
        document.getElementById('groupModalBtn').textContent = '保存';
        document.getElementById('groupIdField').style.display = 'none';
        document.getElementById('groupId').value = group.id;
        document.getElementById('groupName').value = group.name;
        UiHelpers.toggleModal('groupModal', true);
    }
    
    closeGroupModal() {
        UiHelpers.toggleModal('groupModal', false);
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
                RulesManager.renderGroupSelector(this.ruleGroups, groupId, 'ruleGroupSelect');
                
                this.currentRuleGroup = groupId;
                RulesManager.setCurrentGroup(groupId);
                this.rules = [];
                this.renderRules();
                
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
                if (group) {
                    group.name = groupName;
                }
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
            
            const rules = await RulesManager.loadFromServer(this.currentRuleGroup);
            this.rules = rules || [];
            RulesManager.save(this.rules);
            this.renderRules();
            
            UiHelpers.setStatus('规则组已删除');
        } catch (err) {
            alert('删除失败: ' + err.message);
        }
    }
    
    insertExcelVar() {
        if (!this.excelData) return;
        
        const container = document.getElementById('excelSheets');
        container.innerHTML = this.excelData.sheets.map(sheet => `
            <div class="mb-3">
                <div class="font-medium text-sm text-gray-700 mb-1">${sheet.name}</div>
                <div class="grid grid-cols-2 gap-2">
                    ${sheet.headers.map((h, i) => `
                        <button onclick="app.insertVar('{{excel.${sheet.name}.${h}}}')" 
                            class="text-xs p-2 text-left bg-gray-50 hover:bg-blue-50 rounded border border-gray-200 hover:border-blue-300 truncate">
                            ${h || `列${i+1}`}
                        </button>
                    `).join('')}
                </div>
            </div>
        `).join('');
        
        UiHelpers.toggleModal('excelVarModal', true);
    }
    
    insertVar(varStr) {
        const textarea = document.getElementById('rulePrompt');
        const start = textarea.selectionStart;
        const end = textarea.selectionEnd;
        const text = textarea.value;
        textarea.value = text.substring(0, start) + varStr + text.substring(end);
        textarea.selectionStart = textarea.selectionEnd = start + varStr.length;
        UiHelpers.toggleModal('excelVarModal', false);
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
        if (!this.settings.apiKey) {
            alert('请先配置API密钥');
            this.toggleSettings();
            return;
        }
        
        this.isAuditing = true;
        const btn = document.getElementById('runAuditBtn');
        btn.disabled = true;
        btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 审核中...';
        
        UiHelpers.setStatus('正在运行AI批量审核...', true);
        this.auditResults = [];
        const resultsContainer = document.getElementById('auditResults');
        resultsContainer.innerHTML = '<div class="space-y-4" id="auditList"></div>';
        
        try {
            const auditList = document.getElementById('auditList');

            // 先按规则顺序创建占位容器
            const placeholders = activeRules.map((rule, i) => {
                const div = document.createElement('div');
                div.id = 'audit-rule-' + i;
                auditList.appendChild(div);
                return div;
            });

            // 使用批量审核模式：只调用一次模型
            const batchPrompt = AiAudit.buildBatchPrompt(activeRules, this.document.text, this.excelData);
            const batchResults = await AiAudit.callBatchLLM(batchPrompt, activeRules, this.settings);
            
            // 渲染所有结果
            batchResults.forEach((result, i) => {
                this.auditResults[i] = result;
                AiAudit.renderResult(result, placeholders[i]);
            });

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
    
    toggleSettings() {
        const modal = document.getElementById('settingsModal');
        modal.classList.toggle('hidden');
    }
    
    saveSettings() {
        this.settings = {
            provider: 'custom',
            endpoint: document.getElementById('apiEndpoint').value,
            apiKey: document.getElementById('apiKey').value,
            model: document.getElementById('apiModel').value,
            auditRole: document.getElementById('auditRole').value || '专业文档审核专家'
        };
        localStorage.setItem('smartdoc_settings', JSON.stringify(this.settings));
        UiHelpers.toggleModal('settingsModal', false);
        this.updateApiStatus();
        alert('设置已保存');
    }
    
    loadSettings() {
        if (this.settings.apiKey) {
            document.getElementById('apiEndpoint').value = this.settings.endpoint || '';
            document.getElementById('apiKey').value = this.settings.apiKey || '';
            document.getElementById('apiModel').value = this.settings.model || 'deepseek-chat';
            document.getElementById('auditRole').value = this.settings.auditRole || '专业文档审核专家';
        }
    }
    
    updateApiStatus() {
        UiHelpers.updateApiStatus(!!this.settings.apiKey);
    }
    
    async testConnection() {
        const btn = event.target;
        const originalText = btn.textContent;
        btn.textContent = '测试中...';
        btn.disabled = true;
        
        try {
            const settings = {
                endpoint: document.getElementById('apiEndpoint').value,
                apiKey: document.getElementById('apiKey').value,
                model: document.getElementById('apiModel').value
            };
            
            const response = await AiAudit.testConnection(settings);
            
            if (response.ok) {
                alert('连接成功！');
            } else {
                const errorData = await response.text();
                alert(`连接失败: ${response.status} - ${response.statusText}\n${errorData}`);
            }
        } catch (err) {
            alert('连接错误: ' + err.message);
        } finally {
            btn.textContent = originalText;
            btn.disabled = false;
        }
    }
    
    switchTab(tab) {
        UiHelpers.switchTab(tab);
    }
    
    scrollToNode(nodeId) {
        UiHelpers.scrollToNode(nodeId);
    }
    
    setStatus(text, loading = false) {
        UiHelpers.setStatus(text, loading);
    }
    
    exportReport() {
        if (!this.document) {
            alert('无审核数据可导出');
            return;
        }
        
        const report = {
            timestamp: new Date().toISOString(),
            document: this.document.name,
            template: this.template?.name || '无',
            structureScore: document.getElementById('structureScore')?.textContent || 'N/A',
            auditResults: this.auditResults,
            excelData: this.excelData?.fileName || '无'
        };
        
        const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = `审核报告_${Date.now()}.json`;
        a.click();
    }
    
    showHelp() {
        UiHelpers.toggleModal('helpModal', true);
    }
    
    closeHelp() {
        UiHelpers.toggleModal('helpModal', false);
    }
}

const app = new SmartDocApp();
