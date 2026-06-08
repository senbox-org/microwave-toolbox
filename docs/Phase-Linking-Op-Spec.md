# Phase Linking Operator (DS InSAR) — Spec

**Status:** proposed
**Owner:** sar-op-insar
**Companion to:** the SBAS Inversion spec (this op feeds it).

## 1. Motivation

Persistent-Scatterer (PS) workflows ignore most of the scene because most
pixels are not point-like. Distributed-Scatterer (DS) processing
*phase-links* a stack of N coregistered SLCs through a covariance-based
estimator (SqueeSAR / EVD / EMI) and recovers a single, consistent phase
history per ground pixel from all N(N-1)/2 ifg phases jointly.

This is the algorithm at the heart of every modern open-source DS-InSAR
tool — FRInGE, MiaplPy, dolphin (NISAR ADT), and the EGMS pipeline. It is
the single most missing capability in `sar-op-insar`.

This spec defines a `PhaseLinkingOp` that takes a coregistered SLC stack
and emits a stack of "linked" wrapped phases (single-master, all
acquisitions vs. one reference) plus a temporal-coherence quality band.
The output is drop-in input to the existing `MultiMasterInSAROp` /
`InterferogramOp` / proposed `SBASInversionOp` chain.

## 2. Algorithm

For each target pixel `p`:

### 2.1 SHP (Statistically Homogeneous Pixel) selection

Select a set of neighbours `Omega(p)` inside a search window (default
21×7) that share the same amplitude distribution as `p`. Test options
(parameterized; default KS):

- **KS test** (Ferretti, SqueeSAR 2011): two-sample Kolmogorov–Smirnov on
  amplitude time series, significance `alpha = 0.05`.
- **AD test** (Anderson–Darling): more powerful in the tails, used by
  FRInGE.
- **t-test on log-amplitude**: cheap, MiaplPy default in fast mode.

Output for the pixel: an integer count `N_shp = |Omega(p)|` and an
implicit boolean mask. Pixels with `N_shp < shpMin` (default 20) are
flagged DS-invalid and pass through unchanged (to be picked up later by a
PS detector if any).

### 2.2 Sample covariance matrix

Form the `N×N` complex sample covariance from SHPs:

```
C_hat[i, j] = (1 / N_shp) * sum_{q in Omega(p)} s_i(q) * conj(s_j(q))     (eq. 1)
```

where `s_i(q)` is the (already coregistered, deramped) complex SLC sample
at acquisition `i`, pixel `q`. Normalise to a coherence matrix:

```
T_hat[i, j] = C_hat[i, j] / sqrt(C_hat[i, i] * C_hat[j, j])                (eq. 2)
```

|T_hat[i,j]| is the per-pair coherence; arg(T_hat[i,j]) is the per-pair
ifg phase, both already multilooked over the SHP set.

#### 2.2.1 Coherence-magnitude bias correction (optional)

The sample coherence magnitude is positively biased, badly so at low
coherence / few looks (`E[|gamma_hat|^2] ~ gamma^2 + (1-gamma^2)/L`). When
`coherenceBiasCorrection` is enabled, each off-diagonal magnitude is debiased
with the first-order magnitude-squared-coherence correction (subtract the
`1/L` noise floor and rescale, `L = N_shp`):

```
|gamma|^2_corrected = max(0, (L * |T_hat[i,j]|^2 - 1) / (L - 1))            (eq. 2b)
```

The phase `arg(T_hat)` is preserved; only magnitudes shrink toward their
unbiased value. **Default off.** It is beneficial for EVD (it down-weights
noisy low-coherence pairs) but can degrade EMI at low coherence / few looks,
since EMI inverts the magnitude matrix and the correction adds variance
there. Note also that this per-element shrinkage is not guaranteed to
preserve PSD (the estimators only need a Hermitian matrix); a PSD-preserving,
variance-controlled debias (regularized/tapered shrinkage, e.g. TABASCO) is
future work.

### 2.3 Phase estimation

Two estimator choices, parameterized by `estimator`:

#### 2.3.1 EVD (Eigenvalue Decomposition) — default fast path

