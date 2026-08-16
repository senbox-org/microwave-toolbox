"""GSLC vs traditional-DInSAR numeric equivalence harness.

Consolidates the proven ad-hoc analysis scripts written during the ERS GSLC InSAR-parity
investigation (``E:\\ESA\\snap_tmp\\ers_final_diff.py``, ``ers_night1.py``, ``ers_blockcc.py``)
into one reusable, machine-parsable CLI. Given two BEAM-DIMAP interferogram products -- one
produced through the GSLC pipeline, one through the traditional (CreateStack + Cross-Correlation
+ Warp + Interferogram [+ Terrain-Correction]) pipeline -- it:

1. Reads each product's complex interferogram bands (``i_ifg*``/``q_ifg*``) directly off disk via
   big-endian float32 memmaps (no SNAP/JVM dependency), using the ``.hdr`` for dimensions and the
   ``.dim``'s ``IMAGE_TO_MODEL_TRANSFORM`` for the geocoding.
2. Aggregates both onto one common geographic grid (~100 m cells by default) by complex
   accumulation, even when the two products have different pixel spacing ("different lattices").
3. Computes a fixed set of gates that together answer "do these two interferograms agree, up to
   the datum ambiguities that are expected to differ (absolute phase, and a single ramp), in the
   overlap region?" -- see the GATE_THRESHOLDS table below and validation/README-equivalence.md
   for the physical meaning of each one and the numbers measured on the ERS campaign.

Usage
-----
    python gslc_equivalence.py <gslc_ifg.dim> <trad_ifg_tc.dim> \\
        [--coh-gslc BAND_BASENAME] [--coh-trad BAND_BASENAME] [--cell-size-deg 9e-4]

    python gslc_equivalence.py --selftest

Output contract
----------------
One line per gate, exactly:
    GATE <name> PASS <value> <threshold>
    GATE <name> FAIL <value> <threshold>
    GATE <name> SKIP <reason...>

Exit code is 0 iff no gate printed FAIL (SKIP does not fail the run).
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys

import numpy as np

# ---------------------------------------------------------------------------------------
# Gate thresholds (from the measured ERS campaign; see task-2.1 brief).
# ---------------------------------------------------------------------------------------
# name -> (comparison, threshold). comparison is ">=" or "<=" against the printed value.
GATE_THRESHOLDS = {
    "common-grid-valid-cells": (">=", 100000.0),
    "phase-residual-conc": (">=", 0.85),
    "residual-rms-rad": ("<=", 1.0),
    "gx-median-ratio": ("<=", 2.0),
    "gy-median-ratio": ("<=", 2.0),
    "coherence-parity": (">=", -0.02),
}

# Default common-grid cell size in degrees (~100 m), matching ers_final_diff.py.
DEFAULT_CELL_DEG = 9.0e-4

# Floor applied to a trad-side median gradient before it is used as a ratio denominator.
GRADIENT_FLOOR = 1.0e-4

# Minimum number of paired samples required for a per-row/per-column lag-1 gradient
# estimate to be trusted (else that row/column is skipped, matching the ad-hoc scripts'
# `m.sum() < 30`-style guards).
MIN_LAG1_SAMPLES = 30


def emit_gate(name: str, passed: bool, value: float, threshold: float) -> None:
    status = "PASS" if passed else "FAIL"
    print(f"GATE {name} {status} {value:.6g} {threshold:.6g}")


def emit_skip(name: str, reason: str) -> None:
    print(f"GATE {name} SKIP {reason}")


def evaluate(name: str, value: float) -> bool:
    """Apply GATE_THRESHOLDS[name], print the line, return whether it passed."""
    op, thr = GATE_THRESHOLDS[name]
    passed = (value >= thr) if op == ">=" else (value <= thr)
    emit_gate(name, passed, value, thr)
    return passed


# ---------------------------------------------------------------------------------------
# BEAM-DIMAP / ENVI I/O
# ---------------------------------------------------------------------------------------

def _read_hdr_dims(hdr_path: str) -> tuple[int, int]:
    txt = open(hdr_path, encoding="utf-8", errors="replace").read()
    w = int(re.search(r"samples\s*=\s*(\d+)", txt).group(1))
    h = int(re.search(r"lines\s*=\s*(\d+)", txt).group(1))
    return w, h


def geotransform(dim_path: str) -> tuple[float, float, float, float]:
    """Returns (dx, dy, lon0, lat0) in degrees, dy positive (south-increasing row)."""
    txt = open(dim_path, encoding="utf-8", errors="replace").read()
    m = re.search(r"<IMAGE_TO_MODEL_TRANSFORM>([^<]*)</IMAGE_TO_MODEL_TRANSFORM>", txt)
    if not m:
        raise ValueError(f"no IMAGE_TO_MODEL_TRANSFORM found in {dim_path}")
    tr = [float(v) for v in m.group(1).split(",")]
    return tr[0], -tr[3], tr[4], tr[5]


def _data_dir(dim_path: str) -> str:
    return dim_path[:-4] + ".data" if dim_path.lower().endswith(".dim") else dim_path + ".data"


def _declared_bands(dim_path: str) -> set[str]:
    """Band names the .dim actually declares — guards against orphaned .img files
    left in the .data dir by earlier runs with different band naming."""
    txt = open(dim_path, encoding="utf-8", errors="replace").read()
    return set(re.findall(r"<BAND_NAME>([^<]+)</BAND_NAME>", txt))


def load_complex_ifg(dim_path: str) -> tuple[np.memmap, np.memmap, int, int]:
    """Locate and memmap the i_ifg*/q_ifg* band pair for a BEAM-DIMAP interferogram product."""
    data_dir = _data_dir(dim_path)
    declared = _declared_bands(dim_path)
    matches = sorted(glob.glob(os.path.join(data_dir, "i_ifg*.img")))
    matches = [m for m in matches
               if os.path.splitext(os.path.basename(m))[0] in declared]
    if not matches:
        raise FileNotFoundError(f"no .dim-declared i_ifg*.img band found under {data_dir}")
    i_path = matches[0]
    q_path = i_path.replace("i_ifg", "q_ifg", 1)
    if not os.path.isfile(q_path):
        raise FileNotFoundError(f"matching q_ifg band not found: {q_path}")
    w, h = _read_hdr_dims(i_path.replace(".img", ".hdr"))
    iB = np.memmap(i_path, dtype=">f4", mode="r", shape=(h, w))
    qB = np.memmap(q_path, dtype=">f4", mode="r", shape=(h, w))
    return iB, qB, w, h


def find_coherence_band(dim_path: str, override_basename: str | None) -> str | None:
    """Returns the path to a coherence .img band, or None if unavailable."""
    data_dir = _data_dir(dim_path)
    if override_basename:
        candidate = os.path.join(data_dir, override_basename + ".img")
        return candidate if os.path.isfile(candidate) else None
    declared = _declared_bands(dim_path)
    matches = sorted(glob.glob(os.path.join(data_dir, "coh*.img")))
    matches = [m for m in matches
               if os.path.splitext(os.path.basename(m))[0] in declared]
    return matches[0] if matches else None


def load_real_band(img_path: str) -> tuple[np.memmap, int, int]:
    w, h = _read_hdr_dims(img_path.replace(".img", ".hdr"))
    band = np.memmap(img_path, dtype=">f4", mode="r", shape=(h, w))
    return band, w, h


# ---------------------------------------------------------------------------------------
# Common-grid aggregation (generalizes ers_final_diff.py's ml_to_grid to two arbitrary
# products of possibly-different pixel spacing / lattice).
# ---------------------------------------------------------------------------------------

class CommonGrid:
    __slots__ = ("nx", "ny", "lon_min", "lat_max", "cell_deg")

    def __init__(self, nx: int, ny: int, lon_min: float, lat_max: float, cell_deg: float):
        self.nx, self.ny = nx, ny
        self.lon_min, self.lat_max = lon_min, lat_max
        self.cell_deg = cell_deg


def build_common_grid(geo_a, dims_a, geo_b, dims_b, cell_deg: float,
                       margin_deg: float = 0.02) -> CommonGrid:
    (dxA, dyA, lon0A, lat0A) = geo_a
    (WA, HA) = dims_a
    (dxB, dyB, lon0B, lat0B) = geo_b
    (WB, HB) = dims_b

    lon_min = max(lon0A, lon0B) + margin_deg
    lat_max = min(lat0A, lat0B) - margin_deg
    lon_max = min(lon0A + WA * dxA, lon0B + WB * dxB) - margin_deg
    lat_min = max(lat0A - HA * dyA, lat0B - HB * dyB) + margin_deg
    nx = max(0, int((lon_max - lon_min) / cell_deg))
    ny = max(0, int((lat_max - lat_min) / cell_deg))
    return CommonGrid(nx, ny, lon_min, lat_max, cell_deg)


def aggregate_complex_to_grid(iB, qB, W, H, dx, dy, lon0, lat0, grid: CommonGrid,
                               min_count: int = 3) -> np.ndarray:
    """Complex accumulation of an (i, q) band pair onto the common grid. Mirrors
    ers_final_diff.py's ml_to_grid: subsampled row scan (oversampled ~3x per cell in the
    row direction), scatter-add into grid cells, keep only cells with enough hits."""
    nx, ny = grid.nx, grid.ny
    out = np.zeros((ny, nx), np.complex128)
    cnt = np.zeros((ny, nx), np.int32)
    if nx == 0 or ny == 0:
        return out
    step = max(1, int(grid.cell_deg / dy / 3))
    lon = lon0 + (np.arange(W) + 0.5) * dx
    gx_all = ((lon - grid.lon_min) / grid.cell_deg).astype(int)
    col_ok = (gx_all >= 0) & (gx_all < nx)
    for r in range(0, H, step):
        lat = lat0 - (r + 0.5) * dy
        gy = int((grid.lat_max - lat) / grid.cell_deg)
        if gy < 0 or gy >= ny:
            continue
        ii = np.asarray(iB[r, :], np.float64)
        qq = np.asarray(qB[r, :], np.float64)
        m = col_ok & ((ii != 0) | (qq != 0))
        if not m.any():
            continue
        np.add.at(out, (gy, gx_all[m]), ii[m] + 1j * qq[m])
        np.add.at(cnt, (gy, gx_all[m]), 1)
    return np.where(cnt > min_count, out, 0)


def aggregate_real_to_grid(band, W, H, dx, dy, lon0, lat0, grid: CommonGrid,
                            min_count: int = 3) -> np.ndarray:
    """Same as aggregate_complex_to_grid but for a real-valued band (e.g. coherence),
    averaged rather than summed, with NaN for empty cells."""
    nx, ny = grid.nx, grid.ny
    out = np.zeros((ny, nx), np.float64)
    cnt = np.zeros((ny, nx), np.int32)
    if nx == 0 or ny == 0:
        return out
    step = max(1, int(grid.cell_deg / dy / 3))
    lon = lon0 + (np.arange(W) + 0.5) * dx
    gx_all = ((lon - grid.lon_min) / grid.cell_deg).astype(int)
    col_ok = (gx_all >= 0) & (gx_all < nx)
    for r in range(0, H, step):
        lat = lat0 - (r + 0.5) * dy
        gy = int((grid.lat_max - lat) / grid.cell_deg)
        if gy < 0 or gy >= ny:
            continue
        vv = np.asarray(band[r, :], np.float64)
        m = col_ok & np.isfinite(vv) & (vv != 0)
        if not m.any():
            continue
        np.add.at(out, (gy, gx_all[m]), vv[m])
        np.add.at(cnt, (gy, gx_all[m]), 1)
    valid = cnt > min_count
    mean = np.where(valid, out / np.where(cnt == 0, 1, cnt), np.nan)
    return mean


# ---------------------------------------------------------------------------------------
# Core phase math: median lag-1 gradients (the "ONE fitted plane"), plane removal,
# residual concentration/RMS. Generalizes ers_final_diff.py's per-row "py" gradient
# to both row (y) and column (x) directions.
# ---------------------------------------------------------------------------------------

def median_lag1_gradients(field: np.ndarray, valid: np.ndarray,
                           min_samples: int = MIN_LAG1_SAMPLES) -> tuple[float, float]:
    """Estimate (gx, gy): the median per-column and per-row lag-1 phase gradient (rad/cell)
    of a complex field, using unit-phasor conjugate products (wrapped-safe, amplitude-blind).

    gx: for each column c, correlate the whole column against column c-1 (unit phasors,
        masked to jointly-valid cells), take the phase of the mean -> one estimate per
        column; gx is the median of those estimates.
    gy: symmetric, correlating whole rows against the row above.

    This is exactly the per-row "py" gradient computed in ers_final_diff.py, applied to
    both grid axes.
    """
    ny, nx = field.shape
    mag = np.abs(field)
    unit = np.where(mag > 0, field / np.where(mag == 0, 1, mag), 0)

    col_est = []
    for c in range(1, nx):
        m = valid[:, c] & valid[:, c - 1]
        if m.sum() < min_samples:
            continue
        v = (unit[:, c][m] * np.conj(unit[:, c - 1][m])).mean()
        if abs(v) > 0:
            col_est.append(np.angle(v))
    gx = float(np.median(col_est)) if col_est else 0.0

    row_est = []
    for r in range(1, ny):
        m = valid[r, :] & valid[r - 1, :]
        if m.sum() < min_samples:
            continue
        v = (unit[r, :][m] * np.conj(unit[r - 1, :][m])).mean()
        if abs(v) > 0:
            row_est.append(np.angle(v))
    gy = float(np.median(row_est)) if row_est else 0.0

    return gx, gy


def remove_plane(field: np.ndarray, gx: float, gy: float) -> np.ndarray:
    ny, nx = field.shape
    yy, xx = np.mgrid[0:ny, 0:nx]
    plane = np.exp(-1j * (gx * xx + gy * yy))
    return field * plane


def concentration(field: np.ndarray, valid: np.ndarray) -> float:
    """Magnitude-weighted concentration: |sum(D)| / sum(|D|) over the valid cells, where
    D is the complex per-cell aggregate itself (NOT normalized to a unit phasor per cell
    first). 1.0 means the cells' phasors point the same way once weighted by their own
    reliability (magnitude -- a cell aggregated from more/stronger-correlated samples
    counts for more); 0.0 means they cancel.

    This is the statistic the gate thresholds (0.85 target, 0.32-0.36 broken, ~0.9 trad
    self-noise ceiling) were calibrated against. An earlier version of this function
    normalized every cell to a unit phasor before summing (i.e. gave every cell equal
    weight regardless of its own magnitude/reliability) -- that is a DIFFERENT,
    uncalibrated statistic and reads much lower on real noisy data (measured ~0.066 on
    the ERS pair vs. the calibrated ~0.33 with this weighted formula).
    """
    n = int(valid.sum())
    if n == 0:
        return 0.0
    vals = field[valid]
    total_mag = np.abs(vals).sum()
    if total_mag == 0:
        return 0.0
    return float(abs(vals.sum()) / total_mag)


def wrapped_rms(field: np.ndarray, valid: np.ndarray) -> float:
    if not valid.any():
        return float("nan")
    ang = np.angle(field[valid])
    return float(np.sqrt(np.mean(ang ** 2)))


# ---------------------------------------------------------------------------------------
# Shared gate computation -- operates on plain numpy grids, independent of how they were
# produced (real products via the .dim/.data loaders above, or synthetic self-test grids).
# ---------------------------------------------------------------------------------------

def compute_gates(G: np.ndarray, T: np.ndarray,
                   coh_g: np.ndarray | None, coh_t: np.ndarray | None,
                   metrics_out: dict | None = None) -> bool:
    """Prints all GATE lines for the given common-grid complex fields G (gslc), T (trad),
    and optional common-grid real coherence fields. Returns True iff no gate FAILed.

    If `metrics_out` is given, the raw computed value for each gate that actually ran is
    stashed into it (keyed by gate name) -- lets a caller (e.g. the self-test) inspect a
    specific measured number without re-parsing the printed GATE lines."""
    all_ok = True

    valid_g = np.abs(G) > 0
    valid_t = np.abs(T) > 0
    valid = valid_g & valid_t
    n_valid = int(valid.sum())
    all_ok &= evaluate("common-grid-valid-cells", float(n_valid))

    if n_valid == 0:
        # Nothing else can be computed meaningfully.
        for name in ("phase-residual-conc", "residual-rms-rad", "gx-median-ratio", "gy-median-ratio"):
            emit_skip(name, "(no common-grid overlap)")
        all_ok = False
    else:
        D = G * np.conj(T)

        # --- phase-residual-conc / residual-rms-rad: single fitted plane on the diff field ---
        gx_d, gy_d = median_lag1_gradients(D, valid)
        D_corr = remove_plane(D, gx_d, gy_d)
        conc = concentration(D_corr, valid)
        rms = wrapped_rms(D_corr, valid)
        all_ok &= evaluate("phase-residual-conc", conc)
        all_ok &= evaluate("residual-rms-rad", rms)
        if metrics_out is not None:
            metrics_out["phase-residual-conc"] = conc
            metrics_out["residual-rms-rad"] = rms

        # --- gx/gy-median-ratio: own-field gradients of G and T, independently ---
        gx_g, gy_g = median_lag1_gradients(G, valid_g)
        gx_t, gy_t = median_lag1_gradients(T, valid_t)
        gx_t_floored = max(abs(gx_t), GRADIENT_FLOOR)
        gy_t_floored = max(abs(gy_t), GRADIENT_FLOOR)
        if abs(gx_t) < GRADIENT_FLOOR:
            print(f"# note: gx-median-ratio trad median |gx|={abs(gx_t):.3g} below floor "
                  f"{GRADIENT_FLOOR:.1e}; floor value used as denominator")
        if abs(gy_t) < GRADIENT_FLOOR:
            print(f"# note: gy-median-ratio trad median |gy|={abs(gy_t):.3g} below floor "
                  f"{GRADIENT_FLOOR:.1e}; floor value used as denominator")
        gx_ratio = abs(gx_g) / gx_t_floored
        gy_ratio = abs(gy_g) / gy_t_floored
        all_ok &= evaluate("gx-median-ratio", gx_ratio)
        all_ok &= evaluate("gy-median-ratio", gy_ratio)
        if metrics_out is not None:
            metrics_out["gx-median-ratio"] = gx_ratio
            metrics_out["gy-median-ratio"] = gy_ratio

    # --- coherence-parity ---
    if coh_g is None or coh_t is None:
        emit_skip("coherence-parity", "(band unavailable)")
    else:
        coh_valid = valid & np.isfinite(coh_g) & np.isfinite(coh_t)
        if not coh_valid.any():
            emit_skip("coherence-parity", "(no common coherent cells)")
        else:
            diff = float(np.mean(coh_g[coh_valid]) - np.mean(coh_t[coh_valid]))
            all_ok &= evaluate("coherence-parity", diff)
            if metrics_out is not None:
                metrics_out["coherence-parity"] = diff

    return all_ok


# ---------------------------------------------------------------------------------------
# Real-product entrypoint
# ---------------------------------------------------------------------------------------

def run_products(gslc_dim: str, trad_dim: str, coh_gslc_name: str | None,
                  coh_trad_name: str | None, cell_deg: float) -> bool:
    iG, qG, WG, HG = load_complex_ifg(gslc_dim)
    geoG = geotransform(gslc_dim)
    iT, qT, WT, HT = load_complex_ifg(trad_dim)
    geoT = geotransform(trad_dim)

    print(f"# gslc:  {gslc_dim}  {WG}x{HG}  geo={geoG}")
    print(f"# trad:  {trad_dim}  {WT}x{HT}  geo={geoT}")

    grid = build_common_grid(geoG, (WG, HG), geoT, (WT, HT), cell_deg)
    print(f"# common grid: {grid.nx} x {grid.ny} cells at {cell_deg:g} deg/cell")

    G = aggregate_complex_to_grid(iG, qG, WG, HG, *geoG, grid)
    T = aggregate_complex_to_grid(iT, qT, WT, HT, *geoT, grid)

    coh_g_path = find_coherence_band(gslc_dim, coh_gslc_name)
    coh_t_path = find_coherence_band(trad_dim, coh_trad_name)
    coh_g_grid = coh_t_grid = None
    if coh_g_path and coh_t_path:
        bandG, cwG, chG = load_real_band(coh_g_path)
        bandT, cwT, chT = load_real_band(coh_t_path)
        coh_g_grid = aggregate_real_to_grid(bandG, cwG, chG, *geoG, grid)
        coh_t_grid = aggregate_real_to_grid(bandT, cwT, chT, *geoT, grid)
    else:
        missing = []
        if not coh_g_path:
            missing.append("gslc")
        if not coh_t_path:
            missing.append("trad")
        print(f"# coherence band unavailable for: {', '.join(missing)}")

    return compute_gates(G, T, coh_g_grid, coh_t_grid)


# ---------------------------------------------------------------------------------------
# Self-test: synthetic in-memory grids, no file I/O. Fast unit loop for TDD.
# ---------------------------------------------------------------------------------------

def _synthetic_base(ny: int, nx: int, seed: int = 42):
    rng = np.random.default_rng(seed)
    mag = 1.0 + 0.1 * rng.random((ny, nx))
    base_phase = (0.7 * np.sin(np.linspace(0, 3, ny))[:, None]
                  + 0.3 * np.cos(np.linspace(0, 2, nx))[None, :])
    G0 = mag * np.exp(1j * base_phase)
    coh_g = 0.80 + 0.05 * rng.random((ny, nx))
    coh_t = coh_g - 0.01  # GSLC slightly higher coherence: diff ~ +0.01, safely inside >= -0.02
    return G0, coh_g, coh_t


def _synthetic_noisy_cell_aggregates(ny: int, nx: int, pixel_coherence: float = 0.4,
                                      samples_per_cell: int = 40, seed: int = 99):
    """Simulate what a common-grid CELL aggregate looks like when built from many noisy
    original-resolution samples: GSLC-ifg and trad-ifg are two INDEPENDENT noisy estimates
    of the SAME underlying true interferometric phase per original pixel (per-pixel
    coherence `pixel_coherence`, e.g. ~0.4 -- quite noisy at full resolution), with zero
    systematic difference between the two pipelines (no ramp, no offset).

    Per cell (i, j): a shared true phase theta_ij ~ Uniform(-pi, pi) (constant across the
    `samples_per_cell` original pixels that fall in that cell -- this is the ground truth
    both pipelines are trying to recover), plus independent complex noise per original
    pixel and per pipeline:
        zg_k = sqrt(pixel_coherence) * exp(j*theta_ij) + sqrt(1 - pixel_coherence) * ng_k
        zt_k = sqrt(pixel_coherence) * exp(j*theta_ij) + sqrt(1 - pixel_coherence) * nt_k
    with ng_k, nt_k iid unit-variance circular complex Gaussian, independent of each other
    and across k. The cell aggregate is the coherent sum over the `samples_per_cell`
    original pixels (exactly what building a ~100 m grid cell from many original-
    resolution i_ifg/q_ifg samples does): G_cell = sum_k(zg_k), T_cell = sum_k(zt_k).

    Because the shared signal term grows linearly with samples_per_cell while the
    independent-noise term only grows as its square root, the cell aggregate's SNR
    improves with more samples per cell even though the underlying per-pixel data is
    quite noisy -- this is the "cell averaging suppresses noise" argument, made concrete.
    """
    rng = np.random.default_rng(seed)
    theta = rng.uniform(-np.pi, np.pi, size=(ny, nx))
    common = np.exp(1j * theta)[:, :, None]  # (ny, nx, 1), broadcasts over samples

    def crand(shape):
        return (rng.normal(size=shape) + 1j * rng.normal(size=shape)) / np.sqrt(2)

    shape = (ny, nx, samples_per_cell)
    a = np.sqrt(pixel_coherence)
    b = np.sqrt(1.0 - pixel_coherence)
    zg = a * common + b * crand(shape)
    zt = a * common + b * crand(shape)
    G_cell = zg.sum(axis=2)
    T_cell = zt.sum(axis=2)
    coh_g = np.full((ny, nx), pixel_coherence)
    coh_t = np.full((ny, nx), pixel_coherence)
    return G_cell, T_cell, coh_g, coh_t


def selftest() -> int:
    ny, nx = 400, 300  # 120,000 cells > the 100,000 common-grid-valid-cells threshold

    print("=== selftest case 1: identical grids (expect all gates PASS) ===")
    G0, coh_g, coh_t = _synthetic_base(ny, nx)
    T0 = G0.copy()
    ok1 = compute_gates(G0, T0, coh_g, coh_t)
    print(f"case 1 result: {'ALL PASS' if ok1 else 'HAS FAILURE(S)'}\n")

    print("=== selftest case 2: GSLC perturbed by a smooth 100-rad surface (expect phase gates FAIL) ===")
    yy, xx = np.mgrid[0:ny, 0:nx]
    Xn = xx / nx - 0.5
    Yn = yy / ny - 0.5
    phi = 200.0 * (Xn ** 2 + Yn ** 2)  # 0 at center rising smoothly to ~100 rad at the corners
    G_pert = G0 * np.exp(1j * phi)
    T_pert = G0.copy()
    ok2 = compute_gates(G_pert, T_pert, coh_g, coh_t)
    print(f"case 2 result: {'ALL PASS (unexpected!)' if ok2 else 'HAS FAILURE(S) as expected'}\n")

    print("=== selftest case 3: noisy speckle, pixel-coherence ~0.4, ZERO systematic "
          "difference (expect weighted phase-residual-conc to PASS on the cell "
          "aggregates) ===")
    G_noisy, T_noisy, coh_g3, coh_t3 = _synthetic_noisy_cell_aggregates(ny, nx)
    metrics3: dict = {}
    ok3 = compute_gates(G_noisy, T_noisy, coh_g3, coh_t3, metrics_out=metrics3)
    conc3 = metrics3.get("phase-residual-conc")
    conc3_ok = conc3 is not None and conc3 >= GATE_THRESHOLDS["phase-residual-conc"][1]
    print(f"case 3 measured phase-residual-conc = {conc3:.6g} "
          f"(threshold {GATE_THRESHOLDS['phase-residual-conc'][1]:g}) -> "
          f"{'PASS' if conc3_ok else 'FAIL'}")
    print(f"case 3 result: {'ALL PASS' if ok3 else 'HAS FAILURE(S)'}\n")

    success = ok1 and not ok2 and conc3_ok
    if success:
        print("SELFTEST OK: identical grids passed everything, the perturbed grid "
              "correctly failed phase gates, and noisy-but-unbiased cell aggregates "
              "(pixel coherence ~0.4) still clear the calibrated phase-residual-conc "
              "threshold -- 0.85 is reachable on realistic noisy data with the shipped "
              "(magnitude-weighted) formula.")
    else:
        print("SELFTEST FAILED: expected case 1 all-pass, case 2 to fail phase gates, "
              "and case 3's phase-residual-conc to pass.")
    return 0 if success else 1


# ---------------------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------------------

def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                      formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("gslc_dim", nargs="?", help="Path to the GSLC interferogram .dim")
    parser.add_argument("trad_dim", nargs="?", help="Path to the traditional interferogram .dim")
    parser.add_argument("--coh-gslc", dest="coh_gslc", default=None,
                         help="Coherence band basename (no extension) in the GSLC product; "
                              "autodetected (coh*.img) if omitted")
    parser.add_argument("--coh-trad", dest="coh_trad", default=None,
                         help="Coherence band basename (no extension) in the trad product; "
                              "OPTIONAL. If neither product has a coherence band, the "
                              "coherence-parity gate SKIPs")
    parser.add_argument("--cell-size-deg", dest="cell_deg", type=float, default=DEFAULT_CELL_DEG,
                         help=f"Common-grid cell size in degrees (default {DEFAULT_CELL_DEG:g})")
    parser.add_argument("--selftest", action="store_true",
                         help="Run the synthetic self-test instead of comparing real products")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    if not args.gslc_dim or not args.trad_dim:
        parser.error("gslc_dim and trad_dim are required unless --selftest is given")

    ok = run_products(args.gslc_dim, args.trad_dim, args.coh_gslc, args.coh_trad, args.cell_deg)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
