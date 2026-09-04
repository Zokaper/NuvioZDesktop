<div align="center">

  <img src="composeApp/src/commonMain/composeResources/drawable/app_logo_wordmark.png" alt="Nuvio" width="300" />
  <br />
  <br />

  [![Contributors][contributors-shield]][contributors-url]
  [![Forks][forks-shield]][forks-url]
  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    A mod of <a href="https://github.com/NuvioMedia/NuvioDesktop">Nuvio Desktop</a> for Windows,
    macOS, and Linux.
    <br />
    Playback modes • Instant • Browse, organize, and play media from sources you add.
  </p>

</div>

## ⚠️ Alpha Software - Slow Development - Testers Only

Nuvio Desktop is currently in alpha and is intended only for testers. It is under development and is not suitable for daily use.

Expect breaking changes with every update. Features, settings, stored data, and compatibility may change or stop working without notice. Do not rely on this build as your primary media app, and report any issues you encounter during testing.

## About

**Nuvio Z Desktop is a mod of [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop), not a
separate product.** It is a bounded set of patches riding on a stated vanilla base, and it inherits
everything vanilla ships. Every Z release names the vanilla release it was built on — *Nuvio Z
0.6.0-z1, based on Nuvio 0.6.0*.

Nuvio Desktop itself is a media client for browsing metadata, managing collections and watch
progress, downloading media, and playing streams from user-installed extensions or user-provided
sources.

