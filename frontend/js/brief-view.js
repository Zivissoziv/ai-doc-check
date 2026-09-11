/**
 * 变更简报视图：负责左侧工单搜索结果列表渲染与 AI 简报 Markdown 渲染。
 * 不依赖第三方库，内置轻量 Markdown 渲染（标题/加粗/斜体/列表/表格/代码/引用/分隔线）。
 */
const BriefView = {

    /** 渲染左侧工单搜索结果列表 */
    renderOrderList(orders, selectedOrderId, containerId = 'structureTree') {
        const container = document.getElementById(containerId);
        if (!container) return;

        if (!orders || orders.length === 0) {
            container.innerHTML = `
                <div class="text-center text-gray-400 mt-16">
                    <i class="fas fa-inbox text-4xl mb-3 opacity-30"></i>
                    <p class="text-sm">暂无工单数据</p>
                    <p class="text-xs mt-2">请调整时间范围后重新搜索</p>
                </div>`;
            return;
        }

        container.innerHTML = `
            <div class="space-y-1">
                ${orders.map((order, idx) => {
                    const selected = order.orderId && order.orderId === selectedOrderId;
                    const docName = order.documentName || '';
                    return `
                    <button onclick="app.selectOrder(${idx})"
                        class="w-full p-2.5 rounded-lg text-left transition-colors border ${selected ? 'bg-blue-50 border-blue-300 shadow-sm' : 'bg-white border-gray-200 hover:bg-gray-50'}">
                        <div class="flex items-center gap-2">
                            <i class="fas fa-clipboard-list ${selected ? 'text-blue-600' : 'text-gray-400'} text-xs"></i>
                            <span class="text-sm font-medium truncate ${selected ? 'text-blue-700' : 'text-gray-900'}">${this.escapeHtml(order.orderId || `#${idx + 1}`)}</span>
                        </div>
                        ${docName ? `<div class="text-xs text-gray-500 truncate mt-0.5 ml-5">${this.escapeHtml(docName)}</div>` : ''}
                    </button>`;
                }).join('')}
            </div>`;
    },

    /** 渲染历史简报列表（最近 15 次） */
    renderHistoryList(records, containerId = 'briefHistoryList') {
        const container = document.getElementById(containerId);
        if (!container) return;

        if (!records || records.length === 0) {
            container.innerHTML = `
                <div class="text-center text-gray-400 mt-32">
                    <i class="fas fa-history text-6xl mb-4 opacity-20"></i>
                    <p>暂无历史简报，点击"AI评审"生成第一份</p>
                </div>`;
            return;
        }

        container.innerHTML = `
            <div class="flex items-center justify-between mb-4">
                <h3 class="font-semibold text-gray-900">
                    <i class="fas fa-history text-blue-600 mr-2"></i>历史简报（最近 ${records.length} 次）
                </h3>
            </div>
            <div class="space-y-2">
                ${records.map((r, idx) => {
                    const ts = String(r.ts || '');
                    const tsText = ts.length >= 12
                        ? `${ts.slice(0, 4)}-${ts.slice(4, 6)}-${ts.slice(6, 8)} ${ts.slice(8, 10)}:${ts.slice(10, 12)}:${ts.slice(12, 14)}`
                        : ts;
                    const name = this.escapeHtml(r.documentName || r.orderId || '变更简报');
                    const preview = this.escapeHtml(String(r.briefContent || '').replace(/[#*|\-\n]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 80));
                    return `
                    <button onclick="app.showBriefRecord(${idx})"
                        class="w-full text-left p-4 bg-white border border-gray-200 rounded-xl hover:border-blue-300 hover:bg-blue-50/50 transition-colors shadow-sm">
                        <div class="flex items-center justify-between gap-3">
                            <div class="flex items-center gap-2 min-w-0">
                                <i class="fas fa-file-lines text-blue-500"></i>
                                <span class="text-sm font-medium text-gray-900 truncate">${name}</span>
                            </div>
                            <span class="text-xs text-gray-400 shrink-0">${this.escapeHtml(tsText)}</span>
                        </div>
                        ${preview ? `<div class="text-xs text-gray-500 mt-1.5 truncate">${preview}…</div>` : ''}
                    </button>`;
                }).join('')}
            </div>`;
    },

    /** 渲染 AI 简报（Markdown → HTML） */
    renderBrief(content, containerId = 'auditResults') {
        const container = document.getElementById(containerId);
        if (!container) return;

        if (!content) {
            container.innerHTML = `
                <div class="text-center text-gray-400 mt-32">
                    <i class="fas fa-file-lines text-6xl mb-4 opacity-20"></i>
                    <p>点击"AI评审"对搜索到的所有工单生成变更简报</p>
                </div>`;
            return;
        }

        container.innerHTML = `
            <div class="max-w-4xl mx-auto bg-white shadow-lg rounded-xl p-10">
                <div class="flex items-center gap-2 pb-4 mb-6 border-b border-gray-200">
                    <i class="fas fa-file-lines text-blue-600"></i>
                    <span class="text-xs font-semibold text-blue-600 uppercase tracking-wide">AI 简报</span>
                </div>
                <div class="brief-markdown text-sm text-gray-800 leading-relaxed">${this.markdownToHtml(content)}</div>
            </div>`;
    },

    /** 轻量 Markdown 渲染 */
    markdownToHtml(md) {
        if (!md) return '';
        const esc = this.escapeHtml(md).replace(/\r\n/g, '\n');
        const lines = esc.split('\n');
        const html = [];
        let inList = null;      // 'ul' | 'ol'
        let inCode = false;
        let codeLines = [];
        let tableRows = [];

        const closeList = () => {
            if (inList) {
                html.push(`</${inList}>`);
                inList = null;
            }
        };
        const flushTable = () => {
            if (tableRows.length) {
                const rows = tableRows.filter(r => !/^\s*\|?[\s:|-]+\|?\s*$/.test(r));
                const parseCells = r => r.replace(/^\s*\|/, '').replace(/\|\s*$/, '').split('|').map(c => c.trim());
                if (rows.length > 0) {
                    const head = parseCells(rows[0]);
                    const body = rows.slice(1);
                    html.push('<table class="w-full border-collapse my-3 text-xs"><thead><tr>'
                        + head.map(c => `<th class="border border-gray-200 bg-gray-50 px-2 py-1.5 text-left font-medium">${this.inline(c)}</th>`).join('')
                        + '</tr></thead><tbody>'
                        + body.map(r => '<tr>' + parseCells(r).map(c => `<td class="border border-gray-200 px-2 py-1.5">${this.inline(c)}</td>`).join('') + '</tr>').join('')
                        + '</tbody></table>');
                }
                tableRows = [];
            }
        };

        for (const raw of lines) {
            const line = raw;

            if (inCode) {
                if (/^```/.test(line.trim())) {
                    html.push(`<pre class="bg-gray-900 text-gray-100 rounded-lg p-3 my-3 overflow-x-auto text-xs"><code>${codeLines.join('\n')}</code></pre>`);
                    inCode = false;
                    codeLines = [];
                } else {
                    codeLines.push(line);
                }
                continue;
            }

            if (/^\s*\|.*\|\s*$/.test(line)) {
                closeList();
                tableRows.push(line);
                continue;
            }
            flushTable();

            const trimmed = line.trim();
            if (!trimmed) {
                closeList();
                continue;
            }

            if (/^```/.test(trimmed)) {
                closeList();
                inCode = true;
                continue;
            }

            const heading = trimmed.match(/^(#{1,6})\s+(.*)$/);
            if (heading) {
                closeList();
                const level = heading[1].length;
                const sizes = { 1: 'text-xl', 2: 'text-lg', 3: 'text-base', 4: 'text-sm', 5: 'text-sm', 6: 'text-sm' };
                html.push(`<h${level} class="${sizes[level]} font-bold text-gray-900 mt-5 mb-2">${this.inline(heading[2])}</h${level}>`);
                continue;
            }

            if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
                closeList();
                html.push('<hr class="my-4 border-gray-200">');
                continue;
            }

            const quote = trimmed.match(/^&gt;\s?(.*)$/);
            if (quote) {
                closeList();
                html.push(`<blockquote class="border-l-4 border-blue-300 bg-blue-50/50 pl-3 pr-2 py-1.5 my-2 text-gray-600 rounded-r">${this.inline(quote[1])}</blockquote>`);
                continue;
            }

            const ulItem = trimmed.match(/^[-*+]\s+(.*)$/);
            const olItem = trimmed.match(/^(\d+)[.、)]\s+(.*)$/);
            if (ulItem) {
                if (inList !== 'ul') { closeList(); html.push('<ul class="list-disc pl-5 my-2 space-y-1">'); inList = 'ul'; }
                html.push(`<li>${this.inline(ulItem[1])}</li>`);
                continue;
            }
            if (olItem) {
                if (inList !== 'ol') { closeList(); html.push('<ol class="list-decimal pl-5 my-2 space-y-1">'); inList = 'ol'; }
                html.push(`<li>${this.inline(olItem[2])}</li>`);
                continue;
            }

            closeList();
            html.push(`<p class="my-1.5">${this.inline(trimmed)}</p>`);
        }

        if (inCode) {
            html.push(`<pre class="bg-gray-900 text-gray-100 rounded-lg p-3 my-3 overflow-x-auto text-xs"><code>${codeLines.join('\n')}</code></pre>`);
        }
        closeList();
        flushTable();

        return html.join('\n');
    },

    /** 行内元素：加粗 / 斜体 / 行内代码 / 链接 */
    inline(text) {
        return text
            .replace(/`([^`]+)`/g, '<code class="bg-gray-100 text-gray-700 px-1 py-0.5 rounded text-xs">$1</code>')
            .replace(/\*\*([^*]+)\*\*/g, '<strong class="font-semibold text-gray-900">$1</strong>')
            .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
            .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a class="text-blue-600 hover:underline" href="$2" target="_blank" rel="noopener">$1</a>');
    },

    escapeHtml(value) {
        return String(value ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }
};
