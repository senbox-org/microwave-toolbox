# Microwave Toolbox JavaHelp Overhaul — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Microwave Toolbox help from a stale parameter reference into help that is correct, fully reachable, explains the SAR concepts behind each tool, and cites the foundational literature.

**Architecture:** Four layers.
1. A structural integrity checker (`tools/javahelp/check_javahelp.py`) that runs in CI and keeps the plumbing from rotting again.
2. Corrections to existing pages.
3. A new **SAR Concepts** branch, hosted in the `microwavetbx-rcp` helpset, which operator pages link to through a standard footer (Background / Prerequisites / Next step / References).
4. Parameter tables generated from operator metadata, so they can't drift from the code.

**Tech Stack:**
- JavaHelp 2.0 (`help.hs`, `map.jhm`, `toc.xml`) in NetBeans modules, registered with `@HelpSetRegistration` (`eu.esa.snap.netbeans.javahelp.api`). The annotation processor also builds the `JavaHelpSearch` index.
- `layer.xml` `helpId` attributes link actions to pages.
- Python 3 (stdlib only) for the tooling.
- HTML 3.2-level markup: the JavaHelp viewer has no CSS3 or JS, and images must be PNG/JPG/GIF.

**Spec:** `docs/reviews/javahelp-2026-11-12/README.md`. Per-domain evidence with file:line is in `review_insar.md`, `review_sarproc.md`, `review_pol_s1.md` and `review_readers.md` in the same folder.

## Global Constraints

- Do not `git commit` or `git push`. The user reviews and commits. Each task ends at "ready for review".
- Terminology: write "Reference"/"Secondary", never master/slave, in prose. Literal legacy identifiers (`MultiMasterInSAR`, `_mst`/`_slv` band tokens, operator aliases) stay literal, in `<code>`.
- Every file and link reference is case-sensitive. The help ships inside JARs.
- Cross-module links use `nbdocs://<OpenIDE-Module code name>/<resource path>`. The code name is `groupId` + `.` + `artifactId` with `-` replaced by `.`, e.g. `eu.esa.microwavetbx.sar.op.insar.ui`.
- TOC entries live under the shared root `<tocitem text="Sentinel Toolbox Application">`, which is merged with snap-desktop. Never rename that root.
- New help pages follow the existing page skeleton: the `snap_header.jpg` header table, `style.css`, and `<title>` inside `<head>`.
- Citations go in as full references (authors, year, title, venue, volume(issue), pages, DOI where one exists). Never cite anything marked "(verify)" in the review until it has been checked against the publisher or DOI.
- Units are stated for every physical parameter; defaults are copied from code, not from old docs.
- `python -I tools/javahelp/check_javahelp.py` must exit 0 at the end of every task from Phase 1 on.

## Review Focus

- **Case-only mismatches** that work on Windows and break in the JAR/Linux (`.PNG` vs `.png`, `importRCM` vs `ImportRCM`). The checker uses exact-case matching; keep it that way.
- **Help buttons that resolve but open the wrong page** (ALOS-2/4 → ALOS-1). The checker cannot see this. Task 1.3 has an explicit table to check against.
- **Merged-helpset ID collisions.** A map ID defined in two helpsets silently picks one. The checker's `dup:` rule covers it; new concept pages must use a `Concept_` ID prefix.
- **Exposing stale pages through the TOC.** Pages with known P0 errors (general/Sentinel1.html, the rcp tutorials) stay out of the TOC until Phase 2 or 3 fixes them. Instead they are allowlisted with a reason.
- **Regenerated parameter tables overwriting hand-written guidance.** The Phase 4 generator only replaces content between `<!-- params:begin -->` and `<!-- params:end -->` markers.

---

## Phase 1 — Help plumbing (structural; no prose rewriting)

**Status 2026-11-12: DONE.** Checker reports 0 errors (was 56) with 8 reasoned allowlist entries; all touched modules build. Remaining manual check is Step 1.5.3 (click-through in SNAP). Deviations from the original task text are recorded under each task as *As built*.

Exit criterion: `check_javahelp.py` reports 0 errors, with only reasoned allowlist entries, and every reader and operator Help button opens the correct page.

### Task 1.1: Help integrity checker

