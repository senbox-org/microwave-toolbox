# GSLC versus classical InSAR: Venezuela S1 IW TOPS, first campaign run

**Update 2026-09-21: the failures in sections 1-3 and 11 were caused by an inverted sign in `InterferogramOp` and are fixed (section 12). The results below are the AS-SHIPPED (legacy sign) runs, kept as recorded; the corrected results are in section 12 and in the tier table below.**

Run of 2026-09-20/21 (executed inline, unattended overnight). Every number below is a recorded row in `E:\Output\parity\results\*.json`; the full generated scorecard is appended unedited. Nothing here is committed.

## 1. Claim and scope

**What was run.** Sentinel-1 IW3 VV, bursts 4-6 (burst IDs 225587-225589, identical in all three products), S1A 23 Jun, S1C 24 Jun, S1D 30 Jun 2026, staged Copernicus 30 m DEM, `TOPSAR-Split` fixtures only. GSLC: `GSLC-Terrain-Correction` (NATIVE_ANISOTROPIC, WGS84(DD), carrier-free) → `CreateStack` auto path (burst-overlap lock asserted engaged) → `Interferogram` (flat-earth and topographic phase removed, ground-corrected 100 m coherence window, residual-ramp option OFF unless stated). Classical: Back-Geocoding (BISINC) + Enhanced-Spectral-Diversity + `Interferogram` (+ deburst).

**What was not run.** The whole stripmap half of the claim (Napa, ASAR Bam), PAZ, NISAR, R6 (geophysical equivalence with published solutions), R7, the deck, the ETAD-off-in-both null, the geolocation offset map, and the perturbed-orbit variant of R1. **Nothing in this note supports a claim about stripmap or about any mission other than S1 IW.**

**Where the three claim tiers stand on Venezuela.**

| Tier | Claim | Status |
|---|---|---|
| 1 | GSLC is self-consistent | **Partly supported.** On synthetic pairs with known truth the GSLC chain adds no phase of its own (R1, and R2-lite with the ramp option off). The real-data closure test (R3) **fails its pre-registered gates**, but the failure is what decorrelation would produce (section 4); it is not a pass. |
| 2 | GSLC is geophysically equivalent | **Not tested** (R6 not run). |
| 3 | GSLC is numerically equivalent to the classical chain | **Supported on one pair, after the fix.** As shipped, the R5b gates failed (concentration 0.236, gy ratio 37.9) because of a per-burst smooth surface. Its cause is an inverted carrier-difference add-back sign in `InterferogramOp` (section 12). With the sign corrected (now the default), ramp off: link 0.949, concentration 0.961, gx / gy ratios 0.87 / 0.86, **all four R5b gates pass**. Validated on ONE pair (S1A x S1C, 3 bursts, ETAD on, mean coherence 0.28); other pairs, ETAD-off, Etna and full scenes are not yet re-run. |

## 2. Results

Values are as recorded. "PROVISIONAL" gates were chosen before the measurement with no prior data behind them (section 6).

| Rung | What | Result | Verdict |
|---|---|---|---|
| Crop gate, as first run | 3-burst fixture vs full-scene control | coherence Δ 0.142 (gate 0.03) | FAIL, **confounded**: the control was built by a different chain (see 5.1) |
| Crop gate, matched | fixture rebuilt with the control's own parameters | coherence Δ 0.0039, gx 0.0011, gy 0.0035 (gates 0.03 / 0.02 / 0.02) | PASS, one deviation: staged DEM tile (5.2). Pixelwise phase concentration 0.49 even for matched chains: unexplained, not gated |
| P7 | inverse-geocode round-trip floor | single-look 0.93 rad; multilooked to ~100 m 0.13 rad (concentration 0.999) | measured. Only relevant to the tie-point R4/R5, which are invalid |
| P8 | classical vs its own bicubic twin | concentration 0.99995, 0.043 rad | measured, optimistic (only the kernel differs) |
| R1 | GSLC removal chain, injected 6 rad lobe, ramp off | RMS 0.0004 rad, retention 1.0000, 137 M pixels (gate 0.05) | PASS. Null-baseline form: geometry identical, does **not** test the flat-earth surface on a high-B⊥ pair |
| R2-lite generator floor | zero-phase pair | exactly 0.0 rad | measured; the spec's "2× floor" gate is unsatisfiable at 0, so the gate is `max(2×floor, 0.05)` (fixed before any lobe result) |
| R2-lite retention | same lobe, three chains | classical **1.0000** (RMS 0.0009); GSLC ramp off **1.0000**; GSLC ramp on **0.9764** (RMS 0.1135, fails 0.05) | first measured cost of `subtractResidualRamp`: it absorbs ~2.4% of a broad 6 rad range-only lobe. The lobe is 1-D; a localised 2-D lobe would retain differently |
| R3 | phase closure, S1A/S1C/S1D, ETAD-off | RMS 1.365 rad (gate 0.3), worst band mean 0.154 (gate 0.1) | **FAIL as pre-registered**; see section 4 |
| R4, R5 (tie-point link) | first attempt | R5 concentration 0.043; R4 range offsets −11/−20/−27 px | **INVALID**: tie-point lat/lon ignores terrain. Rows kept and marked |
| R5b (bin link), ramp off, **as shipped (sign +1)** | GSLC vs classical, ~100 m radar cells | link amplitude correlation 0.923 (valid); concentration 0.236 (ratio to floor 0.236, gate 0.90); gx ratio 1.18; **gy ratio 37.9** (gate 2) | **FAIL**, valid comparison. Caused by the inverted sign (section 12) |
| R5b, ramp off, **sign corrected (new default)** | same, from a real rebuild of the interferogram | link 0.949; concentration **0.961**; gx ratio 0.87; gy ratio 0.86 | **PASS** all four gates (rung `R5b-signfix`) |
| R5b, ramp on, sign corrected | same with `subtractResidualRamp` | link 0.947; concentration 0.334; gx 1.12; gy 0.34 | **FAIL** (rung `R5b-signfix-ramp`): the ramp now degrades agreement, it was absorbing the sign artefact |
| R5b, ramp on (variant) | same with `subtractResidualRamp` | link 0.945; concentration 0.288; gx 0.16; **gy 3.09** | **FAIL**; the gradient is largely fixed, the phase agreement is not |
| Seam gate | worst phase step at burst seams | worst 3.03 rad on all reported rows; 3.03 (ramp off) / 3.08 (ramp on) at the 11 rows confirmed as true seams, median 1.45 / 2.19 | **FAIL as measured, but an upper bound**, cause not isolated (section 4) |

