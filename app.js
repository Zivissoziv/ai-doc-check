// 设置PDF.js worker
pdfjsLib.GlobalWorkerOptions.workerSrc = './libs/pdf.worker.min.js';

// 主应用类
class SmartDocApp {
    constructor() {
        this.template = null;  // { name, tree, text, html }
        this.document = null;  // { name, tree, text, html }
        this.excelData = null; // { sheets: { name, headers, rows }[] }
        this.rules = JSON.parse(localStorage.getItem('smartdoc_rules') || '[]');
        this.settings = JSON.parse(localStorage.getItem('smartdoc_settings') || '{"auditRole": "专业文档审核专家"}');
        this.currentEditingRule = null;
        this.auditResults = [];
        
        this.init();
    }
    
    async init() {
        // 先尝试从config目录加载预配置
        await this.loadPresetConfig();
        this.loadSettings();
        this.renderRules();
        this.updateApiStatus();
    }
    
    // 加载预配置文件（每次都从config读取，不使用浏览器缓存）
    async loadPresetConfig() {
        try {
            // 强制加载API配置（禁用缓存）
            const apiResponse = await fetch('./config/api.json', { cache: 'no-store' });
            if (apiResponse.ok) {
                const apiConfig = await apiResponse.json();
                // 如果配置文件中没有apiKey，尝试从本地存储恢复（保护用户已输入的密钥）
                if (!apiConfig.apiKey) {
                    const savedSettings = localStorage.getItem('smartdoc_settings');
                    if (savedSettings) {
                        const localSettings = JSON.parse(savedSettings);
                        if (localSettings.apiKey) {
                            apiConfig.apiKey = localSettings.apiKey;
                        }
                    }
                }
                this.settings = apiConfig;
                console.log('已加载预配置API设置');
            }
            
            // 强制加载规则配置（禁用缓存）
            const rulesResponse = await fetch('./config/rules.json', { cache: 'no-store' });
            if (rulesResponse.ok) {
                const rulesConfig = await rulesResponse.json();
                this.rules = rulesConfig;
                console.log('已加载预配置审核规则');
            }
            
            // 加载模板列表
            await this.loadTemplateList();
            
        } catch (err) {
            console.log('预配置文件加载失败，使用默认配置:', err.message);
        }
    }
    
    // 加载模板列表
    async loadTemplateList() {
        try {
            const response = await fetch('./config/templates.json', { cache: 'no-store' });
            if (response.ok) {
                const config = await response.json();
                this.templateList = config.templates || [];
                this.defaultTemplateName = config.defaultTemplate || '';
                
                // 如果有模板列表，渲染选择器
                if (this.templateList.length > 0) {
                    this.renderTemplateSelector();
                }
                
                // 如果有默认模板且当前没有加载模板，自动加载
                if (this.defaultTemplateName && !this.template) {
                    await this.loadPresetTemplate(this.defaultTemplateName);
                }
                
                console.log('已加载模板列表');
            }
        } catch (err) {
            console.log('模板列表加载失败:', err.message);
        }
    }
    
    // 渲染模板选择器
    renderTemplateSelector() {
        const container = document.getElementById('templateInput').parentElement;
        
        // 检查是否已存在选择器
        if (document.getElementById('presetTemplateSelect')) return;
        
        const selectHtml = `
            <select id="presetTemplateSelect" onchange="app.onPresetTemplateSelect(this.value)" 
                class="w-full px-3 py-2 mb-2 border border-gray-300 rounded-lg text-sm bg-white">
                <option value="">-- 选择预设模板 --</option>
                ${this.templateList.map(t => `<option value="${t.file}">${t.name}</option>`).join('')}
            </select>
        `;
        
        container.insertAdjacentHTML('afterbegin', selectHtml);
    }
    
    // 选择预设模板
    async onPresetTemplateSelect(fileName) {
        if (!fileName) return;
        await this.loadPresetTemplate(fileName);
    }
    
