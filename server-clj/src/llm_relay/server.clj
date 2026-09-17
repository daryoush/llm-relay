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

(def ^:private home-dir
  "The HOME of this server instance: the directory the process was
   LAUNCHED from. config.json is read/created here and relative
   :save-path values resolve against it. Override with LLM_RELAY_HOME
   (env) or -Dllm-relay.home=... (system property)."
  (.getAbsoluteFile
   (io/file (or (System/getProperty "llm-relay.home")
                (System/getenv "LLM_RELAY_HOME")
                "."))))

(def config-file
  (io/file (or (System/getProperty "llm-relay.config")
               (System/getenv "LLM_RELAY_CONFIG")
               (io/file home-dir "config.json"))))

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

(defn- save-path
  "Absolute save target: :save-path as-is when absolute, else resolved
   against home-dir — the launch directory."
  [cfg]
  (let [p (str (or (:save-path cfg) "instructions.md"))]
    (if (.isAbsolute (io/file p))
      p
      (str (io/file home-dir p)))))

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
  ;; Fences are recognized ONLY at the beginning of a line (?m): both the
  ;; opening and the closing marker must start a line. Fence-like sequences
  ;; in the middle of a line are plain text and can never introduce an
  ;; executable block. (Policy: the extension sends text verbatim; all
  ;; interpretation lives here, on the server.)
  (re-pattern (str "(?m)^" fence "([^\\n\\r]*)\\r?\\n"
                   "([\\s\\S]*?)"
                   "^" fence "[ \\t]*")))

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
    (println (str "  home    : " (.getAbsolutePath home-dir)))
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