## 3. What the R5b diagnostics say

The comparison bins each GSLC map pixel into a radar multilook cell (8 lines × 30 columns, ~112 m × 100 m) by the source range/azimuth index the GSLC recorded for it, and compares with the classical interferogram in the same cells. It needs no resampling and no geolocation; its validity is checked by the amplitude correlation of the two products over shared cells (0.92-0.95, against 0.30 through the tie-point grid).

* **[Superseded, see section 12] The GSLC azimuth phase gradient was read as the known annotation-mismatch ramp.** It is the `2 x (m_ref - m_sec)` residual of the inverted sign; the reading below is kept as recorded. Ramp off, the GSLC interferogram's own azimuth gradient is −0.89 rad per 8-line cell (about −0.11 rad/line; −0.78 / −0.81 / −1.26 in bursts 1-3) against −0.02 for the classical chain. The residual-ramp option reduces the gradient ratio from 37.9 to 3.09.
* **Bursts share one offset.** Ramp on, the mean offset of GSLC×conj(classical) is +0.85 / +0.81 / +0.84 rad in bursts 1-3, so there are no per-burst jumps between them.
* **Local agreement is much better than global.** Dividing burst 2 into six range bands, each band agrees with the classical phase at a phase-only concentration of 0.84-0.94 after its own plane, while a single plane over the whole swath leaves 0.005. Over 173 tiles of about 3.6 × 6.4 km, the median phase-only concentration is 0.787 (ramp off, 0.70 rad RMS about the tile mean) and 0.889 (ramp on, 0.51 rad). The 10th percentile is about 0.3, so some tiles agree badly.
* **Interpretation (revised 2026-09-21).** The difference between the chains was a smooth, per-burst phase surface that one plane cannot remove. Section 11 characterised it (per-burst, cubic-order, not terrain). Its origin is now known and is NOT annotation error: it is `2 x (m_ref - m_sec)` from the inverted carrier-difference sign (section 12). Every bullet above describes the legacy-sign product. These tile-local figures are post-hoc diagnostics, recorded as rung `R5b-diag`, and they do not turn the failed gates into passes.

## 4. Caveats that change how the failures read

**R3 fails because the pairs are barely coherent, not (on this evidence) because GSLC is inconsistent.** Mean coherence is 0.284 (S1A×S1C, 1 day), 0.177 (S1C×S1D, 6 days), 0.161 (S1A×S1D, 7 days): tropical, ETAD-off. The pre-registered 0.3 rad gate assumed coherence around 0.5. Stratifying closure by the weakest pair's coherence (post-hoc, rung `R3d`):

| min coherence ≥ | cells | closure RMS (rad) | Cramér-Rao noise model (L=200) |
|---|---|---|---|
| 0.0 | 452,989 | 1.365 | |
| 0.2 | 64,869 | 0.523 | 0.266 |
| 0.3 | 18,123 | 0.252 | 0.181 |
| 0.4 | 5,641 | 0.153 | 0.135 |
| 0.5 | 1,877 | 0.108 | 0.105 |
| 0.7 | 148 | 0.043 | 0.067 |

At coherence ≥ 0.4 the observed closure matches the noise model; at 0.2-0.3 it exceeds it 1.4-2×, which may be the known upward bias of coherence estimators at low coherence or a small real inconsistency (undecidable here). The conventions are verified: at coherence ≥ 0.5 the closure as formed is 0.108 rad, while flipping either pair gives 1.8 rad. A coherence-aware gate ("observed ≤ 1.5× the noise-model prediction") should be pre-registered for the next run; it must not be applied retroactively to declare a pass.

**The seam figures are an upper bound.** The carrier-band seam locator reported 7-10 rows per column strip on a product with two seams; checked against the GSLC's `diag_burst` band it finds the true seams (about 9 rows early) plus about five spurious rows per strip. At the 11 confirmed seam rows the steps are 0.4-3.1 rad, but the measure (35-row complex means, 10 rows either side) is contaminated by any azimuth ramp between the bands, and the ramp option deliberately allows per-burst constants. It is not the seam step.

