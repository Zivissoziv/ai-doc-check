# 预配置说明

本目录用于存放预配置文件，应用启动时会自动加载这些配置。

## 目录结构

```
config/
├── api.json         # API配置文件
├── rules.json       # 审核规则配置
├── templates.json   # 模板列表配置
├── templates/       # 模板文件目录（存放.docx/.pdf/.txt文件）
└── README.txt       # 本说明文件
```

## 配置说明

### api.json
API连接配置，包含以下字段：
- provider: 固定为 "custom"
- endpoint: API端点地址，如 "https://api.deepseek.com/chat/completions"
- apiKey: 你的API密钥（建议不要在此文件中存放，通过界面配置更安全）
- model: 模型名称，如 "deepseek-chat"
- auditRole: 审核角色描述，如 "专业文档审核专家"

### rules.json
预设的审核规则数组，每条规则包含：
- id: 规则唯一标识
- name: 规则名称
- prompt: 审核提示词
- severity: 严重级别 (error/warning/info)

### templates.json
模板列表配置文件：
- defaultTemplate: 默认加载的模板文件名（如 "standard.docx"），留空则不自动加载
- templates: 模板数组，每个模板包含：
  - name: 显示名称
  - file: 文件名（需放在templates/目录下）
  - description: 模板描述（可选）

### templates/
将模板文档（.docx, .pdf, .txt, .md）放入此目录。
文件名需与templates.json中的file字段对应。

## 注意事项

1. 配置文件为JSON格式，请确保语法正确
2. 每次刷新页面都会从config目录重新加载配置
3. API密钥特殊处理：如果config/api.json中apiKey为空，会保留用户在界面输入的密钥
4. 修改配置文件后刷新页面即可生效，无需清除浏览器缓存
