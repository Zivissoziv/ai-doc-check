#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SmartDoc AI - 带 API 代理的 HTTP 服务器（纯标准库实现）"""

import sys
import io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

import base64
import json
import logging
import os
import re
import ssl
import urllib.request
import urllib.error
import zipfile
import xml.etree.ElementTree as ET
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from typing import Dict, List, Optional, Any

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

CONFIG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'config')

MAX_FILE_SIZE = 50 * 1024 * 1024
DOWNLOAD_TIMEOUT = 60
REPETITION_SEPARATOR = '\n\n--- 重复提示（请仔细阅读以上内容）---\n\n'

# LLM调用相关常量
LLM_TIMEOUT_SECONDS = 120
LLM_TEMPERATURE = 0.1
RETRY_TEMPERATURE = 0.05
PARSE_FAILURE_CONFIDENCE = 30


class JSONRepair:
    """轻量级 JSON 修复器，处理 LLM 返回的畸形 JSON"""

    @staticmethod
    def repair(json_str):
        """修复 JSON 字符串，返回修复后的字符串或 None"""
        if not json_str or not isinstance(json_str, str):
            return None

        # 先尝试直接解析
        try:
            json.loads(json_str)
            return json_str
        except json.JSONDecodeError:
            pass

        # 修复常见错误
        repaired = json_str

        # 1. 提取 ```json ... ``` 或 ``` ... ``` 中的内容
        code_block_match = re.search(r'```(?:json)?\s*([\s\S]*?)\s*```', repaired)
        if code_block_match:
            repaired = code_block_match.group(1)

        # 2. 去除 BOM 和零宽字符
        repaired = repaired.lstrip('\ufeff\u200b\u200c\u200d')

        # 3. 修复 trailing commas (逗号在 } 或 ] 前)
        repaired = re.sub(r',(\s*[}\]])', r'\1', repaired)

        # 4. 修复单引号为双引号（简单处理）
        # 先标记字符串内的单引号，然后替换
        repaired = JSONRepair._fix_quotes(repaired)

        # 5. 修复缺失的引号键
        repaired = re.sub(r'([{,]\s*)([a-zA-Z_][a-zA-Z0-9_]*)\s*:', r'\1"\2":', repaired)

        # 6. 修复 undefined、NaN、Infinity
        repaired = repaired.replace('undefined', 'null')
        repaired = re.sub(r'\bNaN\b', 'null', repaired)
        repaired = re.sub(r'\bInfinity\b', 'null', repaired)
        repaired = re.sub(r'\b-Infinity\b', 'null', repaired)

        # 7. 修复缺失的逗号（简单场景）
        repaired = re.sub(r'("[^"]*"|\d+|true|false|null)\s*("[^"]*")', r'\1,\2', repaired)
        repaired = re.sub(r'(}\s*{)', '},{', repaired)
        repaired = re.sub(r'(]\s*\[)', '],[', repaired)
        repaired = re.sub(r'(}\s*\[)', '},[', repaired)
        repaired = re.sub(r'(]\s*{)', '],{', repaired)

        # 8. 尝试解析
        try:
            json.loads(repaired)
            return repaired
        except json.JSONDecodeError:
            pass

        # 9. 更激进的修复：尝试补全不完整的 JSON
        repaired = JSONRepair._complete_json(repaired)

        try:
            json.loads(repaired)
            return repaired
        except json.JSONDecodeError:
            # 修复失败，返回原始内容让前端处理
            return json_str

    @staticmethod
    def _fix_quotes(s):
        """修复引号问题"""
        result = []
        i = 0
        in_string = False
        string_char = None

        while i < len(s):
            char = s[i]

            if not in_string:
                if char in '"\'':
                    in_string = True
                    string_char = char
                    result.append('"')
                else:
                    result.append(char)
            else:
                if char == string_char:
                    # 检查是否是转义的
                    backslash_count = 0
                    j = i - 1
                    while j >= 0 and s[j] == '\\':
                        backslash_count += 1
                        j -= 1

                    if backslash_count % 2 == 0:
                        # 未转义，结束字符串
                        in_string = False
                        string_char = None
                        result.append('"')
                    else:
                        # 转义的，保留
                        result.append(char)
                elif char == '"' and string_char == '\'':
                    # 单引号字符串内的双引号，保留
                    result.append(char)
                elif char == '\'' and string_char == '"':
                    # 双引号字符串内的单引号，保留
                    result.append(char)
                else:
                    result.append(char)

            i += 1

        # 如果字符串未闭合，补全
        if in_string:
            result.append('"')

        return ''.join(result)

    @staticmethod
    def _complete_json(s):
        """尝试补全不完整的 JSON"""
        s = s.strip()

        # 统计括号
        open_braces = s.count('{') - s.count('}')
        open_brackets = s.count('[') - s.count(']')

        # 补全缺失的闭合括号
        if open_braces > 0:
            s += '}' * open_braces
        if open_brackets > 0:
            s += ']' * open_brackets

        return s

    @staticmethod
    def repair_response(resp_body):
        """修复 API 响应体中的 JSON 内容"""
        try:
            # 解析响应
            data = json.loads(resp_body.decode('utf-8'))

            # 检查是否有 choices 字段（OpenAI 格式）
            if 'choices' in data and isinstance(data['choices'], list):
                for choice in data['choices']:
                    if 'message' in choice and 'content' in choice['message']:
                        content = choice['message']['content']
                        if isinstance(content, str):
                            # 尝试修复 content 中的 JSON
                            repaired = JSONRepair.repair(content)
                            if repaired and repaired != content:
                                choice['message']['content'] = repaired

            return json.dumps(data, ensure_ascii=False).encode('utf-8')

        except (json.JSONDecodeError, UnicodeDecodeError):
            # 解析失败，返回原始内容
            return resp_body


