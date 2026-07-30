# GSLC phase unwrapping — results report

**Date:** 2026-07-27
**Scene:** Sentinel-1A 23 Jun 2026 × Sentinel-1C 24 Jun 2026, IW3 / VV — the Venezuela coseismic pair
(Mw 7.2 + Mw 7.5 doublet, 22:04:33 / 22:05:11 UTC on the San Sebastián fault)
**Goal:** produce a *verified* unwrapped LOS displacement field from the GSLC interferogram, as the
prerequisite for Plan B §B3 (physical validation against published slip models and GNSS).

---

## 1. Summary

**Converged result: LOS displacement peak-to-peak ≈ 0.60 m (p5–p95), range ≈ −0.13 to +0.47 m.**

Established by convergence across three independent multilook factors, which agree with each other to
**0.8–1.8 cm RMS**. A full-resolution 24-tile solution is rejected as an outlier — it differs from all
three by ~18 cm over 93% of the scene, consistent with the tile-boundary artifact snaphu warned about
twice (§6).

| Solution | Tiling | ptp (p5–p95) | Verdict |
|---|---|---|---|
| Full resolution, 13.9 m | 6 × 4, 200 px overlap | 0.233 m | **rejected** — outlier |
| 4× multilook, 55.6 m | single tile | 0.621 m | converged |
| 8× multilook, 111 m | single tile | 0.594 m | converged |
| 16× multilook, 222 m | single tile | 0.600 m | converged |

External comparison: the converged peak of **+0.47 m** sits alongside a published DInSAR figure of
~30 cm LOS and a GNSS-derived projection of **+0.336 m** (at a station outside the scene). Same order,
which is as much as can be claimed until §9's model comparison is done.

**Open question:** the two coherence metrics disagree on whether ETAD-style corrections help (§7.5).

---

## 2. What was run

| Stage | Detail |
|---|---|
| Input | `…_GSLC_Stack_1C_ifg_flt.dim` — GSLC interferogram, topo phase and residual ramp removed, Goldstein filtered |
| Full scene | 8358 × 14516 @ 1.2566604e-4° (≈13.89 m), lon −69.206…−68.156, lat 9.619…11.443 |
| Subset | **4000 × 6000 at full resolution** (≈55 × 83 km), origin (−68.7233, 10.8120), centred on the epicentre (10.435 N, 68.472 W) |
| Unwrapper | **snaphu v2.0.4** (win64), auto-download URL `step.esa.int/thirdparties/snaphu/2.0.4/snaphu-v2.0.4_win64.zip` |
| Mode | `STATCOSTMODE DEFO`, `INITMETHOD MST` |
| Tiling (run 1) | 6 × 4 tiles, 200 px overlap, 8 processors |
| Runtime | **19 min wall clock, 75 min CPU** |

**Note on the subset.** Run 1 used a full-resolution subset. §7.1 shows that multilooking to ≈8 × 8
is the better choice for this scene, and §6 shows the multilooked solutions are the trustworthy ones —
so the full-resolution run is retained here only as the rejected comparison point.

---

## 3. Two blockers resolved along the way

**`SnaphuExport` requires a band whose unit is `phase`.** It ignores the complex bands entirely and
looks for a phase band plus a coherence band, so a product carrying only `i_`, `q_` and `coh_` fails
with `Wrapped phase band required`. A `Subset` that selects only the complex bands hits this. Fixed by
grafting the virtual `Phase_…` band declaration (`atan2(q,i)`) into the subset's `.dim` — valid because
its source bands were present — which avoided repeating the 12-minute subset.

**Endianness differs between the two file families.** BEAM-DIMAP `.img` files are **big-endian**;
the `.snaphu.img` files snaphu reads and writes are **native/little-endian**. Reading either with the
wrong dtype produces values around ±1e38 rather than an obvious failure.

---

## 4. Verification

Four tests, chosen because an unwrapped raster always *looks* plausible and unwrapping fails silently.

### [1] Re-wrap consistency — exact, but proves less than it appears

