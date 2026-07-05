#!/usr/bin/env python3
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


SAMPLE_TEXT = "This is a sample document for SmartDoc async audit testing.\nAll required content is present.\n"


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_text(self, text, status=200):
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/ticket/"):
            ticket_id = self.path.rsplit("/", 1)[-1]
            base_url = f"http://{self.server.server_address[0]}:{self.server.server_address[1]}"
            if base_url.startswith("http://0.0.0.0"):
                base_url = f"http://127.0.0.1:{self.server.server_address[1]}"
            self._send_json({
                "documentUrl": f"{base_url}/files/sample.txt",
                "documentName": f"ticket_{ticket_id}.txt",
                "data": {
                    "ticketId": ticket_id,
                    "status": "READY"
                }
            })
            return

        if self.path == "/files/sample.txt":
            self._send_text(SAMPLE_TEXT)
            return

        self._send_json({"error": "not found"}, status=404)

    def do_POST(self):
        if self.path == "/v1/chat/completions":
            length = int(self.headers.get("Content-Length", "0"))
            if length:
                self.rfile.read(length)
            content = {
                "results": [
                    {
                        "ruleId": 0,
                        "pass": True,
                        "confidence": 95,
                        "summary": "Mock audit passed",
                        "issues": []
                    }
                ]
            }
            self._send_json({
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": json.dumps(content, ensure_ascii=False)
                        }
                    }
                ]
            })
            return

        self._send_json({"error": "not found"}, status=404)

    def log_message(self, fmt, *args):
        print("%s - - [%s] %s" % (self.address_string(), self.log_date_time_string(), fmt % args))


def main():
    parser = argparse.ArgumentParser(description="Mock ticket, file, and LLM server for async audit tests.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Mock server listening on http://{args.host}:{args.port}")
    print(f"Ticket endpoint: http://{args.host}:{args.port}/ticket/{{id}}")
    print(f"LLM endpoint:    http://{args.host}:{args.port}/v1/chat/completions")
    server.serve_forever()


if __name__ == "__main__":
    main()
