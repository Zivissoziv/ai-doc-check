#!/usr/bin/env python3
"""临时验证用 mock LLM：把收到的请求体存档，便于检查变量是否被替换。
- 支持 stream=true（SSE）与非流式两种返回
- 每次请求追加写入 scripts/.tmp-llm-requests.jsonl
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ARCHIVE = "scripts/.tmp-llm-requests.jsonl"
STREAM_TOKENS = ["# Mock 简报\n", "第一段内容。\n", "第二段内容。\n"]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw.decode("utf-8"))
        except Exception:
            body = {"_raw": raw.decode("utf-8", errors="replace")}

        with open(ARCHIVE, "a", encoding="utf-8") as f:
            f.write(json.dumps(body, ensure_ascii=False) + "\n")

        if body.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.end_headers()
            for tok in STREAM_TOKENS:
                chunk = {"choices": [{"delta": {"content": tok}}]}
                self.wfile.write(("data: " + json.dumps(chunk, ensure_ascii=False) + "\n\n").encode("utf-8"))
                self.wfile.flush()
            # 顺带覆盖 content 为 JSON null 的分片，验证后端不会拼出字面量 "null"
            self.wfile.write(('data: {"choices":[{"delta":{"content":null}}]}\n\n').encode("utf-8"))
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            return

        content = {"results": [{"ruleId": 0, "pass": True, "confidence": 95,
                                "summary": "Mock audit passed", "issues": []}]}
        payload = {"choices": [{"message": {"role": "assistant",
                                            "content": json.dumps(content, ensure_ascii=False)}}]}
        out = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, fmt, *args):
        print("[mock-llm] %s" % (fmt % args), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18099)
    args = ap.parse_args()
    open(ARCHIVE, "w", encoding="utf-8").close()  # 清空历史
    print("mock LLM listening on http://127.0.0.1:%d/v1/chat/completions" % args.port, flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
