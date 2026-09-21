"""Scorecard and error-budget bookkeeping. Every rung records ONE JSON row per gate; nothing is
typed by hand, so the scorecard cannot drift from what was measured."""
from __future__ import annotations

import json
import math
import os
import sys
import tempfile
from pathlib import Path

RESULTS = Path(os.environ.get("PARITY_RESULTS", "E:/Output/parity/results"))


def record(rung: str, source: str, gate: str, value: float, threshold: float | None,
           provenance: str, passed: bool | None, note: str = "", root: Path | None = None) -> Path:
    """Write one result. `provenance` says where the threshold came from: 'spec', 'measured floor
    (<file>)' or 'PROVISIONAL: chosen before measurement, no prior data'. passed=None means the
    row is a measurement with no gate (R0-style)."""
    root = Path(root) if root else RESULTS
    root.mkdir(parents=True, exist_ok=True)
    row = {"rung": rung, "source": source, "gate": gate,
           "value": None if value is None or (isinstance(value, float) and math.isnan(value)) else float(value),
           "threshold": threshold, "provenance": provenance, "passed": passed, "note": note}
    p = root / f"{rung}__{source}__{gate}.json".replace(" ", "_")
    p.write_text(json.dumps(row, indent=2), encoding="utf-8")
    return p


def load(root: Path | None = None) -> list[dict]:
    root = Path(root) if root else RESULTS
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(root.glob("*.json"))]


def scorecard_md(rows: list[dict]) -> str:
    def cell(r):
        v = "n/a" if r["value"] is None else f"{r['value']:.4g}"
        t = "" if r["threshold"] is None else f" (gate {r['threshold']:.4g})"
        s = "MEASURED" if r["passed"] is None else ("PASS" if r["passed"] else "FAIL")
        return f"{s} {v}{t}"
    lines = ["| Rung | Source | Gate | Result | Threshold provenance | Note |", "|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['rung']} | {r['source']} | {r['gate']} | {cell(r)} | {r['provenance']} | {r['note']} |")
    return "\n".join(lines)


def budget_row(residual_rad: float, terms: dict[str, float]) -> dict:
    """Decompose a measured residual into named terms (each an RMS in rad, assumed independent):
    explained = sqrt(sum t^2), unexplained = sqrt(max(res^2 - explained^2, 0)). A term that alone
    exceeds the residual is flagged, because an over-explained residual means a term is wrong."""
    expl2 = sum(t * t for t in terms.values())
    over = [k for k, t in terms.items() if t > residual_rad * 1.0000001]
    return {"residual_rad": residual_rad, "explained_rad": math.sqrt(expl2),
            "unexplained_rad": math.sqrt(max(residual_rad ** 2 - expl2, 0.0)),
            "coverage": (expl2 / residual_rad ** 2) if residual_rad > 0 else float("nan"),
            "over_explained": over, "terms": dict(terms)}


def _selftest() -> int:
    ok = True
    with tempfile.TemporaryDirectory() as d:
        record("R3", "venezuela", "closure-rms", 0.21, 0.3, "PROVISIONAL: chosen before measurement", True, root=Path(d))
        record("R1", "venezuela", "rms", float("nan"), 0.05, "spec", False, "no data", root=Path(d))
        record("R0", "venezuela", "coh", 0.4, None, "n/a", None, root=Path(d))
        rows = load(Path(d))
        md = scorecard_md(rows)
        print(md)
        if len(rows) != 3 or "PASS 0.21 (gate 0.3)" not in md or "FAIL n/a (gate 0.05)" not in md or "MEASURED 0.4" not in md:
            print("  FAIL: scorecard")
            ok = False
        record("R3", "venezuela", "closure-rms", 0.25, 0.3, "x", True, root=Path(d))
        if len(load(Path(d))) != 3:
            print("  FAIL: re-recording a gate must replace, not duplicate")
            ok = False
    b = budget_row(0.5, {"ETAD": 0.3, "geoloc": 0.2})
    print(b)
    if not (abs(b["explained_rad"] - math.sqrt(0.13)) < 1e-12 and abs(b["unexplained_rad"] - math.sqrt(0.12)) < 1e-12
            and not b["over_explained"]):
        print("  FAIL: budget arithmetic")
        ok = False
    if budget_row(0.1, {"a": 0.3})["over_explained"] != ["a"] or budget_row(0.1, {"a": 0.3})["unexplained_rad"] != 0.0:
        print("  FAIL: over-explained residual must be flagged")
        ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
    if "--scorecard" in sys.argv:
        print(scorecard_md(load()))
        raise SystemExit(0)
    print("usage: budget.py --selftest | --scorecard")
    raise SystemExit(2)
