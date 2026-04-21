/* VicinoLLM — vanilla web UI. No framework. Talks to /v1 on the same host. */
(() => {
  "use strict";

  // =========================================================================
  // constants
  // =========================================================================
  const API = {
    health:    "/health",
    models:    "/v1/models",
    available: "/v1/models/available",
    load:      "/v1/models/load",
    unload:    (id) => `/v1/models/${encodeURIComponent(id)}`,
    chat:      "/v1/chat/completions",
    search:    "/v1/search",
  };
  const DEFAULT_SAMPLING = {
    temperature: 0.8,
    top_p: 0.95,
    top_k: 40,
    max_tokens: 512,
    context_window: 4096,  // applied at model load
    stream: true,
    seed: 0,               // 0 = random
  };
  // Kept in sync with Prefs.kt on the Android side.
  // Sentinel header the server uses to recognise our bundled web UI and
  // skip the Bearer-key check. Harmless for external clients to ignore.
  const UI_HEADERS = { "X-Vicino-UI": "1" };
  const LINKS = {
    source:  "https://github.com/angolo40/vicino-llm",
  };
  // Tip jar addresses — shown in About so users can copy/scan them directly
  // without going through a centralised payment processor.
  const CRYPTO_ADDRESSES = [
    {
      symbol: "XMR",
      label:  "Monero",
      address: "87pT82pEGVxXD7Cv1wKrm5eD1r1J1cpBTXBtFKagAkzUQtpfK4WiuRdUB5RyPzwiNwMCK37r161JXDueNfkzYDma7rAq7ya",
      // Used as wallet-friendly URI in the QR payload so most wallets can
      // open it directly instead of just reading a raw string.
      uriPrefix: "monero:",
    },
    {
      symbol: "LTC",
      label:  "Litecoin",
      address: "ltc1qhzj2hvfpu8vtex22mzccamhr5utzdw34946a40",
      uriPrefix: "litecoin:",
    },
  ];
  // Kept for back-compat with any inline references.
  const XMR_ADDRESS = CRYPTO_ADDRESSES[0].address;
  // NOTE: "edgellm.*" keys kept from the pre-rename app — renaming them
  // now would orphan every existing user's saved chats / settings in
  // localStorage. The name of the constants is what matters, not the
  // storage string.
  const STORAGE_KEY_CHATS    = "edgellm.chats";
  const STORAGE_KEY_ACTIVE   = "edgellm.active";
  const STORAGE_KEY_SAMPLING = "edgellm.sampling";
  const STORAGE_KEY_SYSTEM   = "edgellm.system";
  const STORAGE_KEY_THEME    = "edgellm.theme";
  const STORAGE_KEY_MODEL    = "edgellm.model";

  // =========================================================================
  // state
  // =========================================================================
  /** @type {{id:string,title:string,messages:{role:string,content:string,stats?:string}[]}[]} */
  let chats = [];
  let activeChatId = null;
  let sampling = { ...DEFAULT_SAMPLING };
  let currentModel = null;
  let availableModels = [];
  let abortController = null;

  // =========================================================================
  // helpers
  // =========================================================================
  // uuid() requires a secure context (HTTPS). On plain-HTTP LAN
  // (our default use case) it's undefined. Fall back to a simple UUIDv4.
  function uuid() {
    if (globalThis.crypto?.randomUUID) return uuid();
    if (globalThis.crypto?.getRandomValues) {
      const b = new Uint8Array(16);
      crypto.getRandomValues(b);
      b[6] = (b[6] & 0x0f) | 0x40;
      b[8] = (b[8] & 0x3f) | 0x80;
      const h = Array.from(b, x => x.toString(16).padStart(2, "0"));
      return `${h.slice(0,4).join("")}-${h.slice(4,6).join("")}-${h.slice(6,8).join("")}-${h.slice(8,10).join("")}-${h.slice(10,16).join("")}`;
    }
    // Last resort, non-cryptographic.
    return "id-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 10);
  }

  // =========================================================================
  // storage
  // =========================================================================
  function load() {
    try {
      chats = JSON.parse(localStorage.getItem(STORAGE_KEY_CHATS) || "[]");
      activeChatId = localStorage.getItem(STORAGE_KEY_ACTIVE);
      sampling = { ...DEFAULT_SAMPLING, ...(JSON.parse(localStorage.getItem(STORAGE_KEY_SAMPLING) || "{}")) };
    } catch { chats = []; }
    if (!chats.length) createChat(false);
    else if (!activeChatId || !chats.find(c => c.id === activeChatId)) activeChatId = chats[0].id;
  }
  function saveChats()    { localStorage.setItem(STORAGE_KEY_CHATS, JSON.stringify(chats)); }
  function saveActive()   { localStorage.setItem(STORAGE_KEY_ACTIVE, activeChatId); }
  function saveSampling() { localStorage.setItem(STORAGE_KEY_SAMPLING, JSON.stringify(sampling)); }

  // =========================================================================
  // chat lifecycle
  // =========================================================================
  function createChat(render = true) {
    const chat = { id: uuid(), title: "New chat", messages: [] };
    chats.unshift(chat);
    activeChatId = chat.id;
    saveChats(); saveActive();
    if (render) { renderChatList(); renderMessages(); }
    return chat;
  }
  function deleteChat(id) {
    chats = chats.filter(c => c.id !== id);
    if (!chats.length) createChat(false);
    if (activeChatId === id) activeChatId = chats[0].id;
    saveChats(); saveActive();
    renderChatList(); renderMessages();
  }
  function renameChat(id, title) {
    const c = chats.find(c => c.id === id);
    if (!c) return;
    c.title = title || "Untitled";
    saveChats(); renderChatList();
  }
  function activeChat() { return chats.find(c => c.id === activeChatId); }

  function selectChat(id) {
    activeChatId = id; saveActive();
    renderChatList(); renderMessages();
  }

  // Derive a short title from the first user message. When the message is
  // multimodal `content` is an array of OpenAI parts — pull the first text
  // part if any, else fall back to a generic "Image" / "Attachment" label.
  function autoTitle(chat) {
    if (chat.title && chat.title !== "New chat") return;
    const first = chat.messages.find(m => m.role === "user");
    if (!first) return;
    const raw = typeof first.content === "string"
      ? first.content
      : (Array.isArray(first.content)
          ? (first.content.find(p => p?.type === "text")?.text ?? "Image")
          : "");
    if (!raw) return;
    chat.title = raw.slice(0, 40).replace(/\s+/g, " ").trim() + (raw.length > 40 ? "…" : "");
    saveChats(); renderChatList();
  }

  // =========================================================================
  // markdown mini-parser
  // Supports: headers (# .. ###), **bold**, *italic*, `inline code`,
  // ```fenced code blocks``` with lang, lists (- / * / 1.), blockquotes (> ),
  // tables (| a | b |), horizontal rules (---), line breaks.
  // Deliberately not a full CommonMark — covers 98 % of chat output and
  // stays under ~120 lines.
  // =========================================================================
  function escapeHtml(s) {
    return s.replace(/[&<>"']/g, ch => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[ch]));
  }

  function renderMarkdown(md) {
    if (!md) return "";
    // First pull out fenced code blocks so other rules don't touch them.
    const fences = [];
    md = md.replace(/```([\w+-]*)\n([\s\S]*?)```/g, (_, lang, code) => {
      const idx = fences.push({ lang: lang.trim(), code }) - 1;
      return `\u0000FENCE${idx}\u0000`;
    });

    // Inline code.
    const inlineCodes = [];
    md = md.replace(/`([^`\n]+)`/g, (_, code) => {
      const idx = inlineCodes.push(code) - 1;
      return `\u0000INLINE${idx}\u0000`;
    });

    // Escape everything else.
    md = escapeHtml(md);

    // Headers.
    md = md.replace(/^### (.*)$/gm, "<h3>$1</h3>");
    md = md.replace(/^## (.*)$/gm, "<h2>$1</h2>");
    md = md.replace(/^# (.*)$/gm, "<h1>$1</h1>");

    // Horizontal rules.
    md = md.replace(/^---+\s*$/gm, "<hr/>");

    // Blockquotes (single-level).
    md = md.replace(/^>\s?(.*)$/gm, "<blockquote>$1</blockquote>");

    // Tables: | a | b |\n|---|---|\n| 1 | 2 |
    md = md.replace(/(?:^\|[^\n]+\|\n)+/gm, block => {
      const rows = block.trim().split("\n");
      if (rows.length < 2 || !/^\|[-:\s|]+\|$/.test(rows[1])) return block;
      const head = rows[0].split("|").slice(1, -1).map(s => `<th>${s.trim()}</th>`).join("");
      const body = rows.slice(2).map(r => {
        const cells = r.split("|").slice(1, -1).map(s => `<td>${s.trim()}</td>`).join("");
        return `<tr>${cells}</tr>`;
      }).join("");
      return `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
    });

    // Lists. Walk line-by-line grouping consecutive list items.
    const lines = md.split("\n");
    const out = [];
    let listType = null;
    const flushList = () => { if (listType) { out.push(`</${listType}>`); listType = null; } };
    for (let raw of lines) {
      const ul = raw.match(/^\s*[-*]\s+(.*)$/);
      const ol = raw.match(/^\s*\d+\.\s+(.*)$/);
      if (ul) {
        if (listType !== "ul") { flushList(); out.push("<ul>"); listType = "ul"; }
        out.push(`<li>${ul[1]}</li>`);
      } else if (ol) {
        if (listType !== "ol") { flushList(); out.push("<ol>"); listType = "ol"; }
        out.push(`<li>${ol[1]}</li>`);
      } else {
        flushList();
        out.push(raw);
      }
    }
    flushList();
    md = out.join("\n");

    // Bold / italic / links (order matters).
    md = md.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
    md = md.replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>");
    md = md.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');

    // Paragraph breaks on blank lines. Skip within block tags.
    const paragraphs = md.split(/\n{2,}/).map(block => {
      if (/^\s*<(h\d|ul|ol|pre|table|blockquote|hr)/.test(block.trim())) return block;
      return `<p>${block.replace(/\n/g, "<br/>")}</p>`;
    }).join("\n");
    md = paragraphs;

    // Restore inline code.
    md = md.replace(/\u0000INLINE(\d+)\u0000/g, (_, i) => `<code>${escapeHtml(inlineCodes[+i])}</code>`);

    // Restore code fences (highlight if hljs is available).
    md = md.replace(/\u0000FENCE(\d+)\u0000/g, (_, i) => {
      const f = fences[+i];
      let highlighted = escapeHtml(f.code);
      if (window.hljs) {
        try {
          highlighted = f.lang && hljs.getLanguage(f.lang)
            ? hljs.highlight(f.code, { language: f.lang, ignoreIllegals: true }).value
            : hljs.highlightAuto(f.code).value;
        } catch { /* fall back to escaped */ }
      }
      return `<pre><button class="copy-btn" data-code>${f.lang || "code"}</button><code class="hljs ${f.lang ? "language-" + f.lang : ""}">${highlighted}</code></pre>`;
    });

    return md;
  }

  // =========================================================================
  // DOM refs
  // =========================================================================
  const $ = sel => document.querySelector(sel);
  const messagesEl    = $("#messages");
  const composerEl    = $("#composerInput");
  const sendBtn       = $("#sendBtn");
  const stopBtn       = $("#stopBtn");
  const newChatBtn    = $("#newChatBtn");
  const chatListEl    = $("#chatList");
  const modelSelect   = $("#modelSelect");
  const healthLine    = $("#healthLine");
  const systemPromptInput = $("#systemPromptInput");
  const settingsBtn   = $("#settingsBtn");
  const settingsModal = $("#settingsModal");
  const exportBtn     = $("#exportBtn");
  const exportDialog  = $("#exportDialog");
  const sidebarToggle = $("#sidebarToggle");
  const sidebarBackdrop = $("#sidebarBackdrop");
  const layoutEl      = $("#app");
  const attachBtn     = $("#attachBtn");
  const imagePicker   = $("#imagePicker");
  const attachmentsBar = $("#attachmentsBar");
  const aboutBtn      = $("#aboutBtn");
  const aboutModal    = $("#aboutModal");
  const modelsBtn     = $("#modelsBtn");
  const modelsModal   = $("#modelsModal");
  const modelsList    = $("#modelsList");
  const modelsCount   = $("#modelsCount");
  const modelsRefresh = $("#modelsRefresh");
  const loadBackend   = $("#loadBackend");

  // =========================================================================
  // rendering
  // =========================================================================
  function renderChatList() {
    chatListEl.innerHTML = "";
    for (const c of chats) {
      const li = document.createElement("li");
      if (c.id === activeChatId) li.classList.add("active");

      const title = document.createElement("span");
      title.className = "chat-list__title";
      title.textContent = c.title;
      title.onclick = () => selectChat(c.id);

      const actions = document.createElement("span");
      actions.className = "chat-list__actions";

      const renameBtn = document.createElement("button");
      renameBtn.title = "Rename";
      renameBtn.innerHTML = "✎";
      renameBtn.onclick = e => {
        e.stopPropagation();
        const newTitle = prompt("Rename chat", c.title);
        if (newTitle !== null) renameChat(c.id, newTitle);
      };

      const delBtn = document.createElement("button");
      delBtn.title = "Delete";
      delBtn.innerHTML = "🗑";
      delBtn.onclick = e => {
        e.stopPropagation();
        if (confirm(`Delete "${c.title}"?`)) deleteChat(c.id);
      };

      actions.append(renameBtn, delBtn);
      li.append(title, actions);
      chatListEl.append(li);
    }
  }

  function renderMessages() {
    const chat = activeChat();
    messagesEl.innerHTML = "";
    if (!chat) return;
    for (const m of chat.messages) appendMessageDom(m);
    scrollToBottom();
  }

  // Small inline-SVG factory — each icon is a single path, ~150 bytes each.
  function icon(name) {
    const paths = {
      copy: '<rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
      regen: '<polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>',
      info: '<circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/>',
    };
    return `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${paths[name]}</svg>`;
  }

  function appendMessageDom(m) {
    const wrap = document.createElement("article");
    wrap.className = "msg";
    wrap.dataset.role = m.role;

    const role = document.createElement("div");
    role.className = "msg__role";
    const roleName = { user: "you", assistant: "assistant", error: "error" }[m.role] || m.role;
    role.textContent = roleName;

    const bubble = document.createElement("div");
    bubble.className = "msg__bubble";
    // Content may be a plain string or an array of OpenAI parts. Users see
    // the text portion rendered + thumbnails. Assistant replies are always
    // text, so markdown-render them.
    if (m.role === "user") {
      const text = typeof m.content === "string"
        ? m.content
        : (Array.isArray(m.content)
            ? m.content.filter(p => p.type === "text").map(p => p.text).join("\n")
            : "");
      bubble.innerHTML = escapeHtml(text);
      // Prefer the locally-stored previews; fall back to scanning the
      // content array so reloaded conversations still show thumbnails.
      // Never combine both — the same image lives in both places for the
      // current session, which would render it twice.
      const thumbs = (m._attachments && m._attachments.length)
        ? m._attachments
        : (Array.isArray(m.content)
            ? m.content.filter(p => p.type === "image_url" && p.image_url?.url)
                .map(p => ({ dataUrl: p.image_url.url, name: "image" }))
            : []);
      if (thumbs.length) {
        const wrap = document.createElement("div");
        wrap.style.cssText = "display:flex;gap:6px;flex-wrap:wrap;margin-top:8px";
        thumbs.forEach(t => {
          const img = document.createElement("img");
          img.src = t.dataUrl;
          img.alt = t.name || "";
          img.style.cssText = "max-width:180px;max-height:180px;border-radius:8px";
          wrap.append(img);
        });
        bubble.append(wrap);
      }
    } else {
      const textOut = typeof m.content === "string" ? m.content : "";
      bubble.innerHTML = renderMarkdown(textOut);
      if (m._sources) appendSourcesBlock(bubble, m._sources);
    }

    const actions = document.createElement("div");
    actions.className = "msg__actions";

    const copyBtn = document.createElement("button");
    copyBtn.title = "Copy";
    copyBtn.setAttribute("aria-label", "Copy");
    copyBtn.innerHTML = icon("copy");
    const copyToast = document.createElement("span");
    copyToast.className = "msg__toast";
    copyBtn.onclick = async () => {
      const ok = await copyText(m.content);
      copyToast.textContent = ok ? "copied" : "copy failed";
      copyToast.classList.add("show");
      setTimeout(() => copyToast.classList.remove("show"), ok ? 900 : 1500);
    };
    actions.append(copyBtn);

    if (m.role === "assistant") {
      const regenBtn = document.createElement("button");
      regenBtn.title = "Regenerate";
      regenBtn.setAttribute("aria-label", "Regenerate");
      regenBtn.innerHTML = icon("regen");
      regenBtn.onclick = () => regenerate();
      actions.append(regenBtn);
    }

    const infoBtn = document.createElement("button");
    infoBtn.title = "Info";
    infoBtn.setAttribute("aria-label", "Info");
    infoBtn.innerHTML = icon("info");
    const statsEl = document.createElement("span");
    statsEl.className = "msg__stats";
    if (m.stats) statsEl.textContent = m.stats;
    infoBtn.onclick = () => {
      const on = statsEl.classList.toggle("show");
      infoBtn.classList.toggle("is-active", on);
    };
    actions.append(infoBtn, statsEl, copyToast);

    wrap.append(role, bubble, actions);
    messagesEl.append(wrap);
    wireCopyButtons(bubble);
    return wrap;
  }

  function wireCopyButtons(scope) {
    scope.querySelectorAll(".copy-btn[data-code]").forEach(btn => {
      btn.onclick = async () => {
        const code = btn.parentElement.querySelector("code")?.innerText ?? "";
        await copyText(code);
        const was = btn.textContent;
        btn.textContent = "copied";
        setTimeout(() => { btn.textContent = was; }, 900);
      };
    });
  }

  // Treat "near the bottom" generously so autoscroll stays engaged when the
  // text stream nudges the layout by a few pixels.
  const STICK_TOLERANCE_PX = 80;
  let stickToBottom = true;

  function isAtBottom() {
    return messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight < STICK_TOLERANCE_PX;
  }

  function scrollToBottom(force = false) {
    if (force || stickToBottom) {
      messagesEl.scrollTop = messagesEl.scrollHeight;
    }
  }

  // Disengage autoscroll as soon as the user scrolls up; re-engage when they
  // come back near the bottom. Matches the UX of most chat apps.
  function wireScrollStick() {
    messagesEl.addEventListener("scroll", () => {
      stickToBottom = isAtBottom();
    }, { passive: true });
  }

  function setSending(isSending) {
    sendBtn.hidden = isSending;
    stopBtn.hidden = !isSending;
    composerEl.disabled = isSending;
  }

  // =========================================================================
  // attachments (vision)
  // =========================================================================
  /** @type {{dataUrl:string, mime:string, name:string}[]} */
  let pendingAttachments = [];

  function renderAttachments() {
    if (!pendingAttachments.length) {
      attachmentsBar.hidden = true;
      attachmentsBar.innerHTML = "";
      return;
    }
    attachmentsBar.hidden = false;
    attachmentsBar.innerHTML = "";
    pendingAttachments.forEach((att, i) => {
      const chip = document.createElement("span");
      chip.className = "attachment-chip";
      if (att.kind === "audio") {
        // Audio chip: icon + name + remove button (no visual preview).
        chip.innerHTML = `<span class="attachment-chip__audio" aria-hidden="true">🎵</span><span>${att.name}</span><button aria-label="Remove">×</button>`;
      } else {
        chip.innerHTML = `<img src="${att.dataUrl}" alt=""><span>${att.name}</span><button aria-label="Remove">×</button>`;
      }
      chip.querySelector("button").onclick = () => {
        pendingAttachments.splice(i, 1);
        renderAttachments();
      };
      attachmentsBar.append(chip);
    });
  }

  // Render a tabbed tip-jar UI: one pill-tab per crypto at the top, one
  // active panel below (QR + address + copy). Much less vertical space
  // than 3 stacked cards, and the QR draws bigger.
  function renderCryptoCards() {
    const host = document.getElementById("cryptoCards");
    if (!host) return;
    host.innerHTML = `
      <div class="crypto-tabs" role="tablist"></div>
      <div class="crypto-panel"></div>
    `;
    const tabBar = host.querySelector(".crypto-tabs");
    const panel  = host.querySelector(".crypto-panel");

    const qrDataUrl = (payload) => {
      try {
        if (!window.qrcode) return "";
        const qr = window.qrcode(0, "M");
        qr.addData(payload);
        qr.make();
        return qr.createDataURL(5, 2);
      } catch (e) {
        console.warn("QR render failed:", e);
        return "";
      }
    };

    const showIndex = (idx) => {
      tabBar.querySelectorAll(".crypto-tab").forEach((t, i) => {
        t.classList.toggle("is-active", i === idx);
        t.setAttribute("aria-selected", i === idx ? "true" : "false");
      });
      const entry = CRYPTO_ADDRESSES[idx];
      const img = qrDataUrl((entry.uriPrefix || "") + entry.address);
      panel.innerHTML = `
        <div class="crypto-panel__label">${entry.label}</div>
        ${img ? `<img class="crypto-panel__qr" src="${img}" alt="${entry.symbol} QR code" />` : ""}
        <code class="crypto-panel__addr"></code>
        <button class="btn btn--sm crypto-panel__copy" type="button">Copy address</button>
      `;
      panel.querySelector(".crypto-panel__addr").textContent = entry.address;
      const btn = panel.querySelector(".crypto-panel__copy");
      btn.onclick = async () => {
        const ok = await copyText(entry.address);
        const was = btn.textContent;
        btn.textContent = ok ? "Copied" : "Failed";
        setTimeout(() => { btn.textContent = was; }, 1200);
      };
    };

    CRYPTO_ADDRESSES.forEach((entry, i) => {
      const tab = document.createElement("button");
      tab.type = "button";
      tab.className = "crypto-tab";
      tab.setAttribute("role", "tab");
      tab.textContent = entry.symbol;
      tab.onclick = () => showIndex(i);
      tabBar.appendChild(tab);
    });
    showIndex(0);
  }

  // navigator.clipboard is gated behind secure contexts (HTTPS/localhost).
  // The device serves the UI over plain http://<lan-ip>:8080 so the modern
  // API throws "not allowed" — fall back to a hidden textarea + execCommand.
  async function copyText(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch {}
    try {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.setAttribute("readonly", "");
      ta.style.position = "fixed";
      ta.style.top = "-9999px";
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand("copy");
      document.body.removeChild(ta);
      return ok;
    } catch { return false; }
  }

  function readFileAsDataUrl(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(file);
    });
  }

  // Gemma 4 vision preprocessor resizes every input to 768x768 internally.
  // Shipping full-size photos forces the native pipeline to decode+resize
  // big bitmaps, which briefly doubles the peak RAM footprint and can get
  // the foreground service killed for mem-pressure. Downscale here instead.
  async function resizeImageToDataUrl(file, maxEdge = 768) {
    const origUrl = await readFileAsDataUrl(file);
    const img = new Image();
    await new Promise((ok, ko) => { img.onload = ok; img.onerror = ko; img.src = origUrl; });
    const { width: w, height: h } = img;
    if (Math.max(w, h) <= maxEdge) return origUrl;
    const scale = maxEdge / Math.max(w, h);
    const cw = Math.round(w * scale), ch = Math.round(h * scale);
    const canvas = document.createElement("canvas");
    canvas.width = cw; canvas.height = ch;
    const ctx = canvas.getContext("2d");
    ctx.drawImage(img, 0, 0, cw, ch);
    return canvas.toDataURL("image/jpeg", 0.9);
  }

  // Gemma 4 caps at maxNumImages=4 in EngineConfig — rendering more pages
  // would be silently dropped by the native preprocessor.
  const MAX_IMAGES_PER_REQUEST = 4;

  async function renderPdfPagesToDataUrls(file, maxPages = MAX_IMAGES_PER_REQUEST) {
    const lib = window.pdfjsLib || await (window.pdfjsReady || Promise.reject(new Error("PDF support unavailable")));
    const buf = await file.arrayBuffer();
    const doc = await lib.getDocument({ data: buf }).promise;
    const totalPages = doc.numPages;
    const take = Math.min(totalPages, maxPages);
    const out = [];
    for (let i = 1; i <= take; i++) {
      const page = await doc.getPage(i);
      // Gemma 4 vision target is 768x768. Render at a viewport scaled so the
      // longer edge ~= 768 — any larger just wastes bytes and RAM.
      const base = page.getViewport({ scale: 1 });
      const scale = 768 / Math.max(base.width, base.height);
      const viewport = page.getViewport({ scale });
      const canvas = document.createElement("canvas");
      canvas.width = Math.round(viewport.width);
      canvas.height = Math.round(viewport.height);
      const ctx = canvas.getContext("2d");
      // White background — PDFs often rely on the page color, and JPEG
      // doesn't carry alpha.
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      await page.render({ canvasContext: ctx, viewport }).promise;
      out.push(canvas.toDataURL("image/jpeg", 0.9));
    }
    return { pages: out, totalPages };
  }

  // Push-to-talk: press and hold the mic button to record, release to attach.
  // We use MediaRecorder with whatever mime the browser supports — on
  // Chrome/WebView that's almost always audio/webm;codecs=opus or
  // audio/mp4. We pass the raw format suffix to the server and let the
  // SDK's decoder handle it (Gemma 4 audio is routed through ffmpeg-like
  // preprocessing server-side).
  function lastUserTextOf(chat) {
    for (let i = chat.messages.length - 1; i >= 0; i--) {
      const m = chat.messages[i];
      if (m.role !== "user") continue;
      if (typeof m.content === "string") return m.content;
      if (Array.isArray(m.content)) {
        const t = m.content.find(p => p?.type === "text");
        return t?.text || "";
      }
    }
    return "";
  }

  // Whitelist of URL schemes allowed in source links from SearXNG. Without
  // this guard a compromised or malicious SearXNG instance could inject a
  // `javascript:` URL that runs in our web UI context when the user clicks
  // the source — full access to localStorage and every open chat.
  function safeHttpUrl(raw) {
    if (typeof raw !== "string") return null;
    try {
      const u = new URL(raw, location.origin);
      if (u.protocol !== "http:" && u.protocol !== "https:") return null;
      return u.href;
    } catch { return null; }
  }

  function appendSourcesBlock(bubble, sources) {
    if (!sources?.results?.length) return;
    const wrap = document.createElement("details");
    wrap.className = "sources";
    const summary = document.createElement("summary");
    summary.textContent = `Sources (${sources.results.length})`;
    wrap.appendChild(summary);
    sources.results.forEach((r, i) => {
      const safeUrl = safeHttpUrl(r.url);
      const row = document.createElement("div");
      row.className = "sources__row";
      if (safeUrl) {
        const a = document.createElement("a");
        a.href = safeUrl;
        a.target = "_blank";
        a.rel = "noopener noreferrer";
        a.textContent = `[${i + 1}] ${r.title || safeUrl}`;
        row.appendChild(a);
      } else {
        // Non-http(s) payload: render inert as text so the user can still
        // see the title without risking an `a:href` injection.
        const span = document.createElement("span");
        span.textContent = `[${i + 1}] ${r.title || "(blocked non-http URL)"}`;
        row.appendChild(span);
      }
      if (r.snippet) {
        const p = document.createElement("div");
        p.className = "sources__snip";
        p.textContent = r.snippet.length > 160 ? r.snippet.slice(0, 160) + "…" : r.snippet;
        row.appendChild(p);
      }
      wrap.appendChild(row);
    });
    bubble.appendChild(wrap);
  }

  // Per-chat web-search state. When true, the next send will ask the server
  // to augment the prompt with SearXNG results AND do a parallel fetch here
  // so we can render the source links below the reply. The button shows/hides
  // itself based on whether the server has a SearXNG URL configured.
  let webSearchEnabled = false;
  let lastWebSearchSources = null;  // {query, results[]} for the current send

  async function probeWebSearch() {
    const btn = document.getElementById("webSearchBtn");
    if (!btn) return;
    try {
      // Probe with a dummy query — 503 = not configured, 200 = reachable,
      // anything else = reachable-but-broken which we still expose so the
      // user can see the Settings test.
      const res = await fetch(`${API.search}?q=ping&n=1`, { headers: UI_HEADERS });
      if (res.status !== 503) btn.hidden = false;
    } catch { /* no-op */ }
  }

  function wireWebSearchToggle() {
    const btn = document.getElementById("webSearchBtn");
    if (!btn) return;
    probeWebSearch();
    btn.addEventListener("click", () => {
      webSearchEnabled = !webSearchEnabled;
      btn.classList.toggle("is-active", webSearchEnabled);
      btn.title = webSearchEnabled
        ? "Web search ON — next reply will use live SearXNG results"
        : "Web search (SearXNG) — toggle to augment next reply";
    });
  }

  async function fetchSourcesForLastUserMessage(text) {
    if (!text) return null;
    try {
      const res = await fetch(`${API.search}?q=${encodeURIComponent(text)}&n=5`, {
        headers: UI_HEADERS,
      });
      if (!res.ok) return null;
      return await res.json();  // {query, results:[{title,snippet,url}]}
    } catch { return null; }
  }

  function wireMicPushToTalk() {
    const btn = document.getElementById("micBtn");
    if (!btn) return;
    let mediaRecorder = null;
    let chunks = [];
    let stream = null;
    let recording = false;

    const stop = async (cancel = false) => {
      if (!recording) return;
      recording = false;
      btn.classList.remove("is-recording");
      try {
        if (mediaRecorder && mediaRecorder.state !== "inactive") {
          mediaRecorder.stop();
        }
      } catch {}
      // Let the final dataavailable event fire before releasing tracks.
      await new Promise(r => setTimeout(r, 50));
      stream?.getTracks().forEach(t => t.stop());
      stream = null;
      if (cancel) { chunks = []; mediaRecorder = null; return; }
    };

    const start = async () => {
      if (recording) return;
      if (!navigator.mediaDevices?.getUserMedia) {
        // Most browsers gate getUserMedia behind secure contexts — plain
        // http://<lan-ip> fails on desktop Chrome. Point the user at a
        // workable alternative (attach a file) rather than a silent no-op.
        alert(
          !window.isSecureContext
            ? "Microphone requires HTTPS or localhost. This server runs over plain HTTP on LAN — record with your phone's voice memo app and use Attach instead."
            : "Microphone not available in this browser."
        );
        return;
      }
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      } catch (e) {
        alert("Microphone permission denied: " + (e.message || e));
        return;
      }
      // Pick the most broadly-supported mime: prefer opus-in-webm.
      const mime =
        (MediaRecorder.isTypeSupported?.("audio/webm;codecs=opus") && "audio/webm;codecs=opus") ||
        (MediaRecorder.isTypeSupported?.("audio/webm") && "audio/webm") ||
        (MediaRecorder.isTypeSupported?.("audio/mp4") && "audio/mp4") ||
        "";
      chunks = [];
      mediaRecorder = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream);
      mediaRecorder.ondataavailable = e => { if (e.data.size > 0) chunks.push(e.data); };
      mediaRecorder.onstop = async () => {
        if (chunks.length === 0) return;
        const blob = new Blob(chunks, { type: mediaRecorder.mimeType || "audio/webm" });
        chunks = [];
        if (blob.size < 1024) return;  // too short, ignore (<0.5s clicks)
        const dataUrl = await new Promise((ok, ko) => {
          const r = new FileReader();
          r.onload = () => ok(r.result);
          r.onerror = () => ko(r.error);
          r.readAsDataURL(blob);
        });
        const stamp = new Date().toISOString().replace(/[:.]/g, "-").slice(11, 19);
        pendingAttachments.push({
          dataUrl,
          mime: blob.type,
          name: `mic-${stamp}`,
          kind: "audio",
        });
        renderAttachments();
      };
      recording = true;
      btn.classList.add("is-recording");
      mediaRecorder.start();
    };

    // Pointer events cover mouse + touch + pen uniformly. Keep pointerleave
    // as a safety net so a drag-off doesn't leave the mic hot.
    btn.addEventListener("pointerdown", e => { e.preventDefault(); start(); });
    btn.addEventListener("pointerup",    e => { e.preventDefault(); stop(); });
    btn.addEventListener("pointerleave", () => stop());
    btn.addEventListener("pointercancel", () => stop());
    // Block the native context menu on long-press so the page stays usable.
    btn.addEventListener("contextmenu", e => e.preventDefault());
  }

  // True while a PDF render / image resize / audio load is in progress.
  // sendCurrent() checks this flag so a user who hits Enter mid-processing
  // doesn't send a message with a half-built attachment list (the PDF case
  // could actually mutate pendingAttachments AFTER send had cleared it).
  let isProcessingAttachment = false;

  async function handleImagePick(file) {
    if (!file) return;
    const isPdf = file.type === "application/pdf" || /\.pdf$/i.test(file.name);
    const isAudio = file.type.startsWith("audio/") || /\.(wav|mp3|ogg|flac|m4a|aac)$/i.test(file.name);
    const isImage = file.type.startsWith("image/");
    if (!isImage && !isPdf && !isAudio) {
      alert("Only images, PDF and audio files are supported.");
      return;
    }
    isProcessingAttachment = true;
    updateSendButtonState();
    try {
      if (isPdf) {
        const remaining = MAX_IMAGES_PER_REQUEST - pendingAttachments.length;
        if (remaining <= 0) {
          alert(`Maximum ${MAX_IMAGES_PER_REQUEST} pages per message.`);
          return;
        }
        const { pages, totalPages } = await renderPdfPagesToDataUrls(file, remaining);
        pages.forEach((dataUrl, i) => {
          pendingAttachments.push({
            dataUrl,
            mime: "image/jpeg",
            name: `${file.name} · p${i + 1}`,
            kind: "image",
          });
        });
        if (totalPages > pages.length) {
          alert(`PDF has ${totalPages} pages — only the first ${pages.length} were attached (model limit).`);
        }
        renderAttachments();
        return;
      }
      if (isAudio) {
        // 25 MB is the OpenAI compat ceiling; LiteRT-LM-0.10 is comfortable
        // with short clips (<=60s). Warn before sending something crazy.
        if (file.size > 25 * 1024 * 1024) {
          alert("Audio file too large (>25 MB). Trim it first.");
          return;
        }
        const dataUrl = await readFileAsDataUrl(file);
        pendingAttachments.push({
          dataUrl,
          mime: file.type || "audio/wav",
          name: file.name,
          kind: "audio",
        });
        renderAttachments();
        return;
      }
      const dataUrl = await resizeImageToDataUrl(file, 768);
      pendingAttachments.push({ dataUrl, mime: "image/jpeg", name: file.name, kind: "image" });
      renderAttachments();
    } catch (e) {
      alert("Failed to read file: " + (e.message || e));
    } finally {
      isProcessingAttachment = false;
      updateSendButtonState();
    }
  }

  // Keep the Send button disabled while an attachment is still being
  // prepared. Cheap to call from both the attachment flow and setSending().
  function updateSendButtonState() {
    const btn = document.getElementById("sendBtn");
    if (!btn) return;
    btn.disabled = isProcessingAttachment;
  }

  // =========================================================================
  // streaming send
  // =========================================================================
  async function sendCurrent() {
    const chat = activeChat();
    if (!chat) return;
    // Block send while a PDF / image resize / audio load is still running.
    // Without this, the attachment pipeline can mutate pendingAttachments
    // after we've snapshotted and cleared it below — the message ends up
    // half-built on the server.
    if (isProcessingAttachment) return;
    const text = composerEl.value.trim();
    if (!text && !pendingAttachments.length) return;

    // Build the message. If there are attachments, the content becomes an
    // OpenAI-style multimodal array; otherwise a plain string for
    // back-compat with simple display code.
    const userMsg = pendingAttachments.length === 0
      ? { role: "user", content: text }
      : {
          role: "user",
          content: [
            ...(text ? [{ type: "text", text }] : []),
            ...pendingAttachments.map(a => {
              // Split the data URL so the OpenAI-style input_audio object can
              // carry pure base64 + a format hint — images keep the full URL
              // form that image_url expects.
              if (a.kind === "audio") {
                const comma = a.dataUrl.indexOf(",");
                const b64 = comma >= 0 ? a.dataUrl.slice(comma + 1) : a.dataUrl;
                const fmt = (a.mime?.split("/")[1] || "wav").toLowerCase();
                return { type: "input_audio", input_audio: { data: b64, format: fmt } };
              }
              return { type: "image_url", image_url: { url: a.dataUrl } };
            }),
          ],
          // Stash the display-friendly previews so renderMessages can
          // show thumbnails after a reload.
          _attachments: pendingAttachments.map(a => ({
            dataUrl: a.dataUrl,
            name: a.name,
            kind: a.kind || "image",
          })),
        };

    chat.messages.push(userMsg);
    appendMessageDom(userMsg);
    composerEl.value = "";
    pendingAttachments = [];
    renderAttachments();
    resizeComposer();
    autoTitle(chat);
    saveChats();
    stickToBottom = true;
    scrollToBottom(true);

    await streamAssistantReply(chat);
  }

  async function regenerate() {
    const chat = activeChat();
    if (!chat || !chat.messages.length) return;
    // Remove the last assistant message (if any) and re-generate.
    while (chat.messages.length && chat.messages[chat.messages.length - 1].role === "assistant") {
      chat.messages.pop();
    }
    saveChats();
    renderMessages();
    await streamAssistantReply(chat);
  }

  async function streamAssistantReply(chat) {
    const sys = systemPromptInput.value.trim();
    const messages = [];
    if (sys) messages.push({ role: "system", content: sys });
    // Forward each stored message verbatim (content may be string OR array
    // of OpenAI parts for multimodal input). We also strip locally-kept
    // UI-only fields like _attachments before sending.
    messages.push(...chat.messages.map(({ role, content }) => ({ role, content })));

    const asstMsg = { role: "assistant", content: "" };
    chat.messages.push(asstMsg);
    const dom = appendMessageDom(asstMsg);
    const bubble = dom.querySelector(".msg__bubble");
    bubble.innerHTML = webSearchEnabled
      ? `<span class="search-chip">🌐 searching…</span><span class="cursor"></span>`
      : `<span class="cursor"></span>`;

    abortController = new AbortController();
    setSending(true);
    const t0 = performance.now();
    let completionTokens = 0;
    let sources = null;

    // Run the SearXNG call in parallel with the chat POST (server will do
    // its own lookup for prompt augmentation — we just need the raw list to
    // render below the reply).
    const lastUserTxt = lastUserTextOf(chat);
    const sourcesPromise = webSearchEnabled
      ? fetchSourcesForLastUserMessage(lastUserTxt)
      : Promise.resolve(null);

    try {
      const res = await fetch(API.chat, {
        method: "POST",
        headers: { "content-type": "application/json", ...UI_HEADERS },
        body: JSON.stringify({
          model: currentModel || undefined,
          messages,
          stream: true,
          web_search: webSearchEnabled,
          ...sampling,
        }),
        signal: abortController.signal,
      });

      if (!res.ok) {
        const errBody = await res.text();
        // 503 + model_not_loaded means the service was killed by lmkd and
        // restarted without its weights. Surface a readable message plus a
        // hint to tap Models → Load so users aren't left staring at JSON.
        if (res.status === 503 && errBody.includes("model_not_loaded")) {
          throw new Error(
            "Model not loaded on the server (it may have been killed for memory pressure). Open Models and reload it, then try again."
          );
        }
        throw new Error(`${res.status}: ${errBody}`);
      }
      if (!res.body) throw new Error("No response body");

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        // Parse full SSE events out of the buffer (separated by blank lines).
        let idx;
        while ((idx = buffer.indexOf("\n\n")) !== -1) {
          const frame = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          handleSseFrame(frame, asstMsg, bubble, () => completionTokens++);
        }
      }
    } catch (err) {
      if (err.name === "AbortError") {
        asstMsg.content += "\n\n_(stopped)_";
      } else {
        asstMsg.role = "error";
        asstMsg.content = String(err.message || err);
        dom.dataset.role = "error";
      }
    } finally {
      sources = await sourcesPromise.catch(() => null);
      if (sources?.results?.length) asstMsg._sources = sources;
      const elapsed = (performance.now() - t0) / 1000;
      const tps = completionTokens > 0 ? (completionTokens / elapsed).toFixed(1) : "0";
      asstMsg.stats = `${completionTokens} tok · ${elapsed.toFixed(1)}s · ${tps} tok/s`;
      bubble.innerHTML = renderMarkdown(asstMsg.content);
      if (asstMsg._sources) appendSourcesBlock(bubble, asstMsg._sources);
      wireCopyButtons(bubble);
      // Update role label and stats (hidden until user clicks the ⓘ icon).
      dom.querySelector(".msg__role").textContent = asstMsg.role === "error" ? "error" : "assistant";
      const statsEl = dom.querySelector(".msg__stats");
      if (statsEl) statsEl.textContent = asstMsg.stats;
      saveChats();
      setSending(false);
      abortController = null;
      scrollToBottom();
    }
  }

  function handleSseFrame(frame, msg, bubble, onToken) {
    for (const line of frame.split("\n")) {
      if (!line.startsWith("data:")) continue;
      const payload = line.slice(5).trim();
      if (payload === "[DONE]") return;
      try {
        const obj = JSON.parse(payload);
        const delta = obj.choices?.[0]?.delta?.content;
        if (delta) {
          msg.content += delta;
          onToken?.();
          // Live-render: markdown parse each frame can be expensive but the
          // chunks are small enough. Re-parse full content each time so code
          // fences close correctly mid-stream.
          bubble.innerHTML = renderMarkdown(msg.content) + '<span class="cursor"></span>';
          scrollToBottom();
        }
      } catch { /* non-JSON frame, ignore */ }
    }
  }

  function stopStreaming() {
    abortController?.abort();
  }

  // =========================================================================
  // health + models polling
  // =========================================================================
  async function refreshModels() {
    try {
      const r = await fetch(API.models, { headers: UI_HEADERS });
      if (!r.ok) throw new Error(r.status);
      const data = await r.json();
      availableModels = (data.data || []).map(d => d.id);
      modelSelect.innerHTML = "";
      if (!availableModels.length) {
        const o = document.createElement("option");
        o.textContent = "(no model loaded)";
        o.disabled = true;
        modelSelect.append(o);
        currentModel = null;
        return;
      }
      for (const id of availableModels) {
        const o = document.createElement("option");
        o.value = id;
        o.textContent = id;
        modelSelect.append(o);
      }
      const saved = localStorage.getItem(STORAGE_KEY_MODEL);
      currentModel = availableModels.includes(saved) ? saved : availableModels[0];
      modelSelect.value = currentModel;
    } catch {
      availableModels = [];
      currentModel = null;
      modelSelect.innerHTML = '<option disabled>(server unreachable)</option>';
    }
  }

  async function refreshHealth() {
    try {
      const r = await fetch(API.health, { headers: UI_HEADERS });
      const data = await r.json();
      const b = data.backend !== "none" ? data.backend : "idle";
      const loaded = (data.loaded_models || []).length;
      healthLine.innerHTML = `<span class="ok">●</span> ${b} · ${loaded} model${loaded === 1 ? "" : "s"}`;
    } catch {
      healthLine.innerHTML = `<span class="err">●</span> server down`;
    }
  }

  // =========================================================================
  // sampling modal
  // =========================================================================
  function openSettings() {
    settingsModal.hidden = false;
    applyModalSamplingValues();
  }
  function closeSettings() { settingsModal.hidden = true; }

  function applyModalSamplingValues() {
    $("#temp").value = sampling.temperature;
    $("#topP").value = sampling.top_p;
    $("#topK").value = sampling.top_k;
    $("#maxTok").value = sampling.max_tokens;
    $("#ctx").value = sampling.context_window;
    $("#stream").checked = !!sampling.stream;
    $("#seed").value = sampling.seed ?? 0;
    updateSamplingLabels();
  }
  function updateSamplingLabels() {
    $("#tempVal").textContent = sampling.temperature.toFixed(2);
    $("#topPVal").textContent = sampling.top_p.toFixed(2);
    $("#topKVal").textContent = sampling.top_k;
    $("#maxTokVal").textContent = sampling.max_tokens;
    $("#ctxVal").textContent = `${sampling.context_window} tok`;
    $("#seedVal").textContent = sampling.seed === 0 ? "random" : String(sampling.seed);
  }

  // =========================================================================
  // export
  // =========================================================================
  function downloadFile(name, content, mime) {
    const blob = new Blob([content], { type: mime });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.click();
    URL.revokeObjectURL(a.href);
  }
  function chatAsMarkdown(chat) {
    const lines = [`# ${chat.title}`, ""];
    for (const m of chat.messages) {
      lines.push(`## ${m.role}`, "", m.content, "");
    }
    return lines.join("\n");
  }

  // =========================================================================
  // theme
  // =========================================================================
  function setTheme(t) {
    document.documentElement.dataset.theme = t;
    try { localStorage.setItem(STORAGE_KEY_THEME, t); } catch { /* quota / private mode */ }
    document.querySelectorAll(".theme-switch button").forEach(b => {
      b.classList.toggle("active", b.dataset.theme === t);
    });
  }

  function wireThemeSwitch() {
    // Explicit listener wiring so the bug where clicks did nothing can't
    // regress (previously tied to .onclick inside init() which meant a
    // missing element silently skipped the block).
    document.querySelectorAll(".theme-switch button[data-theme]").forEach(b => {
      b.addEventListener("click", () => setTheme(b.dataset.theme));
    });
  }

  // =========================================================================
  // models modal (load/unload)
  // =========================================================================
  function openModels() {
    modelsModal.hidden = false;
    refreshAvailable();
  }
  function closeModels() { modelsModal.hidden = true; }

  async function refreshAvailable() {
    modelsList.innerHTML = '<li class="models-empty">Loading…</li>';
    try {
      const r = await fetch(API.available, { headers: UI_HEADERS });
      if (!r.ok) throw new Error("HTTP " + r.status);
      const data = await r.json();
      renderAvailable(data.data || []);
    } catch (e) {
      modelsList.innerHTML = `<li class="models-empty">Error: ${escapeHtml(String(e.message || e))}</li>`;
    }
  }

  function renderAvailable(list) {
    modelsCount.textContent = `${list.length} file${list.length === 1 ? "" : "s"} on device, ${list.filter(x => x.loaded).length} loaded`;
    modelsList.innerHTML = "";
    if (!list.length) {
      modelsList.innerHTML = `<li class="models-empty">No *.litertlm / *.task files found.<br>Push one via adb.</li>`;
      return;
    }
    for (const m of list) {
      const li = document.createElement("li");
      li.className = "model-row" + (m.loaded ? " is-loaded" : "");

      const info = document.createElement("div");
      info.className = "model-row__info";
      const name = document.createElement("div");
      name.className = "model-row__name";
      name.textContent = m.id;
      if (m.loaded) {
        const tag = document.createElement("span");
        tag.className = "badge";
        tag.textContent = "loaded";
        name.appendChild(tag);
      }
      const meta = document.createElement("div");
      meta.className = "model-row__meta";
      meta.textContent = `${m.size_mib} MiB · ${m.source}`;
      info.append(name, meta);

      const btn = document.createElement("button");
      btn.className = "model-row__btn" + (m.loaded ? " model-row__btn--loaded" : "");
      btn.textContent = m.loaded ? "Unload" : "Load";
      btn.onclick = () => m.loaded ? doUnload(m.id, btn) : doLoad(m.id, btn);

      li.append(info, btn);
      modelsList.append(li);
    }
  }

  async function doLoad(id, btn) {
    btn.disabled = true;
    btn.textContent = "Loading…";
    try {
      const r = await fetch(API.load, {
        method: "POST",
        headers: { "content-type": "application/json", ...UI_HEADERS },
        body: JSON.stringify({ id, backend: loadBackend.value || "gpu" }),
      });
      if (!r.ok) {
        const body = await r.text();
        throw new Error(`HTTP ${r.status} — ${body.slice(0, 200)}`);
      }
      // Poll /v1/models until the server reports the new engine.
      for (let i = 0; i < 120; i++) { // up to ~2min
        await sleep(1000);
        const mr = await fetch(API.models, { headers: UI_HEADERS });
        const data = await mr.json();
        if ((data.data || []).some(d => d.id === id)) break;
      }
    } catch (e) {
      alert("Load failed: " + (e.message || e));
    } finally {
      await refreshAvailable();
      await refreshModels();
    }
  }

  async function doUnload(id, btn) {
    if (!confirm(`Unload "${id}"?`)) return;
    btn.disabled = true;
    btn.textContent = "Unloading…";
    try {
      const r = await fetch(API.unload(id), { method: "DELETE", headers: UI_HEADERS });
      if (!r.ok) {
        const body = await r.text();
        throw new Error(`HTTP ${r.status} — ${body.slice(0, 200)}`);
      }
    } catch (e) {
      alert("Unload failed: " + (e.message || e));
    } finally {
      await refreshAvailable();
      await refreshModels();
    }
  }

  const sleep = (ms) => new Promise(r => setTimeout(r, ms));

  // =========================================================================
  // composer auto-grow
  // =========================================================================
  function resizeComposer() {
    composerEl.style.height = "auto";
    composerEl.style.height = Math.min(composerEl.scrollHeight, 200) + "px";
  }

  // =========================================================================
  // init
  // =========================================================================
  function init() {
    const safe = (label, fn) => {
      try { fn(); } catch (e) { console.error(`[${label}]`, e); }
    };

    safe("load", load);
    safe("theme", () => setTheme(localStorage.getItem(STORAGE_KEY_THEME) || "auto"));
    safe("systemPrompt", () => { systemPromptInput.value = localStorage.getItem(STORAGE_KEY_SYSTEM) || ""; });

    safe("renderChatList", renderChatList);
    safe("renderMessages", renderMessages);
    safe("refreshModels", refreshModels);
    safe("refreshHealth", refreshHealth);
    setInterval(() => safe("refreshHealth.poll", refreshHealth), 10_000);

    // Listeners
    newChatBtn.onclick = () => {
      createChat();
      layoutEl.classList.remove("sidebar-open");  // close drawer on mobile
      composerEl.focus();
    };
    sendBtn.onclick = sendCurrent;
    stopBtn.onclick = stopStreaming;
    attachBtn.onclick = () => imagePicker.click();
    imagePicker.addEventListener("change", e => {
      const f = e.target.files?.[0];
      if (f) handleImagePick(f);
      imagePicker.value = "";
    });
    wireMicPushToTalk();
    wireWebSearchToggle();
    sidebarToggle.onclick = () => layoutEl.classList.toggle("sidebar-open");
    sidebarBackdrop.onclick = () => layoutEl.classList.remove("sidebar-open");

    wireThemeSwitch();
    wireScrollStick();

    // About modal
    $("#sourceLink").href  = LINKS.source;
    $("#aboutBaseUrl").textContent = `${location.protocol}//${location.host}/v1`;
    renderCryptoCards();
    aboutBtn.onclick = () => { aboutModal.hidden = false; };
    aboutModal.querySelector("[data-close]").onclick = () => { aboutModal.hidden = true; };
    aboutModal.addEventListener("click", e => {
      if (e.target === aboutModal) aboutModal.hidden = true;
    });

    // Models management modal
    modelsBtn.onclick = openModels;
    modelsModal.querySelector("[data-close]").onclick = closeModels;
    modelsModal.addEventListener("click", e => {
      if (e.target === modelsModal) closeModels();
    });
    modelsRefresh.onclick = refreshAvailable;

    composerEl.addEventListener("input", resizeComposer);
    composerEl.addEventListener("keydown", e => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        sendCurrent();
      }
    });

    modelSelect.onchange = () => {
      currentModel = modelSelect.value;
      localStorage.setItem(STORAGE_KEY_MODEL, currentModel);
    };

    systemPromptInput.addEventListener("input", () => {
      localStorage.setItem(STORAGE_KEY_SYSTEM, systemPromptInput.value);
    });

    settingsBtn.onclick = openSettings;
    settingsModal.querySelector("[data-close]").onclick = closeSettings;
    settingsModal.addEventListener("click", e => {
      if (e.target === settingsModal) closeSettings();
    });

    const wireSlider = (id, key, parse) => {
      const el = document.getElementById(id);
      el.oninput = () => {
        sampling[key] = parse(el.value);
        updateSamplingLabels();
        saveSampling();
      };
    };
    wireSlider("temp",   "temperature", v => +v);
    wireSlider("topP",   "top_p",       v => +v);
    wireSlider("topK",   "top_k",       v => +v);
    wireSlider("maxTok", "max_tokens",  v => +v);
    wireSlider("ctx",    "context_window", v => +v);

    $("#stream").onchange = (e) => {
      sampling.stream = e.target.checked;
      saveSampling();
    };
    $("#seed").oninput = (e) => {
      sampling.seed = Math.max(0, parseInt(e.target.value, 10) || 0);
      saveSampling();
      updateSamplingLabels();
    };

    $("#samplingResetBtn").onclick = () => {
      sampling = { ...DEFAULT_SAMPLING };
      saveSampling();
      applyModalSamplingValues();
    };

    const openExport = () => { exportDialog.hidden = false; };
    const closeExport = () => { exportDialog.hidden = true; };
    exportBtn.onclick = openExport;
    exportDialog.querySelector("[data-close]").onclick = closeExport;
    exportDialog.addEventListener("click", e => {
      if (e.target === exportDialog) closeExport();
    });
    $("#exportMd").onclick = () => {
      const c = activeChat(); if (!c) return;
      downloadFile(`${c.title}.md`, chatAsMarkdown(c), "text/markdown");
      closeExport();
    };
    $("#exportJson").onclick = () => {
      const c = activeChat(); if (!c) return;
      downloadFile(`${c.title}.json`, JSON.stringify(c, null, 2), "application/json");
      closeExport();
    };
    $("#copyChat").onclick = async () => {
      const c = activeChat(); if (!c) return;
      await copyText(chatAsMarkdown(c));
      closeExport();
    };

    // Keyboard: Ctrl+K new chat, Esc closes modals.
    document.addEventListener("keydown", e => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        createChat();
        composerEl.focus();
      }
      if (e.key === "Escape") {
        if (!settingsModal.hidden) closeSettings();
        if (!exportDialog.hidden) exportDialog.hidden = true;
        if (!modelsModal.hidden) closeModels();
        if (!aboutModal.hidden) aboutModal.hidden = true;
        layoutEl.classList.remove("sidebar-open");
      }
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
