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

**Work:**
- **B3.1** Forward-model each of the three slip models to S1 LOS displacement on our grid; compare
  against our unwrapped GSLC interferogram. Report residual against all three, not the best one.
- **B3.2** Project the published GNSS vectors into LOS and compare at station locations.
- **B3.3** Compare against ESA's published interferogram qualitatively, noting the pair-span difference.

**Effort:** low compute; the data hunting that was the risk is complete. **Value:** highest of all
three tiers, and the only one that speaks to *accuracy* rather than reproducibility.

**Two honest caveats to carry into it:**
1. **The epicentre (10.435° N, 68.472° W) lies just east of our scene's eastern edge.** So we capture
   the rupture's western portion, not the epicentral maximum. This does not invalidate the comparison —
   the rupture is ~200 km long and we cover a substantial part of it — but any claim must be about the
   part of the fault we actually image, and the figures should show the scene footprint against the
   fault trace so this is visible rather than buried.
2. The residual-ramp removal absorbs a scene-wide *linear* gradient by design, so GNSS comparison must
   be on the **relative** displacement field (station-to-station differences), not absolute LOS
   offsets. State this up front rather than discovering it in review. With a ~200 km rupture and a
   scene-scale ramp both present, this is a real interpretation constraint, not a formality.

**Honest caveat to carry into it:** our tutorial pair is 1-day, so it is nearly pure coseismic — good
for this comparison. But the residual-ramp removal absorbs a scene-wide *linear* gradient by design,
so a GNSS comparison must be interpreted on the *relative* displacement field (differences between
stations), not absolute LOS offsets. Worth stating up front rather than discovering during review.

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
