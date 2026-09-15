"use strict";

const status = document.getElementById("status");
const srv = document.getElementById("srv");

chrome.storage.sync.get({ serverUrl: "http://127.0.0.1:8765/send" })
  .then(c => (srv.textContent = "→ " + c.serverUrl));

document.getElementById("opts").onclick = (e) => {
  e.preventDefault();
  chrome.runtime.openOptionsPage();
};

document.getElementById("send").onclick = async () => {
  status.textContent = "…";
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id) { status.textContent = "⚠️ no active tab"; return; }

  let resp;
  try {
    resp = await chrome.tabs.sendMessage(tab.id, { type: "llr-extract" });
  } catch {
    // tab was opened before the extension was loaded/reloaded — inject and retry
    try {
      await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["content.js"] });
      resp = await chrome.tabs.sendMessage(tab.id, { type: "llr-extract" });
    } catch (e) {
      status.textContent = "⚠️ can't run here: " + (e.message || "unsupported page");
      return;
    }
  }
  if (!resp?.ok) { status.textContent = "⚠️ " + (resp?.error || "no reply"); return; }

  const r = await chrome.runtime.sendMessage({ type: "llr-relay", payload: resp.payload });
  status.textContent = r?.ok ? `✅ sent ${resp.chars} chars` : "⚠️ " + (r?.error || "failed");
};
