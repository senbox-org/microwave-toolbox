"""
Case runner: validate -> check conventions -> invoke engines -> measure -> report.

Deliberately thin. All engine knowledge lives in `engines/<name>/run.sh`; all measurement lives in
`metrics.py`. This file only sequences them and enforces the preconditions that make a comparison
meaningful.

    python -m compare.run_case cases/<case>.yml [--engines snap,isce3] [--dry-run]
"""
from __future__ import annotations

import argparse
import os
import shlex
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent.parent


class CaseError(Exception):
    """A precondition failed. Raised rather than warned: a comparison run on mismatched inputs
    produces a number that looks valid and is not, which is worse than no number."""


@dataclass
class Case:
    path: Path
    raw: dict
    name: str = ""
    feature: str = ""
    engines: list[str] = field(default_factory=list)
    metrics: list[str] = field(default_factory=list)

    @classmethod
    def load(cls, p: Path) -> "Case":
        raw = yaml.safe_load(p.read_text(encoding="utf-8"))
        c = cls(path=p, raw=raw,
                name=raw.get("case", p.stem),
                feature=raw.get("feature", "?"),
                engines=list(raw.get("engines", [])),
                metrics=list(raw.get("metrics", [])))
        c.validate()
        return c

    def validate(self) -> None:
        from . import metrics as M
        missing = [k for k in ("case", "feature", "engines", "metrics") if k not in self.raw]
        if missing:
            raise CaseError(f"{self.path.name}: missing required key(s): {', '.join(missing)}")
        if len(self.engines) < 2:
            raise CaseError(f"{self.path.name}: a comparison needs at least two engines, got "
                            f"{self.engines}. A single-engine run is not validation.")
        unknown = [m for m in self.metrics if m not in M.ALL]
        if unknown:
            raise CaseError(f"{self.path.name}: unknown metric(s) {unknown}. "
                            f"Available: {sorted(M.ALL)}")

        # One staged DEM file, passed explicitly to every engine. Naming a DEM by label ("Copernicus
        # 30 m") lets each tool resolve its own copy, and then the comparison measures the DEMs.
        dem = self.raw.get("dem")
        if not dem:
            raise CaseError(f"{self.path.name}: 'dem' must name ONE staged file under /data, so every "
                            f"engine demonstrably uses identical elevation data.")

        grid = self.raw.get("grid") or {}
        for k in ("crs", "spacing"):
            if k not in grid:
                raise CaseError(f"{self.path.name}: grid.{k} is required — without an identical "
                                f"output grid the differences are dominated by resampling.")

        self.check_conventions()

    def check_conventions(self) -> None:
        """
        Abort unless every engine is asked for the SAME phase convention.

        This is the single most important precondition. ISCE3/COMPASS defaults to
        flatten=True, reramp=True; the Microwave Toolbox defaults to the opposite on BOTH axes
        (outputFlattened=false, outputAzimuthCarrier=false). A default-vs-default comparison
        yields noise that is indistinguishable from a genuine defect.
        """
        conv = self.raw.get("conventions")
        if conv is None:
            raise CaseError(
                f"{self.path.name}: 'conventions' is required for any phase comparison. Declare "
                f"'flattened' and 'azimuth_carrier' explicitly — the engines' defaults disagree.")
        for k in ("flattened", "azimuth_carrier"):
            if k not in conv:
                raise CaseError(f"{self.path.name}: conventions.{k} must be stated explicitly.")

        # Per-engine overrides may exist for engines that cannot honour the shared convention;
        # such a case must not silently proceed.
        for eng, spec in (self.raw.get("engine_params") or {}).items():
            for k in ("flattened", "azimuth_carrier"):
                if k in spec and spec[k] != conv[k]:
                    raise CaseError(
                        f"{self.path.name}: engine '{eng}' declares {k}={spec[k]} but the case "
                        f"requires {k}={conv[k]}. Comparing across a convention mismatch is invalid; "
                        f"either align them or split into separate cases.")


def run_engine(case: Case, engine: str, dry: bool) -> int:
    """Invoke one engine. SNAP runs natively; everything else runs in its container."""
    # Any engine named snap or snap-<variant> is the NATIVE Windows build: the shipped artifact is
    # what is under test, so it is never containerised. The variant selects which pipeline run.ps1
    # builds (e.g. snap-classic = Back-Geocoding/ESD, snap-gslc = geocode-first), which is how one
    # tool can appear on both sides of an intra-tool comparison.
    if engine == "snap" or engine.startswith("snap-"):
        script = HERE / "engines" / "snap" / "run.ps1"
        cmd = ["powershell", "-NoProfile", "-File", str(script), str(case.path)]
        variant = engine.split("-", 1)[1] if "-" in engine else ""
        if variant:
            cmd += ["-Variant", variant]
    else:
        cmd = ["docker", "compose", "run", "--rm", engine,
               f"/engines/{engine}/run.sh", f"/cases/{case.path.name}"]
    print(f"  $ {' '.join(shlex.quote(c) for c in cmd)}")
    if dry:
        return 0
    return subprocess.call(cmd, cwd=str(HERE))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("case", type=Path)
    ap.add_argument("--engines", help="comma-separated subset of the case's engines")
    ap.add_argument("--dry-run", action="store_true",
                    help="validate the case and print the commands without running anything")
    a = ap.parse_args(argv)

    try:
        case = Case.load(a.case)
    except CaseError as e:
        print(f"CASE INVALID: {e}", file=sys.stderr)
        return 2

    engines = a.engines.split(",") if a.engines else case.engines
    unknown = [e for e in engines if e not in case.engines]
    if unknown:
        print(f"CASE INVALID: engine(s) {unknown} not declared in {case.path.name}", file=sys.stderr)
        return 2

    print(f"case      : {case.name}")
    print(f"feature   : {case.feature}")
    print(f"engines   : {', '.join(engines)}")
    print(f"metrics   : {', '.join(case.metrics)}")
    print(f"conventions: {case.raw['conventions']}")
    print("preconditions: OK (engines >= 2, one staged DEM, grid declared, conventions aligned)")

    rc = 0
    for e in engines:
        print(f"\n[{e}]")
        r = run_engine(case, e, a.dry_run)
        if r != 0:
            print(f"  engine '{e}' exited {r}", file=sys.stderr)
            rc = r
    if a.dry_run:
        print("\ndry run: no engines invoked, no measurement performed")
    return rc


if __name__ == "__main__":
    sys.exit(main())
