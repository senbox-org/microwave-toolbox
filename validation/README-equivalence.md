# GSLC vs traditional-DInSAR numeric equivalence harness

`gslc_equivalence.py` consolidates the ad-hoc analysis scripts written during the ERS
GSLC InSAR-parity investigation (`ers_final_diff.py`, `ers_night1.py`, `ers_blockcc.py`,
originally in a scratch working directory) into one reusable, machine-parsable CLI.

Given two BEAM-DIMAP interferogram products — one produced through the GSLC pipeline, one
through the traditional pipeline (CreateStack + Cross-Correlation + Warp + Interferogram,
optionally Terrain-Corrected) — it answers one question: **do these two interferograms
agree, up to the datum ambiguities that are expected to differ (an absolute phase offset
and a single geometric ramp), over their common ground overlap?**

It works directly off disk (memmapped big-endian float32 ENVI bands + the `.dim`'s
`IMAGE_TO_MODEL_TRANSFORM`), with no SNAP/JVM dependency, so it can be run standalone
during algorithm development.

This is not a Maven module and is not part of the build. See
`GSLCEquivalenceLongTest` (`sar-op-insar`) for the `-Denable.long.tests=true`-gated JUnit
wrapper that shells out to this script.

## Usage

```bash
python gslc_equivalence.py <gslc_ifg.dim> <trad_ifg_tc.dim> \
    [--coh-gslc BAND_BASENAME] [--coh-trad BAND_BASENAME] [--cell-size-deg 9e-4]

python gslc_equivalence.py --selftest
```

- `<gslc_ifg.dim>` / `<trad_ifg_tc.dim>`: BEAM-DIMAP products each containing an
  `i_ifg*`/`q_ifg*` complex band pair (glob-matched, so band-name suffixes like `_VV` are
  handled transparently).
- `--coh-gslc` / `--coh-trad`: coherence band basename (no extension) to use instead of
  autodetecting `coh*.img` in the product's `.data` directory. **Both are optional** — if
  either product has no coherence band, the `coherence-parity` gate SKIPs rather than
  erroring, since not every traditional-pipeline product includes one (e.g. plain
  Terrain-Correction on an `i_ifg`/`q_ifg` pair with `includeCoherence=false`).
- `--cell-size-deg`: common-grid cell size in degrees; default `9e-4` (~100 m), matching
  the ad-hoc scripts.
- `--selftest`: runs a synthetic self-check (see below) instead of reading real products.

Exit code is **0 iff every gate that ran printed PASS** (a `SKIP`ped gate does not fail the
run).

## Output contract

One line per gate, exactly:

```
GATE <name> PASS <value> <threshold>
GATE <name> FAIL <value> <threshold>
GATE <name> SKIP <reason...>
```

Lines starting with `#` are diagnostic/context (input dimensions, geotransforms, grid
size) and are not part of the gate contract — a consumer should only look at lines
starting with `GATE `.

## Gate table

| Gate | Threshold | Meaning |
|---|---|---|
| `common-grid-valid-cells` | `>= 100000` | Sanity check: enough geographic overlap between the two products to draw any conclusion at all. |
| `phase-residual-conc` | `>= 0.85` | Magnitude-weighted concentration `\|sum(D)\| / sum(\|D\|)` of the diff field `D = GSLC · conj(trad)`, after removing ONE fitted plane (see "Plane removal" below). **D itself is the complex per-cell aggregate — not normalized to a unit phasor per cell first** — so a cell built from stronger/more-coherent samples counts for more, matching how the 0.85 target and the 0.32–0.36 broken range were originally measured. 1.0 = phase perfectly aligned after removing the ramp; 0.0 = uniformly random. Measured on the broken ERS v5 product: 0.355 (right in the documented 0.32–0.36 broken range); the trad self-noise ceiling (comparing trad against itself under equivalent conditions) is ~0.9. |
| `residual-rms-rad` | `<= 1.0` | RMS of the wrapped residual phase (radians) after plane removal, over the same valid-cell set as `phase-residual-conc`. |
| `gx-median-ratio` | `<= 2.0` | Median \|gx\| (column-direction, i.e. range/x, lag-1 phase gradient) of the GSLC ifg divided by the same quantity for the trad ifg, both measured independently on the common grid (not on the diff field). Measured broken: 4–24 (see run below); a floor of `1e-4` rad/cell is applied to the trad-side denominator to avoid division by ~0. |
| `gy-median-ratio` | `<= 2.0` | Same as `gx-median-ratio` but for the row-direction (azimuth/y) gradient. |
| `coherence-parity` | `>= -0.02` | `mean(coh_GSLC) − mean(coh_trad)` over common coherent cells. SKIPs if either product lacks a coherence band. |

### Plane removal

