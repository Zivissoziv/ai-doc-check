#!/usr/bin/env python3
"""SmartDoc AI - 带 API 代理的 HTTP 服务器（纯标准库实现）"""

import json
import os
import ssl
import urllib.request
import urllib.error
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

CONFIG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'config')


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
