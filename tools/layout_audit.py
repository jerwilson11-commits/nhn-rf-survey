#!/usr/bin/env python3
"""
Finds Compose rows that can clip their own text.

WHY THIS EXISTS
---------------
Three text-collision defects shipped inside one week -- an ARFCN overflowing its field, the map
strip clipping "PCI", and a verdict headline wrapping into its own marker. Every one was the same
shape: a Row holding two or more Texts, none of them told what to do when the row is narrower than
their sum. On the 411 dp phone the layouts were written against there is usually room; on a
narrower screen, at a larger font scale, or the first time a value is longer than the developer
imagined, there is not.

Finding the fourth one by reading 4,300 lines of UI code is the method that already failed three
times, so this looks instead.

WHAT IT IS, AND IS NOT
----------------------
This is a heuristic over source text, not a layout engine. It brace-matches each Row(...) block and
flags any that holds two or more Text( calls with no width discipline -- no weight(), no maxLines,
and no YieldingText. It will miss a row built out of a helper composable, and it will occasionally
flag a row whose texts are both short constants and genuinely safe.

It is worth having anyway: it turns "I hope I found them all" into a number, and the number is
checkable before a release rather than discoverable in the field.

USAGE
-----
    python tools/layout_audit.py            # report, exit 1 if anything is unguarded
    python tools/layout_audit.py --list     # report every multi-Text row, guarded or not
"""

import glob
import io
import os
import re
import sys

UI_GLOB = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "java", "com", "nhnengineering", "rftest", "ui", "*.kt",
)

GUARDS = ("weight(", "maxLines", "YieldingText(")


def row_blocks(source):
    """Yield (line_number, block_text) for each Row(...) { ... } in the file."""
    for match in re.finditer(r"\bRow\s*\(", source):
        i, depth = match.end(), 1
        while i < len(source) and depth > 0:
            if source[i] in "([{":
                depth += 1
            elif source[i] in ")]}":
                depth -= 1
            i += 1
        j = i
        while j < len(source) and source[j] in " \t\n":
            j += 1
        if j < len(source) and source[j] == "{":
            depth, j = 1, j + 1
            while j < len(source) and depth > 0:
                if source[j] == "{":
                    depth += 1
                elif source[j] == "}":
                    depth -= 1
                j += 1
            end = j
        else:
            end = i
        yield source[:match.start()].count("\n") + 1, source[match.start():end]


def main():
    show_all = "--list" in sys.argv
    unguarded, guarded = [], []

    for path in sorted(glob.glob(UI_GLOB)):
        source = io.open(path, encoding="utf-8").read()
        name = os.path.basename(path)
        for line, block in row_blocks(source):
            # A nested Row reports itself; only judge the innermost.
            if block.count("Row(") > 1:
                continue
            texts = len(re.findall(r"\bText\s*\(", block))
            if texts < 2:
                continue
            (guarded if any(g in block for g in GUARDS) else unguarded).append(
                (name, line, texts)
            )

    if show_all:
        print(f"{'file':<24}{'line':>6}{'texts':>7}  state")
        for name, line, n in sorted(guarded + unguarded):
            state = "guarded" if (name, line, n) in guarded else "UNGUARDED"
            print(f"{name:<24}{line:>6}{n:>7}  {state}")
        print()

    if unguarded:
        print(f"{len(unguarded)} row(s) hold multiple texts with no width discipline:\n")
        for name, line, n in unguarded:
            print(f"  {name}:{line}  ({n} texts)")
        print(
            "\nIn any row holding a variable-length text, exactly one of them should be a\n"
            "YieldingText. The measurement never yields -- the label does."
        )
        return 1

    print(f"No unguarded multi-text rows. ({len(guarded)} guarded rows checked.)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
