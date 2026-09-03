# Nuvio Z Desktop Agent Pointer

The authoritative agent instructions and status handoff for this repository
live on the default branch of `Zokaper/nuvio-z` and cover both repositories:

- `AGENTS.md`: https://github.com/Zokaper/nuvio-z/blob/main/AGENTS.md
- `STATUS.md`: https://github.com/Zokaper/nuvio-z/blob/main/STATUS.md

**Nuvio Z is a mod, not a fork.** It rides on a stated vanilla base; vanilla features arrive by
inheritance, and every release names the vanilla release it is built on. The documents that settle
what that means are canonical in `nuvio-z` and cover this repository too:

- `Docs/Z-FEATURES.md`: https://github.com/Zokaper/nuvio-z/blob/main/Docs/Z-FEATURES.md
  (**what Nuvio Z is** - every Z feature, numbered, with the platforms it exists on. A new feature
  is not done until it has a row there, desktop column included.)
- `Docs/UPSTREAM.md`: https://github.com/Zokaper/nuvio-z/blob/main/Docs/UPSTREAM.md
  (the patch-surface rules, the versioning scheme, and the sync procedure)
- `Docs/PATCH-SURFACE.md`: https://github.com/Zokaper/nuvio-z/blob/main/Docs/PATCH-SURFACE.md
  (every upstream-owned file we modify - **135 of them here**, against 8 in `NuvioZWeb`)

**Upstream for this repository is a different repository from mobile's.** Upstream retired
`NuvioMedia/NuvioMobile` branch `desktopweb` and moved desktop development to
`NuvioMedia/NuvioDesktop` branch `Dev`, which is where our base `1704f6c9` lives.
`NuvioMobile:cmp-rewrite` still carries a `desktopMain`, but it is vestigial -- 4 files against our
278 -- so merging desktop work from it would be wrong.

| remote | tracks | role |
| --- | --- | --- |
| `upstream` | `NuvioDesktop.git` branch `Dev` | the sync source |
| `upstream-mobile` | `NuvioMobile.git` branch `cmp-rewrite` | reference for the shared `commonMain`; **never** a merge source |

Run `scripts/upstream-drift.sh` to see the current distance. Pushing to either upstream is disabled
by a `DISABLED` push URL.

### Shared changes flow by merge, not by `cp`

**The `cp` ritual is retired.** `Zokaper/nuvio-z` is the remote `mobile` here, and
the two repos share history at mobile's fork base `979d5680`, so a shared change
can be carried across with `git merge mobile/<branch>` and a conflict is a
conflict rather than a silent delta. Push to it is disabled.

```bash
scripts/shared-code-drift.sh              # what differs, and why
scripts/shared-code-drift.sh --expected   # only the unexplained ones
```

**303 shared files currently differ.** Two causes, which the script labels:
**upstream-fork-gap** (the two repos forked from different upstreams, so SIMKL and
the newer locales are inherited on one side only - settled by an upstream sync,
never by copying) and **missed `cp`** (one of our changes that never made it
across - the real bug).

For a genuinely divergent file, port by hand; do not copy. `AppUpdater.kt` is the
worked example: it looks shared, but this repo's carries MSI paths,
`downloadedUpdatePath` instead of `downloadedApkPath`, its own install-permission
naming and a different import list. A `cp` reverts all of it silently.

Never copy: `MetaDetailsScreen.kt`, `strings.xml` (extra keys here), this repo's
`AppFeaturePolicy` external-player gating, the NVIDIA RTX setting,
`features/setup/SetupHomeStill.kt`, and everything under `desktopMain`.

**First clone, once, or `.gitattributes` silently does nothing:**

```bash
git config merge.ours.driver true
git config rerere.enabled true
```

**`merge=ours` only fires on a *conflict*.** A version file we have not touched since the fork base
merges cleanly and silently takes upstream's value -- this is exactly what happens to `appinfo.json`
on web and to the (stale, unused) `iosApp/Configuration/Version.xcconfig` on desktop. After every
sync, re-check the version files by eye before cutting a release; the attribute protects the files
we edit, not the files we ignore.

### Local desktop development and delivery

- For Compose UI changes, use the local Hot Reload workflow documented in `README.md`: start
  `scripts/dev-desktop.ps1 hot`, then use the Compose Hot Reload MCP tools to inspect screenshots,
  semantics, logs/errors, reload state, and supported UI interactions. Revert any temporary visual
  verification change before finishing.
- For mechanical or non-UI changes, use Hot Reload when the change is compatible. Structural or
  startup changes may require a normal app restart; always run the relevant local compile/test and,
  when useful, launch the desktop app locally before considering the work complete.
- Once a change is complete, or when it needs multi-device/cross-platform validation, push through
  the existing debug workflow by default. Do not cut, tag, promote, or trigger an actual release
  unless the user explicitly requests a release. Do not replace or bypass the existing MSI/DMG
  GitHub Actions release workflow.

Active feature plan, also canonical in `nuvio-z` and covering both repositories:

- `PLAYBACK_MODES_PLAN.md`: https://github.com/Zokaper/nuvio-z/blob/main/PLAYBACK_MODES_PLAN.md
  (Classic / Streamlined / Instant playback modes. Its execution ledger is the
  resume point. Every new `expect` it introduces needs a `desktopMain` actual
  here, which only the Windows CI job will catch.)

Read both before changing this repository. The table at the top of the
canonical `STATUS.md` names the active branch in `nuvio-z` and
`NuvioZDesktop`; when it names a branch, that branch contains newer work than
this `Dev` checkout.

Keep this pointer on `Dev`. Do not copy the full handoff here, because two
independent copies would drift.
