"""Per-burst characterisation of the GSLC - classical phase surface (companion to surface_diag.py).

Each burst is unwrapped and fitted on its own: the offsets BETWEEN bursts are arbitrary multiples of 2*pi
(the burst patches are disconnected at the seams), so only the shape inside a burst is meaningful.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
ML_AZ, ML_RG, B = 8, 30, 8
LINE_DT = 0.002055556299999998          # s per azimuth line
PER = 1504 // ML_AZ                     # cells per burst (188)
NPX = ML_RG                             # range pixels per cell


def unwrap_burst(s, q, n, rows):
    from skimage.restoration import unwrap_phase
    ok = (q[rows] > 0.45) & (n[rows] > 0.6 * B * B)
    ph = np.angle(np.where(ok, s[rows], 1.0))
    U = np.ma.filled(unwrap_phase(np.ma.masked_array(ph, mask=~ok)), np.nan)
    ok &= np.isfinite(U)
    return U, ok


def fit_poly(xc, yc, u, w, deg):
    cols = [(xc ** i) * (yc ** j) for i in range(deg + 1) for j in range(deg + 1 - i)]
    A = np.stack(cols, axis=1)
    sw = np.sqrt(w)
    coef, *_ = np.linalg.lstsq(A * sw[:, None], u * sw, rcond=None)
    return coef, A


def eval_poly(coef, xc, yc, deg):
    cols = [(xc ** i) * (yc ** j) for i in range(deg + 1) for j in range(deg + 1 - i)]
    return np.stack(cols, axis=1) @ coef


def analyse(path, label, ramp_rates=None):
    z = np.load(path)
    G, T = z["G"], z["T"]
    valid = (np.abs(G) > 0) & (np.abs(T) > 0)
    D = np.where(valid, G * np.conj(T), 0)
    ny, nx = (D.shape[0] // B) * B, (D.shape[1] // B) * B
    blk = D[:ny, :nx].reshape(ny // B, B, nx // B, B)
    s = blk.sum(axis=(1, 3))
    a = np.abs(blk).sum(axis=(1, 3))
    q = np.where(a > 0, np.abs(s) / np.maximum(a, 1e-30), 0)
    n = (np.abs(blk) > 0).sum(axis=(1, 3))
    per_b = PER // B                                   # coarse rows per burst (23.5 -> use edges)
    edges = [0, int(round(PER / B)), int(round(2 * PER / B)), s.shape[0]]
    print(f"\n===== {label}  (coarse grid {s.shape}, bursts at coarse rows {edges}) =====")
    total_conc_before, total_conc_after, tot_n = 0j, 0j, 0
    results = []
    for b in range(3):
        rows = slice(edges[b], edges[b + 1])
        U, ok = unwrap_burst(s, q, n, rows)
        iy, ix = np.where(ok)
        yy = (iy + 0.5) / (edges[b + 1] - edges[b])
        xx = (ix + 0.5) / s.shape[1]
        u = U[ok]
        w = q[rows][ok] ** 2
        out = {}
        for deg in (1, 2, 3):
            coef, A = fit_poly(xx, yy, u, w, deg)
            res = u - A @ coef
            out[deg] = (coef, np.sqrt(np.average(res ** 2, weights=w)))
        coef1 = out[1][0]        # (const, y, x) order: i=range power, j=azimuth power -> cols (0,0),(0,1),(1,0)
        # slopes in physical units
        d_az = coef1[1] / (edges[b + 1] - edges[b]) / (B * ML_AZ)             # rad per azimuth line
        d_rg = coef1[2] / s.shape[1] / (B * NPX)                               # rad per range pixel
        std = np.sqrt(np.average((u - np.average(u, weights=w)) ** 2, weights=w))
        line = (f"burst {b + 1}: blocks {int(ok.sum()):5d} | surface std {std:5.1f} rad | residual rms after plane "
                f"{out[1][1]:.2f}, quadratic {out[2][1]:.2f}, cubic {out[3][1]:.2f} rad | plane slopes: "
                f"azimuth {d_az:+.4f} rad/line ({d_az / LINE_DT:+.1f} rad/s), range {d_rg:+.5f} rad/px")
        print(line)
        results.append((b, d_az / LINE_DT, d_rg, out[2][1], std))
        # cell-level test: subtract the fitted cubic surface and measure the phase-only concentration
        for deg, key in ((1, "plane"), (3, "cubic")):
            coef = out[deg][0]
            r0, r1 = b * PER, (b + 1) * PER
            cy = (np.arange(r0, r1) + 0.5 - r0) / PER
            cx = (np.arange(D.shape[1]) + 0.5) / D.shape[1]
            X, Y = np.meshgrid(cx, cy)
            fit = eval_poly(coef, X.ravel(), Y.ravel(), deg).reshape(X.shape)
            Db = D[r0:r1]
            vb = valid[r0:r1]
            u_ = np.exp(1j * np.angle(Db[vb] * np.exp(-1j * fit[vb])))
            conc = abs(u_.mean())
            rms = np.sqrt(np.mean(np.angle(u_ * np.exp(-1j * np.angle(u_.mean()))) ** 2))
            print(f"          cell-level phase-only concentration after removing the per-burst {key:5s}: {conc:.3f}  (rms about mean {rms:.2f} rad)")
    if ramp_rates:
        print("   GSLC per-burst ramp the estimator REMOVED (interferogram log), rad/s:", ramp_rates)
        print("   azimuth slope of the GSLC-minus-classical surface, per burst,     rad/s:", [round(r[1], 1) for r in results])
    return results


def ramp_rates_from_log(path):
    t = Path(path).read_text(errors="replace")
    m = re.search(r"GSLC residual ramp per-burst.*", t)
    return [float(v) for v in re.findall(r"rate=([+-]?[\d.]+)", m.group(0))] if m else None


if __name__ == "__main__":
    tmp = "C:/Users/luis_/AppData/Local/Temp/"
    rates = ramp_rates_from_log("E:/Output/parity/ven/ven_etadD_ifg.log")
    analyse(tmp + "r5b_cells_ramp_off.npz", "ramp OFF (spec R5 configuration)", rates)
    analyse(tmp + "r5b_cells_ramp_on.npz", "ramp ON (per-burst residual ramp applied)", None)
    print("\n(ramp rates parsed from the ramp-on interferogram log; the estimator's rate is the ramp it REMOVED, so the ramp-OFF surface's azimuth slope should equal it)")
