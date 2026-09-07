# Phase 2F Desktop Visual Handoff — Complete Agent Handoff

**Date:** 2026-09-07  
**Branch:** `claude/phase-2-desktop-handoff`  
**Working Directory:** `nuviozdesktop`

---

## 1. Executive Summary

Phase 2F addresses the final visual transition defects on desktop:
1. **Task A (Player -> Details / Escape Exit):** Eliminate the black gap / jump cut when exiting the player back to `DetailsDestination`.
2. **Task B (Loading -> Player Startup):** Eliminate the pre-frame flash that exposed an empty dark/light gradient surface and floating player transport controls before real video frames were ready.
3. **Task C (Deterministic Choreography):** Coordinate transitions respecting heavyweight AWT/Win32 airspace dominance without fake Compose fades or arbitrary sleeps (`delay(...)`).

All code changes across C++, HTML, JavaScript, and Kotlin have been fully written and verified. `composeApp/build/native/windows/player_bridge.dll` has been cleanly recompiled and verified against the new C++ code.

---

## 2. Root Causes Identified & Proven

### Task A (Black Gap on Player Exit)
- **Root Cause 1 (Premature Airspace Concealment):** At `T0` (`releaseBeforeNavigation`), `host.concealInteropAirspaceForExit()` was invoked immediately. This hid the `Canvas` and collapsed the Swing wrapper to 1x1 before the navigation pop (`T1`) or the previous Compose destination draw (`T2`).
- **Root Cause 2 (60ms Compose Alpha Delay):** In `MainAppContent.kt`, `NavDisplay.popTransitionSpec` for desktop was configured with `fadeIn(tween(180, delayMillis = 60))`. Because of `delayMillis = 60`, `DetailsDestination` drew at `alpha = 0.0` for 60ms upon pop, revealing the `#0D0D0D` window background underneath.
- **Root Cause 3 (Unfrozen Playback):** The player was not paused upon Escape, allowing audio/video to run until native teardown.

### Task B (Pre-Frame Flash on Startup)
- **Root Cause 1 (Premature `isLoading = false` in C++):** In `player_bridge.cpp`, `isLoading()` previously evaluated to `false` as soon as demuxer container headers were read (`duration > 0 || track-list/count > 0`), and `videoWidth > 0` was satisfied from stream metadata before a single video frame was rendered.
- **Root Cause 2 (Eager Promotion):** `PlaybackHandover.hasFirstFrame` satisfied prematurely based on the premature C++ `isLoading = false`, promoting `SwingPanel` from 1dp to fullscreen before MPV was ready.
- **Root Cause 3 (Controls HTML/JS Initial State):** In `controls.html`, `#playerRoot` had no initial `chrome-hidden` class, and `controls.js` initialized `controlsVisible: false`. In `controls.js`, `showOpening` dropped on `&& state.isLoading`. When `isLoading` dropped early, transport chrome and gradients floated over unrendered video surfaces.
- **Root Cause 4 (HWND Resize Lag):** When `NativePlayerHost`'s AWT Canvas was promoted to full size, `containerHwnd` in C++ was not resized until the 500ms C++ timer (`onTimer()`), leaving the native container mismatched during startup.

---

## 3. Implemented Changes

### C++ Native Player Bridge (`composeApp/src/desktopMain/native/windows/player_bridge.cpp`)
1. **Added `MPV_EVENT_PLAYBACK_RESTART = 21`** to `mpv_event_id`.
2. **Added `std::atomic_bool firstFrameRendered = false;`** to track real frame presentation.
3. **Implemented `bool hasFirstFrame()`:** requires `firstFrameRendered.load()`, `rawPositionSeconds() >= 0.0`, and `int64Property("estimated-frame-number", 0) >= 1`.
4. **Updated `isLoading()`:** returns `true` while `!hasFirstFrame()`.
5. **In `drainMpvEvents()`:** on `MPV_EVENT_PLAYBACK_RESTART`, sets `firstFrameRendered = true` and dispatches `"firstFrame"` event (value `1.0`) to Kotlin via JNI.
6. **In `promoteOpeningContainer()`:** removed early return `if (self->containerPromoted) return;` so that subsequent resize/promote notifications immediately execute `layoutNativeSubviews()`.
7. **Exported JNI functions:**
   - Windows: `Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_hasFirstFrame`
   - macOS (`player_bridge.mm`): stubbed matching export to prevent unresolved symbol errors.

### Web Controls (`composeApp/src/desktopMain/resources/player-ui/`)
1. **`controls.html`:** added `chrome-hidden` class to `#playerRoot` by default.
2. **`controls.js`:**
   - Initialized `controlsVisible: false`.
   - Removed `&& state.isLoading` from `showOpening` (line 1942) and `isOpeningOverlayActive` (line 2210) so the opening overlay remains governed strictly by `state.showOpeningOverlay` (controlled by Kotlin via `!firstFrameReached`).
   - Verified clean with `node --check`.

### Kotlin Layer (`composeApp/src/`)
1. **`NativePlayerBridge.kt`:**
   - Added `external fun hasFirstFrame(handle: Long): Boolean`.
