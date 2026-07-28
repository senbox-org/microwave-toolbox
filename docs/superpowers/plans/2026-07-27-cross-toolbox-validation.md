# Plan B — Cross-toolbox validation of SNAP GSLC InSAR

**Goal:** establish external, defensible evidence that SNAP's GSLC InSAR products are correct — not
just self-consistent and not just equal to SNAP's own classical chain.

---

## B0 — Framing: what can actually serve as a reference

One correction to the premise, because it changes the design. **No processing toolbox is ground
truth.** MintPy, ISCE, GAMMA and SNAP all implement the same physics with different approximations;
agreement between two of them is evidence of *correctness of implementation*, not of truth, and
disagreement doesn't tell you which one is wrong. There are three genuinely different kinds of
external evidence, in increasing strength:

| Tier | Evidence | What it proves | Setup cost |
|------|----------|----------------|------------|
| 1 | **Algorithmic peer**: OPERA CSLC-S1 (ISCE3 `geocodeSlc`) | Our geocode-first implementation matches the reference implementation of *the same algorithm* | Low — download products |
| 2 | **Independent consumer as referee**: MintPy | Our interferograms are valid input to a widely trusted time-series engine, and comparable to another source's | Medium — env setup |
| 3 | **Physical ground truth**: GNSS / published coseismic models | The displacements are *right*, not merely reproducible | Low compute, needs data hunting |

A second design point specific to MintPy: **MintPy is a time-series consumer, not an interferogram
former.** It ingests stacks of unwrapped interferograms + coherence and inverts a network into
displacement time series — and it ships `prep_snap.py`, an ingester *for SNAP output*. So "compare
against MintPy" is best re-framed as **"use MintPy as the common referee"**: push SNAP-GSLC and
SNAP-classical (and ideally an ISCE-derived) stack of the *same* acquisitions through the *same*
MintPy configuration. Differences in the output time series are then attributable to the
interferogram source, with the inversion held constant. That is a much stronger experiment than
treating MintPy as a competitor, and it's also the design that most resembles how users will
actually consume our products.

Recommendation: run **Tier 1 first** (best evidence per unit of effort, no environment work), then
Tier 2, and treat **Tier 3 as the actual headline** for the ESA deliverable — "our coseismic
displacement agrees with GNSS/published models" is a far stronger claim than "our interferogram
resembles another toolbox's".

---

## B1 — Tier 1: OPERA CSLC-S1 as the algorithmic peer  ← start here

OPERA CSLC-S1 is the closest thing to a reference implementation of what GSLC does: ISCE3's
`geocodeSlc`, run operationally by JPL, products publicly available from ASF. It is also the
precedent we already cite to ESA for non-square pixels (5 × 10 m UTM). Comparing against it tests
our geocoding, our carrier convention, and our lattice logic against an independent implementation
of the same design — with **no Python environment required**.

**Work:**
- **B1.1 — Reader.** No OPERA reader exists in `sar-io` (verified). CSLC-S1 products are HDF5 with a
  structure close to NISAR's, and `NisarSubReader` already has the EPSG→projected-grid plumbing to
  borrow. Estimated ~1–2 days for a read-only reader producing complex bands + `CrsGeoCoding` +
  `is_terrain_corrected=1` + orbit state vectors. Shares directly in Plan A's A2.2 work — **do A2.2
  first and this gets cheaper**.
- **B1.2 — Acquisition matching. RESOLVED, and the answer forces a change of target (checked
  2026-07-27).** OPERA CSLC-S1 coverage is: *"USA and U.S. Territories, Canada within 200 km of the
  U.S. border, and all mainland countries from the southern U.S. border down to and including
  Panama."* **Venezuela is beyond Panama — there is no OPERA CSLC-S1 for our tutorial pair.** So
  Tier 1 and Tier 3 must run on *different* scenes; they were never going to be the same experiment
  anyway, and separating them is cleaner:
  - **Tier 1 target:** a California or Cascadia track (densest OPERA coverage, and coverage there is
    routine rather than event-driven). Correctness comparison does not need an earthquake — a stable
    scene is arguably *better*, because any structured phase residual between SNAP-GSLC and OPERA-CSLC
    is then unambiguously an implementation difference rather than real deformation.
  - **Tier 3 target:** stays Venezuela (see B3 — now confirmed viable).

  **Also confirmed:** OPERA CSLC-S1 posts at **5 m east × 10 m north** — non-square, in the same
  aspect direction as the 3.4 × 7.5 m we recommended to ESA. That is direct external validation of
  the rectangular-pixel answer, independent of any comparison we run.

