"""Campi Flegrei: every number the tutorial and the ESA deck quote, computed in one place and SAVED.

Inputs: the per-chain samples written by cf_psbas_compare.py (E:\\Output\\parity\\cf\\score_<pair>_<chain>).
Output: E:\\Output\\parity\\cf\\cf_summary.json and cf_summary.txt. patch_cf_slides.py reads the JSON, so no
deck number is typed by hand.

What it computes, per pair (72d, 1yr):
  - each chain vs the published P-SBAS (all points, chain coherence > 0.3, the approximate anomaly box),
    re-derived here from the samples so the numbers are on ONE shared point set;
  - GSLC vs classical at 37 m on the same points;
  - fine scale: the residual (chain - P-SBAS) high-passed at 2 km, in the caldera-centre zoom and the box,
    over N random block-grid origins. Reported as mean and range. A single block origin is NOT enough:
    it produced a spurious 'GSLC ahead' result in an earlier version (adversarial review 2026-09-26);
  - mean coherence of each chain at the P-SBAS points.

Points are matched between chains by P-SBAS point ID (coordinates are NOT unique: 3419 pairs of distinct
P-SBAS points share the same 5-decimal lat/lon).

  python cf_summary.py [--origins 50]
  python cf_summary.py --selftest
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import cf_psbas_compare as C  # noqa: E402

S = Path(r'E:\Output\parity\cf')
ZOOM = (40.805, 40.845, 14.095, 14.185)
PAIRS = {'72d': ('2023-08-09', '2023-10-20'), '1yr': ('2022-10-13', '2023-10-20')}


def ikey(lat, lon):
    """Exact integer key at 1e-5 deg (P-SBAS coordinates are given to 5 decimals)."""
    return np.round(lat * 1e5).astype(np.int64) * 100_000_000 + np.round(lon * 1e5).astype(np.int64)


def conc(r):
    r = r[np.isfinite(r)]
    return float(np.abs(np.exp(1j * r).mean())) if r.size else float('nan')


def load_pair(tag):
    g = np.load(S / f'score_{tag}_gslc' / 'psbas_samples.npz')
    t = np.load(S / f'score_{tag}_trad' / 'psbas_samples.npz')
    for z, nm in ((g, 'gslc'), (t, 'trad')):
        if int(z['sign']) != -1:
            raise ValueError(f'{tag} {nm}: fitted P-SBAS sign {int(z["sign"])}, expected -1 - check before trusting')
    for z, nm in ((g, 'gslc'), (t, 'trad')):
        if 'id' not in z.files:
            raise ValueError(f'{tag} {nm}: samples carry no P-SBAS id - re-run cf_psbas_compare.py')
        if (str(z['d1']), str(z['d2'])) != PAIRS[tag]:
            raise ValueError(f'{tag} {nm}: samples are for {z["d1"]} -> {z["d2"]}, expected {PAIRS[tag]}')
    # match by P-SBAS point ID: 3419 pairs of distinct P-SBAS points share identical 5-decimal coordinates
    kg, kt = g['id'], t['id']
    if len(np.unique(kg)) != kg.size or len(np.unique(kt)) != kt.size:
        raise ValueError(f'{tag}: duplicate P-SBAS ids in the samples')
    _, ig, it = np.intersect1d(kg, kt, return_indices=True)
    return dict(id=kg[ig], lat=g['lat'][ig], lon=g['lon'][ig], dlos=g['dlos'][ig], pg=g['phi'][ig], pt=t['phi'][it],
                cg=g['coh'][ig], ct=t['coh'][it], n_gslc=int(kg.size), n_trad=int(kt.size))


def highpass(v, lat, lon, km=2.0, origin=(0.0, 0.0)):
    """Subtract the per-block circular mean; block grid anchored at a fixed corner + origin offset (km)."""
    bx = np.floor(((lon - 14.0) * 85.2 + origin[0]) / km).astype(np.int64)
    by = np.floor(((lat - 40.7) * 111.3 + origin[1]) / km).astype(np.int64)
    _, inv = np.unique(by * 100_000 + bx, return_inverse=True)
    z = np.exp(1j * v)
    s = np.bincount(inv, z.real) + 1j * np.bincount(inv, z.imag)
    return np.angle(z * np.conj(s[inv] / np.maximum(np.abs(s[inv]), 1e-12)))


def summarise(tag, n_origins, seed=0):
    d = load_pair(tag)
    lat, lon = d['lat'], d['lon']
    model = -4 * np.pi / C.LAMBDA * d['dlos']
    rg, rt = d['pg'] - model, d['pt'] - model
    A = (lat > C.ANOMALY[0]) & (lat < C.ANOMALY[1]) & (lon > C.ANOMALY[2]) & (lon < C.ANOMALY[3])
    Z = (lat > ZOOM[0]) & (lat < ZOOM[1]) & (lon > ZOOM[2]) & (lon < ZOOM[3])
    coh = (d['cg'] > 0.3) & (d['ct'] > 0.3)
    out = dict(pair=tag, dates=PAIRS[tag], n_points=int(lat.size), n_gslc=d['n_gslc'], n_trad=d['n_trad'],
               n_box=int(A.sum()), n_zoom=int(Z.sum()),
               peak_dlos_cm=float(100 * np.nanmax(d['dlos'])),
               gslc_vs_psbas=conc(rg), trad_vs_psbas=conc(rt),
               gslc_vs_psbas_coh=conc(rg[coh]), trad_vs_psbas_coh=conc(rt[coh]),
               gslc_vs_psbas_box=conc(rg[A]), trad_vs_psbas_box=conc(rt[A]),
               gslc_vs_trad=conc(d['pg'] - d['pt']), gslc_vs_trad_coh=conc(d['pg'][coh] - d['pt'][coh]),
               coh_gslc=float(np.mean(d['cg'])), coh_trad=float(np.mean(d['ct'])))
    rng = np.random.default_rng(seed)
    fz, fb = [], []
    for _ in range(n_origins):
        o = tuple(rng.uniform(0, 2.0, 2))
        hg, ht = highpass(rg, lat, lon, origin=o), highpass(rt, lat, lon, origin=o)
        fz.append((conc(hg[Z]), conc(ht[Z])))
        fb.append((conc(hg[A]), conc(ht[A])))
    fz, fb = np.array(fz), np.array(fb)
    for nm, arr in (('zoom', fz), ('box', fb)):
        gap = arr[:, 0] - arr[:, 1]
        out[f'fine_{nm}'] = dict(gslc_mean=float(arr[:, 0].mean()), trad_mean=float(arr[:, 1].mean()),
                                 gap_mean=float(gap.mean()), gap_min=float(gap.min()), gap_max=float(gap.max()),
                                 n_origins=n_origins)
    return out


def psbas_trend_scatter(last_n=12, half=8):
    """Spatial std (cm) of each late P-SBAS epoch's departure from a local quadratic trend fitted to the
    neighbouring epochs (leave-one-out). Measures how much per-epoch jitter - e.g. atmosphere - the
    published series still carries."""
    ps, dates = C.load_psbas()
    t = np.array([(d - dates[0]).days for d in dates], float)
    ts = ps[:, 9:]
    out = {}
    for k in range(len(t) - last_n, len(t)):
        idx = [i for i in range(max(0, k - 2 * half), min(len(t), k + half + 1)) if i != k]
        coef = np.linalg.lstsq(np.vander(t[idx] - t[k], 3), ts[:, idx].T, rcond=None)[0]
        dev = ts[:, k] - coef[-1]
        out[str(dates[k])] = float(np.std(dev - np.median(dev)))
    return out


def residual_shared(res_a, res_b, km=2.0, min_n=30):
    """Do two pairs leave the same km-scale residual? Circular concentration of the difference of their
    per-block mean residuals, against a random re-pairing of the blocks as the no-relation baseline."""
    lat, lon = res_a['lat'], res_a['lon']
    bx = np.floor((lon - 14.0) * 85.2 / km).astype(np.int64)
    by = np.floor((lat - 40.7) * 111.3 / km).astype(np.int64)
    _, inv = np.unique(by * 100_000 + bx, return_inverse=True)
    n = np.bincount(inv)
    ok = n >= min_n

    def bmean(r):
        z = np.exp(1j * r)
        s = np.bincount(inv, z.real) + 1j * np.bincount(inv, z.imag)
        return np.angle(s[ok])
    ua, ub = bmean(res_a['r']), bmean(res_b['r'])
    rng = np.random.default_rng(0)
    base = float(np.mean([conc(ua - rng.permutation(ub)) for _ in range(500)]))
    return dict(blocks=int(ok.sum()), same=conc(ua - ub), random_baseline=base)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--origins', type=int, default=50)
    ap.add_argument('--selftest', action='store_true')
    a = ap.parse_args(argv)
    if a.selftest:
        return selftest()
    res = {tag: summarise(tag, a.origins) for tag in PAIRS}
    # shared km-scale residual across the two pairs, on the SAME P-SBAS points (matched by id), per chain
    pa, pb = load_pair('72d'), load_pair('1yr')
    _, ia, ib = np.intersect1d(pa['id'], pb['id'], return_indices=True)
    shared = {}
    for chain, key in (('gslc', 'pg'), ('trad', 'pt')):
        ra = pa[key][ia] - (-4 * np.pi / C.LAMBDA * pa['dlos'][ia])
        rb = pb[key][ib] - (-4 * np.pi / C.LAMBDA * pb['dlos'][ib])
        shared[chain] = residual_shared(dict(lat=pa['lat'][ia], lon=pa['lon'][ia], r=ra), dict(r=rb))
    extra = {'residual_shared_72d_vs_1yr': shared, 'psbas_trend_scatter_cm': psbas_trend_scatter()}
    (S / 'cf_summary.json').write_text(json.dumps({**res, **extra}, indent=2))
    lines = []
    for tag, r in res.items():
        lines += [f'== {tag} {r["dates"][0]} -> {r["dates"][1]}: {r["n_points"]} shared P-SBAS points '
                  f'(GSLC {r["n_gslc"]}, classical {r["n_trad"]}), box {r["n_box"]}, zoom {r["n_zoom"]}',
                  f'  peak P-SBAS dLOS {r["peak_dlos_cm"]:.1f} cm',
                  f'  vs P-SBAS  GSLC {r["gslc_vs_psbas"]:.3f}  classical {r["trad_vs_psbas"]:.3f}   '
                  f'(coh>0.3 both: {r["gslc_vs_psbas_coh"]:.3f} / {r["trad_vs_psbas_coh"]:.3f}; '
                  f'box {r["gslc_vs_psbas_box"]:.3f} / {r["trad_vs_psbas_box"]:.3f})',
                  f'  GSLC vs classical {r["gslc_vs_trad"]:.3f} (coh>0.3 both {r["gslc_vs_trad_coh"]:.3f})',
                  f'  coherence at P-SBAS points: GSLC {r["coh_gslc"]:.3f}  classical {r["coh_trad"]:.3f}']
        for nm in ('zoom', 'box'):
            f = r[f'fine_{nm}']
            lines.append(f'  fine scale (2 km high-pass of the residual), {nm}: GSLC {f["gslc_mean"]:.3f} '
                         f'classical {f["trad_mean"]:.3f}; gap mean {f["gap_mean"]:+.3f} '
                         f'[{f["gap_min"]:+.3f}, {f["gap_max"]:+.3f}] over {f["n_origins"]} block origins')
    for chain, v in shared.items():
        lines.append(f'shared 2 km residual 72d vs 1yr ({chain}): conc {v["same"]:.3f} vs random re-pairing '
                     f'{v["random_baseline"]:.3f} over {v["blocks"]} blocks')
    lines.append('P-SBAS departure from its local quadratic trend (spatial std, cm): '
                 + ', '.join(f'{k} {v:.2f}' for k, v in extra['psbas_trend_scatter_cm'].items()))
    txt = '\n'.join(lines)
    (S / 'cf_summary.txt').write_text(txt + '\n')
    print(txt)
    return 0


def selftest() -> int:
    lat = np.array([40.81234, 40.81235, 40.81234]); lon = np.array([14.10001, 14.10001, 14.10002])
    k = ikey(lat, lon)
    assert len(np.unique(k)) == 3, k                                   # neighbours at 1e-5 never collide
    rng = np.random.default_rng(2)
    la = rng.uniform(40.8, 40.85, 5000); lo = rng.uniform(14.1, 14.2, 5000)
    smooth = 2.0 * np.sin((la - 40.8) * 40)                            # km-scale surface
    fine = rng.normal(0, 0.3, la.size)
    hp = highpass(smooth + fine, la, lo)
    assert conc(hp - fine) > conc(smooth + fine - fine) - 1e-9           # high-pass removes the smooth part
    assert conc(highpass(fine + 5.0, la, lo) - highpass(fine, la, lo)) > 0.999   # constant-invariant
    print('selftest OK')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
