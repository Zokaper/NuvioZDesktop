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

This repository has **no `upstream` remote at all**, and its base `1704f6c9` sits on upstream's
`desktopweb` branch, which is a different branch from the one mobile forked. Wiring it is the first
thing to do.

**First clone, once, or `.gitattributes` silently does nothing:**

```bash
git config merge.ours.driver true
git config rerere.enabled true
```

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