    // 加载预设模板文件
    async loadPresetTemplate(fileName) {
        try {
            this.setStatus('正在加载预设模板...', true);
            const response = await fetch(`./config/templates/${fileName}`, { cache: 'no-store' });
            if (response.ok) {
                const blob = await response.blob();
                const file = new File([blob], fileName, { type: blob.type });
                this.template = await this.parseDocument(file);
                this.renderStructureTree();
                this.setStatus(`模板已加载: ${fileName}`);
                this.compareStructure();
                console.log('已加载预设模板:', fileName);
            } else {
                throw new Error('模板文件不存在');
            }
        } catch (err) {
            console.log('预设模板加载失败:', err.message);
            this.setStatus('模板加载失败');
        }
    }
    
    // 文件上传处理
    async handleTemplateUpload(input) {
        const file = input.files[0];
        if (!file) return;
        
        this.setStatus('正在解析模板...', true);
        try {
            this.template = await this.parseDocument(file);
            this.renderStructureTree();
            this.setStatus(`模板已加载: ${file.name}`);
            this.compareStructure();
        } catch (err) {
            alert('解析失败: ' + err.message);
            this.setStatus('就绪');
        }
    }
    
    async handleDocUpload(input) {
        const file = input.files[0];
        if (!file) return;
        
        this.setStatus('正在解析文档...', true);
        try {
            this.document = await this.parseDocument(file);
            this.renderDocumentPreview();
            this.renderStructureTree();
            this.setStatus(`文档已加载: ${file.name}`);
            this.compareStructure();
        } catch (err) {
            alert('解析失败: ' + err.message);
            this.setStatus('就绪');
        }
    }
    
    async handleExcelUpload(input) {
        const file = input.files[0];
        if (!file) return;
        
        this.setStatus('正在解析Excel...', true);
        try {
            const data = await file.arrayBuffer();
            const workbook = XLSX.read(data, { type: 'array' });
            
            this.excelData = {
                fileName: file.name,
                sheets: workbook.SheetNames.map(name => {
                    const sheet = workbook.Sheets[name];
                    const json = XLSX.utils.sheet_to_json(sheet, { header: 1 });
                    return {
                        name,
                        headers: json[0] || [],
                        rows: json.slice(1).filter(row => row.some(cell => cell != null))
                    };
                })
            };
            
            document.getElementById('excelLabel').textContent = file.name;
            document.getElementById('insertVarBtn').style.display = 'inline';
            this.setStatus(`Excel已加载: ${file.name}`);
        } catch (err) {
            alert('Excel解析失败: ' + err.message);
        }
    }
    
    // 文档解析核心
    async parseDocument(file) {
        const ext = file.name.split('.').pop().toLowerCase();
        const buffer = await file.arrayBuffer();
        
        let result = { name: file.name, type: ext, tree: [], text: '', html: '' };
        
        if (ext === 'docx') {
            const res = await mammoth.convertToHtml({ arrayBuffer: buffer });
            result.html = res.value;
            result.tree = this.htmlToTree(result.html);
            result.text = res.value.replace(/<[^>]+>/g, '');
        } else if (ext === 'pdf') {
            const pdf = await pdfjsLib.getDocument({ data: buffer }).promise;
            let text = '';
            if (pdf.numPages > 0) {
                for (let i = 1; i <= pdf.numPages; i++) {
                    const page = await pdf.getPage(i);
                    const content = await page.getTextContent();
                    if (content && content.items) {
                        text += content.items.map(item => item.str || '').join(' ') + '\n';
                    }
                }
            }
            result.text = text;
            result.tree = this.textToTree(text);
            result.html = `<pre class="whitespace-pre-wrap">${text}</pre>`;
        } else {
            // txt, md
            const text = new TextDecoder().decode(buffer);
            result.text = text;
            result.tree = this.textToTree(text);
            result.html = `<pre class="whitespace-pre-wrap">${text}</pre>`;
        }
        
        document.getElementById('wordCount').textContent = `字数: ${result.text ? result.text.length : 0}`;
        return result;
    }
    
