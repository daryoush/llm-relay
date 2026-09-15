"use strict";

const WRAP_ID = "llr-root";

// ── Per-site selectors. First working selector wins; last DOM match = newest reply.
//    Chat UIs change their DOM often — if a site breaks, inspect the last reply
//    in DevTools and add/fix a selector here.
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

const GENERIC = [
  '[data-message-author-role="assistant"]',
  '[role="assistant"]',
  "article",
  '[class*="assistant" i]',
  '[class*="markdown" i]',
  ".prose",
];

function usable(el) {
  if (el.closest(`#${WRAP_ID}`)) return false;                                   // our own UI
  if (el.closest('[contenteditable="true"], textarea, input')) return false;     // input box
  return !!(el.offsetParent || el.getClientRects().length);                      // visible
}

function pickLast(selector) {
  let nodes;
  try { nodes = document.querySelectorAll(selector); } catch { return null; }
  const arr = Array.from(nodes).filter(usable);
  if (!arr.length) return null;
  // keep outermost containers so innerText covers the whole message
  const tops = arr.filter(el => !arr.some(o => o !== el && o.contains(el)));
  return tops[tops.length - 1] || null;
}

// innerText of a detached clone (buttons/SVGs stripped, formatting preserved)
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
  const rule = SITE_RULES.find(r => r.match(location.hostname));
  for (const sel of [...(rule?.selectors || []), ...GENERIC]) {
    const el = pickLast(sel);
    const text = el && elText(el);
    if (text) return { text, via: sel };
  }
  return null;
}

const buildPayload = (text) => ({
  text,
  source: location.hostname,
  url: location.href,
  ts: new Date().toISOString(),
});

// ── messages from background (popup / hotkey / context menu)
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

async function sendFromPage() {
  const found = extract();
  if (!found) { toast("❌ No reply found on this page"); return; }
  const res = await chrome.runtime
    .sendMessage({ type: "llr-relay", payload: buildPayload(found.text) })
    .catch(e => ({ ok: false, error: e.message }));
  toast(res?.ok ? `✅ Sent (${found.text.length} chars)` : `⚠️ ${res?.error || "failed"}`);
}

// ── optional floating button
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
    btn.addEventListener("click", sendFromPage);
    wrap.appendChild(btn);
    document.body.appendChild(wrap);
  } else if (!showButton && wrap) {
    wrap.remove();
    wrap = null;
  }
}
chrome.storage.onChanged.addListener(syncButton);
syncButton();

// ── toast
let toastTimer;
function toast(msg) {
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
  el.textContent = msg;
  el.style.display = "block";
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (el.style.display = "none"), 2500);
}
