"""Campi Flegrei: score a chain's wrapped interferogram against the PUBLISHED CNR-IREA P-SBAS series.

External truth, not chain-vs-chain: Giudicepietro et al. 2024 (IJAEOG 132:104060) released the
Sentinel-1 P-SBAS LOS displacement time series (Zenodo 10.5281/zenodo.10781496, CC-BY 4.0). The
difference of two epochs is the published displacement for exactly our pair. It is turned into a
predicted WRAPPED phase and compared with the chain's interferogram by phase concentration
|mean(exp(j(phi_chain - phi_psbas)))| - no unwrapping on our side, and the arbitrary constant
(different reference points: P-SBAS is referenced to Napoli 14.2523E 40.8337N) drops out.

Matching the reference's resolution: P-SBAS is 37 m (2 x 10 looks, Goldstein 0.5), so the chain's
complex interferogram is box-averaged over ~37 m before sampling. A single-look pixel against a 37 m
multilooked reference would be floor-limited by speckle, the same mistake the Napa slide made.

Sign: the phase/LOS sign convention is NOT assumed. Both signs are scored and the better one is
reported with the margin; a real match has a large margin, noise has none.

  python cf_psbas_compare.py --ifg <map-geometry ifg .dim> --d1 2023-08-09 --d2 2023-10-20
                             --label "GSLC 72 d" --out <dir>  [--win-m 37]
  python cf_psbas_compare.py --selftest
"""
from __future__ import annotations

import argparse
import datetime as dt
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import chain_compare as cc  # noqa: E402

REF = Path(r"E:\TestData\campi_flegrei\reference")
LAMBDA = 0.055465760           # m, from the P-SBAS metadata
BOX = (40.76, 40.90, 14.00, 14.26)   # lat0, lat1, lon0, lon1 - same AOI as the fixture split
# Mt Olibano - Accademia anomaly (~1.3 km2, Giudicepietro 2024). APPROXIMATE box placed by us from the
# paper's description ("east of Pozzuoli, Mt. Olibano-Accademia area"), not published coordinates.
ANOMALY = (40.818, 40.832, 14.135, 14.160)
M_PER_DEG_LAT = 111_320.0


PSBAS_CSV = REF / "DTSLOS_dsc22" / "DTSLOS_CNRIREA_20150324_20231020_UJBI.csv"


def psbas_dates(csv: Path = PSBAS_CSV):
    """The EXACT acquisition dates, from the CSV header's List_of_dates. (The decimal-year Time_Years
    converts +1..+3 d late, and on the 6-day-cadence years a +3 d label ties with the previous epoch -
    a nearest-date lookup on it picked the wrong epoch for 50 of 411 dates.)"""
    with open(csv, encoding="utf-8", errors="replace") as fh:
        for ln in fh:
            if ln.startswith("List_of_dates:"):
                return [dt.date.fromisoformat(x.strip()[:10]) for x in ln.split(":", 1)[1].split(",")]
            if ln.strip().startswith("ID,"):
                break
    raise ValueError(f"no List_of_dates in {csv}")


def load_psbas():
    a = np.load(REF / "dsc22_caldera.npy")
    dates = psbas_dates()
    if a.shape[1] - 9 != len(dates):
        raise ValueError(f"{a.shape[1] - 9} time-series columns vs {len(dates)} dates")
    return a, dates


def epoch_index(dates, want: dt.date) -> int:
    """Index of the P-SBAS epoch acquired ON `want`. Exact match only: with a 6-day cadence any
    tolerance can land on a neighbouring epoch, and a missing epoch must fail, not be substituted."""
    for k, d in enumerate(dates):
        if d == want:
            return k
    raise ValueError(f"no P-SBAS epoch on {want}")