    // HTML转换为树形结构
    htmlToTree(html) {
        const parser = new DOMParser();
        const doc = parser.parseFromString(html, 'text/html');
        const tree = [];
        const path = [];
        
        const traverse = (node) => {
            if (node.nodeType === Node.ELEMENT_NODE) {
                const tag = node.tagName.toLowerCase();
                const isHeading = /^h[1-6]$/.test(tag);
                
                if (isHeading || (tag === 'p' && node.textContent.trim())) {
                    const level = isHeading ? parseInt(tag[1]) : 99;
                    const item = {
                        id: Math.random().toString(36).substr(2, 9),
                        type: isHeading ? 'heading' : 'paragraph',
                        level,
                        content: node.textContent.trim(),
                        html: node.outerHTML,
                        children: []
                    };
                    
                    while (path.length > 0 && path[path.length - 1].level >= level) {
                        path.pop();
                    }
                    
                    if (path.length === 0) {
                        tree.push(item);
                    } else {
                        path[path.length - 1].children.push(item);
                    }
                    
                    if (isHeading) path.push(item);
                }
                
                // 处理表格
                if (tag === 'table') {
                    const parent = path.length > 0 ? path[path.length - 1] : tree[tree.length - 1];
                    if (parent) {
                        parent.children.push({
                            id: Math.random().toString(36).substr(2, 9),
                            type: 'table',
                            level: 99,
                            content: `[表格] ${node.rows?.length || 0}行 × ${node.rows?.[0]?.cells?.length || 0}列`,
                            html: node.outerHTML
                        });
                    }
                }
            }
            
            node.childNodes.forEach(child => traverse(child));
        };
        
        traverse(doc.body);
        return tree;
    }
    
    // 纯文本转换为树（基于缩进或空行启发式）
    textToTree(text) {
        const lines = text.split('\n');
        const tree = [];
        const stack = [];
        
        lines.forEach((line, idx) => {
            const trimmed = line.trim();
            if (!trimmed) return;
            
            // 启发式检测标题（数字开头、短行、特定符号结尾）
            const isHeading = /^\d+[\.\s]/.test(trimmed) || 
                             (trimmed.length < 50 && /[：:]$/.test(trimmed)) ||
                             /^第[一二三四五六七八九十\d]+章/.test(trimmed);
            
            const level = isHeading ? (trimmed.match(/^\d+/)?.[0].length || 1) : 99;
            
            const item = {
                id: `line-${idx}`,
                type: isHeading ? 'heading' : 'paragraph',
                level: isHeading ? Math.min(level, 6) : 99,
                content: trimmed,
                html: `<p class="${isHeading ? 'font-bold text-lg' : ''}">${trimmed}</p>`,
                children: []
            };
            
            while (stack.length > 0 && stack[stack.length - 1].level >= level) {
                stack.pop();
            }
            
            if (stack.length === 0) {
                tree.push(item);
            } else {
                stack[stack.length - 1].children.push(item);
            }
            
            if (isHeading) stack.push(item);
        });
        
        return tree;
    }
    
    // 结构比对算法
    compareStructure() {
        if (!this.template || !this.document) return;
        
        const diffs = [];
        const templateNodes = this.flattenTree(this.template.tree);
        const docNodes = this.flattenTree(this.document.tree);
        
        // LCS相似度计算
        const matches = this.findBestMatches(templateNodes, docNodes);
        const matchedDocIds = new Set(matches.map(m => m.doc.id));
        
        // 找出缺失项
        matches.forEach(match => {
            const sim = this.similarity(match.template.content, match.doc.content);
            if (sim < 0.6) {
                diffs.push({
                    type: 'changed',
                    template: match.template.content,
                    actual: match.doc.content,
                    similarity: sim,
                    severity: 'warning'
                });
            }
        });
        
        // 未匹配的模板项（缺失）
        templateNodes.forEach((node, idx) => {
            if (!matches.some(m => m.template.id === node.id)) {
                diffs.push({
                    type: 'missing',
                    template: node.content,
                    severity: 'error'
                });
            }
        });
        
        // 未匹配的文档项（多余）
        docNodes.forEach(node => {
            if (!matchedDocIds.has(node.id)) {
                diffs.push({
                    type: 'extra',
                    content: node.content,
                    severity: 'info'
                });
            }
        });
        
        // 计算匹配度：以模板为基准，统计有多少模板项被成功匹配（相似度>0.8）
        const matchedTemplateCount = matches.filter(m => m.similarity > 0.8).length;
        const totalTemplateCount = templateNodes.length || 1;
        const score = Math.round((matchedTemplateCount / totalTemplateCount) * 100);
        
        this.renderDiffs(diffs, score);
        this.renderCompareView(templateNodes, docNodes, matches);
    }
    
