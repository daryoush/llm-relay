Both servers now treat "save the latest received text to instructions.md" as the default action, with sensible knobs around it:

Default target: <project root>/instructions.md (relative save_paths resolve against the repo root; absolute paths as-is). The resolved path is printed at startup and on every save.
Overwrite semantics by default — the file holds the latest capture. Set save_append: true to accumulate, separated by --- rules.
Python: new built-in command "save" is the default; "show", "popup", shell commands still selectable.
Clojure: modes are now "save" (default — one-line console note), "echo" (the old plain echo), "active" (markdown + gated execution; also saves the file so you keep the reviewed content). The legacy value "default" is accepted as an alias for "save" and auto-migrated on load.

⚠️ While rewiring the Clojure /mode endpoint I found a real bug in the previously shipped code: it checked (str mode) against the parsed JSON object instead of extracting the "mode" field, so POST /mode always answered 400. Fixed below — mode switching actually works now.

Run everything from the repo root.

1 · Python server patches + example config + gitignore
bash
    if (cfg.get("command") or "save") == "save":
        print(f" saves to: {instructions_path(cfg)}"
              + ("  (appending)" if cfg.get("save_append") else ""))'''
assert old in t
t = t.replace(old, new)


f.write_text(t)
print("✓ llm_relay_server.py patched")
EOF


cat > server/config.example.json << 'EOF'
{
  "host": "127.0.0.1",
  "port": 8765,
  "token": "",
  "command": "save",
  "save_path": "instructions.md",
  "save_append": false,
  "max_length": 200000
}
EOF


cat >> .gitignore << 'EOF'


# default save target for received messages (remove this line to track it in git)
instructions.md
EOF

Note: your existing server/config.json (gitignored, created earlier) still says "command": "show" — the Python server re-reads it per request, so it keeps echoing until you either edit that file to "save" or delete it and let make run regenerate it.

2 · Clojure server — full rewrite (includes the /mode bug fix)
bash
  (case (:request-method req)
    :get (json-resp 200 {:mode (:mode @config)})
    :post (let [body (try (json/read-str (read-body req)) (catch Exception _ nil))
                mode (cond
                       (map? body)    (str (get body "mode"))
                       (string? body) body
                       :else nil)
                mode (if (= "default" mode) "save" mode)]   ; legacy alias
            (if (contains? #{"save" "echo" "active"} mode)
              (do (swap! config assoc :mode mode)
                  (save-config!)
                  (println (str "\n[mode] → " mode))
                  (flush)
                  (json-resp 200 {:ok true :mode mode}))
              (json-resp 400 {:ok false :error "mode must be \"save\", \"echo\" or \"active\""})))
    :options (no-content)
    (json-resp 405 {:ok false :error "method not allowed"})))


(defn- clean-path [uri]
  (let [p (str/replace (first (str/split (or uri "/") #"\?")) #"/+$" "")]
    (if (str/blank? p) "/" p)))


(defn handler [req]
  (try
    (if-not (authorized? req)
      (json-resp 401 {:ok false :error "missing or bad X-Relay-Token"})
      (case (clean-path (:uri req))
        "/send" (case (:request-method req)
                  :post    (handle-send req)
                  :options (no-content)
                  (json-resp 405 {:ok false :error "POST required"}))
        "/mode" (handle-mode req)
        (json-resp 404 {:ok false :error (str "unknown path " (:uri req))})))
    (catch Exception e
      (json-resp 500 {:ok false :error (str (ex-message e))}))))


;; ── main ──────────────────────────────────────────────────────────────


(defn -main [& _]
  (load-config!)
  (let [{:keys [mode]} @config]
    (println "──────────────────────────────────────────────────────")
    (println " LLM Relay Server (Clojure)")
    (println (str "  ->  http://" (:host @config) ":" (:port @config) "/send"))
    (println (str "  mode    : " (case mode
                                   "active" "ACTIVE — markdown + executable code (asks first)"
                                   "echo"   "echo — plain console output"
                                   "save — write instructions.md (one-line note)")))
    (println (str "  saves to: " (save-path @config)
                  (when (true? (:save-append @config)) "  (appending)")
                  (when (false? (:save-on-receive @config)) "  (saving DISABLED)")))
    (println (str "  config  : " (.getAbsolutePath config-file)))
    (println (str "  token   : " (if (str/blank? (:token @config)) "disabled" "enabled")))
    (println (str "  timeout : " (:exec-timeout-ms @config) " ms per code block"))
    (println "  switch  : curl -X POST :8765/mode -d '{\"mode\":\"active\"}'   ; save | echo | active")
    (println "──────────────────────────────────────────────────────")
    (flush)
    (jetty/run-jetty #'handler {:host (:host @config)
                                :port (->long 8765 (:port @config))})))
CLOJEOF


cd server-clj && clojure -M -e "(require 'llm-relay.server) (println :syntax-ok)"; cd ..

Your existing server-clj/config.json needs no edits — "default" is auto-migrated to "save" on load and the new keys fall back to defaults.

3 · Update README, extension hint, DESIGN.md
bash
python3 - << 'EOF'
import pathlib


# README
f = pathlib.Path("README.md"); t = f.read_text()
old = '    "show"                            print to the server console (default)\n'
new = ('    "save"                            write instructions.md (default)\n'
       '    "show"                            print to the server console\n')
assert old in t; f.write_text(t.replace(old, new)); print("✓ README.md")


# extension options hint
f = pathlib.Path("extension/options.html"); t = f.read_text()
old = "(default command: print it)"
new = "(default command: save it to instructions.md)"
assert old in t; f.write_text(t.replace(old, new)); print("✓ extension/options.html")


# DESIGN.md
f = pathlib.Path("DESIGN.md"); t = f.read_text()


old = "-   Display the reply outside the browser (console, desktop window)."
new = ("-   Persist the latest reply as instructions.md (the default action on\n"
       "    both servers).\n"
       "-   Display the reply outside the browser (console, desktop window).")
assert old in t; t = t.replace(old, new, 1)


old = '| POST   | /mode | {"mode": "active"} or {"mode": "default"} | 200 {"ok": true, "mode": "..."} | no | yes |'
new = '| POST   | /mode | {"mode": "save"/"echo"/"active"} (legacy "default" = alias of "save") | 200 {"ok": true, "mode": "..."} | no | yes |'
assert old in t; t = t.replace(old, new, 1)


old = '''4.  Dispatch on config command:
    -   "show" (default): print a banner + the text to the server console.'''
new = '''4.  Dispatch on config command:
    -   "save" (default): write the text to instructions.md and print a
        one-line confirmation. Target path: save_path (default
        "instructions.md"); relative paths resolve against the PROJECT
        ROOT (parent of server/), absolute as-is. Overwrites by default;
        save_append true appends, separated by a "---" rule.
    -   "show": print a banner + the text to the server console.'''
assert old in t; t = t.replace(old, new, 1)


old = '''-   default: plain echo — banner, source, char count, raw text. Functionally
    equivalent to the Python "show" command.
