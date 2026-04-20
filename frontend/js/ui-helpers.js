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

        const search = searchText.trim();
        console.log('_findAndHighlight 搜索:', search);

        const walker = document.createTreeWalker(
            container,
            NodeFilter.SHOW_TEXT,
            null,
            false
        );

        const textNodes = [];
        let node;
        while ((node = walker.nextNode())) {
            textNodes.push(node);
        }

        for (const textNode of textNodes) {
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

        const fullText = textNodes.map(n => n.textContent).join('');
        const globalIndex = fullText.indexOf(search);
        
        if (globalIndex !== -1) {
            console.log('在合并文本中找到，位置:', globalIndex);
            
            let currentPos = 0;
            for (const textNode of textNodes) {
                const nodeLength = textNode.textContent.length;
                const nodeStart = currentPos;
                const nodeEnd = currentPos + nodeLength;

                if (globalIndex >= nodeStart && globalIndex < nodeEnd) {
                    const localIndex = globalIndex - nodeStart;
                    const availableLength = nodeLength - localIndex;
                    const matchLength = Math.min(search.length, availableLength);

                    try {
                        const range = document.createRange();
                        range.setStart(textNode, localIndex);
                        range.setEnd(textNode, localIndex + matchLength);

                        const span = document.createElement('span');
                        span.className = 'jump-highlight';
                        range.surroundContents(span);

                        span.scrollIntoView({ behavior: 'smooth', block: 'center' });

                        setTimeout(() => {
                            span.classList.add('fade-out');
                        }, 2000);

                        console.log('找到并高亮(部分匹配):', search.substring(0, matchLength));
                        return true;
                    } catch (e) {
                        console.warn('高亮失败:', e);
                    }
                }

                currentPos += nodeLength;
            }
        }

        console.log('未找到:', search);
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
                const hasHeadingChildren = node.children && node.children.some(c => c.type === 'heading' && c.level !== 99);
                result.push({
                    id: node.id,
                    type: node.type,
                    level: node.level,
                    content: node.content,
                    html: node.html,
                    hasHeadingChildren: hasHeadingChildren,
                    originalNode: node
                });
            }
            if (node.children) {
                this.flattenTree(node.children, result);
            }
        });
        return result;
    },

    normalizeTitle(text) {
        if (!text) return '';
        let normalized = text.trim();
        normalized = normalized.replace(/[^\w\u4e00-\u9fa5\d\.\-\s]/g, '');
        normalized = normalized.replace(/\s+/g, ' ');
        return normalized.toLowerCase();
    },

    extractTitleKey(text) {
        if (!text) return '';
        const match = text.match(/^(\d+[\.\、]|[一二三四五六七八九十]+[\.\、章节])/);
        if (match) {
            return match[1];
        }
        return '';
    },

    extractFullKey(text) {
        if (!text) return '';
        const patterns = [
            /^第([一二三四五六七八九十百]+)章\s*(第([一二三四五六七八九十百]+)节)?/,
            /^(\d+)\.(\d+)\.(\d+)/,
            /^(\d+)\.(\d+)/,
            /^(\d+)[\.\、]/
        ];
        
        for (const pattern of patterns) {
            const match = text.match(pattern);
            if (match) {
                return match[0].trim();
            }
        }
        return '';
    },

    similarity(a, b) {
        if (!a || !b) return 0;
        if (a === b) return 1;
        
        const normA = this.normalizeTitle(a);
        const normB = this.normalizeTitle(b);
        
        if (normA === normB) return 1;
        
        const keyA = this.extractTitleKey(a);
        const keyB = this.extractTitleKey(b);
        
        if (keyA && keyB && keyA === keyB) {
            const restA = a.replace(keyA, '').trim();
            const restB = b.replace(keyB, '').trim();
            if (restA === restB) return 1;
            
            const setA = new Set(restA.split(''));
            const setB = new Set(restB.split(''));
            const intersection = new Set([...setA].filter(x => setB.has(x)));
            const baseScore = intersection.size / Math.sqrt(setA.size * setB.size);
            return 0.7 + 0.3 * baseScore;
        }
        
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
                
                const levelMatch = tNode.level === dNode.level;
                const titleScore = this.similarity(tNode.content, dNode.content);
                
                let finalScore = titleScore;
                if (levelMatch && titleScore > 0.5) {
                    finalScore = titleScore + 0.2;
                }
                
                if (finalScore > bestScore && finalScore > 0.6) {
                    bestScore = finalScore;
                    best = { template: tNode, doc: dNode, idx, similarity: titleScore, levelMatch };
                }
            });
            
            if (best) {
                usedDoc.add(best.idx);
                matches.push(best);
            }
        });
        
        return matches;
    },

    getSectionContent(node) {
        if (!node) return '';
        let content = '';
        const originalNode = node.originalNode || node;
        if (originalNode.children) {
            originalNode.children.forEach(child => {
                if (child.type !== 'heading') {
                    content += (child.content || '') + ' ';
                }
            });
        }
        return content.trim();
    },

    compareContent(template, document, matches) {
        const contentDiffs = [];
        
        if (!template || !document) return contentDiffs;
        
        const templateNodes = this.flattenTree(template.tree);
        const docNodes = this.flattenTree(document.tree);
        const matchedTemplateIds = new Set(matches?.map(m => m.template.id) || []);
        const matchedDocIds = new Set(matches?.map(m => m.doc.id) || []);
        
        const templateLeafNodes = templateNodes.filter(n => !n.hasHeadingChildren);
        const docLeafNodes = docNodes.filter(n => !n.hasHeadingChildren);
        
        if (matches && matches.length > 0) {
            matches.forEach(match => {
                const isTemplateLeaf = !match.template.hasHeadingChildren;
                const isDocLeaf = !match.doc.hasHeadingChildren;
                
                if (isTemplateLeaf || isDocLeaf) {
                    const templateContent = this.getSectionContent(match.template);
                    const docContent = this.getSectionContent(match.doc);
                    
                    if (templateContent || docContent) {
                        const diffs = this.findTextDifferences(templateContent, docContent);
                        const similarity = this.calculateTextSimilarity(templateContent, docContent);
                        
                        if (diffs.length > 0 || similarity < 0.95) {
                            contentDiffs.push({
                                sectionTitle: match.template.content,
                                templateContent,
                                docContent,
                                diffs,
                                similarity,
                                templateNode: match.template,
                                docNode: match.doc,
                                matchType: 'matched',
                                level: match.template.level
                            });
                        }
                    }
                }
            });
        }
        
        templateLeafNodes.forEach(tNode => {
            if (!matchedTemplateIds.has(tNode.id)) {
                const templateContent = this.getSectionContent(tNode);
                contentDiffs.push({
                    sectionTitle: tNode.content,
                    templateContent,
                    docContent: '',
                    diffs: [{ type: 'removed', text: templateContent || '章节内容缺失' }],
                    similarity: 0,
                    templateNode: tNode,
                    docNode: null,
                    matchType: 'missing',
                    level: tNode.level
                });
            }
        });
        
        docLeafNodes.forEach(dNode => {
            if (!matchedDocIds.has(dNode.id)) {
                const docContent = this.getSectionContent(dNode);
                contentDiffs.push({
                    sectionTitle: dNode.content,
                    templateContent: '',
                    docContent,
                    diffs: [{ type: 'added', text: docContent || '新增章节内容' }],
                    similarity: 0,
                    templateNode: null,
                    docNode: dNode,
                    matchType: 'extra',
                    level: dNode.level
                });
            }
        });
        
        contentDiffs.sort((a, b) => (a.level || 0) - (b.level || 0));
        
        return contentDiffs;
    },

    findTextDifferences(templateText, docText) {
        const diffs = [];
        const templateSentences = this.splitIntoSentences(templateText);
        const docSentences = this.splitIntoSentences(docText);
        
        const matchedTemplate = new Set();
        const matchedDoc = new Set();
        
        templateSentences.forEach((tSentence, tIdx) => {
            const tTrimmed = tSentence.trim();
            if (!tTrimmed) return;
            
            for (let dIdx = 0; dIdx < docSentences.length; dIdx++) {
                const dTrimmed = docSentences[dIdx].trim();
                if (!dTrimmed || matchedDoc.has(dIdx)) continue;
                
                if (tTrimmed === dTrimmed) {
                    matchedTemplate.add(tIdx);
                    matchedDoc.add(dIdx);
                    break;
                }
            }
        });
        
        const templateUnmatched = [];
        const docUnmatched = [];
        
        templateSentences.forEach((sentence, idx) => {
            if (!matchedTemplate.has(idx) && sentence.trim()) {
                templateUnmatched.push({ idx, text: sentence.trim() });
            }
        });
        
        docSentences.forEach((sentence, idx) => {
            if (!matchedDoc.has(idx) && sentence.trim()) {
                docUnmatched.push({ idx, text: sentence.trim() });
            }
        });
        
        const modifications = [];
        const usedDocIndices = new Set();
        
        templateUnmatched.forEach(tItem => {
            let bestMatch = null;
            let bestSimilarity = 0;
            
            docUnmatched.forEach(dItem => {
                if (usedDocIndices.has(dItem.idx)) return;
                
                const sim = this.similarity(tItem.text, dItem.text);
                if (sim > 0.6 && sim > bestSimilarity) {
                    bestSimilarity = sim;
                    bestMatch = dItem;
                }
            });
            
            if (bestMatch && bestSimilarity > 0.6) {
                modifications.push({
                    type: 'modified',
                    template: tItem.text,
                    doc: bestMatch.text,
                    similarity: bestSimilarity
                });
                usedDocIndices.add(bestMatch.idx);
                matchedTemplate.add(tItem.idx);
                matchedDoc.add(bestMatch.idx);
            }
        });
        
        diffs.push(...modifications);
        
        templateUnmatched.forEach(tItem => {
            if (!matchedTemplate.has(tItem.idx)) {
                diffs.push({
                    type: 'removed',
                    text: tItem.text
                });
            }
        });
        
        docUnmatched.forEach(dItem => {
            if (!matchedDoc.has(dItem.idx)) {
                diffs.push({
                    type: 'added',
                    text: dItem.text
                });
            }
        });
        
        return diffs;
    },

    splitIntoSentences(text) {
        if (!text) return [];
        return text.split(/[。！？\n]+/).filter(s => s.trim().length > 0);
    },

    calculateTextSimilarity(a, b) {
        if (!a && !b) return 1;
        if (!a || !b) return 0;
        
        const normalize = (text) => text.replace(/\s+/g, '').trim();
        const normA = normalize(a);
        const normB = normalize(b);
        
        if (normA === normB) return 1;
        
        const hasChinese = /[\u4e00-\u9fa5]/.test(normA + normB);
        
        if (hasChinese) {
            const charsA = normA.split('');
            const charsB = normB.split('');
            
            const setA = new Set(charsA);
            const setB = new Set(charsB);
            const intersection = new Set([...setA].filter(x => setB.has(x)));
            const union = new Set([...setA, ...setB]);
            
            const jaccardSimilarity = intersection.size / union.size;
            
            const maxLen = Math.max(normA.length, normB.length);
            const minLen = Math.min(normA.length, normB.length);
            const lenRatio = minLen / maxLen;
            
            let commonPrefix = 0;
            for (let i = 0; i < minLen; i++) {
                if (normA[i] === normB[i]) {
                    commonPrefix++;
                } else {
                    break;
                }
            }
            const prefixBonus = commonPrefix / maxLen * 0.2;
            
            return Math.min(1, jaccardSimilarity * 0.8 + lenRatio * 0.2 + prefixBonus);
        } else {
            const wordsA = a.split(/\s+/).filter(w => w.length > 0);
            const wordsB = b.split(/\s+/).filter(w => w.length > 0);
            
            if (wordsA.length === 0 && wordsB.length === 0) return 1;
            if (wordsA.length === 0 || wordsB.length === 0) return 0;
            
            const setA = new Set(wordsA);
            const setB = new Set(wordsB);
            const intersection = new Set([...setA].filter(x => setB.has(x)));
            const union = new Set([...setA, ...setB]);
            
            return intersection.size / union.size;
        }
    },

    compare(template, document, exactMatchMode = false) {
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
        
        const totalTemplateCount = templateNodes.length || 1;
        let totalScore = 0;
        
        templateNodes.forEach(node => {
            const match = matches.find(m => m.template.id === node.id);
            if (match) {
                totalScore += match.similarity;
            }
        });
        
        const score = Math.round((totalScore / totalTemplateCount) * 100);
        
        let contentDiffs = null;
        if (exactMatchMode) {
            contentDiffs = this.compareContent(template, document, matches);
        }
        
        return { diffs, score, templateNodes, docNodes, matches, contentDiffs };
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
    },

    renderContentDiffs(contentDiffs) {
        const container = document.getElementById('contentDiffContainer');
        const list = document.getElementById('contentDiffList');
        const totalScoreEl = document.getElementById('totalSimilarityScore');
        
        if (!contentDiffs || contentDiffs.length === 0) {
            container.classList.add('hidden');
            if (totalScoreEl) totalScoreEl.innerHTML = '';
            return;
        }
        
        container.classList.remove('hidden');
        
        const matchedDiffs = contentDiffs.filter(d => d.matchType === 'matched');
        const missingDiffs = contentDiffs.filter(d => d.matchType === 'missing');
        const extraDiffs = contentDiffs.filter(d => d.matchType === 'extra');
        
        let totalScore = 0;
        const totalSections = contentDiffs.length;
        
        matchedDiffs.forEach(diff => {
            totalScore += diff.similarity;
        });
        
        missingDiffs.forEach(() => {
            totalScore += 0;
        });
        
        extraDiffs.forEach(() => {
            totalScore += 0.5;
        });
        
        const avgScore = totalSections > 0 ? totalScore / totalSections : 0;
        
        const matchedAvgSimilarity = matchedDiffs.length > 0 
            ? matchedDiffs.reduce((sum, d) => sum + d.similarity, 0) / matchedDiffs.length 
            : 0;
        
        if (totalScoreEl) {
            const scoreClass = avgScore > 0.8 ? 'text-green-600' : avgScore > 0.5 ? 'text-yellow-600' : 'text-red-600';
            const bgClass = avgScore > 0.8 ? 'bg-green-100' : avgScore > 0.5 ? 'bg-yellow-100' : 'bg-red-100';
            totalScoreEl.innerHTML = `
                <span class="px-3 py-1.5 rounded-lg ${bgClass} ${scoreClass}">
                    <i class="fas fa-chart-pie mr-1"></i>
                    总体匹配度: ${Math.round(avgScore * 100)}%
                </span>
                <span class="ml-2 text-gray-500 text-xs">
                    匹配章节平均: ${Math.round(matchedAvgSimilarity * 100)}%
                    | 共 ${contentDiffs.length} 个章节
                    ${matchedDiffs.length > 0 ? `<span class="text-green-500 ml-1">(${matchedDiffs.length} 匹配)</span>` : ''}
                    ${missingDiffs.length > 0 ? `<span class="text-red-500 ml-1">(${missingDiffs.length} 缺失)</span>` : ''}
                    ${extraDiffs.length > 0 ? `<span class="text-blue-500 ml-1">(${extraDiffs.length} 多余)</span>` : ''}
                </span>
            `;
        }
        
        list.innerHTML = contentDiffs.map((diff, idx) => {
            const isMissing = diff.matchType === 'missing';
            const isExtra = diff.matchType === 'extra';
            const headerBgClass = isMissing ? 'bg-red-50' : isExtra ? 'bg-blue-50' : 'bg-gray-50';
            const borderClass = isMissing ? 'border-red-200' : isExtra ? 'border-blue-200' : 'border-gray-200';
            const iconClass = isMissing ? 'fa-times-circle text-red-500' : isExtra ? 'fa-plus-circle text-blue-500' : 'fa-chevron-right text-gray-400';
            const badgeClass = isMissing ? 'bg-red-100 text-red-700' : isExtra ? 'bg-blue-100 text-blue-700' : 
                (diff.similarity > 0.8 ? 'bg-green-100 text-green-700' : diff.similarity > 0.5 ? 'bg-yellow-100 text-yellow-700' : 'bg-red-100 text-red-700');
            const badgeText = isMissing ? '缺失' : isExtra ? '多余' : `相似度: ${Math.round(diff.similarity * 100)}%`;
            
            return `
            <div class="bg-white rounded-xl shadow-sm border ${borderClass} overflow-hidden">
                <div class="p-4 ${headerBgClass} border-b ${borderClass} flex items-center justify-between cursor-pointer" onclick="StructureCompare.toggleContentDiff(${idx})">
                    <div class="flex items-center gap-2">
                        <i class="fas ${iconClass} transition-transform" id="contentDiffIcon-${idx}"></i>
                        <span class="font-medium ${isMissing ? 'text-red-900' : isExtra ? 'text-blue-900' : 'text-gray-900'}">
                            ${diff.sectionTitle.substring(0, 50)}${diff.sectionTitle.length > 50 ? '...' : ''}
                        </span>
                    </div>
                    <div class="flex items-center gap-2">
                        <span class="text-xs px-2 py-1 rounded-full ${badgeClass}">
                            ${badgeText}
                        </span>
                        <span class="text-xs text-gray-500">${diff.diffs.length} 处差异</span>
                    </div>
                </div>
                <div class="hidden p-4" id="contentDiffDetail-${idx}">
                    ${isMissing ? `
                        <div class="bg-red-50 rounded p-4">
                            <div class="flex items-center gap-2 mb-2">
                                <i class="fas fa-exclamation-triangle text-red-500"></i>
                                <span class="font-medium text-red-700">模板中存在但文档中缺失的章节</span>
                            </div>
                            <div class="text-sm text-gray-700 bg-white rounded p-3 max-h-40 overflow-y-auto border border-red-200">
                                ${diff.templateContent || '<span class="text-gray-400 italic">无内容</span>'}
                            </div>
                        </div>
                    ` : isExtra ? `
                        <div class="bg-blue-50 rounded p-4">
                            <div class="flex items-center gap-2 mb-2">
                                <i class="fas fa-info-circle text-blue-500"></i>
                                <span class="font-medium text-blue-700">文档中存在但模板中没有的章节</span>
                            </div>
                            <div class="text-sm text-gray-700 bg-white rounded p-3 max-h-40 overflow-y-auto border border-blue-200">
                                ${diff.docContent || '<span class="text-gray-400 italic">无内容</span>'}
                            </div>
                        </div>
                    ` : `
                        <div class="grid grid-cols-2 gap-4 mb-4">
                            <div>
                                <h4 class="text-xs font-semibold text-gray-500 uppercase mb-2">模板内容</h4>
                                <div class="text-sm text-gray-700 bg-gray-50 rounded p-3 max-h-40 overflow-y-auto">${this.highlightDifferences(diff.templateContent, diff.diffs, 'template')}</div>
                            </div>
                            <div>
                                <h4 class="text-xs font-semibold text-gray-500 uppercase mb-2">文档内容</h4>
                                <div class="text-sm text-gray-700 bg-gray-50 rounded p-3 max-h-40 overflow-y-auto">${this.highlightDifferences(diff.docContent, diff.diffs, 'doc')}</div>
                            </div>
                        </div>
                        <div class="border-t border-gray-200 pt-4">
                            <h4 class="text-xs font-semibold text-gray-500 uppercase mb-2">差异详情</h4>
                            <div class="space-y-2">
                                ${diff.diffs.map(d => `
                                    <div class="text-xs p-2 rounded ${d.type === 'added' ? 'bg-green-50 border-l-2 border-green-500' : d.type === 'removed' ? 'bg-red-50 border-l-2 border-red-500' : 'bg-yellow-50 border-l-2 border-yellow-500'}">
                                        <span class="font-medium ${d.type === 'added' ? 'text-green-700' : d.type === 'removed' ? 'text-red-700' : 'text-yellow-700'}">
                                            ${d.type === 'added' ? '新增' : d.type === 'removed' ? '删除' : '修改'}
                                        </span>
                                        ${d.type === 'modified' ? `
                                            <div class="mt-1">
                                                <div class="text-red-600 line-through">${d.template}</div>
                                                <div class="text-green-600">${d.doc}</div>
                                            </div>
                                        ` : `
                                            <span class="ml-2 ${d.type === 'added' ? 'text-green-600' : 'text-red-600'}">${d.text}</span>
                                        `}
                                    </div>
                                `).join('')}
                            </div>
                        </div>
                    `}
                </div>
            </div>
        `}).join('');
    },

    highlightDifferences(content, diffs, type) {
        if (!content) return '<span class="text-gray-400 italic">无内容</span>';
        
        let highlighted = content;
        
        diffs.forEach(diff => {
            if (diff.type === 'added' && type === 'doc') {
                highlighted = highlighted.replace(
                    new RegExp(this.escapeRegex(diff.text), 'g'),
                    `<span class="bg-green-200 text-green-800 px-0.5 rounded">${diff.text}</span>`
                );
            } else if (diff.type === 'removed' && type === 'template') {
                highlighted = highlighted.replace(
                    new RegExp(this.escapeRegex(diff.text), 'g'),
                    `<span class="bg-red-200 text-red-800 px-0.5 rounded line-through">${diff.text}</span>`
                );
            } else if (diff.type === 'modified') {
                const text = type === 'template' ? diff.template : diff.doc;
                highlighted = highlighted.replace(
                    new RegExp(this.escapeRegex(text), 'g'),
                    `<span class="bg-yellow-200 text-yellow-800 px-0.5 rounded">${text}</span>`
                );
            }
        });
        
        return highlighted;
    },

    escapeRegex(string) {
        return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    },

    toggleContentDiff(idx) {
        const detail = document.getElementById(`contentDiffDetail-${idx}`);
        const icon = document.getElementById(`contentDiffIcon-${idx}`);
        
        if (detail.classList.contains('hidden')) {
            detail.classList.remove('hidden');
            icon.style.transform = 'rotate(90deg)';
        } else {
            detail.classList.add('hidden');
            icon.style.transform = 'rotate(0deg)';
        }
    },

    hideContentDiffs() {
        const container = document.getElementById('contentDiffContainer');
        if (container) {
            container.classList.add('hidden');
        }
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