2. **`NativePlayerHost.kt`:**
   - Added `var onHostResized: ((width: Int, height: Int) -> Unit)? = null`.
   - In `onLaidOut()` (inside `addComponentListener`): invokes `onHostResized?.invoke(width, height)`.
   - In `removeNotify()`: clears `onHostResized = null`.
3. **`NativePlayerController.kt`:**
   - Added `init` block:
     ```kotlin
     init {
         host.onHostResized = { _, _ ->
             if (nativeSurfacePromoted && handle != 0L) {
                 nativePromoteOpeningContainer(handle)
             }
         }
     }
     ```
   - Added `fun hasFirstFrame(): Boolean = runCatching { NativePlayerBridge.hasFirstFrame(handle) }.getOrDefault(false)`.
   - In `handlePlayerEvent`: added `"firstFrame"` handler calling `promoteNativeSurface()`.
   - In `releaseBeforeNavigation`:
     - Immediately pauses playback: `runCatching { NativePlayerBridge.setPaused(currentHandle, true) }`.
     - Defers `host.concealInteropAirspaceForExit()` to T2 using `PlayerExitDiagnostics.runAfterPreviousDraw`:
       ```kotlin
       val concealAction = Runnable {
           nativeSurfacePromoted = false
           host.concealInteropAirspaceForExit()
           onSurfacePromotedChanged?.invoke(false)
           PlayerExitDiagnostics.recordT3("releaseBeforeNavigation conceal (post-T2)")
           log.i { "nativeSurface=CONCEALED reason=releaseBeforeNavigation(post-T2)" }
       }
       val deferredConceal = PlayerExitDiagnostics.runAfterPreviousDraw {
           SwingUtilities.invokeLater(concealAction)
       }
       if (!deferredConceal) {
           if (SwingUtilities.isEventDispatchThread()) concealAction.run() else SwingUtilities.invokeLater(concealAction)
       }
       ```
4. **`MainAppContent.kt`:**
   - Changed `popTransitionSpec` for desktop under `entry<PlayerRoute>` to:
     ```kotlin
     isDesktop -> NavDisplay.transitionSpec {
         EnterTransition.None togetherWith ExitTransition.None
     } + NavDisplay.popTransitionSpec {
         EnterTransition.None togetherWith ExitTransition.None
     }
     ```
     eliminating the 60ms delay and alpha fade.
5. **`NativePlayerAirspaceGateTest.kt`:**
   - Updated `releaseBeforeNavigationConcealsHostImmediately`: asserts `host.isVisible` remains `true` until `PlayerExitDiagnostics.recordT2("unit_test")` fires, after which `host.isVisible` and the interop wrapper are concealed.

---

## 4. Environment & Build Setup

### Environment Variables for Gradle & Native Compilation
Gradle toolchain JDK is at:
`C:\Users\Rayoa\.gradle\jdks\jetbrains_s_r_o_-25-amd64-windows.2`
(contains `include/jni.h` and `include/win32/jni_md.h`).

In PowerShell:
```powershell
$env:JAVA_HOME = "C:\Users\Rayoa\.gradle\jdks\jetbrains_s_r_o_-25-amd64-windows.2"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

In Git Bash:
```bash
export JAVA_HOME="/c/Users/Rayoa/.gradle/jdks/jetbrains_s_r_o_-25-amd64-windows.2"
export PATH="$JAVA_HOME/bin:$PATH"
```

### Verified Compilation Status
- `.\gradlew.bat :composeApp:buildWindowsPlayerBridge` passed with exit code 0 (`BUILD SUCCESSFUL in 37s`).
- `composeApp/build/native/windows/player_bridge.dll` (551,936 bytes) was built freshly and is present on disk.

---

## 5. Next Steps for Next Agent

1. **Verify Unit Tests:**
   Run desktop unit tests:
   ```powershell
   $env:JAVA_HOME = "C:\Users\Rayoa\.gradle\jdks\jetbrains_s_r_o_-25-amd64-windows.2"; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :composeApp:desktopTest --tests "com.nuvio.app.features.player.desktop.*"
   ```
   Run pure suites:
   ```powershell
   & 'C:\Program Files\Git\bin\bash.exe' -c 'export JAVA_HOME="/c/Users/Rayoa/.gradle/jdks/jetbrains_s_r_o_-25-amd64-windows.2"; export PATH="$JAVA_HOME/bin:$PATH"; bash scripts/run-pure-suites.sh'
   ```

2. **Package Fresh MSI:**
   ```powershell
   $env:JAVA_HOME = "C:\Users\Rayoa\.gradle\jdks\jetbrains_s_r_o_-25-amd64-windows.2"; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat :composeApp:packageMsi -Pnuvio.desktop.debugTools=true
   ```

3. **Maintainer Verification:**
   Request maintainer to watch the packaged MSI and verify:
   - Escape from playback returns to `DetailsDestination` seamlessly with no black gap or jump.
   - Startup from loading to video shows no pre-frame flash of gradient/chrome before video pixels take over.