`gx`/`gy` for the plane-fit are estimated the same way the ad-hoc scripts did: for the
diff field `D = GSLC · conj(trad)`, reduce every cell to its **unit phasor** (amplitude
discarded), then take the lag-1 conjugate product between adjacent columns (→ one `gx`
estimate per column, aggregated across the whole column) and between adjacent rows (→
one `gy` estimate per row, aggregated across the whole row); the reported `gx`/`gy` are
the **median** across those per-column / per-row estimates. This is wrapped-safe (no
unwrapping needed) and matches `ers_final_diff.py`'s per-row `gy` computation, generalized
to both axes. The plane `exp(-j(gx·x + gy·y))` is then divided out of `D` before computing
`phase-residual-conc` and `residual-rms-rad`.

The `gx-median-ratio`/`gy-median-ratio` gates apply this *same* per-row/column median
gradient estimator, but to the GSLC and trad ifg fields **independently** (each on its own
valid-cell mask), not to the diff field — they measure whether the GSLC ifg carries an
excess phase ramp relative to trad, which is the leading symptom of the currently-open
GSLC ramp bug.

**Two different "unit-phasor" uses, deliberately not the same statistic.** The `gx`/`gy`
estimators above normalize each cell to a unit phasor *before* the lag-1 conjugate
product, so a strong cell and a weak cell contribute equally to "which way is the phase
drifting" — correct for a directional plane fit. `phase-residual-conc`, computed after the
plane is removed, does the opposite on purpose: it sums the plane-corrected diff field `D`
**without** per-cell unit-phasor normalization, so a cell built from stronger/more-coherent
samples counts for more when asking "how well-aligned is the residual overall". Conflating
the two (normalizing before summing for `conc` as well) is exactly the bug this file's
current formula was corrected away from — see the gate table above and the ERS run below
for the numeric difference it makes.

## Self-test (`--selftest`)

Fast, file-free unit loop for TDD on the harness itself. Three cases, all in-memory:

1. **Identical grids** (`G == T`, 400×300 = 120,000 cells): the diff field is real and
   positive everywhere, so every phase gate should PASS trivially
   (`phase-residual-conc == 1.0`, `residual-rms-rad ≈ 0`).
2. **GSLC perturbed by a smooth 100-rad quadratic surface** (`G = G0 · exp(jφ)`,
   `φ = 200·((x/nx − 0.5)² + (y/ny − 0.5)²)`, peak-to-peak 100 rad, `T = G0` unperturbed):
   a global quadratic can't be captured by a single linear plane fit, so a large,
   effectively-random residual phase survives — `phase-residual-conc` and
   `residual-rms-rad` must FAIL, and because only the GSLC side carries the extra
   structure, `gx-`/`gy-median-ratio` must FAIL too (mirroring the real broken case, where
   the GSLC side is the one with excess gradient).
3. **Calibration evidence: noisy speckle, ~0.4 per-pixel coherence, ZERO systematic
   difference.** Per common-grid cell, a shared true phase `θ_ij ~ Uniform(-π, π)` (the
   ground truth both pipelines are trying to recover) is held constant across
   `samples_per_cell = 40` synthetic original-resolution pixels; each pipeline then gets
   its *own* independent complex noise realization at ~0.4 per-pixel coherence
   (`z = sqrt(0.4)·exp(jθ) + sqrt(0.6)·noise`), and the common-grid cell aggregate is the
   coherent sum of those 40 noisy samples — exactly what forming a ~100 m grid cell out of
   many original-resolution `i_ifg`/`q_ifg` samples does. Because the shared signal adds
   linearly with `samples_per_cell` while independent noise only grows as its square root,
   the cell aggregate's SNR — and with it the magnitude-weighted `phase-residual-conc` —
   climbs well above the noisy per-pixel coherence even though there is no systematic
   difference between the two pipelines to correct for. This is the calibration evidence
   that 0.85 is reachable on realistic noisy data with the shipped (magnitude-weighted)
   formula, not just on noise-free synthetic grids.

`selftest()` asserts case 1 passes everything, case 2 fails the phase gates, and case 3's
`phase-residual-conc` specifically passes (other gates in case 3, such as
`gx-median-ratio`, are not asserted — with a spatially uncorrelated per-cell true phase
there is no smooth structure for the gradient estimator to lock onto, so that ratio is
sampling noise, not a claim about calibration). Returns exit code 0 only if all three
hold. Measured (deterministic, seeded):

