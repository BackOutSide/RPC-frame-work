"""Local Alertmanager receiver. Exposes recently delivered alerts for evaluation."""

import argparse
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from threading import Lock


class Receiver(BaseHTTPRequestHandler):
    events = deque(maxlen=256)
    events_lock = Lock()

    def do_POST(self):
        if self.path != "/alerts":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 1024 * 1024:
                raise ValueError("Invalid payload length")
            payload = json.loads(self.rfile.read(length))
            if not isinstance(payload, dict) or not isinstance(payload.get("alerts"), list):
                raise ValueError("Missing alerts list")
        except (ValueError, OSError):
            self.send_error(400, "Expected an Alertmanager webhook payload")
            return

        event = {"received_at": datetime.now(timezone.utc).isoformat(), "payload": payload}
        with self.events_lock:
            self.events.append(event)
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        if self.path == "/healthz":
            self.write_json({"status": "ok"})
        elif self.path == "/events":
            with self.events_lock:
                events = list(self.events)
            self.write_json(events)
        else:
            self.send_error(404)

    def write_json(self, value):
        body = json.dumps(value).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    with ThreadingHTTPServer((args.host, args.port), Receiver) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