`T_hat` is Hermitian PSD; its dominant eigenvector `u` (largest eigenvalue
`lambda_1`) is the ML estimate of the per-epoch complex phasors under a
unit-norm rank-1 model:

```
u = arg max_v (v^H T_hat v) s.t. ||v|| = 1                                 (eq. 3)
phi_n = arg(u_n) - arg(u_ref)                                              (eq. 4)
```

`u_ref` is the entry corresponding to the chosen reference acquisition (so
`phi_ref = 0`). Cost: one Hermitian eigendecomposition per pixel,
O(N^3) — for typical N=30..200 stacks this is fine.

#### 2.3.2 EMI (Eigenvalue-based Maximum-likelihood-estimator of
       Interferometric phase)

Ansari, De Zan, Bamler 2018. Better unbiasedness and lower variance than
EVD when coherence is low; same complexity. The estimate is the eigenvector
of the **smallest** eigenvalue of the Hermitian matrix

```
M = Gamma^{-1}  ⊙  T_hat ,     Gamma = |T_hat|                              (eq. 5)
```

where `Gamma^{-1}` is the (real, symmetric) **matrix inverse** of the
coherence-magnitude matrix and `⊙` is the elementwise (Hadamard) product
with the complex coherence `T_hat`. The inverse-magnitude weighting
down-weights low-coherence (long-baseline) pairs — exactly where EVD's
equal weighting of noisy phases hurts — which is the source of EMI's
advantage. Implementation: invert `Gamma` (pseudo-inverse via the Hermitian
solver if near-singular), form `M`, and read the phase from the
smallest-eigenvalue eigenvector returned by the same solver as EVD.

(Note: `Gamma^{-1}` is the matrix inverse, **not** the elementwise
reciprocal `|T_hat| .^ -1`; an earlier draft of this spec and the first
implementation used the latter, which does not recover the phase for any
non-rank-1 coherence matrix. Both are corrected in
`phaselinking/EMIEstimator.java`.)

#### 2.3.3 (Optional, future) CRLB-MLE / sequential PL

Iterative refinement of EVD/EMI starting point. Out of scope for v1; leave
hooks in `PhaseEstimator` interface.

### 2.4 Temporal (Goodness-of-fit) coherence

Quality metric per pixel (Pepe & Lanari):

```
gamma_T = (2 / (N(N-1))) * | sum_{i<j} exp(j (arg(T_hat[i,j]) - (phi_i - phi_j))) |
                                                                            (eq. 6)
```

`arg(T_hat[i,j]) = atan2(Im(T_hat[i,j]), Re(T_hat[i,j]))`. The signal-flow
convention is `T_hat[i,j] = E[s_i * conj(s_j)]`, so under a rank-1 model
`arg(T_hat[i,j]) = phi_i - phi_j`; the residual zeroes when the model is
correct. (Earlier drafts of this spec wrote `(phi_j - phi_i)` and dropped
the `arg()`; both are corrected in the implementation in
`phaselinking/TemporalCoherence.java`.)

Range [0, 1]. Threshold (`tempCohMin`, default 0.6) decides which pixels
are passed into downstream processing.

### 2.5 Output

For each acquisition `n` (including the reference), write back into the SLC
stack a "linked" complex sample whose amplitude is the original `|s_n(p)|`
and whose phase is the estimated `phi_n(p)`:

```
s_n_linked(p) = |s_n(p)| * exp(j * phi_n(p))                                (eq. 7)
```

The phase-linking solution is defined only up to a global phase (the
estimator eigenvector has an arbitrary global rotation); the operator fixes
that gauge by setting the reference epoch as the **zero-phase datum**,
`phi_ref(p) = 0`. The reference band is therefore written as a real-valued
sample `|s_ref(p)| * exp(j·0)` — its original absolute SLC phase (the
arbitrary per-pixel propagation/topographic phase) is discarded, not
preserved. This is the single-reference convention used by dolphin /
MiaplPy / FRInGE.

