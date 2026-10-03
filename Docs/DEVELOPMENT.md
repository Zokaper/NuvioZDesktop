# Developing Nuvio Z Desktop

This is the build, run and tooling guide for the Windows and macOS app. For what Nuvio Z is and how
to install it, see the [README](../README.md). The documents that explain how the mod relates to
upstream Nuvio live in the mobile repository and cover both:
[`Z-FEATURES.md`](https://github.com/Zokaper/nuvio-z/blob/main/Docs/Z-FEATURES.md),
[`UPSTREAM.md`](https://github.com/Zokaper/nuvio-z/blob/main/Docs/UPSTREAM.md) and
[`PATCH-SURFACE.md`](https://github.com/Zokaper/nuvio-z/blob/main/Docs/PATCH-SURFACE.md). Read
[`CONTRIBUTING.md`](../CONTRIBUTING.md) before opening an issue or pull request.

Working on this repository with an AI agent or as a maintainer? [`AGENTS.md`](../AGENTS.md) points
to the instructions and the live handoff, which cover both repositories.

## Run from source

```bash
git clone https://github.com/Zokaper/NuvioZDesktop.git
cd NuvioZDesktop
```

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

## Compose Hot Reload and MCP

The sections below are written for the Compose Hot Reload workflow, including driving it from an AI
agent (Claude Code or Codex).

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

### Starting the agent from the parent folder

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

## Package a release build

Official releases are built by the GitHub Actions workflows (`desktop-release.yml`, and
`desktop-debug-release.yml` for the debug channel), not locally. The release family is a Windows
MSI and two macOS DMGs (Apple Silicon and Intel). To build the same packages yourself:

```bash
# Package for the current host
./gradlew :composeApp:packageReleaseDistributionForCurrentOS

# Windows
./gradlew :composeApp:packageReleaseMsi --rerun-tasks

# macOS
./scripts/build-macos-release-dmgs.sh --package-only
```

Gradle can also produce Linux packages (`:composeApp:packageReleaseDeb`, and RPM, AppImage and
Flatpak jobs exist in the release workflow), but Linux is not part of the release family and none of
it is published.

## Project structure

- `composeApp/` contains the app code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/desktopMain/` contains desktop-specific integrations.
- `composeApp/Configuration/DesktopVersion.properties` contains the desktop release version and build code.
- `scripts/` contains the build, release and test helpers, including `dev-desktop.ps1`.

## Versioning

Desktop versions are set in `composeApp/Configuration/DesktopVersion.properties`. A Nuvio Z version is
the upstream Nuvio Desktop version plus a Z revision, for example `0.1.26-alpha-z1`; the revision
resets when the base moves.

```properties
VERSION_NAME=<upstream version>-z<revision>
VERSION_CODE=<monotonic build code>
```

Release ordering uses a separate monotonic serial in
`composeApp/Configuration/DesktopReleaseSerial.properties`, bumped by one in the same commit as
every stable version bump. Use the version helper when changing desktop release versions:

```bash
./scripts/set-version.sh --desktop <version> --desktop-code <code>
./scripts/set-version.sh --show
```

The mobile repository's [`RELEASES.md`](https://github.com/Zokaper/nuvio-z/blob/main/Docs/RELEASES.md)
is the authoritative release policy for both repositories: channels, tags and publishing.
