# LLM Relay

Copy an LLM chat reply (the site's copy button preserves raw markdown,
code fences intact), then relay it — verbatim — to a local server that
saves and/or acts on it.

    Browser clipboard  ──POST──>  local server  ──>  instructions.md / your command

## Layout

    llm-relay/
    ├── extension/              Chrome/Edge (MV3) extension — load unpacked
    │   ├── manifest.json
    │   ├── background.js       clipboard capture; ALL network I/O lives here
    │   ├── content.js          floating "Send clipboard" button + toasts
    │   └── popup.* / options.*
    ├── server/                 Python 3.7+, stdlib only
    │   ├── llm_relay_server.py
    │   ├── config.example.json committed template
    │   └── config.json         created at runtime (gitignored — may hold token)
    ├── server-clj/             Clojure implementation (Ring + Jetty)
    ├── bin/relay-clj           run the Clojure server from any directory
    ├── tools/run-md.py         execute a saved .md file's bash blocks (asks first)
    └── DESIGN.md               full architecture & design decisions

## Sites

The toolbar POPUP works on any page (it reads the clipboard itself, no
page access needed). The floating "⇪ Send clipboard" button and hotkey
in-tab path are available on the listed chat sites:

ChatGPT · Claude · Gemini · DeepSeek · Kimi · Qwen (Tongyi) · z.ai

## Quick start

    make run          # starts the Python server, creates config.json on first run
    make test         # POSTs a test message with curl
    make run-clj      # alternative: the Clojure server (same port)

Then in Chrome/Edge: chrome://extensions → Developer mode → Load unpacked →
select the `extension/` folder.

## Clipboard workflow (v1.3.0+)

1.  Copy the reply you want — the site's own copy button yields the raw
    markdown including code fences.
2.  Send it: toolbar popup (shows a preview first) · floating "⇪ Send
    clipboard" button · Alt+Shift+S · right-click menu.
3.  The server prints the text and saves it to instructions.md (default).

The clipboard content is relayed verbatim — the extension performs no
extraction or filtering; the server owns all interpretation.

## Configuration

Server command is set in `server/config.json` and re-read on every request
(no restart needed):

    "print-save"                      print to console AND save (default)
    "save"                            save only
    "show"                            print to the server console
    "popup"                           desktop window (tkinter)
    "pbcopy" / "wl-copy" / "clip"     clipboard (macOS / Wayland / Windows)
    "cat >> llm_log.txt"              append to file
    "python my_script.py {content}"   pass text as an argument

Other keys: save_path (default "instructions.md"; relative paths resolve
against the project root), save_append (append with a --- separator),
debug_payload (log raw request bodies to server/debug_payload.jsonl).

The Clojure server has its own local config (modes: save / echo / active;
active renders markdown and asks before executing each fenced bash block —
see DESIGN.md §7). Switch modes live:

    curl -s -X POST http://127.0.0.1:8765/mode -d '{"mode":"active"}'

To execute the bash blocks of a saved file later:

    python3 tools/run-md.py instructions.md    # asks per block

Extension settings (server URL, token, floating button) live in the
extension's options page. If you set a `token` in a server config, put the
same value in the extension options — config.json files are gitignored so
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

    llm-relay/extension/     ← select THIS

Done — "LLM Relay — clipboard to relay" now appears in the list.
Tip: click the puzzle-piece icon in the toolbar and pin LLM Relay so its icon is always visible.
