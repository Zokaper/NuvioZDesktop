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

## ⚠️ Alpha Software — Testers Only

Nuvio Desktop is currently in alpha and is intended only for testers. It is under active development and is not suitable for daily use.

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
- Linux: DEB package, when available

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
