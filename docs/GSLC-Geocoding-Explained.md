# GSLC Terrain Correction, Explained

**A conceptual guide to the `GSLC-Terrain-Correction` operator (Geocoded Single Look Complex generation) in the SNAP Microwave Toolbox.**

*Audience:* users who want to understand **what the operator does, why it does it that way, and how to drive it** — not just which button to press. Read it start-to-finish, then follow the hands-on tutorial (`GSLC-Tutorial.md`) for a step-by-step run. For the button-level reference see the in-app help (`GSLCGeocodingOp.html`); for a runnable end-to-end walkthrough see the notebook `snap-nb-sar-gslc-insar`.

---

## 1. The problem it solves

Traditional SAR terrain correction (Range-Doppler) geocodes the **amplitude** and, in doing so, discards or scrambles the **phase**. But the phase of a complex SAR signal encodes the sensor-to-target distance — the very thing interferometry and polarimetry depend on. So the classical InSAR pipeline keeps everything in slant-range geometry through a long, mission-specific chain (Back-Geocoding → ESD → Interferogram → Deburst → Merge → Terrain-Correction) and geocodes only at the very end.

**GSLC (Geocoded Single Look Complex)** flips that around: it geocodes each acquisition to a map projection **up front, while preserving phase**. Every scene becomes a phase-preserving complex product on a **common map grid**, and the rest of the InSAR chain consumes those directly. The result:

- **Fewer steps** — no separate deburst, merge, or final terrain correction.
- **Independent, parallel** per-acquisition processing.
- **Consistent geocoding** across all dates (same DEM, same geometry) — so a pixel is the *same ground point* in every scene.
- Output that is **directly geocoded** and ready for stacking / time-series analysis.

This is the "geocode-first" architecture behind global-scale systems (Agram et al. 2022; OPERA-CSLC / NISAR ADT), now native in the SNAP Microwave Toolbox.

---

## 2. The core idea in one paragraph

For every pixel of the **output map grid**, use the precise orbit + a DEM to find where that ground point falls in the source SLC (its zero-Doppler azimuth time and slant range). Before resampling, **remove the geometric carrier phase** (4πR/λ) so the signal is smooth enough to interpolate cleanly; resample the complex I/Q with a high-fidelity interpolator; then **restore that same carrier phase** in the new map geometry. The output complex pixel therefore carries the correct sensor-to-target phase for its map location — so a `reference · conj(secondary)` product between two GSLCs yields exactly the differential-range phase (the `exp(−j·4π(R_reference − R_secondary)/λ)` term), with no coregistration chain in between.

---

## 3. How it works, step by step (as implemented)

This mirrors the operator's per-pixel geometry loop.

### 3.1 Precise geometry — where does this ground point live in the SLC?

For each output-grid pixel the operator computes its geographic coordinate (lat, lon, height) from the DEM, then runs a **zero-Doppler search** against precise orbit state vectors to find the corresponding azimuth time, and computes the **slant range** R from sensor to ground point. Optional geometry-level corrections (Solid Earth Tide) shift the ground point itself before this solve.

### 3.2 Phase flattening — carrier removal before resampling

The raw SLC phase spins rapidly (high fringe frequency from the 4πR/λ carrier). Interpolating that directly aliases. So the operator simulates the carrier phase

```
φ_sim = 4π · R / λ
```

and removes it from the complex signal. The SLC carrier is `exp(−j·φ_sim)`, so flattening **multiplies by** `exp(+j·φ_sim)` to cancel it: `C' = C · exp(+j·φ_sim)`. The flattened signal has low fringe frequency and resamples cleanly.

