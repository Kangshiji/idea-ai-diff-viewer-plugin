---
name: ai-baseline-patch
description: Generate a cumulative unified diff against the frozen original baseline (A versus current code), with user decisions consumed across rounds. Use before and after every AI CLI code modification, including repeated edits, so the patch shows only undecided changes against the first snapshot. Do not use for single-round diffs against the immediately previous state.
---

# AI Baseline Patch

The baseline is the frozen original code **A**, established once and never silently refreshed (A→B→C produces patches referenced against A, not B). The IDE plugin records every Accept/Reject in `.ai_diff/decisions.jsonl`; each round filters decided blocks so the patch shows only changes still awaiting review, while Reject All still rolls the file all the way back to A.

## Mandatory workflow around every modification session

Run these in order; do not reorder:

1. **Before changing any file**, run:
   `python scripts/ai_patch.py --root <project-root> ensure`
   - First time in a project: snapshots the current tree into `.ai_diff/baseline/` (this is A).
   - Every later invocation: no-op. The baseline is never refreshed, even across rounds.
2. **Read prior decisions** before editing:
   - If `.ai_diff/decisions.jsonl` exists, read it (JSON Lines; last entry for the same file and fingerprint wins).
   - Each entry: `file`, `fingerprint`, `removed`, `added`, `decision` (`accepted`/`rejected`), `time`, `source`.
   - Fingerprint identifies a block by content alone: SHA-256 of `join("\n", removed) + '\u0000' + join("\n", added)` UTF-8.
3. **Respect decisions while editing**:
   - **rejected**: this is the user's explicit choice. Never re-introduce the exact same change, even if you remember suggesting it or notice it "missing" from the file. A genuinely different edit at the same location is fine (it has a different fingerprint and will be reviewed normally).
   - **accepted**: established code. Do not revert, duplicate, or re-generate it; build further edits on top of it.
   - Only the user explicitly asking to bring a rejected suggestion back overrides a rejection.
4. Perform the accidental-prevention: if `generate` prints a line starting with `WARNING RESURRECTED`, a rejected change exists in the current tree (likely re-introduced by your edit). You MUST restore the file to the rejected state for the reported file and fingerprint before finishing — do not leave rejected changes alive.
5. **After all modifications**, run:
   `python scripts/ai_patch.py --root <project-root> generate`
   - Diffs baseline A against the current tree, matches regions by fingerprint, and writes `.ai_diff/ai-changes.patch` containing only undecided blocks, with hunk line numbers aligned to the current file.
   - Empty patch is valid and means no pending changes.

Never run `ensure` after editing and assume it updated A — it is a no-op once the baseline exists. If `generate` reports no baseline exists, the pre-edit `ensure` step was skipped: STOP and tell the user. Do not snapshot already-modified files as the baseline; that would silently corrupt A.

## File rules

- File enumeration prefers `git ls-files --cached --others --exclude-standard` when the project is a git repo; otherwise it walks the tree. Both always additionally exclude `.ai_diff`, `.git`, and common build/IDE directories (`.idea`, `.gradle`, `node_modules`, `target`, `build`, `dist`, `out`, `__pycache__`).
- New files appear with `--- /dev/null`; deleted files with `+++ /dev/null`; modified files with normal a/b headers. Binary files get `Binary files ... differ` lines and cannot carry decisions; they always appear until the next `reset`.
- Encoding is UTF-8; CRLF line endings are preserved; files without a final newline get a `\ No newline at end of file` marker.

## Baseline and decisions lifecycle

- `reset`: `python scripts/ai_patch.py --root <project-root> reset` discards baseline, current patch and decisions, then makes the CURRENT tree the new A. Use only when the user explicitly asks to re-baseline — typically after they have finished reviewing all previous changes. Do not call it on your own initiative; never delete or rewrite `decisions.jsonl` by hand.
- Recommend adding `.ai_diff/` to the project's `.gitignore` (baseline snapshots and working files should not be committed).

## Verification

After `generate`, confirm the patch covers the intended undecided files, paths use `/` separators, and no `RESURRECTED` warnings remain. If the IDE plugin shows a file as a conflict, its current content no longer matches the patch; report that to the user rather than forcing the change.
