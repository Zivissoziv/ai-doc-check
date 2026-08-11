const TicketAuditView = {
    render(app) {
        this.renderDirectory(app.ticketData, 'structureTree');
        this.renderDetail(app.ticketData, app.orderId, app.ts, 'ticketContent');
    },

    renderDirectory(data, containerId) {
        const container = document.getElementById(containerId);
        if (!container) return;

        const keys = this.getTopLevelKeys(data);
        if (keys.length === 0) {
            container.innerHTML = `
                <div class="text-center text-gray-400 mt-20">
                    <i class="fas fa-diagram-project text-4xl mb-3 opacity-30"></i>
                    <p class="text-sm">暂无工单数据</p>
                    <p class="text-xs mt-2">通过工单链接或 ticketId 加载</p>
                </div>`;
            return;
        }

        container.innerHTML = `
            <div class="space-y-1">
                ${keys.map(key => `
                    <button onclick="app.scrollToTicketField('${this.escapeAttr(key)}')"
                        class="w-full flex items-center gap-2 p-2 rounded-lg hover:bg-gray-100 text-left transition-colors group">
                        <i class="fas ${this.iconForValue(data[key])} text-blue-500 text-xs"></i>
                        <span class="text-sm truncate font-medium text-gray-900">${this.escapeHtml(key)}</span>
                        <span class="ml-auto text-[10px] px-1.5 py-0.5 rounded-full bg-gray-100 text-gray-500">${this.typeLabel(data[key])}</span>
                    </button>
                `).join('')}
            </div>`;
    },

    renderDetail(data, ticketId, ts, containerId) {
        const container = document.getElementById(containerId);
        if (!container) return;

        const keys = this.getTopLevelKeys(data);
        if (keys.length === 0) {
            container.innerHTML = `
                <div class="max-w-3xl mx-auto bg-white shadow-lg rounded-xl p-12 min-h-[800px]">
                    <div class="text-center text-gray-400 mt-32">
                        <i class="fas fa-clipboard-list text-6xl mb-4 opacity-20"></i>
                        <p>加载工单数据后开始审核</p>
                    </div>
                </div>`;
            return;
        }

        container.innerHTML = `
            <div class="ticket-audit-sheet max-w-5xl mx-auto bg-white shadow-lg rounded-xl p-8 min-h-[800px]">
                <div class="border-b border-gray-200 pb-5 mb-6">
                    <div class="flex items-start justify-between gap-4">
                        <div>
                            <div class="text-xs font-semibold text-blue-600 uppercase tracking-wide mb-2">工单审核</div>
                            <h2 class="text-2xl font-bold text-gray-900">${this.escapeHtml(ticketId || '当前工单')}</h2>
                            <div class="flex flex-wrap gap-3 mt-3 text-xs text-gray-500">
                                <span><i class="fas fa-key mr-1"></i>${keys.length} 个一级字段</span>
                                ${ts ? `<span><i class="fas fa-clock mr-1"></i>${this.escapeHtml(ts)}</span>` : ''}
                            </div>
                        </div>
                        <div class="flex items-center gap-2">
                            <button onclick="TicketAuditView.setAllCollapsed('ticketContent', false)" class="px-3 py-2 text-xs rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50 transition-colors">
                                <i class="fas fa-expand-alt mr-1"></i>展开全部
                            </button>
                            <button onclick="TicketAuditView.setAllCollapsed('ticketContent', true)" class="px-3 py-2 text-xs rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50 transition-colors">
                                <i class="fas fa-compress-alt mr-1"></i>折叠全部
                            </button>
                        </div>
                    </div>
                </div>

                ${this.renderJsonTree(data)}
            </div>`;
    },

    renderJsonTree(data) {
        const keys = this.getTopLevelKeys(data);
        return `
            <div class="ticket-json-panel rounded-lg border border-gray-200 bg-gray-50 overflow-hidden">
                <div class="ticket-json-scroll p-4 overflow-auto">
                    <div class="ticket-json-tree text-sm">
                        ${this.renderJsonLine('{', 0, 'ticket-json-brace')}
                        ${keys.map((key, index) => this.renderJsonProperty(key, data[key], key, 1, index === keys.length - 1)).join('')}
                        ${this.renderJsonLine('}', 0, 'ticket-json-brace')}
                    </div>
                </div>
            </div>`;
    },

    renderJsonProperty(key, value, path, depth, isLast) {
        const isContainer = value && typeof value === 'object';
        const comma = isLast ? '' : ',';
        const fieldId = depth === 1 ? ` id="ticket-field-${this.safeId(key)}"` : '';
        const pathAttr = this.escapeAttr(path);

        if (!isContainer) {
            return this.renderJsonLine(`
                <span class="ticket-json-key" onclick="app.copyDataPath('${pathAttr}')" title="点击复制路径">"${this.escapeHtml(key)}"</span><span class="ticket-json-punctuation">: </span>${this.renderScalar(value)}<span class="ticket-json-punctuation">${comma}</span>
            `, depth, 'ticket-json-row', fieldId);
        }

        const entries = Array.isArray(value)
            ? value.map((item, index) => ({ key: String(index), value: item, path: `${path}[${index}]`, arrayItem: true }))
            : Object.keys(value).map(childKey => ({ key: childKey, value: value[childKey], path: `${path}.${childKey}`, arrayItem: false }));
        const openChar = Array.isArray(value) ? '[' : '{';
        const closeChar = Array.isArray(value) ? ']' : '}';
        const countLabel = Array.isArray(value) ? `${value.length}` : `${entries.length}`;

        return `
            <div class="ticket-json-node"${fieldId}>
                ${this.renderJsonLine(`
                    <button class="ticket-json-toggle" onclick="TicketAuditView.toggleNode(this)" title="展开/折叠">
                        <i class="fas fa-caret-down"></i>
                    </button>
                    <span class="ticket-json-key" onclick="app.copyDataPath('${pathAttr}')" title="点击复制路径">"${this.escapeHtml(key)}"</span><span class="ticket-json-punctuation">: ${openChar}</span>
                    <span class="ticket-json-count">${countLabel}</span>
                    <span class="ticket-json-ellipsis hidden"> ... ${closeChar}${comma}</span>
                `, depth, 'ticket-json-row ticket-json-parent')}
                <div class="ticket-json-children">
                    ${entries.map((entry, index) => this.renderJsonEntry(entry, depth + 1, index === entries.length - 1)).join('')}
                </div>
                ${this.renderJsonLine(`<span class="ticket-json-punctuation">${closeChar}${comma}</span>`, depth, 'ticket-json-row ticket-json-close')}
            </div>`;
    },

    renderJsonEntry(entry, depth, isLast) {
        if (entry.arrayItem) {
            return this.renderArrayItem(entry.value, entry.path, depth, isLast);
        }
        return this.renderJsonProperty(entry.key, entry.value, entry.path, depth, isLast);
    },

    renderArrayItem(value, path, depth, isLast) {
        const isContainer = value && typeof value === 'object';
        const comma = isLast ? '' : ',';
        if (!isContainer) {
            return this.renderJsonLine(`${this.renderScalar(value)}<span class="ticket-json-punctuation">${comma}</span>`, depth, 'ticket-json-row');
        }

        const entries = Array.isArray(value)
            ? value.map((item, index) => ({ key: String(index), value: item, path: `${path}[${index}]`, arrayItem: true }))
            : Object.keys(value).map(childKey => ({ key: childKey, value: value[childKey], path: `${path}.${childKey}`, arrayItem: false }));
        const openChar = Array.isArray(value) ? '[' : '{';
        const closeChar = Array.isArray(value) ? ']' : '}';

        return `
            <div class="ticket-json-node">
                ${this.renderJsonLine(`
                    <button class="ticket-json-toggle" onclick="TicketAuditView.toggleNode(this)" title="展开/折叠">
                        <i class="fas fa-caret-down"></i>
                    </button>
                    <span class="ticket-json-punctuation">${openChar}</span>
                    <span class="ticket-json-count">${entries.length}</span>
                    <span class="ticket-json-ellipsis hidden"> ... ${closeChar}${comma}</span>
                `, depth, 'ticket-json-row ticket-json-parent')}
                <div class="ticket-json-children">
                    ${entries.map((entry, index) => this.renderJsonEntry(entry, depth + 1, index === entries.length - 1)).join('')}
                </div>
                ${this.renderJsonLine(`<span class="ticket-json-punctuation">${closeChar}${comma}</span>`, depth, 'ticket-json-row ticket-json-close')}
            </div>`;
    },

    renderJsonLine(content, depth, className, extraAttrs = '') {
        return `<div${extraAttrs} class="${className}" style="--depth:${depth}">${content}</div>`;
    },

    renderScalar(value) {
        if (value === null || value === undefined) {
            return '<span class="ticket-json-null">null</span>';
        }
        if (value === '') {
            return '<span class="ticket-json-empty">""</span>';
        }
        if (typeof value === 'number') {
            return `<span class="ticket-json-number">${this.escapeHtml(value)}</span>`;
        }
        if (typeof value === 'boolean') {
            return `<span class="ticket-json-boolean">${value}</span>`;
        }
        return `<span class="ticket-json-string">"${this.escapeHtml(value)}"</span>`;
    },

    toggleNode(button) {
        const node = button.closest('.ticket-json-node');
        if (!node) return;
        const collapsed = node.classList.toggle('is-collapsed');
        button.setAttribute('aria-expanded', String(!collapsed));
    },

    setAllCollapsed(containerId, collapsed) {
        const container = document.getElementById(containerId);
        if (!container) return;
        container.querySelectorAll('.ticket-json-node').forEach(node => {
            node.classList.toggle('is-collapsed', collapsed);
            const button = node.querySelector(':scope > .ticket-json-row .ticket-json-toggle');
            if (button) {
                button.setAttribute('aria-expanded', String(!collapsed));
            }
        });
    },

    scrollToField(key) {
        const el = document.getElementById(`ticket-field-${this.safeId(key)}`);
        if (!el) return;
        el.scrollIntoView({ behavior: 'smooth', block: 'start' });
        el.classList.add('ticket-field-highlight');
        setTimeout(() => el.classList.remove('ticket-field-highlight'), 1800);
    },

    toAuditText(data, auditId, ts, idLabel = 'ticketId') {
        const header = [
            '工单信息',
            auditId ? `${idLabel}: ${auditId}` : '',
            ts ? `ts: ${ts}` : ''
        ].filter(Boolean).join('\n');
        return `${header}\n\n${JSON.stringify(data || {}, null, 2)}`;
    },

    getTopLevelKeys(data) {
        if (!data || typeof data !== 'object' || Array.isArray(data)) return [];
        return Object.keys(data);
    },

    iconForValue(value) {
        if (Array.isArray(value)) return 'fa-list';
        if (value && typeof value === 'object') return 'fa-folder-tree';
        return 'fa-align-left';
    },

    typeLabel(value) {
        if (Array.isArray(value)) return `数组 ${value.length}`;
        if (value && typeof value === 'object') return `对象 ${Object.keys(value).length}`;
        return '字段';
    },

    safeId(key) {
        return btoa(unescape(encodeURIComponent(key))).replace(/=+$/g, '').replace(/[+/]/g, '_');
    },

    escapeHtml(value) {
        return String(value ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    },

    escapeAttr(value) {
        return this.escapeHtml(String(value ?? '').replace(/\\/g, '\\\\').replace(/'/g, "\\'"));
    }
};
