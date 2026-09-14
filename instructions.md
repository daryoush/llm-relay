Yes — here's the whole thing as one repo. Run the blocks below **top to bottom** in your terminal (from wherever you keep projects) and you'll end up with a ready-to-commit git project.

Final layout:

```
llm-relay/
├── .gitignore
├── Makefile
├── README.md
├── extension/          ← load this folder via chrome://extensions
│   ├── manifest.json
│   ├── background.js
│   ├── content.js
│   ├── popup.html
│   ├── popup.js
│   ├── options.html
│   └── options.js
└── server/
    ├── llm_relay_server.py
    ├── config.example.json
    └── config.json     ← created at runtime, gitignored (may hold your token)
```

## 1 · Scaffold

```bash
mkdir -p llm-relay/extension llm-relay/server
cd llm-relay

cat > .gitignore << 'EOF'
# local config may contain the shared token — never commit it
server/config.json

__pycache__/
*.pyc
.DS_Store
*.crswap
EOF
```

## 2 · Server

```bash
cat > server/llm_relay_server.py << 'PYEOF'
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
    "token": "",           # optional secret; requests must send X-Relay-Token
    "command": "show",     # "show" | "popup" | any shell command
    "max_length": 200000,  # truncate huge payloads (None to disable)
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
    command = cfg.get("command") or "show"
    if command == "show":
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
    print(f" token  : {'enabled' if cfg.get('token') else 'disabled'}")
    print("─" * 50 + "\n")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
PYEOF

cat > server/config.example.json << 'EOF'
{
  "host": "127.0.0.1",
  "port": 8765,
  "token": "",
  "command": "show",
  "max_length": 200000
}
EOF

chmod +x server/llm_relay_server.py
```

## 3 · Extension — manifest & background

```bash
cat > extension/manifest.json << 'EOF'
{
  "manifest_version": 3,
  "name": "LLM Relay — send last reply",
  "version": "1.0.0",
  "description": "Grabs the newest assistant reply on ChatGPT, Claude, Gemini, DeepSeek, Kimi, Qwen and z.ai, and POSTs it to your local relay server.",

  "permissions": ["storage", "contextMenus"],
  "host_permissions": [
    "http://127.0.0.1/*",
    "http://localhost/*",
    "https://chatgpt.com/*",
    "https://chat.openai.com/*",
    "https://claude.ai/*",
    "https://gemini.google.com/*",
    "https://chat.deepseek.com/*",
    "https://kimi.moonshot.cn/*",
    "https://kimi.com/*",
    "https://www.kimi.com/*",
    "https://chat.qwen.ai/*",
    "https://tongyi.aliyun.com/*",
    "https://www.tongyi.com/*",
    "https://chat.z.ai/*",
    "https://www.z.ai/*"
  ],

  "background": { "service_worker": "background.js" },

  "action": {
    "default_popup": "popup.html",
    "default_title": "Send last LLM reply"
  },

  "options_ui": { "page": "options.html", "open_in_tab": true },

  "commands": {
    "send-last-reply": {
      "suggested_key": { "default": "Alt+Shift+S" },
      "description": "Send the last LLM reply to the relay server"
    }
  },

  "content_scripts": [
    {
      "matches": [
        "https://chatgpt.com/*",
        "https://chat.openai.com/*",
        "https://claude.ai/*",
        "https://gemini.google.com/*",
        "https://chat.deepseek.com/*",
        "https://kimi.moonshot.cn/*",
        "https://kimi.com/*",
        "https://www.kimi.com/*",
        "https://chat.qwen.ai/*",
        "https://tongyi.aliyun.com/*",
        "https://www.tongyi.com/*",
        "https://chat.z.ai/*",
        "https://www.z.ai/*"
      ],
      "js": ["content.js"],
      "run_at": "document_idle"
    }
  ]
}
EOF

cat > extension/background.js << 'EOF'
"use strict";

const DEFAULTS = { serverUrl: "http://127.0.0.1:8765/send", token: "" };

const cfg = async () => ({ ...DEFAULTS, ...(await chrome.storage.sync.get(DEFAULTS)) });

async function relay(payload) {
  const c = await cfg();
  const headers = { "Content-Type": "application/json" };
  if (c.token) headers["X-Relay-Token"] = c.token;
  let res;
  try {
    res = await fetch(c.serverUrl, { method: "POST", headers, body: JSON.stringify(payload) });
  } catch {
    throw new Error("can't reach server — is it running?");
  }
  if (!res.ok) throw new Error(`server answered ${res.status}`);
}

async function extractAndSend(tabId) {
  let resp;
  try {
    resp = await chrome.tabs.sendMessage(tabId, { type: "llr-extract" });
  } catch {
    return { ok: false, error: "no content script here (unsupported site?)" };
  }
  if (!resp?.ok) return { ok: false, error: resp?.error || "no reply found" };
  try {
    await relay(resp.payload);
  } catch (e) {
    return { ok: false, error: e.message };
  }
  return { ok: true, chars: resp.chars };
}

// Messages from content script (floating button) and options page (test)
chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg?.type === "llr-relay") {
    relay(msg.payload)
      .then(() => sendResponse({ ok: true }))
      .catch(e => sendResponse({ ok: false, error: e.message }));
    return true; // async response
  }
  if (msg?.type === "llr-test") {
    relay({
      text: "Test message from LLM Relay extension 🎉",
      source: "extension-test", url: "n/a", ts: new Date().toISOString(),
    })
      .then(() => sendResponse({ ok: true }))
      .catch(e => sendResponse({ ok: false, error: e.message }));
    return true;
  }
});

// Keyboard shortcut
chrome.commands.onCommand.addListener(async (command) => {
  if (command !== "send-last-reply") return;
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id) return;
  const r = await extractAndSend(tab.id);
  chrome.tabs.sendMessage(tab.id, { type: "llr-status", ...r }).catch(() => {});
});

// Context menu
chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: "llr-send",
    title: "Send last LLM reply to server",
    contexts: ["page"],
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId !== "llr-send" || !tab?.id) return;
  const r = await extractAndSend(tab.id);
  chrome.tabs.sendMessage(tab.id, { type: "llr-status", ...r }).catch(() => {});
});
EOF
```