`wrap(unwrapped)` vs the input wrapped phase: median and p95 `|Δφ| = 0.0000` rad in **every** coherence
bin; 0.0% of pixels exceed 0.1 rad.

**What this establishes:** the full read/write path is correct — endianness, scaling, no byte-swap, no
offset. It would have caught an entire class of silent errors.
**What it does NOT establish:** snaphu outputs φ + 2πk *by construction*, so re-wrapping must return the
input. This says nothing about whether the 2πk choices are right. It is a necessary check, not evidence
of a correct unwrap.

### [2] Residue density — high; this is the ceiling on trust

**1142 residues per 10⁴ px** in the central 2000 × 2000 window (456,519 charges). Roughly 11% of pixels
carry an unwrapping ambiguity. For context, from the tutorial's zone comparison on this scene: 588 per
10⁴ px in the near-fault zone, 1604 in the vegetated south. The subset therefore sits nearer the
difficult end. Unwrapping had to guess in many places.

### [3] 2π jumps in the unwrapped field — acceptable

| coherence | n | frac \|Δφ\| > π | frac > 2π | max |
|---|---|---|---|---|
| > 0.3 | 809,403 | 0.925% | 0.014% | 9.76 rad |
| > 0.5 | 243,771 | 0.098% | 0.002% | 7.40 rad |

At coh > 0.5 the field is essentially smooth. The 0.925% at coh > 0.3 is consistent with **genuine**
steep fringes rather than errors — independently measured, the near-field phase rate reaches 2.66 rad
per row at p99, so |Δφ| > π legitimately occurs.

### [4] Coherence stratification — passes, and this is the informative one

LOS displacement (m), p5 / p50 / p95 by coherence bin:

| coherence | n | p5 | p50 | p95 | p5–p95 |
|---|---|---|---|---|---|
| 0.0–0.2 | 787,264 | −0.074 | −0.000 | +0.161 | 0.234 |
| 0.2–0.4 | 910,906 | −0.071 | −0.001 | +0.165 | 0.236 |
| 0.4–0.6 | 438,535 | −0.068 | +0.001 | +0.164 | 0.232 |
| 0.6–1.0 | 155,915 | −0.055 | +0.004 | +0.163 | 0.218 |

If the field were noise, the low-coherence bins would show **inflated** spread. They do not — the range
is flat across bins and slightly *narrower* at high coherence. The variance is therefore dominated by a
large-scale spatial field, not per-pixel noise. This is the test that most supports the result being
real signal.

---

## 5. The displacement figure

From the converged multilooked solutions (§6):

- **Peak-to-peak (p5–p95): ≈ 0.60 m LOS** — ML4 0.621, ML8 0.594, ML16 0.600
- **Range: ≈ −0.13 to +0.47 m** (ML8: p5 −0.128, p95 +0.468), sign + = toward the satellite
- One 2π fringe = **2.773 cm** LOS; 4.4138 mm/rad (λ = 5.5466 cm)

External comparisons (independent of this processing):
- Published DInSAR for this event: **~30 cm LOS**
- Published GNSS at CCS1 (dE −0.463, dN −0.007, dU +0.030 m) projected onto this scene's LOS vector
  (E −0.6775, N −0.1452, U +0.7210): **+0.336 m**. Note CCS1 (Caracas, ~66.9° W) lies **outside** the
  scene, so this validates convention and magnitude, not a co-located point.

The converged peak of +0.47 m is the same order as both, and larger — which is the expected direction,
since our scene contains the epicentre while the GNSS station does not. Turning "same order" into a
quantitative statement requires the forward-modelled slip comparison (§9).

## 6. Convergence test — which solution to trust

snaphu warned twice about the full-resolution run: `WARNING: Tile overlap is small (may give bad
results)` and `SUGGESTION: Try increasing tile overlap and/or size if solution has edge artifacts`.