    // 只提取标题节点用于结构比对
    flattenTree(tree, result = []) {
        tree.forEach(node => {
            // 只收集标题类型的节点（type为heading且level不为99）
            if (node.type === 'heading' && node.level !== 99) {
                result.push(node);
            }
            if (node.children) this.flattenTree(node.children, result);
        });
        return result;
    }
    
    findBestMatches(templateNodes, docNodes) {
        const matches = [];
        const usedDoc = new Set();
        
        if (!templateNodes || !docNodes) {
            return matches;
        }
        
        templateNodes.forEach(tNode => {
            let best = null;
            let bestScore = -1;
            
            docNodes.forEach((dNode, idx) => {
                if (usedDoc.has(idx)) return;
                const score = this.similarity(tNode.content, dNode.content);
                if (score > bestScore && score > 0.5) {
                    bestScore = score;
                    best = { template: tNode, doc: dNode, idx, similarity: score };
                }
            });
            
            if (best) {
                usedDoc.add(best.idx);
                matches.push(best);
            }
        });
        
        return matches;
    }
    
    similarity(a, b) {
        // 简化的余弦相似度（基于字符）
        const setA = new Set(a.split(''));
        const setB = new Set(b.split(''));
        const intersection = new Set([...setA].filter(x => setB.has(x)));
        return intersection.size / Math.sqrt(setA.size * setB.size);
    }
    
    // 渲染函数
    renderStructureTree() {
        const container = document.getElementById('structureTree');
        if (!this.document && !this.template) {
            container.innerHTML = '<div class="text-center text-gray-400 mt-20"><i class="fas fa-sitemap text-4xl mb-3 opacity-30"></i><p class="text-sm">请先上传文档</p></div>';
            return;
        }
        
        const tree = this.document ? this.document.tree : this.template.tree;
        const html = this.renderTreeNodes(tree, 0);
        container.innerHTML = `<div class="space-y-1">${html}</div>`;
    }
    
    renderTreeNodes(nodes, depth) {
        if (!nodes || !Array.isArray(nodes)) {
            return '';
        }
        // 只渲染标题节点（type为heading且level不为99）
        return nodes
            .filter(node => node.type === 'heading' && node.level !== 99)
            .map(node => `
                <div class="relative" style="padding-left: ${depth * 16}px">
                    <div class="flex items-center gap-2 p-2 rounded-lg hover:bg-gray-100 cursor-pointer group transition-colors" onclick="app.scrollToNode('${node.id}')">
                        <i class="fas fa-heading text-blue-500 text-xs"></i>
                        <span class="text-sm truncate font-medium text-gray-900">${node.content.substring(0, 50)}${node.content && node.content.length > 50 ? '...' : ''}</span>
                        ${this.hasHeadingChildren(node) ? `<i class="fas fa-chevron-right text-xs text-gray-400 ml-auto"></i>` : ''}
                    </div>
                    ${this.hasHeadingChildren(node) ? `<div class="mt-1">${this.renderTreeNodes(node.children, depth + 1)}</div>` : ''}
                </div>
            `).join('');
    }
    
    // 检查是否有标题类型的子节点
    hasHeadingChildren(node) {
        if (!node.children || node.children.length === 0) return false;
        return node.children.some(child => child.type === 'heading' && child.level !== 99);
    }
    
    renderDocumentPreview() {
        if (!this.document) return;
        
        const contentHTML = this.document.html 
            || (this.document.tree?.length > 0 
                ? this.document.tree.map(node => `<div id="node-${node.id}" class="mb-4 p-2 rounded hover:bg-gray-50 transition-colors">${node.html}</div>`).join('')
                : '<div class="text-gray-500 italic">无法预览文档内容</div>');
        
        document.getElementById('docContent').innerHTML = `
            <div class="prose max-w-none">
                <div class="border-b border-gray-200 pb-4 mb-6">
                    <h1 class="text-2xl font-bold text-gray-900">${this.document.name}</h1>
                    <div class="flex gap-4 mt-2 text-sm text-gray-500">
                        <span><i class="fas fa-file-alt mr-1"></i> ${this.document.type?.toUpperCase() || ''}</span>
                        <span><i class="fas fa-font mr-1"></i> ${this.document.text?.length || 0} 字符</span>
                    </div>
                </div>
                <div class="preview-content">${contentHTML}</div>
            </div>`;
    }
    
