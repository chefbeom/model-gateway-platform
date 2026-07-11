#!/usr/bin/env python3
"""Deterministic HTTP sink for live incident-notification rehearsals."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import time


EVENTS = []
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _body(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                size_line = self.rfile.readline().strip().split(b";", 1)[0]
                size = int(size_line, 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            return b"".join(chunks)
        return self.rfile.read(int(self.headers.get("Content-Length", "0")))

    def _json(self, status, value):
        encoded = json.dumps(value).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"status": "UP"})
            return
        if self.path == "/events":
            with LOCK:
                snapshot = list(EVENTS)
            self._json(200, snapshot)
            return
        self._json(404, {"error": "not_found"})

    def do_DELETE(self):
        if self.path != "/events":
            self._json(404, {"error": "not_found"})
            return
        with LOCK:
            EVENTS.clear()
        self._json(200, {"cleared": True})

    def do_POST(self):
        raw = self._body()
        try:
            payload = json.loads(raw.decode("utf-8")) if raw else None
        except json.JSONDecodeError:
            payload = {"raw": raw.decode("utf-8", errors="replace")}
        with LOCK:
            EVENTS.append({"path": self.path, "payload": payload, "receivedAt": time.time()})
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *_args):
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 18080), Handler).serve_forever()