Rather than pick a winner on plausibility, the test was **convergence**: unwrap the same interferogram
at 4×, 8× and 16× multilook — three different grids, three separate **single-tile** snaphu runs, no
tiling involved — and see whether they agree with each other and with the full-resolution result.
All comparisons are de-medianed first, since unwrapped phase is defined only up to a global constant,
and all are projected onto a common 250 × 375 grid (222 m cells, 86.2% valid).

**Pairwise RMS difference (cm) / fraction of pixels differing by more than one full fringe cycle:**

| | FULL | ML4 | ML8 | ML16 |
|---|---|---|---|---|
| **FULL** | — | 19.0 / 93% | 18.2 / 93% | 18.3 / 93% |
| **ML4** | 19.0 / 93% | — | 1.8 / 7% | 1.8 / 6% |
| **ML8** | 18.2 / 93% | 1.8 / 7% | — | 0.8 / 3% |
| **ML16** | 18.3 / 93% | 1.8 / 6% | 0.8 / 3% | — |

**Conclusion: the full-resolution 24-tile solution is rejected.** Three independent solutions agree to
0.8–1.8 cm and all three disagree with it by ~18 cm across 93% of pixels. A disagreement that is
overwhelmingly *whole fringe cycles* is the signature of unwrapping-branch divergence, and the most
likely mechanism is tile-to-tile offsets in the 24-tile assembly with marginal 200 px overlap — the
failure snaphu explicitly warned about.

The multilooked runs are also better conditioned in every respect: single tile (no seams to get wrong),
coherence proxy **median 0.671** against 0.231 single-look (73.6% > 0.3 vs 37%), and dramatically
cheaper — **64 s** for ML8 against **19 min** for the full-resolution run.

**Independent verification of the ML8 solution** (same tests as §4):
- Re-wrap: max `|Δφ|` = 1.4e-4 rad.
- 2π jumps: **0.026%** above π at coh > 0.5 (against 0.098% full-resolution) — a smoother field.
- Coherence stratification: ptp 0.570 / 0.588 / 0.601 / 0.596 m across the four bins — flat, so
  dominated by a large-scale field rather than per-pixel noise.

**The fourth run confirms the tiling diagnosis — and the limits of fixing it that way.** A
full-resolution unwrap with **3 × 2 tiles and 600 px overlap** gives ptp **0.448 m**:

| solution | ptp (p5–p95) | RMS vs ML8 | frac > 2π vs ML8 |
|---|---|---|---|
| FULL, 6 × 4 tiles, 200 px | 0.233 m | 18.2 cm | 93% |
| FULL, 3 × 2 tiles, 600 px | **0.448 m** | **7.35 cm** | 77% |
| ML4 / ML8 / ML16 (single tile) | 0.594–0.621 m | ≤1.8 cm | ≤7% |

Tripling the overlap and quartering the tile count moved the answer **monotonically toward** the
converged value (0.233 → 0.448 → 0.594) and halved the disagreement, which is direct evidence that
tile-boundary error was the dominant defect. But it did **not** converge: even with generous overlap,
full-resolution unwrapping at median coherence 0.23 remains unreliable. **Multilooking, not better
tiling, is the fix** — and it is also ~20× cheaper.

---

## 7. Caveats to carry forward

### 7.1 Multilook before unwrapping — and measure the right thing

Raw adjacent-pixel phase differences are NOT a guide to a safe multilook factor: at coherence ~0.23
they are dominated by noise, not by the signal's gradient. Measure instead by applying the multilook
first and then measuring the block-to-block step, i.e. the signal's rate at that scale:

| multilook B | cell | p50 rad | p95 rad |
|---|---|---|---|
| 1 | 13.9 m | 0.680 | 2.705 |
| 2 | 27.8 m | 0.607 | 2.636 |
| 4 | 55.6 m | 0.470 | 2.393 |
| **8** | **111 m** | **0.423** | **2.079** ← minimum |
| 16 | 222 m | 0.530 | 2.183 |
| 32 | 445 m | 0.838 | 2.670 |

