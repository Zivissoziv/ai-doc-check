const ConfigLoader = {
    async loadRuleGroups() {
        try {
            const response = await fetch('./config/rules/index.json', { cache: 'no-store' });
            if (response.ok) {
                const config = await response.json();
                return {
                    groups: config.groups || [],
                    defaultGroup: config.defaultGroup || 'default'
                };
            }
        } catch (err) {
            console.log('规则组配置加载失败:', err.message);
        }
        return { groups: [], defaultGroup: 'default' };
    },

    async loadApiConfig() {
        try {
            const response = await fetch('./config/api.json', { cache: 'no-store' });
            if (response.ok) {
                const config = await response.json();
                if (!config.apiKey) {
                    const savedSettings = localStorage.getItem('smartdoc_settings');
                    if (savedSettings) {
                        const localSettings = JSON.parse(savedSettings);
                        if (localSettings.apiKey) {
                            config.apiKey = localSettings.apiKey;
                        }
                    }
                }
                return config;
            }
        } catch (err) {
            console.log('API配置加载失败:', err.message);
        }
        return null;
    },

    async loadRulesFromFile(fileName) {
        try {
            const response = await fetch(`./config/rules/${fileName}`, { cache: 'no-store' });
            if (response.ok) {
                return await response.json();
            }
        } catch (err) {
            console.log('规则文件加载失败:', err.message);
        }
        return null;
    },

    async loadRulesLegacy() {
        try {
            const response = await fetch('./config/rules.json', { cache: 'no-store' });
            if (response.ok) {
                return await response.json();
            }
        } catch (err) {
            console.log('规则加载失败:', err.message);
        }
        return [];
    },

    async loadTemplateList() {
        try {
            const response = await fetch('./config/templates.json', { cache: 'no-store' });
            if (response.ok) {
                return await response.json();
            }
        } catch (err) {
            console.log('模板列表加载失败:', err.message);
        }
        return { templates: [], defaultTemplate: '' };
    },

    async loadPresetTemplate(fileName) {
        try {
            const response = await fetch(`./config/templates/${fileName}`, { cache: 'no-store' });
            if (response.ok) {
                const blob = await response.blob();
                return new File([blob], fileName, { type: blob.type });
            }
        } catch (err) {
            console.log('预设模板加载失败:', err.message);
        }
        return null;
    }
};