What the Z mod adds on top is listed in
[`Docs/Z-FEATURES.md`](https://github.com/Zokaper/nuvio-z/blob/main/Docs/Z-FEATURES.md), with a
Desktop column. The doctrine that governs the mod is in
[`Docs/UPSTREAM.md`](https://github.com/Zokaper/nuvio-z/blob/main/Docs/UPSTREAM.md). Both are
maintained once, in the `nuvio-z` repository, and are not duplicated here.

## Installation

Download the latest desktop build from [GitHub Releases](https://github.com/Zokaper/NuvioZDesktop/releases/latest).

Release packages are provided for supported desktop platforms:

- Windows: MSI installer
- macOS: DMG installer
- Linux: DEB, RPM, FLATPAK and AppImage available.

## Development

```bash
git clone https://github.com/Zokaper/NuvioZDesktop.git
cd NuvioZDesktop
```

Run from source:

```bash
./gradlew :composeApp:run
```

On Windows PowerShell:

```powershell
.\gradlew.bat :composeApp:run
```

On Windows, the helper below finds the JetBrains Runtime bundled with Android Studio or IntelliJ,
so `JAVA_HOME` does not have to be configured globally:

```powershell
# Normal local desktop run
.\scripts\dev-desktop.ps1 normal

# Compose Hot Reload with automatic reloads on saved source changes
.\scripts\dev-desktop.ps1 hot
```

The underlying Gradle tasks are `:composeApp:run` and `:composeApp:hotRunDesktop --auto`. The
desktop JVM target is named `desktop`; do not substitute a guessed `hotRunJvm` task.

### Compose Hot Reload MCP

The project-level `.mcp.json` configures Claude Code to start the Compose Hot Reload MCP server.
Trust the project, start the app with `.\scripts\dev-desktop.ps1 hot`, then start/restart Claude
Code from this repository so it loads the server.

**Start the app before the agent connects.** The MCP server attaches to an app that is *already
running* - it watches for `composeApp/build/run/desktopMain/desktopMain.pid`. Launched with no app
up it exits immediately, and the client reports only `CONNECTION_CLOSED`, which reads like a missing
capability rather than an ordering problem. Wait for `> Task :composeApp:hotRunDesktop` in the
launcher output, confirm the PID file exists, then connect - `/mcp reconnect` re-spawns the server,
so a full restart of the agent is not needed.

**`--auto` does not rebuild on save here.** After editing, run `.\gradlew.bat reload` as a second
Gradle invocation; it coexists with the running `hotRunDesktop`, applies the change, and is where
Kotlin compile errors surface. Budget roughly three minutes.

#### Starting the agent from the parent folder

MCP servers are read from the *session's* working directory, so a session opened at the folder that
contains both repositories never sees this repository's `.mcp.json`. Create one beside the repos, at
the parent folder root, with the script path relative to it:

```json
{
  "mcpServers": {
    "compose-hot-reload": {
      "command": "powershell.exe",
      "args": [
        "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", "nuviozdesktop/scripts/dev-desktop.ps1", "mcp"
      ]
    }
  }
}
```

That parent folder is not a git repository, so this file is version-controlled nowhere and has to be
recreated by hand if it goes missing. It works because `dev-desktop.ps1` sets its own working
directory first: Gradle takes its project directory from the working directory rather than from the
path of the wrapper it was invoked through, so pointing at `gradlew.bat` from elsewhere is not
enough on its own.

The currently installed Codex CLI stores MCP registrations in the user configuration. Register this
repository's server once from the repository root, then restart the Codex task/app:

```powershell
$script = (Resolve-Path -LiteralPath "scripts\dev-desktop.ps1").Path
codex mcp add compose-hot-reload -- powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script mcp
```

Verify it with `codex mcp get compose-hot-reload`. Claude Code and Codex launch the same underlying
Gradle task, `:composeApp:hotMcpServerDesktop`; each agent starts it on demand rather than relying on
a permanent background server.

A typical UI loop is: start the Hot Reload app, let the agent connect to the Compose MCP server,
edit Compose code, await/trigger reload, inspect the screenshot and semantic tree, check logs or UI
errors, and repeat. The MCP server also exposes supported click, typing, scrolling, window resize,
restart, and UI-reset operations.

**A local run is a release-channel build, so it does not share the debug build's stored state.**
`DesktopStorage.resolveAppDataDir()` picks its directory from
`AppVersionConfig.DESKTOP_DEBUG_CHANNEL`, and that flag is off for every local invocation by design,
so `dev-desktop.ps1` reads `%APPDATA%\Nuvio Z` while an installed debug MSI reads
`%APPDATA%\Nuvio Z Debug`. Different profiles, addons, settings, and a separate
`setup_wizard_completed_revision` - which is why the setup wizard can appear on a local run for an
account that completed it long ago. To share the debug build's state instead, ask for the channel:

```powershell
.\gradlew.bat -Pnuvio.desktop.debugChannel=true :composeApp:hotRunDesktop --auto
```

This local workflow does not package or install an MSI/DMG. Release packaging remains in the
existing GitHub Actions desktop release workflows and is unchanged.

Build a release package for the current host:

```bash
./gradlew :composeApp:packageReleaseDistributionForCurrentOS
```

Platform-specific packaging:

```bash
# Windows
./gradlew :composeApp:packageReleaseMsi --rerun-tasks

# macOS
./scripts/build-macos-release-dmgs.sh --package-only

# Linux
./gradlew :composeApp:packageReleaseDeb
```

## Project Structure

- `composeApp/` contains the app code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/desktopMain/` contains desktop-specific integrations.
- `composeApp/Configuration/DesktopVersion.properties` contains the desktop release version and build code.

## Versioning

Desktop versions are set in `composeApp/Configuration/DesktopVersion.properties`.

```properties
VERSION_NAME=0.1.1-alpha
VERSION_CODE=1
```

Use the version helper when changing desktop release versions:

```bash
./scripts/set-version.sh --desktop 0.1.2-alpha --desktop-code 2
./scripts/set-version.sh --show
```

## Upstream & License

Nuvio Z Desktop is a modification of [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop) by
NuvioMedia, and would not exist without it. Upstream authors hold the copyright in the code Nuvio Z
inherits.

Both Nuvio Desktop and Nuvio Z Desktop are licensed under the **GNU General Public License v3.0** —
see [LICENSE](./LICENSE). Nuvio Z is distributed under the same terms, with source available.

Nuvio Z is not affiliated with or endorsed by NuvioMedia. Please do not report Nuvio Z bugs to
upstream unless you can also reproduce them in vanilla Nuvio Desktop.

## Legal & DMCA

Nuvio functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

Nuvio is not affiliated with any third-party extensions, catalogs, sources, or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including our full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit our [Legal & Disclaimer Page](https://nuvioapp.space/legal).

## Built With

- Kotlin Multiplatform
- Compose Multiplatform
- Kotlin
- Compose Desktop packaging
- Native desktop player integrations

## Star History

<a href="https://www.star-history.com/#Zokaper/NuvioZDesktop&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=Zokaper/NuvioZDesktop&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=Zokaper/NuvioZDesktop&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=Zokaper/NuvioZDesktop&type=date&legend=top-left" />
 </picture>
</a>

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/Zokaper/NuvioZDesktop.svg?style=for-the-badge
[contributors-url]: https://github.com/Zokaper/NuvioZDesktop/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/Zokaper/NuvioZDesktop.svg?style=for-the-badge
[forks-url]: https://github.com/Zokaper/NuvioZDesktop/network/members
[stars-shield]: https://img.shields.io/github/stars/Zokaper/NuvioZDesktop.svg?style=for-the-badge
[stars-url]: https://github.com/Zokaper/NuvioZDesktop/stargazers
[issues-shield]: https://img.shields.io/github/issues/Zokaper/NuvioZDesktop.svg?style=for-the-badge
[issues-url]: https://github.com/Zokaper/NuvioZDesktop/issues
[license-shield]: https://img.shields.io/github/license/Zokaper/NuvioZDesktop.svg?style=for-the-badge
[license-url]: https://github.com/Zokaper/NuvioZDesktop/blob/main/LICENSE