The step **falls** to a minimum at B = 8 and only rises beyond B ≈ 16 — the classic
noise-versus-resolution optimum. Below B ≈ 8 the step is noise-dominated, so averaging helps; above
it, genuine signal variation across a cell begins to dominate. Aliasing would instead make the step
rise monotonically. p95 never reaches π at any B tested.

**Guidance: multilook to ≈ 8 × 8 (about 111 m) before unwrapping this scene.** It raises the
coherence proxy from 0.231 to 0.671, removes the need for snaphu tiling entirely, and cuts runtime
from 19 min to 64 s.

The phase rate is essentially **isotropic** (p95 ratio row/col 0.85, 2.02 vs 2.39 rad/px), so there is
no case for multilooking one axis preferentially on this scene.

### 7.2 snaphu is given radar-geometry parameters for a map-geometry raster — **measured, and it barely matters**
`snaphu.conf` carries `DR` = 2.3296 m (slant range) and `DA` = 15.6179 m (azimuth) with
`NCORRLOOKS 23.8`, but a GSLC raster is in **map** geometry (13.89 m square, or 111 m after 8×
multilook). SNAP writes these unscaled even for a multilooked product.

Rather than leave this as a worry, it was tested: the same multilooked interferogram was unwrapped
twice, once with SNAP's unscaled values and once with `DR`/`DA` scaled ×8 and `NCORRLOOKS 30`.

| | ptp (p5–p95) |
|---|---|
| SNAP export (DR 2.33, DA 15.62, NCORRLOOKS 23.8) | 0.596 m |
| Hand-built (DR 18.64, DA 124.94, NCORRLOOKS 30.0) | 0.594 m |

**RMS difference 1.66 cm**, and the reported displacement changes by 2 mm. So the cost-parameter
mismatch is **not** a material error source here — `DEFO` costs are indeed generic enough. (28.9% of
pixels differ by more than π, but those are scattered low-coherence cells, not a systematic shift: the
RMS and the summary statistic both stay put.) The caveat is worth knowing but does not need fixing
before the result can be used.

### 7.3 Long-wavelength signal has already been removed
The input had `subtractResidualRamp` applied, which fits and removes a low-order polynomial. Any
scene-wide linear deformation gradient is therefore absorbed. Comparisons against GNSS or slip models
must be made on the **relative** field (differences between locations), not absolute LOS offsets.

### 7.4 Coherence is low
Median coherence over the subset is **0.231**, mean 0.256; only 37% of pixels exceed 0.3 and 13.9% are
nodata. This bounds what any unwrapper can achieve here.

---

## 8. Reproduction

```bash
# 1. snaphu (auto-downloaded by Batch Snaphu Unwrapping, or directly):
#    step.esa.int/thirdparties/snaphu/2.0.4/snaphu-v2.0.4_win64.zip   (keep bin/ intact: needs msys-2.0.dll)

# 2. full-resolution subset centred on the fault
gpt Subset -Ssource=<ifg_flt>.dim -Pregion=3841,5022,4000,6000 \
    -PsourceBands=i_ifg_…,q_ifg_…,coh_… -PcopyMetadata=true \
    -t ifg_sub.dim -f BEAM-DIMAP
#    then ensure a band with unit `phase` is present (see §3)

# 3. export
gpt SnaphuExport -Ssource=ifg_sub.dim -PtargetFolder=snaphu_out \
    -PstatCostMode=DEFO -PinitMethod=MST \
    -PnumberOfTileRows=6 -PnumberOfTileCols=4 -PnumberOfProcessors=8 \
    -ProwOverlap=400 -PcolOverlap=400 -PtileCostThreshold=500

# 4. unwrap  (last argument is raster WIDTH)
cd snaphu_out/ifg_sub
snaphu -f snaphu.conf Phase_ifg_….snaphu.img 4000

# 5. import and convert
gpt SnaphuImport -SsnaphuPhase=UnwPhase_….snaphu.hdr -Swrapped=ifg_sub.dim -t unw.dim
gpt PhaseToDisplacement -Ssource=unw.dim -t disp.dim
```

