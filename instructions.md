Yes — the extension can inject its own **⇪ button into each message's action bar**, right next to copy/retry, and clicking it sends *that specific reply* (not just the newest one). Two realities shape the implementation:

1. **Chat UIs re-render constantly** (React replaces DOM nodes, especially during streaming). Any button we add can be wiped at any moment — so we run a `MutationObserver` that re-injects as needed.
2. **Every site builds its toolbar differently**, so the code finds the toolbar heuristically (nearest button-row after the message). If it can't find one, it falls back to a small ⇪ button in the message's top-right corner on hover. Either way the text extraction and sending logic is identical.

Only the content script changes — manifest, background, popup, server all stay as they are.

## Replace `extension/content.js`

```bash
cat > extension/content.js << 'JSEOF'
"use strict";

const WRAP_ID = "llr-root";

// ── Per-site rules.
//    selectors   : how to find assistant message containers (last match = newest)
//    toolbar     : OPTIONAL exact selector for the per-message action bar.
//                  Find it with DevTools (inspect the copy/retry icons) and add
//                  e.g.  toolbar: 'div[class*="actions"]'  — then the heuristic
//                  below is skipped for that site.
const SITE_RULES = [
  {
    match: h => /(^|\.)chatgpt\.com$/.test(h) || h === "chat.openai.com",
    selectors: ['[data-message-author-role="assistant"]'],
  },
  {
    match: h => h === "claude.ai",
    selectors: ['[data-testid="assistant-message"]', ".font-claude-message"],
  },
  {
    match: h => h === "gemini.google.com",
    selectors: ["model-response .markdown", "model-response message-content", "model-response", "response-container"],
  },
  {
    match: h => h === "chat.deepseek.com",
    selectors: ["div.ds-markdown", '[class*="ds-markdown"]', ".markdown-body"],
  },
  {
    match: h => /(^|\.)kimi\.com$/.test(h) || h === "kimi.moonshot.cn",
    selectors: ['[role="assistant"]', '[class*="assistant" i]', '[class*="segment" i]'],
  },
  {
    match: h => h === "chat.qwen.ai" || /(^|\.)(tongyi\.aliyun|tongyi)\.com$/.test(h),
    selectors: ['[id^="response-content-container"]', ".tongyi-markdown", '[class*="answer" i]'],
  },
  {
    match: h => /(^|\.)z\.ai$/.test(h),
    selectors: ['[class*="assistant" i]', '[class*="markdown" i]', ".prose"],
  },
];

const GENERIC = [   // used only for "send last reply" (hotkey/popup/floating button)
  '[data-message-author-role="assistant"]',
  '[role="assistant"]',
  "article",
  '[class*="assistant" i]',
  '[class*="markdown" i]',
  ".prose",
];

function siteRule() { return SITE_RULES.find(r => r.match(location.hostname)); }

// ── extraction helpers ────────────────────────────────────────────────

function usable(el) {
  if (el.closest(`#${WRAP_ID}`)) return false;
  if (el.closest('[contenteditable="true"], textarea, input')) return false;
  return !!(el.offsetParent || el.getClientRects().length);
}

function pickAll(selector) {
  let nodes;
  try { nodes = document.querySelectorAll(selector); } catch { return []; }
  const arr = Array.from(nodes).filter(usable);
  return arr.filter(el => !arr.some(o => o !== el && o.contains(el))); // outermost only
}

function lastReply() {
  for (const sel of [...(siteRule()?.selectors || []), ...GENERIC]) {
    const arr = pickAll(sel);
    if (arr.length) return { el: arr[arr.length - 1], via: sel };
  }
  return null;
}

// innerText of a detached clone (buttons/SVGs stripped — including OUR injected ones)
function elText(el) {
  const c = el.cloneNode(true);
  c.querySelectorAll("button, svg, [aria-hidden='true']").forEach(n => n.remove());
  c.style.cssText = "position:absolute;left:-99999px;top:0;width:800px;";
  document.body.appendChild(c);
  const t = (c.innerText || "").trim();
  c.remove();
  return t;
}

function extract() {
  const r = lastReply();
  return r ? { text: elText(r.el), via: r.via } : null;
}

