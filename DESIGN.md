# LLM Relay — Design Document

Version: 1.2 · See README.md for installation; this document explains how the
system works, why it is built this way, and where to make changes. It is
written to serve as complete context for a new user *or* an LLM agent.
Code blocks in this document are indented, not fenced, so the file contains
no fence sequences that markdown tooling could misinterpret.

## 0. Current state (v1.3.0)

The extension is a CLIPBOARD PIPE: the user copies the text they want (the
site's copy button yields raw markdown with code fences intact), then
triggers a send (popup with preview / floating button / hotkey / context
menu). The clipboard content is relayed VERBATIM. Earlier DOM-based
extraction of "the last reply" was removed after field testing showed
rendered page text does not contain fence markers on several sites (see
§12.3 for the full history). Servers print and/or save the text; a
companion tool, bin/run-md, can later walk a saved file and execute
its bash blocks with per-block approval.

## 1. Purpose

LLM Relay carries text the user has copied in the browser to a small local
server that runs a configurable action on it.

Typical uses:

-   Persist a captured reply as instructions.md (default on both servers).
-   Display the text outside the browser (console, desktop window).
-   Pipe it into local scripts, a log file, or the clipboard via a shell
    command configured on the server.
-   (Clojure server, active mode; bin/run-md) review and selectively
    execute bash code blocks contained in the text, with per-block approval.

Design principles:

1.  Everything stays local: loopback HTTP, no cloud, no telemetry.
2.  The extension is a dumb pipe: it performs no filtering or
    transformation. The server owns all interpretation of the content.
3.  The browser cannot execute anything; only the local server can, and
    only after an explicit human approval per code block (Clojure active
    mode, run-md.py).
4.  Both servers speak the same wire protocol, so the extension is
    agnostic to which one is running.

## 2. Architecture

    user copies text (site copy button = raw markdown incl. fences)
                │
                ▼
    ┌──────────────────────────── Browser (Chromium) ───────────────────────┐
    │                                                                       │
    │  chat page (chatgpt.com / claude.ai / deepseek / kimi / …)            │
    │   └─ content.js      floating "Send clipboard" button · toasts        │
    │        │             chrome.runtime message passing                   │
    │        ▼                                                              │
    │  background.js (MV3 service worker)                                   │
    │   ├─ chrome.scripting.executeScript: read the CLIPBOARD in the tab    │
    │   └─ fetch POST            ← ALL network I/O happens here             │
    └──────────────────────────────┬────────────────────────────────────────┘
                                   │  POST /send {"text","capture","source","url","ts"}
                                   ▼
                        http://127.0.0.1:8765
             ┌─────────────────────────┴─────────────────────────┐
             │  server/llm_relay_server.py        (Python)       │  run ONE
             │  server-clj/src/llm_relay/server.clj (Clojure)    │  of the two
             └─────────────────────────┬─────────────────────────┘
                                       ▼
                              configured action
        Python : print-save (default) · show · save · popup · shell command
        Clojure: save (default) · echo · active (markdown render + gated
                 execution of fenced code blocks)

## 3. Repository layout

    llm-relay/
    ├── DESIGN.md                  this document
    ├── README.md                  quick start
    ├── Makefile                   run / test / run-clj / context / install
    ├── bin/
    │   ├── llm-relay              the Python server command (run anywhere)
    │   ├── run-md                 execute a .md file's bash blocks (asks first)
    │   ├── run-llm                alias of run-md (symlink)
    │   ├── relay-clj              Clojure server launcher (any directory)
    │   └── repo-context.sh        emits the repo as one paste-able bundle
    ├── extension/                 MV3 extension, loaded unpacked
    │   ├── manifest.json          permissions, matches, shortcut, UI wiring
    │   ├── background.js          clipboard capture + relay fetch, commands, menus
    │   ├── content.js             floating button + toasts (no extraction)
    │   ├── popup.html/.js         clipboard preview + send
    │   └── options.html/.js       serverUrl / token / showButton + test send
    └── server-clj/                Clojure implementation (Ring + Jetty)
        ├── deps.edn
        └── src/llm_relay/server.clj

    Runtime outputs (all gitignored): instructions.md and
    debug_payload.jsonl are written to the launching directory. The
    Clojure server keeps a local config.json per its home rules (§7.4);
    the Python server has no config file at all (§6).

## 4. Wire protocol

All endpoints are on 127.0.0.1 (default port 8765). Responses are JSON with
CORS headers allowing browser origins.

| Method | Path  | Body                          | Success response       | Python | Clojure |
|--------|-------|-------------------------------|------------------------|--------|---------|
| POST   | /send | JSON {"text": "...", ...} or raw text body | 200 {"ok": true} | yes | yes |
| POST   | /mode | {"mode": "save"/"echo"/"active"} (legacy "default" = alias of "save") | 200 {"ok": true, "mode": "..."} | no | yes |
| GET    | /mode | —                             | 200 {"mode": "..."}    | no     | yes     |
| OPTIONS| any   | —                             | 204 + CORS headers     | yes    | yes     |

Payload fields sent by the extension:

-   text — the clipboard content, verbatim (required)
-   capture — how it was obtained: "clipboard" or "test" (diagnostics only;
    servers ignore it)
-   source — hostname of the active tab when the send was triggered
-   url — page URL (servers ignore it, kept for diagnostics)
-   ts — ISO timestamp (same)

Errors: 400 empty text · 401 missing/bad X-Relay-Token · 404 unknown path ·
405 wrong method · 500 server-side exception ({"ok": false, "error": ...}).

Authentication: if the server config sets a non-empty token, every request
must carry header X-Relay-Token with the same value. Both servers and the
extension support this.

## 5. Browser extension

### 5.1 Manifest (MV3)

-   permissions: storage, contextMenus, scripting, clipboardRead
-   host_permissions: http://127.0.0.1/*, http://localhost/*, plus one
    pattern per supported chat site (Kimi uses wildcard patterns covering
    all subdomains and TLD variants). These exist for TWO things only:
    injecting the floating button (content_scripts) and reading the
    clipboard inside a tab via chrome.scripting.executeScript. The POPUP
    path needs no host permissions and works on any page.
-   commands: send-last-reply (suggested Alt+Shift+S; user-configurable at
    chrome://extensions/shortcuts) — sends the clipboard despite its
    historical id
-   action popup + options page

### 5.2 Component responsibilities

| File          | Role                                                              |
|---------------|-------------------------------------------------------------------|
| background.js | The only component that talks to the relay server (fetch). Reads the clipboard in the active tab (executeScript) for hotkey/context-menu/floating-button sends. Answers llr-relay / llr-send-clipboard / llr-test messages. |
| content.js    | Runs on supported chat pages. Renders the floating "Send clipboard" button and toasts. Contains NO extraction logic. |
| popup.js      | Reads the clipboard directly (extension page: permission auto-granted), shows a 140-char preview, sends on click. |
| options.js    | Persists serverUrl, token, showButton in chrome.storage.sync; sends a fixed test message. |

### 5.3 Internal message contract

| From → To             | type               | payload     | reply                                 |
|-----------------------|--------------------|-------------|---------------------------------------|
| content → background  | llr-send-clipboard | —           | {ok, text} or {ok:false, error}       |
| popup → background    | llr-relay          | {payload}   | {ok} or {ok:false, error}             |
| options → background  | llr-test           | —           | {ok} or {ok:false, error}             |
| background → content  | llr-status         | {ok, detail?/error?} | — (content shows a toast)    |

Note: listeners that respond asynchronously must return true from the
onMessage listener to keep the channel open.

### 5.4 Clipboard capture

Two paths, both reading the clipboard and nothing else:

1.  In-tab (floating button, hotkey, context menu): background injects
    readClipInPage via chrome.scripting.executeScript. It first uses the
    legacy document.execCommand("paste") into an offscreen textarea —
    silent under the clipboardRead permission — and falls back to
    navigator.clipboard.readText() (which may prompt once). If injection
    is impossible (no host permission, protected page), the error suggests
    using the popup instead.
2.  Popup: navigator.clipboard.readText() directly — extension pages have
    the permission auto-granted, so no prompt.

The captured text is sent verbatim. No trimming beyond JSON transport, no
filtering, no transformation (§12.5).

### 5.5 Trigger paths

1.  Toolbar popup → clipboard preview → "Send clipboard to server"
2.  Floating "⇪ Send clipboard" button on chat pages
3.  Keyboard shortcut (Alt+Shift+S default)
4.  Page context menu → "Send clipboard to relay server"
5.  Options page → "Send test message" (fixed text, skips the clipboard)

All converge on background.js relay().

### 5.6 Protected pages

chrome:// pages, the Web Store, and similar cannot be injected into. The
floating button does not appear there (no content script), and a hotkey
send reports an error toast suggesting the popup, which works anywhere.

## 6. Python server (bin/llm-relay)

Stdlib only (http.server). ThreadingHTTPServer → one thread per request.
There is NO config file: all settings are command-line arguments with
built-in defaults (the former config defaults).

    llm-relay [--host H] [--port P] [--token T] [--command CMD]
              [--save-path PATH] [--append] [--max-length N]
              [--no-debug-payload]

| flag               | default         | meaning                                    |
|--------------------|-----------------|--------------------------------------------|
| --host             | 127.0.0.1       | bind address                               |
| --port             | 8765            | listen port                                |
| --token            | ""              | require X-Relay-Token when non-empty       |
| --command          | print-save      | print-save / save / show / popup / shell   |
| --save-path        | instructions.md | target; relative → launch directory        |
| --append           | off             | append with a "---" separator instead      |
| --max-length       | 200000          | truncate payloads (0 disables)             |
| --no-debug-payload | off             | skip raw-body logging (see below)          |

HOME = the directory the server was LAUNCHED from: relative --save-path
resolves against it and debug_payload.jsonl is written there (same home
model as the Clojure server, §7.4). make run launches from the repo root,
so its outputs land in the repo root. Install anywhere:
make install symlinks bin/llm-relay, bin/run-md, bin/run-llm into
~/.local/bin.

Request handling (POST /send):

1.  Token check (X-Relay-Token) if --token is set.
2.  Body: JSON {"text","source"} preferred; a raw text body is accepted.
3.  Raw body optionally appended to HOME/debug_payload.jsonl (default ON;
    disable with --no-debug-payload) — the ground truth of what the client
    sent, for pipeline diagnosis.
4.  Trim; empty → 400. Truncate to max_length.
5.  Dispatch on --command:
    -   "print-save" (default): print a banner + the text, then save.
    -   "save": save only.  "show": print only.
    -   "popup": run a small tkinter window (via python -c) that displays
        the text received on stdin.
    -   anything else: a shell command. If it contains the marker {content},
        the marker is replaced with shlex.quote(text) (POSIX shells); the
        command runs with shell=True. Otherwise the text is piped to the
        command's stdin.

Save semantics: overwrites by default — the file holds the latest capture;
--append appends, separated by a "---" rule.

Execution model: spawn() starts a child without waiting; if text is piped,
a daemon thread writes stdin and closes it. A slow child can never block
the HTTP response or other requests.

Examples:

    llm-relay                                    # defaults: print + save
    llm-relay --command show                     # console only
    llm-relay --command "cat >> llm_log.txt"     # shell command (stdin)
    llm-relay --port 9000 --token s3cret

## 7. Clojure server (server-clj/)

Ring handler on Jetty (deps.edn: ring/ring-jetty-adapter, clojure/data.json).
Same /send protocol; additionally /mode.

### 7.1 Modes

-   save (default): the received text is written to the save target (§7.5)
    and the console shows a one-line confirmation.
-   echo: plain echo — banner, source, char count, raw text (the file is
    still saved).
-   active: renders the text as markdown with ANSI styling and runs
    command-for languages through the approval gate (§7.3). The file is
    still saved.

Mode is switched at runtime via POST /mode (save | echo | active; the
legacy value "default" is accepted as an alias of save) and persisted to
the runtime config. Default mode on first run: save.

### 7.2 Markdown pipeline (active mode)

1.  split-segments: the text is split into :text and :code segments by the
    fenced-code regex. The fence token (three backticks) is BUILT at
    runtime via (apply str (repeat 3 (char 96))) instead of written
    literally, so the source file contains no fence sequence that markdown
    tooling could misinterpret (lesson learned — see git history).
    The regex anchors fences to the BEGINNING OF A LINE only — fence-like
    sequences mid-line are treated as plain text (§12.4). bin/run-md
    implements the same policy for saved files.
2.  :text segments → fmt-text: headings bold; blockquotes italic/dim;
    bullets (- * +) → •; horizontal rules dimmed; inline code cyan; bold /
    italic / link syntax styled inline.
3.  :code segments → render-code: lang label + vertical-bar prefix, cyan
    body. All code is DISPLAYED first; nothing runs yet.

### 7.3 Execution gate (multimethod architecture)

Active mode is built on two open multimethods - extending it means adding
defmethods, never editing the core loop:

-   process-segment - dispatched once per segment. Dispatch value: :text
    for prose, [:code "lang"] for an exact-language override, [:code
    :default] for any other code block. Each method renders/prints as
    needed and RETURNS the possibly-updated policy (:ask | :all |
    :skip-all); the message loop is just (reduce ... :ask segments).
-   command-for - the main extension point. Dispatches on the fence
    language and returns the command VECTOR that runs a block, or nil
    (the :default) meaning display-only. Because [:code :default]
    consults command-for, ONE defmethod makes a language executable:

        (defmethod command-for "node" [_ _ b] ["node" "-e" (:text b)])

    Shipped: bash, sh, shell, zsh (via bash), python, python3.
-   For each executable block the server prints:
        ▶ Execute this block?  [y]es  [n]o (display only)  [a]ll remaining  [q]uit
    read from the server terminal's stdin.
-   Policy state machine: y/n apply to one block; a = :all (execute the
    remaining blocks without asking); q = :skip-all (display only from
    here on). EOF on stdin or unrecognized input = :no (fail safe).
-   An exact [:code "lang"] process-segment method overrides the standard
    prompt/run flow for that language (rare - e.g. auto-run trusted
    languages without asking).
-   run-command: ProcessBuilder with the command vector (no shell); child
    stdin closed immediately; stdout/stderr drained on futures; killed
    after exec-timeout-ms (default 60000); exit code, stdout, stderr are
    printed.
-   prompt-lock serializes concurrent /send requests around the
    interactive prompt; ANSI colors only on a real console with NO_COLOR
    unset.

### 7.4 State & persistence

-   Config is an atom loaded from config.json at STARTUP (differs from the
    Python server's per-request reload). Hand-editing the file requires a
    restart; changing mode via POST /mode persists immediately.
-   HOME = the directory the server process is LAUNCHED from. config.json
    is read/created there and relative :save-path values resolve against
    it. bin/relay-clj starts the server from any directory (it points the
    Clojure CLI at the project sources via -Sdeps, so no deps.edn is
    needed in the launch dir); overrides: LLM_RELAY_HOME and
    LLM_RELAY_CONFIG env vars, or -Dllm-relay.home / -Dllm-relay.config.
    make run-clj launches from the repo root, so its home is the repo
    root. An uberjar (clojure -X:build in server-clj/) removes the CLI
    dependency entirely: java -jar llm-relay.jar, same home rules.
-   Keys: host, port, token, mode, save-path, save-append,
    save-on-receive, exec-timeout-ms, max-length.

### 7.5 Saving

The DEFAULT action on every received message is to persist the text:

-   save-path (default "instructions.md"): relative paths resolve against
    HOME — the launch directory (§7.4); absolute paths are used as-is.
    The resolved path is printed at startup and on every save.
-   Overwrite semantics by default — the file holds the latest capture.
    save-append true appends instead, separating captures with a "---"
    rule.
-   save-on-receive false disables saving entirely (console-only modes).

## 8. Configuration reference

Python server (bin/llm-relay) — command-line only, no config file:

| flag               | default         | meaning                              |
|--------------------|-----------------|--------------------------------------|
| --host             | 127.0.0.1       | bind address                         |
| --port             | 8765            | listen port                          |
| --token            | ""              | require X-Relay-Token when non-empty |
| --command          | print-save      | print-save/save/show/popup/shell cmd |
| --save-path        | instructions.md | relative → launch directory (home)   |
| --append           | off             | append with a "---" separator        |
| --max-length       | 200000          | 0 disables truncation                |
| --no-debug-payload | off             | disable raw-body logging             |

server-clj/config.json (Clojure, local-only):

| key             | default   | meaning                                  |
|-----------------|-----------|------------------------------------------|
| host / port     | 127.0.0.1 / 8765 | bind address / port                |
| token           | ""        | shared secret                            |
| mode            | "save"    | "save" (default) / "echo" / "active"; legacy "default" aliased to "save" |
| save-path       | "instructions.md" | relative → launch directory (home) |
| save-append     | false     | append with a "---" separator instead    |
| save-on-receive | true      | set false to disable file writing        |
| exec-timeout-ms | 60000     | kill code blocks after this long         |
| max-length      | 200000    | payload truncation                       |

Extension (chrome.storage.sync, edited in the options page):

| key        | default                      | meaning                    |
|------------|------------------------------|----------------------------|
| serverUrl  | http://127.0.0.1:8765/send   | relay endpoint             |
| token      | ""                           | must match server token    |
| showButton | true                         | floating button on pages   |

## 9. Security model

-   Servers bind to 127.0.0.1 only. No remote exposure.
-   CORS is open (*) because the client is a browser extension/page; this
    means ANY page or app on the machine can POST to the port. When a shell
    command is configured, set a token (server config + extension options).
-   The extension never executes anything; it only relays clipboard text.
    It reads the clipboard ONLY on an explicit trigger (button, hotkey,
    menu, popup); there is no background polling.
-   Clojure active mode / run-md.py: human approval is the only execution
    gate. Approved scripts run locally with full user privileges — review
    before pressing y. Timeouts kill runaway blocks; EOF fails closed
    (no execution).
-   All config.json files (server/, server-clj/, and a root one created by
    the Clojure server's home rules) are gitignored because they can hold
    tokens; config.example.json is the committed, secret-free template.
-   {content} substitution uses shlex.quote (POSIX). On Windows prefer
    stdin-style commands.

## 10. Extending

Add a supported chat site (floating button + in-tab clipboard read):

1.  manifest.json: add match patterns to content_scripts.matches AND
    host_permissions (wildcards https://*.example.com/* cover all
    subdomains). The popup and its clipboard send work everywhere already.
2.  Reload the extension.

Change what happens to the text: edit the server config (Python: live;
Clojure: restart, except mode via /mode).

Add an internal message type: follow the table in §5.3; remember to return
true from onMessage listeners that respond asynchronously.

Add an executable code language (Clojure active mode): one defmethod in
server-clj/src/llm_relay/server.clj, then restart - e.g.

    (defmethod command-for "node" [_ _ b] ["node" "-e" (:text b)])

To customize a language beyond the standard prompt/run flow, add an exact
[:code "lang"] process-segment method (see section 7.3).

Change fence handling: keep the LINE-START anchoring consistent across
server-clj/src/llm_relay/server.clj (split-segments) and
bin/run-md — mid-line fence-like sequences must stay plain text.

## 11. Development & testing workflow

    make run          # Python server (bin/llm-relay; CLI-arg settings)
    make test         # curl POST a test message
    make run-clj      # Clojure server from repo root (home = repo root)
    ./bin/relay-clj   # same server from ANY directory (home = that dir)
    make install      # symlink llm-relay / run-md / run-llm into ~/.local/bin
    make context      # emit repo state + sources as one paste-able bundle
                      #   (pipe to pbcopy on macOS for LLM-grounded workflows)
    ./bin/run-md instructions.md
                      # walk a saved file: renders markdown, asks before
                      # executing each line-anchored bash block

Ports collide on 8765 — run one server at a time or change config.

Extension: chrome://extensions → Developer mode → Load unpacked → select
extension/. After editing extension files: reload the card (↻); content
scripts re-inject on the next tab load. After editing manifest.json:
reload AND refresh open chat tabs.

Mode control (Clojure):

    curl -s http://127.0.0.1:8765/mode
    curl -s -X POST http://127.0.0.1:8765/mode -d '{"mode":"active"}'

## 12. Design decisions & known limitations

1.  All extension network I/O lives in the background service worker.
    Extension-origin requests with host_permissions bypass the CORS and
    mixed-content restrictions an HTTPS chat page would hit posting to
    http://127.0.0.1.
2.  Two server implementations, one protocol. The extension does not know
    or care which is running. Ports collide on 8765 — run one at a time or
    change config.
3.  CLIPBOARD AS SOURCE OF TRUTH. The LLM's raw markdown lives in each
    site's private app state; rendering CONSUMES the fence syntax, so
    rendered DOM text on several sites contains no fence markers at all.
    History: DOM extraction (inner-text) → DOM-to-markdown reconstruction
    (rewrote text, rolled back) → automating the sites' copy buttons
    (focus/permission fragility) → the current model: the USER copies via
    the site's own copy button — which serializes the raw markdown — and
    the extension relays the clipboard verbatim. Boring, but the only
    stable public window into the same data the copy button uses.
4.  FENCE POLICY: fence detection is anchored to the BEGINNING OF A LINE
    in both the Clojure server (split-segments) and bin/run-md.
    Mid-line fence-like sequences are plain text and can never introduce
    an executable block. Fail-safe direction: a missed fence means
    display-only, never unintended execution.
5.  VERBATIM RELAY: whatever is on the clipboard is sent byte-for-byte.
    If the user's copy action yields rendered text without fences (e.g.
    manual selection copy), the relay preserves that too — the extension
    does not second-guess the user.
6.  Staleness is user-managed: the popup shows a 140-char clipboard
    preview before sending, so "what am I about to relay" is always
    visible. There is no streaming-capture problem because nothing is
    captured from the page.
7.  Firefox is not configured out of the box: it needs
    background.scripts instead of service_worker plus a gecko id in the
    manifest.
8.  Unpacked installs require Chrome's Developer mode toggle (an install-
    time policy only, no runtime effect). Publishing unlisted to the Web
    Store removes the toggle for other machines.
9.  Windows: the Clojure server and run-md.py shell out to bash (use WSL
    or Git Bash); Python {content} quoting is POSIX-only.
10. max_length guards both servers against runaway payloads; the extension
    has no client-side cap.
11. The two servers deliberately differ on config semantics: Python re-reads
    config per request (live editing); Clojure loads at startup (mode is
    the runtime-switchable dimension, persisted via /mode).
