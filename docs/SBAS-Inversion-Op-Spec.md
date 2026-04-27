# SBAS Inversion Operator — Spec

**Status:** proposed
**Owner:** sar-op-insar
**Companion to:** existing `MultiMasterInSAROp` / `MultiMasterOp` (network *generation*); this spec covers the missing network *inversion*.

## 1. Motivation

`sar-op-insar` can already build small-baseline interferogram networks
(`MultiMasterInSAROp`) but stops there. To produce a deformation time series
the user has to export to StaMPS / MintPy / PyRate. This is the single
biggest gap in the toolbox vs. SOTA open-source InSAR (`mintpy ifgram_inversion`,
`isce3 + dolphin`, `LiCSBAS`).

This spec defines a native `SBASInversionOp` that takes a stack of unwrapped
small-baseline interferograms and inverts them for per-epoch phase /
displacement, plus diagnostic outputs (residuals, closure-phase, network
condition).

## 2. Algorithm

Following Berardino, Fornaro, Lanari, Sansosti, *IEEE TGRS* 2002.

### 2.1 Network model

Given `M` unwrapped interferograms formed from `N` SAR acquisitions
(`N ≤ M+1`), each interferogram `k` connects acquisition pair `(i_k, j_k)`,
`i_k < j_k`. For one ground pixel:

```
phi_k = phi_{j_k} - phi_{i_k} + n_k
```

`phi_n` is the unknown per-epoch phase (relative to a chosen reference
acquisition that we set to zero). Stacking all `M` equations:

```
A · x = phi_obs
```

with:
- `A ∈ R^{M × (N-1)}` — design matrix, one row per interferogram, two
  non-zero entries (+1, -1) marking the pair, columns indexed by the `N-1`
  free epochs.
- `x ∈ R^{N-1}` — phase at each non-reference epoch.
- `phi_obs ∈ R^M` — observed unwrapped interferometric phase at the pixel.

### 2.2 Reference epoch

Default: choose the acquisition closest to the median date so the design
matrix is well conditioned. Allow user override by epoch index.

### 2.3 Coherence-weighted LSQ

Form the diagonal weight matrix `W = diag(w_k)` from per-pair coherence
`gamma_k`, using the Cramér–Rao-style noise variance:

```
sigma_k^2 = (1 - gamma_k^2) / (2 * L * gamma_k^2)        (eq. 1)
w_k        = 1 / sigma_k^2
```

`L` is the number of independent looks used in the coherence window (read
from each interferogram's metadata — fall back to `cohWinAz * cohWinRg` of
the upstream `CoherenceOp`). Pixels with `gamma_k < gammaMin` are excluded
from the equation row for that pair (equivalent to `w_k = 0`).

Solve:

```
x_hat = (A^T W A)^{-1} A^T W phi_obs                      (eq. 2)
```

When `rank(W A) < N-1` the network is disconnected at this pixel; mark the
output epoch values no-data and continue.

When the network is full-rank but ill-conditioned (`cond(A^T W A) >
condThreshold`, default 1e6), apply Tikhonov regularization:

```
x_hat = (A^T W A + lambda I)^{-1} A^T W phi_obs           (eq. 3)
```

with `lambda = regWeight * trace(A^T W A) / (N-1)`,
`regWeight` default `1e-3`. Optional second-order temporal smoothing
(finite-difference operator on time gaps) when `temporalSmoothing=true`.

### 2.4 Velocity

After inversion, fit a weighted linear model `x_n = v · t_n + b` using the
same weights, project to LOS displacement:

```
d_n = -lambda_radar / (4 * pi) * x_n                      (eq. 4)
v_LOS = -lambda_radar / (4 * pi) * v
```

Output velocity in mm/yr.

### 2.5 Residuals and closure phase

Per-pair residual `r_k = phi_k - (phi_{j_k} - phi_{i_k})` written as a band.
For each closed triplet `(a, b, c)` in the network, closure phase:

```
phi_closure(a,b,c) = phi_{ab} + phi_{bc} - phi_{ac}       (eq. 5)
```

is written as a per-triplet output band (or, more compactly, the RMS
closure across all triplets). Non-zero closure indicates unwrapping errors
and is used downstream for triplet-based unwrap-error correction
(MintPy `unwrap_error_bridging` / `phase_closure`).

## 3. Inputs

A single SNAP product:

- A multi-temporal stack with bands grouped per interferogram. Required
  per pair `k`:
  - Unwrapped phase band, `Unit.PHASE`. Naming convention:
    `Unw_Phase_<masterDate>_<slaveDate>` (matches `SnaphuImportOp` output).
  - Coherence band, `Unit.COHERENCE`, paired by suffix.
- Acquisition timestamps recoverable from band metadata (slave/master MJD)
  or, fallback, parsed from the date suffix in the band name.

Optional:
- Reference point parameters (range, azimuth, lat/lon) — phase at the
  reference is forced to zero in the inversion to remove the constant
  reference-phase ambiguity.

## 4. Parameters

