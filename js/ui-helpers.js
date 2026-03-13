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
    },

    highlightAndScroll(textSnippet, location) {
        const docContent = document.getElementById('docContent');
        if (!docContent) {
            alert('请先上传文档');
            return;
        }

        UiHelpers.clearHighlights();

        let searchText = (textSnippet || '').trim();
        let locationText = (location || '').trim();
        let found = false;

        console.log('跳转搜索:', { textSnippet: searchText, location: locationText });

        if (searchText && searchText.length >= 2) {
            found = UiHelpers._findAndHighlight(docContent, searchText);
        }

        if (!found && locationText) {
            const searchPatterns = UiHelpers._generateSearchPatterns(locationText);
            console.log('搜索模式:', searchPatterns);
            
            for (const pattern of searchPatterns) {
                if (pattern && pattern.length >= 2) {
                    found = UiHelpers._findAndHighlight(docContent, pattern);
                    if (found) {
                        console.log('使用模式找到:', pattern);
                        break;
                    }
                }
            }
        }

        if (!found) {
            const previewContent = docContent.querySelector('.preview-content');
            if (previewContent) {
                if (searchText && searchText.length >= 2) {
                    found = UiHelpers._findAndHighlight(previewContent, searchText);
                }
                if (!found && locationText) {
                    const searchPatterns = UiHelpers._generateSearchPatterns(locationText);
                    for (const pattern of searchPatterns) {
                        if (pattern && pattern.length >= 2) {
                            found = UiHelpers._findAndHighlight(previewContent, pattern);
                            if (found) break;
                        }
                    }
                }
            }
        }

        if (!found) {
            const allText = docContent.textContent;
            console.log('文档内容片段:', allText.substring(0, 500));
            console.log('搜索文本:', searchText || locationText);
            
            const message = `未找到文本：${searchText || locationText}\n\n提示：该位置可能在表格、图片中，或文本已被修改。\n请手动在文档预览中查找。`;
            alert(message);
        }
    },

    _generateSearchPatterns(locationText) {
        const patterns = [];
        
        patterns.push(locationText);
        
        const chapterMatch = locationText.match(/第(\d+)章/);
        const sectionMatch = locationText.match(/第(\d+)节/);
        const chineseNumMatch = locationText.match(/第([一二三四五六七八九十]+)章/);
        const chineseSectionMatch = locationText.match(/第([一二三四五六七八九十]+)节/);
        
        const chineseToNum = { '一': '1', '二': '2', '三': '3', '四': '4', '五': '5', '六': '6', '七': '7', '八': '8', '九': '9', '十': '10', '十一': '11', '十二': '12', '十三': '13', '十四': '14', '十五': '15' };
        
        if (chapterMatch && sectionMatch) {
            const chapter = chapterMatch[1];
            const section = sectionMatch[1];
            patterns.push(`${chapter}.${section}`);
            patterns.push(`${chapter}.${section} `);
            patterns.push(`${chapter}.${section}.`);
            patterns.unshift(`${chapter}.${section}`);
        }
        
        if (chapterMatch) {
            const chapter = chapterMatch[1];
            patterns.unshift(`${chapter}. `);
            patterns.unshift(`${chapter}.`);
            patterns.push(`第${chapter}章`);
        }
        
        if (chineseNumMatch) {
            const chineseNum = chineseNumMatch[1];
            const num = chineseToNum[chineseNum] || chineseNum;
            patterns.unshift(`${num}. `);
            patterns.unshift(`${num}.`);
            patterns.push(`第${num}章`);
        }
        
        if (chineseSectionMatch) {
            const chineseNum = chineseSectionMatch[1];
            const num = chineseToNum[chineseNum] || chineseNum;
            patterns.unshift(`.${num} `);
            patterns.unshift(`.${num}`);
        }
        
        if (chineseNumMatch && chineseSectionMatch) {
            const chapterNum = chineseToNum[chineseNumMatch[1]] || chineseNumMatch[1];
            const sectionNum = chineseToNum[chineseSectionMatch[1]] || chineseSectionMatch[1];
            patterns.unshift(`${chapterNum}.${sectionNum}`);
            patterns.unshift(`${chapterNum}.${sectionNum} `);
        }
        
        const parts = locationText.split(/[，,、\s第章节段]+/).filter(p => p.length >= 2);
        for (const part of parts) {
            if (!patterns.includes(part)) {
                patterns.push(part);
            }
        }
        
        return [...new Set(patterns)];
    },

    _findAndHighlight(container, searchText) {
        if (!searchText || searchText.length < 2) return false;

        const walker = document.createTreeWalker(
            container,
            NodeFilter.SHOW_TEXT,
            null,
            false
        );

        let textNode;
        const search = searchText.trim();

        while ((textNode = walker.nextNode())) {
            const text = textNode.textContent;
            const index = text.indexOf(search);

            if (index !== -1) {
                try {
                    const range = document.createRange();
                    range.setStart(textNode, index);
                    range.setEnd(textNode, index + search.length);

                    const span = document.createElement('span');
                    span.className = 'jump-highlight';
                    range.surroundContents(span);

                    span.scrollIntoView({ behavior: 'smooth', block: 'center' });

                    setTimeout(() => {
                        span.classList.add('fade-out');
                    }, 2000);

                    console.log('找到并高亮:', search);
                    return true;
                } catch (e) {
                    console.warn('高亮失败:', e, '文本:', text.substring(0, 50));
                }
            }
        }

        return false;
    },

    clearHighlights() {
        const highlights = document.querySelectorAll('.jump-highlight');
        highlights.forEach(span => {
            const parent = span.parentNode;
            while (span.firstChild) {
                parent.insertBefore(span.firstChild, span);
            }
            parent.removeChild(span);
        });
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
