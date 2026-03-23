const AiAudit = {
    REPETITION_SEPARATOR: '\n\n--- 重复提示（请仔细阅读以上内容）---\n\n',
    
    buildPrompt(rule, documentText, excelData, repeatPrompt = true) {
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
        
        const basePrompt = `文档内容：
${documentText.substring(0, 10000)}

审核规则：${prompt}

=== 输出格式要求 ===
你必须返回一个合法的JSON对象，格式如下：
{
  "pass": true,
  "confidence": 95,
  "issues": [],
  "summary": "文档格式规范，符合要求"
}

或发现问题时：
{
  "pass": false,
  "confidence": 85,
  "issues": [
    {
      "location": "第3章第2节",
      "textSnippet": "参数说明",
      "problem": "缺少必要的参数说明",
      "suggestion": "建议补充参数列表和类型定义"
    }
  ],
  "summary": "发现1处问题，建议修改"
}

=== 字段说明 ===
- pass: 是否通过，true或false
- confidence: 置信度，0-100的整数
- issues: 问题列表，通过时为[]，不通过时包含具体对象
- summary: 总体评价，简短描述
- location: 问题所在的位置描述（如"第3章第2节"、"摘要部分"）
- textSnippet: 问题所在位置的文档原文片段（用于定位，10-30个字符）
- problem: 问题描述
- suggestion: 修改建议

=== 重要约束 ===
1. 必须返回合法JSON，不要添加markdown代码块标记
2. issues数组为空时写成 [] 而不是 null
3. 字符串使用双引号，不要使用单引号
4. 最后一个元素后面不要加逗号
5. 不要包含任何解释说明文字，只返回JSON`;

        if (repeatPrompt) {
            return basePrompt + this.REPETITION_SEPARATOR + basePrompt;
        } else {
            return basePrompt;
        }
    },

    buildBatchPrompt(rules, documentText, excelData, repeatPrompt = true) {
        const processPrompt = (prompt) => {
            if (!excelData) return prompt;
            return prompt.replace(/\{\{excel\.([^}]+)\}\}/g, (match, path) => {
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
        };

        const rulesList = rules.map((rule, index) => ({
            id: index,
            name: rule.name,
            severity: rule.severity,
            prompt: processPrompt(rule.prompt)
        }));

        const basePrompt = `你需要对以下文档进行批量审核，按照给定的规则逐一检查。

文档内容：
${documentText.substring(0, 10000)}

审核规则列表：
${rulesList.map(r => `
[规则${r.id}] ${r.name} (级别: ${r.severity})
${r.prompt}`).join('\n')}

=== 输出格式要求 ===
你必须返回一个合法的JSON对象，格式如下：
{
  "results": [
    {
      "ruleId": 0,
      "pass": true,
      "confidence": 95,
      "issues": [],
      "summary": "文档格式规范，符合要求"
    },
    {
      "ruleId": 1,
      "pass": false,
      "confidence": 85,
      "issues": [
        {
          "location": "第3章第2节",
          "textSnippet": "参数说明",
          "problem": "缺少必要的参数说明",
          "suggestion": "建议补充参数列表和类型定义"
        }
      ],
      "summary": "发现1处问题，建议修改"
    }
  ]
}

=== 字段说明 ===
- ruleId: 规则序号，对应规则列表中的序号(0-${rules.length - 1})
- pass: 是否通过，true或false
- confidence: 置信度，0-100的整数
- issues: 问题列表，通过时为[]，不通过时包含具体对象
- summary: 总体评价，简短描述
- location: 问题所在的位置描述（如"第3章第2节"、"摘要部分"）
- textSnippet: 问题所在位置的文档原文片段（用于定位，10-30个字符）
- problem: 问题描述
- suggestion: 修改建议

=== 重要约束 ===
1. 必须返回合法JSON，不要添加markdown代码块标记
2. results数组长度必须等于${rules.length}
3. 每个规则都要有对应的result对象
4. issues数组为空时写成 [] 而不是 null
5. 字符串使用双引号，不要使用单引号
6. 最后一个元素后面不要加逗号
7. 不要包含任何解释说明文字，只返回JSON`;

        if (repeatPrompt) {
            return basePrompt + this.REPETITION_SEPARATOR + basePrompt;
        } else {
            return basePrompt;
        }
    },

    async callLLM(prompt, rule, settings) {
        const { endpoint, model, auditRole } = settings;

        try {
            const response = await fetch('/api/proxy', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    endpoint: endpoint || 'https://api.openai.com/v1/chat/completions',
                    body: {
                        model: model || 'deepseek-chat',
                        messages: [
                            { role: 'system', content: `${auditRole || '专业文档审核专家'}，你擅长发现文档中的结构、逻辑和合规问题。请严格按照要求的JSON格式返回结果，不要添加任何额外说明。` },
                            { role: 'user', content: prompt }
                        ],
                        temperature: 0.1
                    }
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

    async callBatchLLM(prompt, rules, settings) {
        const { endpoint, model, auditRole } = settings;

        try {
            const response = await fetch('/api/proxy', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    endpoint: endpoint || 'https://api.openai.com/v1/chat/completions',
                    body: {
                        model: model || 'deepseek-chat',
                        messages: [
                            { role: 'system', content: `${auditRole || '专业文档审核专家'}，你擅长发现文档中的结构、逻辑和合规问题。请严格按照要求的JSON格式返回结果，不要添加任何额外说明。` },
                            { role: 'user', content: prompt }
                        ],
                        temperature: 0.1
                    }
                })
            });

            if (!response.ok) throw new Error('API错误: ' + response.status);

            const data = await response.json();
            const content = data.choices[0].message.content;

            // 解析批量结果
            const batchResult = this.parseBatchResult(content, rules);
            return batchResult;

        } catch (err) {
            // 批量调用失败时，返回所有规则的错误结果
            return rules.map(rule => ({
                ruleName: rule.name,
                severity: rule.severity,
                pass: false,
                confidence: 0,
                issues: [{ location: 'API调用', problem: err.message, suggestion: '请检查网络或API配置' }],
                summary: '批量调用失败'
            }));
        }
    },

    parseBatchResult(content, rules) {
        let result;
        try {
            // 尝试直接解析JSON
            try {
                result = JSON.parse(content);
            } catch {
                // 尝试从代码块中提取
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
            
            // 提取results数组
            const results = result.results || [];
            
            // 将批量结果映射到每条规则
            return rules.map((rule, index) => {
                const ruleResult = results.find(r => r.ruleId === index) || results[index] || {};
                
                return {
                    ruleName: rule.name,
                    severity: rule.severity,
                    pass: ruleResult.pass ?? false,
                    confidence: ruleResult.confidence ?? 50,
                    issues: ruleResult.issues ?? [],
                    summary: ruleResult.summary ?? '审核完成'
                };
            });
            
        } catch (err) {
            // 解析失败，返回降级结果
            return rules.map(rule => ({
                ruleName: rule.name,
                severity: rule.severity,
                pass: false,
                confidence: 30,
                issues: [{ 
                    location: '批量解析', 
                    problem: '返回格式解析失败: ' + err.message, 
                    suggestion: '请检查模型输出格式' 
                }],
                summary: '解析失败，请重试'
            }));
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

    jumpToLocation(textSnippet, location) {
        UiHelpers.switchTab('preview');
        
        setTimeout(() => {
            UiHelpers.highlightAndScroll(textSnippet, location);
        }, 100);
    },

    async testConnection(settings) {
        const { endpoint, apiKey, model } = settings;
        
        const response = await fetch('/api/proxy', {
            method: 'POST',
            headers: { 
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                endpoint: endpoint,
                apiKey: apiKey,
                body: {
                    model: model || 'deepseek-chat',
                    messages: [{ role: 'user', content: '你好' }],
                    temperature: 0.7,
                    max_tokens: 10
                }
            })
        });
        
        return response;
    }
};