const buildPayload = (text) => ({
  text,
  source: location.hostname,
  url: location.href,
  ts: new Date().toISOString(),
});

async function sendText(text, label) {
  const res = await chrome.runtime
    .sendMessage({ type: "llr-relay", payload: buildPayload(text) })
    .catch(e => ({ ok: false, error: e.message }));
  toast(res?.ok ? `✅ Sent ${label} (${text.length} chars)` : `⚠️ ${res?.error || "failed"}`);
}

// ── per-message buttons in the site's action bar ──────────────────────

function findToolbar(msg, rule) {
  // 1) exact selector if the rule provides one
  if (rule?.toolbar) {
    for (let el = msg.parentElement, i = 0; el && i < 6; i++, el = el.parentElement) {
      const t = el.querySelector(rule.toolbar);
      if (t) return t;
    }
  }
  // 2) heuristic: nearest following sibling (walking up a few levels) that is a
  //    small button row — but never a sibling that IS/CONTAINS another message
  const sels = rule?.selectors || [];
  const hasMsg = n => {
    try { return sels.some(s => n.matches(s) || n.querySelector(s)); } catch { return false; }
  };
  let el = msg;
  for (let i = 0; i < 5 && el; i++) {
    let sib = el.nextElementSibling;
    while (sib) {
      const n = sib.querySelectorAll("button").length;
      if (n >= 1 && n <= 12 && !sib.querySelector("pre") && !hasMsg(sib)) return sib;
      sib = sib.nextElementSibling;
    }
    el = el.parentElement;
  }
  return null;
}

function stop(e) { e.preventDefault(); e.stopPropagation(); }

function makeToolbarButton(msg, bar) {
  const ref = bar.querySelector("button");
  const b = ref ? ref.cloneNode(false) : document.createElement("button");
  b.type = "button";
  b.textContent = "⇪";
  b.title = "Send this reply to relay server";
  b.setAttribute("aria-label", b.title);
  b.setAttribute("data-llr-btn", "1");
  if (!ref) Object.assign(b.style, {
    border: "0", background: "transparent", cursor: "pointer",
    font: "14px system-ui", padding: "4px 6px", opacity: "0.85",
  });
  b.addEventListener("click", (e) => {
    stop(e);
    const target = msg.isConnected ? msg : lastReply()?.el;
    if (target) sendText(elText(target), "this reply");
  }, true); // capture: run before the site's own delegated handlers
  return b;
}

function attachCornerButton(msg) {
  if (getComputedStyle(msg).position === "static") msg.style.position = "relative";
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = "⇪";
  b.title = "Send this reply to relay server";
  b.setAttribute("data-llr-btn", "1");
  Object.assign(b.style, {
    position: "absolute", top: "4px", right: "4px", zIndex: 5,
    opacity: "0", transition: "opacity .15s", border: "0", borderRadius: "6px",
    padding: "4px 8px", cursor: "pointer",
    background: "#111827", color: "#fff", font: "12px system-ui",
  });
  msg.addEventListener("mouseenter", () => (b.style.opacity = "0.9"));
  msg.addEventListener("mouseleave", () => (b.style.opacity = "0"));
  b.addEventListener("click", (e) => { stop(e); sendText(elText(msg), "this reply"); }, true);
  msg.appendChild(b);
}

function ensureButton(msg, rule) {
  const bar = findToolbar(msg, rule);
  if (bar && !bar.closest(`#${WRAP_ID}`)) {
    if (!bar.querySelector("[data-llr-btn]")) bar.appendChild(makeToolbarButton(msg, bar));
  } else if (!msg.querySelector(":scope > [data-llr-btn]")) {
    attachCornerButton(msg);
  }
}

// chat pages mutate constantly (streaming, re-renders) — rescan debounced
function startObserver() {
  const rule = siteRule();
  if (!rule) return;                       // injection only on known sites
  let t = null;
  new MutationObserver(() => {
    clearTimeout(t);
    t = setTimeout(() => {
      for (const sel of rule.selectors)
        for (const msg of pickAll(sel)) ensureButton(msg, rule);
    }, 300);
  }).observe(document.body, { childList: true, subtree: true });
  for (const sel of rule.selectors)
    for (const msg of pickAll(sel)) ensureButton(msg, rule);
}

