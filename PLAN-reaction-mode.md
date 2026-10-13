# PLAN — Reaction Mode (experimental, desktop / Windows)

Status: **Stages 1-4 built and committed (2026-10-13), not pushed.** Branch `claude/reaction-mode` in
`nuviozdesktop`, cut from `Dev`, in the worktree `.claude-worktrees/reaction-mode`. The branch's `STATUS.md`
is the handoff (what is validated, what is not, measured facts). Stage 5 (episode flow) is partly there
(the linked Firefox tab is followed to the next video); Stage 6 (desktop QA) not started. Not a Z-FEATURES
row until it is promoted out of experimental.

## What was inspected (facts, not assumptions)

- **Main player**: libmpv (`libmpv-2.dll`, bundled) renders with `vo=gpu-next` into a child HWND via
  `wid` (`native/windows/player_bridge.cpp:1858-1910`). Controls are a **WebView2** page layered over
  it (`resources/player-ui/controls.{html,js,css}`), not Compose. Commands flow
  `controls.js data-command` → native → `NativePlayerController.kt:1626` → `PlayerControlsAction`.
- **Watch Together button** is `#watchTogetherButton` in the controls header; its panel is the
  `aside.party-panel` side sheet (340 px, 20 px radius, `rgba(15,16,21,.9)` + 18 px blur). Reaction
  Mode's button and setup sheet copy exactly that pattern.
- **The native player is recreated per source** (`PlayerEngine.desktop.kt:172`, `DisposableEffect`
  on `sourceUrl`). A reaction session must therefore live in an app-scoped store, not in the player,
  to survive next-episode autoplay.
- Bridge already exposes everything sync needs on the master: `positionMs`, `isPaused`, `seekTo*`,
  `setVolume`, `setSpeed`, `screenshotToFile`.
- Bundled libmpv contains `ytdl_hook`, `screenshot-raw`, `video-crop`, `lavfi-complex`, libplacebo,
  d3d11va (string probe of the DLL; not yet exercised).
- This laptop: Firefox 157.0.1 (release channel, so **unsigned extensions cannot be installed
  permanently**), AMD Radeon 890M, **one** 1920×1200 display, no yt-dlp/ffmpeg. The real target
  (dual monitor, NVIDIA, 4K HDR) is the maintainer's desktop PC.
- **Reaction Z (the standalone app) is not on this machine** — it lives on the maintainer's other PC.
  Nothing below reuses it yet; stages 3–4 are where it would slot in.

## Architecture

```
            Firefox tab (YouTube)                       Nuvio Z Desktop
  ┌───────────────────────────────┐    WebSocket    ┌──────────────────────────────────────────┐
  │ "Nuvio Z Reaction Link" ext.  │◄──127.0.0.1────►│ ReactionLinkServer (token + origin check) │
  │  content script: <video> ctl  │   tab list,     │                                          │
  │  background: tabs, URL watch  │   URL, time,    │ ReactionSession  (app-scoped, survives    │
  └───────────────────────────────┘   play/pause    │   episode changes, per-profile)           │
                                                    │   ├─ ReactionSource: LocalFile | Tab       │
  Main player (unchanged mpv) ── position/pause ───►│   ├─ SyncEngine  (master = main player)   │
                                                    │   ├─ SyncMap     (cached segments)        │
                                                    │   ├─ ReactorLayout (cached, editable)     │
                                                    │   └─ AudioMixer  (reaction primary)       │
                                                    │                                          │
                                                    │ ReactionStage: 2nd libmpv instance →      │
                                                    │   GPU compositor window on monitor 2      │
                                                    │ Analyzer: headless decode → fingerprints  │
                                                    └──────────────────────────────────────────┘
```

1. **ReactionStage (rendering).** A second, independent libmpv instance plays the reaction in its own
   `gpu-next`/D3D11 surface (`wid` into a borderless native window on the chosen monitor, or a child
   surface in single-screen mode). The crop-and-grid is a **generated libplacebo user shader**
   (proven in S5): each reactor box is a source rect mapped to a destination rect, sampled with
   Catmull-Rom, with rounded corners and gutters; dim/blackout are live shader parameters. Full
   hardware decoding, no CPU copies, no custom renderer. The main player's mpv options, HWND and
   threads are untouched.
