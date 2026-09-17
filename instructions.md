Diagnosis: on DeepSeek the auto-detection **found a "toolbar" and appended the ⇪ into it — but that container is one you never see**. DeepSeek (a) builds its copy/retry icon row lazily / on hover and (b) uses hashed CSS class names, so the heuristic's "nearest sibling with a few buttons" latched onto something hidden or wrong. And because *a* bar was found, the guaranteed-visible corner-button fallback never ran. z.ai and Qwen happen to have simple, always-present action rows, which is why they work.

Fix in two parts: make `findToolbar` refuse invisible/wrong containers (so the corner fallback always kicks in as a floor), and give DeepSeek explicit toolbar candidates.

## Patch `extension/content.js`

```bash
cd llm-relay

python3 - << 'EOF'
import re, pathlib
f = pathlib.Path("extension/content.js")
t = f.read_text()

# 1) DeepSeek rule: add explicit toolbar candidates
old_rule = '''  {
    match: h => h === "chat.deepseek.com",
    selectors: ["div.ds-markdown", '[class*="ds-markdown"]', ".markdown-body"],
  },'''
new_rule = '''  {
    match: h => h === "chat.deepseek.com",
    selectors: ["div.ds-markdown", '[class*="ds-markdown"]', ".markdown-body"],
    // DeepSeek builds its hover icon-row lazily and uses hashed classes —
    // try these known bar shapes first, else fall back to the corner button
    toolbars: ['[class*="ds-chat-message__action"]',
               '[class*="chat-message"] [class*="action"]'],
  },'''
assert old_rule in t, "DeepSeek rule not found"
t = t.replace(old_rule, new_rule)

# 2) findToolbar: visibility check, composer exclusion, toolbars[] support
new_fn = '''const isVisible = el => !!(el.offsetParent || el.getClientRects().length);

function findToolbar(msg, rule) {
  // 1) explicit selectors from the site rule, in order — must be VISIBLE
  const explicit = rule?.toolbars || (rule?.toolbar ? [rule.toolbar] : []);
  for (const sel of explicit) {
    for (let el = msg.parentElement, i = 0; el && i < 6; i++, el = el.parentElement) {
      for (const t of el.querySelectorAll(sel)) if (isVisible(t)) return t;
    }
  }
  // 2) heuristic: nearest following sibling (walking up a few levels) that is a
  //    small, visible button row — never another message, never the composer
  const sels = rule?.selectors || [];
  const hasMsg = n => {
    try { return sels.some(s => n.matches(s) || n.querySelector(s)); } catch { return false; }
  };
  let el = msg;
  for (let i = 0; i < 5 && el; i++) {
    let sib = el.nextElementSibling;
    while (sib) {
      const n = sib.querySelectorAll("button").length;
      if (n >= 1 && n <= 12
          && !sib.querySelector("pre")
          && !sib.querySelector('textarea, [contenteditable="true"]')
          && isVisible(sib)
          && !hasMsg(sib)) return sib;
      sib = sib.nextElementSibling;
    }
    el = el.parentElement;
  }
  return null;
}'''
t, n = re.subn(r"(?s)function findToolbar\(msg, rule\) \{.*?\n\}", new_fn, t)
assert n == 1, "findToolbar not found"
f.write_text(t)
print("✓ content.js patched (DeepSeek toolbars + visibility-gated heuristic)")
EOF
```

After this, DeepSeek gets at minimum the **hover ⇪ in the message's top-right corner** — always visible, works regardless of DeepSeek's DOM games. If one of the `toolbars` candidates matches, you get the in-row placement instead.

## Reload & check

`chrome://extensions` → **↻** on LLM Relay → refresh the DeepSeek tab. Hover an assistant message: ⇪ should appear top-right. Click → toast → text at your server.

## Optional: put it inside DeepSeek's real icon row

If you want it next to DeepSeek's copy/retry buttons, find the exact bar with this probe — paste in the console on a DeepSeek conversation:

```js
(() => {
  const msgs = document.querySelectorAll("div.ds-markdown");
  if (!msgs.length) return console.log("⚠️ no div.ds-markdown — message selectors outdated?");
  let el = msgs[msgs.length - 1];
  for (let i = 0; i < 6 && el; i++, el = el.parentElement) {
    for (let sib = el.nextElementSibling; sib; sib = sib.nextElementSibling) {
      const btns = sib.querySelectorAll("button");
      if (btns.length)
        console.log(btns.length + " btn | bar classes: " + [...sib.classList].join(" ")
                  + " | first btn: " + [...btns[0].classList].join(" "), sib);
    }
  }
  console.log("→ pick the row whose classes look like icon buttons (copy/retry),");
  console.log("  prefer names containing 'ds-' or 'action' (hashed ones change per deploy)");
})();
```

Then paste the winning selector into the rule:

```js
    toolbars: ['THE-SELECTOR-FROM-THE-PROBE'],
```

reload the extension, done.

## Commit

```bash
git add extension/content.js
git commit -m "DeepSeek: explicit toolbar candidates; refuse invisible toolbars so corner button always available"
git push
```

One caveat to set expectations: if DeepSeek's `ds-chat-message__action`-style guesses don't match their current build, the corner button is the durable answer — it depends on nothing but the message element itself, which `div.ds-markdown` already gives us. And if the probe ever prints *"no div.ds-markdown"*, that means DeepSeek renamed their message container too — that same probe output tells us, and updating `selectors` restores both placement and the hotkey path.