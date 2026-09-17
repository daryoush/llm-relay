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

Clipboard workflow (v1.3.0+): copy the reply you want (the site's copy
button preserves raw markdown incl. code fences), then send via toolbar
popup (with preview) · floating "Send clipboard" button · Alt+Shift+S ·
right-click menu. The clipboard content is relayed verbatim — the
extension performs no extraction or filtering; the server owns all
interpretation.

## Configuration

Server command is set in `server/config.json` and re-read on every request
(no restart needed):

    "save"                            write instructions.md (default)
    "show"                            print to the server console
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

## How to install the plugin
The extension isn't from a store — you load it directly from the folder (this is called "load unpacked" and works in Chrome, Edge, Brave, and other Chromium browsers).

Install in Chrome / Edge / Brave
Open the extensions page — type this in your address bar:
Chrome: chrome://extensions
Edge: edge://extensions
Enable Developer mode — toggle switch, top-right corner.
Click Load unpacked (top-left).
In the file picker, select the extension/ folder of the project — the folder containing manifest.json, not the repo root:
text

llm-relay/extension/     ← select THIS
Done — "LLM Relay — send last reply" now appears in the list.
Tip: click the puzzle-piece icon in the toolbar and pin 🔒 LLM Relay so its icon is always visible.