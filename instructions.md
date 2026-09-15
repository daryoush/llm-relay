Good news: the test passing means the background worker and server connection are fine. The error means the **content script isn't running inside the chat tab** — almost always for one of these reasons:

## Why it happens

1. **The tab was opened before the extension was loaded/reloaded.** Content scripts only auto-inject into pages loaded *after* the extension is installed or reloaded. Any chat tab that was already open stays without it until you refresh. **This is the most common cause — just refresh the chat page (F5) and try again.**
2. **Site access is restricted.** Go to `chrome://extensions` → LLM Relay → **Details** → **Site access** → make sure it's **"On all sites"**.
3. **The domain doesn't match the manifest.** Check your address bar — e.g. bare `https://z.ai` (without `www.` or `chat.`) is *not* in the match list, nor are any other subdomains not listed.
4. Quick check that injection happened: on the chat page open DevTools → **Sources → Content scripts** → you should see `content.js`. Or run in the console:
   ```js
   document.getElementById("llr-root")   // the floating button wrapper; null = not injected
   ```

## Permanent fix: auto-inject on demand

You can make the extension inject the content script itself whenever it's missing, so "tab opened before install" never matters again.

```bash
cd llm-relay

# 1. manifest: add the "scripting" permission
python3 - << 'EOF'
import pathlib
m = pathlib.Path("extension/manifest.json")
t = m.read_text()
old = '"permissions": ["storage", "contextMenus"],'
new = '"permissions": ["storage", "contextMenus", "scripting"],'
assert old in t, "manifest pattern not found"
m.write_text(t.replace(old, new))
print("✓ manifest.json patched")
EOF

# 2. background.js: inject + retry when messaging fails
python3 - << 'EOF'
import pathlib
f = pathlib.Path("extension/background.js")
t = f.read_text()
old = '''  } catch {
    return { ok: false, error: "no content script here (unsupported site?)" };
  }'''
new = '''  } catch {
    // tab was opened before the extension was loaded/reloaded — inject and retry
    try {
      await chrome.scripting.executeScript({ target: { tabId }, files: ["content.js"] });
      resp = await chrome.tabs.sendMessage(tabId, { type: "llr-extract" });
    } catch {
      return { ok: false, error: "no content script here (unsupported site?)" };
    }
  }'''
assert old in t, "background pattern not found"
f.write_text(t.replace(old, new))
print("✓ background.js patched")
EOF

# 3. popup.js: same inject + retry
python3 - << 'EOF'
import pathlib
f = pathlib.Path("extension/popup.js")
t = f.read_text()
old = '''  } catch {
    status.textContent = "⚠️ no content script on this page";
    return;
  }'''
new = '''  } catch {
    // tab was opened before the extension was loaded/reloaded — inject and retry
    try {
      await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["content.js"] });
      resp = await chrome.tabs.sendMessage(tab.id, { type: "llr-extract" });
    } catch (e) {
      status.textContent = "⚠️ can't run here: " + (e.message || "unsupported page");
      return;
    }
  }'''
assert old in t, "popup pattern not found"
f.write_text(t.replace(old, new))
print("✓ popup.js patched")
EOF
```

The `assert` lines make the patches safe — if a pattern doesn't match (e.g. you edited the files), it fails loudly instead of corrupting anything.

## Then

1. `chrome://extensions` → click the **↻ reload** icon on the LLM Relay card.
2. Go to your chat tab — no refresh needed anymore; click the extension icon → **Send last LLM reply**. The first click injects the script and sends.

Note: on pages browsers protect by design (`chrome://` pages, Chrome Web Store, the extension's own options page) injection will always fail — the popup now tells you that instead of the misleading message.

Commit the fix:

```bash
git add extension/
git commit -m "Auto-inject content script on demand via chrome.scripting"
git push
```

If it still says "can't run here" **on an actual supported chat page after this**, tell me the exact URL you're testing on and I'll adjust the match patterns.