Verification scripts used for §4 and §6 are in the session scratchpad (`check_unwrap.py`,
`compare_unwrap.py`).

---

## 8b. Method constraints for the remaining work

Three constraints this exercise established, all of which bind the model-comparison work that follows.

1. **Internal consistency is not correctness.** The re-wrap test passes *by construction* — snaphu's
   output satisfies it necessarily — so it validates the read/write path and nothing about the
   unwrapping decisions. Only agreement between independently-computed solutions settles a value.
2. **No displacement number is reportable until two independently-computed solutions agree on it.**
   The operative evidence here is three multilook factors agreeing to 0.8–1.8 cm, not any single
   careful run.
3. **Match the metric to the quantity.** Residue density and small-window coherence measure *local*
   phase noise and are blind to a smooth scene-scale field; long-wavelength phase needs a statistic
   like the circular σ of the heavily-multilooked phase (§7.5).

## 8c. Delivered products — and two operators that reject GSLC input

The ML8 solution was taken through to a SNAP displacement product:

| Product | Contents |
|---|---|
| `E:/Output/unwrap/ifg_ml8.dim` | 8×-coarsened interferogram, 500 × 750 @ 0.00100533° (111 m), i/q/coh/Phase |
| `E:/Output/unwrap/unw_ml8.dim` | + `Unw_Phase_ifg_23Jun2026_24Jun2026` (unit `abs_phase`) |
| `E:/Output/unwrap/disp_ml8.dim` | **`displacement`** band, unit **m**, geocoded |

**Final displacement figures** (de-medianed, 86.2% valid):

| statistic | SNAP sign (+ = away from satellite) | toward-satellite sign |
|---|---|---|
| p5 | −0.453 | −0.143 |
| p95 | +0.143 | +0.453 |
| **ptp (p5–p95)** | **0.596 m** | 0.596 m |
| p1 / p99 | −0.534 / +0.155 | — |

This matches the independently computed ML8 value of 0.594 m to **2 mm**.

**Two standard operators refuse map-projected input, which blocks the normal GSLC workflow:**

1. **`Multilook`** — `Source product should not be map projected.` Since multilooking is precisely what
   makes GSLC unwrapping reliable (§6, §7.1), this is a real functional gap for geocode-first
   processing. **Workaround used:** `Resample` with `targetWidth/targetHeight` and
   `downsampling=Mean`, which is exactly coherent averaging of i and q, preserves the geocoding
   (verified: step 0.00100533° = 8 × 1.2566604e-4, origin unchanged) and carries the `Phase` band
   through with its `phase` unit intact.
2. **`PhaseToDisplacement`** — same rejection. **Workaround used:** `BandMaths` with
   `-(λ/4π) · Unw_Phase`, matching `PhaseToDisplacementOp:184` exactly (λ/4π = 0.004413706 m/rad) and
   preserving the sign convention, so the output is equivalent to what the operator would produce.

Both are candidates for a small follow-up: allowing map-projected input where the operation is
geometry-agnostic. `PhaseToDisplacement` in particular is pure per-pixel arithmetic with no geometric
assumption at all, so its guard looks over-broad.

**Two `gpt` invocation notes** worth recording: `SnaphuImport` takes exactly two source products and
would not accept a comma-separated `-SsourceProducts=` list on Windows (`The filename, directory name,
or volume label syntax is incorrect`); it needs a graph with `<sourceProduct>` and
`<sourceProduct.1>`. `Resample`'s source parameter is `-SsourceProduct`, not `-Ssource`.

---

## 9. Next steps
3. Plan B §B3.1c — fetch the USGS / INGV / Peking finite-fault models and forward-model them to LOS on
   this grid; compare against all three rather than the best-fitting one.
4. Plan B §B4 — the correction ablation (ETAD / GSLC-level / interferogram-domain), which must run with
   `subtractResidualRamp=false` so ramp removal does not absorb the very signal under test.
