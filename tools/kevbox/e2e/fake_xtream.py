#!/usr/bin/env python3
"""A fake Xtream Codes panel for the KevBox Live end-to-end run on the emulator.

KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).

    python3 tools/kevbox/e2e/fake_xtream.py --port 8091
    python3 tools/kevbox/e2e/fake_xtream.py --port 8091 --media /tmp/clip.ts
    python3 tools/kevbox/e2e/fake_xtream.py --port 8091 --stream-status 403
    python3 tools/kevbox/e2e/fake_xtream.py --port 8091 --stream-status 401 --account-status Expired

The emulator reaches the host at 10.0.2.2, so a playlist row with server URL
http://10.0.2.2:8091 points the TV here. player_api.php accepts any username and password and
answers with an active account (auth 1, status Active, expiry 30 days out, one connection), two
live categories with three channels each, and empty movie and series lists.

Every request is logged with the username and password it carried. They are fake, and the log is
how the run proves which credentials the TV is using after an admin edit.

Stream URLs (/live/<user>/<pass>/<id>.ts and friends) answer with the --media file when one is
given, so a channel can play; without it they answer 404. --stream-status N makes every stream URL
answer N instead, whatever --media says, so the error reporter sees a panel refusing a channel.
--account-status WORD puts WORD in player_api.php's status field (auth stays 1), the way a panel
reports an expired or banned line: Expired, Banned, Disabled.
"""

import argparse
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

CATEGORIES = [
    {"category_id": "1", "category_name": "E2E News", "parent_id": 0},
    {"category_id": "2", "category_name": "E2E Sports", "parent_id": 0},
]

CHANNELS = [
    ("101", "E2E News One", "1"),
    ("102", "E2E News Two", "1"),
    ("103", "E2E News Three", "1"),
    ("201", "E2E Sports One", "2"),
    ("202", "E2E Sports Two", "2"),
    ("203", "E2E Sports Three", "2"),
]


def live_streams(category_id):
    out = []
    for num, (stream_id, name, cat) in enumerate(CHANNELS, start=1):
        if category_id and category_id != cat:
            continue
        out.append({
            "num": num,
            "name": name,
            "stream_type": "live",
            "stream_id": int(stream_id),
            "stream_icon": "",
            "epg_channel_id": "",
            "added": "1700000000",
            "category_id": cat,
            "tv_archive": 0,
            "direct_source": "",
            "tv_archive_duration": 0,
        })
    return out


def account(host, port, username, password, status="Active"):
    now = int(time.time())
    return {
        "user_info": {
            "username": username,
            "password": password,
            "message": "",
            "auth": 1,
            "status": status,
            "exp_date": str(now + 30 * 86400),
            "is_trial": "0",
            "active_cons": "0",
            "created_at": str(now - 86400),
            "max_connections": "1",
            "allowed_output_formats": ["m3u8", "ts"],
        },
        "server_info": {
            "url": host,
            "port": str(port),
            "https_port": "",
            "server_protocol": "http",
            "rtmp_port": "",
            "timezone": "UTC",
            "timestamp_now": now,
            "time_now": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(now)),
        },
    }


def player_api(action, query, host, port, username, password, status="Active"):
    if not action:
        return account(host, port, username, password, status)
    if action == "get_live_categories":
        return CATEGORIES
    if action == "get_live_streams":
        return live_streams(query.get("category_id", [""])[0])
    if action in ("get_vod_categories", "get_vod_streams", "get_series_categories", "get_series"):
        return []
    if action == "get_short_epg":
        return {"epg_listings": []}
    if action == "get_series_info":
        return {"episodes": {}}
    return []


EMPTY_XMLTV = b'<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="fake_xtream"></tv>\n'


class Handler(BaseHTTPRequestHandler):
    server_version = "fake_xtream/1"
    # Keep-alive, as real panels do. Under HTTP/1.0 the server closes every connection, and OkHttp's
    # next request on a pooled connection fails with "unexpected end of stream".
    protocol_version = "HTTP/1.1"
    media = None
    stream_status = None
    account_status = "Active"

    def log_message(self, fmt, *args):
        pass

    def log_line(self, text):
        stamp = time.strftime("%H:%M:%S")
        print(f"{stamp} {text}", flush=True)

    def send_body(self, code, body, content_type):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urlparse(self.path)
        query = parse_qs(url.query, keep_blank_values=True)
        path = url.path.rstrip("/")
        username = query.get("username", [""])[0]
        password = query.get("password", [""])[0]

        if path.endswith("/player_api.php"):
            action = query.get("action", [""])[0]
            self.log_line(f"player_api username={username} password={password} action={action or '(account)'}")
            host, port = self.server.server_address[:2]
            payload = player_api(
                action, query, self.headers.get("Host", host).split(":")[0], port, username, password, self.account_status,
            )
            self.send_body(200, json.dumps(payload).encode(), "application/json")
            return

        if path.endswith("/xmltv.php"):
            self.log_line(f"xmltv username={username} password={password}")
            self.send_body(200, EMPTY_XMLTV, "application/xml")
            return

        parts = path.strip("/").split("/")
        if parts and parts[0] in ("live", "movie", "series", "timeshift") and len(parts) >= 4:
            self.log_line(f"stream {parts[0]} username={parts[1]} password={parts[2]} file={parts[-1]}")
            if self.stream_status is not None:
                self.send_body(self.stream_status, b"refused by --stream-status\n", "text/plain")
            elif self.media:
                self.send_body(200, self.media, "video/mp2t")
            else:
                self.send_body(404, b"no media\n", "text/plain")
            return

        self.log_line(f"unknown {self.command} {self.path}")
        self.send_body(404, b"not found\n", "text/plain")


def main():
    parser = argparse.ArgumentParser(description="Fake Xtream panel for the KevBox Live e2e run.")
    parser.add_argument("--port", type=int, default=8091)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--media", help="file served for every stream URL (an MPEG-TS clip)")
    parser.add_argument("--stream-status", type=int, metavar="N", help="answer every stream URL with HTTP status N")
    parser.add_argument("--account-status", default="Active", metavar="WORD",
                        help="status player_api.php reports for the account (default Active), e.g. Expired")
    args = parser.parse_args()

    if args.stream_status is not None and not 100 <= args.stream_status <= 999:
        parser.error("--stream-status must be 100-999")
    Handler.stream_status = args.stream_status
    Handler.account_status = args.account_status

    if args.media:
        with open(args.media, "rb") as handle:
            Handler.media = handle.read()

    server = ThreadingHTTPServer((args.bind, args.port), Handler)
    print(
        f"fake_xtream listening on {args.bind}:{args.port}"
        f" stream_status={args.stream_status or 'normal'} account_status={args.account_status}",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
