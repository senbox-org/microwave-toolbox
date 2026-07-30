"""
Diff the fringes of two engines' interferograms and emit a report.

Runs INSIDE the isce3 container: that is where GDAL lives, and /work already exposes BOTH engines'
outputs, so no data is copied to compare it.

    python -m compare.diff_fringes --case /cases/<case>.yml \
        --snap /work/snap/<case>/<case>_ifg.data \
        --isce3 /work/isce3/<case>/ifg.tif \
        --report /work/reports/<case>.md

WHAT IS AND IS NOT MEASURED
The quantity compared is the DOUBLE difference: angle(ifg_snap) - angle(ifg_isce3), unwrapped by
nobody. A constant offset between the two is NOT a defect -- each engine may carry a different
absolute phase reference -- so the constant is estimated and removed, and reported separately. What
matters is the SPATIAL STRUCTURE of the residual: a smooth ramp means a geometry/geocoding
difference; salt-and-pepper means noise or decorrelation; localised patches mean a real disagreement.

Residue density and small-window coherence are BLIND to smooth scene-scale error, which is exactly
what a geocoding difference produces. circular_std_multilooked is therefore the load-bearing metric
and is always computed, whatever the case lists.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import numpy as np
import yaml


def die(msg: str) -> "None":
    print(f"DIFF: {msg}", file=sys.stderr)
    raise SystemExit(3)


# --- readers ----------------------------------------------------------------------------------
def read_envi_pair(dim_data: Path, max_px: int = 0
                   ) -> "tuple[np.ndarray, tuple[float, float, float, float]]":
    """Read a BEAM-DIMAP .data directory's i/q interferogram bands as complex, plus its geotransform.

    ENVI is parsed directly rather than via GDAL so the SNAP product is read exactly as written,
    with its declared byte order -- SNAP writes big-endian, and a silent endianness flip would look
    like noise rather than an error.
    """
    hdrs = sorted(dim_data.glob("*.hdr"))
    ib = next((h for h in hdrs if re.match(r"^i_", h.stem)), None)
    qb = next((h for h in hdrs if re.match(r"^q_", h.stem)), None)
    if not ib or not qb:
        die(f"no i_*/q_* band pair in {dim_data}. Found: {[h.stem for h in hdrs]}")

    def load(h: Path, step: int = 1) -> "tuple[np.ndarray, int, int]":
        t = h.read_text(errors="replace")

        def g(k: str) -> int:
            m = re.search(rf"^{k}\s*=\s*(-?\d+)", t, re.I | re.M)
            if not m:
                die(f"{h.name}: missing '{k}'")
            return int(m.group(1))

        W, L, dt, bo = g("samples"), g("lines"), g("data type"), g("byte order")
        npdt = {4: np.float32, 5: np.float64, 12: np.uint16, 2: np.int16}.get(dt)
        if npdt is None:
            die(f"{h.name}: unsupported ENVI data type {dt}")
        d = np.dtype(npdt).newbyteorder(">" if bo == 1 else "<")
        img = h.with_suffix(".img")
        if step <= 1:
            a = np.fromfile(img, dtype=d)
            if a.size != W * L:
                die(f"{h.name}: expected {W*L} samples, file holds {a.size}")
            return a.reshape(L, W).astype(np.float64), W, L
        # STRIDED read: seek to every `step`-th row and take every `step`-th sample.
        #
        # Reading the whole band is not viable for a full-swath geocoded product: at 500 Mpixels the
        # i and q bands are 4 GB each as float64 and the complex array another 8 GB, which the OOM
        # killer reaps (observed: SIGKILL/137). Decimating on READ keeps peak memory proportional to
        # the rendered size instead of the product size.
        #
        # Strided, never averaged: averaging wrapped phase collapses it toward zero wherever a fringe
        # crosses the block, which would erase fringes exactly where they are densest.
        rows = range(0, L, step)
        buf = np.empty((len(list(rows)), (W + step - 1) // step), dtype=np.float64)
        with open(img, "rb") as f:
            for k, r in enumerate(range(0, L, step)):
                f.seek(r * W * d.itemsize)
                raw = np.frombuffer(f.read(W * d.itemsize), dtype=d)
                if raw.size != W:
                    die(f"{h.name}: short read at row {r} ({raw.size} of {W})")
                buf[k] = raw[::step]
        return buf, buf.shape[1], buf.shape[0]

    # Decide the stride BEFORE reading anything, from the header dimensions.
    step = 1
    if max_px > 0:
        t = ib.read_text(errors="replace")
        fw = int(re.search(r"^samples\s*=\s*(\d+)", t, re.I | re.M).group(1))
        fl = int(re.search(r"^lines\s*=\s*(\d+)", t, re.I | re.M).group(1))
        step = max(1, int(np.ceil(max(fw, fl) / float(max_px))))

    i, W, L = load(ib, step)
    q, W2, L2 = load(qb, step)
    if (W, L) != (W2, L2):
        die(f"i/q shape mismatch {(W,L)} vs {(W2,L2)}")
    gt = _dimap_gt(dim_data, W, L)
    if step > 1:
        # The geotransform must describe the DECIMATED grid, or every downstream alignment is wrong
        # by a factor of `step`. Pixel size scales; the origin (an edge) does not move.
        gt = (gt[0], gt[1] * step, gt[2], gt[3] * step)
        print(f"DIFF: decimated by 1/{step} on read -> {W}x{L} (memory-bounded)")
    return i + 1j * q, gt


def _dimap_gt(dim_data: Path, W: int, L: int) -> "tuple[float, float, float, float]":
    """Recover (x0, dx, y0, dy) from the sibling .dim IMAGE_TO_MODEL_TRANSFORM."""
    dim = dim_data.with_suffix(".dim")
    if not dim.exists():
        cand = list(dim_data.parent.glob(dim_data.stem + ".dim"))
        dim = cand[0] if cand else None
    if dim is None or not dim.exists():
        die(f"no .dim beside {dim_data}: cannot establish the SNAP geotransform")
    m = re.search(r"IMAGE_TO_MODEL_TRANSFORM>([^<]+)", dim.read_text(errors="replace"))
    if not m:
        die(f"{dim.name}: no IMAGE_TO_MODEL_TRANSFORM")
    v = [float(x) for x in re.split(r"[,\s]+", m.group(1).strip()) if x]
    if len(v) < 6:
        die(f"{dim.name}: malformed transform {v}")
    # SNAP writes an affine as [m00, m10, m01, m11, m02, m12] = [dx, 0, 0, dy, x0, y0]
    return v[4], v[0], v[5], v[3]


def read_gtiff(p: Path) -> "tuple[np.ndarray, tuple[float, float, float, float]]":
    from osgeo import gdal
    gdal.UseExceptions()
    ds = gdal.Open(str(p))
    if ds is None:
        die(f"cannot open {p}")
    a = ds.GetRasterBand(1).ReadAsArray()
    gt = ds.GetGeoTransform()
    ds = None
    if not np.iscomplexobj(a):
        die(f"{p} is not complex")
    return a, (gt[0], gt[1], gt[3], gt[5])


# --- alignment --------------------------------------------------------------------------------
def align(a: np.ndarray, ga, b: np.ndarray, gb):
    """Crop both rasters to their common extent using INTEGER pixel offsets only."""
    (ax, adx, ay, ady) = ga
    (bx, bdx, by, bdy) = gb
    if not (np.isclose(adx, bdx, atol=1e-6) and np.isclose(ady, bdy, atol=1e-6)):
        die(f"posting mismatch: SNAP ({adx},{ady}) vs ISCE3 ({bdx},{bdy}). The comparison would be "
            f"measuring resampling, not the algorithms.")
    ox, oy = (bx - ax) / adx, (by - ay) / ady
    fr = max(abs(ox - round(ox)), abs(oy - round(oy)))
    if fr > 0.01:
        die(f"grids are NOT co-lattice: fractional offset {fr:.4f} px. Both engines were asked for "
            f"the same snapped grid, so this is a real finding -- investigate before diffing.")
    ox, oy = int(round(ox)), int(round(oy))
    x0, y0 = max(0, ox), max(0, oy)
    x1, y1 = min(a.shape[1], ox + b.shape[1]), min(a.shape[0], oy + b.shape[0])
    if x1 <= x0 or y1 <= y0:
        die("the two interferograms do not overlap")
    A = a[y0:y1, x0:x1]
    B = b[y0 - oy:y1 - oy, x0 - ox:x1 - ox]
    print(f"DIFF: SNAP {a.shape}  ISCE3 {b.shape}  offset ({ox},{oy})  common {A.shape}")
    return A, B


# --- report -----------------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--case", type=Path, required=True)
    ap.add_argument("--snap", type=Path, required=True, help="BEAM-DIMAP .data dir of the SNAP ifg")
    ap.add_argument("--isce3", type=Path, required=True, help="ISCE3 ifg GeoTIFF")
    ap.add_argument("--report", type=Path, required=True)
    a = ap.parse_args()

    case = yaml.safe_load(a.case.read_text(encoding="utf-8"))
    zs, gs = read_envi_pair(a.snap)
    zi, gi = read_gtiff(a.isce3)
    A, B = align(zs, gs, zi, gi)

    good = (np.isfinite(A.real) & np.isfinite(A.imag) & (A != 0)
            & np.isfinite(B.real) & np.isfinite(B.imag) & (B != 0))
    n = int(good.sum())
    if n == 0:
        die("no pixel is valid in BOTH interferograms -- nothing to compare")
    cov = 100.0 * n / A.size

    # Double difference. A constant offset is not a defect: each engine may carry a different
    # absolute phase reference. Estimate it as the circular mean and remove it, then report it.
    d = np.angle(A * np.conj(B))
    dv = d[good]
    bias = float(np.angle(np.mean(np.exp(1j * dv))))
    res = np.angle(np.exp(1j * (dv - bias)))

    R = float(np.abs(np.mean(np.exp(1j * (dv - bias)))))
    circ_std = float(np.sqrt(-2.0 * np.log(max(R, 1e-12))))
    rms = float(np.sqrt(np.mean(res ** 2)))
    p50, p95 = (float(x) for x in np.percentile(np.abs(res), [50, 95]))

    # Multilooked circular std: the metric that CAN see smooth scene-scale error.
    B32 = 32
    H, W = (A.shape[0] // B32) * B32, (A.shape[1] // B32) * B32
    ml_std = float("nan")
    if H and W:
        cx = np.where(good, np.exp(1j * np.angle(A * np.conj(B))) * np.exp(-1j * bias), 0.0)
        cnt = good[:H, :W].reshape(H // B32, B32, W // B32, B32).sum(axis=(1, 3))
        acc = cx[:H, :W].reshape(H // B32, B32, W // B32, B32).sum(axis=(1, 3))
        ok = cnt > (B32 * B32) // 4
        if ok.any():
            mlR = np.abs(acc[ok] / cnt[ok])
            ml_std = float(np.sqrt(-2.0 * np.log(np.clip(np.mean(mlR), 1e-12, 1.0))))

    tol = case.get("tolerances") or {}
    checks = []
    t = (tol.get("rms_difference") or {}).get("max")
    if t is not None:
        checks.append(("rms_difference", rms, t, rms <= t))
    t = (tol.get("circular_std_multilooked") or {}).get("max")
    if t is not None and np.isfinite(ml_std):
        checks.append(("circular_std_multilooked", ml_std, t, ml_std <= t))

    conv = case.get("conventions") or {}
    lines = [
        f"# Fringe diff: {case.get('case', a.case.stem)}",
        "",
        "SNAP (Microwave Toolbox) vs ISCE3/COMPASS, geocoded interferograms on a shared lattice.",
        "",
        "| | |",
        "|---|---|",
        f"| feature | {case.get('feature','?')} |",
        f"| conventions | flattened={conv.get('flattened')}, azimuth_carrier={conv.get('azimuth_carrier')} |",
        f"| common grid | {A.shape[1]} x {A.shape[0]} px |",
        f"| valid in both | {n} px ({cov:.1f}%) |",
        "",
        "## Phase agreement",
        "",
        "| metric | value | note |",
        "|---|---|---|",
        f"| constant offset (removed) | {bias:+.4f} rad | not a defect: differing absolute phase reference |",
        f"| circular std (per-pixel) | {circ_std:.4f} rad | includes decorrelation noise |",
        f"| RMS residual | {rms:.4f} rad | after removing the constant |",
        f"| median abs residual | {p50:.4f} rad | |",
        f"| 95th pct abs residual | {p95:.4f} rad | |",
        f"| **circular std, 32x32 multilook** | **{ml_std:.4f} rad** | **sees smooth scene-scale error that residue density cannot** |",
        "",
    ]
    if checks:
        lines += ["## Tolerances", "", "| metric | value | limit | verdict |", "|---|---|---|---|"]
        for nm, v, t, ok in checks:
            lines.append(f"| {nm} | {v:.4f} | {t} | {'PASS' if ok else 'FAIL'} |")
        lines.append("")
    else:
        lines += ["## Tolerances", "", "No applicable tolerance declared in the case.", ""]

    lines += [
        "## How to read the residual",
        "",
        "- smooth ramp -> geometry/geocoding difference",
        "- salt-and-pepper -> decorrelation or noise, not an implementation difference",
        "- localised patches -> genuine disagreement, worth investigating",
        "",
        "Not established by this diff: absolute correctness. Both engines agreeing does not make them",
        "right, and the constant offset is removed by construction.",
    ]

    a.report.parent.mkdir(parents=True, exist_ok=True)
    a.report.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"DIFF: valid in both {n} px ({cov:.1f}%)")
    print(f"DIFF: constant offset {bias:+.4f} rad (removed)")
    print(f"DIFF: RMS residual {rms:.4f} rad, median {p50:.4f}, p95 {p95:.4f}")
    print(f"DIFF: circular std per-pixel {circ_std:.4f} rad, 32x32 multilooked {ml_std:.4f} rad")
    for nm, v, t, ok in checks:
        print(f"DIFF: {'PASS' if ok else 'FAIL'} {nm} {v:.4f} (limit {t})")
    print(f"DIFF: report -> {a.report}")
    return 0 if all(ok for _, _, _, ok in checks) else 1


if __name__ == "__main__":
    sys.exit(main())
