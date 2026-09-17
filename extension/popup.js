"use strict";

const status = document.getElementById("status");
const prev = document.getElementById("prev");
const srv = document.getElementById("srv");

const F3 = "`".repeat(3);
const hostOf = u => { try { return new URL(u).hostname; } catch { return "unknown"; } };

chrome.storage.sync.get({ serverUrl: "http://127.0.0.1:8765/send" })
  .then(c => (srv.textContent = "→ " + c.serverUrl));

document.getElementById("opts").onclick = (e) => {
  e.preventDefault();
  chrome.runtime.openOptionsPage();
};

// popup is an extension page: with the clipboardRead permission this is
// auto-granted — no prompt
async function readClipboard() {
  try {
    const t = await navigator.clipboard.readText();
    const trimmed = t.trim();
    prev.textContent = trimmed
      ? `"${trimmed.slice(0, 140)}${trimmed.length > 140 ? "…" : ""}"`
      : "(clipboard is empty)";
    return t;
  } catch (e) {
    prev.textContent = "⚠️ clipboard read failed: " + (e.message || e.name);
    return null;
  }
}
readClipboard();

document.getElementById("send").onclick = async () => {
  const t = await readClipboard();
  if (!t || !t.trim()) { status.textContent = "⚠️ nothing to send"; return; }

  status.textContent = "…";
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const payload = {
    text: t,
    capture: "clipboard",
    source: tab?.url ? hostOf(tab.url) : "unknown",
    url: tab?.url || "n/a",
    ts: new Date().toISOString(),
  };
  const r = await chrome.runtime.sendMessage({ type: "llr-relay", payload })
    .catch(e => ({ ok: false, error: e.message }));
  status.textContent = r?.ok
    ? `✅ sent ${t.split(F3).length - 1} fenced blocks (${t.length} chars)`
    : "⚠️ " + (r?.error || "failed");
};