## 4 · Extension — content script

```bash
cat > extension/content.js << 'EOF'
"use strict";

const WRAP_ID = "llr-root";

// ── Per-site selectors. First working selector wins; last DOM match = newest reply.
//    Chat UIs change their DOM often — if a site breaks, inspect the last reply
//    in DevTools and add/fix a selector here.
const SITE_RULES = [
  {
    match: h => /(^|\.)chatgpt\.com$/.test(h) || h === "chat.openai.com",
    selectors: ['[data-message-author-role="assistant"]'],
  },
  {
    match: h => h === "claude.ai",
    selectors: ['[data-testid="assistant-message"]', ".font-claude-message"],
  },
  {
    match: h => h === "gemini.google.com",
    selectors: ["model-response .markdown", "model-response message-content", "model-response", "response-container"],
  },
  {
    match: h => h === "chat.deepseek.com",
    selectors: ["div.ds-markdown", '[class*="ds-markdown"]', ".markdown-body"],
  },
  {
    match: h => /(^|\.)kimi\.com$/.test(h) || h === "kimi.moonshot.cn",
    selectors: ['[role="assistant"]', '[class*="assistant" i]', '[class*="segment" i]'],
  },
  {
    match: h => h === "chat.qwen.ai" || /(^|\.)(tongyi\.aliyun|tongyi)\.com$/.test(h),
    selectors: ['[id^="response-content-container"]', ".tongyi-markdown", '[class*="answer" i]'],
  },
  {
    match: h => /(^|\.)z\.ai$/.test(h),
    selectors: ['[class*="assistant" i]', '[class*="markdown" i]', ".prose"],
  },
];

const GENERIC = [
  '[data-message-author-role="assistant"]',
  '[role="assistant"]',
  "article",
  '[class*="assistant" i]',
  '[class*="markdown" i]',
  ".prose",
];

function usable(el) {
  if (el.closest(`#${WRAP_ID}`)) return false;                                   // our own UI
  if (el.closest('[contenteditable="true"], textarea, input')) return false;     // input box
  return !!(el.offsetParent || el.getClientRects().length);                      // visible
}

