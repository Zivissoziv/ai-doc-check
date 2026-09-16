const AuditMode = {
    DOCUMENT: 'document',
    BRIEF: 'brief',

    apply(app, mode) {
        const validMode = mode === this.BRIEF ? this.BRIEF : this.DOCUMENT;
        app.auditMode = validMode;
        localStorage.setItem('smartdoc_audit_mode', app.auditMode);

        const isBrief = app.auditMode === this.BRIEF;

        this.setModeButton('document', !isBrief);
        this.setModeButton('brief', isBrief);
        this.setText('leftPanelTitle', isBrief ? '搜索工单' : '文档结构');
        this.setText('tabPreviewText', isBrief ? '工单内容' : '文档预览');
        this.setText('tabCompareText', '结构对比');
        this.setText('tabAuditText', isBrief ? 'AI简报' : 'AI审核结果');
        this.setText('runAuditBtnText', isBrief ? 'AI评审' : 'AI审核');
        this.setText('wordCount', isBrief
            ? `字段: ${this.getTicketFieldCount(app.ticketData)}`
            : `字数: ${app.document?.text?.length || 0}`);

        this.toggle('uploadArea', !isBrief);
        this.toggle('dataSourceArea', !isBrief);
        this.toggle('orderSearchArea', isBrief);
        this.toggle('briefStyleMenuItem', isBrief);
        this.toggle('tab-briefHistory', isBrief);
        // 左侧栏左下角工单总条数：仅简报模式显示，有搜索结果时展示
        if (isBrief) {
            if (typeof app._updateOrderTotalCount === 'function') app._updateOrderTotalCount();
        } else {
            const footer = document.getElementById('orderTotalFooter');
            if (footer) footer.classList.add('hidden');
        }
        this.toggle('structureScore', !isBrief && !!app.document && !!app.template);
        this.toggle('structureDiff', !isBrief && document.getElementById('structureDiff')?.style.display !== 'none');
        this.toggle('tab-compare', !isBrief);
        // 规则训练已挪到变更简报：只允许用历史简报训练总结规则
        this.toggle('ruleTrainingMenuItem', isBrief);

        // 简报模式：切换规则弹窗标题与严重级别/触发条件显隐（总结规则无触发概念）
        const severityField = document.getElementById('ruleSeverityField');
        if (severityField) severityField.classList.toggle('hidden', isBrief);
        const triggerField = document.getElementById('ruleTriggerField');
        if (triggerField) triggerField.classList.toggle('hidden', isBrief);

        // 总结规则不引用变量：弹窗文案引导用户填写总结要点
        const promptLabel = document.getElementById('rulePromptLabel');
        const promptInput = document.getElementById('rulePrompt');
        const varHint = document.getElementById('ruleVariableHint');
        const nameInput = document.getElementById('ruleName');
        if (isBrief) {
            if (promptLabel) promptLabel.textContent = '总结要点（自然语言）';
            if (promptInput) promptInput.placeholder =
                '描述这条总结规则要覆盖的要点，AI 会围绕这些要点从工单内容中提炼信息。例如：总结本次变更涉及的容量调整，包括变更前后的对比与影响范围。';
            if (nameInput) nameInput.placeholder = '例如：容量变更说明';
            if (varHint) varHint.classList.add('hidden');
        } else {
            if (promptLabel) promptLabel.textContent = '审核内容描述（自然语言）';
            if (promptInput) promptInput.placeholder =
                '请检查文档中是否包含敏感词，如发现请指出具体位置和修改建议... 支持使用 {{data.工作表.列名}} 引用Excel数据或 {{data.字段名}} 引用工单数据';
            if (nameInput) nameInput.placeholder = '例如：检查敏感词';
            if (varHint) varHint.classList.remove('hidden');
        }

        if (isBrief) {
            this.toggle('view-preview', false);
            this.toggle('view-compare', false);
            this.toggle('structureTree', true);
            // 默认搜索时间范围：最近 7 天（小时级）
            if (!document.getElementById('orderSearchStart').value) {
                const toLocal = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}T${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
                const end = new Date();
                const start = new Date();
                start.setDate(start.getDate() - 7);
                document.getElementById('orderSearchStart').value = toLocal(start);
                document.getElementById('orderSearchEnd').value = toLocal(end);
            }
            document.getElementById('orderSearchResult').textContent =
                app.orderList && app.orderList.length > 0 ? `共 ${app.orderList.length} 条工单` : '输入起止时间后点击搜索';
            BriefView.renderOrderList(app.orderList || [], app.orderId);
            BriefView.renderBrief(app.briefContent, 'auditResults');
            UiHelpers.switchTab('ticket');
        } else {
            this.toggle('view-ticket', false);
            TreeRenderer.render(app.document?.tree || app.template?.tree || [], 'structureTree');
            UiHelpers.switchTab('preview');
            this._restoreDocumentAuditResults(app);
        }
    },

    /**
     * 简报模式会把简报内容渲染进 auditResults 容器，切回文档审核时恢复原有审核结果；
     * 无审核结果时恢复初始空状态提示
     */
    _restoreDocumentAuditResults(app) {
        const container = document.getElementById('auditResults');
        if (!container) return;

        const hasResults = Array.isArray(app.auditResults) && app.auditResults.some(Boolean);
        if (!hasResults) {
            container.innerHTML = `
                <div class="text-center text-gray-400 mt-32">
                    <i class="fas fa-clipboard-check text-6xl mb-4 opacity-20"></i>
                    <p>点击"运行AI审核"开始检查</p>
                </div>`;
            return;
        }

        container.innerHTML = '<div class="space-y-4" id="auditList"></div>';
        const auditList = document.getElementById('auditList');
        app.auditResults.forEach((result, i) => {
            if (!result) return;
            const placeholder = document.createElement('div');
            placeholder.id = 'audit-rule-' + i;
            auditList.appendChild(placeholder);
            AiAudit.renderResult(result, placeholder, i);
        });
        const badge = document.getElementById('auditBadge');
        if (badge) badge.classList.remove('hidden');
    },

    setModeButton(mode, active) {
        const btn = document.getElementById(`mode-${mode}`);
        if (!btn) return;
        btn.className = active
            ? 'px-3 py-1.5 text-xs font-medium rounded-md bg-gray-900 text-white shadow-sm'
            : 'px-3 py-1.5 text-xs font-medium rounded-md text-gray-600 hover:bg-gray-100';
    },

    setText(id, value) {
        const el = document.getElementById(id);
        if (el) el.textContent = value;
    },

    toggle(id, show) {
        const el = document.getElementById(id);
        if (!el) return;
        el.classList.toggle('hidden', !show);
    },

    getTicketFieldCount(data) {
        return data && typeof data === 'object' && !Array.isArray(data) ? Object.keys(data).length : 0;
    }
};