    renderDiffs(diffs, score) {
        const container = document.getElementById('diffList');
        const scoreEl = document.getElementById('structureScore');
        
        if (diffs.length === 0) {
            container.innerHTML = '<div class="text-green-600 flex items-center gap-2"><i class="fas fa-check-circle"></i> 结构完全一致</div>';
        } else {
            container.innerHTML = diffs.map(d => `
                <div class="p-2 rounded-lg ${d.type === 'missing' ? 'bg-red-50 text-red-700' : d.type === 'extra' ? 'bg-blue-50 text-blue-700' : 'bg-yellow-50 text-yellow-700'} text-xs">
                    <div class="flex items-center gap-1 font-medium mb-1">
                        <i class="fas ${d.type === 'missing' ? 'fa-times-circle' : d.type === 'extra' ? 'fa-info-circle' : 'fa-exclamation-circle'}"></i>
                        ${d.type === 'missing' ? '缺少章节' : d.type === 'extra' ? '多余内容' : '内容差异'}
                    </div>
                    <div class="truncate">${d.template || d.content || d.actual}</div>
                </div>
            `).join('');
        }
        
        scoreEl.textContent = `匹配度: ${score}%`;
        scoreEl.className = `text-xs px-2 py-1 rounded-full ${score > 80 ? 'bg-green-100 text-green-700' : score > 50 ? 'bg-yellow-100 text-yellow-700' : 'bg-red-100 text-red-700'}`;
        scoreEl.classList.remove('hidden');
        document.getElementById('structureDiff').style.display = 'block';
    }
    
    renderCompareView(templateNodes, docNodes, matches) {
        const renderNode = (node, isMatch) => `
            <div class="p-2 rounded border ${isMatch ? 'border-green-200 bg-green-50' : 'border-gray-200'} text-sm mb-2">
                <div class="flex items-center gap-2">
                    <i class="fas fa-heading text-xs text-gray-400"></i>
                    <span class="${isMatch ? 'text-green-900' : 'text-gray-600'}">${node.content.substring(0, 40)}</span>
                    ${isMatch ? '<i class="fas fa-check text-green-500 ml-auto"></i>' : ''}
                </div>
            </div>`;
        
        document.getElementById('templateStructure').innerHTML = (templateNodes || [])
            .map(n => renderNode(n, matches?.some(m => m.template?.id === n.id))).join('');
        
        document.getElementById('docStructure').innerHTML = (docNodes || [])
            .map(n => renderNode(n, matches?.some(m => m.doc?.id === n.id))).join('');
    }
    
    // 规则管理
    renderRules() {
        const container = document.getElementById('rulesList');
        if (this.rules.length === 0) {
            container.innerHTML = '<div class="text-center text-gray-400 mt-8 text-sm">暂无规则，点击上方添加</div>';
            return;
        }
        
        container.innerHTML = this.rules.map((rule, idx) => {
            const isEnabled = rule.enabled !== false;
            return `
                <div class="p-3 border rounded-xl transition-all duration-200 group ${isEnabled ? 'bg-white border-gray-200 shadow-sm hover:shadow-md' : 'bg-gray-50 border-gray-100 opacity-70'}">
                    <div class="flex items-start justify-between mb-2">
                        <div onclick="app.editRule(${idx})" class="flex items-center gap-2 overflow-hidden cursor-pointer flex-1" title="点击编辑规则">
                            <span class="flex-shrink-0 w-2 h-2 rounded-full ${rule.severity === 'error' ? 'bg-red-500' : rule.severity === 'warning' ? 'bg-yellow-500' : 'bg-blue-500'} shadow-sm"></span>
                            <span class="font-medium text-sm truncate ${isEnabled ? 'text-gray-900 group-hover:text-blue-600' : 'text-gray-400'} transition-colors">${rule.name}</span>
                            <i class="fas fa-edit text-xs text-gray-300 opacity-0 group-hover:opacity-100 transition-opacity"></i>
                        </div>
                        <!-- 简约开关 -->
                        <div onclick="app.toggleRuleStatus(${idx})" 
                            class="relative inline-flex h-5 w-9 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none ${isEnabled ? 'bg-blue-600' : 'bg-gray-200'}"
                            title="${isEnabled ? '点击禁用' : '点击启用'}">
                            <span class="pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${isEnabled ? 'translate-x-4' : 'translate-x-0'}"></span>
                        </div>
                    </div>
                    <p onclick="app.editRule(${idx})" class="text-xs ${isEnabled ? 'text-gray-500' : 'text-gray-400'} line-clamp-2 leading-relaxed cursor-pointer">${rule.prompt}</p>
                </div>
            `;
        }).join('');
    }
    