function pickLast(selector) {
  let nodes;
  try { nodes = document.querySelectorAll(selector); } catch { return null; }
  const arr = Array.from(nodes).filter(usable);
  if (!arr.length) return null;
  // keep outermost containers so innerText covers the whole message
  const tops = arr.filter(el => !arr.some(o => o !== el && o.contains(el)));
  return tops[tops.length - 1] || null;
}

// innerText of a detached clone (buttons/SVGs stripped, formatting preserved)
function elText(el) {
  const c = el.cloneNode(true);
  c.querySelectorAll("button, svg, [aria-hidden='true']").forEach(n => n.remove());
  c.style.cssText = "position:absolute;left:-99999px;top:0;width:800px;";
  document.body.appendChild(c);
  const t = (c.innerText || "").trim();
  c.remove();
  return t;
}

function extract() {
  const rule = SITE_RULES.find(r => r.match(location.hostname));
  for (const sel of [...(rule?.selectors || []), ...GENERIC]) {
    const el = pickLast(sel);
    const text = el && elText(el);
    if (text) return { text, via: sel };
  }
  return null;
}

const buildPayload = (text) => ({
  text,
  source: location.hostname,
  url: location.href,
  ts: new Date().toISOString(),
});

// ── messages from background (popup / hotkey / context menu)
chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg?.type === "llr-extract") {
    const found = extract();
    if (!found) { sendResponse({ ok: false, error: "no reply found on this page" }); return; }
    sendResponse({ ok: true, payload: buildPayload(found.text), chars: found.text.length });
    return;
  }
  if (msg?.type === "llr-status") {
    toast(msg.ok ? `✅ Sent ${msg.chars} chars` : `⚠️ ${msg.error}`);
  }
});

async function sendFromPage() {
  const found = extract();
  if (!found) { toast("❌ No reply found on this page"); return; }
  const res = await chrome.runtime
    .sendMessage({ type: "llr-relay", payload: buildPayload(found.text) })
    .catch(e => ({ ok: false, error: e.message }));
  toast(res?.ok ? `✅ Sent (${found.text.length} chars)` : `⚠️ ${res?.error || "failed"}`);
}

// ── optional floating button
let wrap = null;
async function syncButton() {
  const { showButton = true } = await chrome.storage.sync.get({ showButton: true });
  if (showButton && !wrap) {
    wrap = document.createElement("div");
    wrap.id = WRAP_ID;
    const btn = document.createElement("div");
    btn.textContent = "⇪ Send last reply";
    Object.assign(btn.style, {
      position: "fixed", right: "16px", bottom: "16px", zIndex: 2147483647,
      padding: "8px 14px", borderRadius: "20px", cursor: "pointer",
      background: "#111827", color: "#fff", font: "13px system-ui, sans-serif",
      boxShadow: "0 2px 8px rgba(0,0,0,.35)", opacity: "0.85", userSelect: "none",
    });
    btn.addEventListener("click", sendFromPage);
    wrap.appendChild(btn);
    document.body.appendChild(wrap);
  } else if (!showButton && wrap) {
    wrap.remove();
    wrap = null;
  }
}
chrome.storage.onChanged.addListener(syncButton);
syncButton();