Choosing the datum this way is downstream-invariant: every consumer
(`InterferogramOp` / `CoherenceOp` / `MultiMasterInSAROp` /
`SBASInversionOp`, plus SNAPHU/StaMPS export and phase-to-height) uses only
relative phases `arg(s_i · conj(s_j))`, in which the global datum cancels.
A linked pixel and a pass-through pixel (which keeps the *original* complex
sample at every epoch, reference included) therefore both yield correct
interferograms for every pair.

This makes the output a **drop-in replacement** for the input stack: any
existing `InterferogramOp` / `CoherenceOp` / `MultiMasterInSAROp` /
`SBASInversionOp` graph will produce phase-linked DS interferograms
without code changes.

## 3. Inputs

A coregistered SLC stack product (output of TOPS `BackGeocodingOp` +
`SpectralDiversityOp`, or non-TOPS `DEMAssistedCoregistrationOp` +
`CreateStackOp`). Bands must be `i_<date>` / `q_<date>` pairs with
`Unit.REAL` / `Unit.IMAGINARY`.

## 4. Parameters

| name | type | default | notes |
|---|---|---|---|
| `windowAzimuth` | int | 21 | SHP search window height |
| `windowRange` | int | 7 | SHP search window width |
| `shpTest` | "KS"/"AD"/"tlog" | "KS" | |
| `shpAlpha` | double | 0.05 | KS/AD significance level |
| `shpMin` | int | 20 | min SHPs to attempt phase linking |
| `estimator` | "EVD"/"EMI" | "EVD" | |
| `referenceEpochDate` | string | median epoch | empty => median chronological epoch (matches SBASInversionOp; well-conditioned phase datum) |
| `tempCohMin` | double | 0.6 | mask output below this |
| `coherenceBiasCorrection` | boolean | false | debias sample coherence magnitude (eq. 2b); aids EVD, can hurt EMI |
| `outputTempCoherence` | boolean | true | extra band |
| `outputShpCount` | boolean | false | diagnostic band |
| `chunkSize` | int | 256 | tile-side hint; balanced against window padding |

## 5. Outputs

- All `i_<date>` / `q_<date>` bands of the input stack, replaced with
  phase-linked values per eq. (7).
- `tempCoherence` (`Unit.COHERENCE`, scaled to [0,1]).
- (optional) `shpCount` (int).

## 6. File layout

- `sar-op-insar/src/main/java/eu/esa/sar/insar/gpf/PhaseLinkingOp.java`.
- `sar-op-insar/src/main/java/eu/esa/sar/insar/gpf/phaselinking/SHPSelector.java`
  (KS / AD / t-test impls behind a small interface).
- `sar-op-insar/src/main/java/eu/esa/sar/insar/gpf/phaselinking/PhaseEstimator.java`
  (EVD, EMI; future PL_iterative).
- `sar-op-insar/src/main/java/eu/esa/sar/insar/gpf/phaselinking/HermitianEigSolver.java`
  (thin wrapper over jblas / commons-math3).
- `sar-op-insar/src/test/java/eu/esa/sar/insar/gpf/TestPhaseLinkingOp.java`.
- Register `eu.esa.sar.insar.gpf.PhaseLinkingOp$Spi` in the GPF SPI file.

## 7. Tile / memory model

Per-pixel computation needs all `N` SLCs over a `windowAzimuth × windowRange`
neighbourhood. Strategy:

1. `computeTileStack` with **expanded source rectangle**: pad target tile
   by `(windowAzimuth/2, windowRange/2)` on each side; reject windows
   that would step outside the scene at the edges.
2. For each target pixel, build the SHP mask (one pass over the window
   amplitude vectors), then accumulate `C_hat` (one pass), then solve.
3. Eigendecompositions are O(N^3); cache nothing per pixel — run them
   inline. For N up to ~300 the O(N^3) cost is dominated by SHP selection
   (which is O(window × N) for KS test). Profile-tune `chunkSize` to keep
   the working set in L2 cache.
4. Multi-thread per output tile (SNAP GPF default); within a tile, single
   threaded — eigensolver is not thread-safe across calls.

## 8. Test plan

1. **Unit: SHP selectors** — synthetic Rayleigh amplitudes with two
   underlying populations, verify KS/AD reject the non-matching cluster
   at `alpha=0.05` with > 95 % power.
