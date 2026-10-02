#!/usr/bin/env python3
"""
ai_patch.py — frozen-baseline cumulative patch generator with decisions.

Subcommands:
  ensure    Snapshot the current tree into .ai_diff/baseline ONLY if no
            baseline exists. Safe to call repeatedly; never refreshes A.
  generate  Diff frozen baseline A against current tree, match every changed
            region against .ai_diff/decisions.jsonl, and write
            .ai_diff/ai-changes.patch containing ONLY undecided blocks.
            Emits RESURRECTED warnings if AI re-applied a rejected change.
  reset     Discard baseline, patch and decisions; current tree becomes new A.

Fingerprint convention (shared with the IDE plugin DecisionStore):
  sha256_hex( join("\n", removed) + '\u0000' + join("\n", added) ), UTF-8.

Uses only the Python standard library. Works on Windows, macOS and Linux.
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from difflib import SequenceMatcher

AI_DIR = ".ai_diff"
BASELINE_DIRNAME = "baseline"
READY_MARKER = ".baseline-ready"
PATCH_NAME = "ai-changes.patch"
DECISIONS_NAME = "decisions.jsonl"

CONTEXT = 3

# Directories ignored in both snapshot and current-tree enumeration.
EXCLUDE_DIRS = {
    ".git", ".ai_diff", ".idea", ".gradle", "node_modules",
    "target", "build", "dist", "out", "__pycache__",
}


# ---------------------------------------------------------------- paths

def ai_dir(root):
    return os.path.join(root, AI_DIR)


def baseline_dir(root):
    return os.path.join(ai_dir(root), BASELINE_DIRNAME)


def ready_marker(root):
    return os.path.join(ai_dir(root), READY_MARKER)


def patch_path(root):
    return os.path.join(ai_dir(root), PATCH_NAME)


def decisions_path(root):
    return os.path.join(ai_dir(root), DECISIONS_NAME)


def is_excluded(rel):
    parts = rel.replace("\\", "/").split("/")
    return any(p in EXCLUDE_DIRS for p in parts)


# ---------------------------------------------------------- enumeration

def enumerate_files(root):
    """Return sorted list of repo-relative paths using '/' separators."""
    files = set()
    git_out = None
    try:
        proc = subprocess.run(
            ["git", "-C", root, "ls-files", "-z",
             "--cached", "--others", "--exclude-standard"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True,
        )
        git_out = proc.stdout.decode("utf-8", "surrogateescape")
    except (OSError, subprocess.CalledProcessError):
        git_out = None

    if git_out is not None:
        for p in git_out.split("\0"):
            if p and not is_excluded(p):
                files.add(p)
    else:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
            for name in filenames:
                abs_p = os.path.join(dirpath, name)
                rel = os.path.relpath(abs_p, root).replace("\\", "/")
                if not is_excluded(rel):
                    files.add(rel)

    return sorted(files)


def enumerate_baseline(root):
    """All files currently stored in the baseline snapshot."""
    base = baseline_dir(root)
    if not os.path.isdir(base):
        return []
    out = []
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in filenames:
            abs_p = os.path.join(dirpath, name)
            rel = os.path.relpath(abs_p, base).replace("\\", "/")
            out.append(rel)
    return sorted(out)


# ------------------------------------------------------------- commands

def cmd_ensure(root):
    if os.path.isfile(ready_marker(root)):
        print("baseline already exists; no-op")
        return 0

    os.makedirs(baseline_dir(root), exist_ok=True)
    count = 0
    for rel in enumerate_files(root):
        src = os.path.join(root, rel)
        dst = os.path.join(baseline_dir(root), rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)
        count += 1

    with open(ready_marker(root), "w", encoding="utf-8") as fh:
        fh.write("1\n")
    print(f"baseline created from current tree ({count} files)")
    return 0


def cmd_reset(root):
    base = baseline_dir(root)
    if os.path.isdir(base):
        shutil.rmtree(base)
    for p in (ready_marker(root), patch_path(root), decisions_path(root)):
        if os.path.isfile(p):
            os.remove(p)
    print("old baseline, patch and decisions discarded")
    return cmd_ensure(root)


# ------------------------------------------------------------- reading

def read_bytes(folder, rel):
    abs_p = os.path.join(folder, rel)
    if not os.path.isfile(abs_p):
        return None
    with open(abs_p, "rb") as fh:
        return fh.read()


def is_binary(data):
    if data is None:
        return False
    return b"\x00" in data[:8192]


def split_text_lines(data):
    """Decode UTF-8 and split to content lines; report final newline."""
    text = data.decode("utf-8")
    raw = text.splitlines(keepends=True)
    lines = []
    for ln in raw:
        if ln.endswith("\r\n"):
            lines.append(ln[:-2])
        elif ln.endswith("\n") or ln.endswith("\r"):
            lines.append(ln[:-1])
        else:
            lines.append(ln)
    ends_with_newline = text == "" or text.endswith(("\n", "\r"))
    return lines, ends_with_newline


# ----------------------------------------------------------- decisions

def fingerprint(removed, added):
    canonical = "\n".join(removed) + "\u0000" + "\n".join(added)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def load_decisions(root):
    """Return {file: {fingerprint: decision}}; later lines win."""
    path = decisions_path(root)
    result = {}
    if not os.path.isfile(path):
        return result
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except ValueError:
                continue
            f = rec.get("file")
            fp = rec.get("fingerprint")
            d = rec.get("decision")
            if f and fp and d in ("accepted", "rejected"):
                result.setdefault(f, {})[fp] = d
    return result


# --------------------------------------------------- effective baseline

class PendingRegion:
    """A changed block with no decision yet; must appear in the patch."""

    def __init__(self, i1, i2, j1, j2, eff_start):
        self.i1 = i1          # baseline slice start
        self.i2 = i2          # baseline slice end
        self.j1 = j1          # current slice start
        self.j2 = j2          # current slice end
        self.eff_start = eff_start  # start line in effective baseline
        self.removed_count = i2 - i1


def build_effective(before, after, file_decisions):
    """
    Walk A-vs-current opcodes and construct the effective baseline B_eff:
      - equal context                       -> same lines
      - block matching accepted decision    -> added lines (established code)
      - block matching rejected decision    -> added lines (suppressed in patch)
      - undecided block                     -> removed lines (shows as pending)

    Returns (eff_lines, pending regions in order, resurrected list).
    """
    eff = []
    pending = []
    resurrected = []

    for tag, i1, i2, j1, j2 in SequenceMatcher(None, before, after).get_opcodes():
        if tag == "equal":
            eff.extend(before[i1:i2])
            continue

        removed = before[i1:i2]
        added = after[j1:j2]
        decision = file_decisions.get(fingerprint(removed, added))

        if decision is None:
            eff_start = len(eff)
            eff.extend(removed)
            pending.append(PendingRegion(i1, i2, j1, j2, eff_start))
        else:
            # Decided (accepted, or rejected but currently present).
            eff.extend(added)
            if decision == "rejected":
                resurrected.append((removed, added))

    return eff, pending, resurrected


# ------------------------------------------------------------ hunks

def range_label(start, stop):
    """Unified diff hunk range label, matching difflib/git conventions."""
    length = stop - start
    beginning = start + 1
    if length == 0:
        beginning -= 1
    if length == 1:
        return str(beginning)
    return f"{beginning},{length}"


class HunkGroup:
    def __init__(self, region):
        self.regions = [region]
        self.c0 = max(0, region.j1 - CONTEXT)
        self.c1 = region.j2 + CONTEXT  # clamped later
        self.e0 = max(0, region.eff_start - CONTEXT)

    def add(self, region):
        self.regions.append(region)
        self.c1 = region.j2 + CONTEXT
        self.e0 = min(self.e0, max(0, region.eff_start - CONTEXT))


def group_hunks(pending, n_eff, n_after):
    groups = []
    for region in pending:
        win_start = max(0, region.j1 - CONTEXT)
        if groups and win_start <= groups[-1].c1:
            groups[-1].add(region)
        else:
            groups.append(HunkGroup(region))
    for g in groups:
        g.c0 = max(0, g.regions[0].j1 - CONTEXT)
        g.c1 = min(n_after, g.regions[-1].j2 + CONTEXT)
        last = g.regions[-1]
        eff_end = last.eff_start + last.removed_count + CONTEXT
        g.e1 = min(n_eff, eff_end)
    return groups


def emit_hunk(group, before, after, before_nl, after_nl, opcodes):
    c0, c1, e0, e1 = group.c0, group.c1, group.e0, group.e1
    lines = [f"@@ -{range_label(e0, e1)} +{range_label(c0, c1)} @@"]

    pending_keys = {(r.i1, r.i2, r.j1, r.j2) for r in group.regions}

    for tag, i1, i2, j1, j2 in opcodes:
        if j2 <= c0 or j1 >= c1:
            continue
        if tag == "equal":
            s, e = max(j1, c0), min(j2, c1)
            for ln in after[s:e]:
                lines.append(" " + ln)
            continue

        removed = before[i1:i2]
        added = after[j1:j2]
        if (i1, i2, j1, j2) not in pending_keys:
            # Suppressed decided region inside this hunk window -> context.
            s, e = max(j1, c0), min(j2, c1)
            for ln in added[s - j1:e - j1]:
                lines.append(" " + ln)
            continue

        for ln in removed:
            lines.append("-" + ln)
        if not before_nl and i2 == len(before):
            lines.append("\\ No newline at end of file")
        for ln in added:
            lines.append("+" + ln)
        if not after_nl and j2 == len(after):
            lines.append("\\ No newline at end of file")

    return lines


# ------------------------------------------------------------ per file

def build_file_patch(rel, before_bytes, after_bytes, decisions):
    """Return (patch lines or None, resurrected entries)."""
    if before_bytes == after_bytes:
        return None, []

    header_before = "a/" + rel
    header_after = "b/" + rel
    if before_bytes is None:
        header_before = "/dev/null"
    if after_bytes is None:
        header_after = "/dev/null"

    if is_binary(before_bytes) or is_binary(after_bytes):
        return [f"Binary files {header_before} and {header_after} differ"], []

    before, before_nl = split_text_lines(before_bytes or b"")
    after, after_nl = split_text_lines(after_bytes or b"")

    file_decisions = decisions.get(rel, {})
    eff, pending, resurrected = build_effective(before, after, file_decisions)
    if not pending:
        return None, resurrected

    opcodes = SequenceMatcher(None, before, after).get_opcodes()
    groups = group_hunks(pending, len(eff), len(after))

    out = [f"--- {header_before}", f"+++ {header_after}"]
    for group in groups:
        out.extend(emit_hunk(group, before, after, before_nl, after_nl, opcodes))
    return out, resurrected


def cmd_generate(root):
    if not os.path.isfile(ready_marker(root)):
        print(
            "error: no frozen baseline exists. Run 'ensure' BEFORE editing files.\n"
            "Refusing to snapshot already-modified content as the baseline.",
            file=sys.stderr,
        )
        return 2

    decisions = load_decisions(root)

    before_files = set(enumerate_baseline(root))
    after_files = set(enumerate_files(root))
    all_files = sorted(before_files | after_files)

    blocks = []
    warnings = []
    for rel in all_files:
        before = read_bytes(baseline_dir(root), rel) if rel in before_files else None
        after = read_bytes(root, rel) if rel in after_files else None
        patch_lines, resurrected = build_file_patch(rel, before, after, decisions)
        if patch_lines:
            blocks.append("\n".join(patch_lines))
        for removed, added in resurrected:
            warnings.append(
                f"RESURRECTED file={rel} removed={len(removed)} added={len(added)} "
                f"fingerprint={fingerprint(removed, added)}"
            )

    os.makedirs(ai_dir(root), exist_ok=True)
    text = "\n".join(blocks)
    if text:
        text += "\n"
    with open(patch_path(root), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)

    print(f"patch written: {PATCH_NAME} ({len(blocks)} file(s) with pending changes)")
    for w in warnings:
        print("WARNING " + w)
    return 0


# ----------------------------------------------------------------- main

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root", default=os.getcwd(),
        help="project root (defaults to current directory)",
    )
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("ensure")
    sub.add_parser("generate")
    sub.add_parser("reset")

    args = parser.parse_args(argv)
    root = os.path.abspath(args.root)
    if not os.path.isdir(root):
        print(f"error: project root not found: {root}", file=sys.stderr)
        return 2

    if args.command == "ensure":
        return cmd_ensure(root)
    if args.command == "generate":
        return cmd_generate(root)
    if args.command == "reset":
        return cmd_reset(root)
    return 2


if __name__ == "__main__":
    sys.exit(main())