def box_mean(z: np.ndarray, ny: int, nx: int) -> np.ndarray:
    """Centred ny x nx box average of a complex field; zero (no-data) contributes nothing."""
    good = np.isfinite(z) & (z != 0)            # zero = BEAM-DIMAP no-data; NaN would poison the cumsum
    z = np.where(good, z, 0)
    valid = good.astype(np.float64)

    def bsum(x):
        c = np.cumsum(np.cumsum(np.pad(x, ((1, 0), (1, 0))), 0), 1)
        h, w = x.shape
        y0 = np.clip(np.arange(h) - ny // 2, 0, h); y1 = np.clip(np.arange(h) + ny - ny // 2, 0, h)
        x0 = np.clip(np.arange(w) - nx // 2, 0, w); x1 = np.clip(np.arange(w) + nx - nx // 2, 0, w)
        return (c[y1][:, x1] - c[y0][:, x1] - c[y1][:, x0] + c[y0][:, x0])

    s = bsum(z.real) + 1j * bsum(z.imag)
    n = bsum(valid)
    return np.where(n > 0, s / np.maximum(n, 1), 0)


def score(phi_chain: np.ndarray, dlos_m: np.ndarray):
    """(best_sign, conc_best, conc_other) of phi_chain against the P-SBAS-predicted phase."""
    out = {}
    for s in (+1, -1):
        d = phi_chain - s * 4 * np.pi / LAMBDA * dlos_m
        out[s] = float(np.abs(np.exp(1j * d).mean())) if d.size else float("nan")
    best = max(out, key=out.get)
    return best, out[best], out[-best]


def conc_sign(phi_chain, dlos_m, s):
    return float(np.abs(np.exp(1j * (phi_chain - s * 4 * np.pi / LAMBDA * dlos_m)).mean()))


def check_pair_dates(dim: str, d1: dt.date, d2: dt.date):
    """The ifg band name carries its dates (i_ifg_..._09Aug2023_20Oct2023); refuse a mismatch with --d1/--d2."""
    import re
    names = [p.stem for p in Path(dim[:-4] + ".data").glob("i_ifg*.img")]
    for nm in names:
        m = re.search(r"(\d{2}[A-Za-z]{3}\d{4})_(\d{2}[A-Za-z]{3}\d{4})", nm)
        if m:
            got = [dt.datetime.strptime(x, "%d%b%Y").date() for x in m.groups()]
            if got != [d1, d2]:
                raise ValueError(f"{Path(dim).name}: band dates {got[0]} / {got[1]} != --d1/--d2 {d1} / {d2}")
            return
    raise ValueError(f"{Path(dim).name}: cannot read the pair dates from the band names {names}")


def read_ifg(dim: str):
    """Complex ifg + coherence cropped to BOX, and the crop's (lat0, lon0, dy, dx)."""
    iarr, w, h = cc._memmap(cc._band(dim, r"^i_ifg"))
    qarr, _, _ = cc._memmap(cc._band(dim, r"^q_ifg"))
    carr, _, _ = cc._memmap(cc._band(dim, r"^coh_"))
    dx, dy, lon0, lat0 = cc._geo(dim)
    r0 = max(0, int((lat0 - BOX[1]) / dy)); r1 = min(h, int((lat0 - BOX[0]) / dy) + 1)
    c0 = max(0, int((BOX[2] - lon0) / dx)); c1 = min(w, int((BOX[3] - lon0) / dx) + 1)
    z = np.asarray(iarr[r0:r1, c0:c1], np.float64) + 1j * np.asarray(qarr[r0:r1, c0:c1], np.float64)
    coh = np.asarray(carr[r0:r1, c0:c1], np.float64)
    return z, coh, (lat0 - r0 * dy, lon0 + c0 * dx, dy, dx)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ifg"); ap.add_argument("--d1"); ap.add_argument("--d2")
    ap.add_argument("--label", default=""); ap.add_argument("--out")
    ap.add_argument("--win-m", type=float, default=37.0)
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args(argv)
    if a.selftest:
        return selftest()

    ps, dates = load_psbas()
    d1, d2 = dt.date.fromisoformat(a.d1), dt.date.fromisoformat(a.d2)
    check_pair_dates(a.ifg, d1, d2)
    i1, i2 = epoch_index(dates, d1), epoch_index(dates, d2)
    lat, lon, pcoh = ps[:, 1], ps[:, 2], ps[:, 5]
    dlos = (ps[:, 9 + i2] - ps[:, 9 + i1]) / 100.0          # cm -> m

    z, coh, (la0, lo0, dy, dx) = read_ifg(a.ifg)
    ny = max(1, round(a.win_m / (dy * M_PER_DEG_LAT)))
    nx = max(1, round(a.win_m / (dx * M_PER_DEG_LAT * np.cos(np.radians(0.5 * (BOX[0] + BOX[1]))))))
    zb = box_mean(z, ny, nx)
    r = np.round((la0 - lat) / dy - 0.5).astype(int); c = np.round((lon - lo0) / dx - 0.5).astype(int)
    inside = (r >= 0) & (r < z.shape[0]) & (c >= 0) & (c < z.shape[1])
    samp = np.zeros(lat.shape, complex); samp[inside] = zb[r[inside], c[inside]]
    csamp = np.zeros(lat.shape); csamp[inside] = coh[r[inside], c[inside]]
    ok = inside & (samp != 0) & np.isfinite(dlos)
    phi = np.angle(samp)

    # the sign is fitted ONCE on all points and then held for the subsets (refitting per subset could
    # silently report a different sign for a small subset)
    sign, c_best, c_other = score(phi[ok], dlos[ok])
    margin = c_best - c_other
    rows = []
    for name, sel in [("all P-SBAS points", ok),
                      ("chain coherence > 0.3", ok & (csamp > 0.3)),
                      ("anomaly box (approx.)", ok & (lat > ANOMALY[0]) & (lat < ANOMALY[1])
                       & (lon > ANOMALY[2]) & (lon < ANOMALY[3]))]:
        if not sel.any():
            rows.append((name, 0, sign, float("nan"), float("nan"))); continue
        cb = conc_sign(phi[sel], dlos[sel], sign); co = conc_sign(phi[sel], dlos[sel], -sign)
        rows.append((name, int(sel.sum()), sign, cb, co))

    txt = [f"ifg            : {Path(a.ifg).name}  [{a.label}]",
           f"pair           : {a.d1} -> {a.d2}   P-SBAS epochs {dates[i1]} / {dates[i2]} (exact List_of_dates)",
           f"sign           : {sign:+d} fitted on all points, margin {margin:.3f}"
           + ("   ** AMBIGUOUS: margin < 0.05, do not trust the sign **" if margin < 0.05 else ""),
           f"box average    : {ny} x {nx} px (~{a.win_m:.0f} m, P-SBAS resolution)",
           f"P-SBAS dLOS    : peak {100 * np.nanmax(dlos[ok]):.1f} cm, p99 {100 * np.nanpercentile(dlos[ok], 99):.1f} cm",
           "subset                          n      sign  conc   (other sign)"]
    txt += [f"{n:30s} {k:7d}   {s:+d}   {cb:.3f}  ({co:.3f})" for n, k, s, cb, co in rows]
    report = "\n".join(txt)
    print(report)
    if a.out:
        out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
        (out / "psbas_score.txt").write_text(report + "\n")
        np.savez_compressed(out / "psbas_samples.npz", id=ps[ok, 0].astype(np.int64), lat=lat[ok], lon=lon[ok],
                            dlos=dlos[ok], phi=phi[ok], coh=csamp[ok], sign=sign, margin=margin,
                            d1=a.d1, d2=a.d2, ifg=Path(a.ifg).name)
    return 0


def selftest() -> int:
    rng = np.random.default_rng(1)
    dlos = rng.uniform(0, 0.05, 20000)
    truth = -4 * np.pi / LAMBDA * dlos + 1.3                     # arbitrary constant offset
    noisy = np.angle(np.exp(1j * (truth + rng.normal(0, 0.3, dlos.size))))
    s, cb, co = score(noisy, dlos)
    assert s == -1 and cb > 0.9 and co < 0.2, (s, cb, co)      # right sign found, wrong sign rejected
    s2, cb2, _ = score(rng.uniform(-np.pi, np.pi, dlos.size), dlos)
    assert cb2 < 0.05, cb2                                       # noise scores ~0 under either sign
    z = np.exp(1j * rng.uniform(-np.pi, np.pi, (40, 50))); z[5, 5] = 0
    m = box_mean(z, 3, 3)
    assert np.isclose(m[10, 10], z[9:12, 9:12].mean())           # centred window
    assert np.isclose(m[5, 5], (z[4:7, 4:7].sum()) / 8)          # no-data excluded from the mean
    zn = z.copy(); zn[20, 20] = np.nan
    assert np.isfinite(box_mean(zn, 3, 3)).all()                 # a NaN is no-data, it must not poison the sums
    six = [dt.date(2017, 3, 4), dt.date(2017, 3, 10), dt.date(2017, 3, 16)]   # 6-day cadence
    assert epoch_index(six, dt.date(2017, 3, 10)) == 1           # exact match
    for bad in (dt.date(2017, 3, 7), dt.date(2017, 3, 11)):      # the old +3 d tie / an off-by-one
        try:
            epoch_index(six, bad); raise AssertionError(f"accepted {bad}")
        except ValueError:
            pass
    ds = psbas_dates()
    assert len(ds) == 411 and ds[-1] == dt.date(2023, 10, 20) and ds == sorted(ds), (len(ds), ds[-1])
    for d in ("2022-10-13", "2023-08-09", "2023-10-20"):         # the three dates actually scored
        assert ds[epoch_index(ds, dt.date.fromisoformat(d))] == dt.date.fromisoformat(d)
    print("selftest OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
