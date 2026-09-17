Both servers now treat **"save the latest received text to `instructions.md`"** as the default action, with sensible knobs around it:

- **Default target:** `<project root>/instructions.md` (relative `save_path`s resolve against the repo root; absolute paths as-is). The resolved path is printed at startup and on every save.
- **Overwrite semantics** by default — the file holds the latest capture. Set `save_append: true` to accumulate, separated by `---` rules.
- **Python:** new built-in command `"save"` is the default; `"show"`, `"popup"`, shell commands still selectable.
- **Clojure:** modes are now `"save"` (default — one-line console note), `"echo"` (the old plain echo), `"active"` (markdown + gated execution; **also saves the file** so you keep the reviewed content). The legacy value `"default"` is accepted as an alias for `"save"` and auto-migrated on load.

⚠️ **While rewiring the Clojure `/mode` endpoint I found a real bug in the previously shipped code:** it checked `(str mode)` against the *parsed JSON object* instead of extracting the `"mode"` field, so `POST /mode` always answered 400. Fixed below — mode switching actually works now.

Run everything from the repo root.

## 1 · Python server patches + example config + gitignore

```bash
python3 - << 'EOF'
import pathlib

# ── server/llm_relay_server.py
f = pathlib.Path("server/llm_relay_server.py")
t = f.read_text()

old = '''DEFAULT_CONFIG = {
    "host": "127.0.0.1",
    "port": 8765,
    "token": "",           # optional secret; requests must send X-Relay-Token
    "command": "show",     # "show" | "popup" | any shell command
    "max_length": 200000,  # truncate huge payloads (None to disable)
}'''
new = '''DEFAULT_CONFIG = {
    "host": "127.0.0.1",
    "port": 8765,
    "token": "",            # optional secret; requests must send X-Relay-Token
    "command": "save",      # "save" (default) | "show" | "popup" | any shell command
    "save_path": "instructions.md",  # relative paths resolve against the project root
    "save_append": False,   # true -> append with a "---" separator instead of overwriting
    "max_length": 200000,   # truncate huge payloads (None to disable)
}'''
assert old in t, "DEFAULT_CONFIG not found"
t = t.replace(old, new)

old = "def cmd_shell(command, text):"
new = '''def instructions_path(cfg):
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
    sep = ("\\n\\n---\\n\\n"
           if append and os.path.exists(path) and os.path.getsize(path) > 0 else "")
    with open(path, "a" if append else "w", encoding="utf-8") as f:
        f.write(sep + text.rstrip() + "\\n")
    print(f"💾 saved {len(text)} chars → {path}"
          + ("  (appended)" if append else ""), flush=True)


def cmd_shell(command, text):'''
assert old in t
t = t.replace(old, new, 1)

old = '''def run_command(cfg, text, meta):
    command = cfg.get("command") or "show"
    if command == "show":
        cmd_show(text, meta)
    elif command == "popup":
        cmd_popup(text, meta)
    else:
        cmd_shell(command, text)'''
new = '''def run_command(cfg, text, meta):
    command = cfg.get("command") or "save"
    if command == "save":
        cmd_save(text, meta, cfg)
    elif command == "show":
        cmd_show(text, meta)
    elif command == "popup":
        cmd_popup(text, meta)
    else:
        cmd_shell(command, text)'''
assert old in t
t = t.replace(old, new)

old = '''    print(f" command: {cfg.get('command')!r}")'''
new = '''    print(f" command: {cfg.get('command')!r}")
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
```

**Note:** your existing `server/config.json` (gitignored, created earlier) still says `"command": "show"` — the Python server re-reads it per request, so it keeps echoing until you either edit that file to `"save"` or delete it and let `make run` regenerate it.

## 2 · Clojure server — full rewrite (includes the `/mode` bug fix)

