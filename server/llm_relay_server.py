#!/usr/bin/env python3
"""
LLM Relay Server — receives text from the browser extension and runs a
configurable command on it. Stdlib only, Python 3.7+.

Run:  make run        (or: python3 server/llm_relay_server.py)

config.json (next to this file) is auto-created from config.example.json and
re-read on EVERY request, so you can change the command without restarting.

Built-in commands:
  "show"   -> print the text to this console (default)
  "popup"  -> open a desktop window with the text (tkinter)
Any other value is run as a shell command:
  - if it contains "{content}", the text is substituted there
    (safely shell-quoted on macOS/Linux; on Windows prefer stdin style)
  - otherwise the text is piped to the command's stdin
"""

import json
import os
import shlex
import subprocess
import sys
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(BASE_DIR, "config.json")

DEFAULT_CONFIG = {
    "host": "127.0.0.1",
    "port": 8765,
    "token": "",            # optional secret; requests must send X-Relay-Token
    "command": "save",      # "save" (default) | "show" | "popup" | any shell command
    "save_path": "instructions.md",  # relative paths resolve against the project root
    "save_append": False,   # true -> append with a "---" separator instead of overwriting
    "max_length": 200000,   # truncate huge payloads (None to disable)
}


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            cfg.update(json.load(f))
    except FileNotFoundError:
        save_config(DEFAULT_CONFIG)
    except Exception as exc:
        print(f"[config] could not read {CONFIG_FILE}: {exc} — using defaults")
    return cfg


def save_config(cfg):
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception as exc:
        print(f"[config] could not write {CONFIG_FILE}: {exc}")


# ------------------------------------------------------------------ commands

def cmd_show(text, meta):
    bar = "=" * 70
    print(f"\n{bar}\n📩 {meta['received_at']}  |  source: {meta['source']}"
          f"  |  {len(text)} chars\n{bar}\n{text}\n{bar}\n", flush=True)


def cmd_popup(text, meta):
    code = (
        "import sys, tkinter as tk\n"
        "t = sys.stdin.read()\n"
        "r = tk.Tk(); r.title('LLM Relay')\n"
        "box = tk.Text(r, wrap='word')\n"
        "box.insert('1.0', t)\n"
        "box.pack(fill='both', expand=True, padx=6, pady=6)\n"
        "tk.Button(r, text='Close', command=r.destroy).pack(pady=4)\n"
        "r.geometry('680x520')\n"
        "r.mainloop()\n"
    )
    spawn([sys.executable, "-c", code], text)


def instructions_path(cfg):
    """Resolve save_path: absolute paths as-is; relative paths against the
    project root (the parent directory of server/)."""
    p = os.path.expanduser(str(cfg.get("save_path") or "instructions.md"))
    if not os.path.isabs(p):
        p = os.path.join(os.path.dirname(BASE_DIR), p)
    return os.path.abspath(p)


def cmd_save(text, meta, cfg):
    path = instructions_path(cfg)
    append = bool(cfg.get("save_append"))
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    sep = ("\n\n---\n\n"
           if append and os.path.exists(path) and os.path.getsize(path) > 0 else "")
    with open(path, "a" if append else "w", encoding="utf-8") as f:
        f.write(sep + text.rstrip() + "\n")
    print(f"💾 saved {len(text)} chars → {path}"
          + ("  (appended)" if append else ""), flush=True)


def cmd_shell(command, text):
    if "{content}" in command:
        spawn(command.replace("{content}", shlex.quote(text)), shell=True)
    else:
        spawn(command, text, shell=True)


def spawn(cmd, text=None, shell=False):
    """Start cmd without waiting for it; feed text via stdin in a background
    thread so slow/uncooperative children can never block the server."""
    p = subprocess.Popen(cmd, shell=shell,
                         stdin=subprocess.PIPE if text is not None else None)
    if text is not None:
        def feed():
            try:
                p.stdin.write(text.encode("utf-8"))
                p.stdin.close()
            except Exception:
                pass
        threading.Thread(target=feed, daemon=True).start()


def run_command(cfg, text, meta):
    command = cfg.get("command") or "save"
    if command == "save":
        cmd_save(text, meta, cfg)
    elif command == "show":
        cmd_show(text, meta)
    elif command == "popup":
        cmd_popup(text, meta)
    else:
        cmd_shell(command, text)


# -------------------------------------------------------------------- server

class RelayHandler(BaseHTTPRequestHandler):

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Relay-Token")

    def _reply(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_POST(self):
        cfg = load_config()  # live config: re-read on every request

        if self.path.split("?")[0].rstrip("/") not in ("", "/send"):
            self._reply(404, {"ok": False, "error": f"unknown path {self.path}"})
            return

        if cfg.get("token") and self.headers.get("X-Relay-Token") != cfg["token"]:
            self._reply(401, {"ok": False, "error": "missing or bad X-Relay-Token"})
            return

        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        raw = self.rfile.read(length) if length > 0 else b""

        text = raw.decode("utf-8", errors="replace")
        source = "unknown"
        try:
            data = json.loads(text)
            if isinstance(data, dict):
                text = str(data.get("text", ""))
                source = str(data.get("source", "unknown"))
        except (ValueError, TypeError):
            pass  # raw text body is also accepted

        text = text.strip()
        if not text:
            self._reply(400, {"ok": False, "error": "empty text"})
            return

        limit = cfg.get("max_length")
        if limit and len(text) > limit:
            text = text[:limit]

        meta = {"source": source,
                "received_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S")}
        try:
            run_command(cfg, text, meta)
            self._reply(200, {"ok": True})
        except Exception as exc:
            self._reply(500, {"ok": False, "error": repr(exc)})

    def log_message(self, fmt, *args):
        pass  # silence default request logging


def main():
    cfg = load_config()
    host, port = cfg.get("host", "127.0.0.1"), int(cfg.get("port", 8765))
    httpd = ThreadingHTTPServer((host, port), RelayHandler)
    print("─" * 50)
    print(f" LLM Relay Server  ->  http://{host}:{port}/send")
    print(f" config : {CONFIG_FILE}  (re-read per request)")
    print(f" command: {cfg.get('command')!r}")
    if (cfg.get("command") or "save") == "save":
        print(f" saves to: {instructions_path(cfg)}"
              + ("  (appending)" if cfg.get("save_append") else ""))
    print(f" token  : {'enabled' if cfg.get('token') else 'disabled'}")
    print("─" * 50 + "\n")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