- **B1.2b — New Tier-2 reference discovered: OPERA DISP-S1.** A Level-3 *validated displacement time
  series* product (not just CSLC), available from ASF DAAC and the AWS Registry of Open Data. This is
  a stronger referee than MintPy-on-our-own-data for one specific claim — it is an independently
  produced, independently validated displacement time series over the same ground. Where DISP-S1
  covers a scene we process, comparing our MintPy-inverted time series against DISP-S1 tests the whole
  chain end to end against a product someone else already validated. Worth folding into B2.3 as a
  fourth input rather than running as a separate tier.
- **B1.3 — Comparison.** Two levels:
  - *Product level:* SNAP GSLC vs OPERA CSLC for the same acquisition. Resample onto a common grid,
    then compare amplitude (should agree closely) and **the complex phase difference** — which
    should be a smooth surface, not noise. Any structured residual is a real finding about one of
    the two implementations.
  - *Interferogram level:* SNAP-GSLC pair vs OPERA-CSLC pair. Use the metric set already built and
    trusted this week: phase-only coherence vs window, cross-coherence between the two
    interferograms, per-burst azimuth binning, phase-residue density. The measurement scripts exist
    (`scratchpad/cfmeasure.py` pattern) and are reusable as-is.
- **B1.4 — Publish** the comparison as a section in the explainer + a deck slide.

**Effort:** ~3–4 days total (dominated by the reader), less if A2.2 lands first.
**Value:** the strongest implementation-level evidence available, and it directly answers the
question ESA is implicitly asking ("does your geocode-first output match the reference one?").

---

## B2 — Tier 2: MintPy as the common referee

**Environment (the part the user correctly flagged as real work).** Findings from this box:
- No conda/mamba installed; system Python is 3.13 (too new for parts of the geospatial stack).
- **WSL2 Ubuntu is installed** (currently stopped) and **Docker Desktop is running**.
- MintPy on Windows natively is the painful path (GDAL/pyproj/h5py/PySolid via conda-forge).

Three options, in recommended order:

1. **WSL2 Ubuntu + Miniforge (recommended).** `conda-forge` MintPy install is well-trodden on Linux;
   WSL2 reads the Windows filesystem directly (`/mnt/e/Output/...`), so no data copying. Lowest
   friction, closest to how MintPy is actually used and documented. ~½ day including a smoke test on
   MintPy's own sample dataset.
2. **Docker.** Use a published MintPy image (or a small Dockerfile on `mambaorg/micromamba`) with
   `E:\Output` bind-mounted. Most reproducible and the easiest to hand to ESA or a colleague; slightly
   more friction for iterative work. ~½ day.
3. **Native Windows conda-forge.** Only if 1 and 2 are unavailable. Expect dependency pain.

Do **1 for development, 2 for the deliverable** (a Dockerfile is the artifact that makes the
comparison reproducible by someone else — which is most of the point).

**Work:**
- **B2.1 — Environment** per above, validated on MintPy's published sample stack (proves the install
  before our data is in the picture).