2. **Browser tab.** A lightweight Firefox WebExtension is **necessary**: nothing else lets Nuvio Z
   list tabs, read the video URL, or play/pause/seek YouTube's `<video>`. Two possible models,
   see Decision 1:
   - *Handoff*: the extension tells Nuvio Z which video the tab holds; Nuvio Z streams it itself
     (yt-dlp, Firefox cookies) in ReactionStage and pauses/mutes the tab. The tab becomes the picker
     and the "next episode" slot.
   - *Mirror*: Firefox keeps playing; Nuvio Z captures the Firefox window (Windows Graphics
     Capture), crops boxes from the capture, and drives the tab's `<video>` via the extension.
   DRM: neither path touches protected media; WGC returns black for protected surfaces and we stop.
3. **Analyzer.** Reaction side: decode at low res (~256 px, 4–8 fps, grey) and detect the *content
   region* (where the episode is shown) vs *reactor boxes* (rectangles with independent motion,
   faces), as a per-shot layout timeline so layouts may change mid-video. Episode side: see
   Decision 3.
   **Splitter requirements**, from the maintainer's reference stills
   (`Nuvio Z/reaction-fixtures/stills/`, kept outside the public repo):
   - *LoneReact*: 8 boxes of unequal size, **no gutters** (boxes abut, edges are only a content
     discontinuity); a box can hold 1–8 people (the unit is the *camera box*, not the person);
     channel watermarks over a reactor box and across the movie; vignette bands; thin **movie
     bleed-through strips** under two boxes.
   - *TEM*: **~80 tiny tiles** (~100 px wide at 1080p) in blocks with dark gutters, a decorative
     red background with drop shadows, **subtitles drawn outside the movie frame**, a corner logo,
     a stray "7:21" overlay inside one tile.
   - *VoltReact*: white gutters around cells, but most cells are **pairs split by a thin dark
     line**, so detection must recurse inside a cell; one cell is a wide 5-person couch.

   Consequences for the design:
   - Edges in one frame are not enough. The primary signal is **temporal**: each tile is a separate
     camera, so frame-to-frame change is correlated within a tile and decorrelated across a
     boundary; a real boundary is an axis-aligned line that persists for minutes while
     internal edges (shelves, mics) don't separate decorrelated motion. Gutter colour and
     single-frame gradients are supporting evidence, not the method.
   - Rectangles come from recursive XY-cuts over that boundary evidence (all three stills are
     near-guillotine), with a segment-graph fallback for partial cuts. Recursion handles pairs
     inside cells; cells are kept as **groups** so pairs stay adjacent by default.
   - The **movie region** is whatever changes all at once on the movie's shot cuts and matches the
     episode fingerprint; slivers that match it (bleed-through) are discarded, and so is anything
     outside every tile (backgrounds, subtitles, logos).
   - A tile is a reactor if it holds a face most of the time; logos/decor without faces drop out.
   - Layouts are detected per **layout epoch** and re-detected when boundary evidence changes
     (reactors joining or leaving mid-video).
   - Tile quality is bounded by the source: an 80-tile compilation at 1080p gives ~100 px tiles.
     Handoff asks yt-dlp for the highest available resolution (1440p/4K when uploaded), and the
     compositor upscales tiles with a sharp scaler rather than bilinear.
   - Every automatic result is editable (split, merge, nudge an edge, hide a tile, drop a box),
     and edits are cached per channel, since a channel reuses its layout across episodes.
   - Acceptance: the three stills are annotated by hand as ground truth; the still-only pass must
     get the box structure right on all three, and the temporal pass is scored on the full videos.

4. **SyncMap.** Fingerprints = normalised DCT perceptual hashes of the content region (plus a
   mirrored variant) + shot-cut times. Alignment = candidate matches → monotonic piecewise-linear
   segments (slope 0.9–1.1 allows sped-up uploads) chained by DP, gaps allowed. Output:
   `[{epStart, epEnd, reactStart, rate, confidence}]`. Robust to watermarks/borders/resolution by
   construction (hash of a normalised crop); manual correction = drag a segment's offset or add an
   anchor ("this reaction frame = this episode moment").
