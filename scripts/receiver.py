#!/usr/bin/env python3
"""Development webhook: fail the first N calls per delivery, then succeed."""
import argparse
import hashlib
import hmac
import json
import os
import time
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

parser = argparse.ArgumentParser()
parser.add_argument("--fail-first", type=int, default=2)
parser.add_argument("--port", type=int, default=9090)
args = parser.parse_args()
counts = defaultdict(int)
lock = Lock()
master = os.environ.get("WEBHOOK_SIGNING_SECRET", "local-signing-secret-change-me").encode()
tenant_key = hmac.new(master, b"tenant:demo", hashlib.sha256).digest()

class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > 262144 + 4096:
            self.send_error(413); return
        data = self.rfile.read(length)
        timestamp = self.headers.get("X-Webhook-Timestamp", "")
        expected = "v1=" + hmac.new(tenant_key, timestamp.encode() + b"." + data, hashlib.sha256).hexdigest()
        try:
            timely = abs(time.time() - int(timestamp)) <= 300
        except ValueError:
            timely = False
        if not timely or not hmac.compare_digest(expected, self.headers.get("X-Webhook-Signature", "")):
            self.send_error(401); return
        delivery = self.headers.get("X-Delivery-ID", "")
        with lock:
            counts[delivery] += 1
            attempt = counts[delivery]
        code = 500 if attempt <= args.fail_first else 204
        print(json.dumps({"deliveryId": delivery, "receivedAttempt": attempt, "response": code}), flush=True)
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()
    def log_message(self, *_):
        pass

print(f"Development receiver listening on {args.port}", flush=True)
ThreadingHTTPServer(("0.0.0.0", args.port), Receiver).serve_forever()
