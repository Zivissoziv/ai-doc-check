#!/usr/bin/env python3
"""SmartDoc AI - 带 API 代理的 HTTP 服务器（纯标准库实现）"""

import json
import ssl
import urllib.request
import urllib.error
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


class ProxyHandler(SimpleHTTPRequestHandler):
    """继承静态文件服务，增加 /api/proxy 代理端点"""

    def do_POST(self):
        if self.path == '/api/proxy':
            self._handle_proxy()
        else:
            self.send_error(404, 'Not Found')

    def _handle_proxy(self):
        try:
            # 读取前端请求体
            length = int(self.headers.get('Content-Length', 0))
            raw = self.rfile.read(length)
            data = json.loads(raw.decode('utf-8'))

            endpoint = data.get('endpoint', '')
            api_key = data.get('apiKey', '')
            body = data.get('body', {})

            if not endpoint:
                self._send_json(400, {'error': '缺少 endpoint 参数'})
                return

            # 构建转发请求
            req_body = json.dumps(body, ensure_ascii=False).encode('utf-8')
            req = urllib.request.Request(
                endpoint,
                data=req_body,
                method='POST',
            )
            req.add_header('Content-Type', 'application/json')
            if api_key:
                req.add_header('Authorization', 'Bearer ' + api_key)

            # 发送请求（支持 HTTPS）
            ctx = ssl.create_default_context()
            resp = urllib.request.urlopen(req, timeout=120, context=ctx)

            # 原样转发 AI API 响应
            resp_body = resp.read()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(resp_body)))
            self.end_headers()
            self.wfile.write(resp_body)

        except urllib.error.HTTPError as e:
            # AI API 返回了 HTTP 错误（如 401、429 等），透传状态码和响应体
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

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        # 在默认日志前加上标识，方便区分代理请求
        print(f'[Server] {self.address_string()} - {format % args}')


if __name__ == '__main__':
    host, port = '0.0.0.0', 8000
    server = ThreadingHTTPServer((host, port), ProxyHandler)
    print(f'SmartDoc AI 服务已启动: http://localhost:{port}')
    print(f'API 代理端点: http://localhost:{port}/api/proxy')
    print('按 Ctrl+C 停止服务器')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\n服务器已停止')
        server.server_close()