5. **SyncEngine.** Driven by the master's position. Inside a segment: |error| > 300 ms → seek the
   reaction, 40–300 ms → speed nudge ±3 % (pitch-corrected). Master paused/seeking → reaction follows.
   Outside any segment (cut opening, credits, skipped scene): reaction pauses and fades out, is
   **pre-seeked to the next segment's start** so it resumes instantly when the episode reaches it.
6. **Audio.** Reaction audio is primary. Main player volume defaults to 0 while a segment is
   active and automatically fades up to a user-set "gap volume" during unmatched sections, so the
   episode never goes silent. Independent sliders for reaction, movie, and gap volume.
7. **Next episode.** The session outlives the player. On the new episode: Handoff — the setup sheet
   reopens pre-armed ("Open the E06 reaction in your Firefox tab"); when the tab's URL changes,
   analysis → match → sync start without further input. Layout overrides carry over per channel.
8. **Cache** (`%APPDATA%\Nuvio Z[ Debug]\reaction\`): reaction fingerprints + layouts keyed by
   YouTube id or file hash; episode fingerprints keyed by a content hash (size + head/tail hash +
   duration); sync maps keyed by the pair, including manual corrections.

Isolation: everything new lives under `features/reaction/` (desktopMain) plus a native
`reaction_stage.cpp`. Patch surface on existing files is limited to: one header button + one panel
in `controls.*`, one action in `NativePlayerController`, and a read-only hook for position/pause and
the volume setter. Hidden behind a debug setting; when off, no code path runs. Watch Together and
Reaction Mode are mutually exclusive at first (the master can't be both a party follower and a
reaction master).

## Major risks

| # | Risk | How it's tested before building on it |
| --- | --- | --- |
| R1 | YouTube via yt-dlp: since late 2025 yt-dlp needs an external JS runtime (Deno) for YouTube, breaks periodically, and age-gated videos need cookies. Also skips YouTube ads, a ToS issue if it ever goes public. | Spike S1: resolve + play 5 real reaction videos via libmpv's ytdl_hook. |
| R2 | Firefox release refuses unsigned extensions permanently. | Decision 2; S2 loads it temporarily first. |
| R3 | Mirror mode: Firefox may stop painting an occluded/minimised window, ads interrupt, capture adds latency, capture is limited to window resolution. | Spike S3 (only if Mirror is chosen). |
| R4 | Episode indexing reads the stream a second time (a 4K remux is 15–25 GB/episode); debrid hosts may cap concurrent connections. | Spike S4: measure on a real debrid stream. |
| R5 | Heavily altered footage (zoom, mirror, colour filters, movie shown small in a box) can defeat hashing. | Synthetic reactions with known ground truth (generated locally with mpv encode mode) + 3 real compilations; target ≥ 95 % of matched time within ±100 ms. |
| R6 | 4K HDR degradation from a second decoder + analysis. | Compare main mpv `frame-drop-count` / `vo-delayed-frame-count` with and without Reaction Mode on the desktop PC. Analysis runs at low priority, paused when the main player drops frames. |
| R7 | New native code (render API + compositor) on Windows only. | Spike S5 standalone before wiring into the app. |
| R8 | Validation hardware: this laptop has one screen and an AMD GPU; the app window can't be driven here and `dev-desktop.ps1 hot` can't play video. | Headless two-mpv sync harness here; real-use QA on the desktop PC via debug MSI. Each result labelled *validated here* / *validated on desktop* / *experimental*. |

## Stage 0 results (2026-10-13, on the laptop: AMD 890M, Firefox 157)

| Spike | Result | Evidence |
| --- | --- | --- |
| S1 yt-dlp via bundled libmpv | **PASS** | `ytdl_hook` runs in Nuvio Z's own `libmpv-2.dll` (Lua is present). Real LoneReact video played at 1080p AV1 + Opus. yt-dlp 2026.08.19 **does** need Deno: without it, YouTube extraction warns "deprecated, some formats may be missing"; with `--js-runtimes deno:<path>` the full DASH set appears. Load → first frame ~8.5 s (yt-dlp). **Network seeks 2.0–2.7 s** → SyncEngine must not depend on seeking (speed nudges; pre-seek in gaps; background-cache the file to disk so later seeks are local). Exact seek and `speed` work. |
| S2 Firefox extension | **PASS (Handoff needs)** | Background-script WebSocket to `127.0.0.1` works; Origin is `moz-extension://<uuid>` (server checks it + token). Lists tabs with URL/title. Pause + mute stick within one 2.5 s cycle, **including during a YouTube pre-roll ad** (~15 ms RTT). In-page related-video click: `tabs.onUpdated` URL at 0 ms, `yt-navigate-finish` at 0.7 s (document.title still stale then, so key on video ID). Full page load detected at 3 s. New videos autoplay with sound → the linked tab must auto-pause/mute on every video change. Seeking the tab during an ad times out (Mirror-only concern). |
| S3 WGC capture | dropped | Mirror not chosen. |
| S4 episode index cost | **NOT RUN** | Needs a real debrid stream URL; do on the desktop PC or with a URL from the maintainer. |
| S5 GPU compositor | **PASS** | A generated libplacebo user shader (`//!HOOK MAIN`, `WIDTH OUTPUT.w`, Catmull-Rom 9-tap, rounded tiles, gutters) crops 9 reactor boxes from the real 1080p video into an equal-height grid in mpv's own `gpu-next`/D3D11 window, `hwdec=d3d11va`. Grid pass **0.33 ms avg / 1.6 ms peak** at 1280×720 on the iGPU; 0 delayed frames. `glsl-shader-opts=dim=1.0` applies **live** (gap dimming without reload). Layout change = rewrite + reload the shader (to measure in S1). Rects must be inset 1–2 px; hand rects leaked neighbour pixels. No custom native renderer needed — the render API path (OpenGL only in this libmpv) is not pursued. |
| S6 single-screen | **HALF** | `video-margin-ratio-*` set live on gpu-next/D3D11 shrinks a 4K source into the centre with no reinit (`osd-dimensions` margins 230/230/129/130). The reaction surface between mpv's container HWND and WebView2 needs the real app → Stage 1. |

### Stage 4 results (2026-10-13, laptop)

- **Accuracy (R5).** Synthetic compilation with known edits (Tears of Steel, CC BY, in the movie box of the
  real LoneReact video: intro, two cuts, a 12 s freeze, a mirrored stretch sped up 4 %, zoom + colour, a
  watermark): 97.5 % of the edit list within 100 ms through the production pipeline (target 95 %); in the
  real player, worst error 33-50 ms against the truth, joined 8.9 s after choosing the reaction.
- **What changed from the design above.** Fingerprints are a 63-bit DCT hash of whatever rectangle the picture
  occupies squeezed to 32x32, with letterbox bars trimmed from +-5 s of frames on each side (not per box: an
  intro without bars hid the film's bars). Alignment is offset voting per 6 s window from each frame's best
  candidates, robust chaining, a Theil-Sen rate, then an intercept from candidate density - not a DP; the
  DP was not needed. No sub-frame refinement: tried, no gain on 30 fps reactions; matches sit on the 125 ms grid.
- **Indexing cost (R4, partly).** Local 12-min 720p film: 18 s. A 4K HDR remux over debrid is unmeasured.
- **Web reactions are downloaded for analysis**: mpv streams YouTube at ~real time, yt-dlp downloaded the
  20-min LoneReact 720p in 9 s. A 480p video-only copy is analysed in full up front.
- **Bug found in Stage 3's analyzer**: `setpts=PTS-STARTPTS` in an encode-mode filter chain made mid-video
  analyses start at twice the requested position. Fixed; layout cache invalidated.
- **Open question for the maintainer**: a reactor pausing the movie to talk is skipped (the episode is the
  master, so the reaction seeks past the pause). Keep that, or hold the reaction's commentary somehow?

### Reaction Z (React Z) assessment

Python + PySide6 + OpenCV + mpv JSON IPC, FFmpeg subprocess analysis, never run on real content.
`detect_boxes` (single-frame Canny quads, area > 1.5 %, ≤ 16 boxes, Haar-face fallback) was run on the
three reference stills: **0 / 3 correct** — LoneReact: no movie, 14 per-face crops; TEM: wrong "movie", 38
of ~80 faces; VoltReact: movie right, 7 cells, pairs unsplit, couch cell missed. Not reused.
Worth porting as ideas (into Kotlin, with tests): the exposure-normalised luminance + edge descriptor and its
low-variance rejection (title cards, black frames); `build_segments` (split on missing anchors, backwards
jumps and offset jumps, line fit, keep highest-confidence on overlapping movie spans); **per-reactor
blackout** (one tile goes black → that tile freezes dimmed with a pause glyph, others keep playing) —
added to this plan; the "never write to the master player" controller rule.

## Stages (each ends committed, with STATUS.md updated)

- **S0 – Spikes** (throwaway, scratchpad): S1 yt-dlp/ytdl_hook, S2 extension ↔ local WebSocket ↔
  YouTube `<video>` control, S3 WGC Firefox capture (if Mirror), S4 episode index cost, S5 libmpv
  render-API compositor on a 2nd window, S6 `video-margin-ratio-*` on the main surface with a child
  tile surface between mpv and WebView2 (single-screen mode). S3 is dropped (Mirror not chosen).
  Report pass/fail before Stage 1.
- **S1 – Skeleton**: branch, flag, Reaction button + setup sheet (render-harness PNGs at four widths),
  app-scoped `ReactionSession`, local-file source, ReactionStage showing the full reaction frame on
  monitor 2, manual offset sync, audio mixer. *Usable end-to-end with a local file and a manual offset.*
- **S2 – Browser link**: extension + `ReactionLinkServer` + tab picker inside Nuvio Z.
- **S3 – Analyzer + layout**: reactor-box detection, layout timeline, grid arranger, edit mode.
  (Reaction Z's crop/layout code ports in here.)
- **S4 – SyncMap**: fingerprints, alignment, gap handling, manual anchors, cache. (Reaction Z's sync
  code ports in here.) Pure-suite tests on synthetic ground truth.
- **S5 – Episode flow**: next-episode prompt, same-tab handoff, automatic re-sync.
- **S6 – Desktop QA pass** on the maintainer's PC: 4K HDR drop counters, three real compilations,
  a three-episode binge.

## Decisions (answers recorded here)

1. Browser model — **Handoff** (2026-10-13). Mirror is not built.
2. Extension distribution — **AMO unlisted signing** (needs the maintainer's Mozilla API keys at S2;
   temporary `about:debugging` loading until then).
3. Episode indexing — **Hybrid**: background index from the current position forward, live
   sampling as fallback; cached permanently.
4. Stage presentation — **two modes** (2026-10-13):
   - *Dual-screen*: movie full-screen on its monitor, reactor grid on a second monitor.
   - *Single-screen*: the movie stays in the middle of the player window, reactors arranged
     around it, like the source compilation but with our own high-quality movie. Implemented
     without recompositing the movie: mpv's `video-margin-ratio-{left,right,top,bottom}` shrinks the
     4K HDR frame inside its own surface (same vo, same quality), and the ReactionStage draws the
     reactor tiles as a child surface in the margins, below the WebView2 controls in z-order.
     The layout solver picks margins + tile placement for the window size and box count.
5. Gaps — **dim + countdown** (2026-10-13): tiles freeze on the last frame, dim, and show a
   "Reactions resume in 1:42" chip; in single-screen mode the movie grows back to full size for gaps
   over 30 s and shrinks again just before reactions resume. **Gap audio**: movie fades up (~0.5 s) to
   a user-set level (default: normal volume) and back down when reactions resume.
6. Reaction Z code — at `Antigravity/React Z` (Python, one Codex prompt, untested). Maintainer: take
   only what is better. See the assessment above.
7. Many reactors — **everyone, equal grid** by default (2026-10-13), even at ~80 tiles. The
   arranger maximises uniform tile size for the count and aspect ratios; hiding tiles stays
   available as a manual edit.
8. Reactor pauses — **skip them, as built** (2026-10-13). Compilations already cut out the parts where
   a reactor pauses to talk, and single reactors avoid pausing so viewers never have to resync, so the
   episode-as-master model holds. Not a gap to design for.