**Files:**
- Create: `tools/javahelp/check_javahelp.py`, `tools/javahelp/allowlist.txt`, `tools/javahelp/README.md`

**Interfaces:**
- Produces: CLI `python -I tools/javahelp/check_javahelp.py [--repo DIR] [--external DIR…] [--only cat,…]`. Exit code 0 means clean.
- Error categories: `registration`, `map`, `toc`, `orphan`, `helpid`, `link`, `html`.
- Allowlist line format: `category|key|reason`.

- [x] Step 1: Implement the seven checks described in the module docstring. Matching is exact-case (`os.listdir` per path component). Externals default to sibling `snap-desktop`, `snap-engine` and `optical-toolbox`. Java helpIds inside `//` comments are ignored.
- [x] Step 2: Run on the unmodified tree. Expected: about 56 errors, including `[registration] 3`, `[helpid] 3` (rstb-io), `[orphan] 21`, `[link] 26` and `[html] 2`. This baseline is the failing test for Tasks 1.2–1.5.

### Task 1.2: Helpset registration

**Files:**
- Create: `microwavetbx-rcp/src/main/java/eu/esa/microwave/dat/docs/package-info.java` with `@HelpSetRegistration(helpSet = "help.hs", position = 4900)`
- Create: `sar-op-analysis-ui/src/main/java/eu/esa/sar/ocean/docs/package-info.java` with `@HelpSetRegistration(helpSet = "help.hs", position = 5330)`
- Delete: `sar-io-gdal/src/main/resources/eu/esa/sar/iogdal/helpset.xml` and its `JavaHelp` folder in `sar-io-gdal/.../layer.xml`. The pages live in, and are registered by, sar-io.
- Modify: `sar-op-analysis-ui/.../ocean/docs/toc.xml`: add a "Radar → Ocean Applications → Ocean Swell Polar View" entry targeting `polarView`.
- Delete: `sar-op-analysis-ui/.../ocean/docs/wave/ProjectView.html`, a stale duplicate of the rcp Project View page.
- Modify: `OceanSwellTopComponent.java`: `getHelpCtx()` returns `new HelpCtx("polarView")`.

*As built:* `sar-op-analysis-ui/pom.xml` gained a direct `org-openide-util-ui` dependency (needed for `HelpCtx`; the nbm plugin rejects transitive use). `ProjectsToolView.getHelpCtx()` also returns `ProjectView` (Task 1.4).

- [x] Step 1: Make the edits.
- [x] Step 2: Run `python -I tools/javahelp/check_javahelp.py --only registration`. Expected: 0.
- [x] Step 3: Build, then confirm the generated layers: `mvn -q -o -pl microwavetbx-rcp,sar-op-analysis-ui,sar-io-gdal -DskipTests package`. Then check that `target/classes/META-INF/generated-layer.xml` contains `eu-esa-microwave-dat-docs.xml` and `eu-esa-sar-ocean-docs.xml`, and that `target/classes/eu/esa/microwave/dat/docs/JavaHelpSearch/` exists.

### Task 1.3: Help IDs on actions

**Files:**
- Modify: `rstb/rstb-io/src/main/resources/org/csa/rstb/io/layer.xml:11,20,29`
- Modify: `sar-io/src/main/resources/eu/esa/sar/io/layer.xml:21,30,157`
- Modify: `sar-io-gdal/src/main/resources/eu/esa/sar/iogdal/layer.xml:11,20,47`
- Modify: `sar-io/src/main/javahelp/eu/esa/microwavetbx/sar/io/docs/map.jhm` and `toc.xml`

| Action | Current helpId | New helpId → page |
|---|---|---|
| RADARSAT-1 / -2 / RCM | `importRadarsat1` / `importRadarsat2` / `importRCM` | `ImportRadarsat1` / `ImportRadarsat2` / `ImportRCM` (already mapped) |
| ALOS-2 CEOS, ALOS-2 GeoTIFF | `ImportAlosPalsar` | `ImportAlos2` |
| ALOS-4 CEOS, ALOS-4 GeoTIFF | `ImportAlosPalsar` | `ImportAlos4` → new mapID pointing at `readers/ImportAlos2.html` until Task 7.2 adds a dedicated page |
| TanDEM-X | `ImportTerraSarX` | `ImportTanDEMX` (already mapped) |
| EOS-04 COG | `importRISAT1` | `ImportEOS04` → new mapID pointing at `readers/ImportRISAT.html` until Task 7.2 |

