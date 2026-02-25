const RulesManager = {
    load() {
        return JSON.parse(localStorage.getItem('smartdoc_rules') || '[]');
    },

    save(rules) {
        localStorage.setItem('smartdoc_rules', JSON.stringify(rules));
    },

    getCurrentGroup() {
        return localStorage.getItem('smartdoc_ruleGroup') || 'default';
    },

    setCurrentGroup(groupId) {
        localStorage.setItem('smartdoc_ruleGroup', groupId);
    },

    renderList(rules, containerId) {
        const container = document.getElementById(containerId);
        if (!container) return;
        
        if (rules.length === 0) {
            container.innerHTML = '<div class="text-center text-gray-400 mt-8 text-sm">暂无规则，点击上方添加</div>';
            return;
        }
        
        container.innerHTML = rules.map((rule, idx) => {
            const isEnabled = rule.enabled !== false;
            return `
                <div class="p-3 border rounded-xl transition-all duration-200 group ${isEnabled ? 'bg-white border-gray-200 shadow-sm hover:shadow-md' : 'bg-gray-50 border-gray-100 opacity-70'}">
                    <div class="flex items-start justify-between mb-2">
                        <div onclick="app.editRule(${idx})" class="flex items-center gap-2 overflow-hidden cursor-pointer flex-1" title="点击编辑规则">
                            <span class="flex-shrink-0 w-2 h-2 rounded-full ${rule.severity === 'error' ? 'bg-red-500' : rule.severity === 'warning' ? 'bg-yellow-500' : 'bg-blue-500'} shadow-sm"></span>
                            <span class="font-medium text-sm truncate ${isEnabled ? 'text-gray-900 group-hover:text-blue-600' : 'text-gray-400'} transition-colors">${rule.name}</span>
                            <i class="fas fa-edit text-xs text-gray-300 opacity-0 group-hover:opacity-100 transition-opacity"></i>
                        </div>
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
    },

    renderGroupSelector(groups, currentGroup, selectId) {
        const select = document.getElementById(selectId);
        if (!select) return;
        
        select.innerHTML = groups.map(g => 
            `<option value="${g.id}" ${g.id === currentGroup ? 'selected' : ''}>${g.name}</option>`
        ).join('');
    },

    exportToFile(rules) {
        if (rules.length === 0) {
            alert('暂无规则可导出');
            return;
        }
        
        const blob = new Blob([JSON.stringify(rules, null, 4)], { type: 'application/json' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = 'rules.json';
        a.click();
        alert('规则已导出！请将下载的 rules.json 文件复制到 config/rules 目录下。');
    }
};
