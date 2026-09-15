(ns llm-relay.server
  "LLM Relay server — Clojure edition.

   Protocol (same as the Python server):
     POST /send  {\"text\": \"...\", \"source\": \"chatgpt.com\"}
     POST /mode  {\"mode\": \"active\" | \"default\"}   (persisted to config.json)
     GET  /mode  -> current mode

   Modes:
     default  print the raw text (plain echo)
     active   render the text as markdown (ANSI) in the terminal; fenced
              bash code blocks are displayed, then you are asked whether
              to execute each one. EOF / anything unrecognized = no."
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [clojure.data.json :as json]
            [ring.adapter.jetty :as jetty]))

;; ── config ────────────────────────────────────────────────────────────

(def defaults
  {:host            "127.0.0.1"
   :port            8765
   :token           ""            ; requests must send X-Relay-Token when set
   :mode            "default"     ; "default" | "active"
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

;; ── bash execution ────────────────────────────────────────────────────

(defn- run-bash [script timeout-ms]
  (try
    (let [p (.start (ProcessBuilder. ^java.util.List ["bash" "-c" script]))]
      (.close (.getOutputStream p))                 ; scripts reading stdin see EOF
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

(defn- execute-block [{:keys [text]} cfg]
  (println (yellow "  ⏳ running…"))
  (let [{:keys [exit out err]} (run-bash text (->long 60000 (:exec-timeout-ms cfg)))]
    (case exit
      :timeout (println (red "  ⏱ timed out — process killed"))
      :error   (println (red "  ✗ could not start bash:") (str err))
      (do
        (println (if (zero? exit)
                   (green (str "  ✓ exit " exit))
                   (red   (str "  ✗ exit " exit))))
        (print-out "stdout:" out)
        (print-out "stderr:" err)))))

;; ── the two modes ─────────────────────────────────────────────────────

(defn- process-default [text source]
  (let [bar (apply str (repeat 66 "="))]
    (println)
    (println bar)
    (println "📩 from" source "·" (count text) "chars")
    (println bar)
    (println text)
    (println bar)))

(def ^:private bash-langs #{"bash" "sh" "shell" "zsh"})

(defn- process-active [text cfg]
  (println)
  (println (dim (str "╭── markdown · " (count text) " chars " (apply str (repeat 30 "─")))))
  (loop [segs (split-segments text) policy :ask]
    (when-let [seg (first segs)]
      (case (:kind seg)
        :text (do (println (fmt-text (:text seg)))
                  (println)
                  (recur (rest segs) policy))
        :code (let [bash?  (contains? bash-langs (:lang seg))
                    choice (cond
                             (not bash?)          :display
                             (= policy :all)      :all
                             (= policy :skip-all) :skip-all
                             :else                (ask!))]
                (when (#{:yes :all} choice)
                  (execute-block seg cfg))
                (when bash? (println))
                (recur (rest segs)
                       (cond (= choice :all)      :all
                             (= choice :skip-all) :skip-all
                             :else                policy))))))
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
      (do (if (= "active" (:mode cfg))
            (locking prompt-lock (process-active text cfg))
            (process-default text source))
          (flush)
          (json-resp 200 {:ok true})))))

(defn- handle-mode [req]
  (case (:request-method req)
    :get (json-resp 200 {:mode (:mode @config)})
    :post (let [mode (try (json/read-str (read-body req)) (catch Exception _ nil))]
            (if (contains? #{"active" "default"} (str mode))
              (do (swap! config assoc :mode mode)
                  (save-config!)
                  (println (str "\n[mode] → " mode))
                  (flush)
                  (json-resp 200 {:ok true :mode mode}))
              (json-resp 400 {:ok false :error "mode must be \"active\" or \"default\""})))
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
  (let [{:keys [host port mode token exec-timeout-ms]} @config]
    (println "──────────────────────────────────────────────────────")
    (println " LLM Relay Server (Clojure)")
    (println (str "  ->  http://" host ":" port "/send"))
    (println (str "  mode    : " (if (= "active" mode)
                                   "ACTIVE — markdown + bash (asks before executing)"
                                   "default — plain echo")))
    (println (str "  config  : " (.getAbsolutePath config-file) "  (mode persisted here)"))
    (println (str "  token   : " (if (str/blank? token) "disabled" "enabled")))
    (println (str "  timeout : " exec-timeout-ms " ms per bash block"))
    (println "  switch  : curl -X POST :8765/mode -d '{\"mode\":\"active\"}'")
    (println "──────────────────────────────────────────────────────")
    (flush)
    (jetty/run-jetty #'handler {:host host :port (->long 8765 port)})))
