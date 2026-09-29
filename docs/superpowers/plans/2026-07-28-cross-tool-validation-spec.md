# Cross-tool validation specification

**Purpose.** Establish, with evidence a sceptic can re-run, that the Microwave Toolbox produces
InSAR products that agree with the reference open-source implementations of the same algorithms —
and, where they disagree, that we know why and can defend our choice.

**The audience is not the compiler.** The goal is trust in the user community and at ESA. That
changes what counts as done: a passing test is not evidence, a screenshot is not evidence. Evidence
is *an independently-computed result agreeing with ours, produced by software we did not write, on
data anyone can obtain, by a procedure anyone can repeat.*

---

## 0. Principles

These are not aspirational; each was learned by getting it wrong during the GSLC work of July 2026,
and each has a named failure attached.

1. **Internal consistency is not correctness.** A re-wrap test passed *perfectly* on a snaphu
   solution that was wrong by 18 cm over 93% of the scene, because snaphu's output satisfies it by
   construction. Only agreement with an independently-computed solution settled it.
2. **No number is reportable until two independently-computed solutions agree on it.** Three
   multilook factors agreeing to 0.8–1.8 cm is evidence. One careful run is not.
3. **Match conventions before comparing, and refuse to run if they disagree.** ISCE3 defaults to
   `flatten=True, reramp=True`; we default to the opposite on both axes. A default-vs-default
   comparison would have produced noise indistinguishable from a real defect.
4. **Use a metric that can see the quantity under test.** Residue density and 5×5 coherence are
   measures of *local* phase noise and are blind to a smooth one-cycle-per-scene atmospheric field.
   An ETAD ablation scored with them showed nothing; the same correction moved the circular σ of the
   32×32 multilooked phase by −8.3%.
