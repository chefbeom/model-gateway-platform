"""Deterministic LM Studio-compatible server for local Gateway smoke tests."""

import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MODEL_KEY = "mock/gemma"
INSTANCE_ID = "mock/gemma:loaded"


class Handler(BaseHTTPRequestHandler):
    server_version = "AiconnectMockLmStudio/1.0"

    def log_message(self, format_string, *args):
        print(format_string % args, flush=True)

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/v1/models":
            self.send_json(200, {
                "models": [{
                    "key": MODEL_KEY,
                    "display_name": "Mock Gemma",
                    "architecture": "gemma",
                    "type": "llm",
                    "quantization": {"name": "Q4_K_M"},
                    "max_context_length": 8192,
                    "capabilities": {"vision": False, "trained_for_tool_use": True},
                    "loaded_instances": [{
                        "id": INSTANCE_ID,
                        "config": {"context_length": 8192, "parallel": 2}
                    }]
                }]
            })
            return
        if self.path == "/v1/models":
            self.send_json(200, {"object": "list", "data": [{"id": INSTANCE_ID, "object": "model"}]})
            return
        self.send_json(404, {"error": "not_found"})

    def read_request_body(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                size_line = self.rfile.readline().strip()
                if not size_line:
                    continue
                size = int(size_line.split(b";", 1)[0], 16)
                if size == 0:
                    while self.rfile.readline().strip():
                        pass
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            return b"".join(chunks)
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length)

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_json(404, {"error": "not_found"})
            return
        request = json.loads(self.read_request_body() or b"{}")
        if request.get("model") != INSTANCE_ID:
            print(f"unexpected model: {request.get('model')!r}; expected: {INSTANCE_ID!r}", flush=True)
            self.send_json(400, {"error": {"message": "unexpected physical model"}})
            return
        if request.get("stream"):
            chunks = [
                {"id": "chatcmpl-mock", "object": "chat.completion.chunk", "created": int(time.time()),
                 "model": INSTANCE_ID, "choices": [{"index": 0, "delta": {"role": "assistant", "content": "mock "}, "finish_reason": None}]},
                {"id": "chatcmpl-mock", "object": "chat.completion.chunk", "created": int(time.time()),
                 "model": INSTANCE_ID, "choices": [{"index": 0, "delta": {"content": "response"}, "finish_reason": "stop"}],
                 "usage": {"prompt_tokens": 4, "completion_tokens": 2, "total_tokens": 6}}
            ]
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            for chunk in chunks:
                self.wfile.write(("data: " + json.dumps(chunk) + "\n\n").encode("utf-8"))
                self.wfile.flush()
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            return
        self.send_json(200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": INSTANCE_ID,
            "choices": [{"index": 0, "message": {"role": "assistant", "content": "mock response"}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 4, "completion_tokens": 2, "total_tokens": 6}
        })


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 1234), Handler).serve_forever()