*As built:* ALOS-4/EOS-04 are **not** added to the TOC yet (they would open another mission's page); they get TOC entries with their own pages in Task 7.2. The `ERS 1/2 CEOS` action now opens `ImportERSCeos`. The checker's new SNAP-collision rule found `importEnvisatProduct` and `importErsProduct` defined by both sar-io and snap-help (MERIS/ATSR pages), so sar-io's IDs were renamed to `ImportEnvisatAsar` / `ImportErs1`.

- [x] Step 1: Make the edits.
- [x] Step 2: Run `--only helpid,toc,map`. Expected: 0.

### Task 1.4: Orphans — map and TOC

**Files:**
- Modify: `sar-op-insar-ui/.../insar/docs/toc.xml`, mirroring the menus in `sar-op-insar-ui/.../layer.xml`:
  - "Interferometric → Phase Linking → Phase Linking" (`PhaseLinkingOp`)
  - "Interferometric → PSI/SBAS → SBAS Inversion" (`SBASInversionOp`)
  - "Coregistration → Stack Tools → Principal Component Analysis" (`PCA`)
  - Remove the duplicate "Create Stack" entry under Stack Tools.
- Modify: `jlinda/jlinda-nest-ui/.../docs/toc.xml`: add "Interferometric → jLinda InSAR Library" (`InSAR`).
- Modify: `rstb/rstb-op-soil-moisture-ui/.../docs/map.jhm` and `toc.xml`: map `Hallikainen` and `IEM-LUT`, and add them as children of the dielectric-model and IEM entries.
- Modify: `sar-io/.../docs/toc.xml`: add `ImportERSCeos` under ERS.
- Delete: `sar-io/.../readers/ImportImageIO.html` (reader not registered) and `sar-io/.../readers/ImportEnvi.html` (ENVI is a SNAP-core reader documented there).
- Modify: `microwavetbx-rcp/.../docs/map.jhm` and `toc.xml`:
  - map `ProjectView` and put it in the TOC;
  - make `ProjectsToolView.getHelpCtx()` return `new HelpCtx("ProjectView")`;
  - delete `visat/WorldMap.html` (a NEST/DAT leftover; the World View is documented by SNAP core).
- Allowlist the stale pages, each with reason `stale-until-phase-2/3`: `general/Definitions.html`, `tutorials/*.html`, and `sar/docs/general/*.html`. They are mapped so their links resolve, but stay out of the TOC.

*As built:* `ImportEnvi.html` was kept and mapped under Generic Formats (SNAP core has no ENVI reader page). `ImportImageIO.html` was deleted, and its link on SupportedProducts unlinked. The legacy rcp and `sar/docs/general` pages got map IDs prefixed `microwave…` / `tutorial…` to avoid colliding with SNAP's `Overview`/`Definitions`. The TOC fixed the typo "Hyrbid".

- [x] Step 1: Make the edits.
- [x] Step 2: Run `--only orphan,toc,map`. Expected: 0.

### Task 1.5: Links, images and page structure

**Files:** every page listed under `[link]` and `[html]` in the checker baseline.

| Defect | Fix |
|---|---|
| `images/*.PNG` in DPRBI, DPRSI, DpDecomposition, DpFactorization | `.png` |
| `../general/Overview.html` on 8 polarimetric pages | Point the header link at the SNAP overview (`nbdocs://org.esa.snap.snap.help/…/SnapOverview.html`), as the sibling polarimetric pages already do. |
| SnaphuImportOp `nbdocs://org.jlinda.jlinda.nest.ui/org/jlinda/jlinda/nest/…` | `…/org/jlinda/nest/docs/operators/SnaphuUnwrapping.html` |
| CreateStackOp `nbdocs://eu.esa.microwavetbx.sar-op-sar-processing-ui/…` | `nbdocs://eu.esa.microwavetbx.sar.op.sar.processing.ui/…` |
| terrain_correction.html `…sar.processing.ui/eu/esa/sar/insar/docs/…` | `…/eu/esa/sar/sar/docs/…` |
| SupportedProducts `geotiff//org/…` | Single slash |
| TimeSeries.html `file:///C:\Users\lveci\…clip_image001.png` | Remove the MS-Word paste artefacts |
| BatchSnaphuUnwrapOp, PyRateExportOp: 2 `<title>` | Keep the correct one |
| jlinda `InSAR.html` `<head>` after `</body>` | Restructure into valid `<html><head><title>…</title></head><body>` |

- [x] Step 1: Make the edits. The checker also gained "`<head>` after `<body>`" and "multiple `<html>`" rules.
- [x] Step 2: Run the full checker. Expected: `0 error(s)`.
- [ ] Step 3: Smoke test in SNAP: start SNAP from the built cluster, open Help → Contents, and confirm the new nodes are present. Press F1 or the Help button in the Radarsat-2, ALOS-2, TanDEM-X and Phase Linking dialogs; each opens its own page.

### Task 1.6: CI gate

**Files:**
- Modify: `Jenkinsfile`: add a stage `Help integrity` that runs `python3 -I tools/javahelp/check_javahelp.py --external /dev/null`. It runs in local-only mode, so external nbdocs links are skipped when siblings are absent.
- Create: `tools/javahelp/README.md`, covering how to run it, what each category means, and the allowlist policy (every entry needs a reason and a phase that removes it).

*As built:* `Jenkinsfile` was **not** modified. It only triggers the external `snap-ci-nightly` job, so wiring the gate is the CI owners' call. The README documents the `--external none` command (about 15 s).

- [x] Step 1: Make the edits. Skip the Jenkins stage if the CI agents have no python3, and note that in the README.

---

## Phase 2 — P0 factual corrections

**Status 2026-11-12: DONE.** Tasks 2.1–2.4 were implemented and passed review (2.1 needed one fix round). A final cross-page review found 1 Critical and 3 Important problems; all were fixed and re-reviewed. Checker reports 0 errors. Deferred minors and the Phase-8 code items are in the SDD ledger, `.superpowers/sdd/2026-11-12-javahelp-overhaul-plan/progress.md` (local, git-ignored).

Each item is a page edit that is checked against the cited code line. Group them into one task per helpset so a reviewer can diff each domain. Evidence and line numbers are in the spec, §2.

### Task 2.1: Polarimetry and soil-moisture corrections

| Page | Fix |
|---|---|
| `PolarimetricDecompositionOp.html` | Pauli: Red \|HH−VV\|² = double-bounce; Green \|HV\| = volume; Blue \|HH+VV\|² = surface / odd-bounce. Document Huynen, Yang, Krogager, Cameron, H-α Dual Pol and `applyOrientationAngleCorrection`. |
| `FaradayRotationCorrectionOp.html` | Show the Bickel-Bates estimator the code implements. Remove the Ω-band promise, or wire it up in Phase 8. |
| `PolarimetricChangeDetectionOp.html` | Explain the effective ENL after window averaging. |
| `DualPolY3DecompositionOp.html`, `DualPolRatioIndicesOp.html`, `PolarimetricParametersOp.html` | Fix the citations after checking them. |
| `OrientationAngleCorrectionOp.html` | Document `outputOrientationAngle`. |
| `PolarimetricSpeckleFilterOp.html` | Fill in the Lee Sigma descriptions, the window range 5–17, and the non-local parameters. |
| `IEM-LUT.html` | The dielectric constant is dimensionless (not "Farad/m"). |
| `IEMHybrid.html` | Use the `$SNAP_HOME/etc/snap.properties` path. |

### Task 2.2: Sentinel-1 / TOPS / orbit corrections

| Page | Fix |
|---|---|
| ETADDeburst | Use the layer names from code. |
| SpectralDiversity | Cloud workflow: `doNotWriteTargetBands=true`, shift fields `overallRangeShift`/`overallAzimuthShift`. |
| S1ETADCorrection | Describe the current dialog, where both options can be selected. |
| AzimuthShift | Document its parameters (the page says "no parameters"). |
| TOPSARSplit | Document `wktAoi`. |
| ESD | Document the 4 optimisation parameters. |
| Back-Geocoding | Update the DEM list; the default is Copernicus 30 m. |
| ApplyOrbitFile | Add the KOMPSAT-5 and BIOMASS orbit types; fix "UTM time" and the GNSS hub wording. |

### Task 2.3: InSAR corrections

| Page | Fix |
|---|---|
| SnaphuExport | `99999` is the line length (number of columns). |
| MultiReference | Real output: N−1 consecutive pairs; not consumed by Multi-Reference InSAR. |
| Warp | Describe the w-test significance level; default order 2; TRI/BICUBIC2. |
| PhaseToElevation | Title, remove "Stitch", fix "Schwäbisch". |
| Coherence vs Interferogram | Explain the bias-correction and topo-removal difference. |
| Interferogram | Complex multilook exists; correct the flat-earth explanation; correct the orbit-degree default. |
| RangeFilter | Oversampling default 1. |
| PhaseLinking | Attribute EVD to Fornaro 2015 / Cao 2015. |
| Goldstein | Document all 9 parameters, and that adaptive α (Baran 2003) is on by default. |
| CoherenceOp | Document all 16 parameters. |
| SNAPHU Export | Document all 9 parameters. |

### Task 2.4: SAR processing, calibration, feature extraction and reader corrections

| Page | Fix |
|---|---|
| GSLCGeocoding | Remove Thermal-Noise-Removal from the SLC/GSLC recipe. Add a warning to both TNR pages: "converts complex data to intensity — not for InSAR/GSLC". |
| general/Sentinel1.html | GRD products are *not* noise-subtracted; S1A, S1C, S1D. Fix this in the page before Phase 3 replaces the page. |
| Copernicus DEM text | The datum is EGM2008. Also file the code issue in snap-engine (Phase 8). |
| Aster correction text | Correct it to match what the code does. |
| Apply-EGM defaults | Explain why they differ between operators. |
| GeolocationGrid, SARSimulation | Time correction is R/c. |
| OilSpill | Background window in km. |
| TC tutorial | σ0 is dimensionless. |
| WindField | Supported sensors are ERS, ENVISAT, S1 and RS2. |
| Report paths | `~/.snap/var/log`. |
| BandSelect XML example | `selectedPolarisations`, `selectedSubImages`. |
| SpeckleFilter | Remove "Edge Threshold"; document Lee Sigma, ENL and IDAN. |
| MultiTemporalSpeckle | Correct the window sizes. |
| Small 2011, Jezek, Le Toan, CMOD5 | Fix the citation errors. |
| ImportQPSSAR | Rewrite for the iQPS X-band constellation. |
| ImportStriX | SLC is CEOS; GRD is XML + GeoTIFF. |
| Reader page titles and headers | Fix the copy-paste titles and headers (spec readers §P0-2). |

---

## Phase 3 — SAR concept layer

**Status 2026-11-13: DONE.** 20 concept pages, a glossary and a bibliography, plus the extra pages the user chose (3b: coregistration, resampling, offset tracking, polarimetric calibration, multitemporal, quality control, phase linking, GSLC, terrain correction, RTC; 3c: interferogram formation, stack design, InSAR error budget, polarimetric matrices). There are 8 workflows, `getting_started.html`, and footers on 124 operator pages. Each new page was independently reviewed and fix-rounded, and all are mapped under Radar > SAR Concepts.

Host the pages in `microwavetbx-rcp/src/main/resources/eu/esa/microwave/dat/docs/concepts/`. Map IDs use the `Concept_` prefix. TOC: "Sentinel Toolbox Application → Radar → SAR Concepts".

Each page:
- is 1–2 screens long;
- starts with a "Why it matters" lead;
- has at least one PNG diagram (generated from SVG with a script kept in `tools/javahelp/figures/`);
- gives typical numbers for S1, TSX, ALOS-2 and NISAR;
- ends with a "Used by" list of operator links and a References section.

### Task 3.1: Page template and operator footer

- Create `concepts/_template.html` (header, lead, body, "Used by", References).
- Define the operator footer block, delimited by `<!-- footer:begin -->` … `<!-- footer:end -->`: Background (concept links), Prerequisites, Next step, References.
- Extend the checker with an `education` category. Every operator page must have the footer and at least one `Concept_` link. This check is a warning until Phase 3 ends, then becomes an error.

### Task 3.2: Priority-1 concept pages

Write these seven pages, with content from spec §6 and topic outlines in the review reports:

| Page | Covers |
|---|---|
| `Concept_SARFundamentals` | SAR basics |
| `Concept_Geometry` | Geometry and geometric distortions |
| `Concept_Radiometry` | Radiometric quantities and calibration, including dB vs linear |
| `Concept_Speckle` | Speckle and multilooking |
| `Concept_InSAR` | InSAR fundamentals |
| `Concept_TOPS` | S1 TOPS primer, TOPS coregistration and ESD |
| `Concept_Polarimetry` | Polarimetry primer |

Add the footer to every operator page that uses each concept.

### Task 3.3: Priority-2 concept pages

| Page | Covers |
|---|---|
| `Concept_HeightReferences` | DEMs and height references |
| `Concept_Coherence` | Coherence |
| `Concept_Unwrapping` | Phase unwrapping |
| `Concept_Displacement` | Phase to displacement: LOS, sign convention, reference point |
| `Concept_Atmosphere` | Atmosphere and ionosphere |
| `Concept_Decompositions` | Polarimetric decompositions |
| `Concept_DualCompactPol` | Dual-pol and compact-pol |
| `Concept_Wishart` | Wishart statistics and classification |

### Task 3.4: Priority-3 pages and navigation

- **Concept pages:**
  - `Concept_TimeSeries` (PS/SBAS/DS)
  - `Concept_Ocean`
  - `Concept_SoilMoisture`
  - `Concept_ChangeDetection`
  - `Concept_OrbitsETAD`
- **Workflow cookbooks** (`Workflow_*`):
  - GRD → γ0T
  - S1 DInSAR
  - stripmap DInSAR
  - GSLC
  - time series
  - DEM generation
  - offset tracking
  - ocean
- **Glossary:** `Concept_Glossary` replaces `general/Definitions.html`; delete the old page.
- **Landing page:** `Microwave_GettingStarted`, the entry point linking concepts → workflows → operators → readers.
- Retire the stale `sar/docs/general/*` pages and the rcp tutorials: rewrite them into the pages above or delete them, and remove their allowlist entries.

---

## Phase 4 — Parameter tables generated from code

**Status 2026-11-13: DONE.** `tools/javahelp/gen_params.py` generates tables on 110+ pages; `--check` is clean.

### Task 4.1: Parameter-table generator

**Files:**
- Create: `tools/javahelp/gen_params.py`

**Approach:** `gpt <alias> -h` already prints each parameter's name, type, description, default and value set from `@Parameter`, so it reuses the operator metadata without any Java tooling.

**Behaviour:**
- Parse that output.
- Render an HTML table: name, label, type, unit, default, allowed values, description.
- Write the table between `<!-- params:begin op="<alias>" -->` and `<!-- params:end -->` in the operator's page.
- `--check` mode exits 1 on drift. Wire it into the checker as category `params`.

**Test:** `tools/javahelp/test_gen_params.py` uses fixture `gpt -h` output for `Goldstein-Phase-Filtering`. It asserts that the 9 parameters render with default `useAdaptiveAlpha=true`, and that text outside the markers is unchanged.

### Task 4.2: Roll-out and source cleanup

- Insert the markers into every operator page and generate the tables.
- While the description strings are in view, fix the misleading ones in source:
  - `SnaphuExportOp.statCostMode` tooltip;
  - PolSpeckle `sigmaStr` label;
  - `PCAOp` boolean defaults;
  - RangeFilter defaults and the invalid `"off"` value.

---

## Phase 5 — References

**Status 2026-11-13: DONE.** All non-exempt pages have verified references. `concepts/bibliography.html` is built by `build_bibliography.py`; `--check` is clean.

### Task 5.1: Shared bibliography and per-page references

**Files:**
- Create: `concepts/Bibliography.html`, one topic-grouped list built from the four review bibliographies.

**Steps:**
- Verify every "(verify)" entry against its DOI or publisher page before adding it; drop it if it can't be verified.
- Every operator page gets a References section naming the foundational paper(s) for its algorithm, listed in the spec §6 and the per-page "refs missing" columns.
- Checker category `refs`: every operator page has at least one reference, unless it is allowlisted as a pure utility (BandSelect, Subset-like tools).

---

## Phase 6 — Terminology and staleness sweep

**Status 2026-11-13: DONE (screenshots deferred).** The `style` checker category reports 0 findings: master/slave changed to Reference/Secondary, with literal identifiers kept in `<code>`; dead domains and NEST/s1tbx paths fixed; Copernicus DEM default. Screenshot refresh needs a SNAP 15 build and is left to the user.

### Task 6.1: Sweep

- **Terminology:** master/slave → Reference/Secondary (about 130 occurrences). Leave literal identifiers as they are.
- **Paths:** remove `NEST4A` and `.s1tbx` paths.
- **DEM text:** replace SRTM and `xftp.jrc.it` auto-download text with the current Copernicus defaults.
- **Missions:**
  - Sentinel-1B ended in 2022; add S1C and S1D.
  - Remove forward-looking mission claims ("ICEYE will have 18 satellites", "Kompsat-6 in 2020").
- **Links:**
  - Convert eoPortal links to the `www.eoportal.org/satellite-missions/` form.
  - Fix the dead DLR, `earth.esa.int/pub` and `sentinel.esa.int` links.
- **Screenshots:** replace the Windows-XP-era screenshots on the most-used pages (TC, Calibration, Coregistration, Interferogram).

**Checker:** add a `style` category that greps for `master|slave` outside `<code>` and for known dead domains.

---

## Phase 7 — Reader / mission pages

**Status 2026-11-13: DONE.** All sar-io/rstb-io reader pages follow the mission template, and facts were checked on the web (S-1A end of operations 29 Jun 2026; S-1C/D added). New pages: ALOS-4, S-1 ETAD, CIMR L1B, SNAPHU, Basic CEOS, Supported Missions (generated by `gen_missions.py`). ERS-CEOS, ERS-2 and TanDEM-X were merged and their old IDs kept as aliases; the TOC is alphabetical.

### Task 7.1: Mission page template

**Sections:**
- Mission: band and wavelength, orbit and revisit, launch and end dates
- Supported products: levels × modes × polarisations
- How to open: the exact file to select
- What you get: bands, units, complex vs detected
- Known limitations
- Recommended next step
- Product format specification link

Convert the stubs to this template: RAT, Gamma, PCIDSK, Capella, ERS-CEOS, TanDEM-X and SeaSat. Merge ERS-CEOS and ERS-2 into ImportErs, and TanDEM-X into TerraSAR-X, keeping their map IDs as aliases.

### Task 7.2: Missing reader pages

- New pages: ALOS-4, EOS-04 COG, Sentinel-1 ETAD product, CIMR L1B, SNAPHU and Basic CEOS.
- S1 COG as a section of ImportSentinel1.
- Repoint the `ImportAlos4` and `ImportEOS04` map IDs from Task 1.3.
- Generated **Supported Missions** page from the `ProductReaderPlugIn` services list (`tools/javahelp/gen_missions.py`).
- Sort the SAR Sensors TOC alphabetically with consistent names.

---

## Phase 8 — Code defects surfaced by the review (separate review gate)

**Status 2026-11-13: DONE for the clear defects; others PARKED.** Fixes were made with tests in jlinda, sar-op-insar(-ui), sar-op-sar-processing(-ui), sar-op-feature-extraction, sar-op-calibration(-ui), sar-op-sentinel1, rstb and rstb-io. Each group had an independent code review plus fix rounds. Help pages were synced to the fixed code. The parked items (defaults, redesigns, cross-repo EGM2008, reader limitations) are listed in the SDD ledger and the final review. Nothing is committed.

| Item | Note |
|---|---|
| `sar-op-insar-ui/layer.xml:398` | Binds the UI to `MultiReferenceStackGenerator`, but the alias is `MultiMasterStackGenerator`, so the custom UI never attaches. |
| `snap-engine CopernicusElevationTile:57` | Applies EGM96, but GLO-30 is EGM2008. This is a cross-repo change: confirm with the user first. |
| SAOCOM-Thermal-Noise-Removal | Add a menu entry and help page. |
| RCM-Thermal-Noise-Removal | Add a menu entry; its help page already exists. |
| GRD-Post | Add a help page. |
| AdaptiveThresholding, Object-Discrimination, Oil-Spill-Clustering | Describe them as sub-steps on the ship and oil-spill pages. |
| TileWriter, BandMerge | Add help pages. |
| Faraday Ω output band | Wire it up, or drop it from the page (Task 2.1). |