| name | type | default | notes |
|---|---|---|---|
| `referenceEpochDate` | string ("YYYY-MM-DD") | median epoch | `null` => median |
| `referencePoint` | "auto"/"manual" | "auto" | "auto" picks the highest-mean-coherence pixel |
| `referenceX`, `referenceY` | int | -1 | used when `referencePoint=manual` |
| `coherenceMin` | double | 0.3 | drop equations with `gamma < this` |
| `minPairsPerPixel` | int | `N-1` | below this, mark pixel no-data |
| `regularization` | "none"/"tikhonov"/"smoothing" | "tikhonov" | |
| `regWeight` | double | 1e-3 | scaled by trace(A^T W A) |
| `condThreshold` | double | 1e6 | `cond(A^T W A)` above => regularized solve |
| `outputResiduals` | boolean | true | per-pair residual bands |
| `outputClosurePhase` | boolean | true | RMS closure band |
| `outputVelocity` | boolean | true | linear fit + LOS conversion |

## 5. Outputs (target product bands)

- `phase_<epochDate>` (N-1 bands, `Unit.PHASE`) — per-epoch phase relative
  to reference epoch.
- `displacement_<epochDate>` (N-1 bands, mm) — converted via eq. (4).
- `velocity` (mm/yr, `Unit.METERS_PER_DAY` ×365e3 or new `Unit.MM_PER_YEAR`).
- `velocity_uncertainty` (1-sigma).
- `temporal_coherence` — Pepe-style metric:
  `gamma_T = | (1/M) sum_k exp(j (phi_k - A_k x_hat)) |` (range [0,1]).
- `residual_<masterDate>_<slaveDate>` (when `outputResiduals=true`).
- `closure_phase_rms` (when `outputClosurePhase=true`).

## 6. File layout

- `sar-op-insar/src/main/java/eu/esa/sar/insar/gpf/SBASInversionOp.java` —
  main operator (extends `Operator`, `@OperatorMetadata` alias `"SBASInversion"`,
  category `"Radar/Interferometric/Time-Series"`).
- `sar-op-insar/src/main/java/eu/esa/sar/insar/gpf/timeseries/Network.java` —
  builds `A`, validates connectivity, lists triplets.
- `sar-op-insar/src/main/java/eu/esa/sar/insar/gpf/timeseries/WeightedLSQ.java` —
  normal-equation solve with optional Tikhonov / smoothing.
- `sar-op-insar/src/test/java/eu/esa/sar/insar/gpf/TestSBASInversionOp.java` —
  unit + small synthetic-stack integration tests.
- Add `eu.esa.sar.insar.gpf.SBASInversionOp$Spi` to the GPF SPI service file.

Compute model: per-pixel inversion is embarrassingly parallel. Use
`computeTileStack` so that all output bands are produced for a tile in one
solve and the per-tile design matrix is built once. `A`, `A^T W` and
sparse-row layouts are precomputed in `initialize()`; only `W` (per pixel)
and `phi_obs` (per pixel) vary inside the tile loop.

## 7. Test plan

1. **Unit: design matrix** — given a synthetic network of 8 epochs / 18 pairs,
   verify `Network.buildDesignMatrix()` is M×(N-1), exactly two non-zero entries
   per row, rank `N-1`, and pinv recovers epochs from synthetic phases.
2. **Unit: solver** — Tikhonov solve on a near-singular system matches the
   closed-form analytical answer to 1e-10.
3. **Unit: closure** — synthetic triplet with injected unwrap error of 2π
   yields closure phase 2π (modulo) at that pair.
4. **Integration: synthetic stack** — generate 12 SAR epochs, deformation
   field = constant velocity + linear-in-elevation atmospheric noise, form
   pairs with `MultiMasterInSAROp`, run `SBASInversionOp`, assert recovered
   velocity within 1 mm/yr per pixel and recovered atmospheric residuals
   within 2 mm.
5. **Integration: real S1 IW stack** — 6 acquisitions over a quiet area
   (San Francisco Bay, 2020), compare against MintPy reference (commit checked
   into `sar-test-stacks`). Median absolute velocity difference should be
   < 0.5 mm/yr.

## 8. Dependencies

- jblas (already used by InterferogramOp / CoherenceOp) for `A^T W A`.
- Apache commons-math3 (already used by `ArcDataIntegration`) for SVD
  fallback and condition-number diagnostics.
- No new third-party libs.

## 9. Out of scope

- Unwrap-error correction itself (operator emits closure phase; bridging /
  triplet correction is a separate downstream operator).
- Atmospheric residual filtering — handled by the existing
  `EmpiricalTropoCorrectionOp` upstream and by future GACOS/ERA5 ops.
- Persistent / distributed scatterer detection — see Phase Linking spec.
- Re-unwrapping of pairs from inversion residuals.

## 10. References

- Berardino, P. et al. (2002). *A new algorithm for surface deformation
  monitoring based on small baseline differential SAR interferograms.* IEEE
  TGRS 40(11), 2375–2383.
- Pepe, A. & Lanari, R. (2006). *On the extension of the minimum cost flow
  algorithm for phase unwrapping of multitemporal differential SAR
  interferograms.* IEEE TGRS 44(9), 2374–2383.
- Yunjun, Z. et al. (2019). *Small baseline InSAR time series analysis:
  Unwrapping error correction and noise reduction* (MintPy). Computers &
  Geosciences 133.
- Doin, M.-P. et al. (2011). *Presentation of the Small BAseline NSBAS
  processing chain on a case example: the Etna deformation monitoring from
  2003 to 2010 using ENVISAT data.* Fringe.
