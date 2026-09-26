"""Gate: did InterferogramOp's metre-based coherence window really cover the requested ground size?

A regex such as `cohWinRg=\\d+` accepts any value - it passed a GSLC window that was ~76 m east-west
instead of 100 m (the GSLC metadata carried the nominal, equator-converted east step; fixed 2026-09-26).
This gate parses the log line

    cohWinSizeMeters=100.0 m -> cohWinAz=7, cohWinRg=56 (pixel spacing az=13.95 m, rg=1.78 m,
    incidence=33.69 deg, geocoded=true)

and computes the window's real footprint on the ground:
  - geocoded: the product's own grid step from IMAGE_TO_MODEL_TRANSFORM at the scene latitude (so a
    wrong metadata spacing is caught, not trusted), per axis;
  - radar geometry: range = cohWinRg * rg / sin(incidence), azimuth = cohWinAz * az.
Both axes must be within --tol (default 15 %, integer window rounding) of the requested size.

  python check_cohwin.py <ifg.log> [--dim <ifg.dim>] [--tol 0.15]      exit 0 = pass
  python check_cohwin.py --selftest
"""
from __future__ import annotations

import argparse
import math
import re
import sys
from pathlib import Path

LINE = re.compile(r'cohWinSizeMeters=([\d.]+) m -> cohWinAz=(\d+), cohWinRg=(\d+) \(pixel spacing az=([\d.]+) m, '
                  r'rg=([\d.]+) m, incidence=([\d.]+) deg, geocoded=(true|false)\)')
M_PER_DEG = 6378137.0 * math.pi / 180.0


def grid_step(dim: str):
    """(east_m, north_m) of a WGS84-degree grid at its centre latitude, from the .dim transform."""
    txt = Path(dim).read_text(encoding='utf-8', errors='replace')
    t = [float(v) for v in re.search(r'<IMAGE_TO_MODEL_TRANSFORM>([^<]*)<', txt).group(1).split(',')]
    h = int(re.search(r'<NROWS>(\d+)</NROWS>', txt).group(1))
    lat_c = t[5] + t[3] * h / 2.0
    return abs(t[0]) * M_PER_DEG * math.cos(math.radians(lat_c)), abs(t[3]) * M_PER_DEG


def footprint(line_match, dim=None):
    size, n_az, n_rg, az, rg, inc, geo = line_match.groups()
    size, n_az, n_rg, az, rg, inc = float(size), int(n_az), int(n_rg), float(az), float(rg), float(inc)
    if geo == 'true':
        if dim is None:
            raise ValueError('geocoded product: pass --dim so the real grid step can be checked')
        east, north = grid_step(dim)
        return size, n_rg * east, n_az * north, dict(meta_rg=rg, real_east=east, meta_az=az, real_north=north)
    return size, n_rg * rg / math.sin(math.radians(inc)), n_az * az, {}


def check(log: str, dim=None, tol=0.15) -> bool:
    text = Path(log).read_text(encoding='utf-8', errors='replace')
    m = LINE.search(text)
    if not m:
        print(f'FAIL no metre-based coherence-window line in {log}')
        return False
    size, x, y, info = footprint(m, dim)
    ok = abs(x - size) <= tol * size and abs(y - size) <= tol * size
    extra = ''
    if info:
        extra = (f' | metadata rg={info["meta_rg"]:.3f} m vs real east step {info["real_east"]:.3f} m, '
                 f'az={info["meta_az"]:.3f} vs real north {info["real_north"]:.3f}')
    print(f'{"PASS" if ok else "FAIL"} coherence window {x:.1f} m x {y:.1f} m on the ground '
          f'(requested {size:.0f} m, tol {100 * tol:.0f}%){extra}')
    return ok


def selftest() -> int:
    good = 'cohWinSizeMeters=100.0 m -> cohWinAz=7, cohWinRg=24 (pixel spacing az=13.95 m, rg=2.33 m, incidence=33.69 deg, geocoded=false)'
    s, x, y, _ = footprint(LINE.search(good))
    assert abs(x - 100.8) < 1 and abs(y - 97.65) < 0.1, (x, y)
    slant = 'cohWinSizeMeters=100.0 m -> cohWinAz=7, cohWinRg=43 (pixel spacing az=13.95 m, rg=2.33 m, incidence=33.69 deg, geocoded=false)'
    s, x, y, _ = footprint(LINE.search(slant))
    assert x > 150, x                                  # the pre-fix slant-range formula is rejected
    import tempfile, os
    d = tempfile.mkdtemp()
    dim = os.path.join(d, 'g.dim')
    Path(dim).write_text('<IMAGE_TO_MODEL_TRANSFORM>2.1110409176808754E-5,0.0,0.0,-1.2531498213467324E-4,'
                         '13.7,40.95</IMAGE_TO_MODEL_TRANSFORM><NROWS>2400</NROWS>')
    bug = 'cohWinSizeMeters=100.0 m -> cohWinAz=7, cohWinRg=43 (pixel spacing az=13.95 m, rg=2.35 m, incidence=33.69 deg, geocoded=true)'
    lg = os.path.join(d, 'a.log'); Path(lg).write_text(bug)
    assert not check(lg, dim)                           # the Campi Flegrei 76 m window FAILS
    fixed = bug.replace('cohWinRg=43', 'cohWinRg=56').replace('rg=2.35', 'rg=1.78')
    Path(lg).write_text(fixed)
    assert check(lg, dim)                               # the corrected 56 x 1.78 m window passes
    print('selftest OK')
    return 0


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('log', nargs='?'); ap.add_argument('--dim'); ap.add_argument('--tol', type=float, default=0.15)
    ap.add_argument('--selftest', action='store_true')
    a = ap.parse_args()
    if a.selftest:
        sys.exit(selftest())
    sys.exit(0 if check(a.log, a.dim, a.tol) else 1)
