"use strict";

const DEFAULTS = { serverUrl: "http://127.0.0.1:8765/send", token: "" };

const cfg = async () => ({ ...DEFAULTS, ...(await chrome.storage.sync.get(DEFAULTS)) });

async function relay(payload) {
  const c = await cfg();
  const headers = { "Content-Type": "application/json" };
  if (c.token) headers["X-Relay-Token"] = c.token;
  let res;
  try {
    res = await fetch(c.serverUrl, { method: "POST", headers, body: JSON.stringify(payload) });
  } catch {
    throw new Error("can't reach server — is it running?");
  }
  if (!res.ok) throw new Error(`server answered ${res.status}`);
}

// Injected into the active tab to read the clipboard. execCommand('paste')
// first: silent with the clipboardRead permission. Async clipboard API as
// fallback. Self-contained function (executeScript requirement).
function readClipInPage() {
  try {
    const ta = document.createElement("textarea");
    ta.style.cssText = "position:fixed;left:-9999px;top:0;opacity:0";
    document.body.appendChild(ta);
    ta.focus();
    const ok = document.execCommand("paste");
    const val = ta.value;
    ta.remove();
    if (ok && val && val.trim()) return { text: val };
  } catch (e) { /* fall through */ }
  return navigator.clipboard.readText()
    .then(t => (t && t.trim()) ? { text: t } : { error: "clipboard is empty" })
    .catch(e => ({ error: "clipboard read failed (" + (e.name || e.message) +
      ") — click the page once, or use the extension popup" }));
}

function hostOf(url) {
  try { return new URL(url).hostname; } catch { return "unknown"; }
}

async function sendClipboard(tabId) {
  let out;
  try {
    const [r] = await chrome.scripting.executeScript({
      target: { tabId }, func: readClipInPage,
    });
    out = r?.result;
  } catch (e) {
    return { ok: false, error: "can't read clipboard here (" +
      (e.message || "no host permission") + ") — use the extension popup instead" };
  }
  if (!out || out.error) return { ok: false, error: out?.error || "no text" };

  let url = null;
  try { url = (await chrome.tabs.get(tabId)).url || null; } catch {}

  const payload = {
    text: out.text,
    capture: "clipboard",
    source: url ? hostOf(url) : "unknown",
    url: url || "n/a",
    ts: new Date().toISOString(),
  };
  try {
    await relay(payload);
  } catch (e) {
    return { ok: false, error: e.message };
  }
  return { ok: true, text: out.text };
}

async function sendClipboardAndReport(tabId) {
  const r = await sendClipboard(tabId);
  const msg = r.ok
    ? { type: "llr-status", ok: true,
        detail: `clipboard — ${r.text.split("`".repeat(3)).length - 1}` +
                ` fenced blocks (${r.text.length} chars)` }
    : { type: "llr-status", ok: false, error: r.error };
  chrome.tabs.sendMessage(tabId, msg).catch(() => {});
}

// ── messages: popup relay / floating button / options test ────────────

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg?.type === "llr-relay") {                 // popup already read clipboard
    relay(msg.payload)
      .then(() => sendResponse({ ok: true }))
      .catch(e => sendResponse({ ok: false, error: e.message }));
    return true;
  }
  if (msg?.type === "llr-send-clipboard") {        // floating button
    const tabId = sender.tab?.id;
    if (!tabId) { sendResponse({ ok: false, error: "no tab" }); return; }
    sendClipboard(tabId).then(sendResponse);
    return true;
  }
  if (msg?.type === "llr-test") {                  // options page
    relay({ text: "Test message from LLM Relay extension 🎉",
            capture: "test", source: "extension-test", url: "n/a",
            ts: new Date().toISOString() })
      .then(() => sendResponse({ ok: true }))
      .catch(e => sendResponse({ ok: false, error: e.message }));
    return true;
  }
});

// ── hotkey + context menu ─────────────────────────────────────────────

chrome.commands.onCommand.addListener(async (command) => {
  if (command !== "send-last-reply") return;
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (tab?.id) await sendClipboardAndReport(tab.id);
});

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: "llr-send", title: "Send clipboard to relay server", contexts: ["page"],
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId === "llr-send" && tab?.id) await sendClipboardAndReport(tab.id);
});
