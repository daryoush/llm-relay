#!/usr/bin/env bash
# Emit the repo (git state + tracked source files) as one text bundle.
#   ./bin/repo-context.sh            -> stdout
#   ./bin/repo-context.sh | pbcopy   -> clipboard (macOS)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

EXCLUDE_RE='^(instructions\.md|how-to-synch\.md)$'
MAX_FILE=120000   # per-file safety cap (bytes)

echo "# llm-relay @ $(git rev-parse --short HEAD) (branch: $(git branch --show-current), $(git log -1 --format=%cs))"
echo
echo "## git status"; git status --short; echo
echo "## git log (last 15)"; git log --oneline -15; echo

code=$(git ls-files | grep -Ev "$EXCLUDE_RE" | grep -E '\.(py|js|json|edn|clj|html)$|^(Makefile|\.gitignore|bin/relay-clj)$' || true)
docs=$(git ls-files | grep -Ev "$EXCLUDE_RE" | grep -E '\.md$' || true)

while IFS= read -r f; do
  [ -n "$f" ] || continue
  echo
  echo "===================== FILE: $f ====================="
  if [ "$(wc -c < "$f")" -gt "$MAX_FILE" ]; then
    echo "[truncated by repo-context.sh: exceeds ${MAX_FILE} bytes]"
  else
    cat "$f"
  fi
done <<< "$(printf '%s\n%s\n' "$code" "$docs" | awk 'NF && !seen[$0]++')"
echo
echo "===================== END OF BUNDLE ====================="
