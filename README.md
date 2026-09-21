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
    ├── bin/
    │   ├── llm-relay           the Python server command (run anywhere)
    │   ├── run-md              execute a .md file's bash blocks (asks first)
    │   ├── run-llm             alias of run-md (symlink)
    │   ├── relay-clj           Clojure server launcher (any directory)
    │   └── repo-context.sh     emit the repo as one paste-able bundle
    ├── server-clj/             Clojure implementation (Ring + Jetty)
    └── DESIGN.md               full architecture & design decisions

## Sites

The toolbar POPUP works on any page (it reads the clipboard itself, no
page access needed). The floating "⇪ Send clipboard" button and hotkey
in-tab path are available on the listed chat sites:

ChatGPT · Claude · Gemini · DeepSeek · Kimi · Qwen (Tongyi) · z.ai

## Quick start

    make run          # starts the Python server (bin/llm-relay; CLI-arg settings)
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

## Configuration (Python server)

There is no config file — every setting is a command-line argument with a
built-in default:

    llm-relay [--host 127.0.0.1] [--port 8765] [--token T]
              [--command print-save] [--save-path instructions.md]
              [--append] [--max-length 200000] [--no-debug-payload]

    --command print-save                print to console AND save (default)
    --command show                      print to the server console
    --command save                      save only
    --command popup                     desktop window (tkinter)
    --command "cat >> llm_log.txt"      any shell command (text on stdin, or
                                        {content} substituted, shell-quoted)
    --command pbcopy                    clipboard (macOS; wl-copy / clip elsewhere)

The LAUNCH DIRECTORY is the instance home: relative --save-path resolves
against it and debug_payload.jsonl (raw request log) is written there.
make run launches from the repo root.

Install the commands anywhere on PATH:

    make install       # symlinks bin/llm-relay, bin/run-md, bin/run-llm
                       # into ~/.local/bin (keep that on your PATH)

Then, from any directory:

    llm-relay          # the Python server
    run-md FILE        # execute a saved .md file's bash blocks (asks first)
    run-llm FILE       # alias of run-md

The Clojure server (make run-clj / ./bin/relay-clj) keeps its own local
config.json with modes save / echo / active — see DESIGN.md §7. If you
set --token, put the same value in the extension options.

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