## 5. What execution changed

1. **The crop gate was confounded.** The full-scene classical control on disk was built with BILINEAR Back-Geocoding, **no Enhanced-Spectral-Diversity step**, a 2×10 coherence window and the auto DEM; the fixture chain used ESD, 7×30 and a staged DEM. **The classical control the campaign has been comparing against has no ESD, so the R5 control here (with ESD) is a stronger chain.** The gate was re-run with the control's own recorded parameters.
2. **The auto-downloaded Copernicus DEM does not finish** on the maven-exec classpath: every worker thread queues on the synchronised `CopernicusDirectElevationTile.getSample` (27 min, no output; the staged tile takes ~4 min). All runs use the staged GeoTIFF.
3. **The master GSLC must be WGS84(DD).** The `CreateStack` auto path locks the secondary in degrees; against a UTM master it fails with a non-integer lattice offset.
4. **The range window is 30 pixels, not 27** (IW3 incidence 43.9°). The old slant-spacing formula gives 43.
5. **Real S1 SLC bands are int16**, so the synthetic generator writes float32 (rounding back to int16 would add noise unrelated to the phase under test).
6. **The first R4/R5 were invalid** (tie-point geolocation ignores terrain) and were replaced by R5b; the invalid rows are kept and marked.

## 6. Threshold provenance

From the spec: R1 < 0.05 rad; R4 < 0.1 px; R5 gradient ratios ≤ 2. **Chosen before the measurement with no prior data (PROVISIONAL):** crop coherence Δ ≤ 0.03 and gradient Δ ≤ 0.02; R5 concentration ≥ 0.90 × the P8 floor; R5 coherence parity ≤ 0.05; R3 closure RMS ≤ 0.30 and worst band mean ≤ 0.10; R5b link correlation ≥ 0.8; seam step ≤ 0.3; the R2 gate rule `max(2×floor, 0.05)`. The R3 gate is now known to have been badly chosen for low-coherence pairs (section 4); it was left as recorded and not moved.

## 7. Error budget (R5b, ramp on, tile-local)