// ── toast
let toastTimer;
function toast(msg) {
  let el = document.getElementById(`${WRAP_ID}-toast`);
  if (!el) {
    el = document.createElement("div");
    el.id = `${WRAP_ID}-toast`;
    Object.assign(el.style, {
      position: "fixed", right: "16px", bottom: "56px", zIndex: 2147483647,
      padding: "8px 12px", borderRadius: "8px", background: "#111827",
      color: "#fff", font: "13px system-ui, sans-serif", display: "none",
    });
    document.body.appendChild(el);
  }
  el.textContent = msg;
  el.style.display = "block";
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (el.style.display = "none"), 2500);
}
EOF
```

## 5 · Extension — popup & options

```bash
cat > extension/popup.html << 'EOF'
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    body { font: 13px system-ui, sans-serif; width: 240px; margin: 0; padding: 12px; }
    button { width: 100%; padding: 8px; border: 0; border-radius: 8px;
             background: #111827; color: #fff; cursor: pointer; }
    button:hover { background: #1f2937; }
    #status { margin-top: 8px; min-height: 16px; }
    .url { color: #6b7280; font-size: 11px; margin-top: 6px; word-break: break-all; }
    a { display: inline-block; margin-top: 10px; color: #2563eb; text-decoration: none; font-size: 12px; }
  </style>
</head>
<body>
  <button id="send">⇪ Send last LLM reply</button>
  <div id="status"></div>
  <div class="url" id="srv"></div>
  <a href="#" id="opts">Settings…</a>
  <script src="popup.js"></script>
</body>
</html>
EOF

cat > extension/popup.js << 'EOF'
"use strict";

const status = document.getElementById("status");
const srv = document.getElementById("srv");

chrome.storage.sync.get({ serverUrl: "http://127.0.0.1:8765/send" })
  .then(c => (srv.textContent = "→ " + c.serverUrl));

document.getElementById("opts").onclick = (e) => {
  e.preventDefault();
  chrome.runtime.openOptionsPage();
};

document.getElementById("send").onclick = async () => {
  status.textContent = "…";
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id) { status.textContent = "⚠️ no active tab"; return; }

  let resp;
  try {
    resp = await chrome.tabs.sendMessage(tab.id, { type: "llr-extract" });
  } catch {
    status.textContent = "⚠️ no content script on this page";
    return;
  }
  if (!resp?.ok) { status.textContent = "⚠️ " + (resp?.error || "no reply"); return; }

  const r = await chrome.runtime.sendMessage({ type: "llr-relay", payload: resp.payload });
  status.textContent = r?.ok ? `✅ sent ${resp.chars} chars` : "⚠️ " + (r?.error || "failed");
};
EOF

cat > extension/options.html << 'EOF'
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    body { font: 14px system-ui, sans-serif; max-width: 560px; margin: 32px auto; color: #111827; }
    label { display: block; margin: 14px 0 4px; font-weight: 600; }
    input[type=text] { width: 100%; padding: 8px; box-sizing: border-box;
                       border: 1px solid #d1d5db; border-radius: 8px; }
    .row { margin-top: 10px; }
    button { padding: 8px 14px; border: 0; border-radius: 8px;
             background: #111827; color: #fff; cursor: pointer; margin-right: 8px; }
    #status { margin-top: 10px; min-height: 20px; }
    code { background: #f3f4f6; padding: 1px 5px; border-radius: 4px; }
  </style>
</head>
<body>
  <h2>LLM Relay — settings</h2>

  <label for="serverUrl">Server URL</label>
  <input type="text" id="serverUrl" placeholder="http://127.0.0.1:8765/send">

  <label for="token">Shared token (must match server config.json, optional)</label>
  <input type="text" id="token">

  <div class="row">
    <label><input type="checkbox" id="showButton"> Show floating button on chat pages</label>
  </div>

  <div class="row">
    <button id="save">Save</button>
    <button id="test">Send test message</button>
  </div>
  <div id="status"></div>

  <p>Change the keyboard shortcut at <code>chrome://extensions/shortcuts</code>.
     What happens to the text after it reaches the server is configured in
     <code>server/config.json</code> (default command: print it).</p>

  <script src="options.js"></script>
</body>
</html>
EOF

cat > extension/options.js << 'EOF'
"use strict";

const DEFAULTS = { serverUrl: "http://127.0.0.1:8765/send", token: "", showButton: true };
const $ = (id) => document.getElementById(id);

chrome.storage.sync.get(DEFAULTS).then((c) => {
  $("serverUrl").value = c.serverUrl;
  $("token").value = c.token;
  $("showButton").checked = c.showButton;
});

$("save").onclick = async () => {
  await chrome.storage.sync.set({
    serverUrl: $("serverUrl").value.trim() || DEFAULTS.serverUrl,
    token: $("token").value.trim(),
    showButton: $("showButton").checked,
  });
  $("status").textContent = "✅ saved";
  setTimeout(() => ($("status").textContent = ""), 2000);
};

$("test").onclick = async () => {
  const r = await chrome.runtime.sendMessage({ type: "llr-test" });
  $("status").textContent = r?.ok
    ? "✅ server answered — check its console"
    : "⚠️ " + (r?.error || "failed");
};
EOF
```

## 6 · Makefile & README

⚠️ Makefile recipes need **real tab characters** — the heredoc below contains them; paste the block as-is.

```bash
cat > Makefile << 'EOF'
.PHONY: run config test

config:
	@test -f server/config.json || cp server/config.example.json server/config.json

run: config
	python3 server/llm_relay_server.py

test:
	@curl -s -X POST http://127.0.0.1:8765/send \
		-H "Content-Type: application/json" \
		-d '{"text":"hello from make test"}' && echo
EOF

cat > README.md << 'EOF'
# LLM Relay

Grab the newest assistant reply from popular LLM chat sites and pipe it to a
local Python server that runs a configurable command on it.

    Browser extension  ──POST──>  local server  ──>  your command

## Layout

    llm-relay/
    ├── extension/              Chrome/Edge (MV3) extension — load unpacked
    │   ├── manifest.json
    │   ├── background.js       all network I/O lives here
    │   ├── content.js          extraction + per-site selectors + UI
    │   └── popup.* / options.*
    └── server/
        ├── llm_relay_server.py Python 3.7+, stdlib only
        ├── config.example.json committed template
        └── config.json         created at runtime (gitignored — may hold token)

## Supported sites

ChatGPT · Claude · Gemini · DeepSeek · Kimi · Qwen (Tongyi) · z.ai

Chat frontends change often; extraction rules live in `extension/content.js`
(`SITE_RULES`) with a generic fallback. If a site breaks, inspect the last
assistant message in DevTools and add a selector.

## Quick start

    make run          # starts the server, creates config.json on first run
    make test         # POSTs a test message with curl

Then in Chrome/Edge: chrome://extensions → Developer mode → Load unpacked →
select the `extension/` folder.

Send the last reply via: toolbar popup · Alt+Shift+S (configurable at
chrome://extensions/shortcuts) · right-click menu · floating button.

## Configuration

Server command is set in `server/config.json` and re-read on every request
(no restart needed):

    "show"                            print to the server console (default)
    "popup"                           desktop window (tkinter)
    "pbcopy" / "wl-copy" / "clip"     clipboard (macOS / Wayland / Windows)
    "cat >> llm_log.txt"              append to file
    "python my_script.py {content}"   pass text as an argument

Extension settings (server URL, token, floating button) live in the
extension's options page. If you set a `token` in the server config, put the
same value in the extension options — server/config.json is gitignored so
tokens never get committed.

## Firefox

Change the manifest background section to scripts and add gecko id
(see git history / issue tracker for the snippet), or just use a
Chromium-based browser.
EOF
```

## 7 · Git init & push to GitHub

```bash
git init
git add .
git status                    # sanity check: config.json must NOT appear
git commit -m "Initial commit: LLM relay server + browser extension"
git branch -M main

# Option A — GitHub CLI creates the repo and pushes in one go:
gh repo create llm-relay --public --source=. --push

# Option B — create an empty repo on github.com first, then:
git remote add origin git@github.com:YOUR_USERNAME/llm-relay.git
git push -u origin main
```

## Verify the whole pipeline

```bash
make run                 # terminal 1 — server starts, prints its config
make test                # terminal 2 — "hello from make test" appears in terminal 1
```

Then load `extension/` in the browser, open any supported chat, hit **Alt+Shift+S** — the last reply shows up in the server console.

Notes:

- **`server/config.json` is gitignored** by design (it can hold your token); the committed `config.example.json` is the template, and `make run` copies it automatically on first start.
- To update later: edit files → `git add -A && git commit -m "..." && git push`.
- If you want a license before publishing, run `gh license add mit` (or pick one in the GitHub UI) — everything else is ready as-is.
