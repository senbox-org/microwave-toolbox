"""
Translate a harness case YAML into a COMPASS runconfig.

Runs INSIDE the isce3 container. Kept separate from run.sh so it can be inspected and unit-tested
without invoking the workflow.

Two facts about COMPASS that constrain what a case may ask for, both read from the installed source
rather than assumed:

  * `flatten` IS configurable (runconfig processing.geocoding.flatten, default True).
  * `reramp` is NOT — `s1_geocode_slc.py` passes `reramp=True` literally. COMPASS can therefore only
    produce carrier-RESTORED output, so a case demanding `azimuth_carrier: removed` cannot be
    satisfied by this engine and is rejected here rather than silently producing the wrong convention.
"""
from __future__ import annotations

import argparse
import glob
import os
import sys
from pathlib import Path

import yaml

CASE_TO_COMPASS_POL = {"VV": "co-pol", "HH": "co-pol", "VH": "cross-pol", "HV": "cross-pol"}


class Unsatisfiable(Exception):
    """The case asks for something this engine cannot do. Fail loudly — a peer that quietly
    produces a different product than the case asked for invalidates the comparison."""


def die(msg: str) -> "None":
    print(f"ISCE3 ADAPTER: {msg}", file=sys.stderr)
    raise SystemExit(3)


def find_orbit(safe: str, search: list[str]) -> str:
    """
    Locate a POD orbit (EOF) whose validity window covers the SAFE. COMPASS requires it explicitly;
    there is no auto-download, so a missing orbit must be reported as a staging gap, not guessed at.
    """
    stem = Path(safe).name
    mission = stem[:3]                       # S1A / S1B / S1C
    for root in search:
        for pat in ("*.EOF", "*.eof", "*/*.EOF"):
            for cand in sorted(glob.glob(os.path.join(root, pat))):
                if mission in Path(cand).name:
                    return cand
    die(f"no POD orbit (.EOF) found for {stem} under {search}. COMPASS takes no auto-download: "
        f"stage the restituted/precise orbit alongside the scene under /data.")


