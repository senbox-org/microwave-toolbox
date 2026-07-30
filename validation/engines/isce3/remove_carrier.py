"""
Analytically remove the TOPS azimuth carrier from a COMPASS CSLC product.

WHY THIS EXISTS
COMPASS hardcodes `reramp=True` (s1_geocode_slc.py:215), so it can only emit carrier-RESTORED
output. The Microwave Toolbox defaults to carrier-free, and carrier-free is the correct basis for
interferometry: the azimuth carrier is acquisition-specific (burst timing, FM rate) and does NOT
cancel between two acquisitions, least of all across two platforms (S1A vs S1C). Comparing a
carrier-restored interferogram against a carrier-free one measures the convention, not the algorithm.

Rather than force SNAP into the wrong convention to match COMPASS, this puts BOTH into the
InSAR-correct one by removing the carrier from the COMPASS side:

    z_carrier_free = z * exp(-1j * azimuth_carrier_phase)

EXACTNESS
COMPASS stores the carrier phase WRAPPED (`_wrap_phase`, s1_geocode_slc.py:226). That is harmless
here: exp(-1j*wrap(phi)) is identically exp(-1j*phi), because wrapping adds a multiple of 2*pi. So
this subtraction is exact, not an approximation -- no unwrapping is needed or wanted.

NaN handling: COMPASS initialises geocoded blocks to NaN+1j*NaN outside the radar footprint. NaN is
preserved (NaN propagates through the multiply), so invalid pixels stay invalid rather than becoming
plausible-looking zeros -- a zero would be read as a valid dark pixel by every downstream metric.

    python remove_carrier.py <cslc.h5> [--out <out.h5>] [--dry-run]
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

import h5py
import numpy as np

CARRIER = "azimuth_carrier_phase"


def find_datasets(h5: h5py.File) -> "tuple[list[str], list[str]]":
    """Locate complex SLC datasets and carrier-phase datasets by inspecting the file.

    Paths are DISCOVERED rather than hardcoded: the OPERA CSLC layout has changed between product
    versions, and a hardcoded path that silently misses would leave the carrier in place while
    reporting success.
    """
    complex_ds: list[str] = []
    carrier_ds: list[str] = []

    def visit(name: str, obj) -> None:
        if not isinstance(obj, h5py.Dataset):
            return
        if name.rsplit("/", 1)[-1] == CARRIER:
            carrier_ds.append(name)
        elif np.iscomplexobj(obj.dtype.type(0)) and obj.ndim == 2:
            complex_ds.append(name)

    h5.visititems(visit)
    return complex_ds, carrier_ds


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("cslc", type=Path)
    ap.add_argument("--out", type=Path, help="default: alongside input, suffixed _carrierfree")
    ap.add_argument("--dry-run", action="store_true", help="report what would change, write nothing")
    a = ap.parse_args()

    if not a.cslc.exists():
        print(f"REMOVE-CARRIER: {a.cslc} not found", file=sys.stderr)
        return 3

    with h5py.File(a.cslc, "r") as f:
        complex_ds, carrier_ds = find_datasets(f)

    print(f"REMOVE-CARRIER: file    {a.cslc}")
    for d in complex_ds:
        print(f"REMOVE-CARRIER:   complex {d}")
    for d in carrier_ds:
        print(f"REMOVE-CARRIER:   carrier {d}")

    if not complex_ds:
        print("REMOVE-CARRIER: no 2-D complex dataset found -- refusing to guess", file=sys.stderr)
        return 4
    if not carrier_ds:
        print(f"REMOVE-CARRIER: no '{CARRIER}' dataset found. COMPASS writes it unconditionally, so "
              f"its absence means this is not a COMPASS CSLC or the layout changed.", file=sys.stderr)
        return 4

    if a.dry_run:
        print("REMOVE-CARRIER: dry run, nothing written")
        return 0

    out = a.out or a.cslc.with_name(a.cslc.stem + "_carrierfree.h5")
    shutil.copy2(a.cslc, out)          # copy so the original CSLC stays available for comparison

    with h5py.File(out, "r+") as f:
        # One carrier grid per burst/grid group; match each complex dataset to the carrier that
        # shares its parent group, so a multi-burst file cannot cross-apply the wrong grid.
        carrier_by_group = {d.rsplit("/", 1)[0]: d for d in carrier_ds}
        applied = 0
        for cd in complex_ds:
            grp = cd.rsplit("/", 1)[0]
            key = carrier_by_group.get(grp)
            if key is None:
                print(f"REMOVE-CARRIER: WARNING no carrier in group '{grp}' for {cd} -- LEFT UNCHANGED")
                continue
            z = f[cd][:]
            phi = f[key][:]
            if z.shape != phi.shape:
                print(f"REMOVE-CARRIER: shape mismatch {cd}{z.shape} vs {key}{phi.shape} -- skipped",
                      file=sys.stderr)
                continue
            # exp(-1j*phi) is exact for wrapped phi. NaN propagates, keeping invalid pixels invalid.
            f[cd][...] = (z * np.exp(-1j * phi)).astype(z.dtype)
            valid = np.isfinite(z).sum()
            print(f"REMOVE-CARRIER:   applied to {cd}  ({valid} finite px, "
                  f"|phi| max {np.nanmax(np.abs(phi)):.3f} rad)")
            applied += 1

        f.attrs["harness_carrier_removed"] = np.bytes_(
            "azimuth_carrier_phase removed analytically: z *= exp(-1j*azimuth_carrier_phase). "
            "Needed because COMPASS hardcodes reramp=True; carrier-free is the correct InSAR basis.")

    if applied == 0:
        print("REMOVE-CARRIER: nothing was modified", file=sys.stderr)
        return 5
    print(f"REMOVE-CARRIER: wrote   {out}  ({applied} dataset(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main())
