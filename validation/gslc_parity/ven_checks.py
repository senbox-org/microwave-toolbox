"""Metadata checks for the Venezuela parity fixtures (read-only, regex on the .dim)."""
from __future__ import annotations

import datetime as dt
import re
import sys
from pathlib import Path


def _txt(dim) -> str:
    return Path(dim).read_text(encoding="utf-8", errors="replace")


def burst_ids(dim) -> list[int]:
    """Track-anchored (relative) S1 burst IDs in first-seen order, de-duplicated (the split
    product repeats the annotation block several times)."""
    seen, out = set(), []
    for m in re.finditer(r'<MDATTR name="burstId"[^>]*>(\d+)</MDATTR>', _txt(dim)):
        v = int(m.group(1))
        if v not in seen:
            seen.add(v)
            out.append(v)
    return out


def attr(dim, name: str) -> str:
    m = re.search(rf'<MDATTR name="{re.escape(name)}"[^>]*>([^<]*)</MDATTR>', _txt(dim))
    if not m:
        raise ValueError(f"{name!r} not found in {Path(dim).name}")
    return m.group(1)


def snap_time(s: str) -> float:
    """'23-JUN-2026 22:50:52.310630' -> seconds since the epoch (UTC)."""
    d = dt.datetime.strptime(s.strip(), "%d-%b-%Y %H:%M:%S.%f").replace(tzinfo=dt.timezone.utc)
    return d.timestamp()


def etad_option(dim) -> dict:
    """ETAD parameters the product's Processing_Graph recorded for S1-ETAD-Correction ({} if the
    product never went through it). SNAP writes them as <MDATTR name="resamplingImage">true</...>
    inside the node's parameters element, after the operator name attribute."""
    t = _txt(dim)
    m = re.search(r'<MDATTR name="operator"[^>]*>S1-ETAD-Correction</MDATTR>', t)
    if not m:
        return {}
    blk = t[m.end():m.end() + 6000]
    out = {}
    for k in ("resamplingImage", "outputPhaseCorrections", "sumOfAzimuthCorrections"):
        v = re.search(rf'<MDATTR name="{k}"[^>]*>([^<]*)</MDATTR>', blk)
        if v:
            out[k] = v.group(1).strip().lower() == "true"
    return out


def etad_azimuth_applied(dim) -> bool:
    """True when the product carries the ETAD azimuth provenance flag (the GSLC then
    suppresses its own bistatic residual, so R4 must not model it)."""
    return attr(dim, "etad_azimuth_applied").strip() == "1"


def check_fixture(root, expect_ids=(225587, 225588, 225589)) -> int:
    root = Path(root)
    ok = True
    for tag in ("A", "C", "D"):
        cand = sorted(root.glob(f"S1{tag if tag != 'A' else 'A'}_IW_SLC*_b4-6_orb.dim"))
        if not cand:
            print(f"FAIL: no {tag} orbit-corrected fixture in {root}")
            ok = False
            continue
        ids = burst_ids(cand[0])
        print(f"{tag}: burst IDs {ids}")
        if ids != list(expect_ids):
            print(f"  FAIL: expected {list(expect_ids)}")
            ok = False
    for tag in ("A", "C"):
        cand = sorted(root.glob(f"S1{tag}_IW_SLC*_b4-6_orb_etad.dim"))
        if not cand:
            print(f"FAIL: no {tag} ETAD fixture")
            ok = False
            continue
        o = etad_option(cand[0])
        print(f"{tag}: ETAD {o}")
        if not (o.get("resamplingImage") and o.get("outputPhaseCorrections")):
            print("  FAIL: ETAD must be option 1 (resamplingImage=true, outputPhaseCorrections=true)")
            ok = False
    print("FIXTURE", "OK" if ok else "FAILED")
    return 0 if ok else 1


def _selftest() -> int:
    import tempfile
    ok = True
    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / "x.dim"
        p.write_text('<MDATTR name="burstId" type="ascii" mode="rw">7</MDATTR>'
                     '<MDATTR name="absolute" type="ascii">9</MDATTR>'
                     '<MDATTR name="burstId" type="ascii" mode="rw">8</MDATTR>'
                     '<MDATTR name="burstId" type="ascii" mode="rw">7</MDATTR>'
                     '<MDATTR name="first_line_time" type="utc">23-JUN-2026 22:50:52.310630</MDATTR>'
                     '<MDATTR name="operator" type="ascii">S1-ETAD-Correction</MDATTR>'
                     '<MDATTR name="resamplingImage" type="ascii">true</MDATTR>'
                     '<MDATTR name="outputPhaseCorrections" type="ascii">false</MDATTR>'
                     '<MDATTR name="etad_azimuth_applied" type="uint8">1</MDATTR>')
        if burst_ids(p) != [7, 8]:
            print("  FAIL: burst_ids", burst_ids(p))
            ok = False
        if abs(snap_time(attr(p, "first_line_time")) - snap_time("23-JUN-2026 22:50:52.310630")) > 0:
            ok = False
        if snap_time("24-JUN-2026 00:00:00.000000") - snap_time("23-JUN-2026 00:00:00.000000") != 86400:
            print("  FAIL: snap_time")
            ok = False
        if etad_option(p) != {"resamplingImage": True, "outputPhaseCorrections": False}:
            print("  FAIL: etad_option", etad_option(p))
            ok = False
        if not etad_azimuth_applied(p):
            print("  FAIL: etad_azimuth_applied")
            ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
    if len(sys.argv) == 3 and sys.argv[1] == "fixture":
        raise SystemExit(check_fixture(sys.argv[2]))
    if len(sys.argv) == 3 and sys.argv[1] == "etad":
        print(etad_option(sys.argv[2]))
        raise SystemExit(0)
    print(__doc__)
    raise SystemExit(2)
