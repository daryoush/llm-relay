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
