const AiAudit = {
    buildPrompt(rule, documentText, excelData) {
        let prompt = rule.prompt;
        
        if (excelData) {
            prompt = prompt.replace(/\{\{excel\.([^}]+)\}\}/g, (match, path) => {
                const parts = path.split('.');
                if (parts.length >= 2) {
                    const [sheetName, colName] = parts;
                    const sheet = excelData.sheets.find(s => s.name === sheetName);
                    if (sheet) {
                        const values = sheet.rows.map(row => row[sheet.headers.indexOf(colName)]).filter(Boolean);
                        return values.join('、');
                    }
                }
                return match;
            });
        }
        
        const context = "文档内容：\n" + 
            documentText.substring(0, 10000) +  
            "\n\n审核规则：" + prompt + 
            "\n\n请严格按以下JSON格式返回，不要包含其他内容，不要添加任何解释说明：\n" +
            "{\n" +
            '  "pass": boolean,\n' +
            '  "confidence": 0-100,\n' +
            '  "issues": [{"location": "位置描述", "problem": "问题描述", "suggestion": "修改建议"}],\n' +
            '  "summary": "总体评价"\n' +
            "}";
        
        return context;
    },

    async callLLM(prompt, rule, settings) {
        const { endpoint, apiKey, model, auditRole } = settings;
        
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
            
            let result = this.parseResult(content);
            
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
    },

    parseResult(content) {
        let result;
        try {
            try {
                result = JSON.parse(content);
            } catch {
                let jsonData = null;
                
                let match = content.match(/```json\s*([\s\S]*?)\s*```/);
                if (match) jsonData = match[1];
                
                if (!jsonData) {
                    match = content.match(/```\s*([\s\S]*?)\s*```/);
                    if (match) jsonData = match[1];
                }
                
                if (!jsonData) {
                    match = content.match(/\{[\s\S]*\}/);
                    if (match) jsonData = match[0];
                }
                
                if (jsonData) {
                    const cleanJsonData = jsonData
                        .trim()
                        .replace(/^[\u200B-\u200D\uFEFF]/, '')
                        .replace(/,\s*}/g, '}')
                        .replace(/,\s*]/g, ']');
                    result = JSON.parse(cleanJsonData);
                } else {
                    throw new Error('未找到JSON数据');
                }
            }
            
            result = {
                pass: result.pass ?? false,
                confidence: result.confidence ?? 50,
                issues: result.issues ?? [],
                summary: result.summary ?? '审核完成'
            };
            
        } catch {
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
        
        return result;
    },

    renderResult(result, container) {
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
    },

    async testConnection(settings) {
        const { endpoint, apiKey, model } = settings;
        
        const response = await fetch(endpoint, {
            method: 'POST',
            headers: { 
                'Authorization': 'Bearer ' + apiKey,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                model: model || 'deepseek-chat',
                messages: [{ role: 'user', content: '你好' }],
                temperature: 0.7,
                max_tokens: 10
            })
        });
        
        return response;
    }
};
