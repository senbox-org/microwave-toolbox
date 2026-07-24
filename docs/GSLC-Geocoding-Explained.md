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

For every pixel of the **output map grid**, use the precise orbit + a DEM to find where that ground point falls in the source SLC (its zero-Doppler azimuth time and slant range). Before resampling, **remove the geometric carrier phase** (4πR/λ) so the signal is smooth enough to interpolate cleanly; resample the complex I/Q with a high-fidelity interpolator; then **restore that same carrier phase** in the new map geometry. The output complex pixel therefore carries the correct sensor-to-target phase for its map location — so a `master · conj(slave)` product between two GSLCs yields exactly the differential-range phase (the `exp(−j·4π(R_master − R_slave)/λ)` term), with no coregistration chain in between.

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

> **TOPS note.** For IW/EW data an additional azimuth deramp (from the antenna steering) is removed *first*, and the range carrier is pre-flattened on the deramped tile before resampling; the azimuth reramp phase is then re-applied **analytically at the fractional source position** rather than interpolated — this is what prevents burst-edge phase bias. Stripmap data with a residual Doppler centroid (e.g. ENVISAT ASAR IMS) gets an analogous sample-by-sample azimuth deramp/reramp.

### 3.3 High-fidelity complex resampling

The flattened I and Q are resampled from the source slant-range grid onto the target map grid. The default interpolator is **BiSinc 5-point** — a truncated sinc, chosen because phase fidelity (not just amplitude) must survive resampling. Cheaper kernels (bilinear, cubic) are available but not recommended for InSAR.

### 3.4 Phase restoration — the InSAR-critical default

By default (**`outputFlattened = false`**) the operator **puts the carrier back** with the inverse multiply `C_geo = C'_resampled · exp(−j·φ_sim)`. This restores the absolute phase relative to the *new map geometry*, which is exactly what InSAR needs — the master·conj(slave) product then carries the differential-range phase `exp(−j·4π(R_master − R_slave)/λ)`.

If `outputFlattened = true`, the carrier is **not** restored: each pixel holds only the local scattering coefficient σ(P). That is useful for amplitude/scattering or single-date PolSAR work, but it makes the GSLC **unusable for InSAR** — the range-difference phase is zeroed. **Use one mode consistently across all dates in a stack**; mixing flattened and non-flattened bands produces a noise interferogram. (The operator stamps `gslc_output_flattened` into metadata so `CreateStack` can enforce a matching state on an auto-built slave.)

