"""Loopback-only manual Android fault fixture; never use as an application gateway.

Map phone tcp:8088 to host tcp:18088 with adb reverse. Only the first refresh
is faulted. All other auth requests go to the real Gateway on 127.0.0.1:8088.
No request/response bodies, credentials or headers are logged or persisted.
"""

import argparse
import http.client
import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


AUTH_PATHS = {f"/api/v1/auth/{name}" for name in
              ("register", "login", "refresh", "logout", "logout-all")}
REFRESH_PATH = "/api/v1/auth/refresh"


class FaultServer(ThreadingHTTPServer):
    def __init__(self, mode):
        super().__init__(("127.0.0.1", 18088), Handler)
        self.mode = mode
        self.pending = True
        self.lock = threading.Lock()

    def claim_fault(self, path):
        with self.lock:
            if path != REFRESH_PATH or not self.pending:
                return False
            self.pending = False
            return True


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def reply(self, status, body=b"", headers=None):
        self.send_response(status)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_POST(self):
        if self.path not in AUTH_PATHS:
            self.reply(404)
            return
        if self.headers.get("Transfer-Encoding"):
            self.reply(400)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.reply(400)
            return
        if not 0 <= length <= 16384:
            self.reply(413)
            return
        body = self.rfile.read(length)
        fault = self.server.claim_fault(self.path)
        if fault and self.server.mode == "503":
            payload = json.dumps({"error": {
                "code": "identity_unavailable",
                "message": "Identity service is unavailable.",
                "details": [], "request_id": "manual-fault-fixture",
            }}).encode()
            self.reply(503, payload, {
                "Content-Type": "application/json", "Cache-Control": "no-store"})
            print("refresh: injected 503; not forwarded", flush=True)
            return
        headers = {name: self.headers[name] for name in
                   ("Content-Type", "Accept", "Authorization", "Idempotency-Key")
                   if name in self.headers}
        connection = http.client.HTTPConnection("127.0.0.1", 8088, timeout=35)
        try:
            connection.request("POST", self.path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            status = response.status
            response_headers = {name: value for name, value in response.getheaders()
                                if name.lower() in {
                                    "content-type", "cache-control", "retry-after",
                                    "www-authenticate", "x-request-id"}}
        except (OSError, http.client.HTTPException):
            self.reply(502)
            print("upstream transport failure", flush=True)
            return
        finally:
            connection.close()
        if fault and self.server.mode == "drop" and status == 200:
            print("refresh: Gateway returned 200; response dropped", flush=True)
            self.close_connection = True
            self.connection.shutdown(socket.SHUT_RDWR)
            self.connection.close()
            return
        self.reply(status, payload, response_headers)
        print(f"{self.path}: forwarded; status {status}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("503", "drop"), required=True)
    args = parser.parse_args()
    with FaultServer(args.mode) as server:
        print(f"Manual fixture on 127.0.0.1:18088; first refresh: {args.mode}",
              flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
