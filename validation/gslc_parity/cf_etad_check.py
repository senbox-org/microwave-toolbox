"""Campi Flegrei: is the chains' shared residual against P-SBAS the atmosphere of the pair's dates?

A pair leaves a smooth, km-scale residual against the published CNR-IREA P-SBAS that is the same in the
GSLC and the classical chain and is tied to the date the two pairs share (2023-10-20). The P-SBAS series
departs from its own local trend by only ~0.25 cm per epoch (cf_summary.py), i.e. it carries little
per-date atmosphere. Hypothesis: a single pair carries the differential atmospheric delay of its two dates
and the P-SBAS series does not.

Independent test with ETAD (ECMWF-based tropospheric delay, ionospheric delay, solid-earth tide), read
straight from the ETAD netCDF of each date - NOT from our interferograms. Each layer's two-way range
time (s) becomes a one-way path delay (x c/2), is interpolated to the P-SBAS points, and the pair's
differential screen is added to the P-SBAS-predicted phase:

    phi_pred = sign_psbas * 4 pi/lambda * dLOS_psbas  +  s * 4 pi/lambda * sum_k p_k (delay_k,d2 - delay_k,d1)

p_k is each layer's phase sign relative to the troposphere (the ionosphere enters with the opposite sign:
its ETAD value is a group delay, while the carrier phase is advanced). The overall sign s is fitted, not
assumed; a real effect has a clear margin. The pair, its dates and the P-SBAS sign are read from the
samples written by cf_psbas_compare.py, never hard-coded.

  python cf_etad_check.py [--pair 72d] [--platform S1A] [--swath IW1] [--figure]
  python cf_etad_check.py --selftest
Output: E:\\Output\\parity\\cf\\etad\\etad_check_<pair>.txt  (+ figures\\slide_cf_etad.png with --figure)
"""
from __future__ import annotations

import argparse
import os
import sys
import zipfile
from pathlib import Path

import h5py
import numpy as np
from scipy.interpolate import LinearNDInterpolator

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import cf_psbas_compare as C  # noqa: E402

CACHE = Path(os.path.expanduser(r'~\.snap\var\cache\etad'))
WORK = Path(r'E:\Output\parity\cf\etad')
SCORES = Path(r'E:\Output\parity\cf')
C_LIGHT = 299_792_458.0
BURSTS = {45937, 45938, 45939, 45940}      # scene bursts 45938/9 plus one either side for interpolation margin
LAYERS = ('troposphericCorrectionRg', 'ionosphericCorrectionRg', 'geodeticCorrectionRg')
# Phase sign of each layer relative to the tropospheric (path-lengthening) delay: the ionosphere advances
# the carrier phase while delaying the group, so its ETAD range shift enters the phase with the OPPOSITE sign.
PHASE_SIGN = {'troposphericCorrectionRg': +1, 'ionosphericCorrectionRg': -1, 'geodeticCorrectionRg': +1}


def etad_ncs(date: str, platform: str = 'S1A'):
    """Every cached ETAD slice of `platform` on `date` (a scene can straddle slices), extracted once."""
    zips = sorted(CACHE.glob(f'{platform}_IW_ETA__AXDV_{date}T*.SAFE.zip'))
    if not zips:
        raise FileNotFoundError(f'no {platform} ETAD for {date} in {CACHE}')
    out = []
    for zp in zips:
        with zipfile.ZipFile(zp) as zf:
            name = next(n for n in zf.namelist() if n.endswith('.nc'))
            dst = WORK / date
            if not (dst / name).exists():
                zf.extract(name, dst)
            out.append(dst / name)
    return out


def layers_at(date: str, lat: np.ndarray, lon: np.ndarray, platform='S1A', swath='IW1', bursts=BURSTS) -> dict:
    """One-way path delay (m) of each ETAD layer at lat/lon, from the swath's bursts over the scene."""
    pts, vals, used = [], {k: [] for k in LAYERS}, []
    for nc in etad_ncs(date, platform):
        with h5py.File(nc, 'r') as f:
            for _, b in f[swath].items():
                bid = int(b.attrs['burstId'][0])
                if bid not in bursts:
                    continue
                used.append(bid)
                pts.append(np.c_[b['lons'][()].ravel(), b['lats'][()].ravel()])
                for k in LAYERS:
                    vals[k].append(b[k][()].ravel() * C_LIGHT / 2)       # s of two-way range time -> one-way m
    if not pts:
        raise ValueError(f'{date}: none of bursts {sorted(bursts)} in the ETAD product')
    missing = sorted(set(bursts) - set(used))
    if missing:
        print(f'  WARNING {date}: ETAD bursts {missing} not found - the points they cover become NaN')
    P = np.vstack(pts)
    out = {k: LinearNDInterpolator(P, np.concatenate(vals[k]))(lon, lat) for k in LAYERS}
    out['_bursts'] = sorted(used)
    return out