```bash
cat > server-clj/src/llm_relay/server.clj << 'CLOJEOF'
(ns llm-relay.server
  "LLM Relay server — Clojure edition.

   Protocol (same as the Python server):
     POST /send  {\"text\": \"...\", \"source\": \"chatgpt.com\"}
     POST /mode  {\"mode\": \"save\" | \"echo\" | \"active\"}  (persisted)
     GET  /mode  -> current mode        (\"default\" accepted as alias of \"save\")

   Modes (console behavior — in ALL modes the text is saved to
   instructions.md unless :save-on-receive is false):
     save (default)  persist + one-line console confirmation
     echo            plain raw-text echo
     active          render the text as markdown (ANSI) in the terminal;
                     fenced code blocks in EXECUTABLE languages (see the
                     command-for multimethod) are displayed, then you are
                     asked whether to run each one.

   Active mode is built on two open multimethods:
     command-for      lang -> command vector or nil (extension point:
                             one defmethod makes a language executable)
     process-segment  :text | [:code \"lang\"] -> renders + returns policy"
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [clojure.data.json :as json]
            [ring.adapter.jetty :as jetty]))

;; ── config ────────────────────────────────────────────────────────────

(def defaults
  {:host            "127.0.0.1"
   :port            8765
   :token           ""               ; requests must send X-Relay-Token when set
   :mode            "save"           ; "save" (default) | "echo" | "active"
   :save-path       "instructions.md" ; relative -> project root (see save-path)
   :save-append     false            ; true -> append with a --- separator
   :save-on-receive true             ; false -> never write the file
   :exec-timeout-ms 60000
   :max-length      200000})

(def config-file
  (io/file (or (System/getProperty "llm-relay.config") "config.json")))

(def config (atom defaults))

(defn- ->long [d v]
  (cond (number? v) (long v)
        (string? v) (or (try (Long/parseLong v) (catch Exception _ nil)) d)
        :else d))

(defn- save-config! []
  (spit config-file (with-out-str (json/pprint @config)) :encoding "UTF-8"))

(defn- load-config! []
  (if (.exists config-file)
    (try
      (reset! config (merge defaults
                            (json/read-str (slurp config-file :encoding "UTF-8")
                                           :key-fn keyword)))
      ;; migrate the legacy mode name
      (when (= "default" (:mode @config))
        (swap! config assoc :mode "save"))
      (catch Exception e
        (binding [*out* *err*]
          (println "[config] could not read" (str config-file) "—" (ex-message e)))))
    (save-config!)))

;; ── ANSI helpers (colors only when stdout is a real terminal) ─────────

(def ^:private ansi?
  (boolean (and (System/console)
                (str/blank? (or (System/getenv "NO_COLOR") "")))))

(defn- c [code s] (if ansi? (str "\u001b[" code "m" s "\u001b[0m") s))
(def ^:private b     #(c "1" %))    ; bold
(def ^:private dim   #(c "2" %))
(def ^:private ital  #(c "3" %))
(def ^:private red    #(c "31" %))
(def ^:private green  #(c "32" %))
(def ^:private yellow #(c "33" %))
(def ^:private cyan   #(c "36" %))

;; ── saving (the DEFAULT action) ───────────────────────────────────────

(def ^:private project-root
  "Parent of the server's working directory — the repo root in a normal
   clone. Relative :save-path values resolve against it."
  (or (.getParentFile (.getAbsoluteFile (io/file ".")))
      (io/file ".")))

(defn- save-path [cfg]
  (let [p (str (or (:save-path cfg) "instructions.md"))]
    (if (.isAbsolute (io/file p))
      p
      (str (io/file project-root p)))))

(defn- save-instructions!
  "Default action for every received message: persist the text to
   instructions.md. Overwrites by default (the file holds the latest
   capture); with :save-append true, appends separated by a --- rule."
  [text cfg]
  (when-not (false? (:save-on-receive cfg))
    (let [f       (io/file (save-path cfg))
          append? (true? (:save-append cfg))
          prefix  (if (and append? (.exists f) (pos? (.length f)))
                    "\n\n---\n\n"
                    "")]
      (io/make-parents f)
      (spit f (str prefix text "\n") :encoding "UTF-8" :append append?)
      (println (green (str "💾 saved " (count text) " chars → "
                           (.getAbsolutePath f)))))))

;; ── markdown ──────────────────────────────────────────────────────────

;; The markdown fence (three backticks) is BUILT at runtime instead of written
;; literally, so this source file contains no triple-backtick sequence that a
;; markdown renderer could ever confuse with a code-fence boundary.
(def ^:private fence (apply str (repeat 3 (char 96))))

(def ^:private fence-re
  (re-pattern (str fence "([^\\n\\r]*)\\r?\\n([\\s\\S]*?)" fence)))

(defn- split-segments
  "Split text into ordered {:kind :text|:code :lang ... :text ...} segments.
   A fence without a closing fence marker stays part of the surrounding text."
  [text]
  (let [m (re-matcher fence-re text)]
    (loop [segs [] end 0]
      (if (.find m)
        (let [lang (first (str/split (str/trim (str (.group m 1))) #"\s+"))
              pre  (subs text end (.start m))
              segs (cond-> segs
                     (not (str/blank? pre)) (conj {:kind :text :text pre})
                     true                   (conj {:kind :code
                                                   :lang (str/lower-case lang)
                                                   :text (.group m 2)}))]
          (recur segs (.end m)))
        (let [tail (subs text end)]
          (cond-> segs
            (not (str/blank? tail)) (conj {:kind :text :text tail})))))))

(defn- fmt-inline [s]
  (str/join
   (for [[_m code txt] (re-seq #"(`[^`\n]+`)|([^`]+)" s)]
     (if code
       (cyan code)
       (-> txt
           (str/replace #"\*\*([^*\n]+)\*\*" #(b (second %)))
           (str/replace #"__([^_\n]+)__"    #(b (second %)))
           (str/replace #"(?<![\w*])\*([^*\n]+?)\*(?![\w*])" #(ital (second %)))
           (str/replace #"\[([^\]\n]+)\]\(([^)\n]+)\)"
                        (fn [[_ label href]] (str label " " (dim (str "⟨" href "⟩"))))))))))

(defn- fmt-text [s]
  (->> (str/split-lines s)
       (map (fn [line]
              (cond
                (re-find #"\A\s{0,3}#{1,6}\s" line)     (b line)
                (re-find #"\A\s{0,3}>\s?" line)         (ital (dim line))
                (re-find #"\A\s*[-*+]\s" line)          (str/replace-first line #"\A(\s*)[-*+]\s+" "$1• ")
                (re-find #"\A\s*([-*_]\s*){3,}\z" line) (dim (apply str (repeat 64 "─")))
                :else (fmt-inline line))))
       (str/join "\n")))

(defn- render-code [{:keys [lang text]}]
  (let [lines (str/split-lines (str/trimr text))
        head  (str (b (if (str/blank? lang) "code" lang)) " " (dim "────"))
        body  (map #(str (dim "│ ") (cyan %)) lines)]
    (str/join "\n" (concat [head] body [(dim "╰────")]))))

;; ── execution ─────────────────────────────────────────────────────────

(defn- run-command
  "Run cmd (a VECTOR — no shell involved) with stdin closed immediately.
   Drain stdout/stderr on futures; kill after timeout-ms. Returns
   {:exit int | :timeout | :error, :out string, :err string}."
  [cmd timeout-ms]
  (try
    (let [p (.start (ProcessBuilder. ^java.util.List cmd))]
      (.close (.getOutputStream p))                 ; stdin readers see EOF
      (let [out (future (slurp (.getInputStream p)))
            err (future (slurp (.getErrorStream p)))]
        (if (.waitFor p timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
          {:exit (.exitValue p) :out @out :err @err}
          (do (.destroyForcibly p)
              {:exit :timeout
               :out (deref out 1000 "")
               :err (deref err 1000 "")}))))
    (catch Exception e
      {:exit :error :out "" :err (or (ex-message e) (str e))})))

(def ^:private prompt-lock (Object.))

(defn- ask!
  "Returns :yes | :no | :all | :skip-all.  EOF or junk answer -> :no (safe)."
  []
  (print (b "  ▶ Execute this block?  "))
  (print (dim "[y]es  [n]o (display only)  [a]ll remaining  [q]uit: "))
  (flush)
  (case (some-> (read-line) str/trim str/lower-case)
    "y" :yes
    "n" :no
    "a" :all
    "q" :skip-all
    :no))

(defn- print-out [label s]
  (when-not (str/blank? s)
    (println (dim (str "  " label)))
    (doseq [l (str/split-lines s)]
      (println (str "  " l)))))

;; ──────────────────────────────────────────────────────────────────────
;; EXTENSION POINT #1 — command-for
;;
;; Dispatches on the FENCE LANGUAGE. Return the command VECTOR that runs a
;; block (the command embeds the script itself — no shell, no quoting), or
;; nil to keep the language display-only. Adding a language is ONE line:
;;
;;   (defmethod command-for "node" [_ _ b] ["node" "-e" (:text b)])
;;
;; Any language with a command automatically gets the standard approval
;; flow (prompt / y-n-a-q / timeout) via process-segment's [:code :default].
;; ──────────────────────────────────────────────────────────────────────

(defmulti command-for
  "Command vector to execute a fenced block of the given language, or nil."
  (fn [lang _block] lang))

(defmethod command-for :default [_ _] nil)

(defmethod command-for "bash"    [_ _ blk] ["bash"    "-c" (:text blk)])
(defmethod command-for "sh"      [_ _ blk] ["sh"      "-c" (:text blk)])
(defmethod command-for "zsh"     [_ _ blk] ["zsh"     "-c" (:text blk)])
(defmethod command-for "shell"   [_ _ blk] ["bash"    "-c" (:text blk)])
(defmethod command-for "python"  [_ _ blk] ["python3" "-c" (:text blk)])
(defmethod command-for "python3" [_ _ blk] ["python3" "-c" (:text blk)])
;; (defmethod command-for "node"  [_ _ blk] ["node"  "-e" (:text blk)])
;; (defmethod command-for "ruby"  [_ _ blk] ["ruby"  "-e" (:text blk)])

(defn- execute-command! [cmd cfg]
  (println (yellow "  ⏳ running…"))
  (let [{:keys [exit out err]} (run-command cmd (->long 60000 (:exec-timeout-ms cfg)))]
    (case exit
      :timeout (println (red "  ⏱ timed out — process killed"))
      :error   (println (red "  ✗ could not start " (first cmd) ":") (str err))
      (do
        (println (if (zero? exit)
                   (green (str "  ✓ exit " exit))
                   (red   (str "  ✗ exit " exit))))
        (print-out "stdout:" out)
        (print-out "stderr:" err)))))

(defn- maybe-execute
  "Standard approval flow for an executable code segment. Returns policy."
  [seg policy cfg]
  (let [choice (cond
                 (= policy :all)      :all
                 (= policy :skip-all) :skip-all
                 :else                (ask!))]
    (when (#{:yes :all} choice)
      (execute-command! (command-for (:lang seg) seg) cfg))
    (cond (= choice :all)      :all
          (= choice :skip-all) :skip-all
          :else                policy)))

;; ──────────────────────────────────────────────────────────────────────
;; EXTENSION POINT #2 — process-segment
;;
;; Dispatched once per segment of an active-mode message:
;;   :text             -> prose (rendered as markdown)
;;   [:code "bash"]    -> exact-language override (wins over :default)
;;   [:code :default]  -> any other code block: rendered, and if
;;                        command-for knows the language, run through the
;;                        standard approval flow
;; Every method must RETURN the new policy (:ask | :all | :skip-all).
;; The whole message is just:  (reduce process-segment :ask segments)
;; ──────────────────────────────────────────────────────────────────────

(defmulti process-segment
  "Process one active-mode segment; returns the updated policy."
  (fn [seg _policy _cfg]
    (if (= :code (:kind seg))
      [:code (or (:lang seg) "")]
      (:kind seg))))

(defmethod process-segment :text [seg policy _cfg]
  (println (fmt-text (:text seg)))
  (println)
  policy)

(defmethod process-segment [:code :default] [seg policy cfg]
  (println (render-code seg))
  (println)
  (if (command-for (:lang seg) seg)
    (maybe-execute seg policy cfg)
    policy))

;; ── the modes ─────────────────────────────────────────────────────────

(defn- process-echo [text source]
  (let [bar (apply str (repeat 66 "="))]
    (println)
    (println bar)
    (println "📩 from" source "·" (count text) "chars")
    (println bar)
    (println text)
    (println bar)))

(defn- process-active [text cfg]
  (println)
  (println (dim (str "╭── markdown · " (count text) " chars " (apply str (repeat 30 "─")))))
  (reduce (fn [policy seg] (process-segment seg policy cfg))
          :ask
          (split-segments text))
  (println (dim (str "╰" (apply str (repeat 60 "─"))))))

;; ── http ──────────────────────────────────────────────────────────────

(defn- json-resp [status m]
  {:status  status
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin"  "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type, X-Relay-Token"}
   :body    (json/write-str m)})

(defn- no-content []
  {:status 204
   :headers {"Access-Control-Allow-Origin"  "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type, X-Relay-Token"}
   :body nil})

(defn- authorized? [req]
  (let [{:keys [token]} @config]
    (or (str/blank? token)
        (= token (get-in req [:headers "x-relay-token"])))))

(defn- read-body [req]
  (if-let [b (:body req)]
    (slurp b :encoding "UTF-8")
    ""))

(defn- parse-payload [raw]
  (try
    (let [d (json/read-str raw)]
      (if (map? d)
        {:text (str (get d "text")) :source (str (get d "source" "unknown"))}
        {:text raw :source "unknown"}))
    (catch Exception _ {:text raw :source "unknown"})))

(defn- handle-send [req]
  (let [cfg @config
        {:keys [text source]} (parse-payload (read-body req))
        text  (str/trim text)
        limit (:max-length cfg)
        text  (if (and limit (> (count text) (int limit))) (subs text 0 limit) text)]
    (cond
      (str/blank? text)
      (json-resp 400 {:ok false :error "empty text"})

      :else
      (do (save-instructions! text cfg)          ; the default action, in ALL modes
          (case (:mode cfg)
            "active" (locking prompt-lock (process-active text cfg))
            "echo"   (process-echo text source)
            nil)                                 ; "save": the save line above is all
          (flush)
          (json-resp 200 {:ok true})))))

(defn- handle-mode [req]
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
```

Your existing `server-clj/config.json` needs no edits — `"default"` is auto-migrated to `"save"` on load and the new keys fall back to defaults.

## 3 · Update README, extension hint, DESIGN.md

```bash
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
```

## 4 · Test

```bash
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
```

## 5 · Commit

```bash
git add server/llm_relay_server.py server/config.example.json \
        server-clj/src/llm_relay/server.clj .gitignore README.md \
        extension/options.html DESIGN.md
git commit -m "Default action on both servers: save received text to instructions.md; fix Clojure /mode parsing bug"
git push
```

Notes:

- **`instructions.md` is gitignored** as runtime capture output. If you actually want the file tracked (e.g., it doubles as your project's agent instructions), delete those lines from `.gitignore` and `git add -f instructions.md`.
- Python reads config per request, so switching to `"append"` (or any other tweak) is live; Clojure needs a restart, except mode via `POST /mode`.
- Reload the extension card if you want the options-page hint text updated (cosmetic; behavior unchanged — the extension was always mode-agnostic).