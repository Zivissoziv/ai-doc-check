const AuditMode = {
    DOCUMENT: 'document',
    TICKET: 'ticket',

    apply(app, mode) {
        app.auditMode = mode === this.TICKET ? this.TICKET : this.DOCUMENT;
        localStorage.setItem('smartdoc_audit_mode', app.auditMode);

        const isTicket = app.auditMode === this.TICKET;
        this.setModeButton('document', !isTicket);
        this.setModeButton('ticket', isTicket);
        this.setText('appTitle', '智能审核工具');
        this.setText('leftPanelTitle', isTicket ? '工单字段目录' : '文档结构');
        this.setText('tabPreviewText', isTicket ? '工单信息' : '文档预览');
        this.setText('tabCompareText', '结构对比');
        this.setText('tabAuditText', 'AI审核结果');
        this.setText('runAuditBtnText', 'AI审核');
        this.setText('wordCount', isTicket ? `字段: ${this.getTicketFieldCount(app.ticketData)}` : `字数: ${app.document?.text?.length || 0}`);

        this.toggle('uploadArea', !isTicket);
        this.toggle('dataSourceArea', !isTicket);
        this.toggle('structureScore', !isTicket && !!app.document && !!app.template);
        this.toggle('structureDiff', !isTicket && document.getElementById('structureDiff')?.style.display !== 'none');
        this.toggle('tab-compare', !isTicket);

        if (isTicket) {
            this.toggle('view-preview', false);
            this.toggle('view-compare', false);
            app.refreshTicketAuditView();
            UiHelpers.switchTab('ticket');
        } else {
            this.toggle('view-ticket', false);
            TreeRenderer.render(app.document?.tree || app.template?.tree || [], 'structureTree');
            UiHelpers.switchTab('preview');
        }
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
