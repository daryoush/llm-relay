Excellent — the mystery is fully resolved: the fences only ever existed in the site's raw clipboard serialization, never in the rendered DOM, so making the clipboard the source of truth was the correct fix. Client is now a dumb pipe (copy → relay), server prints + saves verbatim. Nothing in the pipeline edits text.

Before you move on, a little housekeeping to make the repo match reality:

```bash
cd llm-relay

# manifest: the hotkey description still says "last reply" — align it
python3 - << 'EOF'
import json, pathlib
m = pathlib.Path("extension/manifest.json")
d = json.loads(m.read_text())
d["commands"]["send-last-reply"]["description"] = "Send clipboard content to the relay server"
m.write_text(json.dumps(d, indent=2) + "\n")
print("✓ command description updated")
EOF

# README: replace the old usage line with the clipboard workflow
python3 - << 'EOF'
import pathlib
f = pathlib.Path("README.md"); t = f.read_text()
old = """Send the last reply via: toolbar popup · Alt+Shift+S (configurable at
chrome://extensions/shortcuts) · right-click menu · floating button."""
new = """Clipboard workflow (v1.3.0+): copy the reply you want (the site's copy
button preserves raw markdown incl. code fences), then send via toolbar
popup (with preview) · floating "Send clipboard" button · Alt+Shift+S ·
right-click menu. The clipboard content is relayed verbatim — the
extension performs no extraction or filtering; the server owns all
interpretation."""
assert old in t
f.write_text(t.replace(old, new)); print("✓ README.md")
EOF

# DESIGN.md: add a dated note so future readers (or an LLM) see the
# current mode first without rewriting the whole history
python3 - << 'EOF'
import pathlib
f = pathlib.Path("DESIGN.md"); t = f.read_text()
marker = "## 1. Purpose"
note = """## 0. Current state (v1.3.0)

The extension is a CLIPBOARD PIPE: the user copies the text they want
(the site's copy button yields raw markdown with code fences), then
triggers a send (popup / floating button / hotkey / context menu). The
clipboard content is relayed verbatim. DOM-based extraction of "the last
reply" was removed after field testing showed rendered page text does
not contain fence markers on several sites. See §5 for historical
extraction details (superseded), §12 for the investigation record.

""" + marker
assert marker in t
f.write_text(t.replace(marker, note, 1)); print("✓ DESIGN.md")
EOF

git add extension/manifest.json README.md DESIGN.md
git commit -m "Docs + manifest aligned with v1.3.0 clipboard mode"
git push
```

Two optional toggles, whenever you feel like it:

- **Debug logging:** the pipeline is proven, so `server/config.json` → `"debug_payload": false` will stop growing `server/debug_payload.jsonl` (takes effect on the next request, no restart).
- **Keep or drop `debug_payload` permanently:** it's handy for future "what did the client actually send?" questions, so I'd leave the code in.

Nice-to-haves if you keep evolving this: rename the command id/description pair cleanly, and eventually rewrite DESIGN.md §5 to describe the clipboard architecture in full (the §0 note covers the essentials for now).

And for the record, the failed approaches are worth remembering: DOM reconstruction (rewrote text), rendered-text capture (fences don't exist there), clipboard-button automation (permission prompts, focus games, per-block vs whole-message ambiguity). The boring solution — user copies, extension relays — won. Sometimes that's the lesson. 🎉

Anything else you want to build on top of it — e.g., the Clojure server parity check, or a "queue mode" that appends instead of overwrites `instructions.md`?