    toggleRuleStatus(idx) {
        this.rules[idx].enabled = this.rules[idx].enabled === false ? true : false;
        this.saveRules();
        this.renderRules();
    }
    
    addRule() {
        this.currentEditingRule = null;
        document.getElementById('ruleName').value = '';
        document.getElementById('rulePrompt').value = '';
        document.getElementById('ruleSeverity').value = 'warning';
        document.getElementById('ruleModal').classList.remove('hidden');
    }
    
    editRule(idx) {
        this.currentEditingRule = idx;
        const rule = this.rules[idx];
        document.getElementById('ruleName').value = rule.name;
        document.getElementById('rulePrompt').value = rule.prompt;
        document.getElementById('ruleSeverity').value = rule.severity;
        document.getElementById('ruleModal').classList.remove('hidden');
    }
    
    deleteRule(idx) {
        if (confirm('确定删除此规则吗？')) {
            this.rules.splice(idx, 1);
            this.saveRules();
            this.renderRules();
        }
    }
    
    closeRuleModal() {
        document.getElementById('ruleModal').classList.add('hidden');
    }
    
    saveRule() {
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
        
        this.saveRules();
        this.renderRules();
        this.closeRuleModal();
    }
    
    saveRules() {
        localStorage.setItem('smartdoc_rules', JSON.stringify(this.rules));
    }
    
    // 导出规则到文件
    exportRulesToFile() {
        if (this.rules.length === 0) {
            alert('暂无规则可导出');
            return;
        }
        
        const blob = new Blob([JSON.stringify(this.rules, null, 4)], { type: 'application/json' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = 'rules.json';
        a.click();
        alert('规则已导出！请将下载的 rules.json 文件复制到 config 目录下替换原文件。');
    }
    
    // Excel变量插入
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
        
        document.getElementById('excelVarModal').classList.remove('hidden');
    }
    
    insertVar(varStr) {
        const textarea = document.getElementById('rulePrompt');
        const start = textarea.selectionStart;
        const end = textarea.selectionEnd;
        const text = textarea.value;
        textarea.value = text.substring(0, start) + varStr + text.substring(end);
        textarea.selectionStart = textarea.selectionEnd = start + varStr.length;
        document.getElementById('excelVarModal').classList.add('hidden');
    }
    
    // AI审核核心
    async runAudit() {
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
        
        this.setStatus('正在运行AI审核...', true);
        this.auditResults = [];
        const resultsContainer = document.getElementById('auditResults');
        resultsContainer.innerHTML = '<div class="space-y-4" id="auditList"></div>';
        
        for (let i = 0; i < activeRules.length; i++) {
            const rule = activeRules[i];
            this.updateProgress(((i + 1) / activeRules.length) * 100);
            
            const prompt = this.buildPrompt(rule);
            const result = await this.callLLM(prompt, rule);
            this.auditResults.push(result);
            this.renderAuditResult(result, i);
        }
        
        this.updateProgress(0);
        this.setStatus('审核完成');
        document.getElementById('auditBadge').classList.remove('hidden');
        this.switchTab('audit');
    }
    
    buildPrompt(rule) {
        let prompt = rule.prompt;
        
        // 替换Excel变量
        if (this.excelData) {
            prompt = prompt.replace(/\{\{excel\.([^}]+)\}\}/g, (match, path) => {
                const parts = path.split('.');
                if (parts.length >= 2) {
                    const [sheetName, colName] = parts;
                    const sheet = this.excelData.sheets.find(s => s.name === sheetName);
                    if (sheet) {
                        const values = sheet.rows.map(row => row[sheet.headers.indexOf(colName)]).filter(Boolean);
                        return values.join('、');
                    }
                }
                return match;
            });
        }
        
        const context = "文档内容：\n" + 
            this.document.text.substring(0, 10000) +  
            "\n\n审核规则：" + prompt + 
            "\n\n请严格按以下JSON格式返回，不要包含其他内容，不要添加任何解释说明：\n" +
            "{\n" +
            '  "pass": boolean,\n' +
            '  "confidence": 0-100,\n' +
            '  "issues": [{"location": "位置描述", "problem": "问题描述", "suggestion": "修改建议"}],\n' +
            '  "summary": "总体评价"\n' +
            "}";
        
        return context;
    }
    
