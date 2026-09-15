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

async function extractAndSend(tabId) {
  let resp;
  try {
    resp = await chrome.tabs.sendMessage(tabId, { type: "llr-extract" });
  } catch {
    // tab was opened before the extension was loaded/reloaded — inject and retry
    try {
      await chrome.scripting.executeScript({ target: { tabId }, files: ["content.js"] });
      resp = await chrome.tabs.sendMessage(tabId, { type: "llr-extract" });
    } catch {
      return { ok: false, error: "no content script here (unsupported site?)" };
    }
  }
  if (!resp?.ok) return { ok: false, error: resp?.error || "no reply found" };
  try {
    await relay(resp.payload);
  } catch (e) {
    return { ok: false, error: e.message };
  }
  return { ok: true, chars: resp.chars };
}

// Messages from content script (floating button) and options page (test)
chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg?.type === "llr-relay") {
    relay(msg.payload)
      .then(() => sendResponse({ ok: true }))
      .catch(e => sendResponse({ ok: false, error: e.message }));
    return true; // async response
  }
  if (msg?.type === "llr-test") {
    relay({
      text: "Test message from LLM Relay extension 🎉",
      source: "extension-test", url: "n/a", ts: new Date().toISOString(),
    })
      .then(() => sendResponse({ ok: true }))
      .catch(e => sendResponse({ ok: false, error: e.message }));
    return true;
  }
});

// Keyboard shortcut
chrome.commands.onCommand.addListener(async (command) => {
  if (command !== "send-last-reply") return;
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id) return;
  const r = await extractAndSend(tab.id);
  chrome.tabs.sendMessage(tab.id, { type: "llr-status", ...r }).catch(() => {});
});

// Context menu
chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: "llr-send",
    title: "Send last LLM reply to server",
    contexts: ["page"],
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId !== "llr-send" || !tab?.id) return;
  const r = await extractAndSend(tab.id);
  chrome.tabs.sendMessage(tab.id, { type: "llr-status", ...r }).catch(() => {});
});
