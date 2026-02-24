const DocumentParser = {
    async parse(file) {
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
            const text = new TextDecoder().decode(buffer);
            result.text = text;
            result.tree = this.textToTree(text);
            result.html = `<pre class="whitespace-pre-wrap">${text}</pre>`;
        }
        
        return result;
    },

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
    },

    textToTree(text) {
        const lines = text.split('\n');
        const tree = [];
        const stack = [];
        
        lines.forEach((line, idx) => {
            const trimmed = line.trim();
            if (!trimmed) return;
            
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
    },

    parseExcel: async function(file) {
        const data = await file.arrayBuffer();
        const workbook = XLSX.read(data, { type: 'array' });
        
        return {
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
    }
};
