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

        container.innerHTML = rules.map((rule, idx) => `
            <div class="flex items-start gap-3 p-3 bg-gray-50 rounded-lg group hover:bg-gray-100 transition-colors ${rule.enabled === false ? 'opacity-50' : ''}">
                <button onclick="app.toggleRuleStatus(${idx})" 
                    class="mt-1 w-5 h-5 rounded flex items-center justify-center ${rule.enabled !== false ? 'bg-blue-500 text-white' : 'bg-gray-300 text-gray-500'}">
                    <i class="fas fa-${rule.enabled !== false ? 'check' : 'times'} text-xs"></i>
                </button>
                <div class="flex-1 min-w-0">
                    <div class="flex items-center gap-2">
                        <span class="font-medium text-gray-900 truncate">${rule.name}</span>
                        <span class="px-2 py-0.5 text-xs rounded-full ${rule.severity === 'error' ? 'bg-red-100 text-red-700' : rule.severity === 'warning' ? 'bg-yellow-100 text-yellow-700' : 'bg-blue-100 text-blue-700'}">
                            ${rule.severity === 'error' ? '错误' : rule.severity === 'warning' ? '警告' : '信息'}
                        </span>
                    </div>
                    <p class="text-xs text-gray-500 mt-1 line-clamp-2">${rule.prompt}</p>
                </div>
                <div class="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                    <button onclick="app.editRule(${idx})" class="p-1.5 text-gray-400 hover:text-blue-500">
                        <i class="fas fa-edit text-sm"></i>
                    </button>
                    <button onclick="app.deleteRule(${idx})" class="p-1.5 text-gray-400 hover:text-red-500">
                        <i class="fas fa-trash text-sm"></i>
                    </button>
                </div>
            </div>
        `).join('');
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
        const passClass = result.pass ? 'text-green-600' : 'text-red-600';
        const bgClass = result.pass ? 'bg-green-50' : 'bg-red-50';
        const icon = result.pass ? 'fa-check-circle' : 'fa-exclamation-circle';

        let issuesHtml = '';
        if (result.issues && result.issues.length > 0) {
            issuesHtml = result.issues.map(issue => `
                <div class="mt-2 p-2 bg-white rounded border text-sm">
                    <div class="text-gray-600"><i class="fas fa-map-marker-alt mr-1"></i> ${issue.location || ''}</div>
                    <div class="text-red-600 mt-1"><i class="fas fa-times-circle mr-1"></i> ${issue.problem || ''}</div>
                    <div class="text-blue-600 mt-1"><i class="fas fa-lightbulb mr-1"></i> ${issue.suggestion || ''}</div>
                </div>
            `).join('');
        }

        container.innerHTML = `
            <div class="p-4 ${bgClass} rounded-lg border">
                <div class="flex items-center justify-between">
                    <div class="flex items-center gap-2">
                        <i class="fas ${icon} ${passClass}"></i>
                        <span class="font-medium">${result.ruleName}</span>
                        <span class="px-2 py-0.5 text-xs rounded-full ${result.severity === 'error' ? 'bg-red-100 text-red-700' : result.severity === 'warning' ? 'bg-yellow-100 text-yellow-700' : 'bg-blue-100 text-blue-700'}">
                            ${result.severity === 'error' ? '错误' : result.severity === 'warning' ? '警告' : '信息'}
                        </span>
                    </div>
                    <div class="flex items-center gap-2">
                        <span class="text-xs text-gray-500">置信度: ${result.confidence}%</span>
                        <span class="${passClass} font-medium">${result.pass ? '通过' : '未通过'}</span>
                    </div>
                </div>
                <p class="text-sm text-gray-600 mt-2">${result.summary || ''}</p>
                ${issuesHtml}
            </div>
        `;
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