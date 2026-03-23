const ConfigAPI = {
    async getRuleGroups() {
        const response = await fetch('/api/config/rules');
        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || '获取规则组列表失败');
        }
        return response.json();
    },

    async getRuleGroup(groupId) {
        const response = await fetch(`/api/config/rules/${encodeURIComponent(groupId)}`);
        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || '获取规则组失败');
        }
        return response.json();
    },

    async saveRuleGroup(groupId, rules, name = null) {
        const body = { rules };
        if (name) {
            body.name = name;
        }

        const response = await fetch(`/api/config/rules/${encodeURIComponent(groupId)}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });

        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || '保存规则组失败');
        }
        return response.json();
    },

    async createRuleGroup(groupId, name, rules = []) {
        const response = await fetch('/api/config/rules', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: groupId, name, rules })
        });

        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || '创建规则组失败');
        }
        return response.json();
    },

    async deleteRuleGroup(groupId) {
        const response = await fetch(`/api/config/rules/${encodeURIComponent(groupId)}`, {
            method: 'DELETE'
        });

        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || '删除规则组失败');
        }
        return response.json();
    },

    async getApiConfig() {
        const response = await fetch('/api/config/api');
        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || '获取API配置失败');
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
            const error = await response.json();
            throw new Error(error.error || '更新API配置失败');
        }
        return response.json();
    }
};
