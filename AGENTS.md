# Nuvio Z Desktop Agent Pointer

The authoritative agent instructions and status handoff for this repository
live on the default branch of `Zokaper/nuvio-z` and cover both repositories:

- `AGENTS.md`: https://github.com/Zokaper/nuvio-z/blob/main/AGENTS.md
- `STATUS.md`: https://github.com/Zokaper/nuvio-z/blob/main/STATUS.md

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