> **Sign convention — a fixed bug worth flagging.** Flatten uses `exp(+jφ)` (to cancel the SLC's `exp(−jφ)` carrier) and restore uses the inverse `exp(−jφ)`. An earlier version used `exp(+jφ)` in **both** steps, doubling the carrier and corrupting the interferogram. The current code is correct (`multiplyByExpJPhi` to flatten, `multiplyByExpMinusJPhi` to restore) and pinned by `GSLCInSarGradeTest`.

### 3.5 Coregistration — folded into the grid, not a separate chain

Because every GSLC is built on the **same standard map grid** (`alignToStandardGrid = true`, default), two overlapping scenes already share a global lat/lon lattice: the same ground point lands at the same fractional pixel (modulo an integer offset). That is coregistration by construction. Any residual sub-pixel misregistration (clock bias, processor-time delta, orbit residual) is absorbed by two scalar offsets — **`rangeOffsetPixels`** and **`azimuthOffsetPixels`** — applied inside the geometric sampling.

The simplest workflow geocodes **only the master** explicitly; `CreateStack` then reads the master's `gslc_source_slc_path` stamp, cross-correlates against the raw slave SLC to estimate the (Δr, Δa) bias, rebuilds the slave GSLC with those offsets, and stacks the two. The user runs GSLC once, feeds raw SLC slaves, and CreateStack handles the rest.

### 3.6 Displacement-grade corrections (optional)

For sub-decimetre deformation work, three geometry/phase corrections are exposed:

| Correction | What it does | Status |
|-----------|--------------|--------|
| **Solid Earth Tide** (`applySolidEarthTide`) | ~10 cm body-tide displacement (IERS 2010 step-1, degree-2 Love numbers); shifts **both** rangeIndex and phase | Implemented |
| **Troposphere** (`applyTroposphericCorrection`) | Saastamoinen dry path delay (standard atmosphere); shifts **phase only** | Implemented |
| **Ionosphere** (`applyIonosphericCorrection`) | L-band TEC path delay (NISAR, ALOS-2/4) | **Stub** — logs a warning; full split-spectrum/TEC pending |

SET and troposphere are negligible for coherence-only or amplitude work; they matter for displacement-grade InSAR. Ionosphere has no effect at C-/X-band.

---

## 4. Where it sits in the pipeline

```
Traditional TOPS InSAR                 GSLC-based InSAR
──────────────────────                 ────────────────────────
Apply-Orbit-File                       Apply-Orbit-File
TOPSAR-Split                           TOPSAR-Split
Back-Geocoding (coregister)      →     GSLC-Terrain-Correction (master)
Enhanced-Spectral-Diversity      →     CreateStack (auto-coregisters slave)
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
| **Pixel Spacing (m / deg)** | 0 (auto) | 0 → derived from source; set explicitly to fix an output resolution across a stack |
| **Oversampling (%)** | 0 | 20% → 20% finer pixels; use to reduce interpolation loss on high-relief scenes |
| **Map Projection** | WGS84(DD) | Any WKT CRS; use a metric projection (UTM) if you need metre grids |
| **Align to Standard Grid** | **true** | **Leave on for stacking** — guarantees a shared lattice across scenes. Off only for a one-off single scene |
| **Standard Grid Origin X/Y** | 0, 0 | Anchor of the shared lattice; leave default unless aligning to an external grid |
| **Output phase-flattened** | **false** | **Leave false for InSAR.** True only for amplitude/PolSAR single-date use |
| **Range/Azimuth offset (px)** | 0 | Sub-pixel slave alignment; **CreateStack sets these automatically** |
| **Apply SET / Tropo / Iono** | false | Turn on SET + Tropo for displacement-grade InSAR; Iono is an L-band stub today |
| **Mask out no-elevation areas** | true | Skips sea/no-DEM pixels; speeds processing |

**First-run recipe:** defaults, `outputFlattened=false`, `alignToStandardGrid=true`. Geocode the master; feed raw SLC slaves to `CreateStack`. Inspect the interferogram coherence; enable SET+tropo only when chasing sub-decimetre displacement.

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

InSAR-grade correctness is pinned by an executable spec, `GSLCInSarGradeTest.java` (15 `@Test` methods, built on the Capella SM SLC fixture), plus a TOPS test family (`GSLCTops*Test`, `GSLCVs*ComparisonTest`). Each hardening fix is anchored to a test:

- **Sign convention** — flatten `exp(+jφ)` / restore `exp(−jφ)` (fixes the doubled-carrier bug).
- **TOPS range pre-flatten + analytic reramp** — removes burst-edge aliasing and phase bias.
- **Layover/shadow classification** and **no-source masking**.
- **Solid Earth Tide** math contract vs. reference, and an **identical-pair → zero-phase interferogram** test.
- **Two-burst S-1 IW** exercised in the InSAR-readiness pyramid (synthetic 2-burst geometry in the TOPS unit tests).

**Known limitations and roadmap:**

- **Full ionospheric correction** is a stub (split-spectrum / GIM-TEC pending).
- A few **end-to-end integration tests are DEM-gated** (skipped without SRTM/Copernicus availability).
- Validation to date is on **2 bursts**; extending to **6 bursts / a full slice** and a **quantified coherence comparison against the traditional pipeline** is the natural next validation step.

---

## 8. Frequently asked questions

- **"Is GSLC just terrain correction on complex data?"** No — it is a geocode-first *architecture*. It preserves phase and builds every scene on a shared grid, so coregistration and the deburst/merge/TC chain collapse into it.
- **"Why might my GSLC interferogram be noisier than expected?"** Most likely candidates, in order: (1) `outputFlattened` mismatched between master and slave; (2) burst-edge / nodata effects when processing many bursts; (3) residual sub-pixel misregistration not captured by the scalar offsets; (4) DEM quality, or SET + troposphere left off for a displacement scene.
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
