# Handoff: Reaction Mode on the maintainer's desktop PC (2026-10-13)

You are picking up the experimental **Reaction Mode** feature on the machine where it is finally being
tested for real. The previous agent worked on a laptop that could not test any of this; you are on
the PC that can. Read this file, then the top section of `STATUS.md`, then `PLAN-reaction-mode.md`
(a copy of the plan, which normally lives outside the repo). This file wins where they disagree.

## The test may still be running: look at it live first

The maintainer left the Debug 92 session open (Interstellar playing, reactions loaded). **Do not
close, restart or kill Nuvio Z Debug** until you have what you need, and ask before doing anything
that would. A lot of the evidence exists only in that process:

- The episode's fingerprint track is written to disk only when indexing finishes or fails
  (`ReactionSession.kt`, the `finally` around `ReactionTrackCache.save(found.key, ...)`). Mid-run, the
  episode `.bin` may be missing or stale, and a JVM exit is not guaranteed to run that `finally`.
  The reaction's track is saved when its read ends (it was at ~75% when this was written).
- The matcher's state is in memory only. Its output is logged every time it changes:
  `matched N stretches in X ms: ep A-Bs @Cs [xRATE] [mirrored], ...`. Tail the newest file in
  `%APPDATA%\Nuvio Z Debug\logs\` and read those lines as the live sync map. Each entry says which
  episode seconds A-B map to reaction second C. The holes between entries are the gaps the
  maintainer sees.
- There is no mpv IPC socket and the Compose window cannot be driven by synthetic input. **What you
  can see is the log and screenshots.** Use `scripts/reaction/wgc-capture.py` (`pip install
  windows-capture pillow` in a throwaway venv) on the main window and, in two-screen mode, on the popup
  titled `Nuvio Z Reactions`. Enumerate top-level windows to get the main window's exact title.
  `PrintWindow` misses the single-screen stage.
- Ask the maintainer to drive: seek to a hole, play across a hole boundary, switch on
  **Show whole video**, and tell you the playback time on screen. Pair each of their actions with a
  screenshot and the log lines from that moment.

Once the live evidence is collected, and only with the maintainer's OK, let the reads finish (or close
the app normally) so both tracks are on disk for the offline replay below.

## Where things are

- Repository: `Zokaper/NuvioZDesktop`, branch **`claude/reaction-mode`** (off `Dev`). Commit here.
  Never `git add -A`; stage the files you changed. The repo is CRLF in the index.
- Wider project rules: `AGENTS.md` here points to `Zokaper/nuvio-z`'s `AGENTS.md` and `Docs/`.
  The upstream production backend `api.nuvio.tv` is **never** a deploy target.
- Installed app: **Nuvio Z Debug 92** (`debug-v0.1.26-alpha-z2.92`), built from `be59070b2` on this branch.
  Reaction Mode is on in debug-channel builds (`ReactionModeFlag` -> `isDebugBuild`), Windows only.
- Code: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/reaction/`, panel UI in
  `composeApp/src/desktopMain/resources/reaction-ui/`, native stage in `player_bridge.cpp` +
  `reaction_stage.inc.cpp`, Firefox extension in `extensions/firefox-reaction-link/`.

### Data the installed app wrote on this PC

