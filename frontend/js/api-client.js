const ConfigAPI = {
    async getApiConfig() {
        const response = await fetch('/api/config/api');
        if (!response.ok) {
            throw new Error('获取API配置失败');
        }
        return response.json();
    },

    async updateApiConfig(config) {
        const response = await fetch('/api/config/api', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(config)
        });
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || '保存API配置失败');
        }
        return response.json();
    }
};

const RulesManager = {
    async getGroupsFromServer() {
        const response = await fetch('/api/config/rules');
        if (!response.ok) {
            throw new Error('获取规则组失败');
        }
        return response.json();
    },

    async loadFromServer(groupId) {
        const response = await fetch(`/api/config/rules/${groupId}`);
        if (!response.ok) {
            throw new Error('获取规则失败');
        }
        return response.json();
    },

    async saveToServer(groupId, rules, groupName) {
        const payload = {
            groupId: groupId,
            name: groupName,
            rules: rules.map((r, idx) => ({
                name: r.name,
                prompt: r.prompt,
                severity: r.severity || 'warning',
                enabled: r.enabled !== false,
                sortOrder: idx
            }))
        };

        const response = await fetch(`/api/config/rules/${groupId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || '保存规则失败');
        }
        return response.json();
    },

    async createGroup(groupId, groupName, rules) {
        const payload = {
            groupId: groupId,
            name: groupName,
            rules: rules || []
        };

        const response = await fetch('/api/config/rules', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || '创建规则组失败');
        }
        return response.json();
    },

    async deleteGroup(groupId) {
        const response = await fetch(`/api/config/rules/${groupId}`, {
            method: 'DELETE'
        });
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || '删除规则组失败');
        }
        return response.json();
    },

    save(rules) {
        localStorage.setItem('smartdoc_rules', JSON.stringify(rules));
    },

    load() {
        const data = localStorage.getItem('smartdoc_rules');
        return data ? JSON.parse(data) : null;
    },

    getCurrentGroup() {
        return localStorage.getItem('smartdoc_current_group') || null;
    },

    setCurrentGroup(groupId) {
        if (groupId) {
            localStorage.setItem('smartdoc_current_group', groupId);
        } else {
            localStorage.removeItem('smartdoc_current_group');
        }
    },

    renderGroupSelector(groups, currentId, containerId) {
        const container = document.getElementById(containerId);
        if (!container) return;

        container.innerHTML = groups.map(g => `
            <option value="${g.groupId}" ${g.groupId === currentId ? 'selected' : ''}>
                ${g.name}
            </option>
        `).join('');
    },

    renderList(rules, containerId) {
        const container = document.getElementById(containerId);
        if (!container) return;

        if (!rules || rules.length === 0) {
            container.innerHTML = '<div class="text-center text-gray-400 py-8"><i class="fas fa-clipboard-list text-3xl mb-2 opacity-30"></i><p class="text-sm">暂无审核规则</p></div>';
            return;
        }

        container.innerHTML = rules.map((rule, idx) => {
            const isEnabled = rule.enabled !== false;
            return `
                <div class="p-3 border rounded-xl transition-all duration-200 group ${isEnabled ? 'bg-white border-gray-200 shadow-sm hover:shadow-md' : 'bg-gray-50 border-gray-100 opacity-70'}">
                    <div class="flex items-start justify-between mb-2">
                        <div onclick="app.editRule(${idx})" class="flex items-center gap-2 overflow-hidden cursor-pointer flex-1" title="点击编辑规则">
                            <span class="flex-shrink-0 w-2 h-2 rounded-full ${rule.severity === 'error' ? 'bg-red-500' : rule.severity === 'warning' ? 'bg-yellow-500' : 'bg-blue-500'} shadow-sm"></span>
                            <span class="font-medium text-sm truncate ${isEnabled ? 'text-gray-900 group-hover:text-blue-600' : 'text-gray-400'} transition-colors">${rule.name}</span>
                            <i class="fas fa-edit text-xs text-gray-300 opacity-0 group-hover:opacity-100 transition-opacity"></i>
                        </div>
                        <div class="flex items-center gap-2 flex-shrink-0">
                            <div onclick="app.toggleRuleStatus(${idx})" 
                                class="relative inline-flex h-5 w-9 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none ${isEnabled ? 'bg-blue-600' : 'bg-gray-200'}"
                                title="${isEnabled ? '点击禁用' : '点击启用'}">
                                <span class="pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${isEnabled ? 'translate-x-4' : 'translate-x-0'}"></span>
                            </div>
                            <button onclick="app.deleteRule(${idx})" 
                                class="w-5 h-5 flex items-center justify-center text-gray-300 hover:text-red-500 transition-colors"
                                title="删除规则">
                                <i class="fas fa-trash-alt text-xs"></i>
                            </button>
                        </div>
                    </div>
                    <p onclick="app.editRule(${idx})" class="text-xs ${isEnabled ? 'text-gray-500' : 'text-gray-400'} line-clamp-2 leading-relaxed cursor-pointer">${rule.prompt}</p>
                </div>
            `;
        }).join('');
    }
};

const ConfigLoader = {
    async loadTemplateList() {
        return { 
            templates: [
                { name: '模板.docx', description: '投产文档模板' }
            ], 
            defaultTemplate: null 
        };
    },

    async loadPresetTemplate(fileName) {
        try {
            const response = await fetch('/api/template/default');
            if (!response.ok) {
                throw new Error('模板加载失败');
            }
            const blob = await response.blob();
            return new File([blob], fileName || '模板.docx', { 
                type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' 
            });
        } catch (err) {
            console.error('加载预设模板失败:', err);
            return null;
        }
    }
};

const DocumentParser = {
    async parse(file) {
        const ext = file.name.split('.').pop().toLowerCase();
        const arrayBuffer = await file.arrayBuffer();
        
        let text = '';
        let tree = [];
        let html = '';

        if (ext === 'docx') {
            const result = await this.parseDocx(arrayBuffer);
            text = result.text;
            tree = result.tree;
            html = result.html;
        } else if (ext === 'pdf') {
            const result = await this.parsePdf(arrayBuffer);
            text = result.text;
            tree = result.tree;
            html = result.html;
        } else if (ext === 'txt') {
            text = new TextDecoder().decode(arrayBuffer);
            tree = this.buildTreeFromText(text);
            html = `<pre class="whitespace-pre-wrap">${this.escapeHtml(text)}</pre>`;
        }

        return {
            name: file.name,
            type: ext,
            text: text,
            tree: tree,
            html: html
        };
    },

    async parseDocx(arrayBuffer) {
        const zip = await JSZip.loadAsync(arrayBuffer);
        const docXml = await zip.file('word/document.xml').async('text');
        
        const parser = new DOMParser();
        const doc = parser.parseFromString(docXml, 'text/xml');
        
        const paragraphs = doc.querySelectorAll('p');
        let text = '';
        let html = '';
        const flatNodes = [];
        let nodeId = 0;

        paragraphs.forEach(p => {
            const runs = p.querySelectorAll('t');
            let content = '';
            runs.forEach(t => {
                content += t.textContent;
            });

            if (content.trim()) {
                text += content + '\n';
                html += `<p>${this.escapeHtml(content)}</p>`;

                const level = this.detectHeadingLevel(content);
                if (level > 0) {
                    flatNodes.push({
                        id: 'node-' + (nodeId++),
                        type: 'heading',
                        level: level,
                        content: content,
                        html: `<p class="font-bold">${this.escapeHtml(content)}</p>`,
                        children: []
                    });
                } else {
                    flatNodes.push({
                        id: 'node-' + (nodeId++),
                        type: 'paragraph',
                        level: 0,
                        content: content,
                        html: `<p>${this.escapeHtml(content)}</p>`,
                        children: []
                    });
                }
            }
        });

        const tree = this.buildHeadingTree(flatNodes);
        return { text, tree, html };
    },

    detectHeadingLevel(content) {
        if (!content) return 0;
        const trimmed = content.trim();
        
        if (/^第[一二三四五六七八九十百]+章/.test(trimmed)) return 1;
        if (/^第[一二三四五六七八九十百]+节/.test(trimmed)) return 2;
        
        if (/^\d+\.\d+\.\d+/.test(trimmed)) return 3;
        if (/^\d+\.\d+/.test(trimmed)) return 2;
        if (/^\d+[\.\、]/.test(trimmed)) return 1;
        
        if (/^[一二三四五六七八九十]+[\.\、]/.test(trimmed)) return 1;
        if (/^[（\(][一二三四五六七八九十]+[）\)]/.test(trimmed)) return 2;
        if (/^[（\(]\d+[）\)]/.test(trimmed)) return 2;
        
        if (/^[①②③④⑤⑥⑦⑧⑨⑩]/.test(trimmed)) return 3;
        
        return 0;
    },

    buildHeadingTree(flatNodes) {
        const tree = [];
        const headingStack = [];

        flatNodes.forEach(node => {
            if (node.type !== 'heading') {
                if (headingStack.length > 0) {
                    headingStack[headingStack.length - 1].children.push(node);
                } else {
                    tree.push(node);
                }
                return;
            }

            while (headingStack.length > 0 && headingStack[headingStack.length - 1].level >= node.level) {
                headingStack.pop();
            }

            if (headingStack.length === 0) {
                tree.push(node);
            } else {
                headingStack[headingStack.length - 1].children.push(node);
            }

            headingStack.push(node);
        });

        return tree;
    },

    async parsePdf(arrayBuffer) {
        if (typeof pdfjsLib !== 'undefined' && pdfjsLib.GlobalWorkerOptions) {
            pdfjsLib.GlobalWorkerOptions.workerSrc = './libs/pdf.worker.min.js';
        }
        const pdf = await pdfjsLib.getDocument({ data: arrayBuffer }).promise;
        let text = '';
        let html = '';
        const tree = [];
        let nodeId = 0;

        for (let i = 1; i <= pdf.numPages; i++) {
            const page = await pdf.getPage(i);
            const content = await page.getTextContent();
            let pageText = '';
            
            content.items.forEach(item => {
                pageText += item.str + ' ';
            });

            text += pageText + '\n';
            html += `<div class="mb-4"><p class="text-gray-500 text-sm mb-2">第 ${i} 页</p><p>${this.escapeHtml(pageText)}</p></div>`;
            
            tree.push({
                id: 'node-' + (nodeId++),
                type: 'heading',
                level: 1,
                content: `第 ${i} 页`,
                html: `<p class="font-bold text-gray-500">第 ${i} 页</p>`,
                children: [{
                    id: 'node-' + (nodeId++),
                    type: 'paragraph',
                    content: pageText,
                    html: `<p>${this.escapeHtml(pageText)}</p>`,
                    children: []
                }]
            });
        }

        return { text, tree, html };
    },

    async parseExcel(file) {
        const arrayBuffer = await file.arrayBuffer();
        const workbook = XLSX.read(arrayBuffer, { type: 'array' });
        
        const data = {};
        workbook.SheetNames.forEach(sheetName => {
            const sheet = workbook.Sheets[sheetName];
            data[sheetName] = XLSX.utils.sheet_to_json(sheet);
        });

        return { data };
    },

    buildTreeFromText(text) {
        const lines = text.split('\n');
        const tree = [];
        let nodeId = 0;

        lines.forEach(line => {
            if (line.trim()) {
                const headingMatch = line.match(/^(第[一二三四五六七八九十\d]+[章节]|[一二三四五六七八九十\d]+[\.、]|\d+[\.\、])/);
                tree.push({
                    id: 'node-' + (nodeId++),
                    type: headingMatch ? 'heading' : 'paragraph',
                    level: headingMatch ? 1 : 0,
                    content: line,
                    html: `<p${headingMatch ? ' class="font-bold"' : ''}>${this.escapeHtml(line)}</p>`,
                    children: []
                });
            }
        });

        return tree;
    },

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
};

const AiAudit = {
    async callBatchLLM(prompt, rules, settings) {
        const response = await fetch('/api/audit', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(prompt)
        });

        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || '审核请求失败');
        }

        const data = await response.json();
        return data.results || [];
    },

    buildBatchPrompt(rules, documentText, ticketData, repeatPrompt) {
        return {
            ruleGroupId: null,
            documentBase64: null,
            documentType: 'txt',
            data: ticketData,
            settings: {
                repeatPrompt: repeatPrompt
            },
            _internal: {
                rules: rules,
                documentText: documentText
            }
        };
    },

    renderResult(result, container) {
        const div = document.createElement('div');
        div.className = 'bg-white rounded-xl border border-gray-200 p-6 fade-in';

        const isSkipped = result.summary && result.summary.startsWith('已跳过:');

        if (isSkipped) {
            div.className = 'bg-gray-50 rounded-xl border border-gray-300 p-6 fade-in opacity-75';

            div.innerHTML = `
                <div class="flex items-center justify-between mb-4">
                    <div class="flex items-center gap-3">
                        <div class="w-10 h-10 rounded-full flex items-center justify-center bg-gray-200 text-gray-500">
                            <i class="fas fa-forward text-lg"></i>
                        </div>
                        <div>
                            <h3 class="font-semibold text-gray-600">${result.ruleName}</h3>
                            <div class="flex items-center gap-2 text-xs text-gray-500">
                                <span class="px-2 py-0.5 rounded-full bg-gray-200 text-gray-600">已跳过</span>
                                <span>置信度: ${result.confidence}%</span>
                            </div>
                        </div>
                    </div>
                </div>
                <div class="text-sm text-gray-500 mb-4">
                    <i class="fas fa-info-circle mr-1"></i> 此规则因缺少必要数据而被跳过，未进行实际审核
                </div>
                <div class="text-xs text-gray-400 pt-3 border-t border-gray-200">
                    <i class="fas fa-quote-left mr-1 opacity-50"></i> ${result.summary}
                </div>`;

            container.appendChild(div);
            return;
        }

        const statusColor = result.pass ? 'green' : 'red';
        const statusIcon = result.pass ? 'check' : 'times';
        const severityClass = result.severity === 'error' ? 'red' : result.severity === 'warning' ? 'yellow' : 'blue';
        
        const issuesHtml = result.issues?.length > 0 
            ? `<div class="space-y-3 mb-4">${result.issues.map((issue, idx) => `
                <div class="p-3 bg-gray-50 rounded-lg border-l-4 border-${severityClass}-400">
                    <div class="flex items-start gap-2">
                        <i class="fas fa-map-marker-alt text-gray-400 mt-0.5 text-xs"></i>
                        <div class="flex-1">
                            <div class="text-xs text-gray-500 mb-1 group relative">
                                <span>${issue.location || '未知位置'}</span>
                                <button onclick="AiAudit.jumpToLocation('${(issue.textSnippet || issue.location || '').replace(/'/g, "\\'")}', '${(issue.location || '').replace(/'/g, "\\'")}')" class="ml-2 opacity-0 group-hover:opacity-100 text-blue-500 hover:text-blue-700 transition-opacity" title="跳转到文档位置"><i class="fas fa-location-arrow text-xs"></i></button>
                            </div>
                            <div class="text-sm text-gray-900 mb-1">${issue.problem}</div>
                            ${issue.suggestion ? `<div class="text-xs text-blue-600 bg-blue-50 p-2 rounded mt-1"><i class="fas fa-lightbulb mr-1"></i> ${issue.suggestion}</div>` : ''}
                        </div>
                    </div>
                </div>`).join('')}</div>`
            : '<div class="text-sm text-green-600 mb-4"><i class="fas fa-check-circle mr-1"></i> 未发现问题</div>';
        
        div.innerHTML = `
            <div class="flex items-center justify-between mb-4">
                <div class="flex items-center gap-3">
                    <div class="w-10 h-10 rounded-full flex items-center justify-center bg-${statusColor}-100 text-${statusColor}-600">
                        <i class="fas fa-${statusIcon} text-lg"></i>
                    </div>
                    <div>
                        <h3 class="font-semibold text-gray-900">${result.ruleName}</h3>
                        <div class="flex items-center gap-2 text-xs text-gray-500">
                            <span class="px-2 py-0.5 rounded-full bg-${severityClass}-100 text-${severityClass}-700">${result.severity === 'error' ? '错误' : result.severity === 'warning' ? '警告' : '信息'}</span>
                            <span>置信度: ${result.confidence}%</span>
                        </div>
                    </div>
                </div>
            </div>
            ${issuesHtml}
            <div class="text-xs text-gray-500 pt-3 border-t border-gray-100">
                <i class="fas fa-quote-left mr-1 opacity-50"></i> ${result.summary}
            </div>`;
        
        container.appendChild(div);
    },

    jumpToLocation(textSnippet, location) {
        UiHelpers.switchTab('preview');
        
        setTimeout(() => {
            UiHelpers.highlightAndScroll(textSnippet, location);
        }, 100);
    }
};

const ReportExporter = {
    exportHtml(document, template, excelData, auditResults) {
        const html = `
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>审核报告 - ${document?.name || '未知文档'}</title>
    <style>
        body { font-family: Arial, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; }
        h1 { color: #333; border-bottom: 2px solid #3b82f6; padding-bottom: 10px; }
        .result { margin: 10px 0; padding: 15px; border-radius: 8px; }
        .pass { background: #f0fdf4; border-left: 4px solid #22c55e; }
        .fail { background: #fef2f2; border-left: 4px solid #ef4444; }
        .issue { margin: 5px 0; padding: 10px; background: white; border-radius: 4px; }
    </style>
</head>
<body>
    <h1>文档审核报告</h1>
    <p><strong>文档名称:</strong> ${document?.name || '未知'}</p>
    <p><strong>审核时间:</strong> ${new Date().toLocaleString()}</p>
    <p><strong>审核结果:</strong> ${auditResults?.length || 0} 条规则</p>
    <hr>
    ${auditResults?.map(r => `
        <div class="result ${r.pass ? 'pass' : 'fail'}">
            <h3>${r.ruleName} - ${r.pass ? '通过' : '未通过'}</h3>
            <p>${r.summary || ''}</p>
            ${r.issues?.map(i => `<div class="issue"><strong>位置:</strong> ${i.location}<br><strong>问题:</strong> ${i.problem}<br><strong>建议:</strong> ${i.suggestion}</div>`).join('') || ''}
        </div>
    `).join('') || '<p>无审核结果</p>'}
</body>
</html>`;

        const blob = new Blob([html], { type: 'text/html' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `审核报告_${new Date().toISOString().slice(0, 10)}.html`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }
};