def build(case_path: Path, out_dir: Path, role: str = "ref") -> Path:
    case = yaml.safe_load(case_path.read_text(encoding="utf-8"))
    name = case.get("case", case_path.stem)
    conv = case.get("conventions") or {}
    ep = (case.get("engine_params") or {}).get("isce3", {}) or {}

    # --- refuse what this engine cannot honour -----------------------------------------------
    carrier = str(conv.get("azimuth_carrier", "")).lower()
    if carrier in ("removed", "carrier_free", "carrier-free", "false", "off"):
        # COMPASS hardcodes reramp=True and cannot GEOCODE carrier-free. It does, however, always
        # write the `azimuth_carrier_phase` grid, so the carrier can be removed exactly afterwards
        # (remove_carrier.py: z *= exp(-1j*phi); exact even though phi is stored wrapped).
        # That post-step must be declared in the case, so a run can never reach a carrier-free
        # comparison by accident while the CSLC is still carrier-restored.
        if not ep.get("analytic_carrier_removal"):
            raise Unsatisfiable(
                f"case '{name}' requires azimuth_carrier={conv['azimuth_carrier']}, but COMPASS "
                f"hardcodes reramp=True (s1_geocode_slc.py) and cannot geocode carrier-free. Set "
                f"engine_params.isce3.analytic_carrier_removal: true to strip it afterwards from the "
                f"azimuth_carrier_phase grid, or exclude isce3 from this case.")
        print("ISCE3 ADAPTER: carrier   restored by geocoding, then REMOVED analytically "
              "(engine_params.isce3.analytic_carrier_removal)")

    # Both dates translate through the same code path; only the SAFE differs. Fields absent from
    # `secondary` inherit from `scene`, so subswath/polarisation cannot silently diverge between dates.
    base = case.get("scene") or {}
    if role == "sec":
        sec = case.get("secondary") or die("case.secondary.slc is required for role 'sec'")
        scene = {**base, **sec}
        safe = scene.get("slc") or die("case.secondary.slc is required")
    else:
        scene = base
        safe = scene.get("slc") or die("case.scene.slc is required")
    if not os.path.exists(safe):
        die(f"scene not found in the container: {safe} (is it under /data?)")

    dem = case.get("dem") or die("case.dem is required")
    if not os.path.exists(dem):
        die(f"staged DEM not found: {dem}. The harness requires ONE explicit DEM file shared by "
            f"every engine — see validation/README.md.")

    grid = case.get("grid") or {}
    spacing = grid.get("spacing") or die("case.grid.spacing is required")
    # grid.spacing may be the literal 'native', meaning the SNAP side DERIVES its step from
    # gridSpacing (NATIVE_ANISOTROPIC). COMPASS has no equivalent -- it takes explicit
    # x_posting/y_posting -- so the numbers must be supplied via engine_params after reading the grid
    # SNAP actually produced. Indexing the string gave float('n') and a ValueError.
    if isinstance(spacing, str):
        if not (ep.get("x_posting") and ep.get("y_posting")):
            die("case.grid.spacing is 'native' (a SNAP-derived grid), but COMPASS needs an explicit "
                "posting. Run the SNAP side first and set engine_params.isce3.x_posting/y_posting to "
                "the step it derived, so both engines land on ONE lattice.")
        x_posting, y_posting = float(ep["x_posting"]), float(ep["y_posting"])
    else:
        x_posting, y_posting = float(spacing[0]), float(spacing[1])

    pol = scene.get("polarisation", "VV").upper()
    orbit = find_orbit(safe, ["/data/orbits", str(Path(safe).parent), "/data"])

    product = out_dir / "product"
    scratch = out_dir / "scratch"
    product.mkdir(parents=True, exist_ok=True)
    scratch.mkdir(parents=True, exist_ok=True)

    rc = {
        "runconfig": {
            "name": f"harness_{name}_{role}",
            "groups": {
                "pge_name_group": {"pge_name": "CSLC_S1_PGE"},
                "input_file_group": {
                    "safe_file_path": [safe],
                    "orbit_file_path": [orbit],
                    # burst_id is list(str(), min=1, required=False): it must be OMITTED entirely to
                    # mean "all bursts", not set to null, which fails schema validation.
                    **({"burst_id": list(scene["burst_ids"])} if scene.get("burst_ids") else {}),
                },
                "dynamic_ancillary_file_group": {
                    "dem_file": dem,
                    "dem_description": "harness-staged, shared by every engine in this case",
                },
                # The schema requires this GROUP to be present even though its only member
                # (burst_database_file) is optional — omitting the group fails validation with
                # "static_ancillary_file_group: Required field missing".
                # UPSTREAM BLOCKER (COMPASS geo_runconfig.py:70-72 vs :101).
                # The schema marks burst_database_file optional, and line 101 has a genuine
                # `if burst_database_file is None:` branch — but line 71 calls
                # os.path.isfile(burst_database_file) FIRST, so a null raises
                # "TypeError: stat: path should be string ... not NoneType" and the None branch is
                # unreachable. Omitting the key does not help either: COMPASS merges our runconfig
                # over its own defaults, and defaults/s1_cslc_geo.yaml:29 supplies an empty
                # burst_database_file, i.e. None.
                # => the OPERA burst-database sqlite file is effectively MANDATORY. Stage it under
                #    /data and set `burst_database_file` in the case's engine_params.isce3.
                "static_ancillary_file_group": (
                    {"burst_database_file": ep["burst_database_file"]}
                    if ep.get("burst_database_file") else {}
                ),
                "product_path_group": {
                    "product_path": str(product),
                    "scratch_path": str(scratch),
                    "sas_output_file": str(product / f"{name}.h5"),
                },
                "primary_executable": {"product_type": "CSLC_S1"},
                "processing": {
                    "polarization": CASE_TO_COMPASS_POL.get(pol, "co-pol"),
                    "geocoding": {
                        # the one convention axis COMPASS exposes
                        "flatten": bool(ep.get("flatten", conv.get("flattened", True))),
                        "x_posting": x_posting,
                        "y_posting": y_posting,
                        # snapping the grid to a multiple of the posting is how both engines are made
                        # to land on ONE lattice; without it each picks its own origin and the
                        # comparison measures resampling rather than the algorithm.
                        "x_snap": x_posting,
                        "y_snap": y_posting,
                    },
                    "geo2rdr": {"lines_per_block": 1000, "threshold": 1.0e-8, "numiter": 25},
                },
            },
        }
    }

    out = out_dir / "runconfig.yaml"
    out.write_text(yaml.safe_dump(rc, sort_keys=False), encoding="utf-8")

    # --- restrict to the case's subswath ------------------------------------------------------
    # COMPASS has NO subswath parameter: left alone it processes IW1+IW2+IW3 (30 bursts for an IW
    # scene). The case names one subswath and the SNAP side splits to it, so without this the two
    # engines would geocode different scenes and the comparison would be meaningless.
    # Selection is by burst_id, and the IDs are not knowable until the SAFE is read -- hence a second
    # pass that asks COMPASS itself for them, rather than constructing IDs by string arithmetic.
    subswath = str(scene.get("subswath", "")).lower()
    if subswath and not scene.get("burst_ids"):
        from compass.utils.geo_runconfig import GeoRunConfig
        cfg = GeoRunConfig.load_from_yaml(str(out), "s1_cslc_geo")
        keep = sorted({str(b.burst_id) for b in cfg.bursts
                       if str(b.burst_id).lower().endswith(f"_{subswath}")})
        if not keep:
            die(f"no bursts matched subswath '{subswath}'. Present: "
                f"{sorted({str(b.burst_id).split('_')[-1] for b in cfg.bursts})}")
        rc["runconfig"]["groups"]["input_file_group"]["burst_id"] = keep
        out.write_text(yaml.safe_dump(rc, sort_keys=False), encoding="utf-8")
        print(f"ISCE3 ADAPTER: subswath  {subswath} -> {len(keep)} of {len(cfg.bursts)} bursts")

    print(f"ISCE3 ADAPTER: case      {name}  [role={role}]")
    print(f"ISCE3 ADAPTER: scene     {safe}")
    print(f"ISCE3 ADAPTER: orbit     {orbit}")
    print(f"ISCE3 ADAPTER: dem       {dem}")
    print(f"ISCE3 ADAPTER: posting   {x_posting} x {y_posting} m (snapped)")
    print(f"ISCE3 ADAPTER: flatten   {rc['runconfig']['groups']['processing']['geocoding']['flatten']}")
    print(f"ISCE3 ADAPTER: reramp    True (hardcoded in COMPASS — not configurable)")
    print(f"ISCE3 ADAPTER: runconfig {out}")
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("case", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--role", choices=("ref", "sec"), default="ref",
                    help="which acquisition: case.scene (ref) or case.secondary (sec)")
    a = ap.parse_args()
    try:
        build(a.case, a.out, a.role)
    except Unsatisfiable as e:
        print(f"ISCE3 ADAPTER: UNSATISFIABLE: {e}", file=sys.stderr)
        return 4
    return 0


if __name__ == "__main__":
    sys.exit(main())