| What | Where |
| --- | --- |
| Log (tags `ReactionMode`, `ReactionAnalyzer`, `ReactionDownloads`, `ReactionStage`, `ReactionTools`, `ReactionLink`) | `%APPDATA%\Nuvio Z Debug\logs\` |
| Layout timelines (`<key>.json`, key `yt-<videoId>` for YouTube) | `%APPDATA%\Nuvio Z Debug\reaction\layouts\` |
| Fingerprint tracks: reaction `<key>-r.bin`, episode keyed by content | `%APPDATA%\Nuvio Z Debug\reaction\fingerprints\` |
| yt-dlp + Deno | `%APPDATA%\Nuvio Z Debug\reaction\tools\` |
| 480p analysis copy of the YouTube reaction | `%LOCALAPPDATA%\Nuvio\Cache\reaction\media\` |

`-Dnuvio.reactionDataDir=<dir>` points both caches somewhere else, so a test can load a copy of them.

## What the maintainer saw (first real test, Debug 92)

Episode: **Interstellar** (~2h49m) from debrid in the installed app. Reaction: a large YouTube
**mashup compilation** of Interstellar reactions (~3 h). Ask the maintainer for the URL if you need it;
the layout JSON's file name gives the video id.

1. **Finding reactors took ~5 minutes** (20% after a minute). Consistent with the laptop's measured
   ~27x real time (11.7 min in 26 s), so not a regression. The read always starts at the reaction's
   beginning though, so someone starting mid-film waits for everything before their position.
2. **Not every reactor is shown, and tiles have different sizes.** Expected from the current code:
   reactor boxes come from motion per 45 s window, so still people are missed and borderless
   neighbours merge. `ReactionGridLayout.justify` gives equal *height*, not equal size (a merged
   two-person box comes out wide and "big"), and single-screen justifies each region separately.
   Plan Decision 7 asked for *everyone, equal grid*. That is a design gap, not yet fixed.
3. **At the film's first frame the pill said "Reactions resume in 3:30".** The countdown runs to the
   start of the first matched stretch.
4. **Matching is spotty.** The log said `matched 13 stretches` and the reactions keep freezing into
   gaps at moments where the reaction clearly does show the film. **This is the main problem.**

### Confirmed bug (fix it regardless)

`ReactionSession.overlayText` shows "Matching the reaction to this episode..." only while `matched`
is empty. As soon as one stretch matches, a still-incomplete match is shown as a confident
"Reactions resume in m:ss". While `stillMatching()` and the current episode position is not yet
covered on both sides, the pill must keep saying it is matching (and the gap must not dim the
reactions as if it were a real cut). Showing a number that later changes under the user is
exactly what the maintainer does not want.

## Why the matching is spotty: hypotheses, NONE confirmed

Do not pick one because it sounds right. Kill or confirm each with data from this PC before writing
a fix. The previous agent got this wrong once already by presenting a guess as the answer.

- **H1, wrong box fingerprinted.** For some 45 s epochs the movie box is missed or wrong, so
  `MovieFingerprinter` hashes the wrong region (or the whole frame). Check each epoch in the layout
  JSON for a `MOVIE` tile and a plausible rect against a real frame of the 480p copy.
- **H2, Interstellar's changing aspect ratio.** The film switches between 2.39:1 and IMAX-expanded
  1.78:1 (or 1.43:1). If the compilation's copy, or its box, crops IMAX scenes differently from the
  episode, those hashes will not agree. `SlidingLetterbox` only judges bars from +/-5 s of frames.
  Check whether the holes line up with IMAX sequences.
- **H3, dark and low-detail scenes.** Space, black and dim frames fall under `BLANK_DETAIL` or match
  too many candidates. `ReactionMatcher` constants: `MAX_DISTANCE 14`, `MAX_CANDIDATES 48`,
  `WINDOW 48`, `WINDOW_STEP 16`, `RATE_SNAP 0.004`, `BLANK_DETAIL 12f`, `MIN_SEGMENT_MS 2000`.
  They were tuned on one synthetic 12-minute fixture. Check how dark/blank the frames in the holes are.
- **H4, the episode index is incomplete.** `ReactionEpisodeIndexer` starts 2 minutes before the
  position at the moment Reactions opened, and pauses for 5 s after every main-player frame drop
  (`noteMainDrops`). A 4K HDR remux dropping frames may keep it pausing. Gaps may just be parts not
  read yet. Check the episode `.bin` coverage (`FingerprintTrack.missingRange`) and the
  `episode ...: N frames cached` log line.
- **H5, rate or edit differences the chain logic rejects.** Compilations may run slightly fast/slow,
  cut every few seconds, or overlay text. Check the per-stretch rates in the `matched N stretches`
  log lines and how long the stretches between holes are.

## Suggested order

1. **Collect evidence, no code.** Start with the live session (section above). Then copy the latest
   log's `Reaction*` lines and the two caches into a scratch folder (not the repo; they can be large).
   Ask the maintainer what episode position they were at when Reactions was opened.
2. **Offline replay.** Write a media-gated test (pattern: `ReactionSyncFixtureTest`, env-var gated)
   that loads both cached tracks with `-Dnuvio.reactionDataDir`, runs `ReactionMatcher` to completion,
   and prints coverage plus, for every hole: its episode range, episode-side coverage, mean frame
   detail, best hash distance found, and letterbox. That decides between H1-H5 without guessing.
   Gradle does not treat env vars as inputs: always `--rerun` a media-gated test, or you will read a
   stale report as a pass.
3. **Look at real frames** at a few hole timestamps: the 480p reaction copy (decode with libmpv
   encode mode, see `ReactionFrames`) next to the episode. `scripts/reaction/wgc-capture.py` captures
   the running app including the in-player stage; `PrintWindow` cannot see that stage.
4. Fix what the evidence supports. Then the confirmed countdown bug, then (asking the maintainer
   first) equal-size tiles and starting the reaction read near the current position.
5. Update the top of `STATUS.md`, commit, push, and publish a debug build for the maintainer to retest.

## Building on this PC

The authoritative recipe is `.github/workflows/desktop-debug-release.yml` (Windows job). In short:

- **JDK:** JetBrains Runtime 25 SDK (`jbrsdk_jcef-25.0.4.1-windows-x64-b583.48`, URL and SHA-256 in
  the workflow). Export `JAVA_HOME` to it and put its `bin` on `PATH`; nothing is on `PATH` by default.
  A plain JRE fails the native bridge build (missing `jni.h`).
- **C++:** Visual Studio (Build Tools) with the x64 C++ workload; Gradle finds `vcvars64.bat` via `vswhere`.
- **libmpv:** `gh release download desktop-runtime-v1 --repo Zokaper/NuvioZDesktop` and extract
  `nuvio-desktop-runtime-windows-x64-v1.tar.gz` at the repo root (checksum in the workflow).
- **WebView2 SDK:** for packaging (see the workflow's install step, `-Pnuvio.webview2.dir`).
- Tests: `./gradlew :composeApp:desktopTest --tests 'com.nuvio.app.features.reaction.*' --rerun`
  (51 tests, green on the laptop). If Gradle fails with `Failed to create MD5 hash for ...
  shrunk-classpath-snapshot.bin`, two builds are sharing a directory: `./gradlew --stop`, delete
  `composeApp/build/kotlin/compileKotlinDesktop/classpath-snapshot/`, rebuild.
- Rebuild `player_bridge.dll` after native edits by deleting it; the task skips when it exists.
- The pure analysis classes (`ReactionMatcher`, `ReactionFingerprint`, `ReactionLayoutDetector`) do
  not need the app. If the full build fights you, compiling only those with `kotlinc` for the
  offline replay is a valid shortcut.

## Publishing a debug build (pre-authorized)

1. `gh release list --repo Zokaper/NuvioZDesktop --limit 8`, take the highest `debug-v*` number + 1
   (other branches publish too; the file's number says nothing about them).
2. Set `DEBUG_BUILD` in `composeApp/Configuration/DesktopDebugVersion.properties`, commit, push.
3. `gh workflow run desktop-debug-release.yml --repo Zokaper/NuvioZDesktop --ref claude/reaction-mode`
   (~13 min; Windows MSI + macOS DMG). The tag is `debug-v<VERSION_NAME>.<DEBUG_BUILD>`, e.g.
   `debug-v0.1.26-alpha-z2.92`.
4. Attach the Firefox extension zip to the release if it changed (`gh release upload`).

Stable releases and merges to `Dev`/`main` need the maintainer's explicit go-ahead. This branch is
experimental and does not merge until the maintainer says so.

## Settled decisions, so you do not re-ask

- The episode is the master. A reactor pausing the film to talk is skipped (Decision 8): compilations
  already cut those pauses and single reactors avoid pausing.
- Two layouts: reactions on a second monitor, or one screen with the film in the middle (Decision 4).
- Gaps freeze and dim the reactions with a countdown; the film's audio comes up (Decision 5).

## Shell traps on Windows

- Bash heredocs mangle backslashes in embedded Python; use a script file.
- PowerShell `-replace` silently no-ops on LF files and mojibakes UTF-8; edit with the Edit tool or
  Python with an assert that the pattern was found.
- Compare files across repos with `diff --strip-trailing-cr`.
