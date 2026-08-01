#!/usr/bin/env python3
"""Compute CRAP scores from detekt cyclomatic complexity + Kover/JaCoCo coverage.

CRAP(m) = CC(m)^2 * (1 - coverage(m))^3 + CC(m)

Joins detekt CyclomaticComplexMethod findings (CC) to Kover XML method coverage.
When a method cannot be matched, coverage defaults to 0.0 (fully uncovered).
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

DETEKT_CC_RE = re.compile(
    r"[Tt]he function [`']?(?P<name>[^`'\s]+)[`']?(?:"
    r" has a [Cc]yclomatic [Cc]omplexity of (?P<cc_old>\d+)"
    r"| appears to be too complex based on Cyclomatic Complexity \(complexity: (?P<cc_new>\d+)\)"
    r")"
)
SYNTHETIC_NAME_RE = re.compile(
    r"^(<clinit>|<init>|access\$|.*\$default$|.*\$lambda\$|compose\$|.*\$anonymous\$)"
)
# Kotlin bytecode mangling for inline/value params, e.g. resolveDropAction-N6fFfp4
# or resolveHoverPreview-8fL75-g — strip from the first '-'.
def normalize_method_name(name: str) -> str:
    idx = name.find("-")
    if idx > 0:
        return name[:idx]
    return name


@dataclass(frozen=True)
class ComplexityFinding:
    path: str
    name: str
    line: int
    cc: int


@dataclass
class CoverageMethod:
    path: str
    class_name: str
    name: str
    line: int
    coverage: float
    coverage_kind: str


@dataclass
class CrapRow:
    path: str
    name: str
    line: int
    cc: int
    coverage: float
    coverage_kind: str
    crap: float
    matched: bool


def normalize_path(path: str) -> str:
    p = path.replace("\\", "/")
    markers = ("/src/main/java/", "/src/main/kotlin/", "/src/debug/java/", "/src/debug/kotlin/")
    lower = p.lower()
    for marker in markers:
        idx = lower.find(marker)
        if idx >= 0:
            return p[idx + len(marker) :]
    return Path(p).name


def coverage_fraction(counters: dict[str, tuple[int, int]]) -> tuple[float, str]:
    if "BRANCH" in counters:
        missed, covered = counters["BRANCH"]
        total = missed + covered
        if total > 0:
            return covered / total, "branch"
    if "INSTRUCTION" in counters:
        missed, covered = counters["INSTRUCTION"]
        total = missed + covered
        if total > 0:
            return covered / total, "instruction"
        return 0.0, "instruction"
    return 0.0, "N/A"


def parse_detekt(path: Path) -> list[ComplexityFinding]:
    root = ET.parse(path).getroot()
    findings: list[ComplexityFinding] = []
    for file_el in root.findall("file"):
        file_path = normalize_path(file_el.attrib.get("name", ""))
        for error in file_el.findall("error"):
            source = error.attrib.get("source", "")
            if "CyclomaticComplexMethod" not in source:
                continue
            message = error.attrib.get("message", "")
            match = DETEKT_CC_RE.search(message)
            if not match:
                continue
            cc_raw = match.group("cc_new") or match.group("cc_old")
            findings.append(
                ComplexityFinding(
                    path=file_path,
                    name=match.group("name"),
                    line=int(error.attrib.get("line", "0") or 0),
                    cc=int(cc_raw),
                )
            )
    return findings


def parse_kover(path: Path) -> list[CoverageMethod]:
    root = ET.parse(path).getroot()
    methods: list[CoverageMethod] = []
    for package in root.findall("package"):
        for class_el in package.findall("class"):
            source = class_el.attrib.get("sourcefilename", "")
            class_name = class_el.attrib.get("name", "").replace("/", ".")
            class_path = normalize_path(source) if source else class_name
            # Prefer package-relative source path when available via sourcefilename only.
            for method in class_el.findall("method"):
                name = normalize_method_name(method.attrib.get("name", ""))
                if SYNTHETIC_NAME_RE.match(name) or not name:
                    continue
                line = int(method.attrib.get("line", "0") or 0)
                counters: dict[str, tuple[int, int]] = {}
                for counter in method.findall("counter"):
                    ctype = counter.attrib.get("type", "")
                    missed = int(counter.attrib.get("missed", "0"))
                    covered = int(counter.attrib.get("covered", "0"))
                    counters[ctype] = (missed, covered)
                cov, kind = coverage_fraction(counters)
                methods.append(
                    CoverageMethod(
                        path=class_path,
                        class_name=class_name,
                        name=name,
                        line=line,
                        coverage=cov,
                        coverage_kind=kind,
                    )
                )
    return methods


def basename(path: str) -> str:
    return Path(path.replace("\\", "/")).name


def find_coverage(
    finding: ComplexityFinding, coverage_by_key: dict[tuple[str, str], list[CoverageMethod]]
) -> CoverageMethod | None:
    keys = [
        (finding.path, finding.name),
        (basename(finding.path), finding.name),
    ]
    candidates: list[CoverageMethod] = []
    for key in keys:
        candidates.extend(coverage_by_key.get(key, []))
    if not candidates:
        # Fall back to name-only matches in same file basename.
        for (path_key, name), values in coverage_by_key.items():
            if name == finding.name and basename(path_key) == basename(finding.path):
                candidates.extend(values)
    if not candidates:
        return None
    if finding.line <= 0:
        return candidates[0]
    return min(candidates, key=lambda m: abs((m.line or 0) - finding.line))


def crap_score(cc: int, coverage: float) -> float:
    uncovered = max(0.0, min(1.0, 1.0 - coverage))
    return (cc * cc) * (uncovered ** 3) + cc


def render_markdown(rows: list[CrapRow], threshold: float | None) -> str:
    lines = [
        "# CRAP report",
        "",
        "`CRAP = CC^2 * (1 - coverage)^3 + CC`",
        "",
        f"Methods analyzed: **{len(rows)}**",
    ]
    if threshold is not None:
        failing = sum(1 for r in rows if r.crap > threshold)
        lines.append(f"Threshold: **{threshold}** — failing: **{failing}**")
    lines.extend(
        [
            "",
            "| CRAP | CC | Cov | Kind | Method | File:line | Matched |",
            "| ---: | -: | --: | --- | --- | --- | --- |",
        ]
    )
    for row in rows:
        lines.append(
            f"| {row.crap:.2f} | {row.cc} | {row.coverage * 100:.1f}% | {row.coverage_kind} "
            f"| `{row.name}` | `{row.path}:{row.line}` | {'yes' if row.matched else 'no'} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kover", required=True, type=Path, help="Kover/JaCoCo XML report")
    parser.add_argument("--detekt", required=True, type=Path, help="detekt checkstyle XML report")
    parser.add_argument("--out", type=Path, help="Markdown report output path")
    parser.add_argument("--top", type=int, default=50, help="Print top N rows to stdout")
    parser.add_argument(
        "--max-crap",
        type=float,
        default=None,
        help="Fail (exit 2) when any method exceeds this CRAP score",
    )
    args = parser.parse_args()

    if not args.kover.is_file():
        print(f"error: Kover report not found: {args.kover}", file=sys.stderr)
        return 1
    if not args.detekt.is_file():
        print(f"error: detekt report not found: {args.detekt}", file=sys.stderr)
        return 1

    findings = parse_detekt(args.detekt)
    coverage_methods = parse_kover(args.kover)

    coverage_by_key: dict[tuple[str, str], list[CoverageMethod]] = {}
    for method in coverage_methods:
        coverage_by_key.setdefault((method.path, method.name), []).append(method)
        coverage_by_key.setdefault((basename(method.path), method.name), []).append(method)

    rows: list[CrapRow] = []
    for finding in findings:
        matched = find_coverage(finding, coverage_by_key)
        coverage = matched.coverage if matched else 0.0
        kind = matched.coverage_kind if matched else "unmatched"
        rows.append(
            CrapRow(
                path=finding.path,
                name=finding.name,
                line=finding.line,
                cc=finding.cc,
                coverage=coverage,
                coverage_kind=kind,
                crap=crap_score(finding.cc, coverage),
                matched=matched is not None,
            )
        )

    rows.sort(key=lambda r: (-r.crap, -r.cc, r.path, r.name))

    markdown = render_markdown(rows, args.max_crap)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(markdown, encoding="utf-8")
        print(f"Wrote {args.out}")

    top = rows[: max(0, args.top)]
    print(f"{'CRAP':>8} {'CC':>4} {'COV':>7}  METHOD")
    for row in top:
        print(
            f"{row.crap:8.2f} {row.cc:4d} {row.coverage * 100:6.1f}%  "
            f"{row.name} ({row.path}:{row.line})"
        )

    unmatched = sum(1 for r in rows if not r.matched)
    print(f"\nTotal methods: {len(rows)}  unmatched coverage: {unmatched}")

    if args.max_crap is not None:
        worst = max((r.crap for r in rows), default=0.0)
        if worst > args.max_crap:
            print(f"FAIL: max CRAP {worst:.2f} exceeds threshold {args.max_crap}", file=sys.stderr)
            return 2
        print(f"PASS: max CRAP {worst:.2f} <= {args.max_crap}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
