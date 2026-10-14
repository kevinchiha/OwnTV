#!/usr/bin/env python3
"""A fake Sentry receiver for the KevBox Live end-to-end run on the emulator.

KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).

    python3 tools/kevbox/e2e/fake_sentry.py --port 8999 --out /tmp/sentry.jsonl

Build the debug app with -Pkevbox.sentryDsnDebug=http://e2e@10.0.2.2:8999/1 and the SDK posts its
events here instead of to sentry.io. The DSN is fake; nothing leaves this PC.

It accepts POST /api/<project>/envelope/ and POST /api/<project>/store/, un-gzips a body sent with
Content-Encoding: gzip (or starting with the gzip magic bytes), and appends one JSON line per
envelope item to --out:

    {"at": "...", "path": "/api/1/envelope/", "item_type": "event", "header": {...}, "payload": {...}}

A payload that is not JSON (an attachment, a minidump) is recorded as its length only. Every
request gets 200 with an empty JSON object, so the SDK drops what it sent and moves on.
"""

import argparse
import gzip
import json
import sys
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()


def decode_body(body, encoding):
    """The raw request body, un-gzipped or inflated when it was sent compressed."""
    encoding = (encoding or "").lower()
    if "gzip" in encoding or body[:2] == b"\x1f\x8b":
        return gzip.decompress(body)
    if "deflate" in encoding:
        return zlib.decompress(body)
    return body


def parse_json(raw):
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return None


def envelope_items(body):
    """Splits a Sentry envelope into (item header, payload bytes) pairs.

    An envelope is a header line, then for each item a header line and a payload. A header with a
    "length" says how many bytes the payload is; without one the payload runs to the next newline.
    """
    items = []
    newline = body.find(b"\n")
    if newline < 0:
        return items
    pos = newline + 1
    while pos < len(body):
        end = body.find(b"\n", pos)
        if end < 0:
            end = len(body)
        line = body[pos:end]
        pos = end + 1
        if not line.strip():
            continue
        header = parse_json(line) or {}
        length = header.get("length")
        if isinstance(length, int):
            payload = body[pos:pos + length]
            pos += length
            if pos < len(body) and body[pos:pos + 1] == b"\n":
                pos += 1
        else:
            end = body.find(b"\n", pos)
            if end < 0:
                end = len(body)
            payload = body[pos:end]
            pos = end + 1
        items.append((header, payload))
    return items


class Handler(BaseHTTPRequestHandler):
    server_version = "fake_sentry/1"
    protocol_version = "HTTP/1.1"
    out_path = None

    def log_message(self, fmt, *args):
        pass

    def answer(self, code):
        body = b"{}"
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def write_lines(self, records):
        with LOCK, open(self.out_path, "a", encoding="utf-8") as out:
            for record in records:
                out.write(json.dumps(record, sort_keys=True) + "\n")

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        parts = path.strip("/").split("/")
        if len(parts) != 3 or parts[0] != "api" or parts[2] not in ("envelope", "store"):
            print(f"{time.strftime('%H:%M:%S')} unknown POST {path}", flush=True)
            self.answer(404)
            return
        try:
            raw = decode_body(body, self.headers.get("Content-Encoding"))
        except (OSError, zlib.error, EOFError):
            print(f"{time.strftime('%H:%M:%S')} undecodable body on {path}", flush=True)
            self.answer(400)
            return

        at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        records = []
        if parts[2] == "store":
            records.append({"at": at, "path": path, "item_type": "event", "header": {}, "payload": parse_json(raw)})
        else:
            for header, payload in envelope_items(raw):
                parsed = parse_json(payload)
                record = {"at": at, "path": path, "item_type": header.get("type"), "header": header}
                if parsed is None:
                    record["payload_bytes"] = len(payload)
                else:
                    record["payload"] = parsed
                records.append(record)
        self.write_lines(records)
        kinds = ",".join(str(r["item_type"]) for r in records) or "(none)"
        print(f"{time.strftime('%H:%M:%S')} {path} items={kinds}", flush=True)
        self.answer(200)


def main():
    parser = argparse.ArgumentParser(description="Fake Sentry receiver for the KevBox Live e2e run.")
    parser.add_argument("--port", type=int, default=8999)
    parser.add_argument("--bind", default="127.0.0.1", help="the emulator reaches 127.0.0.1 as 10.0.2.2")
    parser.add_argument("--out", required=True, help="file that gets one JSON line per envelope item")
    args = parser.parse_args()

    Handler.out_path = args.out
    server = ThreadingHTTPServer((args.bind, args.port), Handler)
    print(f"fake_sentry listening on {args.bind}:{args.port}, writing {args.out}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