> **TOPS note.** For IW/EW data an additional azimuth deramp (from the antenna steering) is removed *first*, and the range carrier is pre-flattened on the deramped tile before resampling. The azimuth carrier is **not put back** by default (`outputAzimuthCarrier = false`): it is acquisition-specific — burst timing and FM rate — and does **not** cancel between two acquisitions, so restoring it injects a per-burst quadratic azimuth phase into every cross-acquisition interferogram (~150 rad ≈ 24 spurious fringes per burst measured on a real S1A+S1D pair; the effect is small for same-platform pairs with tight burst sync, which is why it can go unnoticed). Classical InSAR is immune because Back-Geocoding resamples the secondary onto the reference's burst grid; independent per-scene geocoding cannot do that, so the carrier must stay off — the same convention as OPERA CSLC. The carrier state is stamped in metadata (`gslc_azimuth_carrier`) and CreateStack matches it when auto-building a secondary; all legs of a stack must share one convention. Stripmap data with a residual Doppler centroid (e.g. ENVISAT ASAR IMS) still gets its sample-by-sample azimuth deramp/reramp — the stripmap f_dc carrier is orders of magnitude gentler, though a cross-acquisition f_dc difference is a known residual there too.
>
> One smooth residual remains in a carrier-free cross-acquisition interferogram: with the carrier restored, deramp-model errors cancel in the round trip; with it removed, the small annotation-vs-data mismatch stays in each leg, and its difference between two acquisitions is a slowly varying ramp (~1 fringe per 80 px measured on an S1A+S1D pair — Hz-scale Doppler-annotation differences). It is orbital-ramp-like and benign; `Interferogram`'s **`subtractResidualRamp`** option removes it robustly (low-order polynomial fitted to block-wise fringe gradients — too rigid to absorb localized deformation, but note it would absorb a genuine scene-wide linear gradient, hence off by default).

### 3.3 High-fidelity complex resampling

The flattened I and Q are resampled from the source slant-range grid onto the target map grid. The default interpolator is **BiSinc 5-point** — a truncated sinc, chosen because phase fidelity (not just amplitude) must survive resampling. Cheaper kernels (bilinear, cubic) are available but not recommended for InSAR.

### 3.4 Phase restoration — the InSAR-critical default

By default (**`outputFlattened = false`**) the operator **puts the carrier back** with the inverse multiply `C_geo = C'_resampled · exp(−j·φ_sim)`. This restores the absolute phase relative to the *new map geometry*, which is exactly what InSAR needs — the reference·conj(secondary) product then carries the differential-range phase `exp(−j·4π(R_reference − R_secondary)/λ)`.

If `outputFlattened = true`, the carrier is **not** restored: each pixel holds only the local scattering coefficient σ(P). That is useful for amplitude/scattering or single-date PolSAR work, but it makes the GSLC **unusable for InSAR** — the range-difference phase is zeroed. **Use one mode consistently across all dates in a stack**; mixing flattened and non-flattened bands produces a noise interferogram. (The operator stamps `gslc_output_flattened` into metadata so `CreateStack` can enforce a matching state on an auto-built secondary.)