def conc(r):
    r = r[np.isfinite(r)]
    return float(np.abs(np.exp(1j * r).mean())) if r.size else float('nan')


def km_smooth_residual(r, lat, lon, km=2.0, min_n=30, origin=(0.0, 0.0)):
    """CIRCULAR spread sqrt(-2 ln R) of the per-block (km) mean residuals, on a FIXED block grid (anchored at
    14.0E / 40.7N, as in cf_summary.py) so every subset is binned identically.

    A plain np.std of the block angles is wrong here: the ETAD layers add large constants (ionosphere
    ~ -47 mm = ~10.6 rad) that move the residual across the +-pi cut and change np.std without changing
    anything physical. The circular measure ignores constants."""
    bx = np.floor(((lon - 14.0) * 85.2 + origin[0]) / km).astype(np.int64)
    by = np.floor(((lat - 40.7) * 111.3 + origin[1]) / km).astype(np.int64)
    _, inv = np.unique(by * 100_000 + bx, return_inverse=True)
    z = np.exp(1j * r)
    s = np.bincount(inv, z.real) + 1j * np.bincount(inv, z.imag)
    n = np.bincount(inv)
    ok = n >= min_n
    if not ok.any():
        raise ValueError(f'no {km} km block has >= {min_n} points')
    m = s[ok] / np.abs(s[ok])
    R = min(1.0, float(np.abs(m.mean())))                  # round-off can put R a hair above 1 -> sqrt(-) = NaN
    return float(np.sqrt(-2 * np.log(max(R, 1e-12)))), int(ok.sum())


ORIGINS = np.random.default_rng(0).uniform(0, 2.0, (50, 2))   # km offsets of the block grid


def spread_change(r_before, r_after, lat, lon):
    """Mean over ORIGINS of the km-scale spread before and after, and the % change (mean, min, max).
    A single block origin is not enough: the reduction varies from ~3% to ~20% with where the blocks fall."""
    b = np.array([km_smooth_residual(r_before, lat, lon, origin=o)[0] for o in ORIGINS])
    a = np.array([km_smooth_residual(r_after, lat, lon, origin=o)[0] for o in ORIGINS])
    pct = 100 * (a - b) / b
    return float(b.mean()), float(a.mean()), float(pct.mean()), float(pct.min()), float(pct.max())


def load_samples(pair: str, chain: str):
    z = np.load(SCORES / f'score_{pair}_{chain}' / 'psbas_samples.npz')
    for need in ('d1', 'd2', 'sign'):
        if need not in z.files:
            raise ValueError(f'{pair} {chain}: samples lack {need!r} - re-run cf_psbas_compare.py')
    return z


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--pair', default='72d'); ap.add_argument('--platform', default='S1A')
    ap.add_argument('--swath', default='IW1'); ap.add_argument('--figure', action='store_true')
    ap.add_argument('--selftest', action='store_true')
    a = ap.parse_args(argv)
    if a.selftest:
        return selftest()
    WORK.mkdir(parents=True, exist_ok=True)
    lines, fig = [], None

    def say(t=''):
        print(t); lines.append(t)

    for chain in ('gslc', 'trad'):
        z = load_samples(a.pair, chain)
        d1, d2 = str(z['d1']).replace('-', ''), str(z['d2']).replace('-', '')
        lat, lon, dl, phi = z['lat'], z['lon'], z['dlos'], z['phi']
        A, B = layers_at(d1, lat, lon, a.platform, a.swath), layers_at(d2, lat, lon, a.platform, a.swath)
        if chain == 'gslc':
            say(f'pair {a.pair}: {d1} -> {d2}   ETAD bursts {A["_bursts"]} / {B["_bursts"]}')
            for k in LAYERS:
                dd = B[k] - A[k]
                p1, p99 = np.nanpercentile(dd, [1, 99])
                say(f'  {k:26s} differential one-way delay: median {1000 * np.nanmedian(dd):+.1f} mm, '
                    f'p1-p99 spread {1000 * (p99 - p1):.1f} mm')
        base = int(z['sign']) * 4 * np.pi / C.LAMBDA * dl            # P-SBAS sign from the scorer, not assumed
        ok = np.all([np.isfinite(B[k] - A[k]) for k in LAYERS], axis=0)  # ONE subset for every row
        r0 = phi[ok] - base[ok]
        say(f'\n[{chain}] P-SBAS only: conc {conc(r0):.3f}  (n={int(ok.sum())}; 2 km spreads are means over '
            f'{len(ORIGINS)} block-grid origins)')
        for name, ks in {'tropo': ['troposphericCorrectionRg'],
                         'tropo+iono': ['troposphericCorrectionRg', 'ionosphericCorrectionRg'],
                         'tropo+iono+SET': list(LAYERS)}.items():
            screen = sum(PHASE_SIGN[k] * (B[k] - A[k]) for k in ks)[ok]
            res = {s: conc(r0 - s * 4 * np.pi / C.LAMBDA * screen) for s in (+1, -1)}
            s = max(res, key=res.get)
            r = r0 - s * 4 * np.pi / C.LAMBDA * screen
            sb, sa, pm, pmin, pmax = spread_change(r0, r, lat[ok], lon[ok])
            say(f'  + ETAD {name:15s} sign {s:+d}: conc {res[s]:.3f} (other sign {res[-s]:.3f}); '
                f'2 km spread {sb:.2f} -> {sa:.2f} rad, change {pm:+.0f}% [{pmin:+.0f}%, {pmax:+.0f}%]')
            if chain == 'gslc' and name == 'tropo':
                fig = (lat[ok], lon[ok], screen, r0, r, sb, sa)
    (WORK / f'etad_check_{a.pair}.txt').write_text('\n'.join(lines) + '\n')
    if a.figure and fig:
        make_figure(*fig, SCORES / 'figures' / 'slide_cf_etad.png')
    return 0