class DocumentParser:
    """文档解析器，支持DOCX和TXT格式"""

    @staticmethod
    def parse_docx(file_bytes):
        """解析DOCX文件，提取文本内容"""
        try:
            text_parts = []
            with zipfile.ZipFile(file_bytes, 'r') as zf:
                if 'word/document.xml' not in zf.namelist():
                    return None
                
                xml_content = zf.read('word/document.xml')
                root = ET.fromstring(xml_content)
                
                ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
                
                for para in root.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}p'):
                    para_text = []
                    for text_elem in para.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t'):
                        if text_elem.text:
                            para_text.append(text_elem.text)
                    if para_text:
                        text_parts.append(''.join(para_text))
                
                return '\n'.join(text_parts)
        except Exception as e:
            print(f"解析DOCX失败: {e}")
            return None

    @staticmethod
    def parse_txt(file_bytes):
        """解析TXT文件"""
        try:
            return file_bytes.decode('utf-8')
        except UnicodeDecodeError:
            try:
                return file_bytes.decode('gbk')
            except:
                return None

    @staticmethod
    def parse_excel(file_bytes):
        """解析XLSX文件，返回数据字典"""
        try:
            result = {'sheets': []}
            with zipfile.ZipFile(file_bytes, 'r') as zf:
                shared_strings = []
                if 'xl/sharedStrings.xml' in zf.namelist():
                    ss_xml = zf.read('xl/sharedStrings.xml')
                    ss_root = ET.fromstring(ss_xml)
                    for si in ss_root.iter('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}t'):
                        shared_strings.append(si.text if si.text else '')
                
                sheet_files = [f for f in zf.namelist() if f.startswith('xl/worksheets/sheet') and f.endswith('.xml')]
                
                for sheet_file in sorted(sheet_files):
                    sheet_xml = zf.read(sheet_file)
                    sheet_root = ET.fromstring(sheet_xml)
                    
                    rows_data = []
                    for row in sheet_root.iter('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}row'):
                        row_data = []
                        for cell in row.iter('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}c'):
                            cell_type = cell.get('t')
                            value_elem = cell.find('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}v')
                            
                            if value_elem is not None and value_elem.text:
                                if cell_type == 's':
                                    idx = int(value_elem.text)
                                    if idx < len(shared_strings):
                                        row_data.append(shared_strings[idx])
                                    else:
                                        row_data.append('')
                                else:
                                    row_data.append(value_elem.text)
                            else:
                                row_data.append('')
                        
                        if any(cell for cell in row_data):
                            rows_data.append(row_data)
                    
                    if rows_data:
                        sheet_name = sheet_file.split('/')[-1].replace('.xml', '')
                        result['sheets'].append({
                            'name': sheet_name,
                            'headers': rows_data[0] if rows_data else [],
                            'rows': rows_data[1:] if len(rows_data) > 1 else []
                        })
            
            return result
        except Exception as e:
            print(f"解析Excel失败: {e}")
            return None

    @staticmethod
    def detect_type(file_bytes, content_type=None):
        """检测文件类型"""
        if file_bytes[:4] == b'PK\x03\x04':
            if content_type and 'spreadsheet' in content_type:
                return 'xlsx'
            if content_type and 'wordprocessing' in content_type:
                return 'docx'
            return 'docx'
        elif file_bytes[:4] == b'%PDF':
            return 'pdf'
        else:
            try:
                file_bytes.decode('utf-8')
                return 'txt'
            except:
                return None


class PromptBuilder:
    """提示词构建器"""

    @staticmethod
    def build_batch_prompt(rules, document_text, user_data=None, repeat_prompt=True):
        """构建批量审核提示词"""

        rules_list = []
        for idx, rule in enumerate(rules):
            prompt = rule.get('prompt', '')
            # 统一使用 {{data.xxx}} 格式替换变量
            if user_data:
                prompt = PromptBuilder._replace_data_vars(prompt, user_data)
            rules_list.append({
                'id': idx,
                'name': rule.get('name', f'规则{idx}'),
                'severity': rule.get('severity', 'warning'),
                'prompt': prompt
            })

        base_prompt = f"""你需要对以下文档进行批量审核，按照给定的规则逐一检查。

文档内容：
{document_text[:10000]}

审核规则列表：
{chr(10).join([f"[规则{r['id']}] {r['name']} (级别: {r['severity']}){chr(10)}{r['prompt']}" for r in rules_list])}

=== 输出格式要求 ===
你必须返回一个合法的JSON对象，格式如下：
{{
  "results": [
    {{
      "ruleId": 0,
      "pass": true,
      "confidence": 95,
      "issues": [],
      "summary": "文档格式规范，符合要求"
    }},
    {{
      "ruleId": 1,
      "pass": false,
      "confidence": 85,
      "issues": [
        {{
          "location": "第3章第2节",
          "problem": "缺少必要的参数说明",
          "suggestion": "建议补充参数列表和类型定义"
        }}
      ],
      "summary": "发现1处问题，建议修改"
    }}
  ]
}}

=== 字段说明 ===
- ruleId: 规则序号，对应规则列表中的序号(0-{len(rules) - 1})
- pass: 是否通过，true或false
- confidence: 置信度，0-100的整数
- issues: 问题列表，通过时为[]，不通过时包含具体对象
- summary: 总体评价，简短描述

=== 重要约束 ===
1. 必须返回合法JSON，不要添加markdown代码块标记
2. results数组长度必须等于{len(rules)}
3. 每个规则都要有对应的result对象
4. issues数组为空时写成 [] 而不是 null
5. 字符串使用双引号，不要使用单引号
6. 最后一个元素后面不要加逗号
7. 不要包含任何解释说明文字，只返回JSON"""

        if repeat_prompt:
            return base_prompt + REPETITION_SEPARATOR + base_prompt
        else:
            return base_prompt

    @staticmethod
    def _check_unresolved_vars(prompt: str) -> List[str]:
        """检查prompt中未解析的变量

        Args:
            prompt: 规则提示词

        Returns:
            未解析变量列表，如 ['{{data}}', '{{data.xxx}}']
        """
        # 统一使用 {{data.xxx}} 格式
        pattern = r'\{\{data(\.[^}]+)?\}\}'  # {{data}} 或 {{data.xxx}}

        unresolved = []
        matches = re.findall(pattern, prompt)
        if matches:
            # 将匹配结果还原为完整变量格式
            unresolved = [f'{{{{data{match}}}}}' for match in matches]

        return unresolved

    @staticmethod
    def filter_rules_by_data_availability(rules: List[Dict], user_data: Optional[Dict] = None) -> tuple:
        """根据数据可用性过滤规则

        Args:
            rules: 原始规则列表
            user_data: 用户传入的数据对象（可选，包含所有数据包括Excel）

        Returns:
            (executable_rules, skipped_rules) 元组
            - executable_rules: 可执行的规则列表
            - skipped_rules: 被跳过的规则信息列表 [{idx, name, reason}, ...]
        """
        executable_rules = []
        skipped_rules = []

        for idx, rule in enumerate(rules):
            prompt = rule.get('prompt', '')

            # 如果有user_data，替换data变量（统一格式）
            if user_data:
                prompt = PromptBuilder._replace_data_vars(prompt, user_data)

            # 检查是否还有未解析的变量
            unresolved_vars = PromptBuilder._check_unresolved_vars(prompt)

            if unresolved_vars:
                # 规则包含未解析变量，跳过
                skipped_rules.append({
                    'idx': idx,
                    'name': rule.get('name', f'规则{idx}'),
                    'reason': f'缺少数据: 规则包含未解析的变量 {", ".join(unresolved_vars)}'
                })
            else:
                # 规则可执行，使用替换后的prompt
                executable_rule = rule.copy()
                executable_rule['prompt'] = prompt
                executable_rules.append(executable_rule)

        return executable_rules, skipped_rules

    @staticmethod
    def _replace_data_vars(prompt: str, data: Dict) -> str:
        """替换data变量

        Args:
            prompt: 规则提示词
            data: 用户传入的数据对象

        Returns:
            替换后的提示词
        """
        def replace_data_var(match):
            var_path = match.group(1)  # 例如: "dxInfos.cchrservername"
            parts = var_path.split('.')

            # 从data对象中按路径查找值
            value = data
            try:
                for part in parts:
                    if isinstance(value, dict):
                        value = value.get(part)
                    else:
                        return match.group(0)  # 无法解析，返回原变量

                if value is None:
                    return match.group(0)  # 值为None，返回原变量

                return str(value)
            except Exception:
                return match.group(0)  # 解析失败，返回原变量

        # 匹配 {{data.xxx}} 或 {{data.xxx.yyy}} 等格式
        pattern = r'\{\{data\.([^}]+)\}\}'
        return re.sub(pattern, replace_data_var, prompt)