Residual: 0.508 rad RMS about the tile mean (post-hoc diagnostic). Measured or modelled terms, added in quadrature: coherence-noise model 0.243 rad (Cramér-Rao at mean coherence 0.284, 200 looks per cell; a model, not a measurement of this pair's noise), classical reproducibility (P8) 0.043 rad (measured). **Explained 0.243 rad, unexplained 0.446 rad; coverage 23% of the variance.** The inverse-geocode floor (P7) does not apply to R5b, which bins and does not resample. Most of the residual is unexplained in the budget sense (no measured term covers it); section 11 characterises it as a per-burst smooth surface whose azimuth part is the GSLC annotation ramp. Its physical origin remains a hypothesis.

## 8. Timings measured (3 bursts)

Fixtures ~10 min in total (ETAD ~4.3 min each). Classical coregistration 7.7 min with ESD (3 min without), interferogram 3.6 min. GSLC 17 min, `CreateStack` auto path 26 min, interferogram 9 min for one pair; with the diagnostic bands (`-Dgslc.diagGeometry=true`) GSLC 35-39 min and ~25 GB. A closure pair ~50 min; the three-pair R3 closure ~2.3 h in total.

## 9. Decisions taken without asking (review these)

* Re-ran the crop gate matched to the control's parameters, and used the staged DEM for that run, after the user chose the matched re-test.
* Changed the master GSLC from UTM to WGS84(DD), and R5's multilook to 7×30 (R5b: 8×30).
* Reported two P7 floors and did not apply the plan's "stop if RMS ≥ 0.05" rule to the single-look one.
* Fixed the R2 gate rule after the floor came out exactly zero, before any lobe result existed.
* Replaced the tie-point R4/R5 with R5b and marked the old rows INVALID, and ran a ramp-on variant under a separate label.
* Ran post-hoc diagnostics (R3d, R5b-diag, SEAM-true), each labelled as such and none replacing a recorded gate.
* Deleted regenerable stage products (stacks, GSLCs) once their results were recorded, to stay inside the disk budget; interferograms and all result rows were kept.
* Did not retune any threshold or meter after seeing a result.

## 10. Reproduction

Plan: `docs/superpowers/plans/2026-09-20-gslc-parity-venezuela-3day.md` (Tasks 1-9 plus the R5b addendum). Drivers in `validation/drivers/` (`ven_fixture.ps1`, `ven_classical.ps1`, `ven_crop_matched.ps1`, `ven_gslc.ps1`, `ven_closure.ps1`, `ven_synth.ps1`, `ven_r5b.ps1`), analysis in `validation/gslc_parity/`. Every module has a `--selftest`. Scorecard: `python validation/gslc_parity/budget.py --scorecard`.

## 11. Follow-up: the smooth surface between the chains

Asked after the first run: what is the smooth phase surface that separates the GSLC and classical interferograms? All of it is post-hoc analysis of the saved R5b cells (8-line × 30-column radar cells, blocked 8 × 8 for unwrapping), recorded as rung `SURFACE`; scripts `validation/gslc_parity/surface_diag*.py`. None of it changes a gate.

**What it is.**

* **Three disconnected per-burst patches.** The GSLC-minus-classical phase has usable blocks in each burst and none at the seams, so each burst unwraps on its own and the offsets *between* bursts are arbitrary multiples of 2π. Only the shape inside a burst is meaningful. An early global fit (a 130 rad "surface", R² 0.70 for a 2-D quadratic) was an artefact of joining the patches; the per-burst range-quadratic fit gives R² 0.95.
* **Per-burst cubic.** With the ramp option on, the surface has a standard deviation of 29 / 14 / 14 rad inside bursts 1-3, and a cubic per burst captures it (block residual 0.17 / 0.27 / 0.48 rad). Removing it lifts the cell-level phase-only concentration to 0.928 / 0.898 / 0.863 (0.896 overall, rms 0.49 rad), against 0.288 before.
* **The azimuth part is the GSLC's annotation-mismatch ramp.** The ramp the GSLC estimator removed from its own interferogram is −46.8 / −47.8 / −73.0 rad/s. The directly measured GSLC-minus-classical azimuth gradient (ramp off) is 1.013 / 1.034 / 1.047 times that, in each burst. Two independent estimators agree within 5%, and the classical chain carries none of it.
* **The range part differs from burst to burst.** With the ramp on, the per-burst range slope is −0.0045 / −0.0010 / +0.0020 rad/px (about −106 / −24 / +47 rad across the swath), differing between bursts by up to ~150 rad. The estimator's range terms are shared by all bursts, so it cannot represent this.

**What it is not.** Not terrain: adding block-averaged DEM height to the per-burst fits changes the block residual by at most 0.3 rad, and the coefficient changes sign and size with the polynomial degree (an implied perpendicular-baseline mismatch anywhere from −18 m to +6 m; the topographic sensitivity is only 0.00035 rad per metre of height per metre of baseline). So it is not a topographic-phase or baseline mismatch.

**Why it cannot be removed from the GSLC interferogram alone.** Per-burst surfaces (shared-range, plane, quadratic, cubic) estimated from the ramp-on GSLC interferogram by unwrapped fits, then scored against the classical chain, give a concentration of 0.013-0.060, against 0.022 uncorrected. The upper bound, the same cubic fitted to the difference with the classical chain, gives 0.896. The reason is in the classical interferogram itself: its own per-burst cubic has a standard deviation of 16.6 rad, so real large-scale phase (coseismic deformation, orbit, atmosphere) has the same order and shape as the annotation surface. A GSLC-alone estimator absorbs both (its cubic removes 24 rad; its correlation with the true difference surface is 0.35 / 0.09 / 0.97 by burst). Adding per-burst range terms to the estimator would therefore not close the gap on its own.

**Hypothesis (TESTED AND REFUTED, section 12).** The carrier-free GSLC keeps each acquisition's annotation deramp-model error (Doppler-centroid and FM-rate polynomials, which are per burst in the annotation), and the classical chain cancels it by deramping and reramping each leg with its own model. That would make the mismatch per-burst in both azimuth and range. A direct test would compare the per-burst annotation DC/FM polynomials of S1A and S1C with the fitted surfaces. If it holds, the discriminating measurement is one where genuine signal cancels: an ESD-like double difference in the burst overlaps. The current GSLC output selects one burst per pixel, so it does not provide that.

**Caveats.** One pair (ETAD on, S1A × S1C), three bursts, mean coherence 0.28; block-level unwrapping (checked by the small fit residuals, not by an independent unwrapper); a gradient-based emulation of the estimator was tried first and discarded because even its upper bound only reached 0.15, which means it was too imprecise to say anything; the classical chain is not ground truth.

---

## Appendix: generated scorecard (unedited)

| Rung | Source | Gate | Result | Threshold provenance | Note |
|---|---|---|---|---|---|
| BUDGET | venezuela | r5b-tile-local-residual-rms-rad | MEASURED 0.508 | post-hoc diagnostic | terms (rad, added in quadrature): coherence-noise model 0.239 (Cramer-Rao at mean coherence 0.284, L=200 looks - model, not a measurement of this pair's noise), classical reproducibility P8 0.043 (measured). Explained 0.243, UNEXPLAINED 0.446 rad, coverage 23%. The inverse-geocode floor P7 does not apply: R5b bins map pixels, it does not resample. |
| CROP | venezuela | crop-gate-matched | PASS 0 (gate 0) | PROVISIONAL: chosen before measurement, no prior data | PASS with matched configuration (control params read from its Processing_Graph: BILINEAR, no ESD, cohWin 2x10): coh delta 0.0039 (<=0.03), gx 0.0011, gy 0.0035 (<=0.02). One deliberate deviation: staged Cop30 GeoTIFF instead of auto DEM (auto reader serialised by synchronised getSample, did not finish in 27 min). Informational pixelwise concentration 0.49 even for matched chains - unexplained, not gated. Supersedes the confounded crop-gate row. |
| CROP | venezuela | crop-gate | FAIL 1 (gate 0) | PROVISIONAL: chosen before measurement, no prior data | FAIL as run (coh delta 0.142 vs 0.03; gx/gy deltas 0.002 pass; pixelwise conc 0.49). CONFOUNDED: control used cohWin 2x10, auto DEM, BILINEAR dem resampling, no ESD; fixture used 7x30, staged DEM, BICUBIC, ESD. Not a clean crop test. |
| P7 | venezuela | roundtrip-conc-ml | MEASURED 0.9991 | measured (this sampler on real fringes) | ML (7, 43), n=13724 |
| P7 | venezuela | roundtrip-conc-single | MEASURED 0.9534 | measured (this sampler on real fringes) | n=4177936 |
| P7 | venezuela | roundtrip-rms-rad-ml | MEASURED 0.1325 | measured (this sampler on real fringes) | the floor R4/R5 are judged against (ML (7, 43) map cells ~ 100 m) |
| P7 | venezuela | roundtrip-rms-rad-single | MEASURED 0.934 | measured (this sampler on real fringes) | per-pixel speckle cost of bilinear; NOT the floor R5 is judged against |
| P8 | venezuela | classical-floor-conc | MEASURED 0.9999 | measured (Back-Geocoding BICUBIC vs BISINC) | OPTIMISTIC: same speckle and geometry, only the resampling kernel differs |
| P8 | venezuela | classical-floor-rms-rad | MEASURED 0.04314 | measured |  |
| R1 | venezuela-synth | gslc-ramp-off-retention | MEASURED 1 | measured (no gate: first error bar) |  |
| R1 | venezuela-synth | gslc-ramp-off-rms-rad | PASS 0.0004043 (gate 0.05) | spec section 7 (R1: 0.05 rad) |  |
| R2 | venezuela-synth | classical-retention | MEASURED 1 | measured (no gate: first error bar) |  |
| R2 | venezuela-synth | classical-rms-rad | PASS 0.0009484 (gate 0.05) | PROVISIONAL: max(2 x measured generator floor, 0.05 rad); the floor was exactly 0, so 2x floor alone would be unsatisfiable |  |
| R2 | venezuela-synth | generator-floor-rms-rad | MEASURED 0 | measured (zero-perturbation pair) | n=136998286. GO/NO-GO spike PASSED: SNAP accepted the +12-day same-geometry copy as a 2nd acquisition (stack built, burst-overlap lock engaged, coherence exactly 1.000, median |phase| 0.000 in every row and range band). |
| R2 | venezuela-synth | gslc-ramp-off-retention | MEASURED 1 | measured (no gate: first error bar) |  |
| R2 | venezuela-synth | gslc-ramp-off-rms-rad | PASS 0.0004043 (gate 0.05) | PROVISIONAL: max(2 x measured generator floor, 0.05 rad); the floor was exactly 0, so 2x floor alone would be unsatisfiable |  |
| R2 | venezuela-synth | gslc-ramp-on-retention | MEASURED 0.9764 | measured (no gate: first error bar) |  |
| R2 | venezuela-synth | gslc-ramp-on-rms-rad | FAIL 0.1135 (gate 0.05) | PROVISIONAL: max(2 x measured generator floor, 0.05 rad); the floor was exactly 0, so 2x floor alone would be unsatisfiable |  |
| R3 | venezuela | closure-mean-rad | MEASURED 0.04346 | measured |  |
| R3 | venezuela | closure-rms-centred-rad | MEASURED 1.365 | measured |  |
| R3 | venezuela | closure-rms-rad | FAIL 1.365 (gate 0.3) | PROVISIONAL: chosen before measurement, no prior data | ETAD-off on all three legs (no S1D ETAD exists) |
| R3 | venezuela | closure-worst-band-mean-rad | FAIL 0.1536 (gate 0.1) | PROVISIONAL: chosen before measurement, no prior data | ETAD-off on all three legs (no S1D ETAD exists) |
| R3d | venezuela | closure-rms-mincoh0.3 | MEASURED 0.2518 | post-hoc diagnostic (not a gate) | n=18123 cells; Cramer-Rao noise model from the measured coherences (L=200 looks) predicts 0.181 rad; observed/predicted 1.39 |
| R3d | venezuela | closure-rms-mincoh0.5 | MEASURED 0.1078 | post-hoc diagnostic (not a gate) | n=1877 cells; Cramer-Rao noise model from the measured coherences (L=200 looks) predicts 0.105 rad; observed/predicted 1.02 |
| R3d | venezuela | convention-check-mincoh0.5 | MEASURED 0.108 | post-hoc diagnostic (not a gate) | closure as formed 0.108 rad; either pair flipped 1.8 rad, so conventions are consistent |
| R3d | venezuela | pair-mean-coherence | MEASURED 0.284 | measured | AC(1 d)=0.284, CD(6 d)=0.177, AD(7 d)=0.161 (ETAD-off, tropical): the raw R3 gate mostly measures decorrelation |
| R4 | venezuela | ref-offset-px | MEASURED 24.88 (gate 0.1) | spec section 7 (R4: 0.1 px) | raw d_row -0.502, bistatic model 0.000, applied=False, sign_ok=True | INVALID COMPARISON - do not read as a GSLC result. Radar-pixel lat/lon came from the classical product's coarse tie-point grid, which does not follow local terrain, while GSLC geocodes with the DEM. Diagnostics 2026-09-20: ML amplitude correlation 0.30; amplitude cross-correlation shows a 25 px range displacement, identical for the reference and secondary legs, growing from -11 to -27 px across the three bursts; registering with those offsets did not recover the phase concentration (0.037), so it is not a constant shift. Redesign needed (see report). |
| R4 | venezuela | sec-offset-px | MEASURED 24.73 (gate 0.1) | spec section 7 (R4: 0.1 px) | raw d_row -0.521, bistatic model 0.000, applied=False, sign_ok=True | INVALID COMPARISON - do not read as a GSLC result. Radar-pixel lat/lon came from the classical product's coarse tie-point grid, which does not follow local terrain, while GSLC geocodes with the DEM. Diagnostics 2026-09-20: ML amplitude correlation 0.30; amplitude cross-correlation shows a 25 px range displacement, identical for the reference and secondary legs, growing from -11 to -27 px across the three bursts; registering with those offsets did not recover the phase concentration (0.037), so it is not a constant shift. Redesign needed (see report). |
| R5 | venezuela | conc-over-floor | MEASURED 0.04324 (gate 0.9) | PROVISIONAL: chosen before measurement, no prior data | INVALID COMPARISON - do not read as a GSLC result. Radar-pixel lat/lon came from the classical product's coarse tie-point grid, which does not follow local terrain, while GSLC geocodes with the DEM. Diagnostics 2026-09-20: ML amplitude correlation 0.30; amplitude cross-correlation shows a 25 px range displacement, identical for the reference and secondary legs, growing from -11 to -27 px across the three bursts; registering with those offsets did not recover the phase concentration (0.037), so it is not a constant shift. Redesign needed (see report). |
| R5 | venezuela | gx-median-ratio | MEASURED 1.144 (gate 2) | spec section 7 (R5) | INVALID COMPARISON - do not read as a GSLC result. Radar-pixel lat/lon came from the classical product's coarse tie-point grid, which does not follow local terrain, while GSLC geocodes with the DEM. Diagnostics 2026-09-20: ML amplitude correlation 0.30; amplitude cross-correlation shows a 25 px range displacement, identical for the reference and secondary legs, growing from -11 to -27 px across the three bursts; registering with those offsets did not recover the phase concentration (0.037), so it is not a constant shift. Redesign needed (see report). |
| R5 | venezuela | gy-median-ratio | MEASURED 53 (gate 2) | spec section 7 (R5) | INVALID COMPARISON - do not read as a GSLC result. Radar-pixel lat/lon came from the classical product's coarse tie-point grid, which does not follow local terrain, while GSLC geocodes with the DEM. Diagnostics 2026-09-20: ML amplitude correlation 0.30; amplitude cross-correlation shows a 25 px range displacement, identical for the reference and secondary legs, growing from -11 to -27 px across the three bursts; registering with those offsets did not recover the phase concentration (0.037), so it is not a constant shift. Redesign needed (see report). |
| R5 | venezuela | phase-residual-conc | MEASURED 0.04324 | measured | INVALID COMPARISON - do not read as a GSLC result. Radar-pixel lat/lon came from the classical product's coarse tie-point grid, which does not follow local terrain, while GSLC geocodes with the DEM. Diagnostics 2026-09-20: ML amplitude correlation 0.30; amplitude cross-correlation shows a 25 px range displacement, identical for the reference and secondary legs, growing from -11 to -27 px across the three bursts; registering with those offsets did not recover the phase concentration (0.037), so it is not a constant shift. Redesign needed (see report). |
| R5 | venezuela | residual-rms-rad | MEASURED 1.782 | measured | INVALID COMPARISON - do not read as a GSLC result. Radar-pixel lat/lon came from the classical product's coarse tie-point grid, which does not follow local terrain, while GSLC geocodes with the DEM. Diagnostics 2026-09-20: ML amplitude correlation 0.30; amplitude cross-correlation shows a 25 px range displacement, identical for the reference and secondary legs, growing from -11 to -27 px across the three bursts; registering with those offsets did not recover the phase concentration (0.037), so it is not a constant shift. Redesign needed (see report). |
| R5b-diag | venezuela | azimuth-gradient-GSLC-vs-classical | MEASURED -0.891 | post-hoc diagnostic (not a gate) | ramp OFF: GSLC own azimuth phase gradient -0.89 rad per 8-line cell (~-0.11 rad/line; per burst -0.78/-0.81/-1.26) vs classical -0.02: the known annotation-mismatch ramp; subtractResidualRamp reduces the gy ratio 37.9 -> 3.09 |
| R5b-diag | venezuela | per-burst-offset-ramp-on | MEASURED 0.83 | post-hoc diagnostic (not a gate) | mean phase offset of GSLC*conj(classical) per burst after its plane: +0.85/+0.81/+0.84 rad, i.e. common to all bursts, no per-burst jumps |
| R5b-diag | venezuela | tile-local-phase-conc-median-ramp-off | MEASURED 0.7869 | post-hoc diagnostic (not a gate) | post-hoc diagnostic (not a gate): median over 32x64-cell tiles (~3.6 x 6.4 km) of the phase-only concentration of GSLC*conj(classical) after removing that tile's own plane; isolates local agreement from smooth low-order surfaces; n tiles=173 |
| R5b-diag | venezuela | tile-local-phase-conc-median-ramp-on | MEASURED 0.8893 | post-hoc diagnostic (not a gate) | post-hoc diagnostic (not a gate): median over 32x64-cell tiles (~3.6 x 6.4 km) of the phase-only concentration of GSLC*conj(classical) after removing that tile's own plane; isolates local agreement from smooth low-order surfaces; n tiles=173 |
| R5b-diag | venezuela | tile-local-rms-rad-median-ramp-off | MEASURED 0.7031 | post-hoc diagnostic (not a gate) | post-hoc diagnostic (not a gate): median over 32x64-cell tiles (~3.6 x 6.4 km) of the phase-only concentration of GSLC*conj(classical) after removing that tile's own plane; isolates local agreement from smooth low-order surfaces |
| R5b-diag | venezuela | tile-local-rms-rad-median-ramp-on | MEASURED 0.5084 | post-hoc diagnostic (not a gate) | post-hoc diagnostic (not a gate): median over 32x64-cell tiles (~3.6 x 6.4 km) of the phase-only concentration of GSLC*conj(classical) after removing that tile's own plane; isolates local agreement from smooth low-order surfaces |
| R5b-ramp | venezuela | conc-over-floor | FAIL 0.2881 (gate 0.9) | PROVISIONAL: chosen before measurement, no prior data | terrain-aware bin link (diag_rangeIndex/diag_azimuthIndex); replaces the invalid tie-point-grid R5 |
| R5b-ramp | venezuela | gx-median-ratio | PASS 0.1616 (gate 2) | spec section 7 (R5) | terrain-aware bin link (diag_rangeIndex/diag_azimuthIndex); replaces the invalid tie-point-grid R5 |
| R5b-ramp | venezuela | gy-median-ratio | FAIL 3.09 (gate 2) | spec section 7 (R5) | terrain-aware bin link (diag_rangeIndex/diag_azimuthIndex); replaces the invalid tie-point-grid R5 |
| R5b-ramp | venezuela | link-amplitude-corr | PASS 0.9446 (gate 0.8) | PROVISIONAL: chosen before measurement, no prior data | terrain-aware bin link (diag_rangeIndex/diag_azimuthIndex); replaces the invalid tie-point-grid R5 |
| R5b-ramp | venezuela | phase-residual-conc | MEASURED 0.2881 | measured | link corr 0.945 |
| R5b-ramp | venezuela | residual-rms-rad | MEASURED 1.81 | measured | link corr 0.945 |
| R5b | venezuela | conc-over-floor | FAIL 0.2359 (gate 0.9) | PROVISIONAL: chosen before measurement, no prior data | terrain-aware bin link (diag_rangeIndex/diag_azimuthIndex); replaces the invalid tie-point-grid R5 |
| R5b | venezuela | gx-median-ratio | PASS 1.182 (gate 2) | spec section 7 (R5) | terrain-aware bin link (diag_rangeIndex/diag_azimuthIndex); replaces the invalid tie-point-grid R5 |
| R5b | venezuela | gy-median-ratio | FAIL 37.87 (gate 2) | spec section 7 (R5) | terrain-aware bin link (diag_rangeIndex/diag_azimuthIndex); replaces the invalid tie-point-grid R5 |
| R5b | venezuela | link-amplitude-corr | PASS 0.9226 (gate 0.8) | PROVISIONAL: chosen before measurement, no prior data | terrain-aware bin link (diag_rangeIndex/diag_azimuthIndex); replaces the invalid tie-point-grid R5 |
| R5b | venezuela | phase-residual-conc | MEASURED 0.2359 | measured | link corr 0.923 |
| R5b | venezuela | residual-rms-rad | MEASURED 1.756 | measured | link corr 0.923 |
| SEAM-true | venezuela-ramp-off | seam-worst-step-true-seams | MEASURED 3.03 (gate 0.3) | PROVISIONAL: chosen before measurement, no prior data | n=11 steps at seam rows confirmed by diag_burst (locator alone reported ~5 spurious rows of 9 per strip). median |step| 1.45. Measurement caveat: two-sided complex means over 35-row bands 10 rows either side; any residual azimuth ramp between the bands adds to the step, so this is an upper bound on the seam step, not the seam step itself. |
| SEAM-true | venezuela-ramp-on | seam-worst-step-true-seams | MEASURED 3.08 (gate 0.3) | PROVISIONAL: chosen before measurement, no prior data | n=11 steps at seam rows confirmed by diag_burst (locator alone reported ~5 spurious rows of 9 per strip). median |step| 2.19. Measurement caveat: two-sided complex means over 35-row bands 10 rows either side; any residual azimuth ramp between the bands adds to the step, so this is an upper bound on the seam step, not the seam step itself. |
| SEAM | venezuela | seam-median-abs-step | MEASURED 1.39 | measured | SUPERSEDED by rung SEAM-true (locator validated against diag_burst; ~5 of 9 reported rows per strip were spurious). n=43 candidate rows; noise-like (uniform-phase expectation 1.57) |
| SEAM | venezuela | seam-worst-step | MEASURED 3.034 (gate 0.3) | PROVISIONAL: chosen before measurement, no prior data | SUPERSEDED by rung SEAM-true (locator validated against diag_burst; ~5 of 9 reported rows per strip were spurious). UNRELIABLE - not a seam finding. (1) The carrier-band seam locator reported 7-10 candidate rows per column strip on a 3-burst product that has 2 seams, with spacings of ~401/629 rows that match no burst geometry; the locator's spacing prior was tuned on the 10-burst full scene and is unvalidated here. (2) Measured steps are noise-like (median |step| 1.39 rad vs 1.57 expected for uniform phase) at mean pair coherence ~0.28. (3) The ifg has coseismic fringe gradients that a plain step conflates with a seam. Not retuned overnight. To validate: locate true seams from the diag_burst band of the diagnostic-band GSLC (ven_etadD_gslc) and measure only on cells with coherence >= 0.4 with a noise estimate per seam. |
| SURFACE | venezuela | azimuth-ramp-match-max-deviation | MEASURED 0.047 | post-hoc diagnostic (not a gate) | GSLC-minus-classical azimuth gradient per burst (ramp OFF) / the ramp the GSLC estimator removed from its own interferogram: 1.013 / 1.034 / 1.047 (estimator -46.8/-47.8/-73.0 rad/s). Two independent estimators agree within 5%: the azimuth part of the surface IS the GSLC annotation-mismatch ramp and the classical chain carries none of it. |
| SURFACE | venezuela | gslc-alone-estimator-best-conc | MEASURED 0.06 | post-hoc diagnostic (not a gate) | per-burst surfaces (shared-range, plane, quadratic, cubic) estimated from the ramp-ON GSLC interferogram ALONE and scored against the classical chain: concentration 0.013-0.060 vs 0.022 uncorrected. They do not help. The classical interferogram's own per-burst cubic has std 16.6 rad, i.e. real large-scale phase of the same order and shape exists, so the annotation surface cannot be separated from signal without external information. (A gradient-based emulation was also tried and rejected as too imprecise: even its upper bound reached only 0.15.) |
| SURFACE | venezuela | height-dependence-implied-baseline-mismatch-range-m | MEASURED 24 | post-hoc diagnostic (not a gate) | adding block-averaged DEM height to the per-burst fits changes the block residual by <=0.3 rad and the coefficient is unstable in sign and size across polynomial degree (implied perpendicular-baseline mismatch between -18 m and +6 m; topographic sensitivity is only 0.00035 rad per m of height per m of baseline): NOT a topographic / baseline-mismatch surface. |
| SURFACE | venezuela | perburst-cubic-cell-conc-all | MEASURED 0.896 | post-hoc diagnostic (not a gate) | ramp ON: after removing a per-burst cubic fitted to the unwrapped GSLC-minus-classical phase, cell-level phase-only concentration 0.928 / 0.898 / 0.863 per burst (rms 0.49 rad overall). Block residuals of the cubic 0.17/0.27/0.48 rad. The surface is three disconnected per-burst patches (offsets between bursts are arbitrary multiples of 2 pi) with surface std 29/14/14 rad. |
| SURFACE | venezuela | perburst-plane-range-slope-rad-per-px | MEASURED -0.00449 | post-hoc diagnostic (not a gate) | ramp ON per-burst plane slopes, range: -0.00449 / -0.00103 / +0.00198 rad/px (about -106 / -24 / +47 rad across the swath); azimuth -0.0008 / -0.0299 / -0.0042 rad/line. The range slope differs between bursts by up to ~150 rad across the swath, which the estimator's shared range terms cannot represent. |

## 12. Cause found and fixed: inverted carrier-difference add-back sign (2026-09-21)

The smooth GSLC-minus-classical surface of sections 1-3 was `2 x (m_ref - m_sec)`. `GSLCGeocodingOp` restores the carrier with exp(-j*m), so carrier-free legs carry `truth x exp(+j*m)`; `InterferogramOp.addGslcCarrierModelDiff` assumed exp(-j*m) and added the leg difference with the wrong sign. The annotation DC/FM hypothesis was tested and refuted (rung CAUSE): both chains use the same model to <0.01 rad and the carrier-free legs equal the classical deramped legs.

**Fix (now the default):** `CARRIER_DIFF_SIGN = -1`; `-Dgslc.carrierDiffSign=+1` restores the legacy sign. Venezuela S1A x S1C, ETAD on, bursts 4-6, ramp OFF: R5b link 0.949, concentration 0.961, gx/gy ratios 0.87/0.86 - all gates pass (as shipped before: 0.236 / 1.18 / 37.9).

**Consequences / caveats**
- Synthetic tests were blind by construction (identical geometry gives m_ref = m_sec) and three-pair closure telescopes the error away. `TestCarrierDiffSign.addBackRestoresTruthWhenLegsCarryPlusJm` now pins the convention with distinct models.
- With the sign right and `subtractResidualRamp=true`, agreement gets WORSE (concentration 0.334, rates -2.6/+6.6/-3.0 rad/s): the ramp was absorbing the sign-error artefact. Earlier "annotation-mismatch ramp" conclusions and the ramp's rationale need re-evaluation. It is default-off.
- The Javadoc's former "sign pinned empirically (fitted rates collapse)" claim is unreconciled; it contradicts these measurements and may date from a different build.
- Validated on ONE pair (3 bursts, ETAD on). Other pairs, ETAD-off, Etna and full scenes, and older recorded GSLC results, should be re-checked.

**Figure.** Wrapped phase on the classical burst-geometry radar cells (8 x 30, ~100 m), bursts 4-6 top to bottom, ramp off. `img/ifg_classical.png` | `img/ifg_GSLC_before.png` (legacy sign: dense azimuth stripes, no topographic fringes) | `img/ifg_GSLC_after.png` (corrected sign: same fringes as classical). Regenerate with `python validation/gslc_parity/plot_ifg_compare.py <out_dir>`.