```
=== selftest case 1: identical grids (expect all gates PASS) ===
GATE common-grid-valid-cells PASS 120000 100000
GATE phase-residual-conc PASS 1 0.85
GATE residual-rms-rad PASS 2.35519e-17 1
GATE gx-median-ratio PASS 1 2
GATE gy-median-ratio PASS 1 2
GATE coherence-parity PASS 0.01 -0.02
case 1 result: ALL PASS

=== selftest case 2: GSLC perturbed by a smooth 100-rad surface (expect phase gates FAIL) ===
GATE common-grid-valid-cells PASS 120000 100000
GATE phase-residual-conc FAIL 0.0134141 0.85
GATE residual-rms-rad FAIL 1.81977 1
GATE gx-median-ratio FAIL 2.31604 2
GATE gy-median-ratio FAIL 2.35751 2
GATE coherence-parity PASS 0.01 -0.02
case 2 result: HAS FAILURE(S) as expected

=== selftest case 3: noisy speckle, pixel-coherence ~0.4, ZERO systematic difference (expect weighted phase-residual-conc to PASS on the cell aggregates) ===
GATE common-grid-valid-cells PASS 120000 100000
GATE phase-residual-conc PASS 0.978154 0.85
GATE residual-rms-rad PASS 0.215254 1
GATE gx-median-ratio FAIL 4.2411 2
GATE gy-median-ratio PASS 1.27627 2
GATE coherence-parity PASS 0 -0.02
case 3 measured phase-residual-conc = 0.978154 (threshold 0.85) -> PASS
case 3 result: HAS FAILURE(S)

SELFTEST OK: identical grids passed everything, the perturbed grid correctly failed phase gates, and noisy-but-unbiased cell aggregates (pixel coherence ~0.4) still clear the calibrated phase-residual-conc threshold -- 0.85 is reachable on realistic noisy data with the shipped (magnitude-weighted) formula.
```

Run it before touching real products — it takes well under a second and needs no data:

```bash
python gslc_equivalence.py --selftest
```

## Real ERS run (current v5 GSLC, executable statement of the open problem)

Run against `E:/Output/ers/ERS_v5_ifg.dim` (GSLC pipeline, coherence band
`coh_01Aug1995_02Aug1995`) vs `E:/Output/ers/trad_dinsar2_TC.dim` (traditional
DEM-Assisted-Coregistration + Terrain-Correction pipeline, `i_ifg*_VV`/`q_ifg*_VV` bands,
**no** coherence band):

```bash
python gslc_equivalence.py E:/Output/ers/ERS_v5_ifg.dim E:/Output/ers/trad_dinsar2_TC.dim
```

Actual output (2026-08-08, magnitude-weighted `phase-residual-conc`):

```
# gslc:  E:/Output/ers/ERS_v5_ifg.dim  19059x30978  geo=(7.096690744544219e-05, 3.5932611364780857e-05, 14.436904431987474, 38.22009937056849)
# trad:  E:/Output/ers/trad_dinsar2_TC.dim  19059x15685  geo=(7.096690744544219e-05, 7.096690744544219e-05, 14.436904431987474, 38.22011509108596)
# common grid: 1458 x 1192 cells at 0.0009 deg/cell
# coherence band unavailable for: trad
GATE common-grid-valid-cells PASS 361216 100000
GATE phase-residual-conc FAIL 0.355128 0.85
GATE residual-rms-rad FAIL 1.87873 1
GATE gx-median-ratio FAIL 24.128 2
GATE gy-median-ratio FAIL 4.12668 2
GATE coherence-parity SKIP (band unavailable)
```

Exit code: `1`.

**Interpretation — this is the executable statement of the open problem:** the common
grid has ample overlap (361k valid cells) so the comparison is meaningful, but the GSLC
v5 interferogram's phase does not agree with the traditional control even after removing
a single best-fit plane (`phase-residual-conc` 0.355 vs the 0.85 target — right in the
documented 0.32–0.36 broken range; the trad-vs-trad self-noise ceiling measured separately
is ~0.9). `gx-median-ratio` of 24 sits squarely in the previously-measured broken range
(10–30): the GSLC ifg carries roughly 24x the column-direction phase gradient that the
traditional ifg does over the same ground area, i.e. an un-modeled ramp specific to the
GSLC chain. `coherence-parity` SKIPs because this particular traditional-pipeline product
was generated without `includeCoherence`/without a Terrain-Corrected coherence band — it
is not a GSLC-side gap.

**Correction (fix round 1):** an earlier revision of this harness computed
`phase-residual-conc` by first normalizing every common-grid cell to a unit phasor and
*then* averaging (equal weight per cell regardless of the cell's own magnitude/reliability).
That is a different, uncalibrated statistic — on this exact ERS pair it read 0.066, not
0.355 — and the 0.85/0.32–0.36/~0.9 reference numbers this gate's threshold is calibrated
against were never measured with that formula. **The 0.066 → 0.355 change on this page was
caused entirely by that metric-normalization fix, not by any change to the plane-fit
(gradient/plane-removal math is unchanged and was correct throughout).** See the gate
table and "Plane removal" above for the corrected (shipped) formula, and self-test case 3
below for evidence that the corrected formula reaches 0.85+ on realistic noisy-but-unbiased
data.

## Expected runtimes

- `--selftest`: well under 1 second (120,000-cell in-memory grids, no I/O).
- Real ERS pair (19059×30978 GSLC vs 19059×15685 trad, both memmapped): a few seconds —
  the aggregation subsamples rows (oversampled ~3x per grid cell in azimuth, matching the
  ad-hoc scripts) so it never touches the full pixel count of either product.
- `GSLCEquivalenceLongTest` (JVM startup + process shell-out): ~10-15 seconds.