5. **Compare terms, not just end products.** Where the peer can emit intermediate phase screens
   (ISCE3's `carrierPhaseRaster` / `flattenPhaseRaster`), compare those. End-product differencing
   lets independent errors alias into one another.
6. **Band existence is not data.** Assert non-zero pixel content. A `CreateStack` bug produced
   full-size, correctly-named, entirely empty secondary bands, and was reported as success.
7. **State the scope of every figure.** "−5.6% residues" meant the *geometric* contribution only,
   because the run had `outputPhaseCorrections=false`. Unscoped numbers become folklore.

---

## 1. Harness architecture

Reuse comes from keeping the comparison layer ignorant of the engines.

```
engines/
  snap/       native Windows gpt — the artifact under test, NOT containerised
  isce3/      Docker: conda-forge isce3 + COMPASS
  dolphin/    Docker: dolphin
  insardev/   Docker: insardev_pygmtsar + GMTSAR binaries
  raider/     Docker: RAiDER
  mintpy/     Docker: MintPy
compare/      host-side Python. Reads georeferenced rasters. Knows no engine.
cases/        one YAML per comparison case
reports/      one accumulating report per phase
```

**Why Docker for the peers and native for SNAP.** ISCE3 (conda-forge) and `insardev_pygmtsar`
(GMTSAR binaries) have conflicting dependency stacks; one container each avoids that entirely, and a
compose file is an artifact we can hand to ESA. SNAP stays native because **the native Windows build
is what we ship and therefore what must be under test** — containerising it would validate a
different artifact.

**Data is mounted once, from one place, at identical in-container paths**, so no case file differs by
engine and nothing is copied:

```
E:\TestData          -> /data   (read-only, every engine)
E:\Output\harness    -> /work   (read-write, per-engine subdirectory)
```

Docker Desktop runs on WSL2, so a Windows bind mount and `/mnt/e` share the same I/O path — there is
no performance argument either way. The argument is isolation and shareability.

### Case file contract

```yaml
case: gslc_s1_tops_venezuela
feature: GSLC
scene:      { ... resolvable from /data or a public download recipe ... }
dem:        /data/dem/copernicus30_venezuela.tif    # ONE staged file, passed to every engine
grid:       { crs: EPSG:32619, spacing: [5.0, 10.0], origin: [...] }   # identical for all
conventions:                       # compared across engines BEFORE processing; mismatch = abort
  flattened: true
  azimuth_carrier: restored
engines:    [snap, isce3, insardev]
metrics:    [amplitude_ratio, complex_phase_difference_smoothness, residue_density, ...]
tolerances: { ... with a stated justification per number ... }
```

Three hard requirements, each from a specific failure:
- **One staged DEM file**, passed explicitly. Not "Copernicus 30 m" by name to each tool.
- **Identical output grid** — CRS, posting and origin. Rectangular cells are supported now, so
  OPERA's 5 × 10 m can be matched exactly.
- **Conventions declared and cross-checked**, and the run aborts on mismatch rather than silently
  comparing incompatible products.

---

## 2. Phases

One phase per feature. Each is independently valuable and produces a standalone report; later phases
reuse the harness and the metric library, not the earlier results.

### Phase 0 — Harness infrastructure
Compose file, the three initial engine images, the host comparator, one smoke case per engine run on
that engine's own published example data (proves the environment before our data is involved).
**Deliverable:** `reports/phase0-harness.md` + a compose file a third party can run.

### Phase 1 — GSLC vs ISCE3/COMPASS and InSAR.dev
The algorithmic peer. Settled already: CSLC-S1 is `flatten=True, reramp=True`
(`COMPASS/s1_geocode_slc.py:215`, `defaults/s1_cslc_geo.yaml:50`).
- **1a Term-by-term** — our carrier and flattening phase screens against ISCE3's
  `carrierPhaseRaster` / `flattenPhaseRaster`. Requires §3 below. This is the strongest available
  test and isolates each model.
- **1b Product level** — amplitude agreement, and the complex phase difference, which must be a
  smooth surface rather than noise.
- **1c Interferogram level** — coherence vs window, residue density, cross-coherence between the two
  interferograms.
- **1d The carrier question** — we measured that restoring the azimuth carrier leaves 141/161 rad
  per-burst on a *cross-platform* S1A×S1D pair; OPERA re-ramps and feeds DISP-S1 successfully on
  same-track repeats. Test both pair types both ways. If our result holds, carrier-free is *more*
  robust than the reference default and that is a finding to publish, not a deviation to excuse.
- **1e InSAR.dev** as an independent third lineage (GMTSAR-derived, not ISCE-derived).

### Phase 2 — Phase Linking vs Dolphin
Arguably higher value than Phase 1: phase linking has far more algorithmic freedom (SHP selection,
EVD vs EMI, mini-stack handling, reference-epoch choice) and therefore more room to differ subtly.
Dolphin is sensor-agnostic and consumes a coregistered SLC stack, so **a CSLC-compatible GSLC stack
can be fed to it directly** — giving an independent estimator on our own products.
Compare: per-pixel temporal coherence, linked phase per epoch, SHP-set overlap, and the resulting
displacement time series.
**Prerequisite:** the CSLC-compatible profile from §3, because phase linking assumes small
within-window phase differences and our unflattened default violates that.

### Phase 3 — ETAD vs s1etad-tools
Independent reader/applier of the same auxiliary product. Compare the correction layers themselves
(a pure data-interpretation check, no SAR processing), then the applied result. Establishes whether
our range-delay phase screen matches an independent implementation — currently validated only
self-consistently and by sign argument.

### Phase 4 — Terrain correction / RTC vs sarsen and OPERA RTC-S1
Backscatter, not phase: γ⁰/σ⁰ agreement, geolocation, layover/shadow masks. RTC-S1 is also
isce3-based, so it shares Phase 1's environment.

### Phase 5 — Tropospheric correction vs RAiDER
Weather-model delay against our empirical/Saastamoinen models. Directly relevant: the July 2026
ablation showed GSLC's Saastamoinen model *degraded* an interferogram (mean coherence
0.2850 → 0.2258), which needs an independent check before we either fix or deprecate it.