> **Sign convention — a fixed bug worth flagging.** Flatten uses `exp(+jφ)` (to cancel the SLC's `exp(−jφ)` carrier) and restore uses the inverse `exp(−jφ)`. An earlier version used `exp(+jφ)` in **both** steps, doubling the carrier and corrupting the interferogram. The current code is correct (`multiplyByExpJPhi` to flatten, `multiplyByExpMinusJPhi` to restore) and pinned by `GSLCInSarGradeTest`.

### 3.5 Coregistration — folded into the grid, not a separate chain

Because every GSLC is always built on the **same standard map grid** (a fixed global lat/lon lattice anchored at 0,0), two overlapping scenes already share it: the same ground point lands at the same fractional pixel (modulo an integer offset). That is coregistration by construction. Any residual sub-pixel misregistration (clock bias, processor-time delta, orbit residual) is absorbed by two scalar offsets — **`rangeOffsetPixels`** and **`azimuthOffsetPixels`** — applied inside the geometric sampling.

The simplest workflow geocodes **only the reference** explicitly; `CreateStack` then reads the reference's `gslc_source_slc_path` stamp, cross-correlates against the raw secondary SLC to estimate the (Δr, Δa) bias, rebuilds the secondary GSLC with those offsets, and stacks the two. The user runs GSLC once, feeds raw SLC secondaries, and CreateStack handles the rest.

### 3.5b Output grid — square or rectangular cells

A map projection fixes the grid axes' **directions and units**, not the cell aspect ratio — the two sampling steps are independent. By default GSLC uses a square cell (equal degree steps, which is already mildly rectangular in ground metres away from the equator). Because SLC resolution is strongly anisotropic, the square default — the **coarser of the two native spacings**, `max(azimuthSpacing, rangeSpacing)` — discards sampling on the finer axis. For S1 IW (≈ 3.4 m ground range × ≈ 22 m azimuth, sampled at ≈ 3.4 × 14 m) that costs ~4× of the range sampling: fewer independent looks for coherence estimation and multilooking than classical radar-geometry InSAR keeps.

**Which axis is the fine one is mission-dependent — do not assume it is range.** S1 IW is range-fine / azimuth-coarse, but BIOMASS stripmap is the reverse (19.81 m slant-range vs 6.71 m azimuth spacing, so azimuth-fine / range-coarse), and its ground aspect ratio is *larger* than S1's. The rule above and the rectangular-cell criterion below are both symmetric in the two axes, so they hold either way; only the resulting orientation flips (`dy < dx` instead of `dx < dy`).

Setting the optional **Pixel Spacing North** (`pixelSpacingInMeterY`/`pixelSpacingInDegreeY`) decouples the axes. The limit on how coarse the north step may be is set by the orbit heading θ rotating the radar axes away from the map axes: the Nyquist-adequate steps are `dx ≤ 1/(B_rg·cosθ + B_az·sinθ)` and `dy ≤ 1/(B_rg·sinθ + B_az·cosθ)`. For S1 (|θ| ≈ 10–15°) that gives **≈ 3.4 m east × 7.5 m north** — near-full information preservation at ~4× fewer samples than a 3.4 m square grid. (Precedent: OPERA CSLC-S1 ships 5 × 10 m UTM cells.) `CreateStack`'s grid-lock and the lattice-alignment guard operate per axis, so rectangular stacks coregister exactly like square ones.

On **oversampling**: because the resampler interpolates band-limited complex data (deramped, range-pre-flattened) with sinc-family kernels, requesting a *finer output grid* **is** band-limited oversampling — mathematically equivalent to FFT zero-padding of the source. Use `oversamplingPercent` (or simply a finer explicit spacing) together with `BISINC_21_POINT` for very-high-resolution work such as point-target analysis; no separate source-domain oversampling stage is needed.

For lossless native-resolution cells (3.4 × 14 m with no heading penalty) the map axes themselves would have to align with the radar axes — i.e. a per-relative-orbit oblique projection (Hotine Oblique Mercator along the ground track). That remains a legitimate, EPSG-parameterizable map projection and a possible future option; rectangular cells on a standard CRS cover most of the benefit today.

### 3.6 Displacement-grade corrections (optional)

For sub-decimetre deformation work, two geometry/phase corrections are exposed:

| Correction | What it does | Status |
|-----------|--------------|--------|
| **Solid Earth Tide** (`applySolidEarthTide`) | ~10 cm body-tide displacement (IERS 2010 step-1, degree-2 Love numbers); shifts **both** rangeIndex and phase | Implemented |
| **Troposphere** (`applyTroposphericCorrection`) | Saastamoinen dry path delay (standard atmosphere); shifts **phase only** | Implemented |

SET and troposphere are negligible for coherence-only or amplitude work; they matter for displacement-grade InSAR.

**These are not the only corrections available, and they overlap with the others.** A GSLC InSAR chain has three independent places to apply corrections:

1. **ETAD** (`S1-ETAD-Correction`), applied to the **split SLC before geocoding** — Sentinel-1's auxiliary timing product supplies measured tropospheric and ionospheric range delay, geodetic effects, and bistatic/FM-mismatch azimuth shifts. Its `sumOfRangeCorrections` / `sumOfAzimuthCorrections` parameters default to **true**, so ETAD's default is the *total* correction; the seven individual layer switches (default false) exist to apply a subset.
2. **GSLC itself** — the two rows above.
3. **The interferogram domain** — the `IonosphericCorrection` operator (phase screens).

Because ETAD's tropospheric layer and GSLC's `applyTroposphericCorrection` model the **same** path delay, enabling both **double-counts** it; the same holds for ETAD's geodetic layers against `applySolidEarthTide`. Choose ETAD (measured) where an ETAD product exists, otherwise GSLC's computed model. ETAD must also be applied to **both** acquisitions or to neither — a differential timing correction goes straight into the interferometric phase.

#### Which corrections actually help — measured

An eight-configuration ablation was run on a Sentinel-1B IW1 pair (15 Aug × 08 Sep 2020, 24-day baseline, 2 bursts), each configuration processed end to end through GSLC → CreateStack → Interferogram with **`subtractResidualRamp=false`** so that ramp removal could not absorb the long-wavelength signal under test. Identical 1200 × 1200 window in every case.

| Configuration | Residues /10⁴ px | Δ vs baseline | 5×5 local coherence |
|---|---|---|---|
| Baseline, no corrections | 2554.6 | — | 0.2859 |
| **ETAD, summed range + azimuth** | **2411.6** | **−5.6 %** | **0.2931** |
| ETAD, azimuth layers only | 2477.4 | −3.0 % | 0.2879 |
| ETAD, tropospheric only | 2487.4 | −2.6 % | 0.2879 |
| ETAD, ionospheric only | 2509.2 | −1.8 % | 0.2857 |
| ETAD, geodetic range only | 2511.1 | −1.7 % | 0.2856 |
| GSLC `applySolidEarthTide` | 2511.0 | −1.7 % | 0.2856 |
| GSLC `applyTroposphericCorrection` | 2535.5 | −0.7 % | **0.2714** |

Four things worth taking from this:

1. **Full ETAD is the best configuration** and beats every individual layer. The layer contributions are roughly additive (−3.0 % azimuth + −2.6 % tropospheric ≈ −5.6 % combined), consistent with independent error terms.
2. **Ionospheric correction is marginal at C-band** (−1.8 %), as theory predicts: TEC phase scales as 1/f, so this term only becomes first-order at P-band (BIOMASS, ~12× larger).
3. **GSLC's built-in Saastamoinen tropospheric model made results worse** — the only configuration to reduce coherence (0.2859 → 0.2714 local, 0.2850 → 0.2258 mean). A standard-atmosphere model applied independently to each acquisition injects more error than it removes over a 24-day pair. **Prefer ETAD's measured correction wherever an ETAD product exists.**
4. **`applySolidEarthTide` and ETAD's geodetic-range layer agree to 0.1 residues** (2511.0 vs 2511.1) — two independent implementations of the same physical effect landing on the same answer, which is both a useful cross-check and a concrete illustration of the double-counting overlap noted above.

> **Scope of these numbers.** Every configuration above ran in ETAD's *resampling* mode with `outputPhaseCorrections=false`, so the gains measure the **geometric** contribution only — better geolocation, hence better coregistration. The atmospheric **phase** was not removed. Resampling relocates a pixel but leaves the −4πΔr/λ that the delay put in its phase, and `InterferogramOp`'s ETAD phase handling runs only on the classical slant-range paths, never on the GSLC path. Enabling `outputPhaseCorrections` now removes the range-delay phase from the complex samples before geocoding, which is the correct route for a geocode-first chain; the figures above are therefore a **lower bound** on what ETAD contributes.

Full experimental design in `docs/superpowers/plans/2026-07-27-cross-toolbox-validation.md` §B4.

---

## 4. Where it sits in the pipeline

```
Traditional TOPS InSAR                 GSLC-based InSAR
──────────────────────                 ────────────────────────
Apply-Orbit-File                       Apply-Orbit-File
TOPSAR-Split                           TOPSAR-Split
Back-Geocoding (coregister)      →     GSLC-Terrain-Correction (reference)
Enhanced-Spectral-Diversity      →     CreateStack (auto-coregisters secondary)
Interferogram                          Interferogram
TOPSAR-Deburst                   →     —
TOPSAR-Merge                     →     —
Terrain-Correction               →     —   (already geocoded)
                                       ↓
                                 Goldstein filter → Phase-Unwrapping → …
```

The GSLC approach collapses steps 3–4 and 6–8 of the traditional chain. Each acquisition is geocoded once, independently, on a shared grid.

---

## 5. Parameter tuning — a practical guide

| Parameter | Default | Notes / when to change |
|-----------|---------|------------------------|
| **DEM** | Copernicus 30m Global | Auto-downloaded; supply an external DEM for local high-res work |
| **Image Resampling** | BiSinc 5-point | Keep a sinc kernel for InSAR; higher orders (11/21) trade speed for fidelity |
| **DEM Resampling** | Bilinear | Bilinear is fine for most terrain; BiSinc for steep relief |
| **Pixel Spacing (m / deg)** | 0 (auto) | 0 → derived from source; set explicitly to fix an output resolution across a stack. Becomes the **east** step when a north spacing is also set |
| **Pixel Spacing North (m / deg)** | 0 (square) | Optional **rectangular cells** preserving the SLC's anisotropic resolution — S1 IW: ≈ 3.4 m east × 7.5 m north (see §3.5b). Leave 0 for square |
| **Oversampling (%)** | 0 | 20% → 20% finer pixels; use to reduce interpolation loss on high-relief scenes. A finer grid through the sinc kernel *is* band-limited oversampling (§3.5b) |
| **Map Projection** | WGS84(DD) | Any WKT CRS; use a metric projection (UTM) if you need metre grids |
| **Output phase-flattened** | **false** | **Leave false for InSAR.** True only for amplitude/PolSAR single-date use |
| **Restore TOPS azimuth carrier** | **false** | **Leave false.** Acquisition-specific; does not cancel between acquisitions — restoring it corrupts cross-acquisition InSAR (~tens of spurious fringes per burst) |
| **Range/Azimuth offset (px)** | 0 | Sub-pixel secondary alignment; **CreateStack sets these automatically** |
| **Apply SET / Tropo** | false | Turn on SET + Tropo for displacement-grade InSAR |
| **Mask out no-elevation areas** | true | Skips sea/no-DEM pixels; speeds processing |

**First-run recipe:** defaults, `outputFlattened=false`. Geocode the reference; feed raw SLC secondaries to `CreateStack`; on the `Interferogram` step enable `subtractFlatEarthPhase`, `subtractTopographicPhase` **and `subtractResidualRamp`** (removes the cross-acquisition annotation-mismatch ramp — the GSLC-domain analogue of ESD; see the TOPS note in §3.2). Inspect the interferogram coherence; enable SET+tropo only when chasing sub-decimetre displacement. Judge phase quality **after** filtering or multilooking — a correct single-look interferogram at γ ≈ 0.2 looks like noise on screen.

---

## 6. Supported inputs (and what is rejected)

| Product | Supported | Note |
|---------|-----------|------|
| Stripmap SLC (S-1 SM, ENVISAT ASAR IMS) | Yes | Direct input after orbit application |
| IW/EW SLC, **split** (single subswath) | Yes | Via `TOPSAR-Split`; operator handles deramp + burst mosaicking internally |
| IW/EW SLC, **debursted** | **No** | Deburst strips burst metadata needed for azimuth deramp — use the split product |
| GRD | **Not usable** | Amplitude-only — no phase. Not explicitly blocked by the operator, but produces a degenerate (phase-less) product, so don't feed GRD to GSLC. |

For full IW coverage, process each subswath (IW1/IW2/IW3) independently and mosaic the GSLCs (or the interferograms) per date.

---

## 7. Validation evidence

InSAR-grade correctness is pinned by an executable spec, `GSLCInSarGradeTest.java` (14 `@Test` methods, built on the Capella SM SLC fixture), plus a TOPS test family (`GSLCTops*Test`, `GSLCVs*ComparisonTest`). Each hardening fix is anchored to a test:

- **Sign convention** — flatten `exp(+jφ)` / restore `exp(−jφ)` (fixes the doubled-carrier bug).
- **TOPS range pre-flatten + analytic reramp** — removes burst-edge aliasing and phase bias.
- **Layover/shadow classification** and **no-source masking**.
- **Solid Earth Tide** math contract vs. reference, and an **identical-pair → zero-phase interferogram** test.
- **Two-burst S-1 IW** exercised in the InSAR-readiness pyramid (synthetic 2-burst geometry in the TOPS unit tests).

**Head-to-head against the traditional pipeline (S1A + S1D cross-platform pair, Venezuela, 2-burst IW3 fixture):** with carrier-free output and `subtractResidualRamp`, the GSLC interferogram's phase-only self-coherence matches the classical (Back-Geocoding) interferogram **to three decimals at every estimation window tested** (5–80 px; e.g. win40: 0.0226 vs 0.0223, win80: 0.0130 vs 0.0128, same block, same statistic), and the two interferograms cross-agree at the level expected from their independent resampling noise. Three defects had to fall to get there — a dropped stacking offset, cross-platform lattice mismatch, and the non-cancelling TOPS azimuth carrier — each now pinned by a regression test (`GSLCStackOffsetProbeTest`, lattice tests, `GSLCCarrierResidualTest`).

**Correction-layer ablation (S1B IW1, 15 Aug × 08 Sep 2020):** eight configurations measured end to end. Full ETAD improves unwrappability by **−5.6 % phase residues** and local phase coherence by **+2.5 %**; GSLC's built-in Saastamoinen tropospheric model is the only setting that makes the interferogram *worse*. Table and interpretation in §3.6.

**Second mission — BIOMASS (P-band, stripmap, left-looking, 3-day pair):** the geocode-first chain runs end to end on a completely different mission. Both legs geocoded independently landed on **one lattice — identical grid step and a whole-pixel origin offset of exactly −71.000000 × −15.000000 px (to 1e-13)** — and the resulting interferogram is 81.7 % valid at **mean coherence 0.528**. This matters because the standard-grid snapping was designed and debugged entirely against Sentinel-1 to fix the cross-platform lattice defect; that it transfers exactly to a different band, geometry, pixel aspect and look direction is evidence the fix was general rather than tuned. Two BIOMASS-specific findings: the square-cell default picks the **coarser** axis, so the native 6.709 m azimuth sampling is coarsened 2.95× to 19.814 m (use rectangular cells — the fine axis is azimuth here, the reverse of S1 IW); and the stripmap Doppler-centroid residual is negligible at P-band (|f_dc| ≤ 0.05 Hz across the swath) where the TOPS azimuth carrier dominates at C-band.

**Known limitations and roadmap:**

- A few **end-to-end integration tests are DEM-gated** (skipped without SRTM/Copernicus availability).
- Full-scene validation is on **10-burst IW subswaths**; a multi-subswath slice and long-stack (SBAS/PSI) validation are the natural next steps.
- The residual-ramp estimator is global per pair; per-burst refinement is possible if long-stack work shows a need.

---

## 8. Frequently asked questions

- **"Is GSLC just terrain correction on complex data?"** No — it is a geocode-first *architecture*. It preserves phase and builds every scene on a shared grid, so coregistration and the deburst/merge/TC chain collapse into it.
- **"Why might my GSLC interferogram be noisier than expected?"** Most likely candidates, in order: (1) `outputFlattened` mismatched between reference and secondary; (2) burst-edge / nodata effects when processing many bursts; (3) residual sub-pixel misregistration not captured by the scalar offsets; (4) DEM quality, or SET + troposphere left off for a displacement scene.
- **"Does it work with and without ETAD?"** Yes. ETAD refines the geometry further but is not required.
- **"ERS / ENVISAT?"** Stripmap ASAR IMS is a direct, supported input — the operator has dedicated ENVISAT Doppler-deramp code (the InSAR-grade test fixture is Capella Stripmap; there is no ENVISAT/S-1 SM end-to-end test yet).
- **"How is the output grid sized?"** From pixel spacing (m or deg) × oversampling, on the standard lattice anchored at the grid origin; set pixel spacing explicitly to lock resolution across a stack.

---

## 9. References

- Agram, P.S., Warren, M.S., Calef, M.T., Arko, S.A. (2022). *An Efficient Global Scale Sentinel-1 Radar Backscatter and Interferometric Processing System.* Preprints.org, doi:10.20944/preprints202206.0252.v1.
- OPERA-CSLC / ISCE-framework: geocoded-SLC reference implementation used by the NISAR ADT.
- IERS Conventions (2010) — Solid Earth Tide displacement model.

---

*Companion documents:* in-app help `GSLCGeocodingOp.html` (parameter reference) · `snap-nb-sar-gslc-insar` (runnable tutorial) · `GSLCInSarGradeTest.java` (executable correctness spec).
