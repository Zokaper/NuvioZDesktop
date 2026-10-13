// Nuvio Z Reaction Mode - the controls-page half (experimental).
//
// Injected into the player's controls page by ReactionControlsUi.kt instead of being linked from
// controls.html, so no upstream file carries it. Idempotent: a repeat injection only re-announces.
// Every decision is made in Kotlin (ReactionSession); this draws `window.reactionUpdate(state)`
// and posts `reaction*` messages back, exactly the Watch Together panel's split.
(function () {
  const post = (type, value) => {
    const bridge = window.chrome && window.chrome.webview;
    if (bridge) bridge.postMessage({ type, value: Number(value) || 0 });
  };
  if (window.__nuvioReactionInstalled) {
    post("reactionReady", 1);
    return;
  }
  window.__nuvioReactionInstalled = true;

  const style = document.createElement("style");
  style.id = "reactionStyles";
  style.textContent = __REACTION_CSS__;
  document.head.appendChild(style);

  // --- Header button: beside Watch Together, same component. ---
  const headerActions = document.querySelector(".header-actions");
  const anchor = document.getElementById("watchTogetherButton");
  const button = document.createElement("button");
  button.className = "header-button reaction-button";
  button.id = "reactionButton";
  button.type = "button";
  button.setAttribute("aria-label", "Reactions");
  button.title = "Reactions";
  button.innerHTML =
    '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v6a2.5 2.5 0 0 1-2.5 2.5H13l-4 3.5V14H6.5A2.5 2.5 0 0 1 4 11.5v-6Zm2.5-.5a.5.5 0 0 0-.5.5v6a.5.5 0 0 0 .5.5H11v1.1L12.3 12h5.2a.5.5 0 0 0 .5-.5v-6a.5.5 0 0 0-.5-.5h-11Zm3 2.25a1 1 0 1 1 0 2 1 1 0 0 1 0-2Zm5 0a1 1 0 1 1 0 2 1 1 0 0 1 0-2ZM9.6 9.9a.75.75 0 0 1 1.05.15 1.7 1.7 0 0 0 2.7 0 .75.75 0 1 1 1.2.9 3.2 3.2 0 0 1-5.1 0 .75.75 0 0 1 .15-1.05ZM5 19.25a.75.75 0 0 1 .75-.75h12.5a.75.75 0 0 1 0 1.5H5.75a.75.75 0 0 1-.75-.75Z"/></svg>' +
    '<span class="reaction-dot" aria-hidden="true"></span>';
  if (headerActions) {
    if (anchor && anchor.parentNode === headerActions) anchor.insertAdjacentElement("afterend", button);
    else headerActions.insertBefore(button, headerActions.firstChild);
  }
  button.addEventListener("click", event => {
    event.stopPropagation();
    if (typeof noteChromeActivity === "function") noteChromeActivity(true);
    post("reactionToggle", 0);
  });

  // --- The panel: Watch Together's sheet, Reaction Mode's content. ---
  const panel = document.createElement("aside");
  panel.className = "party-panel reaction-panel";
  panel.id = "reactionPanel";
  panel.setAttribute("aria-label", "Reactions");
  panel.setAttribute("aria-hidden", "true");
  panel.innerHTML = `
    <div class="wt-head">
      <div class="wt-head-title">
        <strong>Reactions</strong>
        <span class="wt-chip reaction-chip" id="reactionChip" hidden></span>
      </div>
      <button type="button" class="party-room-close" data-r="reactionClose" aria-label="Close reactions">&times;</button>
    </div>
    <div class="wt-error" id="reactionError" role="alert" hidden>
      <span id="reactionErrorText"></span>
      <button type="button" data-r="reactionDismissError" aria-label="Dismiss error">&times;</button>
    </div>
    <div class="party-panel-scroll">
      <section class="wt-section" id="reactionSourceSection">
        <div class="wt-summary reaction-source" id="reactionSourceSummary" hidden>
          <strong id="reactionSourceTitle"></strong>
          <span id="reactionSourceSub"></span>
        </div>
        <p class="wt-message" id="reactionIntro">Pick a reaction video. Nuvio Z plays it beside the episode, keeps it in sync, and uses its audio.</p>
        <div class="reaction-tabs" id="reactionTabsBlock" hidden>
          <div class="wt-section-title">Open in Firefox</div>
          <div class="reaction-tab-list" id="reactionTabList"></div>
        </div>
        <p class="wt-hint" id="reactionBrowserHint" hidden>Add Nuvio Z Reaction Link to Firefox to pick a YouTube tab here.</p>
        <div class="wt-row reaction-row" id="reactionSourceButtons">
          <button type="button" class="primary" data-r="reactionPasteLink" id="reactionPasteButton">Paste YouTube link</button>
          <button type="button" data-r="reactionPickFile" id="reactionFileButton">Choose file…</button>
        </div>
        <div class="reaction-tools" id="reactionTools" hidden>
          <p class="wt-hint" id="reactionToolsText"></p>
          <button type="button" data-r="reactionInstallTools" id="reactionToolsButton">Set up YouTube support</button>
        </div>
      </section>

      <section class="wt-section" id="reactionLayoutSection">
        <div class="wt-section-title">Layout</div>
        <div class="wt-setting wt-setting-stack">
          <div class="wt-segments" role="radiogroup" aria-label="Layout">
            <button type="button" role="radio" data-r="reactionMode" data-v="0">Two screens</button>
            <button type="button" role="radio" data-r="reactionMode" data-v="1">One screen</button>
          </div>
        </div>
        <div class="wt-setting wt-setting-stack" id="reactionMonitorRow">
          <span>Reactions on</span>
          <div class="wt-segments reaction-monitors" id="reactionMonitors" role="radiogroup" aria-label="Reactions on"></div>
        </div>
        <label class="reaction-slider" id="reactionMovieSizeRow">
          <span>Movie size <output id="reactionMovieSizeValue"></output></span>
          <input type="range" min="40" max="85" step="1" data-r="reactionMovieSize" id="reactionMovieSize">
        </label>
      </section>

      <section class="wt-section" id="reactionSyncSection">
        <div class="wt-section-title">Sync</div>
        <div class="reaction-offset">
          <span id="reactionOffsetText"></span>
        </div>
        <div class="reaction-nudges" role="group" aria-label="Adjust sync">
          <button type="button" data-r="reactionNudge" data-v="-5000">−5s</button>
          <button type="button" data-r="reactionNudge" data-v="-1000">−1s</button>
          <button type="button" data-r="reactionNudge" data-v="-100">−0.1</button>
          <button type="button" data-r="reactionNudge" data-v="100">+0.1</button>
          <button type="button" data-r="reactionNudge" data-v="1000">+1s</button>
          <button type="button" data-r="reactionNudge" data-v="5000">+5s</button>
        </div>
        <p class="wt-hint">If the reactions are ahead of the episode, nudge them back; if they lag, nudge forward.</p>
      </section>

      <section class="wt-section" id="reactionAudioSection">
        <div class="wt-section-title">Audio</div>
        <label class="reaction-slider">
          <span>Reaction <output id="reactionVolumeValue"></output></span>
          <input type="range" min="0" max="100" step="1" data-r="reactionVolume" id="reactionVolume">
        </label>
        <label class="reaction-slider">
          <span>Movie under reactions <output id="reactionMovieVolumeValue"></output></span>
          <input type="range" min="0" max="100" step="1" data-r="reactionMovieVolume" id="reactionMovieVolume">
        </label>
        <label class="reaction-slider">
          <span>Movie when reactions skip <output id="reactionGapVolumeValue"></output></span>
          <input type="range" min="0" max="100" step="1" data-r="reactionGapVolume" id="reactionGapVolume">
        </label>
      </section>
    </div>
    <div class="party-panel-actions" id="reactionFooter">
      <button type="button" data-r="reactionStop">Turn off reactions</button>
    </div>`;
  const partyPanel = document.getElementById("partyPanel");
  const host = partyPanel && partyPanel.parentNode ? partyPanel.parentNode : document.body;
  host.appendChild(panel);

  // On the main screen in either layout: when the reactions skip part of the episode, say when they
  // come back, without opening the panel. Purely informative, so it never takes the pointer.
  const gapPill = document.createElement("div");
  gapPill.className = "reaction-gap-pill";
  gapPill.id = "reactionGapPill";
  gapPill.setAttribute("role", "status");
  gapPill.setAttribute("aria-live", "polite");
  gapPill.hidden = true;
  host.appendChild(gapPill);

  const $ = id => document.getElementById(id);
  const sliderTimers = {};
  const sendSlider = input => {
    const type = input.dataset.r;
    window.clearTimeout(sliderTimers[type]);
    sliderTimers[type] = window.setTimeout(() => post(type, input.value), 60);
  };

  // Nothing inside the panel may reach the player surface (a click there toggles playback) or the
  // page's keyboard shortcuts (space would pause while a slider has focus).
  ["click", "dblclick", "pointerdown", "pointerup", "wheel"].forEach(type =>
    panel.addEventListener(type, event => event.stopPropagation()));
  panel.addEventListener("keydown", event => {
    if (event.key !== "Escape") event.stopPropagation();
  });
  panel.addEventListener("click", event => {
    if (typeof noteChromeActivity === "function") noteChromeActivity(true);
    const target = event.target.closest("button[data-r]");
    if (!target || target.disabled) return;
    post(target.dataset.r, target.dataset.v);
  });
  panel.addEventListener("input", event => {
    const input = event.target.closest("input[data-r]");
    if (!input) return;
    const output = $(input.id + "Value");
    if (output) output.textContent = input.id === "reactionMovieSize" ? input.value + "%" : input.value;
    sendSlider(input);
  });

  const formatOffset = ms => {
    const sign = ms < 0 ? "−" : "+";
    return sign + (Math.abs(ms) / 1000).toFixed(1) + " s";
  };

  const setSlider = (id, value, suffix) => {
    const input = $(id);
    if (!input || document.activeElement === input) return;
    input.value = String(Math.round(value));
    const output = $(id + "Value");
    if (output) output.textContent = Math.round(value) + (suffix || "");
  };

  window.reactionUpdate = s => {
    s = s || {};
    const open = Boolean(s.open);
    panel.classList.toggle("visible", open);
    panel.setAttribute("aria-hidden", open ? "false" : "true");
    button.hidden = !s.enabled;
    button.classList.toggle("active", Boolean(s.active));
    button.setAttribute("aria-pressed", s.active ? "true" : "false");

    const chip = $("reactionChip");
    const status = s.status || null;
    chip.hidden = !status;
    if (status) {
      chip.textContent = status.text || "";
      chip.dataset.tone = status.tone || "neutral";
    }

    $("reactionError").hidden = !s.error;
    $("reactionErrorText").textContent = s.error || "";

    const source = s.source || null;
    $("reactionSourceSummary").hidden = !source;
    $("reactionIntro").hidden = Boolean(source);
    if (source) {
      $("reactionSourceTitle").textContent = source.label || "";
      $("reactionSourceSub").textContent = source.detail || "";
    }
    // Once a reaction is playing, picking another one is secondary: quiet buttons, shorter labels.
    $("reactionSourceButtons").classList.toggle("reaction-change", Boolean(source));
    $("reactionPasteButton").classList.toggle("primary", !source);
    $("reactionPasteButton").textContent = source ? "Paste another link" : "Paste YouTube link";
    $("reactionFileButton").textContent = source ? "Another file…" : "Choose file…";

    const browser = s.browser || {};
    const tabs = Array.isArray(browser.tabs) ? browser.tabs : [];
    $("reactionTabsBlock").hidden = !browser.connected || tabs.length === 0;
    $("reactionBrowserHint").hidden = Boolean(browser.connected) || Boolean(source);
    const tabList = $("reactionTabList");
    const tabSignature = JSON.stringify(tabs);
    if (tabList.dataset.signature !== tabSignature) {
      tabList.dataset.signature = tabSignature;
      tabList.innerHTML = "";
      tabs.forEach(tab => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "reaction-tab" + (tab.linked ? " linked" : "");
        b.dataset.r = "reactionPickTab";
        b.dataset.v = String(tab.id);
        b.title = tab.title;
        const label = document.createElement("span");
        label.textContent = tab.title;
        b.appendChild(label);
        if (tab.linked) {
          const badge = document.createElement("em");
          badge.textContent = "Playing";
          b.appendChild(badge);
        }
        tabList.appendChild(b);
      });
    }

    gapPill.hidden = !s.gapText;
    gapPill.textContent = s.gapText || "";

    const tools = s.tools || {};
    $("reactionTools").hidden = (tools.ready !== false || Boolean(source)) && !tools.message;
    $("reactionToolsText").textContent = tools.message || "YouTube links need yt-dlp and Deno (about 120 MB, downloaded once).";
    $("reactionToolsButton").hidden = Boolean(tools.ready) || Boolean(tools.busy);

    const active = Boolean(s.active);
    ["reactionLayoutSection", "reactionSyncSection", "reactionAudioSection", "reactionFooter"].forEach(id => {
      $(id).hidden = !active;
    });

    panel.querySelectorAll('[data-r="reactionMode"]').forEach(b => {
      const selected = (s.mode === "single" ? "1" : "0") === b.dataset.v;
      b.setAttribute("aria-checked", selected ? "true" : "false");
      b.classList.toggle("selected", selected);
    });
    const monitors = Array.isArray(s.monitors) ? s.monitors : [];
    $("reactionMonitorRow").hidden = s.mode === "single" || monitors.length === 0;
    const monitorBox = $("reactionMonitors");
    const signature = JSON.stringify(monitors);
    if (monitorBox.dataset.signature !== signature) {
      monitorBox.dataset.signature = signature;
      monitorBox.innerHTML = "";
      monitors.forEach(m => {
        const b = document.createElement("button");
        b.type = "button";
        b.setAttribute("role", "radio");
        b.dataset.r = "reactionMonitor";
        b.dataset.v = String(m.index);
        b.textContent = m.label;
        b.title = m.detail || "";
        monitorBox.appendChild(b);
      });
    }
    monitorBox.querySelectorAll("button").forEach(b => {
      const selected = monitors.some(m => String(m.index) === b.dataset.v && m.selected);
      b.setAttribute("aria-checked", selected ? "true" : "false");
      b.classList.toggle("selected", selected);
    });
    $("reactionMovieSizeRow").hidden = s.mode !== "single";
    setSlider("reactionMovieSize", Math.round((s.movieSize || 0.62) * 100), "%");

    $("reactionOffsetText").textContent = typeof s.offsetMs === "number"
      ? "Reaction offset " + formatOffset(s.offsetMs)
      : "";

    const v = s.volumes || {};
    setSlider("reactionVolume", v.reaction == null ? 100 : v.reaction);
    setSlider("reactionMovieVolume", v.movie == null ? 0 : v.movie);
    setSlider("reactionGapVolume", v.gap == null ? 100 : v.gap);
  };

  post("reactionReady", 1);
})();