    async callLLM(prompt, rule) {
        const { endpoint, apiKey, model, auditRole } = this.settings;
        
        try {
            const response = await fetch(endpoint || 'https://api.openai.com/v1/chat/completions', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': 'Bearer ' + apiKey
                },
                body: JSON.stringify({
                    model: model || 'deepseek-chat',
                    messages: [
                        { role: 'system', content: `${auditRole || '专业文档审核专家'}，你擅长发现文档中的结构、逻辑和合规问题。请严格按照要求的JSON格式返回结果，不要添加任何额外说明。` },
                        { role: 'user', content: prompt }
                    ],
                    temperature: 0.1
                })
            });
            
            if (!response.ok) throw new Error('API错误: ' + response.status);
            
            const data = await response.json();
            const content = data.choices[0].message.content;
            
            // 尝试解析JSON - 更强健的处理方式，适应不同大模型的输出格式
            let result;
            try {
                // 首先尝试直接解析整个内容
                try {
                    result = JSON.parse(content);
                } catch {
                    // 尝试多种方式提取JSON
                    let jsonData = null;
                    
                    // 方式1: 查找```json代码块
                    let match = content.match(/```json\s*([\s\S]*?)\s*```/);
                    if (match) jsonData = match[1];
                    
                    // 方式2: 查找```代码块
                    if (!jsonData) {
                        match = content.match(/```\s*([\s\S]*?)\s*```/);
                        if (match) jsonData = match[1];
                    }
                    
                    // 方式3: 查找完整的JSON对象（贪婪匹配）
                    if (!jsonData) {
                        match = content.match(/\{[\s\S]*\}/);
                        if (match) jsonData = match[0];
                    }
                    
                    if (jsonData) {
                        // 清理可能的特殊字符和多余内容
                        const cleanJsonData = jsonData
                            .trim()
                            .replace(/^[\u200B-\u200D\uFEFF]/, '')
                            .replace(/,\s*}/g, '}')  // 移除尾部多余逗号
                            .replace(/,\s*]/g, ']'); // 移除数组尾部多余逗号
                        result = JSON.parse(cleanJsonData);
                    } else {
                        throw new Error('未找到JSON数据');
                    }
                }
                
                // 确保结果包含必要的字段
                result = {
                    pass: result.pass ?? false,
                    confidence: result.confidence ?? 50,
                    issues: result.issues ?? [],
                    summary: result.summary ?? '审核完成'
                };
                
            } catch {
                // 解析失败时，尝试从文本中提取有用信息
                const hasPass = /通过|合格|没有问题|无问题/.test(content);
                const hasFail = /不通过|不合格|存在问题|发现问题/.test(content);
                
                result = {
                    pass: hasPass && !hasFail,
                    confidence: 30,
                    issues: [{ 
                        location: '模型输出', 
                        problem: '返回格式非JSON，以下为原始回复', 
                        suggestion: content.substring(0, 500) 
                    }],
                    summary: hasPass ? '模型认为文档可能通过审核' : '模型认为文档可能存在问题'
                };
            }
            
            return {
                ruleName: rule.name,
                severity: rule.severity,
                ...result
            };
        } catch (err) {
            return {
                ruleName: rule.name,
                severity: rule.severity,
                pass: false,
                confidence: 0,
                issues: [{ location: 'API调用', problem: err.message, suggestion: '请检查网络或API配置' }],
                summary: '调用失败'
            };
        }
    }
    
    renderAuditResult(result, idx) {
        const container = document.getElementById('auditList');
        const div = document.createElement('div');
        div.className = 'bg-white rounded-xl border border-gray-200 p-6 fade-in';
        
        const statusColor = result.pass ? 'green' : 'red';
        const statusIcon = result.pass ? 'check' : 'times';
        const severityClass = result.severity === 'error' ? 'red' : result.severity === 'warning' ? 'yellow' : 'blue';
        
        const issuesHtml = result.issues?.length > 0 
            ? `<div class="space-y-3 mb-4">${result.issues.map(issue => `
                <div class="p-3 bg-gray-50 rounded-lg border-l-4 border-${severityClass}-400">
                    <div class="flex items-start gap-2">
                        <i class="fas fa-map-marker-alt text-gray-400 mt-0.5 text-xs"></i>
                        <div class="flex-1">
                            <div class="text-xs text-gray-500 mb-1">${issue.location}</div>
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
                            <span class="px-2 py-0.5 rounded-full bg-${severityClass}-100 text-${severityClass}-700">${result.severity}</span>
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
    }
    
    // 设置与工具
    toggleSettings() {
        document.getElementById('settingsModal').classList.toggle('hidden');
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
        this.toggleSettings();
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
        const status = document.getElementById('apiStatus');
        if (this.settings.apiKey) {
            status.innerHTML = '<i class="fas fa-circle text-[8px] mr-1"></i> API已配置';
            status.className = 'flex items-center gap-1 text-green-600';
        }
    }
    
    async testConnection() {
        const btn = event.target;
        const originalText = btn.textContent;
        btn.textContent = '测试中...';
        btn.disabled = true;
        
        try {
            const endpoint = document.getElementById('apiEndpoint').value;
            const apiKey = document.getElementById('apiKey').value;
            
            // 直接向聊天补全端点发送一个简单的请求来测试
            // 这是最可靠的方式，因为无论什么API，聊天补全端点都应该可用
            const response = await fetch(endpoint, {
                method: 'POST',
                headers: { 
                    'Authorization': 'Bearer ' + apiKey,
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    model: document.getElementById('apiModel').value || 'deepseek-chat',
                    messages: [
                        { role: 'user', content: '你好' }
                    ],
                    temperature: 0.7,
                    max_tokens: 10
                })
            });
            
            if (response.ok) {
                alert('连接成功！');
            } else {
                const errorData = await response.text();
                alert(`连接失败: ${response.status} - ${response.statusText}\n${errorData}`);
            }
        } catch (err) {
            // 捕获网络错误等
            alert('连接错误: ' + err.message);
        } finally {
            btn.textContent = originalText;
            btn.disabled = false;
        }
    }
    
    // UI辅助
    switchTab(tab) {
        ['preview', 'compare', 'audit'].forEach(t => {
            document.getElementById(`view-${t}`).classList.add('hidden');
            document.getElementById(`tab-${t}`).className = 'px-4 py-2 text-sm font-medium rounded-lg text-gray-600 hover:bg-gray-50';
        });
        document.getElementById(`view-${tab}`).classList.remove('hidden');
        document.getElementById(`tab-${tab}`).className = 'px-4 py-2 text-sm font-medium rounded-lg bg-gray-100 text-gray-900';
    }
    
    scrollToNode(nodeId) {
        const el = document.getElementById(`node-${nodeId}`);
        if (el) {
            el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            el.classList.add('bg-yellow-100');
            setTimeout(() => el.classList.remove('bg-yellow-100'), 2000);
        }
    }
    
    setStatus(text, loading = false) {
        document.getElementById('statusText').innerHTML = loading ? `<span class="spinner inline-block mr-2 align-middle"></span>${text}` : text;
    }
    
    updateProgress(percent) {
        document.getElementById('progressBar').classList.remove('hidden');
        document.getElementById('progressFill').style.width = percent + '%';
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
}

const app = new SmartDocApp();