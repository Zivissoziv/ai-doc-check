const UiHelpers = {
    switchTab(tab) {
        ['preview', 'compare', 'audit'].forEach(t => {
            document.getElementById(`view-${t}`).classList.add('hidden');
            document.getElementById(`tab-${t}`).className = 'px-4 py-2 text-sm font-medium rounded-lg text-gray-600 hover:bg-gray-50';
        });
        document.getElementById(`view-${tab}`).classList.remove('hidden');
        document.getElementById(`tab-${tab}`).className = 'px-4 py-2 text-sm font-medium rounded-lg bg-gray-100 text-gray-900';
    },

    setStatus(text, loading = false) {
        document.getElementById('statusText').innerHTML = loading ? 
            `<span class="spinner inline-block mr-2 align-middle"></span>${text}` : text;
    },

    updateProgress(percent) {
        document.getElementById('progressBar').classList.remove('hidden');
        document.getElementById('progressFill').style.width = percent + '%';
    },

    hideProgress() {
        document.getElementById('progressBar').classList.add('hidden');
    },

    scrollToNode(nodeId) {
        const el = document.getElementById(`node-${nodeId}`);
        if (el) {
            el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            el.classList.add('bg-yellow-100');
            setTimeout(() => el.classList.remove('bg-yellow-100'), 2000);
        }
    },

    updateWordCount(count) {
        document.getElementById('wordCount').textContent = `字数: ${count}`;
    },

    updateApiStatus(hasKey) {
        const status = document.getElementById('apiStatus');
        if (hasKey) {
            status.innerHTML = '<i class="fas fa-circle text-[8px] mr-1"></i> API已配置';
            status.className = 'flex items-center gap-1 text-green-600';
        } else {
            status.innerHTML = '<i class="fas fa-circle text-[8px]"></i> API未配置';
            status.className = 'flex items-center gap-1 text-yellow-600';
        }
    },

    toggleModal(modalId, show) {
        const modal = document.getElementById(modalId);
        if (modal) {
            if (show) {
                modal.classList.remove('hidden');
            } else {
                modal.classList.add('hidden');
            }
        }
    }
};

const StructureCompare = {
    flattenTree(tree, result = []) {
        tree.forEach(node => {
            if (node.type === 'heading' && node.level !== 99) {
                result.push(node);
            }
            if (node.children) this.flattenTree(node.children, result);
        });
        return result;
    },

    similarity(a, b) {
        const setA = new Set(a.split(''));
        const setB = new Set(b.split(''));
        const intersection = new Set([...setA].filter(x => setB.has(x)));
        return intersection.size / Math.sqrt(setA.size * setB.size);
    },

    findBestMatches(templateNodes, docNodes) {
        const matches = [];
        const usedDoc = new Set();
        
        if (!templateNodes || !docNodes) return matches;
        
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
    },

    compare(template, document) {
        if (!template || !document) return null;
        
        const diffs = [];
        const templateNodes = this.flattenTree(template.tree);
        const docNodes = this.flattenTree(document.tree);
        
        const matches = this.findBestMatches(templateNodes, docNodes);
        const matchedDocIds = new Set(matches.map(m => m.doc.id));
        
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
        
        templateNodes.forEach((node) => {
            if (!matches.some(m => m.template.id === node.id)) {
                diffs.push({
                    type: 'missing',
                    template: node.content,
                    severity: 'error'
                });
            }
        });
        
        docNodes.forEach(node => {
            if (!matchedDocIds.has(node.id)) {
                diffs.push({
                    type: 'extra',
                    content: node.content,
                    severity: 'info'
                });
            }
        });
        
        const matchedTemplateCount = matches.filter(m => m.similarity > 0.8).length;
        const totalTemplateCount = templateNodes.length || 1;
        const score = Math.round((matchedTemplateCount / totalTemplateCount) * 100);
        
        return { diffs, score, templateNodes, docNodes, matches };
    },

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
    },

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
};

const TreeRenderer = {
    render(tree, containerId) {
        const container = document.getElementById(containerId);
        if (!container) return;
        
        if (!tree || tree.length === 0) {
            container.innerHTML = '<div class="text-center text-gray-400 mt-20"><i class="fas fa-sitemap text-4xl mb-3 opacity-30"></i><p class="text-sm">请先上传文档</p></div>';
            return;
        }
        
        const html = this.renderNodes(tree, 0);
        container.innerHTML = `<div class="space-y-1">${html}</div>`;
    },

    renderNodes(nodes, depth) {
        if (!nodes || !Array.isArray(nodes)) return '';
        
        return nodes
            .filter(node => node.type === 'heading' && node.level !== 99)
            .map(node => `
                <div class="relative" style="padding-left: ${depth * 16}px">
                    <div class="flex items-center gap-2 p-2 rounded-lg hover:bg-gray-100 cursor-pointer group transition-colors" onclick="app.scrollToNode('${node.id}')">
                        <i class="fas fa-heading text-blue-500 text-xs"></i>
                        <span class="text-sm truncate font-medium text-gray-900">${node.content.substring(0, 50)}${node.content && node.content.length > 50 ? '...' : ''}</span>
                        ${this.hasHeadingChildren(node) ? `<i class="fas fa-chevron-right text-xs text-gray-400 ml-auto"></i>` : ''}
                    </div>
                    ${this.hasHeadingChildren(node) ? `<div class="mt-1">${this.renderNodes(node.children, depth + 1)}</div>` : ''}
                </div>
            `).join('');
    },

    hasHeadingChildren(node) {
        if (!node.children || node.children.length === 0) return false;
        return node.children.some(child => child.type === 'heading' && child.level !== 99);
    }
};

const DocumentRenderer = {
    render(doc, containerId) {
        if (!doc) return;
        
        const container = document.getElementById(containerId);
        if (!container) return;
        
        const contentHTML = doc.html 
            || (doc.tree?.length > 0 
                ? doc.tree.map(node => `<div id="node-${node.id}" class="mb-4 p-2 rounded hover:bg-gray-50 transition-colors">${node.html}</div>`).join('')
                : '<div class="text-gray-500 italic">无法预览文档内容</div>');
        
        container.innerHTML = `
            <div class="prose max-w-none">
                <div class="border-b border-gray-200 pb-4 mb-6">
                    <h1 class="text-2xl font-bold text-gray-900">${doc.name}</h1>
                    <div class="flex gap-4 mt-2 text-sm text-gray-500">
                        <span><i class="fas fa-file-alt mr-1"></i> ${doc.type?.toUpperCase() || ''}</span>
                        <span><i class="fas fa-font mr-1"></i> ${doc.text?.length || 0} 字符</span>
                    </div>
                </div>
                <div class="preview-content">${contentHTML}</div>
            </div>`;
    }
};