def make_figure(lat, lon, screen, r0, r1, s0, s1, path):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    plt.rcParams.update({'font.family': 'Arial', 'font.size': 12, 'axes.titlesize': 14})
    demean = lambda r: np.angle(np.exp(1j * r) * np.conj(np.exp(1j * r).mean()))  # noqa: E731  (display only)
    asp = 1 / np.cos(np.radians(40.83))
    fig, ax = plt.subplots(1, 3, figsize=(15, 4.6), constrained_layout=True)
    sc = ax[0].scatter(lon, lat, c=1000 * (screen - np.median(screen)), s=0.5, cmap='RdBu_r', vmin=-15, vmax=15,
                       rasterized=True)
    ax[0].set_title('ETAD tropospheric delay, second minus first date')
    fig.colorbar(sc, ax=ax[0], shrink=0.8, label='mm (median removed)')
    for a_, r, t in [(ax[1], r0, f'GSLC minus P-SBAS  (2 km spread {s0:.2f} rad)'),
                     (ax[2], r1, f'...minus ETAD troposphere  ({s1:.2f} rad)')]:   # spreads: 50-grid means
        sc = a_.scatter(lon, lat, c=demean(r), s=0.5, cmap='twilight_shifted', vmin=-np.pi, vmax=np.pi,
                        rasterized=True)
        a_.set_title(t)
    fig.colorbar(sc, ax=ax[1:], shrink=0.8, label='rad (mean removed)')
    for a_ in ax:
        a_.set_aspect(asp); a_.set_xticks([]); a_.set_yticks([])
    fig.savefig(path, dpi=150)
    print('wrote', path)


def selftest() -> int:
    rng = np.random.default_rng(3)
    lat = rng.uniform(40.8, 40.86, 20000); lon = rng.uniform(14.05, 14.2, 20000)
    atm = 0.006 * np.sin(lat * 200)                                   # 6 mm smooth screen
    dl = rng.uniform(0, 0.05, lat.size)
    phi = np.angle(np.exp(1j * (-4 * np.pi / C.LAMBDA * dl + 4 * np.pi / C.LAMBDA * atm + 0.7
                                + rng.normal(0, 0.4, lat.size))))
    r0 = phi + 4 * np.pi / C.LAMBDA * dl
    r1 = r0 - 4 * np.pi / C.LAMBDA * atm
    assert conc(r1) > conc(r0) + 0.2, (conc(r0), conc(r1))           # removing the screen helps
    s0, _ = km_smooth_residual(r0, lat, lon); s1, _ = km_smooth_residual(r1, lat, lon)
    assert s1 < s0, (s0, s1)                                          # and flattens the km-scale residual
    k0, _ = km_smooth_residual(r0 + 10.6, lat, lon)
    assert abs(k0 - s0) < 1e-9, (k0, s0)                              # a constant never changes the spread
    for c in rng.uniform(-np.pi, np.pi, 200):                         # a perfectly flat residual: R rounds to
        v, _ = km_smooth_residual(np.full(lat.size, c), lat, lon)     # 1 +- eps; must give 0, never NaN
        assert np.isfinite(v) and v < 1e-6, v
    assert PHASE_SIGN['ionosphericCorrectionRg'] == -PHASE_SIGN['troposphericCorrectionRg']
    assert np.isnan(conc(np.array([np.nan])))
    try:
        km_smooth_residual(r0[:10], lat[:10], lon[:10]); raise AssertionError('accepted too-sparse blocks')
    except ValueError:
        pass
    print('selftest OK')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