// ── messages from background (hotkey / popup / context menu) ──────────

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg?.type === "llr-extract") {
    const found = extract();
    if (!found) { sendResponse({ ok: false, error: "no reply found on this page" }); return; }
    sendResponse({ ok: true, payload: buildPayload(found.text), chars: found.text.length });
    return;
  }
  if (msg?.type === "llr-status") {
    toast(msg.ok ? `✅ Sent ${msg.chars} chars` : `⚠️ ${msg.error}`);
  }
});

// ── floating button (sends the LAST reply, unchanged behavior) ────────

let wrap = null;
async function syncButton() {
  const { showButton = true } = await chrome.storage.sync.get({ showButton: true });
  if (showButton && !wrap) {
    wrap = document.createElement("div");
    wrap.id = WRAP_ID;
    const btn = document.createElement("div");
    btn.textContent = "⇪ Send last reply";
    Object.assign(btn.style, {
      position: "fixed", right: "16px", bottom: "16px", zIndex: 2147483647,
      padding: "8px 14px", borderRadius: "20px", cursor: "pointer",
      background: "#111827", color: "#fff", font: "13px system-ui, sans-serif",
      boxShadow: "0 2px 8px rgba(0,0,0,.35)", opacity: "0.85", userSelect: "none",
    });
    btn.addEventListener("click", () => {
      const found = extract();
      if (!found) { toast("❌ No reply found on this page"); return; }
      sendText(found.text, "last reply");
    });
    wrap.appendChild(btn);
    document.body.appendChild(wrap);
  } else if (!showButton && wrap) {
    wrap.remove();
    wrap = null;
  }
}
chrome.storage.onChanged.addListener(syncButton);
syncButton();
startObserver();

// ── toast ─────────────────────────────────────────────────────────────

let toastTimer;
function toast(msgText) {
  let el = document.getElementById(`${WRAP_ID}-toast`);
  if (!el) {
    el = document.createElement("div");
    el.id = `${WRAP_ID}-toast`;
    Object.assign(el.style, {
      position: "fixed", right: "16px", bottom: "56px", zIndex: 2147483647,
      padding: "8px 12px", borderRadius: "8px", background: "#111827",
      color: "#fff", font: "13px system-ui, sans-serif", display: "none",
    });
    document.body.appendChild(el);
  }
  el.textContent = msgText;
  el.style.display = "block";
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (el.style.display = "none"), 2500);
}
JSEOF
```

## Reload & test

```bash
# no build step — just reload the extension, then refresh a chat page once
```

1. `chrome://extensions` → **↻ reload** on LLM Relay → refresh the chat tab once.
2. Open any conversation: every assistant message should now have a **⇪** in its icon row (or top-right corner on hover where the toolbar wasn't found). Click it → toast confirms → the text of **that** message arrives at your server.
3. Hotkey / popup / floating button still send the newest reply, as before.

Commit:

```bash
git add extension/content.js
git commit -m "Inject per-message send button into each chat reply's action bar"
git push
```

## How it behaves / notes

- **Correct message, guaranteed:** each button keeps a live reference to its message. Even if the heuristic mis-places a button visually, clicking it sends the reply it was created for. If the site has since replaced that message node, it falls back to the newest reply.
- **Your own buttons can't pollute extraction:** `elText` strips all `<button>`/`<svg>` elements from the clone before reading text, so the injected ⇪ never appears in what gets sent — regardless of toolbar or corner placement.
- **Streaming:** buttons may appear on a message as soon as it starts streaming; text sent mid-stream is whatever is rendered at click time.
- **Toolbar not found on some site?** You'll see the corner button instead — it works identically. If you want it *inside* the icon row, use the DevTools probing from my previous answer on the copy/retry icons, then add one key to that site's rule in `content.js`:
  ```js
  { match: h => h === "chat.deepseek.com",
    selectors: ["div.ds-markdown"],
    toolbar: '.ds-markdown--actions' },   // ← your inspected selector
  ```
  and reload the extension.
- **Known-site injection only:** per-message buttons are injected where a `SITE_RULES` entry matches the hostname (your seven LLM sites). Unknown pages keep hotkey/popup/floating-button support with the generic selectors, which are too broad to safely decorate every match.