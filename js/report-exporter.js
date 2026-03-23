const ReportExporter = {
    exportJson(document, template, excelData, auditResults) {
        if (!document) {
            alert('无审核数据可导出');
            return;
        }
        
        const report = {
            timestamp: new Date().toISOString(),
            document: document.name,
            template: template?.name || '无',
            structureScore: document.getElementById('structureScore')?.textContent || 'N/A',
            auditResults: auditResults,
            excelData: excelData?.fileName || '无'
        };
        
        const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = `审核报告_${Date.now()}.json`;
        a.click();
    },
    
    exportHtml(document, template, excelData, auditResults) {
        if (!document) {
            alert('无审核数据可导出');
            return;
        }
        
        const timestamp = new Date().toLocaleString('zh-CN');
        const structureScoreEl = document.getElementById('structureScore');
        const structureScore = structureScoreEl ? structureScoreEl.textContent : 'N/A';
        const passedCount = auditResults.filter(r => r.pass).length;
        const failedCount = auditResults.filter(r => !r.pass).length;
        const totalCount = auditResults.length;
        
        let auditHtml = '';
        
        if (auditResults.length > 0) {
            auditHtml = auditResults.map((result, idx) => {
                const statusColor = result.pass ? '#10B981' : '#EF4444';
                const statusBg = result.pass ? '#D1FAE5' : '#FEE2E2';
                const severityColor = result.severity === 'error' ? '#EF4444' : result.severity === 'warning' ? '#F59E0B' : '#3B82F6';
                const severityBg = result.severity === 'error' ? '#FEE2E5' : result.severity === 'warning' ? '#FEF3C7' : '#DBEAFE';
                
                let issuesHtml = '';
                if (result.issues && result.issues.length > 0) {
                    issuesHtml = result.issues.map(issue => `
                        <div style="background: #F9FAFB; border-left: 3px solid ${severityColor}; padding: 12px; margin-bottom: 8px; border-radius: 0 6px 6px 0;">
                            <div style="font-size: 12px; color: #6B7280; margin-bottom: 4px;">
                                <span style="font-weight: 500;">📍 ${issue.location || '位置未知'}</span>
                            </div>
                            <div style="font-size: 14px; color: #1F2937; margin-bottom: 6px;">${issue.problem || ''}</div>
                            ${issue.suggestion ? `<div style="font-size: 12px; color: #2563EB; background: #EFF6FF; padding: 8px; border-radius: 4px;">
                                💡 ${issue.suggestion}
                            </div>` : ''}
                        </div>
                    `).join('');
                } else {
                    issuesHtml = `<div style="color: #10B981; font-size: 14px; padding: 12px; background: #D1FAE5; border-radius: 6px; margin-bottom: 12px;">
                        ✓ 未发现问题
                    </div>`;
                }
                
                return `
                    <div style="background: white; border: 1px solid #E5E7EB; border-radius: 12px; padding: 20px; margin-bottom: 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.05);">
                        <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px;">
                            <div style="display: flex; align-items: center; gap: 12px;">
                                <div style="width: 40px; height: 40px; border-radius: 50%; background: ${statusBg}; color: ${statusColor}; display: flex; align-items: center; justify-content: center; font-size: 16px;">
                                    ${result.pass ? '✓' : '✕'}
                                </div>
                                <div>
                                    <div style="font-weight: 600; color: #111827; font-size: 16px;">${result.ruleName}</div>
                                    <div style="display: flex; align-items: center; gap: 8px; font-size: 12px; color: #6B7280;">
                                        <span style="background: ${severityBg}; color: ${severityColor}; padding: 2px 8px; border-radius: 9999px;">${result.severity}</span>
                                        <span>置信度: ${result.confidence || 0}%</span>
                                    </div>
                                </div>
                            </div>
                        </div>
                        <div>${issuesHtml}</div>
                        <div style="font-size: 12px; color: #6B7280; padding-top: 12px; border-top: 1px solid #F3F4F6; margin-top: 12px;">
                            📝 ${result.summary || ''}
                        </div>
                    </div>
                `;
            }).join('');
        } else {
            auditHtml = `<div style="text-align: center; padding: 40px; color: #6B7280;">暂无审核结果</div>`;
        }
        
        const htmlContent = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>文档审核报告 - ${document.name}</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background: #F3F4F6; color: #1F2937; line-height: 1.6; padding: 24px; }
        .container { max-width: 900px; margin: 0 auto; }
        .header { background: linear-gradient(135deg, #1E40AF 0%, #3B82F6 100%); color: white; padding: 32px; border-radius: 16px; margin-bottom: 24px; }
        .header h1 { font-size: 24px; font-weight: 600; margin-bottom: 8px; }
        .header .meta { font-size: 14px; opacity: 0.9; }
        .summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 24px; }
        .summary-card { background: white; padding: 20px; border-radius: 12px; text-align: center; box-shadow: 0 1px 3px rgba(0,0,0,0.05); }
        .summary-card .value { font-size: 28px; font-weight: 700; }
        .summary-card .label { font-size: 12px; color: #6B7280; margin-top: 4px; }
        .card { background: white; border-radius: 16px; padding: 24px; margin-bottom: 24px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); }
        .card h2 { font-size: 18px; font-weight: 600; margin-bottom: 16px; color: #111827; }
        .info-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
        .info-item { font-size: 14px; }
        .info-item .label { color: #6B7280; font-size: 12px; }
        .info-item .value { color: #111827; font-weight: 500; }
        .footer { text-align: center; font-size: 12px; color: #9CA3AF; padding: 24px; }
        @media print { body { background: white; } }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>📄 文档审核报告</h1>
            <div class="meta">生成时间: ${timestamp}</div>
        </div>
        
        <div class="summary">
            <div class="summary-card">
                <div class="value" style="color: #3B82F6;">${totalCount}</div>
                <div class="label">审核规则数</div>
            </div>
            <div class="summary-card">
                <div class="value" style="color: #10B981;">${passedCount}</div>
                <div class="label">通过</div>
            </div>
            <div class="summary-card">
                <div class="value" style="color: #EF4444;">${failedCount}</div>
                <div class="label">未通过</div>
            </div>
            <div class="summary-card">
                <div class="value" style="color: #8B5CF6;">${structureScore}</div>
                <div class="label">结构匹配度</div>
            </div>
        </div>
        
        <div class="card">
            <h2>📋 基本信息</h2>
            <div class="info-grid">
                <div class="info-item">
                    <div class="label">文档名称</div>
                    <div class="value">${document.name || '未知'}</div>
                </div>
                <div class="info-item">
                    <div class="label">参考模板</div>
                    <div class="value">${template?.name || '无'}</div>
                </div>
                <div class="info-item">
                    <div class="label">文档字数</div>
                    <div class="value">${document.text?.length || 0} 字符</div>
                </div>
                <div class="info-item">
                    <div class="label">Excel数据</div>
                    <div class="value">${excelData?.fileName || '未使用'}</div>
                </div>
            </div>
        </div>
        
        <div class="card">
            <h2>🔍 审核结果详情</h2>
            ${auditHtml}
        </div>
        
        <div class="footer">
            由 AI Doc Check 自动生成
        </div>
    </div>
</body>
</html>`;
        
        const blob = new Blob([htmlContent], { type: 'text/html;charset=utf-8' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        const fileName = (document.name || 'document').replace(/\.[^/.]+$/, '');
        a.download = `审核报告_${fileName}_${Date.now()}.html`;
        a.click();
    }
};