2. **Unit: EVD/EMI** — generate a known coherence matrix
   `T = u u^H * gamma + (1-gamma) I` with chosen `u` and
   `gamma = 0.5/0.7/0.9`; check recovered eigenphase agrees with ground
   truth within `1/(2*N*sqrt(gamma))` (Cramér–Rao bound).
3. **Unit: temp coherence** — for a perfectly rank-1 `T`, `gamma_T = 1`;
   for random noise, `gamma_T = O(1/N)`.
4. **Integration: synthetic DS stack** — 30 SAR epochs over a flat scene,
   inject a slow ramp deformation + multiplicative speckle, run
   `PhaseLinkingOp` then `MultiMasterInSAROp` then proposed
   `SBASInversionOp`. Assert recovered velocity within 0.5 mm/yr per
   pixel; assert temp coherence > 0.85 over the simulated DS area.
5. **Integration: real S1 IW stack** — 30 acquisitions over a vegetated
   area (Po valley, 2018-2019), compare phase-linked ifgs to MiaplPy
   reference on the same stack. Median absolute per-pair phase difference
   < 0.1 rad.
6. **Integration: PS pixels pass-through** — pixels marked PS (`N_shp <
   shpMin`) must come through unchanged so a downstream PS detector
   still sees their original speckle statistics.

## 9. Dependencies

- jblas: complex matrix ops (already used).
- commons-math3: KS / AD test statistics, Hermitian eigensolver (fallback
  to `EigenDecomposition` from commons-math3 if jblas Hermitian path is
  unavailable).
- No new third-party libs.

## 10. Out of scope

- **Sequential / ministack phase linking (Dolphin / FRInGE style).**
  Production pipelines split long stacks (`N > 100`) into overlapping
  ministacks with compressed SLCs to keep numerical conditioning and
  temporal decorrelation under control. v1 of `PhaseLinkingOp` is
  single-shot full-stack; recommended operating range is `N ≤ ~50`.
  v2 will add a ministack mode behind the same `PhaseEstimator`
  interface.
- **Iterative MLE / CRLB refinement (Ferretti SqueeSAR 2011 inner loop,
  Ansari 2017).** Slightly better phase estimates at low coherence
  for a 30 % cost increase. v2.
- **TOPS burst-by-burst processing.** v1 rejects burst-organized TOPS
  input (`InputProductValidator.isDebursted()` check); users must run
  `TOPSAR-Deburst` first. v2 could add burst-wise SHP selection mirror-
  ing the `InterferogramOp` per-burst pattern.
- **Persistent-scatterer detection.** A separate `PSCandidateOp`
  reusing the amplitude-dispersion statistics cached here is planned.
- **Adaptive multi-looking (NLSAR-AML, AMSTer).** The SHP window here is
  fixed.
- **Re-coregistration based on linked phase.** The op assumes upstream
  `BackGeocodingOp` + `SpectralDiversityOp` are already converged.

## 11. References

- Ferretti, A. et al. (2011). *A new algorithm for processing
  interferometric data-stacks: SqueeSAR.* IEEE TGRS 49(9), 3460–3470.
- Ansari, H., De Zan, F., Bamler, R. (2018). *Efficient phase estimation
  for interferogram stacks.* IEEE TGRS 56(7), 4109–4125.  (EMI)
- Cao, N., Lee, H., Jung, H.-C. (2016). *Mathematical framework for
  phase-triangulation algorithms in distributed-scatterer interferometry.*
  IEEE GRSL 12(9), 1838–1842.
- Fornaro, G., Verde, S., Reale, D., Pauciullo, A. (2015). *CAESAR: An
  approach based on covariance matrix decomposition to improve multibaseline
  multitemporal interferometric SAR processing.* IEEE TGRS 53(4),
  2050–2065.
- Mirzaee, S., Amelung, F., Fattahi, H. (2023). *Non-linear phase linking
  using joined distributed and persistent scatterers.* Computers &
  Geosciences 171.  (MiaplPy)
- ISCE-framework / dolphin: https://github.com/isce-framework/dolphin —
  reference C++/Python phase-linking implementation used by NISAR ADT.
