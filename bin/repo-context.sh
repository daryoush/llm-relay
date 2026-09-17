#!/usr/bin/env bash
# Emit the repo (git state + all tracked source files) as one text bundle
# for pasting into an LLM chat.
#   ./bin/repo-context.sh            -> stdout
#   ./bin/repo-context.sh | pbcopy   -> clipboard (macOS)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

echo "# llm-relay @ $(git rev-parse --short HEAD) (branch: $(git branch --show-current), $(git log -1 --format=%cs))"
echo
echo "## git status"
git status --short
echo
echo "## git log (last 15)"
git log --oneline -15
echo

while IFS= read -r f; do
  case "$f" in
    *.md|*.py|*.json|*.js|*.html|*.edn|*.clj|Makefile|.gitignore) ;;
    *) continue ;;
  esac
  echo
  echo "===================== FILE: $f ====================="
  cat "$f"
done < <(git ls-files)
echo
echo "===================== END OF BUNDLE ====================="