-   active: treats the text as markdown, renders it with ANSI styling in the
    terminal, and gates bash execution behind an approval prompt.


Mode is switched at runtime via POST /mode and persisted to
server-clj/config.json. Default mode on first run.'''
new = '''-   save (default): the received text is written to instructions.md (§7.5)
    and the console shows a one-line confirmation.
-   echo: plain echo — banner, source, char count, raw text (the file is
    still saved).
-   active: renders the text as markdown with ANSI styling and runs
    command-for languages through the approval gate (§7.3). The file is
    still saved.


Mode is switched at runtime via POST /mode (save | echo | active; the
legacy value "default" is accepted as an alias of save) and persisted to
server-clj/config.json. Default mode on first run: save.'''
assert old in t; t = t.replace(old, new, 1)


old = "-   Keys: host, port, token, mode, exec-timeout-ms, max-length."
new = ("-   Keys: host, port, token, mode, save-path, save-append,\n"
       "    save-on-receive, exec-timeout-ms, max-length.")
assert old in t; t = t.replace(old, new, 1)


old = "## 8. Configuration reference"
new = '''### 7.5 Saving to instructions.md


The DEFAULT action on every received message is to persist the text:


-   save-path (default "instructions.md"): relative paths resolve against
    the project root (the parent directory of server-clj/); absolute
    paths are used as-is. The resolved path is printed at startup and on
    every save.
-   Overwrite semantics by default — the file holds the latest capture.
    save-append true appends instead, separating captures with a "---"
    rule.
-   save-on-receive false disables saving entirely (console-only modes).


## 8. Configuration reference'''
assert old in t; t = t.replace(old, new, 1)


old = '| command    | "show"  | show / popup / any shell command                |'
new = ('| command    | "save"  | save / show / popup / any shell command         |\n'
       '| save_path  | "instructions.md" | save target; relative → project root  |\n'
       '| save_append| false   | append with a "---" separator instead           |')
assert old in t; t = t.replace(old, new, 1)


old = '| mode            | "default" | "default" or "active"                    |'
new = ('| mode            | "save"    | "save" (default) / "echo" / "active"; legacy "default" aliased to "save" |\n'
       '| save-path       | "instructions.md" | relative → project root          |\n'
       '| save-append     | false     | append with a "---" separator instead    |\n'
       '| save-on-receive | true      | set false to disable file writing        |')
assert old in t; t = t.replace(old, new, 1)


f.write_text(t); print("✓ DESIGN.md")
EOF
4 · Test
bash
rm -f server/config.json        # regenerate with the new default
make run &                      # terminal 1 (or run in its own terminal)
sleep 1
make test                       # terminal 2
cat instructions.md             # → "hello from make test"


# Clojure side — stop the Python server first (same port), then:
make run-clj &
python3 - << 'EOF'
import json, urllib.request
fence = "`" * 3
text = ("## Demo\n\n" + fence + "bash\necho captured AND executed?\n" + fence)
req = urllib.request.Request("http://127.0.0.1:8765/send",
      data=json.dumps({"text": text, "source": "curl"}).encode(),
      headers={"Content-Type": "application/json"})
print(urllib.request.urlopen(req).read().decode())
EOF
# server console: 💾 saved … chars → …/instructions.md, then the markdown + prompt
curl -s -X POST http://127.0.0.1:8765/mode -d '{"mode":"echo"}'   # works now (bug fixed)
curl -s http://127.0.0.1:8765/mode
5 · Commit
bash
git add server/llm_relay_server.py server/config.example.json \
        server-clj/src/llm_relay/server.clj .gitignore README.md \
        extension/options.html DESIGN.md
git commit -m "Default action on both servers: save received text to instructions.md; fix Clojure /mode parsing bug"
git push

Notes:

instructions.md is gitignored as runtime capture output. If you actually want the file tracked (e.g., it doubles as your project's agent instructions), delete those lines from .gitignore and git add -f instructions.md.
Python reads config per request, so switching to "append" (or any other tweak) is live; Clojure needs a restart, except mode via POST /mode.
Reload the extension card if you want the options-page hint text updated (cosmetic; behavior unchanged — the extension was always mode-agnostic).
