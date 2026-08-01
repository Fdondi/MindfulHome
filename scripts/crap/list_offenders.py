#!/usr/bin/env python3
"""List CRAP offenders over a threshold, grouped by file."""
from __future__ import annotations

import argparse
import re
from collections import defaultdict
from pathlib import Path

ROW_RE = re.compile(
    r"\| (?P<crap>[0-9.]+) \| (?P<cc>\d+) \| (?P<cov>[0-9.]+)% \| "
    r"(?P<kind>\w+) \| `(?P<name>[^`]+)` \| `(?P<path>[^:]+):(?P<line>\d+)`"
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, default=Path("results/crap/crap-report.md"))
    parser.add_argument("--max-crap", type=float, default=42.0)
    args = parser.parse_args()

    rows = []
    for line in args.report.read_text(encoding="utf-8").splitlines():
        m = ROW_RE.match(line)
        if not m:
            continue
        crap = float(m.group("crap"))
        if crap <= args.max_crap:
            continue
        rows.append(
            {
                "crap": crap,
                "cc": int(m.group("cc")),
                "cov": float(m.group("cov")),
                "name": m.group("name"),
                "path": m.group("path"),
                "line": int(m.group("line")),
            }
        )

    by_file: dict[str, list] = defaultdict(list)
    for row in rows:
        by_file[row["path"]].append(row)

    print(f"over_{args.max_crap:g}={len(rows)} files={len(by_file)}")
    print(f"must_split_cc_gt_{args.max_crap:g}={sum(1 for r in rows if r['cc'] > args.max_crap)}")
    for path, items in sorted(by_file.items(), key=lambda kv: -sum(x["crap"] for x in kv[1])):
        total = sum(x["crap"] for x in items)
        max_cc = max(x["cc"] for x in items)
        print(f"{total:10.1f} n={len(items):2d} maxcc={max_cc:3d} {path}")
        for it in sorted(items, key=lambda x: -x["crap"]):
            print(f"    {it['crap']:8.1f} cc={it['cc']:3d} {it['name']}:{it['line']}")


if __name__ == "__main__":
    main()
