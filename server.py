#!/usr/bin/env python3
"""SmartDoc AI - 带 API 代理的 HTTP 服务器（纯标准库实现）"""

import json
import os
import re
import ssl
import urllib.request
import urllib.error
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

CONFIG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'config')


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
        elif self.path.startswith('/api/config/rules/'):
            group_id = self.path[len('/api/config/rules/'):].split('?')[0]
            self._get_rule_group(group_id)
        else:
            super().do_GET()

    def do_POST(self):
        if self.path == '/api/proxy':
            self._handle_proxy()
        elif self.path == '/api/config/rules':
            self._create_rule_group()
        else:
            self.send_error(404, 'Not Found')

    def do_PUT(self):
        if self.path.startswith('/api/config/rules/'):
            group_id = self.path[len('/api/config/rules/'):].split('?')[0]
            self._update_rule_group(group_id)
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

            endpoint = data.get('endpoint', '')
            api_key = data.get('apiKey', '')
            body = data.get('body', {})

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
    print('按 Ctrl+C 停止服务器')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\n服务器已停止')
        server.server_close()
