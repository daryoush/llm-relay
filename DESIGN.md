# LLM Relay — Design Document

Version: 1.1 · See README.md for installation; this document explains how the
system works, why it is built this way, and where to make changes. It is
written to serve as complete context for a new user *or* an LLM agent.

## 1. Purpose

LLM Relay captures LLM chat replies in the browser and hands them to a small
local server that runs a configurable action on the text.

Typical uses:

-   Persist the latest reply as instructions.md (the default action on
    both servers).
-   Display the reply outside the browser (console, desktop window).
-   Pipe LLM output into local scripts, a log file, or the clipboard.
-   (Clojure server, active mode) review and selectively execute bash code
    blocks contained in the reply, with per-block approval.

Design principles:

1.  Everything stays local: loopback HTTP, no cloud, no telemetry.
2.  The browser cannot execute anything; only the local server can, and the
    Clojure server only after an explicit human approval per block.
3.  Both servers speak the same wire protocol, so the extension is agnostic
    to which one is running.

## 2. Architecture

    ┌──────────────────────────── Browser (Chromium) ────────────────────────────┐
    │                                                                            │
    │  chat page (chatgpt.com / claude.ai / gemini / deepseek / kimi / …)        │
    │   └─ content.js      extraction · per-message ⇪ buttons · floating button  │
    │        │             chrome.runtime message passing                        │
    │        ▼                                                                   │
    │  background.js (MV3 service worker)                                        │
    │   └─ fetch POST            ← ALL network I/O happens here                  │
    └──────────────────────────────┬─────────────────────────────────────────────┘
                                   │  POST /send  {"text","source","url","ts"}
                                   ▼
                        http://127.0.0.1:8765
             ┌─────────────────────────┴─────────────────────────┐
             │  server/llm_relay_server.py        (Python)       │  run ONE
             │  server-clj/src/llm_relay/server.clj (Clojure)    │  of the two
             └─────────────────────────┬─────────────────────────┘
                                       ▼
                               configured command
        Python: show (echo) · popup window · any shell command
        Clojure: default = echo · active = markdown render + approved bash exec

## 3. Repository layout

    llm-relay/
    ├── DESIGN.md                  this document
    ├── README.md                  quick start
    ├── Makefile                   run / test / run-clj
    ├── extension/                 MV3 extension, loaded unpacked
    │   ├── manifest.json          permissions, matches, shortcut, UI wiring
    │   ├── background.js          service worker: relay fetch, commands, menus
    │   ├── content.js             extraction rules, per-message UI, observer
    │   ├── popup.html/.js         toolbar popup: send + status
    │   └── options.html/.js       serverUrl / token / showButton + test send
    ├── server/                    Python implementation (stdlib only)
    │   ├── llm_relay_server.py
    │   ├── config.example.json    committed template
    │   └── config.json            runtime, gitignored (may hold token)
    └── server-clj/                Clojure implementation (Ring + Jetty)
        ├── deps.edn
        ├── src/llm_relay/server.clj
        └── config.json            runtime, gitignored

## 4. Wire protocol

All endpoints are on 127.0.0.1 (default port 8765). Responses are JSON with
CORS headers allowing browser origins.

| Method | Path  | Body                          | Success response       | Python | Clojure |
|--------|-------|-------------------------------|------------------------|--------|---------|
| POST   | /send | JSON {"text": "...", "source": "host"} or raw text body | 200 {"ok": true} | yes | yes |
| POST   | /mode | {"mode": "save"/"echo"/"active"} (legacy "default" = alias of "save") | 200 {"ok": true, "mode": "..."} | no | yes |
| GET    | /mode | —                             | 200 {"mode": "..."}    | no     | yes     |
| OPTIONS| any   | —                             | 204 + CORS headers     | yes    | yes     |

Payload fields sent by the extension:

-   text — the extracted reply (required)
-   source — page hostname
-   url — page URL (servers ignore this, kept for logging/future use)
-   ts — ISO timestamp (same)

Errors: 400 empty text · 401 missing/bad X-Relay-Token · 404 unknown path ·
405 wrong method · 500 server-side exception ({"ok": false, "error": ...}).

