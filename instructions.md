Two things currently tie the Clojure server to its own directory:

1. **The Clojure CLI itself** — `clojure -M -m llm-relay.server` only finds the code if a `deps.edn` sits in the launch directory (that's why the Makefile does `cd server-clj` first).
2. **The `project-root` hack in `server.clj`** — it resolves saves against the *parent* of the working directory, an assumption that breaks the moment you launch from anywhere else.

Both are fixed below. New model: **home = the directory you launch from**. `config.json` is read/created there, relative `save-path`s resolve there. A small launcher script makes the code runnable from any directory.

## 1 · Patch `server.clj` — home-dir semantics

```bash
python3 - << 'EOF'
import pathlib
f = pathlib.Path("server-clj/src/llm_relay/server.clj")
t = f.read_text()

old = '''(def config-file
  (io/file (or (System/getProperty "llm-relay.config") "config.json")))'''
new = '''(def ^:private home-dir
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
               (io/file home-dir "config.json"))))'''
assert old in t, "config-file block not found"
t = t.replace(old, new, 1)

old = '''(def ^:private project-root
  "Parent of the server's working directory — the repo root in a normal
   clone. Relative :save-path values resolve against it."
  (or (.getParentFile (.getAbsoluteFile (io/file ".")))
      (io/file ".")))

(defn- save-path [cfg]
  (let [p (str (or (:save-path cfg) "instructions.md"))]
    (if (.isAbsolute (io/file p))
      p
      (str (io/file project-root p)))))'''
new = '''(defn- save-path
  "Absolute save target: :save-path as-is when absolute, else resolved
   against home-dir — the launch directory."
  [cfg]
  (let [p (str (or (:save-path cfg) "instructions.md"))]
    (if (.isAbsolute (io/file p))
      p
      (str (io/file home-dir p)))))'''
assert old in t, "project-root block not found"
t = t.replace(old, new, 1)

old = '''    (println (str "  saves to: " (save-path @config)'''
new = '''    (println (str "  home    : " (.getAbsolutePath home-dir)))
    (println (str "  saves to: " (save-path @config)'''
assert old in t, "banner line not found"
t = t.replace(old, new, 1)

f.write_text(t)
print("✓ server.clj: home = launch directory (config.json + relative saves)")
EOF

cd server-clj && clojure -M -e "(require 'llm-relay.server) (println :syntax-ok)"; cd ..
```

## 2 · Launcher: `bin/relay-clj` (the arbitrary-directory trick)

The launcher points the Clojure CLI at the project's `src` via `-Sdeps` with an **absolute path derived from the script's own location** — so no `deps.edn` is needed where you run it, and the CLI still resolves the jars from `~/.m2`:

```bash
mkdir -p bin
cat > bin/relay-clj << 'EOF'
#!/usr/bin/env bash
# relay-clj — run the LLM Relay Clojure server from ANY directory.
#
# The launch directory becomes the server's HOME: config.json is read or
# created there, and relative save paths (instructions.md) resolve
# against it.
#
#   relay-clj                                     # home = current directory
#   LLM_RELAY_CONFIG=/path/config.json relay-clj  # explicit config file
#   LLM_RELAY_HOME=/path relay-clj                # explicit home directory
#
# Requires the clojure CLI on PATH. Dependency versions below must stay
# in sync with server-clj/deps.edn — update both together.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

exec clojure -Sdeps "{:paths [\"${ROOT}/server-clj/src\"]
                       :deps {org.clojure/clojure     {:mvn/version \"1.11.3\"}
                              ring/ring-jetty-adapter {:mvn/version \"1.13.0\"}
                              org.clojure/data.json   {:mvn/version \"2.5.0\"}}}" \
       -M -m llm-relay.server "$@"
EOF
chmod +x bin/relay-clj
```

Optional — make it a global command:

```bash
mkdir -p ~/.local/bin
ln -sf "$PWD/bin/relay-clj" ~/.local/bin/relay-clj
# then: cd ~/anywhere && relay-clj
```

## 3 · Makefile, gitignore, one-time migration

```bash
python3 - << 'EOF'
import pathlib
f = pathlib.Path("Makefile"); t = f.read_text()
old = "run-clj:\n\tcd server-clj && clojure -M -m llm-relay.server\n"
new = "run-clj:\n\t./bin/relay-clj\n"
assert old in t, "run-clj target not found"
f.write_text(t.replace(old, new)); print("✓ Makefile")
EOF

cat >> .gitignore << 'EOF'

# clojure server may run with any launch directory as its home
/config.json
server-clj/llm-relay.jar
EOF

# one-time: carry over an existing customized config (else defaults are created)
[ -f server-clj/config.json ] && [ ! -f config.json ] \
  && cp server-clj/config.json config.json && echo "→ migrated server-clj/config.json to ./config.json" || true
```

`make run-clj` still behaves like before — make runs from the repo root, so home = repo root and `instructions.md` lands there as always.

## 4 · Optional: uberjar (zero launcher magic, fastest startup)

Build once, then `java -jar` from anywhere — no `clojure` CLI needed at runtime:

```bash
cat > server-clj/deps.edn << 'EOF'
{:paths ["src"]
 :deps  {org.clojure/clojure        {:mvn/version "1.11.3"}
         ring/ring-jetty-adapter    {:mvn/version "1.13.0"}
         org.clojure/data.json      {:mvn/version "2.5.0"}}
 :aliases {:run   {:main-opts ["-m" "llm-relay.server"]}
           :build {:deps {com.github.seancorfield/depstar {:mvn/version "2.1.303"}}
                   :ns-default hf.depstar
                   :exec-args {:jar "llm-relay.jar"
                               :aot true
                               :main-class llm-relay.server}}}}
EOF

cd server-clj && clojure -X:build && cd ..
java -jar server-clj/llm-relay.jar     # launch dir = home, same rules
```

## 5 · Update DESIGN.md

```bash
python3 - << 'EOF'
import pathlib
f = pathlib.Path("DESIGN.md"); t = f.read_text()

old = """-   Config is an atom loaded from config.json at STARTUP (differs from the
    Python server's per-request reload). Hand-editing the file requires a
    restart; changing mode via POST /mode persists immediately."""
new = """-   Config is an atom loaded from config.json at STARTUP (differs from the
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
    dependency entirely: java -jar llm-relay.jar, same home rules."""
assert old in t; t = t.replace(old, new, 1)

old = "    make run-clj      # Clojure server (same port — run one at a time)"
new = ("    make run-clj      # Clojure server from repo root (home = repo root)\n"
       "    ./bin/relay-clj   # same server from ANY directory (home = that dir)")
assert old in t; t = t.replace(old, new, 1)

f.write_text(t); print("✓ DESIGN.md")
EOF
```

## 6 · Verify

```bash
mkdir -p /tmp/relay-home && cd /tmp/relay-home
/path/to/llm-relay/bin/relay-clj          # terminal 1
```

```bash
cd /tmp/relay-home                        # terminal 2
curl -s -X POST http://127.0.0.1:8765/send -H 'Content-Type: application/json' \
     -d '{"text":"**launched from elsewhere**"}'
cat instructions.md    # ← created HERE
cat config.json        # ← created HERE (defaults)
```

The startup banner now tells you exactly what it decided:

```
  home    : /tmp/relay-home
  saves to: /tmp/relay-home/instructions.md
  config  : /tmp/relay-home/config.json
```

## 7 · Commit

```bash
git add server-clj/src/llm_relay/server.clj server-clj/deps.edn \
        bin/relay-clj Makefile .gitignore DESIGN.md
git commit -m "Clojure server: launch-directory home semantics; bin/relay-clj launcher for arbitrary directories; optional uberjar build"
git push
```

Notes:

- **The launcher duplicates the dep versions** from `deps.edn` (a `-Sdeps` limitation). I put a sync reminder in both files; if that bothers you, the uberjar path eliminates the duplication — build once, run the jar.
- **`server-clj/config.json` is now dormant** for `make run-clj` (home moved to repo root) — the migration line in step 3 copies it; delete it once you're happy.
- **Windows:** `bin/relay-clj` is bash — use WSL/Git Bash, or just `java -jar` (option 4), which works from `cmd`/PowerShell.
- The Python server was left untouched deliberately: it's location-independent already (config lives next to the script, saves default to the repo root). If you'd rather have Python adopt the same launch-directory semantics, it's a two-line change to `CONFIG_FILE` and `instructions_path` — say the word.