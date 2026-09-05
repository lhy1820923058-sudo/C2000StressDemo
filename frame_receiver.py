#!/usr/bin/env python3
"""Small HTTP sink for the C2000 WiFi stress demo.

It accepts binary POST bodies, intentionally discards them, and reports the
aggregate rate.  This keeps the PC receiver from becoming the bottleneck while
the C2000 sends synthetic payloads or JPEG camera frames.
"""

import argparse
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class ReceiverMetrics:
    def __init__(self):
        self.lock = threading.Lock()
        self.started = time.monotonic()
        self.requests = 0
        self.bytes_received = 0

    def record(self, body_size):
        with self.lock:
            self.requests += 1
            self.bytes_received += body_size
            elapsed = max(0.001, time.monotonic() - self.started)
            return self.requests, self.bytes_received * 8 / elapsed / 1_000_000


METRICS = ReceiverMetrics()


class FrameReceiver(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400, "invalid Content-Length")
            return
        if length < 0 or length > 32 * 1024 * 1024:
            self.send_error(413, "payload too large")
            return

        remaining = length
        while remaining:
            chunk = self.rfile.read(min(64 * 1024, remaining))
            if not chunk:
                self.send_error(400, "incomplete request body")
                return
            remaining -= len(chunk)

        requests, mbps = METRICS.record(length)
        if requests == 1 or requests % 50 == 0:
            print("requests=%d total=%.2f MiB avg=%.2f Mbps" % (
                requests, METRICS.bytes_received / 1024 / 1024, mbps), flush=True)
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, _format, *_args):
        return


def main():
    parser = argparse.ArgumentParser(description="C2000 WiFi stress-test HTTP receiver")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), FrameReceiver)
    print("Receiver listening at http://%s:%d/frame" % (args.host, args.port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nReceiver stopped.", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
