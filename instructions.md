That error is precise: `chrome.scripting.executeScript` refused because **the exact host in your address bar isn't in `host_permissions`**. It works on z.ai because that host matches a pattern — so you're on a Kimi variant we didn't list. Kimi has several (`kimi.moonshot.cn`, `kimi.com`, `www.kimi.com`, plus short/regional domains and subdomains like `chat.kimi.com` or `kimi.ai`).

**Quick check:** look at your address bar on Kimi — the hostname must literally match one of the patterns in the manifest.

## Fix: widen the Kimi patterns to wildcards

In Chrome match patterns, `*.kimi.com` covers **the bare domain and every subdomain** (`kimi.com`, `www.`, `chat.`, …), so a handful of wildcards covers the whole family:

```bash
cd llm-relay

python3 - << 'EOF'
import json, pathlib

m = pathlib.Path("extension/manifest.json")
data = json.loads(m.read_text())

KIMI = [
    "https://*.kimi.com/*",
    "https://*.kimi.ai/*",
    "https://*.moonshot.cn/*",
    "https://*.moonshot.ai/*",
]

def update(patterns):
    # remove any old kimi/moonshot entries, re-add wildcard set after localhost
    kept = [p for p in patterns if "kimi" not in p and "moonshot" not in p]
    out = []
    inserted = False
    for p in kept:
        out.append(p)
        if not inserted and p.startswith("http://localhost"):
            out.extend(KIMI)
            inserted = True
    if not inserted:
        out.extend(KIMI)
    return out

data["host_permissions"] = update(data["host_permissions"])
for cs in data.get("content_scripts", []):
    cs["matches"] = update(cs["matches"])

m.write_text(json.dumps(data, indent=2) + "\n")
print("✓ manifest.json — Kimi patterns widened to:")
print("\n".join("    " + p for p in KIMI))
EOF
```

The script is idempotent (safe to run twice) and patches **both** lists that matter:

- `host_permissions` → gates programmatic injection (your current error)
- `content_scripts[].matches` → gates automatic injection on page load

## Then

1. `chrome://extensions` → click **↻ reload** on the LLM Relay card.
2. Go to your Kimi tab — **no refresh needed** — click the extension icon → **Send last LLM reply**. On-demand injection now works on the new hosts immediately.

Note the floating button only appears automatically on pages loaded *after* the manifest change; on the current tab it appears after the first send (or a page refresh).

## If it still fails

Copy the exact URL from the address bar and check the hostname (the part between `https://` and the first `/`). If it's something exotic not covered by the four wildcards, either add it to the `KIMI` list above and re-run, or paste it here and I'll confirm the right pattern.

Commit once it works:

```bash
git add extension/manifest.json
git commit -m "Widen Kimi host patterns to cover all subdomains and TLD variants"
git push
```

Side note: the same wildcard trick would work for z.ai (`https://*.z.ai/*`) — it's working for you as-is, so I left it untouched, but it's the same one-line change if z.ai ever grows a subdomain you use.