### Phase 6 — Time series: MintPy as common referee
Not a peer — a *referee*. Push SNAP-GSLC, SNAP-classical and an ISCE3-derived stack of the same
acquisitions through one MintPy configuration. Differences in the output time series are then
attributable to the interferogram source with the inversion held constant. Also the natural place to
compare against OPERA **DISP-S1**, an independently validated displacement product.

### Phase 7 — Physical validation
The only tier that speaks to *accuracy* rather than reproducibility, and the strongest trust
argument available. For the Venezuela Mw 7.2/7.5 doublet: forward-model the USGS, INGV and Peking
finite-fault solutions to LOS and compare against our unwrapped field; report the residual against
**all three**, so the spread between published models serves as the error bar. Plus the published
GNSS vectors projected into our verified LOS geometry (E −0.6775, N −0.1452, U +0.7210).

---

## 3. Prerequisite work item: separable phase terms

Blocks Phases 1a and 2. `GSLCGeocodingOp` bakes its convention into the product; ISCE3 instead emits
the carrier and flattening phase as separate rasters, which is why its default is tolerable.

Add optional output bands for the **azimuth-carrier phase** and the **flattening phase**, mirroring
ISCE3's `carrierPhaseRaster` / `flattenPhaseRaster`. This:
- makes the convention **reversible** after the fact instead of baked in;
- enables the term-by-term comparison of Phase 1a;
- removes the need to win the defaults argument at all — a user can move either term in or out.

Then add a documented **CSLC-compatible profile** (`outputFlattened=true`,
`outputAzimuthCarrier=true`) for product exchange and for feeding Dolphin. Keep the current defaults
for the SNAP-internal chain: the carrier default is measurement-backed.

---

## 4. Report format

Two tiers, because they have different readers and different obligations.

### 4a. Internal validation report (during development)

One per phase, in `docs/superpowers/reports/`. **Rewritten freely as the work proceeds** — a figure
changing during validation is the process working, not an event, and a running ledger of every
intermediate number would make ordinary iteration look like instability. Each report states the
*current* position:

1. **Claim** — one sentence a non-specialist can check.
2. **What was compared** — engines, versions, commit hashes, container digests.
3. **Convention alignment** — the settings on both sides, and the evidence they match.
4. **Metrics with scope** — what each number does *not* cover.
5. **Result** — agreement, or disagreement with a diagnosis.
6. **Open questions** — what is not yet settled, and what would settle it.
7. **Reproduction** — exact commands.

Where a headline figure changes materially, note it as *superseded* so a reader holding an older copy
knows not to rely on it. That is a courtesy to colleagues, not a formal record.

### 4b. Published deliverable (released to users or ESA)

Release notes, user documentation, ESA reports, conference material. Once a number is out, someone
may have processed data or made a decision on the strength of it. So a published claim that later
proves wrong requires an **explicit, visible retraction** — stating what the earlier figure was, what
it is now, and why it changed. Silently editing a published number is the one thing that would
actually damage trust, because it makes every other number unverifiable.

The threshold is publication, not certainty: internal work iterates without ceremony; published work
is amended on the record.

**Definition of done for a phase:** an independent party with the compose file, a public scene and
the case YAML reproduces the headline number within the stated tolerance.

---

## 5. Trap registry

Carried forward so each phase does not re-learn them. All observed, not hypothesised.

| Trap | Consequence |
|---|---|
| Reading a raster mid-write | `gpt` pre-allocates full-size files; a partial read looks like all-zero data |
| Judging a chain by exit code | An empty interferogram exits 0 with correctly-named full-size bands |
| Metrics blind to the quantity | Residue counts cannot see a smooth atmospheric field |
| Convention mismatch | Flattened vs unflattened, or re-ramped vs carrier-free, compares as noise |
| Grid/DEM mismatch | Differences dominated by resampling, not by the algorithm |
| Stale Maven artifacts | Downstream module resolves an old jar from `~/.m2`; convincing but stale results |
| Renamed intermediates | ETAD matches products by timestamps *in the filename*; a rename fails opaquely |
| Coarse multilooking | Aliases steep near-field fringes; measure the block-to-block step and take the minimum |