class ProxyHandler(SimpleHTTPRequestHandler):
    """继承静态文件服务，增加 /api/proxy 代理端点和配置管理API"""

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate')
        self.send_header('Pragma', 'no-cache')
        self.send_header('Expires', '0')
        super().end_headers()

    def do_GET(self):
        if self.path == '/api/config/rules':
            self._get_rules_index()
        elif self.path == '/api/config/api':
            self._get_api_config()
        elif self.path == '/api/rules':
            self._api_get_rules()
        elif self.path.startswith('/api/config/rules/'):
            group_id = self.path[len('/api/config/rules/'):].split('?')[0]
            self._get_rule_group(group_id)
        elif self.path.startswith('/api/ticket/'):
            parts = self.path.split('/')
            if len(parts) >= 4:
                ticket_id = parts[3].split('?')[0]
                if ticket_id:
                    self._get_ticket(ticket_id)
                    return
            self.send_error(404, 'Not Found')
        else:
            super().do_GET()

    def do_POST(self):
        if self.path == '/api/proxy':
            self._handle_proxy()
        elif self.path == '/api/config/rules':
            self._create_rule_group()
        elif self.path == '/api/audit':
            self._api_audit()
        elif self.path == '/api/ticket/download':
            self._ticket_download()
        else:
            self.send_error(404, 'Not Found')

    def do_PUT(self):
        if self.path.startswith('/api/config/rules/'):
            group_id = self.path[len('/api/config/rules/'):].split('?')[0]
            self._update_rule_group(group_id)
        elif self.path == '/api/config/api':
            self._update_api_config()
        else:
            self.send_error(404, 'Not Found')

    def do_DELETE(self):
        if self.path.startswith('/api/config/rules/'):
            group_id = self.path[len('/api/config/rules/'):].split('?')[0]
            self._delete_rule_group(group_id)
        else:
            self.send_error(404, 'Not Found')

    def _handle_proxy(self):
        try:
            length = int(self.headers.get('Content-Length', 0))
            raw = self.rfile.read(length)
            data = json.loads(raw.decode('utf-8'))

            body = data.get('body', {})
            
            api_config = self._load_api_config_from_file()
            
            endpoint = data.get('endpoint') or api_config.get('endpoint', '')
            api_key = data.get('apiKey') or api_config.get('apiKey', '')

            if not endpoint:
                self._send_json(400, {'error': '缺少 endpoint 参数'})
                return

            req_body = json.dumps(body, ensure_ascii=False).encode('utf-8')
            req = urllib.request.Request(
                endpoint,
                data=req_body,
                method='POST',
            )
            req.add_header('Content-Type', 'application/json')
            if api_key:
                req.add_header('Authorization', 'Bearer ' + api_key)

            ctx = ssl.create_default_context()
            resp = urllib.request.urlopen(req, timeout=120, context=ctx)

            resp_body = resp.read()

            # 修复模型返回的畸形 JSON
            resp_body = JSONRepair.repair_response(resp_body)

            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(resp_body)))
            self.end_headers()
            self.wfile.write(resp_body)

        except urllib.error.HTTPError as e:
            err_body = e.read()
            self.send_response(e.code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(err_body)))
            self.end_headers()
            self.wfile.write(err_body)

        except urllib.error.URLError as e:
            self._send_json(502, {'error': f'无法连接到 AI 服务: {e.reason}'})

        except TimeoutError:
            self._send_json(504, {'error': 'AI 服务响应超时'})

        except json.JSONDecodeError:
            self._send_json(400, {'error': '请求体 JSON 格式错误'})

        except Exception as e:
            self._send_json(500, {'error': str(e)})

    def _get_rules_index(self):
        try:
            index_path = os.path.join(CONFIG_DIR, 'rules', 'index.json')
            with open(index_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            self._send_json(200, data)
        except FileNotFoundError:
            self._send_json(404, {'error': '规则索引文件不存在'})
        except Exception as e:
            self._send_json(500, {'error': str(e)})

    def _get_api_config(self):
        """获取API配置（不返回API Key的实际值）"""
        try:
            api_config_path = os.path.join(CONFIG_DIR, 'api.json')
            with open(api_config_path, 'r', encoding='utf-8') as f:
                config = json.load(f)

            result = {
                'endpoint': config.get('endpoint', 'https://api.deepseek.com/v1/chat/completions'),
                'model': config.get('model', 'deepseek-chat'),
                'auditRole': config.get('auditRole', '专业文档审核专家'),
                'hasApiKey': bool(config.get('apiKey', ''))
            }

            self._send_json(200, result)
        except FileNotFoundError:
            self._send_json(404, {'error': 'API配置文件不存在'})
        except Exception as e:
            self._send_json(500, {'error': str(e)})

    def _update_api_config(self):
        """更新API配置"""
        try:
            length = int(self.headers.get('Content-Length', 0))
            raw = self.rfile.read(length)
            data = json.loads(raw.decode('utf-8'))

            api_config_path = os.path.join(CONFIG_DIR, 'api.json')

            existing_config = {}
            if os.path.exists(api_config_path):
                with open(api_config_path, 'r', encoding='utf-8') as f:
                    existing_config = json.load(f)

            update_fields = ['endpoint', 'model', 'auditRole']
            for field in update_fields:
                if field in data:
                    existing_config[field] = data[field]

            if 'apiKey' in data and data['apiKey']:
                existing_config['apiKey'] = data['apiKey']

            with open(api_config_path, 'w', encoding='utf-8') as f:
                json.dump(existing_config, f, ensure_ascii=False, indent=4)

            self._send_json(200, {'success': True, 'message': 'API配置已更新'})
        except json.JSONDecodeError:
            self._send_json(400, {'error': '请求体 JSON 格式错误'})
        except Exception as e:
            self._send_json(500, {'error': str(e)})

    def _get_rule_group(self, group_id):
        try:
            if not self._is_safe_id(group_id):
                self._send_json(400, {'error': '无效的规则组ID'})
                return

            group_path = os.path.join(CONFIG_DIR, 'rules', f'{group_id}.json')
            with open(group_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            self._send_json(200, data)
        except FileNotFoundError:
            self._send_json(404, {'error': f'规则组 {group_id} 不存在'})
        except Exception as e:
            self._send_json(500, {'error': str(e)})

    def _create_rule_group(self):
        try:
            length = int(self.headers.get('Content-Length', 0))
            raw = self.rfile.read(length)
            data = json.loads(raw.decode('utf-8'))

            group_id = data.get('id', '')
            group_name = data.get('name', group_id)
            rules = data.get('rules', [])

            if not group_id:
                self._send_json(400, {'error': '缺少规则组ID'})
                return

            if not self._is_safe_id(group_id):
                self._send_json(400, {'error': '无效的规则组ID，只能包含字母、数字、下划线和横线'})
                return

            group_path = os.path.join(CONFIG_DIR, 'rules', f'{group_id}.json')
            if os.path.exists(group_path):
                self._send_json(409, {'error': f'规则组 {group_id} 已存在'})
                return

            with open(group_path, 'w', encoding='utf-8') as f:
                json.dump(rules, f, ensure_ascii=False, indent=4)

            index_path = os.path.join(CONFIG_DIR, 'rules', 'index.json')
            with open(index_path, 'r', encoding='utf-8') as f:
                index_data = json.load(f)

            index_data['groups'].append({'id': group_id, 'name': group_name})

            with open(index_path, 'w', encoding='utf-8') as f:
                json.dump(index_data, f, ensure_ascii=False, indent=4)

            self._send_json(200, {'success': True, 'id': group_id, 'path': f'config/rules/{group_id}.json'})
        except json.JSONDecodeError:
            self._send_json(400, {'error': '请求体 JSON 格式错误'})
        except Exception as e:
            self._send_json(500, {'error': str(e)})

    def _update_rule_group(self, group_id):
        try:
            if not self._is_safe_id(group_id):
                self._send_json(400, {'error': '无效的规则组ID'})
                return

            length = int(self.headers.get('Content-Length', 0))
            raw = self.rfile.read(length)
            data = json.loads(raw.decode('utf-8'))

            rules = data.get('rules', [])
            group_name = data.get('name')

            group_path = os.path.join(CONFIG_DIR, 'rules', f'{group_id}.json')
            if not os.path.exists(group_path):
                self._send_json(404, {'error': f'规则组 {group_id} 不存在'})
                return

            with open(group_path, 'w', encoding='utf-8') as f:
                json.dump(rules, f, ensure_ascii=False, indent=4)

            if group_name:
                index_path = os.path.join(CONFIG_DIR, 'rules', 'index.json')
                with open(index_path, 'r', encoding='utf-8') as f:
                    index_data = json.load(f)

                for group in index_data['groups']:
                    if group['id'] == group_id:
                        group['name'] = group_name
                        break

                with open(index_path, 'w', encoding='utf-8') as f:
                    json.dump(index_data, f, ensure_ascii=False, indent=4)

            self._send_json(200, {'success': True})
        except json.JSONDecodeError:
            self._send_json(400, {'error': '请求体 JSON 格式错误'})
        except Exception as e:
            self._send_json(500, {'error': str(e)})

    def _delete_rule_group(self, group_id):
        try:
            if not self._is_safe_id(group_id):
                self._send_json(400, {'error': '无效的规则组ID'})
                return

            if group_id == 'index':
                self._send_json(403, {'error': '不能删除索引文件'})
                return

            group_path = os.path.join(CONFIG_DIR, 'rules', f'{group_id}.json')
            if not os.path.exists(group_path):
                self._send_json(404, {'error': f'规则组 {group_id} 不存在'})
                return

            index_path = os.path.join(CONFIG_DIR, 'rules', 'index.json')
            with open(index_path, 'r', encoding='utf-8') as f:
                index_data = json.load(f)

            original_count = len(index_data['groups'])
            index_data['groups'] = [g for g in index_data['groups'] if g['id'] != group_id]

            if len(index_data['groups']) == original_count:
                self._send_json(404, {'error': f'规则组 {group_id} 不在索引中'})
                return

            if index_data['defaultGroup'] == group_id and index_data['groups']:
                index_data['defaultGroup'] = index_data['groups'][0]['id']

            os.remove(group_path)

            with open(index_path, 'w', encoding='utf-8') as f:
                json.dump(index_data, f, ensure_ascii=False, indent=4)

            self._send_json(200, {'success': True})
        except Exception as e:
            self._send_json(500, {'error': str(e)})

    def _is_safe_id(self, id_str):
        if not id_str or len(id_str) > 50:
            return False
        return all(c.isalnum() or c in '_-' for c in id_str)

    def _load_api_config_from_file(self):
        """从api.json文件加载配置"""
        try:
            api_config_path = os.path.join(CONFIG_DIR, 'api.json')
            if os.path.exists(api_config_path):
                with open(api_config_path, 'r', encoding='utf-8') as f:
                    return json.load(f)
            return {}
        except Exception:
            return {}

    def _api_get_rules(self):
        """获取规则组列表（第三方API）"""
        try:
            index_path = os.path.join(CONFIG_DIR, 'rules', 'index.json')
            with open(index_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            self._send_json(200, data)
        except FileNotFoundError:
            self._send_json(404, {'error': '规则索引文件不存在'})
        except Exception as e:
            self._send_json(500, {'error': str(e)})

    def _get_ticket(self, ticket_id):
        """获取工单信息"""
        try:
            api_config = self._load_api_config_from_file()
            ticket_endpoint = api_config.get('ticketEndpoint', '')
            
            if not ticket_endpoint:
                self._send_json(500, {'error': '工单平台地址未配置，请在设置中配置 ticketEndpoint'})
                return
            
            url = ticket_endpoint.replace('{ticketId}', ticket_id)
            if url == ticket_endpoint and not url.endswith(ticket_id):
                url = url.rstrip('/') + '/' + ticket_id
            
            ctx = ssl.create_default_context()
            req = urllib.request.Request(url)
            ticket_token = api_config.get('ticketToken', '')
            if ticket_token:
                req.add_header('Authorization', f'Bearer {ticket_token}')
            
            with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT, context=ctx) as resp:
                result = json.loads(resp.read().decode('utf-8'))
            
            self._send_json(200, result)
            
        except urllib.error.HTTPError as e:
            err_body = e.read().decode('utf-8', errors='ignore')
            self._send_json(e.code, {'error': f'工单平台返回错误: {e.code}', 'detail': err_body})
        except urllib.error.URLError as e:
            self._send_json(502, {'error': f'无法连接工单平台: {e.reason}'})
        except TimeoutError:
            self._send_json(504, {'error': '工单平台响应超时'})
        except json.JSONDecodeError:
            self._send_json(502, {'error': '工单平台返回的数据不是有效的JSON'})
        except Exception as e:
            self._send_json(500, {'error': str(e)})

    def _ticket_download(self):
        """代理下载工单文档"""
        try:
            length = int(self.headers.get('Content-Length', 0))
            raw = self.rfile.read(length)
            data = json.loads(raw.decode('utf-8'))
            
            url = data.get('url')
            if not url:
                self._send_json(400, {'error': '缺少 url 参数'})
                return
            
            print(f"[Ticket Download] 请求下载: {url}")
            
            req = urllib.request.Request(url)
            
            if url.startswith('https://'):
                ctx = ssl.create_default_context()
                with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT, context=ctx) as resp:
                    file_bytes = resp.read(MAX_FILE_SIZE + 1)
            else:
                with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT) as resp:
                    file_bytes = resp.read(MAX_FILE_SIZE + 1)
            
            print(f"[Ticket Download] 下载成功, 大小: {len(file_bytes)} bytes")
            
            if len(file_bytes) > MAX_FILE_SIZE:
                self._send_json(413, {'error': '文件大小超过限制（50MB）'})
                return
            
            self.send_response(200)
            self.send_header('Content-Type', 'application/octet-stream')
            self.send_header('Content-Length', str(len(file_bytes)))
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(file_bytes)
            
        except urllib.error.HTTPError as e:
            err_body = e.read().decode('utf-8', errors='ignore')
            print(f"[Ticket Download] HTTPError: {e.code} - {err_body}")
            self._send_json(e.code, {'error': f'下载失败: {e.code}', 'detail': err_body})
        except urllib.error.URLError as e:
            print(f"[Ticket Download] URLError: {e.reason}")
            self._send_json(502, {'error': f'无法下载文件: {e.reason}'})
        except TimeoutError:
            print(f"[Ticket Download] TimeoutError")
            self._send_json(504, {'error': '下载超时'})
        except json.JSONDecodeError:
            print(f"[Ticket Download] JSONDecodeError")
            self._send_json(400, {'error': '请求体 JSON 格式错误'})
        except Exception as e:
            print(f"[Ticket Download] Exception: {type(e).__name__}: {e}")
            self._send_json(500, {'error': str(e)})

    def _api_audit(self):
        """文档审核接口（第三方API）"""
        try:
            length = int(self.headers.get('Content-Length', 0))
            raw = self.rfile.read(length)
            data = json.loads(raw.decode('utf-8'))

            rule_group_id = data.get('ruleGroupId', '')
            if not rule_group_id:
                self._send_json(400, {'error': '缺少 ruleGroupId 参数'})
                return

            if not self._is_safe_id(rule_group_id):
                self._send_json(400, {'error': '无效的规则组ID'})
                return

            group_path = os.path.join(CONFIG_DIR, 'rules', f'{rule_group_id}.json')
            if not os.path.exists(group_path):
                self._send_json(404, {'error': f'规则组 {rule_group_id} 不存在'})
                return

            with open(group_path, 'r', encoding='utf-8') as f:
                rules = json.load(f)

            if not rules:
                self._send_json(400, {'error': '规则组为空'})
                return

            document_url = data.get('documentUrl')
            document_base64 = data.get('documentBase64')
            document_type = data.get('documentType', 'docx')

            if not document_url and not document_base64:
                self._send_json(400, {'error': '缺少 documentUrl 或 documentBase64 参数'})
                return

            document_bytes = None
            content_type = None

            if document_url:
                try:
                    ctx = ssl.create_default_context()
                    req = urllib.request.Request(document_url)
                    with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT, context=ctx) as resp:
                        content_type = resp.headers.get('Content-Type', '')
                        document_bytes = resp.read(MAX_FILE_SIZE + 1)
                        
                        if len(document_bytes) > MAX_FILE_SIZE:
                            self._send_json(413, {'error': '文件大小超过限制（50MB）'})
                            return
                except urllib.error.URLError as e:
                    self._send_json(502, {'error': f'无法下载文档: {e.reason}'})
                    return
                except TimeoutError:
                    self._send_json(504, {'error': '下载文档超时'})
                    return
            else:
                try:
                    document_bytes = base64.b64decode(document_base64)
                except Exception:
                    self._send_json(400, {'error': 'documentBase64 格式错误'})
                    return

            if not document_type or document_type == 'auto':
                document_type = DocumentParser.detect_type(document_bytes, content_type)
                if not document_type:
                    self._send_json(422, {'error': '无法识别文件类型'})
                    return

            if document_type == 'docx':
                document_text = DocumentParser.parse_docx(document_bytes)
            elif document_type == 'txt':
                document_text = DocumentParser.parse_txt(document_bytes)
            else:
                self._send_json(422, {'error': f'不支持的文件类型: {document_type}，目前支持 docx 和 txt'})
                return

            if not document_text:
                self._send_json(422, {'error': '无法解析文档内容'})
                return

            excel_data = None
            excel_url = data.get('excelUrl')
            excel_base64 = data.get('excelBase64')

            if excel_url or excel_base64:
                excel_bytes = None
                if excel_url:
                    try:
                        ctx = ssl.create_default_context()
                        req = urllib.request.Request(excel_url)
                        with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT, context=ctx) as resp:
                            excel_bytes = resp.read(MAX_FILE_SIZE + 1)
                            if len(excel_bytes) > MAX_FILE_SIZE:
                                self._send_json(413, {'error': 'Excel文件大小超过限制（50MB）'})
                                return
                    except Exception as e:
                        self._send_json(502, {'error': f'无法下载Excel: {str(e)}'})
                        return
                else:
                    try:
                        excel_bytes = base64.b64decode(excel_base64)
                    except Exception:
                        self._send_json(400, {'error': 'excelBase64 格式错误'})
                        return

                excel_data = DocumentParser.parse_excel(excel_bytes)
                if not excel_data:
                    self._send_json(422, {'error': '无法解析Excel文件'})
                    return

            # 获取用户传入的data参数（统一数据格式）
            user_data = data.get('data', {})  # 支持用户传入的数据对象

            # 如果有Excel数据，合并到user_data中（统一使用{{data.xxx}}格式）
            if excel_data:
                # 将Excel的sheets数据合并到user_data
                if 'sheets' not in user_data:
                    user_data['sheets'] = excel_data.get('sheets', [])
                # 如果user_data已有sheets，需要决定是否覆盖（这里优先使用已解析的excel_data）
                # 或者可以合并：user_data['sheets'] = excel_data.get('sheets', [])

            settings = data.get('settings', {})
            endpoint = settings.get('endpoint', 'https://api.deepseek.com/v1/chat/completions')
            api_key = settings.get('apiKey', '')
            model = settings.get('model', 'deepseek-chat')
            audit_role = settings.get('auditRole', '专业文档审核专家')
            repeat_prompt = settings.get('repeatPrompt', True)
            batch_size = settings.get('batchSize', 0)

            # 过滤规则：分离可执行规则和跳过规则（统一使用user_data）
            executable_rules, skipped_rules = PromptBuilder.filter_rules_by_data_availability(rules, user_data)

            # 记录跳过的规则
            if skipped_rules:
                print(f"\n[规则过滤] 跳过 {len(skipped_rules)} 条规则（缺少数据）:")
                logger.info(f"[规则过滤] 跳过 {len(skipped_rules)} 条规则（缺少数据）:")
                for skipped in skipped_rules:
                    print(f"  - 规则{skipped['idx']} [{skipped['name']}]: {skipped['reason']}")
                    logger.info(f"  - 规则{skipped['idx']} [{skipped['name']}]: {skipped['reason']}")
                print()

            # 如果没有可执行规则，直接返回结果
            if not executable_rules:
                logger.warning("[规则过滤] 所有规则都被跳过，无可用规则执行审核")
                # 为所有规则生成跳过结果
                all_skipped_results = []
                for idx, rule in enumerate(rules):
                    # 查找跳过原因
                    skip_info = next((s for s in skipped_rules if s['idx'] == idx), None)
                    reason = skip_info['reason'] if skip_info else '未知原因'

                    all_skipped_results.append({
                        'ruleId': idx,
                        'ruleName': rule.get('name', f'规则{idx}'),
                        'severity': rule.get('severity', 'warning'),
                        'pass': True,  # 跳过的规则视为"通过"（实际未检查）
                        'confidence': 100,
                        'issues': [],
                        'summary': f'已跳过: {reason}'
                    })

                return self._send_json(200, {'success': True, 'results': all_skipped_results})

            if batch_size > 0 and len(executable_rules) > batch_size:
                all_results = []
                total_batches = (len(executable_rules) + batch_size - 1) // batch_size

                for batch_idx in range(total_batches):
                    start_idx = batch_idx * batch_size
                    end_idx = min(start_idx + batch_size, len(executable_rules))
                    batch_rules = executable_rules[start_idx:end_idx]

                    # 构建batch上下文
                    batch_context = {
                        'document_text': document_text,
                        'rules': batch_rules,  # 使用当前batch的可执行规则
                        'user_data': user_data,  # 统一使用user_data
                        'repeat_prompt': repeat_prompt
                    }

                    batch_config = {
                        'endpoint': endpoint,
                        'apiKey': api_key,
                        'model': model,
                        'auditRole': audit_role
                    }

                    prompt = PromptBuilder.build_batch_prompt(batch_rules, document_text, user_data, repeat_prompt)
                    batch_result = self._call_llm(prompt, endpoint, api_key, model, audit_role)

                    if 'error' in batch_result:
                        return self._send_json(502, {'error': batch_result['error']})

                    # 传递上下文给_parse_audit_results
                    batch_results = self._parse_audit_results(batch_result.get('content', ''), batch_rules, batch_context, batch_config)

                    for i, r in enumerate(batch_results):
                        r['ruleId'] = start_idx + i
                    all_results.extend(batch_results)

                # 合并跳过规则的结果
                # 为跳过的规则创建索引映射
                executable_idx_map = {}  # 原始rules索引 -> executable_results索引
                for new_idx, rule in enumerate(executable_rules):
                    # 查找原始索引
                    original_idx = rules.index(rule)
                    executable_idx_map[original_idx] = new_idx

                final_results = []
                for idx, rule in enumerate(rules):
                    if idx in executable_idx_map:
                        # 可执行规则，从all_results获取
                        result_idx = executable_idx_map[idx]
                        if result_idx < len(all_results):
                            final_results.append(all_results[result_idx])
                        else:
                            # 结果缺失，添加错误
                            final_results.append({
                                'ruleId': idx,
                                'ruleName': rule.get('name', f'规则{idx}'),
                                'severity': rule.get('severity', 'warning'),
                                'pass': False,
                                'confidence': PARSE_FAILURE_CONFIDENCE,
                                'issues': [{'location': '解析', 'problem': '未找到审核结果', 'suggestion': '请重试'}],
                                'summary': '解析失败'
                            })
                    else:
                        # 跳过的规则
                        skip_info = next((s for s in skipped_rules if s['idx'] == idx), None)
                        reason = skip_info['reason'] if skip_info else '未知原因'
                        final_results.append({
                            'ruleId': idx,
                            'ruleName': rule.get('name', f'规则{idx}'),
                            'severity': rule.get('severity', 'warning'),
                            'pass': True,
                            'confidence': 100,
                            'issues': [],
                            'summary': f'已跳过: {reason}'
                        })

                return self._send_json(200, {'success': True, 'results': final_results})
            else:
                # 非批量处理：使用可执行规则
                prompt = PromptBuilder.build_batch_prompt(executable_rules, document_text, user_data, repeat_prompt)

            # 保存审核上下文用于重试（使用可执行规则）
            audit_context = {
                'document_text': document_text,
                'rules': executable_rules,  # 使用可执行规则
                'user_data': user_data,  # 统一使用user_data
                'repeat_prompt': repeat_prompt
            }

            audit_config = {
                'endpoint': endpoint,
                'apiKey': api_key,
                'model': model,
                'auditRole': audit_role
            }

            llm_body = {
                'model': model,
                'messages': [
                    {'role': 'system', 'content': f'{audit_role}，你擅长发现文档中的结构、逻辑和合规问题。请严格按照要求的JSON格式返回结果，不要添加任何额外说明。'},
                    {'role': 'user', 'content': prompt}
                ],
                'temperature': 0.1
            }

            req_body = json.dumps(llm_body, ensure_ascii=False).encode('utf-8')
            req = urllib.request.Request(endpoint, data=req_body, method='POST')
            req.add_header('Content-Type', 'application/json')
            if api_key:
                req.add_header('Authorization', 'Bearer ' + api_key)

            ctx = ssl.create_default_context()
            with urllib.request.urlopen(req, timeout=120, context=ctx) as resp:
                resp_body = resp.read()
                resp_body = JSONRepair.repair_response(resp_body)
                llm_result = json.loads(resp_body.decode('utf-8'))

            content = llm_result.get('choices', [{}])[0].get('message', {}).get('content', '')

            # 解析可执行规则的结果
            executable_results = self._parse_audit_results(content, executable_rules, audit_context, audit_config)

            # 合并跳过规则的结果
            # 创建索引映射
            executable_idx_map = {}
            for new_idx, rule in enumerate(executable_rules):
                original_idx = rules.index(rule)
                executable_idx_map[original_idx] = new_idx

            final_results = []
            for idx, rule in enumerate(rules):
                if idx in executable_idx_map:
                    # 可执行规则，从executable_results获取
                    result_idx = executable_idx_map[idx]
                    if result_idx < len(executable_results):
                        final_results.append(executable_results[result_idx])
                    else:
                        # 结果缺失
                        final_results.append({
                            'ruleId': idx,
                            'ruleName': rule.get('name', f'规则{idx}'),
                            'severity': rule.get('severity', 'warning'),
                            'pass': False,
                            'confidence': PARSE_FAILURE_CONFIDENCE,
                            'issues': [{'location': '解析', 'problem': '未找到审核结果', 'suggestion': '请重试'}],
                            'summary': '解析失败'
                        })
                else:
                    # 跳过的规则
                    skip_info = next((s for s in skipped_rules if s['idx'] == idx), None)
                    reason = skip_info['reason'] if skip_info else '未知原因'
                    final_results.append({
                        'ruleId': idx,
                        'ruleName': rule.get('name', f'规则{idx}'),
                        'severity': rule.get('severity', 'warning'),
                        'pass': True,
                        'confidence': 100,
                        'issues': [],
                        'summary': f'已跳过: {reason}'
                    })

            self._send_json(200, {'success': True, 'results': final_results})

        except urllib.error.HTTPError as e:
            err_body = e.read().decode('utf-8', errors='ignore')
            self._send_json(502, {'error': f'LLM API错误: {e.code}', 'detail': err_body})
        except urllib.error.URLError as e:
            self._send_json(502, {'error': f'无法连接到LLM服务: {e.reason}'})
        except TimeoutError:
            self._send_json(504, {'error': 'LLM服务响应超时'})
        except json.JSONDecodeError:
            self._send_json(400, {'error': '请求体 JSON 格式错误'})
        except Exception as e:
            self._send_json(500, {'error': str(e)})

    def _parse_audit_results(self, content: str, rules: List[Dict], context: Optional[Dict] = None, config: Optional[Dict] = None) -> List[Dict]:
        """解析审核结果

        Args:
            content: LLM返回的JSON字符串
            rules: 规则列表
            context: 审核上下文（可选，用于重试）
            config: API配置（可选，用于重试）

        Returns:
            审核结果列表
        """
        try:
            try:
                result = json.loads(content)
            except json.JSONDecodeError:
                # 尝试使用JSONRepair修复
                repaired = JSONRepair.repair(content)
                if repaired and repaired != content:
                    try:
                        result = json.loads(repaired)
                    except json.JSONDecodeError as e:
                        # JSONRepair修复失败，触发LLM重试
                        if context and config:
                            error_info = {
                                'error_type': 'JSONDecodeError',
                                'error_message': str(e),
                                'lineno': e.lineno if hasattr(e, 'lineno') else '未知',
                                'colno': e.colno if hasattr(e, 'colno') else '未知'
                            }

                            retry_result = self._retry_audit_with_context(context, error_info, config)

                            if 'error' in retry_result:
                                # 重试调用失败，返回错误
                                return self._build_parse_error_result(rules, f'审核结果解析失败且重试修复失败: {retry_result["error"]}')

                            # 重试成功，解析修复后的内容
                            try:
                                result = json.loads(retry_result.get('content', ''))
                            except json.JSONDecodeError as e2:
                                # 重试后仍无法解析
                                return self._build_parse_error_result(rules, f'审核结果解析失败，重试修复后仍无法解析: {str(e2)}')
                        else:
                            # 无上下文或配置，无法重试
                            raise
                else:
                    # JSONRepair无法修复，触发LLM重试
                    if context and config:
                        error_info = {
                            'error_type': 'JSONDecodeError',
                            'error_message': 'JSON格式严重错误',
                            'lineno': '未知',
                            'colno': '未知'
                        }

                        retry_result = self._retry_audit_with_context(context, error_info, config)

                        if 'error' in retry_result:
                            return self._build_parse_error_result(rules, f'审核结果解析失败且重试修复失败: {retry_result["error"]}')

                        try:
                            result = json.loads(retry_result.get('content', ''))
                        except json.JSONDecodeError as e2:
                            return self._build_parse_error_result(rules, f'审核结果解析失败，重试修复后仍无法解析: {str(e2)}')
                    else:
                        raise

            results_list = result.get('results', [])

            final_results = []
            for idx, rule in enumerate(rules):
                rule_result = None
                for r in results_list:
                    if r.get('ruleId') == idx:
                        rule_result = r
                        break
                
                if not rule_result and idx < len(results_list):
                    rule_result = results_list[idx]

                if rule_result:
                    final_results.append({
                        'ruleId': idx,
                        'ruleName': rule.get('name', f'规则{idx}'),
                        'severity': rule.get('severity', 'warning'),
                        'pass': rule_result.get('pass', False),
                        'confidence': rule_result.get('confidence', 50),
                        'issues': rule_result.get('issues', []),
                        'summary': rule_result.get('summary', '审核完成')
                    })
                else:
                    final_results.append({
                        'ruleId': idx,
                        'ruleName': rule.get('name', f'规则{idx}'),
                        'severity': rule.get('severity', 'warning'),
                        'pass': False,
                        'confidence': 30,
                        'issues': [{'location': '解析', 'problem': '未找到审核结果', 'suggestion': '请重试'}],
                        'summary': '解析失败'
                    })

            return final_results

        except Exception as e:
            return self._build_parse_error_result(rules, f'结果解析失败: {str(e)}')

    def _build_parse_error_result(self, rules: List[Dict], error_message: str) -> List[Dict]:
        """构建解析错误结果（DRY优化）

        Args:
            rules: 规则列表
            error_message: 错误信息

        Returns:
            包含错误信息的审核结果列表
        """
        return [{
            'ruleId': idx,
            'ruleName': rule.get('name', f'规则{idx}'),
            'severity': rule.get('severity', 'warning'),
            'pass': False,
            'confidence': PARSE_FAILURE_CONFIDENCE,
            'issues': [{'location': '解析', 'problem': error_message, 'suggestion': '请重试'}],
            'summary': '解析失败'
        } for idx, rule in enumerate(rules)]

    def _build_retry_prompt(self, context: Dict, error_info: Dict, rules_count: int) -> str:
        """构造重试提示词

        Args:
            context: 审核上下文（包含document_text, rules, user_data, repeat_prompt）
            error_info: 错误信息（包含error_type, error_message, lineno, colno）
            rules_count: 规则数量

        Returns:
            重试提示词字符串
        """
        error_type = error_info.get('error_type', '未知错误')
        error_message = error_info.get('error_message', '')
        lineno = error_info.get('lineno', '未知')
        colno = error_info.get('colno', '未知')

        # 构建重试提示词前缀
        retry_prefix = f"""你之前返回的审核结果JSON格式错误，解析失败。

=== 错误信息 ===
错误类型: {error_type}
错误位置: 第{lineno}行，第{colno}列
错误详情: {error_message}

=== 重要提醒 ===
请严格按照JSON格式要求重新返回审核结果：
1. 必须返回合法JSON对象，不要添加markdown代码块标记
2. results数组长度必须等于{rules_count}
3. 每个ruleId从0到{rules_count-1}都要有对应结果对象
4. 字符串使用双引号，不能用单引号
5. 最后一个元素后面不要加逗号
6. 数组为空时写[]而不是null
7. 不要包含任何解释说明文字，只返回JSON

"""

        # 构建原始完整审核提示词
        document_text = context.get('document_text', '')
        rules = context.get('rules', [])
        user_data = context.get('user_data')  # 统一使用user_data
        repeat_prompt = context.get('repeat_prompt', True)

        original_prompt = PromptBuilder.build_batch_prompt(rules, document_text, user_data, repeat_prompt)

        return retry_prefix + original_prompt

    def _retry_audit_with_context(self, context: Dict, error_info: Dict, config: Dict) -> Dict:
        """执行LLM重试调用

        Args:
            context: 审核上下文
            error_info: JSON解析错误信息
            config: API配置

        Returns:
            成功返回{'content': ...}，失败返回{'error': ...}
        """
        try:
            rules = context.get('rules', [])
            endpoint = config.get('endpoint', 'https://api.minimaxi.com/v1/chat/completions')
            api_key = config.get('apiKey', '')
            model = config.get('model', 'MiniMax-M2.7')
            audit_role = config.get('auditRole', '专业文档审核专家')

            # 构建重试提示词
            retry_prompt = self._build_retry_prompt(context, error_info, len(rules))

            logger.info("[Retry] JSON解析失败，启动LLM重试修复")

            # 构建LLM请求体，使用更低的temperature提高格式规范性
            llm_body = {
                'model': model,
                'messages': [
                    {'role': 'system', 'content': f'{audit_role}，你擅长发现文档中的结构、逻辑和合规问题。请严格按照要求的JSON格式返回结果，不要添加任何额外说明。'},
                    {'role': 'user', 'content': retry_prompt}
                ],
                'temperature': RETRY_TEMPERATURE
            }

            req_body = json.dumps(llm_body, ensure_ascii=False).encode('utf-8')
            req = urllib.request.Request(endpoint, data=req_body, method='POST')
            req.add_header('Content-Type', 'application/json')
            if api_key:
                req.add_header('Authorization', 'Bearer ' + api_key)

            ctx = ssl.create_default_context()
            with urllib.request.urlopen(req, timeout=LLM_TIMEOUT_SECONDS, context=ctx) as resp:
                resp_body = resp.read()
                resp_body = JSONRepair.repair_response(resp_body)
                llm_result = json.loads(resp_body.decode('utf-8'))

            content = llm_result.get('choices', [{}])[0].get('message', {}).get('content', '')

            logger.info("[Retry] 修复成功")
            return {'content': content}

        except urllib.error.HTTPError as e:
            err_body = e.read().decode('utf-8', errors='ignore')
            logger.error(f"[Retry] 修复失败: LLM API错误 {e.code}")
            return {'error': f'LLM API错误: {e.code}', 'detail': err_body}
        except urllib.error.URLError as e:
            logger.error(f"[Retry] 修复失败: 无法连接到LLM服务")
            return {'error': f'无法连接到LLM服务，请检查网络连接或API端点配置: {e.reason}'}
        except TimeoutError:
            logger.error(f"[Retry] 修复失败: LLM服务响应超时")
            return {'error': f'LLM服务响应超时（{LLM_TIMEOUT_SECONDS}秒）'}
        except Exception as e:
            logger.error(f"[Retry] 修复失败: {str(e)}")
            return {'error': str(e)}

    def _call_llm(self, prompt: str, endpoint: str, api_key: str, model: str, audit_role: str) -> Dict:
        """调用LLM API的辅助方法

        Args:
            prompt: 提示词
            endpoint: API端点
            api_key: API密钥
            model: 模型名称
            audit_role: 审核角色

        Returns:
            成功返回{'content': ...}，失败返回{'error': ...}
        """
        try:
            llm_body = {
                'model': model,
                'messages': [
                    {'role': 'system', 'content': f'{audit_role}，你擅长发现文档中的结构、逻辑和合规问题。请严格按照要求的JSON格式返回结果，不要添加任何额外说明。'},
                    {'role': 'user', 'content': prompt}
                ],
                'temperature': LLM_TEMPERATURE
            }

            req_body = json.dumps(llm_body, ensure_ascii=False).encode('utf-8')
            req = urllib.request.Request(endpoint, data=req_body, method='POST')
            req.add_header('Content-Type', 'application/json')
            if api_key:
                req.add_header('Authorization', 'Bearer ' + api_key)

            ctx = ssl.create_default_context()
            with urllib.request.urlopen(req, timeout=LLM_TIMEOUT_SECONDS, context=ctx) as resp:
                resp_body = resp.read()
                resp_body = JSONRepair.repair_response(resp_body)
                llm_result = json.loads(resp_body.decode('utf-8'))

            content = llm_result.get('choices', [{}])[0].get('message', {}).get('content', '')
            return {'content': content}

        except urllib.error.HTTPError as e:
            err_body = e.read().decode('utf-8', errors='ignore')
            return {'error': f'LLM API错误: {e.code}', 'detail': err_body}
        except urllib.error.URLError as e:
            return {'error': f'无法连接到LLM服务: {e.reason}'}
        except TimeoutError:
            return {'error': f'LLM服务响应超时（{LLM_TIMEOUT_SECONDS}秒）'}
        except Exception as e:
            return {'error': str(e)}

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        print(f'[Server] {self.address_string()} - {format % args}')


if __name__ == '__main__':
    host, port = '0.0.0.0', 8000
    server = ThreadingHTTPServer((host, port), ProxyHandler)
    print(f'SmartDoc AI 服务已启动: http://localhost:{port}')
    print(f'API 代理端点: http://localhost:{port}/api/proxy')
    print(f'配置管理端点: http://localhost:{port}/api/config/rules')
    print(f'第三方API端点:')
    print(f'  - GET  /api/rules   获取规则组列表')
    print(f'  - POST /api/audit   文档审核')
    print('按 Ctrl+C 停止服务器')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\n服务器已停止')
        server.server_close()
