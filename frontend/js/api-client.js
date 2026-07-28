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

const LockAPI = {
    async lockGroup(groupId, password) {
        const response = await fetch(`/api/config/rules/${groupId}/lock`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ password })
        });
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || '上锁失败');
        }
        return response.json();
    },

    async unlockGroup(groupId, password) {
        const response = await fetch(`/api/config/rules/${groupId}/unlock`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ password })
        });
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || '解锁失败');
        }
        return response.json();
    },

    async getLockStatus(groupId) {
        const response = await fetch(`/api/config/rules/${groupId}/locked`);
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || '获取锁状态失败');
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
                id: r.id,
                name: r.name,
                prompt: r.prompt,
                severity: r.severity || 'warning',
                enabled: r.enabled !== false,
                sortOrder: r.sortOrder ?? idx,
                triggerCondition: r.triggerCondition || null
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
        const updatedGroup = await response.json();
        return updatedGroup.rules || [];
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
                ${g.locked ? '🔒 ' : ''}${g.name}
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

        const isLocked = app.ruleGroups.find(g => g.groupId === app.currentRuleGroup)?.locked;

        const sorted = rules.slice().sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));

        container.innerHTML = sorted.map((rule, idx) => {
            const isEnabled = rule.enabled !== false;
            const originIdx = rules.indexOf(rule);
            return `
                <div class="p-3 border rounded-xl transition-all duration-200 group ${isEnabled ? 'bg-white border-gray-200 shadow-sm hover:shadow-md' : 'bg-gray-50 border-gray-100 opacity-70'}">
                    <div class="flex items-start justify-between mb-2">
                        <div onclick="${isLocked ? `app.viewRule(${originIdx})` : `app.editRule(${originIdx})`}" class="flex items-center gap-2 overflow-hidden cursor-pointer flex-1" title="${isLocked ? '规则组已上锁，仅查看详情' : '点击编辑规则'}">
                             <span class="flex-shrink-0 w-5 h-5 rounded-full bg-gray-100 text-xs font-bold text-gray-500 flex items-center justify-center">${idx + 1}</span>
                             <span class="flex-shrink-0 w-2 h-2 rounded-full ${rule.severity === 'error' ? 'bg-red-500' : rule.severity === 'warning' ? 'bg-yellow-500' : 'bg-blue-500'} shadow-sm"></span>
                             <span class="font-medium text-sm truncate ${isEnabled ? 'text-gray-900 group-hover:text-blue-600' : 'text-gray-400'} transition-colors">${rule.name}</span>
                            <i class="fas ${isLocked ? 'fa-eye' : 'fa-edit'} text-xs text-gray-300 opacity-0 group-hover:opacity-100 transition-opacity"></i>
                        </div>
                        <div class="flex items-center gap-2 flex-shrink-0">
                            <div onclick="${isLocked ? '' : `app.toggleRuleStatus(${originIdx})`}" 
                                class="relative inline-flex h-5 w-9 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none ${isEnabled ? 'bg-blue-600' : 'bg-gray-200'} ${isLocked ? 'cursor-not-allowed opacity-60' : ''}"
                                title="${isLocked ? '规则组已上锁' : (isEnabled ? '点击禁用' : '点击启用')}">
                                <span class="pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${isEnabled ? 'translate-x-4' : 'translate-x-0'}"></span>
                            </div>
                            <button onclick="${isLocked ? '' : `app.deleteRule(${originIdx})`}" 
                                class="w-5 h-5 flex items-center justify-center text-gray-300 hover:text-red-500 transition-colors ${isLocked ? 'cursor-not-allowed opacity-30' : ''}"
                                title="${isLocked ? '规则组已上锁' : '删除规则'}">
                                <i class="fas fa-trash-alt text-xs"></i>
                            </button>
                        </div>
                    </div>
                    <p onclick="${isLocked ? `app.viewRule(${originIdx})` : `app.editRule(${originIdx})`}" class="text-xs ${isEnabled ? 'text-gray-500' : 'text-gray-400'} line-clamp-2 leading-relaxed cursor-pointer" title="${isLocked ? '规则组已上锁，仅查看详情' : '点击编辑规则'}">${rule.prompt}</p>
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
        } else if (ext === 'doc') {
            const result = await this.parseDoc(arrayBuffer, file.name);
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

    async parseDoc(arrayBuffer, fileName) {
        const formData = new FormData();
        const blob = new Blob([arrayBuffer], { type: 'application/msword' });
        formData.append('file', blob, fileName);

        const response = await fetch('/api/parse', {
            method: 'POST',
            body: formData
        });

        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || 'DOC文件解析失败');
        }

        const result = await response.json();
        const text = result.text || '';
        const tree = text ? this.buildTreeFromText(text) : [];

        return { text, tree, html: `<div>${this.escapeHtml(text).replace(/\n/g, '<br>')}</div>` };
    },

    async parseDocx(arrayBuffer) {
        const zip = await JSZip.loadAsync(arrayBuffer);
        const docXml = await zip.file('word/document.xml').async('text');
        const numberingXml = zip.file('word/numbering.xml')
            ? await zip.file('word/numbering.xml').async('text')
            : '';
        const stylesXml = zip.file('word/styles.xml')
            ? await zip.file('word/styles.xml').async('text')
            : '';

        const parser = new DOMParser();
        const doc = parser.parseFromString(docXml, 'text/xml');
        const numberingDoc = numberingXml ? parser.parseFromString(numberingXml, 'text/xml') : null;
        const stylesDoc = stylesXml ? parser.parseFromString(stylesXml, 'text/xml') : null;
        const numbering = this._parseDocxNumbering(numberingDoc, stylesDoc);
        const counters = {};
        const outlineCounters = [];

        const paragraphs = this._xmlDescendants(doc, 'p');
        let text = '';
        const flatNodes = [];
        const paragraphInfos = [];
        let nodeId = 0;

        paragraphs.forEach(p => {
            const rawContent = this._readDocxParagraphText(p);
            const numPr = this._getDocxParagraphNumbering(p, numbering);
            let prefix = this._formatDocxNumberPrefix(numbering, numPr, counters);
            if (!prefix) {
                prefix = this._formatDocxOutlineNumberPrefix(
                    this._getDocxParagraphOutlineLevel(p, numbering),
                    outlineCounters
                );
            }
            const content = this._joinDocxNumberPrefix(prefix, rawContent);

            if (content.trim()) {
                text += content + '\n';
                paragraphInfos.push({ rawContent, content, prefix });

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

        let html = '';
        try {
            if (typeof mammoth !== 'undefined') {
                const result = await mammoth.convertToHtml({arrayBuffer: arrayBuffer}, {
                    convertImage: mammoth.images.imgElement(function(image) {
                        return image.read("base64").then(function(imageBuffer) {
                            return {
                                src: "data:" + image.contentType + ";base64," + imageBuffer
                            };
                        });
                    })
                });
                const numberedHtml = this._applyDocxNumberPrefixesToHtml(result.value, paragraphInfos);
                html = `<div class="mammoth-output">${numberedHtml}</div>`;
            } else {
                html = `<pre class="whitespace-pre-wrap">${this.escapeHtml(text)}</pre>`;
            }
        } catch (e) {
            console.error('mammoth转换失败:', e);
            html = `<pre class="whitespace-pre-wrap">${this.escapeHtml(text)}</pre>`;
        }

        return { text, tree, html };
    },

    _parseDocxNumbering(numberingDoc, stylesDoc) {
        const numbering = {
            abstractNums: {},
            nums: {},
            styleNumbering: {},
            styleRules: {}
        };

        if (numberingDoc) {
            this._xmlDescendants(numberingDoc, 'abstractNum').forEach(abstractNum => {
                const abstractId = this._xmlAttr(abstractNum, 'abstractNumId');
                if (!abstractId) return;

                const levels = {};
                this._xmlChildren(abstractNum, 'lvl').forEach(lvl => {
                    const ilvl = this._xmlAttr(lvl, 'ilvl') || '0';
                    levels[ilvl] = {
                        ilvl,
                        start: parseInt(this._xmlAttr(this._xmlFirst(lvl, 'start'), 'val') || '1', 10),
                        numFmt: this._xmlAttr(this._xmlFirst(lvl, 'numFmt'), 'val') || 'decimal',
                        lvlText: this._xmlAttr(this._xmlFirst(lvl, 'lvlText'), 'val') || `%${Number(ilvl) + 1}.`,
                        pStyle: this._xmlAttr(this._xmlFirst(lvl, 'pStyle'), 'val') || ''
                    };
                });
                numbering.abstractNums[abstractId] = {
                    abstractId,
                    levels,
                    numStyleLink: this._xmlAttr(this._xmlFirst(abstractNum, 'numStyleLink'), 'val') || '',
                    styleLink: this._xmlAttr(this._xmlFirst(abstractNum, 'styleLink'), 'val') || ''
                };
            });

            this._xmlChildren(numberingDoc.documentElement, 'num').forEach(num => {
                const numId = this._xmlAttr(num, 'numId');
                const abstractId = this._xmlAttr(this._xmlFirst(num, 'abstractNumId'), 'val');
                if (numId && abstractId) {
                    numbering.nums[numId] = { numId, abstractId };
                }
            });

            Object.keys(numbering.nums).forEach(numId => {
                const abstractNum = numbering.abstractNums[numbering.nums[numId].abstractId];
                if (!abstractNum) return;
                Object.keys(abstractNum.levels).forEach(ilvl => {
                    const level = abstractNum.levels[ilvl];
                    if (level.pStyle && !numbering.styleNumbering[level.pStyle]) {
                        numbering.styleNumbering[level.pStyle] = { numId, ilvl };
                    }
                });
            });
        }

        if (stylesDoc) {
            this._xmlDescendants(stylesDoc, 'style').forEach(style => {
                const styleType = this._xmlAttr(style, 'type');
                if (styleType !== 'paragraph' && styleType !== 'numbering') return;
                const styleId = this._xmlAttr(style, 'styleId');
                const pPr = this._xmlFirst(style, 'pPr');
                const numPr = pPr ? this._xmlFirst(pPr, 'numPr') : null;
                if (!styleId) return;

                numbering.styleRules[styleId] = {
                    type: styleType,
                    numId: numPr ? this._xmlAttr(this._xmlFirst(numPr, 'numId'), 'val') : '',
                    ilvl: numPr ? this._xmlAttr(this._xmlFirst(numPr, 'ilvl'), 'val') : '',
                    basedOn: this._xmlAttr(this._xmlFirst(style, 'basedOn'), 'val') || '',
                    outlineLevel: this._xmlAttr(this._xmlFirst(pPr, 'outlineLvl'), 'val') || '',
                    name: this._xmlAttr(this._xmlFirst(style, 'name'), 'val') || ''
                };
            });
        }

        return numbering;
    },

    _getDocxParagraphNumbering(paragraph, numbering) {
        const pPr = this._xmlFirst(paragraph, 'pPr');
        const styleId = this._xmlAttr(this._xmlFirst(pPr, 'pStyle'), 'val');
        const numPr = this._xmlFirst(pPr, 'numPr');
        let numId = this._xmlAttr(this._xmlFirst(numPr, 'numId'), 'val');
        let ilvl = this._xmlAttr(this._xmlFirst(numPr, 'ilvl'), 'val');

        if (!numId && styleId) {
            const styleRule = this._resolveDocxStyleNumbering(numbering, styleId);
            if (styleRule) {
                numId = styleRule.numId;
                ilvl = styleRule.ilvl;
            }
        }

        if (!numId && styleId && numbering.styleNumbering[styleId]) {
            numId = numbering.styleNumbering[styleId].numId;
            ilvl = numbering.styleNumbering[styleId].ilvl;
        }

        if (numId && (ilvl === '' || ilvl == null) && styleId) {
            ilvl = this._inferDocxNumberLevel(numbering, numId, styleId);
        }

        if (!numId) return null;
        return { numId, ilvl: ilvl === '' || ilvl == null ? '0' : String(ilvl) };
    },

    _getDocxParagraphOutlineLevel(paragraph, numbering) {
        const pPr = this._xmlFirst(paragraph, 'pPr');
        const directOutline = this._xmlAttr(this._xmlFirst(pPr, 'outlineLvl'), 'val');
        if (directOutline !== '') {
            return Number(directOutline);
        }

        const styleId = this._xmlAttr(this._xmlFirst(pPr, 'pStyle'), 'val');
        return this._getDocxStyleOutlineLevel(numbering, styleId);
    },

    _getDocxStyleOutlineLevel(numbering, styleId, seen = {}) {
        if (!styleId || seen[styleId]) return -1;
        seen[styleId] = true;

        const rule = numbering.styleRules[styleId];
        if (rule && rule.outlineLevel !== '') {
            return Number(rule.outlineLevel);
        }

        const inferred = this._inferOutlineLevelFromStyleName(styleId, rule?.name || '');
        if (inferred >= 0) {
            return inferred;
        }

        return rule ? this._getDocxStyleOutlineLevel(numbering, rule.basedOn, seen) : -1;
    },

    _inferOutlineLevelFromStyleName(styleId, styleName) {
        const value = `${styleId || ''} ${styleName || ''}`.toLowerCase();
        for (let i = 1; i <= 9; i++) {
            if (
                value.includes(`heading${i}`) ||
                value.includes(`heading ${i}`) ||
                value.includes(`标题${i}`) ||
                value.includes(`標題${i}`)
            ) {
                return i - 1;
            }
        }
        return -1;
    },

    _formatDocxOutlineNumberPrefix(outlineLevel, counters) {
        if (outlineLevel < 0 || outlineLevel > 8 || Number.isNaN(outlineLevel)) {
            return '';
        }

        for (let i = 0; i < outlineLevel; i++) {
            if (counters[i] == null || counters[i] === 0) {
                counters[i] = 1;
            }
        }
        counters[outlineLevel] = (counters[outlineLevel] || 0) + 1;
        counters.length = outlineLevel + 1;

        return counters.slice(0, outlineLevel + 1).join('.') + '. ';
    },

    _resolveDocxStyleNumbering(numbering, styleId, seen = {}) {
        if (!styleId || seen[styleId]) return null;
        seen[styleId] = true;

        const rule = numbering.styleRules[styleId];
        if (!rule) return null;
        if (rule.numId) {
            return {
                numId: rule.numId,
                ilvl: rule.ilvl || this._inferDocxNumberLevel(numbering, rule.numId, styleId) || '0'
            };
        }

        return this._resolveDocxStyleNumbering(numbering, rule.basedOn, seen);
    },

    _inferDocxNumberLevel(numbering, numId, styleId) {
        const num = numbering.nums[numId];
        const abstractNum = this._resolveDocxAbstractNum(numbering, num ? num.abstractId : null);
        if (!abstractNum) return '0';

        const matched = Object.keys(abstractNum.levels).find(ilvl => {
            return abstractNum.levels[ilvl].pStyle === styleId;
        });
        return matched || '0';
    },

    _formatDocxNumberPrefix(numbering, numPr, counters) {
        if (!numPr) return '';

        const num = numbering.nums[numPr.numId];
        const abstractNum = this._resolveDocxAbstractNum(numbering, num ? num.abstractId : null);
        if (!abstractNum) return '';

        const ilvl = parseInt(numPr.ilvl || '0', 10);
        const level = abstractNum.levels[String(ilvl)];
        if (!level) return '';

        const key = numPr.numId;
        counters[key] = counters[key] || [];
        for (let i = 0; i <= ilvl; i++) {
            if (counters[key][i] == null) {
                const start = abstractNum.levels[String(i)]?.start || 1;
                counters[key][i] = start - 1;
            }
        }
        counters[key][ilvl] += 1;
        counters[key].length = ilvl + 1;

        const prefix = level.lvlText.replace(/%([1-9])/g, (_, levelIndex) => {
            const index = Number(levelIndex) - 1;
            const value = counters[key][index];
            const refLevel = abstractNum.levels[String(index)] || level;
            return value == null ? '' : this._formatDocxNumberValue(value, refLevel.numFmt);
        });

        return prefix && !/\s$/.test(prefix) ? prefix + ' ' : prefix;
    },

    _formatDocxNumberValue(value, numFmt) {
        if (numFmt === 'upperLetter' || numFmt === 'lowerLetter') {
            let n = value;
            let text = '';
            while (n > 0) {
                n -= 1;
                text = String.fromCharCode(65 + (n % 26)) + text;
                n = Math.floor(n / 26);
            }
            return numFmt === 'lowerLetter' ? text.toLowerCase() : text;
        }

        if (numFmt === 'upperRoman' || numFmt === 'lowerRoman') {
            const roman = this._toRoman(value);
            return numFmt === 'lowerRoman' ? roman.toLowerCase() : roman;
        }

        return String(value);
    },

    _resolveDocxAbstractNum(numbering, abstractId, seen = {}) {
        if (!abstractId || seen[abstractId]) return null;
        seen[abstractId] = true;

        const abstractNum = numbering.abstractNums[abstractId];
        if (!abstractNum) return null;
        if (Object.keys(abstractNum.levels || {}).length > 0) {
            return abstractNum;
        }

        const linkedStyleId = abstractNum.numStyleLink || abstractNum.styleLink;
        const linkedStyle = linkedStyleId ? numbering.styleRules[linkedStyleId] : null;
        const linkedNum = linkedStyle && linkedStyle.numId ? numbering.nums[linkedStyle.numId] : null;
        return linkedNum
            ? this._resolveDocxAbstractNum(numbering, linkedNum.abstractId, seen)
            : abstractNum;
    },

    _toRoman(value) {
        const pairs = [
            [1000, 'M'], [900, 'CM'], [500, 'D'], [400, 'CD'],
            [100, 'C'], [90, 'XC'], [50, 'L'], [40, 'XL'],
            [10, 'X'], [9, 'IX'], [5, 'V'], [4, 'IV'], [1, 'I']
        ];
        let n = value;
        let result = '';
        pairs.forEach(([amount, symbol]) => {
            while (n >= amount) {
                result += symbol;
                n -= amount;
            }
        });
        return result;
    },

    _joinDocxNumberPrefix(prefix, content) {
        if (!prefix) return content;
        const trimmedPrefix = prefix.trim();
        const trimmedContent = content.trimStart();
        return trimmedContent.startsWith(trimmedPrefix) ? content : prefix + content;
    },

    _readDocxParagraphText(paragraph) {
        let content = '';
        this._xmlDescendants(paragraph, '*').forEach(node => {
            if (node.localName === 't') {
                content += node.textContent || '';
            } else if (node.localName === 'tab') {
                content += '\t';
            } else if (node.localName === 'br' || node.localName === 'cr') {
                content += '\n';
            }
        });
        return content;
    },

    _applyDocxNumberPrefixesToHtml(html, paragraphInfos) {
        if (!html || !paragraphInfos.some(p => p.prefix)) return html;

        const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html');
        const blocks = Array.from(doc.body.firstElementChild.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li'));
        let index = 0;

        blocks.forEach(block => {
            while (index < paragraphInfos.length && !paragraphInfos[index].rawContent.trim()) {
                index += 1;
            }
            if (index >= paragraphInfos.length) return;

            const info = paragraphInfos[index];
            index += 1;
            if (!info.prefix) return;

            const blockText = (block.textContent || '').trim();
            if (blockText === info.rawContent.trim() || blockText.endsWith(info.rawContent.trim())) {
                block.insertBefore(doc.createTextNode(info.prefix), block.firstChild);
            }
        });

        return doc.body.firstElementChild.innerHTML;
    },

    _xmlDescendants(node, localName) {
        if (!node) return [];
        if (localName === '*') {
            return Array.from(node.getElementsByTagName('*'));
        }
        return Array.from(node.getElementsByTagName('*')).filter(child => child.localName === localName);
    },

    _xmlChildren(node, localName) {
        if (!node) return [];
        return Array.from(node.childNodes).filter(child => child.nodeType === 1 && child.localName === localName);
    },

    _xmlFirst(node, localName) {
        return this._xmlChildren(node, localName)[0] || null;
    },

    _xmlAttr(node, name) {
        if (!node) return '';
        return node.getAttribute(`w:${name}`) || node.getAttribute(name) || '';
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

    _headingHtml(content, level) {
        const sizes = { 1: 'text-lg', 2: 'text-base', 3: 'text-sm' };
        const cls = `font-bold ${sizes[level] || 'text-sm'}`;
        return `<p class="${cls}" style="margin:0.75rem 0 0.25rem">${this.escapeHtml(content)}</p>`;
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
        const flatNodes = [];
        let nodeId = 0;

        lines.forEach(line => {
            const trimmed = line.trim();
            if (!trimmed) return;

            const level = this.detectHeadingLevel(trimmed);
            const nodeType = level > 0 ? 'heading' : 'paragraph';
            flatNodes.push({
                    id: 'node-' + (nodeId++),
                    type: nodeType,
                    level: level,
                    content: trimmed,
                    html: nodeType === 'heading'
                        ? this._headingHtml(trimmed, level)
                        : `<p>${this.escapeHtml(trimmed)}</p>`,
                    children: []
                });
        });

        return this.buildHeadingTree(flatNodes);
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

    renderResult(result, container, resultIndex) {
        container.innerHTML = '';
        const div = document.createElement('div');
        div.className = 'bg-white rounded-xl border border-gray-200 p-6 fade-in';

        const isSkipped = result.summary && (result.summary.startsWith('已跳过:') || result.summary.startsWith('未匹配关键词:') || result.summary.startsWith('触发条件'));

        if (isSkipped) {
            div.className = 'bg-gray-50 rounded-xl border border-gray-300 p-6 fade-in opacity-75';

            const isKeywordMiss = result.summary && result.summary.startsWith('未匹配关键词:');
            const isTriggerCondition = result.summary && result.summary.startsWith('触发条件');
            const skipLabel = isKeywordMiss ? '未匹配' : isTriggerCondition ? '条件不满足' : '已跳过';
            const skipDesc = isKeywordMiss
                ? 'AI 已完成审核，但文档中未找到所需关键字'
                : isTriggerCondition
                    ? '此规则因触发条件不满足而被跳过，未进行实际审核'
                    : '此规则因缺少必要数据而被跳过，未进行实际审核';

            div.innerHTML = `
                <div class="flex items-center justify-between mb-4">
                    <div class="flex items-center gap-3">
                        <div class="w-10 h-10 rounded-full flex items-center justify-center bg-gray-200 text-gray-500">
                            <i class="fas fa-forward text-lg"></i>
                        </div>
                        <div>
                            <h3 class="font-semibold text-gray-600">${result.ruleName}</h3>
                            <div class="flex items-center gap-2 text-xs text-gray-500">
                                <span class="px-2 py-0.5 rounded-full bg-gray-200 text-gray-600">${skipLabel}</span>
                                <span>置信度: ${result.confidence}%</span>
                            </div>
                        </div>
                    </div>
                </div>
                <div class="text-sm text-gray-500 mb-4">
                    <i class="fas fa-info-circle mr-1"></i> ${skipDesc}
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
                            <div class="text-xs text-gray-500 mb-1">
                                <span>${issue.location || '未知位置'}</span>
                                <button onclick="AiAudit.jumpToLocation('${(issue.textSnippet || issue.location || '').replace(/'/g, "\\'")}', '${(issue.location || '').replace(/'/g, "\\'")}')" class="ml-2 text-blue-500 hover:text-blue-700" title="跳转到文档位置"><i class="fas fa-location-arrow text-xs"></i></button>
                            </div>
                            <div class="text-sm text-gray-900 mb-1">${issue.problem}</div>
                            ${issue.suggestion ? `<div class="text-xs text-blue-600 bg-blue-50 p-2 rounded mt-1"><i class="fas fa-lightbulb mr-1"></i> ${issue.suggestion}</div>` : ''}
                        </div>
                    </div>
                </div>`).join('')}</div>`
            : '<div class="text-sm text-green-600 mb-4"><i class="fas fa-check-circle mr-1"></i> 未发现问题</div>';
        
        const feedbackLabelId = 'feedback-label-' + resultIndex;
        const feedbackStatus = result._feedbackType
            ? (result._feedbackType === 'ACCURATE'
                ? '<span class="text-green-600"><i class="fas fa-check mr-1"></i>准确</span>'
                : '<span class="text-red-600"><i class="fas fa-times mr-1"></i>不准确</span>')
            : '<span class="text-gray-400"><i class="fas fa-comment mr-1"></i>反馈</span>';
        
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
            <div class="flex items-center justify-between pt-3 border-t border-gray-100">
                <div class="text-xs text-gray-500">
                    <i class="fas fa-quote-left mr-1 opacity-50"></i> ${result.summary}
                </div>
                <button onclick="app.openFeedbackModal(${resultIndex})" class="text-xs flex items-center gap-1 px-2 py-1 rounded-lg hover:bg-gray-100 transition-colors flex-shrink-0 ml-2" id="${feedbackLabelId}">
                    ${feedbackStatus}
                </button>
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
    exportHtml(docObj, template, excelData, auditResults) {
        const html = `
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>审核报告 - ${docObj?.name || '未知文档'}</title>
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
    <p><strong>文档名称:</strong> ${docObj?.name || '未知'}</p>
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

const FeedbackAPI = {
    async saveAuditResults(results, groupId, durationMs, ticketId, ts) {
        let url = '/api/feedback/save';
        const params = [];
        if (groupId) {
            params.push('groupId=' + encodeURIComponent(groupId));
        }
        if (durationMs != null) {
            params.push('durationMs=' + durationMs);
        }
        if (ticketId) {
            params.push('ticketId=' + encodeURIComponent(ticketId));
        }
        if (ts) {
            params.push('ts=' + encodeURIComponent(ts));
        }
        if (params.length > 0) {
            url += '?' + params.join('&');
        }
        const response = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(results)
        });
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || '保存审核结果失败');
        }
        return response.json();
    },

    async submitFeedback(feedbackId, feedbackType, reason) {
        const response = await fetch(`/api/feedback/${feedbackId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ feedbackType, reason: reason || '' })
        });
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || '提交反馈失败');
        }
        return response.json();
    },

    async getRuleStats(ruleId, startDate, endDate) {
        let url = `/api/feedback/stats/${ruleId}`;
        const params = [];
        if (startDate) params.push('startDate=' + encodeURIComponent(startDate));
        if (endDate) params.push('endDate=' + encodeURIComponent(endDate));
        if (params.length > 0) url += '?' + params.join('&');
        const response = await fetch(url);
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || '获取统计信息失败');
        }
        return response.json();
    },

    async getAuditRecordByTicketIdAndTs(ticketId, ts) {
        const url = `/api/ticket/audit-record?ticketId=${encodeURIComponent(ticketId)}&ts=${encodeURIComponent(ts)}`;
        const response = await fetch(url);
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || '查询历史审核记录失败');
        }
        return response.json();
    }
};
