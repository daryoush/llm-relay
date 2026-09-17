"use strict";

const WRAP_ID = "llr-root";
const F3 = "`".repeat(3);                       // fence token, built at runtime

// v1.3.0 — CLIPBOARD MODE. The extension does not extract anything from
// the page. You copy the text you want (site's copy button = raw markdown
// with fences), then trigger a send; the clipboard content is relayed
// verbatim. All capture logic lives in background.js.

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
      maxWidth: "420px", whiteSpace: "normal",
    });
    document.body.appendChild(el);
  }
  el.textContent = msgText;
  el.style.display = "block";
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (el.style.display = "none"), 4000);
}

let wrap = null;
async function syncButton() {
  const { showButton = true } = await chrome.storage.sync.get({ showButton: true });
  if (showButton && !wrap) {
    wrap = document.createElement("div");
    wrap.id = WRAP_ID;
    const btn = document.createElement("div");
    btn.textContent = "⇪ Send clipboard";
    btn.title = "Sends the current clipboard content to the relay server verbatim";
    Object.assign(btn.style, {
      position: "fixed", right: "16px", bottom: "16px", zIndex: 2147483647,
      padding: "8px 14px", borderRadius: "20px", cursor: "pointer",
      background: "#111827", color: "#fff", font: "13px system-ui, sans-serif",
      boxShadow: "0 2px 8px rgba(0,0,0,.35)", opacity: "0.85", userSelect: "none",
    });
    let last = 0;
    btn.addEventListener("click", async () => {
      if (Date.now() - last < 400) return;      // debounce double-fires
      last = Date.now();
      const res = await chrome.runtime
        .sendMessage({ type: "llr-send-clipboard" })
        .catch(e => ({ ok: false, error: e.message }));
      toast(res?.ok
        ? `✅ Sent clipboard — ${res.text.split(F3).length - 1} fenced blocks (${res.text.length} chars)`
        : `⚠️ ${res?.error || "failed"}`);
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

// feedback for hotkey / context-menu triggered sends
chrome.runtime.onMessage.addListener((msg) => {
  if (msg?.type === "llr-status") {
    toast(msg.ok ? `✅ Sent ${msg.detail}` : `⚠️ ${msg.error}`);
  }
});