- **B2.2 — Ingestion.** MintPy ships `prep_snap.py` for SNAP-produced interferograms. **Verify what
  it actually expects** — this is the main unknown and must be checked, not assumed: probably
  geocoded ifg + coherence + `.dim` metadata with specific naming, likely developed against the
  classical `..._deb_TC` output rather than a GSLC product. Gaps here fall into two buckets:
  - trivial (naming/band conventions) → a small conversion script on our side;
  - structural (metadata MintPy needs that our GSLC products don't expose) → then the right answer
    is a **`MintPyExportOp`**, following the existing precedent of `StampsExportOp` and the PyRATE
    writer (`sar-io/.../pyrate/`). That would be a genuinely useful toolbox feature independent of
    this validation.
- **B2.3 — The referee experiment.** Same acquisitions, ≥ 5–8 epochs (MintPy needs a network, not a
  pair — this is a data-collection prerequisite worth starting early), three input stacks where
  available: SNAP-GSLC, SNAP-classical, ISCE/OPERA-derived. One MintPy configuration. Compare the
  output displacement time series: velocity fields, temporal-coherence masks, residual RMS.
- **B2.4 — Report** the three-way comparison.

**Effort:** B2.1 ~½ day; B2.2 ~1–3 days (depends entirely on what `prep_snap.py` needs — resolve
this early, it's the schedule risk); B2.3 ~2 days + the multi-epoch data; B2.4 ~½ day.
**Prerequisite:** Plan A's **A1** (N interferograms from one stack) makes producing a multi-epoch
GSLC network practical rather than N separate stack runs.

---

## B3 — Tier 3: physical validation (the real headline) — **GREEN LIGHT**

**Status changed 2026-07-27: this is no longer conditional.** The data hunt is done and every
ingredient exists. The open question was "is there anything to compare against?" — the answer is yes,
three independent finite-fault models plus published GNSS.

**The event (corrected facts — the tutorial had several of these wrong):** a **doublet**, not a single
shock — **Mw 7.2 at 22:04:33 UTC** followed by **Mw 7.5 at 22:05:11 UTC**, 39 s apart, on the
**San Sebastián fault**. Epicentre 10.435° N, 68.472° W; depths 20.3 and 10 km. Right-lateral
strike-slip, rupture ~200–210 × 30–40 km, max slip 3.6 m (USGS) / 4.49 m peak (Peking University).

**References now identified:**
- **Finite-fault slip models from three independent groups: USGS, INGV, and Peking University.** Three
  models matters more than one — the spread between them is an honest error bar to compare our
  residual against, instead of treating any single model as truth.
- **Published GNSS:** 7 stations processed via CSRS-PPP/OPUS. Station CCS1: **dE = −0.463 m,
  dN = −0.007 m, dU = +0.03 m**. Published DInSAR at 20 sites gives ~0.48 m westward and
  0.03–0.05 m uplift, ~30 cm in LOS.
- **An independently published interferogram exists:** ESA released its own Sentinel-1 interferogram
  of this event — using an **18 June / 25 June** pair. Note that is a *7-day* span bracketing the
  event, i.e. a **worse coseismic pair than ours** (23 Jun / 24 Jun, ~1 day). Our pair is the tighter
  one, which is a point worth making explicitly in the deliverable rather than leaving implicit.

### B3.0 — Scene geometry and LOS projection — **DONE (2026-07-27)**

Everything below is read from the interferogram product, not assumed.

| Quantity | Value | Source |
|---|---|---|
| Footprint | lon −69.206 … −68.156, lat 9.619 … 11.443 (115 × 202 km) | `IMAGE_TO_MODEL_TRANSFORM` |
| Grid | 8358 × 14516 @ 1.2566604e-4° | same |
| Pass / look | **ASCENDING**, right-looking | `PASS`, `antenna_pointing` |
| Incidence | 41.764° near … 45.965° far (mean **43.86°**) | `incidence_near/far` |
| Heading | **347.90°** | derived from mid-scene orbit **velocity** (ENU), 92 state vectors |
| **LOS unit vector (ground→sat)** | **E −0.6775, N −0.1452, U +0.7210** | derived, \|v\| = 1.000000 |

Two traps worth recording, both hit and corrected:
1. The product's `first/last_near/far_lat/long` corners are the **geocoded, axis-aligned map bounding
   box**, so differencing them yields a heading of exactly 180° — meaningless. The heading must come
   from the orbit velocity vector rotated into ENU. 347.90° is the expected value for S1 ascending.
2. `look_az = heading − 90°` is *already* the ground→satellite azimuth (257.9°, i.e. the satellite is
   **west** of the target, as required for an ascending right-looking pass). Applying a further sign
   flip inverts the LOS vector and silently flips the sign of every subsequent displacement.

**Independent validation of the geometry:** projecting the published CCS1 GNSS vector
(dE −0.463, dN −0.007, dU +0.030 m) onto this LOS gives **+0.336 m toward the satellite** (12.1
fringes at 2.77 cm). The published DInSAR result for this event is **~30 cm LOS** — agreement to
~10%, from completely independent inputs. The sign is physically right too: right-lateral slip moved
the station ~46 cm **west**, and the satellite is to the west, so range shortens.
**Caveat:** CCS1 (Caracas, ~66.9° W) lies *east* of our scene's eastern edge, so this validates the
convention and magnitude, **not** a co-located point comparison. Which of the 7 published stations
fall inside the footprint is still to be determined — that is the gate on B3.2.

### B3.0b — Near-field fringe rate, and why the cheap route fails — **DONE (2026-07-27)**

I attempted a shortcut: a N–S profile through the epicentre, coherence-weighted along-strike, then
**1-D unwrapped** — which would have given LOS displacement with no unwrapper at all. It produced a
clean-looking antisymmetric profile with a sign reversal at the epicentre latitude and a peak-to-peak
of 49.75 cm. **That number is an artifact and is retracted.** Two checks killed it:

1. **Stability sweep.** Across 12 combinations of block size (4/8/16/32 rows) and coherence threshold
   (0.20/0.25/0.35), peak-to-peak ranged **5.3 – 49.7 cm** (24.7 ± 17.7). A quantity that moves 10×
   with processing choices is not a measurement.
2. **Nyquist check at full resolution** (13.89 m rows, ±15 km about the epicentre, 2114 usable row
   steps) — the row-to-row wrapped phase step is:

   | percentile | rad/row | equivalent |
   |---|---|---|
   | p50 | 0.166 | 1 fringe / 0.53 km |
   | p90 | 0.844 | 1 fringe / 104 m |
   | p95 | 1.355 | 1 fringe / 65 m |
   | p99 | 2.661 | 1 fringe / 33 m |

   At the time this was read as an aliasing limit: *"a block of B rows aliases once `B·|Δφ| > π`, so
   every block size including B=4 aliases; averaging destroys the near-field fringes."*

   > **That inference was WRONG and is corrected here (2026-07-27, later the same day).** These are
   > *raw adjacent-pixel* differences, and at coherence ~0.23 they are dominated by **noise**, not by
   > the signal's gradient. Averaging suppresses noise rather than aliasing it. Measuring properly —
   > multilook first, then measure the block-to-block step — the step **falls** to a minimum at
   > **B = 8 (111 m)** and only rises beyond B ≈ 16. Aliasing would make it rise monotonically. Full
   > detail in `docs/superpowers/reports/2026-07-27-gslc-unwrapping-results.md` §7.1.

**Corrected consequences:**
- **Multilook to ≈ 8 × 8 (about 111 m) before unwrapping.** It raises the coherence estimate from 0.231
  to 0.671, removes the need for snaphu tiling entirely, and cuts runtime from 19 min to 64 s.
- **Tiling, not resolution, was the real hazard.** A full-resolution 24-tile unwrap disagreed with three
  independent multilooked solutions by ~18 cm across 93% of pixels, while those three agreed with each
  other to 0.8–1.8 cm. The full-resolution solution was rejected.
- The profile/1-D-unwrap shortcut remains **not viable** for amplitude — but for the original reason
  (it was unstable across processing choices, 5.3–49.7 cm), not because of aliasing.
- The along-strike coherent average (±64 columns ≈ 1.8 km) was still never validated, and the
  anisotropy check since run shows the phase rate is essentially **isotropic** (p95 ratio row/col 0.85),
  so there is no case for averaging one axis preferentially here.
- What survives: the sign reversal sat at lat **10.43 ± 0.01** in every long-run configuration, against
  a published epicentre of 10.435 — suggestive corroboration of the deformation field's location,
  though still not an amplitude measurement.

### Remaining work

- **B3.1a — Wrapped-domain comparison (no unwrapping needed; do this first).** Forward-model the slip
  models to LOS, **wrap to the same 2.77 cm ambiguity**, and compare fringe geometry and count against
  the observed wrapped interferogram. This sidesteps the unwrapping prerequisite and avoids importing
  unwrapping's error modes. Note B3.0b's constraint: compare at full resolution, do not pre-average.
- **B3.1b — Quantitative residual.** Needs the unwrapped product. **snaphu version resolved
  (2026-07-27):** `BatchSnaphuUnwrapOp.downloadSnaphu()` auto-fetches it, so this is a download rather
  than a build — **Windows 64-bit: snaphu v2.0.4**
  (`step.esa.int/thirdparties/snaphu/2.0.4/snaphu-v2.0.4_win64.zip`, 1.77 MB, verified reachable
  HTTP 200); Windows 32-bit and Linux/macOS: v1.4.2. Nothing is staged in `.snap/auxdata` yet. Route:
  `SnaphuExport` → `snaphu` → `SnaphuImport`, or `BatchSnaphuUnwrapOp` which handles the download.
- **B3.1c — Model acquisition + forward model.** The three published slip models must be fetched
  (USGS/INGV/Peking) and an Okada elastic-dislocation forward model applied. This is the largest
  single piece of remaining work in B3 and has no code in the repo today.
- **B3.2** Project the published GNSS vectors into LOS and compare at station locations — gated on
  station coordinates falling inside the footprint (see caveat above).
- **B3.3** Compare against ESA's published interferogram qualitatively, noting that their 18 Jun /
  25 Jun pair spans 7 days against our ~1 day.

**Effort:** low compute; the data hunting that was the risk is complete. **Value:** highest of all
three tiers, and the only one that speaks to *accuracy* rather than reproducibility.

**Two honest caveats to carry into it:**
1. **Scene coverage — corrected 2026-07-27 by reading the product geocoding.** An earlier note in this
   plan said the epicentre lay *east of* the scene. That was wrong: the interferogram's own
   `IMAGE_TO_MODEL_TRANSFORM` (origin −69.20599, 11.44309; step 1.2566604e-4°; 8358 × 14516) gives a
   footprint of **lon −69.206 … −68.156, lat 9.619 … 11.443**, ≈ 115 km E–W × 202 km N–S. The
   epicentre (10.435° N, 68.472° W) is **inside** it — 34.6 km from the eastern edge, 80.4 km from the
   western. So the epicentral zone *is* imaged, which strengthens B3 rather than qualifying it.
   The real coverage limit is different: the rupture runs ~200 km along strike and **leaves the scene
   to the east**, so the eastern half of the fault is unimaged. Figures should overlay the footprint on
   the fault trace so that is visible rather than buried.
2. The residual-ramp removal absorbs a scene-wide *linear* gradient by design, so GNSS comparison must
   be on the **relative** displacement field (station-to-station differences), not absolute LOS
   offsets. State this up front rather than discovering it in review. With a ~200 km rupture and a
   scene-scale ramp both present, this is a real interpretation constraint, not a formality.

**Honest caveat to carry into it:** our tutorial pair is 1-day, so it is nearly pure coseismic — good
for this comparison. But the residual-ramp removal absorbs a scene-wide *linear* gradient by design,
so a GNSS comparison must be interpreted on the *relative* displacement field (differences between
stations), not absolute LOS offsets. Worth stating up front rather than discovering during review.

---

## B4 — Correction-layer ablation: do the atmospheric/timing corrections actually help?

**The question:** of the correction layers available to a GSLC InSAR chain, which measurably improve the
interferogram, and by how much? Today every one of them is **off by default**, and nobody has measured
their contribution on our data.

### The full inventory (verified in code 2026-07-27)

There are **three** independent places corrections can be applied, which is itself worth writing down
because they overlap:

| Layer | Where | Switches | Default |
|---|---|---|---|
| **1. ETAD** (`S1-ETAD-Correction`) | on the **split SLC**, before geocoding | `troposphericCorrectionRg`, `ionosphericCorrectionRg`, `geodeticCorrectionRg`, `dopplerShiftCorrectionRg`, `geodeticCorrectionAz`, `bistaticShiftCorrectionAz`, `fmMismatchCorrectionAz` | all **false** |
| | | `sumOfRangeCorrections`, `sumOfAzimuthCorrections` | both **true** |
| **2. GSLC** (`GSLC-Terrain-Correction`) | during geocoding | `applySolidEarthTide`, `applyTroposphericCorrection` (Saastamoinen) | both **false** |
| **3. Interferogram domain** | after the ifg | `IonosphericCorrection` op (phase screens), `subtractResidualRamp` | off / off |

**Two overlaps that must not be double-counted:**
- ETAD's tropospheric layer and GSLC's `applyTroposphericCorrection` model the **same** delay. Enabling
  both double-counts it. ETAD is measured, GSLC's is a computed model — prefer ETAD where a product exists.
- ETAD's geodetic layers overlap GSLC's `applySolidEarthTide` the same way.
- Note also that ETAD's *defaults* are not "nothing applied": with both sums `true`, the total range and
  azimuth corrections — tropospheric and ionospheric delay included — are already applied. The
  individual switches exist to select a subset, which is exactly what an ablation needs.

### The confound that would invalidate the whole study

**`subtractResidualRamp` must be OFF for the ablation.** It fits and removes a low-order polynomial,
which is precisely the spatial form most of these corrections take (tropospheric delay and solid-Earth
tide are long-wavelength). With ramp removal on, the ramp estimator would absorb the very signal being
tested and every configuration would look identical. This is the single easiest way to get a
convincing null result out of this experiment.

Second confound: **corrections must be applied to BOTH acquisitions or to neither.** A differential
correction goes straight into the interferometric phase. Since `CreateStack` auto-geocodes a raw
secondary from the reference's stamps, the ablation must use the **all-GSLC** path (both legs geocoded
explicitly) — which makes this dependent on **Plan A's A1** for convenience, though a 2-product stack
works today.

### Design

Metrics — reuse the set already built and trusted this week, no new tooling: **phase residues / 10⁴ px**
(unwrappability), **5×5 local phase coherence**, **phase-only coherence vs window size** (the 1/n-noise
vs plateau discriminator), and near-field fringe rate.

Configurations, each run end-to-end with `subtractResidualRamp=false`:

| # | Configuration | Tests |
|---|---|---|
| 0 | baseline, no corrections | reference point |
| 1 | ETAD, both sums on | total ETAD benefit |
| 2 | ETAD, `troposphericCorrectionRg` only | tropospheric contribution |
| 3 | ETAD, `ionosphericCorrectionRg` only | ionospheric contribution (expect **small at C-band**) |
| 4 | ETAD, azimuth layers only (geodetic + bistatic + FM) | timing vs path-delay split |
| 5 | GSLC `applyTroposphericCorrection` only | model vs ETAD's measured delay (compare against #2) |
| 6 | GSLC `applySolidEarthTide` only | SET contribution |
| 7 | best of the above + `IonosphericCorrection` phase screens | does the ifg-domain estimator add anything |

**Data — no obstacle (corrected 2026-07-27).** ETAD needs **no manual download**: with `etadFile` unset,
`S1ETADCorrectionOp` searches the Copernicus Data Space for the ETAD product matching each acquisition
and downloads it to `<cache>/etad` (`S1ETADCorrectionOp:216-226` → `ETADSearch` → `DataSpaces`). The only
prerequisite is **Copernicus credentials stored in SNAP's `CredentialsManager`**; without them the search
cannot authenticate, and a genuinely missing product raises `ETAD product not found`.

So the ablation **can run directly on the Venezuela coseismic pair**, which is the better target — it is
the scene the deliverable is about. ETAD products also already sit on disk for **IW-Philippines** (the
pair `GSLCTopsETADTest` uses), **ETAD-Surat** and **SM-Nigeria**, which remain useful as an
offline/credential-free fallback and for cross-checking that an effect is not scene-specific.

### Honest expectations, stated in advance

Writing these down first so the result cannot be rationalised afterwards:
- **Tropospheric** should dominate the atmospheric terms — differential delay of several cm to a dm,
  i.e. multiple 2.77 cm fringes. Most likely to show a real effect.
- **Ionospheric at C-band should be minor.** TEC phase scales as 1/f; this is the term that becomes
  *first-order* at P-band (BIOMASS, ~12× larger), not at C-band.
- **Solid-Earth tide** is ~cm and very long-wavelength, so it is the term most likely to be
  indistinguishable from a ramp — and the one whose apparent effect would vanish if ramp removal were
  left on.
- **A null result is a legitimate and publishable outcome**: "for 1-day C-band pairs these corrections
  change the interferogram by less than X" is useful guidance, provided the confounds above were
  genuinely controlled.

**Effort:** ~1 day for the Philippines ablation given the data is local (8 configurations × one chain
each), plus ~½ day to write up. **Value:** turns seven off-by-default switches from folklore into
measured guidance, and directly informs what the tutorial should recommend.

---

## Sequencing

```
B1 (Tier 1, no env)      ── start here; cheaper if Plan A's A2.2 lands first
   │
B2.1/B2.2 (env + ingest) ── can run in parallel with B1; resolve prep_snap.py compatibility EARLY
   │                        (it is the main schedule risk in this plan)
B2.3  ── needs multi-epoch data + Plan A's A1
   │
B3    ── independent of both; start the data hunt now, it has the longest lead time
```

**Both lead-time items are now closed (2026-07-27):**
1. ~~Check OPERA CSLC-S1 coverage~~ → **done.** Coverage stops at Panama; Venezuela is excluded, so
   Tier 1 retargets to a California/Cascadia track. See B1.2.
2. ~~Look for GNSS/published models for Venezuela~~ → **done.** Three finite-fault models (USGS, INGV,
   Peking) plus published GNSS vectors all exist. B3 is a green light. See B3.

**Consequence for sequencing:** B3 was the item with the longest presumed lead time and it turned out
to have none, while B1's target moved. So **B3 is now the best first move**, not B1 — it needs no
reader, no Python environment, and no new downloads beyond the published models, and it produces the
strongest claim in the plan. Revised order: **B3 → B1 → B2**.

**One decision to make before B2 starts:** whether the deliverable is "we compared against MintPy
once" or "anyone can reproduce this comparison" — the latter justifies the Docker artifact and
possibly `MintPyExportOp`, and is the better use of the effort if this material is going to ESA.