Authentication: if the server config sets a non-empty token, every request
must carry header X-Relay-Token with the same value. Both servers and the
extension support this.

## 5. Browser extension

### 5.1 Manifest (MV3)

-   permissions: storage, contextMenus, scripting
-   host_permissions: http://127.0.0.1/*, http://localhost/*, plus one pattern
    per supported chat site. Kimi uses wildcard patterns
    (https://*.kimi.com/*, https://*.kimi.ai/*, https://*.moonshot.cn/*,
    https://*.moonshot.ai/*) to cover all subdomains and TLD variants.
-   content_scripts: content.js at document_idle on the same site list
-   commands: send-last-reply (suggested Alt+Shift+S; user-configurable at
    chrome://extensions/shortcuts)
-   action popup + options page

### 5.2 Component responsibilities

| File          | Role                                                              |
|---------------|-------------------------------------------------------------------|
| background.js | The only component that talks to the relay server (fetch). Handles the keyboard command and context menu by asking the active tab's content script to extract, then relaying. Answers llr-relay / llr-test messages. |
| content.js    | Runs inside chat pages. Knows how to find assistant messages (SITE_RULES + GENERIC selectors), renders the floating button and per-message ⇪ buttons, shows toasts. |
| popup.js      | Manual send button for the active tab + status line + settings link. |
| options.js    | Persists serverUrl, token, showButton in chrome.storage.sync; sends a test message. |

### 5.3 Internal message contract

| From → To             | type        | payload              | reply                                |
|-----------------------|-------------|----------------------|--------------------------------------|
| popup/hotkey/menu → content | llr-extract | —              | {ok, payload, chars} or {ok:false, error} |
| content → background  | llr-relay   | {payload}            | {ok} or {ok:false, error}            |
| background → content  | llr-status  | {ok, chars?, error?} | — (content shows toast)              |
| options → background  | llr-test    | —                    | {ok} or {ok:false, error}            |

Note: llr-extract and llr-status handlers respond synchronously; llr-relay and
llr-test return true from the listener to keep the message channel open for
the async sendResponse.

### 5.4 Message extraction (content.js)

1.  Pick the site rule whose match() matches location.hostname (SITE_RULES).
2.  Candidate selectors = rule.selectors first, then GENERIC fallbacks.
3.  For each selector: querySelectorAll, filter with usable() — visible
    (offsetParent or client rects), not inside our own UI wrapper, not inside
    an editable/textarea/input — then keep only outermost elements (drop
    nodes contained in another match). The LAST remaining element is the
    newest reply.
4.  elText(): clone the element, remove all button/svg/[aria-hidden] nodes
    (this also removes our own injected buttons), attach the clone offscreen,
    read innerText, remove the clone. Rendered text is captured, not original
    markdown (see §12.4).
5.  Wrap into payload {text, source, url, ts}.

### 5.5 Per-message buttons

Goal: a ⇪ button next to each reply's native copy/retry icons that sends
THAT specific message (not just the newest).

Placement strategy, in order:

1.  Explicit: rule.toolbars selectors searched in the message's ancestor
    chain (up to 6 levels), only if the found bar is visible.
2.  Heuristic: walk up ≤5 ancestor levels; scan following siblings; accept a
    container with 1–12 buttons, no <pre>, no composer controls, visible,
    and not containing another message.
3.  Fallback (always works): a corner button appended to the message itself,
    positioned absolute top-right, shown on hover.

Robustness details:

-   The button keeps a live reference to its message; on click it sends that
    message's text (falling back to the newest reply if the site replaced
    the node).
-   Click handler runs in capture phase to precede the site's own delegated
    handlers; default is prevented.
-   A MutationObserver (debounced 300 ms) re-injects buttons after the site
    re-renders; ensureButton is idempotent via a data-llr-btn marker.
-   Because elText strips buttons, injected UI can never leak into sent text.
-   Per-message UI is only injected on sites listed in SITE_RULES (the
    GENERIC selectors are too broad to decorate safely); hotkey/popup/
    floating button work on any page via GENERIC.

### 5.6 Trigger paths (all converge on the same relay call)

1.  Toolbar popup → "Send last LLM reply"
2.  Keyboard shortcut (Alt+Shift+S default)
3.  Page context menu → "Send last LLM reply to server"
4.  Floating button (bottom-right, sends newest reply)
5.  Per-message ⇪ button (sends that reply)
6.  Options page → "Send test message" (fixed text, skips extraction)

### 5.7 On-demand content-script injection

A tab opened BEFORE the extension was installed/reloaded has no content
script, and messaging it fails. Both background.js and popup.js catch that
failure, call chrome.scripting.executeScript({files: ["content.js"]}), and
retry the message once. This requires the "scripting" permission and the
host being covered by host_permissions — the reason host patterns must list
every host the user may chat on (wildcards for Kimi). Protected pages
(chrome://, web store) can never be injected and report a clear error.

## 6. Python server (server/llm_relay_server.py)

Stdlib only (http.server). ThreadingHTTPServer → one thread per request.

Config model:

-   config.json lives next to the script; auto-created from defaults on
    first run (Makefile also copies config.example.json).
-   Re-read on EVERY request — editing the file changes behavior live, no
    restart. This is intentional; the Clojure server differs here (§7.4).

Request handling (POST /send):

1.  Token check (X-Relay-Token) if configured.
2.  Body: JSON {"text","source"} preferred; a raw text body is accepted.
3.  Trim; empty → 400. Truncate to max_length.
4.  Dispatch on config command:
    -   "save" (default): write the text to instructions.md and print a
        one-line confirmation. Target path: save_path (default
        "instructions.md"); relative paths resolve against the PROJECT
        ROOT (parent of server/), absolute as-is. Overwrites by default;
        save_append true appends, separated by a "---" rule.
    -   "show": print a banner + the text to the server console.
    -   "popup": run a small tkinter window (via python -c) that displays
        the text received on stdin.
    -   anything else: a shell command. If it contains the marker {content},
        the marker is replaced with shlex.quote(text) (POSIX shells); the
        command runs with shell=True. Otherwise the text is piped to the
        command's stdin.

Execution model: spawn() starts the child without waiting; if text is piped,
a daemon thread writes stdin and closes it. A slow child can never block the
HTTP response or other requests.

Example commands (config.json "command"):

    "show"                          print to console (default)
    "popup"                         desktop window
    "pbcopy" / "wl-copy" / "clip"   clipboard (macOS / Wayland / Windows)
    "cat >> llm_log.txt"            append to file
    "python my_script.py {content}" pass text as argument

## 7. Clojure server (server-clj/)

Ring handler on Jetty (deps.edn: ring/ring-jetty-adapter, clojure/data.json).
Same /send protocol; additionally /mode.

### 7.1 Modes

-   save (default): the received text is written to instructions.md (§7.5)
    and the console shows a one-line confirmation.
-   echo: plain echo — banner, source, char count, raw text (the file is
    still saved).
-   active: renders the text as markdown with ANSI styling and runs
    command-for languages through the approval gate (§7.3). The file is
    still saved.

Mode is switched at runtime via POST /mode (save | echo | active; the
legacy value "default" is accepted as an alias of save) and persisted to
server-clj/config.json. Default mode on first run: save.

### 7.2 Markdown pipeline (active mode)

1.  split-segments: the text is split into :text and :code segments by the
    fenced-code regex. The fence token (three backticks) is BUILT at runtime
    via (apply str (repeat 3 (char 96))) instead of written literally, so the
    source file contains no fence sequence that markdown tooling could
    misinterpret (lesson learned — see git history).
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
-   Keys: host, port, token, mode, save-path, save-append,
    save-on-receive, exec-timeout-ms, max-length.

### 7.5 Saving to instructions.md

The DEFAULT action on every received message is to persist the text:

-   save-path (default "instructions.md"): relative paths resolve against
    the project root (the parent directory of server-clj/); absolute
    paths are used as-is. The resolved path is printed at startup and on
    every save.
-   Overwrite semantics by default — the file holds the latest capture.
    save-append true appends instead, separating captures with a "---"
    rule.
-   save-on-receive false disables saving entirely (console-only modes).

## 8. Configuration reference

server/config.json (Python):

| key        | default | meaning                                        |
|------------|---------|------------------------------------------------|
| host       | 127.0.0.1 | bind address                                  |
| port       | 8765    | listen port                                     |
| token      | ""      | require X-Relay-Token when non-empty            |
| command    | "save"  | save / show / popup / any shell command         |
| save_path  | "instructions.md" | save target; relative → project root  |
| save_append| false   | append with a "---" separator instead           |
| max_length | 200000  | truncate longer payloads (null/None to disable) |

server-clj/config.json (Clojure):

| key             | default   | meaning                                  |
|-----------------|-----------|------------------------------------------|
| host / port     | 127.0.0.1 / 8765 | bind address / port                |
| token           | ""        | shared secret                            |
| mode            | "save"    | "save" (default) / "echo" / "active"; legacy "default" aliased to "save" |
| save-path       | "instructions.md" | relative → project root          |
| save-append     | false     | append with a "---" separator instead    |
| save-on-receive | true      | set false to disable file writing        |
| exec-timeout-ms | 60000     | kill bash blocks after this long          |
| max-length      | 200000    | payload truncation                        |

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
-   The extension never executes anything; it only sends text.
-   Clojure active mode: human approval is the only execution gate. Approved
    scripts run locally with full user privileges — review before pressing y.
    Timeouts kill runaway blocks; EOF fails closed (no execution).
-   config.json files are gitignored precisely because they can hold tokens;
    config.example.json is the committed, secret-free template.
-   {content} substitution uses shlex.quote (POSIX). On Windows prefer
    stdin-style commands.

## 10. Extending

Add a supported chat site:

1.  manifest.json: add match patterns to content_scripts.matches AND
    host_permissions (wildcards https://*.example.com/* cover all
    subdomains).
2.  content.js SITE_RULES: add {match: h => ..., selectors: [...],
    toolbars: [...] (optional)}. Use DevTools to find a stable attribute
    (data-* / role beats hashed classes).
3.  Reload the extension; verify extraction via the popup or hotkey.

Change what happens to the text: edit the server config (Python: live;
Clojure: restart, except mode via /mode).

Add an internal message type: follow the table in §5.3; remember to return
true from onMessage listeners that respond asynchronously.

Add an executable code language (Clojure active mode): one defmethod in
server-clj/src/llm_relay/server.clj, then restart - e.g.

    (defmethod command-for "node" [_ _ b] ["node" "-e" (:text b)])

To customize a language beyond the standard prompt/run flow, add an exact
[:code "lang"] process-segment method (see section 7.3).

## 11. Development & testing workflow

    make run          # Python server (creates config.json on first run)
    make test         # curl POST a test message
    make run-clj      # Clojure server (same port — run one at a time)

Extension: chrome://extensions → Developer mode → Load unpacked → select
extension/. After editing extension files: reload the card (↻); refreshing
chat tabs is optional thanks to on-demand injection. After editing
manifest.json (permissions/matches): reload AND refresh open chat tabs.

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
3.  Chat DOMs drift. SITE_RULES carries per-site selectors (data-* and role
    hooks preferred), GENERIC fallbacks cover unknown/broken sites, and the
    corner-button fallback depends only on the message element itself.
4.  Extraction captures RENDERED text (innerText of a cleaned clone), not
    the original markdown. Consequence: server-side markdown rendering
    (Clojure active mode) operates on the page's rendering; fenced code
    blocks survive intact because they render verbatim, but emphasis/nested
    structures may be normalized.
5.  Text is captured at click time; sending during streaming captures a
    partial reply.
6.  Firefox is not configured out of the box: it needs
    background.scripts instead of service_worker plus a gecko id in the
    manifest.
7.  Unpacked installs require Chrome's Developer mode toggle (an install-
    time policy only, no runtime effect). Publishing unlisted to the Web
    Store removes the toggle for other machines.
8.  Windows: the Clojure server shells out to bash (use WSL or Git Bash);
    Python {content} quoting is POSIX-only.
9.  max_length guards both servers against runaway payloads; the extension
    has no client-side cap.
10. The two servers deliberately differ on config semantics: Python re-reads
    config per request (live editing); Clojure loads at startup (mode is
    the runtime-switchable dimension, persisted via /mode).
