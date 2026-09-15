"use strict";

const DEFAULTS = { serverUrl: "http://127.0.0.1:8765/send", token: "", showButton: true };
const $ = (id) => document.getElementById(id);

chrome.storage.sync.get(DEFAULTS).then((c) => {
  $("serverUrl").value = c.serverUrl;
  $("token").value = c.token;
  $("showButton").checked = c.showButton;
});

$("save").onclick = async () => {
  await chrome.storage.sync.set({
    serverUrl: $("serverUrl").value.trim() || DEFAULTS.serverUrl,
    token: $("token").value.trim(),
    showButton: $("showButton").checked,
  });
  $("status").textContent = "✅ saved";
  setTimeout(() => ($("status").textContent = ""), 2000);
};

$("test").onclick = async () => {
  const r = await chrome.runtime.sendMessage({ type: "llr-test" });
  $("status").textContent = r?.ok
    ? "✅ server answered — check its console"
    : "⚠️ " + (r?.error || "failed");
};
