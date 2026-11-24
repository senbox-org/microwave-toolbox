# snap-validation: Design

**Date:** 2026-11-20 · **Status:** proposed

**Relation to earlier specs.** This design replaces §6 (T3 scientific validation) of
[`2026-11-17-validation-framework-design.md`](2026-11-17-validation-framework-design.md). It also moves the
release-time efficiency measurement out of §5.2 of
[`2026-11-17-memory-efficiency-design.md`](2026-11-17-memory-efficiency-design.md) ("heapSweep in
snap-gpt-tests") and into this repository. The T1 contract tests (`sar-contract-tests`) and the T2
regression work in snap-gpt-tests / snap-reader-tests in the earlier spec are unchanged, and are out of
scope here.

**Report mockup:** [`2026-11-20-snap-validation-report-mockup.html`](2026-11-20-snap-validation-report-mockup.html). Open it in a browser. All numbers in it are invented.

---

## 1. Intent

Before each SNAP release, one command on a dedicated machine produces a report. The report shows, for
each feature, whether the feature **runs correctly** and whether it **runs efficiently**.

- **Correctly** means it is scientifically right against ground truth, and comparable to independent
  open-source tools (ISCE3/COMPASS, Dolphin, OPERA products, later MintPy and PolSARpro).
- **Efficiently** means its wall time and memory are no worse than in the last release.

The report has two audiences. **The team** uses it for go/no-go before a release and to find what to fix.
**ESA** gets evidence per feature, archived per release.

The front page is a scorecard. Each feature has pass/fail, its key accuracy metric, its agreement with
peer tools, and its speed and memory against the last release. Every feature links to a detail page with
plots and provenance.

## 2. Goals, non-goals, anti-goals

**Goals**
- One command produces the whole report for an installed SNAP build.
- Each first-wave feature (§6) has at least one case with a cited threshold.
- Every number in the report says where it came from: inputs, truth, peer-tool version, SNAP build, and
  the source of the threshold.
- Speed and memory are compared with the previous release, measured on the same machine.
- The report looks crisp and professional, in the SNAP house style, with visualisations wherever a
  number benefits from one.

**Non-goals (now)**
- Missions other than Sentinel-1.
- The optical toolbox. The repository is named and laid out so that it can be added later (§4).
- Showing toolbox unit or contract test results in the report.
- Running on AWS. The design keeps it possible (§10), but nothing is built for it.
- The user-facing corner reflector and point-target tools. They belong in microwave-toolbox and will be
  built later (§9).
- Regression testing ("same input, same output as last time"). That stays in snap-gpt-tests.

**Anti-goals: failure even if it technically works**
- A second snap-gpt-tests. This repository does not compare outputs bit for bit with the previous
  release.
- Agreement with a peer tool presented as proof of correctness. When truth exists, truth is the
  reference.
- A green scorecard that hides a disagreement. A disagreement with a peer tool is shown with its
  diagnosis and is never silently dropped.
- A report that cannot be reproduced. Results without the build, input and version provenance are
  worthless to ESA.
- Timings that are not comparable: different machine, different parallelism, or other work running at
  the same time.

## 3. Decisions taken (with the user)

| Decision | Choice |
|---|---|
| Repository | New repository **`snap-validation`**. Microwave Toolbox first; optical may join later |
| Language | **Python** (runner, metrics, report). SNAP is invoked as the installed `gpt`, never linked |
| Where it runs | **This Windows workstation**, started manually by the user before each release |
| Baseline of record | Windows, on this machine. Any future AWS/Linux run keeps its own baseline |
| Data | Read locally from `E:\TestData` (and the scenes folder). The S3 copy is **`s3://skywatch-image-processing-test/SNAPTestData/`**, a new folder holding **only what the first-wave cases need** (§4.1). It is not the existing OVH `snap-s3` sync of `s1tbx\SAR` |
| First-wave features | S1 Calibration, S1 GRD → Terrain Correction, S1 TOPS InSAR, GSLC. **Sentinel-1 only** |
| Memory scope of the initial pass | **S1 only.** Efficiency is measured for the four scorecard features. The memory spec's other workflows are not in the initial pass (§7.2) |
| Report front page | **A table** with one row per feature, linking to a detail page per feature |
| Report look | SNAP house palette; modern components (cards, drop shadows, hover states); charts wherever possible |
| Toolbox test results in the report | **No** |
| Corner reflector / point-target analysis for users | Out of scope; it lands in microwave-toolbox later |

## 4. Repository layout

```
snap-validation/
  pyproject.toml            Python 3.12, pinned dependencies (lock file committed)
  docker-compose.yml        peer-tool services (moved from microwave-toolbox/validation)
  config/
    machine.example.yaml    snap_home, data_root (path or s3://), work_dir, machine_id, cpu/heap settings
  data/
    manifest.yaml           every input, truth and reference product: id, relative path, S3 key, sha256
  engines/                  one directory per engine; peers are Docker images, snap is the native gpt
    snap/  isce3/  dolphin/  insardev/
  truth/
    cr/                     corner reflector lists (moved from sar-test-stacks) + reader
    opera/                  OPERA RTC-S1 / CSLC-S1 product readers
  toolboxes/
    microwave/
      features.yaml         scorecard rows: id, title, order, the cases that make up the feature
      cases/*.yml           one YAML per case
      graphs/*.xml          the SNAP graphs the cases run
  sv/                       the Python package
    cli.py                  `sv run`, `sv report`, `sv baseline`
    runner.py               case execution, resume, provenance capture
    measure.py              wall time, peak RSS, GC share, minimum heap search
    metrics/                metric library (the existing compare/ code, cleaned up)
    history.py              results store and baseline lookup
    report/                 templates, house-style chart helpers, HTML → PDF
  history/                  results JSON per run (committed; small)
  reports/releases/<ver>/   archived release reports (committed)
  tests/                    unit tests for metrics, history and report (synthetic data, no SNAP)
```

The `toolboxes/microwave/` level is the only structural concession to optical. Everything under `sv/`
is toolbox-agnostic.

### 4.1 Test data on S3
- Bucket and prefix: `s3://skywatch-image-processing-test/SNAPTestData/`. Inside it, keep the same
  relative layout as the local data root, so a manifest path maps to an S3 key by adding the prefix.
- **Upload only what the first-wave cases use**, listed in `data/manifest.yaml` with a sha256 for each:
  - the S1 GRD and SLC scenes over Rosamond and Surat;
  - the Venezuela orbit-106 pair;
  - the GSLC CR scene and its ETAD product;
  - the DEM tiles the cases use;
  - the OPERA RTC-S1 and CSLC-S1 reference products;
  - the corner reflector lists.

  Orbit files are fetched by SNAP as usual, and are not uploaded.
- An `sv data upload` command pushes whatever the manifest lists that is not yet in the bucket. Uploading
  the first-wave set is a task in the plan, not a prerequisite for starting work.
- The data layer uses the default AWS credential chain, with a named profile set in `machine.yaml`. The
  builder must check the bucket's region, because AWS runs must use the same region (§10).

## 5. How a run works

```
sv run --label 14.0.0-rc2 [--features grd_tc,gslc] [--resume <run-id>] [--skip-efficiency]
```

1. **Preflight.** Read `config/machine.yaml`. Find the SNAP install and record its version and module
   versions (`gpt --diag`, or the module manifests). Check Docker is up and the pinned peer images exist.
   Check that every input in the manifest the selected cases need is present locally. If one is
   missing, fetch it from S3 and verify its sha256. Refuse to start if another `gpt` or `java` process is
   running, because it would skew the timings.
2. **Correctness pass.** For each case: run the SNAP graph through `gpt` at a generous fixed heap; get
   the peer-tool output (§5.1); load the truth; compute the metrics; evaluate the thresholds; write the
   plots.
3. **Efficiency pass.** For each feature's designated efficiency case: time it, then search for the
   minimum heap (§7).
4. **Record.** Write `history/<run-id>.json` with every metric, threshold, verdict, timing and the full
   provenance.
5. **Report.** Build the HTML report (and the PDF) from this run plus the baseline run from history.

**Resumable.** Each case writes a completion marker into `work_dir/<run-id>/<case>/`. `--resume` skips
completed cases. A full run is expected to take overnight.

### 5.1 Peer-tool outputs are cached
Peer tools and reference products do not change between SNAP releases. A peer output is cached under
`work_dir/peers/<engine>/<image-digest>/<case>/` and recomputed only when the pinned image or the case
inputs change. Each SNAP release therefore reruns only SNAP. *(My call, to keep the run overnight-sized.)*

### 5.2 Fixed conditions for SNAP runs
Every `gpt` invocation sets parallelism, tile cache size and `-Xmx` explicitly from `machine.yaml` (or
from the minimum heap search). It never relies on the installed `gpt.vmoptions`. The values used go into
the provenance.

## 6. First-wave features and cases

The thresholds are **proposals**. Each must cite its source in the case YAML before it gates a release.
The report prints the source next to every limit.

| Feature (scorecard row) | Cases | Reference | Metrics (proposed limit) |
|---|---|---|---|
| **S1 Calibration** | Rosamond SLC; Surat SLC (ported from the Java `CRValidation*SLCTest`) | Corner reflectors. Rosamond: theoretical trihedral RCS from side length, corrected for the boresight angle. Surat: geolocation only, because its list carries no RCS or size, unless Geoscience Australia publishes reflector dimensions | Absolute RCS bias by integrated energy on calibrated SLC (±0.5 dB); scatter across reflectors; geolocation in radar geometry |
| **S1 GRD → Terrain Correction** | Rosamond GRD; Surat GRD; one OPERA RTC-S1 frame | CR surveyed positions; OPERA RTC-S1 | ALE 2-D RMS (proposed ≤ 3 m, from published S1 ALE results; the source is to be cited); γ0 bias (±0.3 dB) and RMSE vs OPERA |
| **S1 TOPS InSAR** (the **classical** chain: Back-Geocoding → ESD → Interferogram → Deburst → TC) | Venezuela orbit-106 pair, run with the `snap-classic` variant (a new case modelled on `insar_classic_vs_gslc_venezuela.yml`) | ISCE3/COMPASS interferogram from the same pair | Circular std of the phase difference (≤ 0.1 rad where coherence > 0.7); coherence bias; ESD residual from SNAP's own metadata |
| **GSLC** (geocode-first chain) | Venezuela orbit-106 (exists: `gslc_s1_venezuela_orbit106.yml`, `insar_s1_venezuela_orbit106.yml`); a CR site with ETAD | OPERA CSLC-S1 / COMPASS; CRs; the classical SNAP chain | ALE (with ETAD ≤ 0.2 m); phase agreement vs COMPASS; coherence no worse than the classical chain |

*My calls on this table.*
- TOPS InSAR means the classical chain, because the user listed it separately from GSLC.
- Dolphin is dropped from the first wave. It is a phase-linking stack engine and adds nothing on a single
  pair. It returns when Phase Linking or SBAS gets a row.
- Calibration is measured on SLC, because the integrated-energy RCS method needs SLC.

**Thresholds.** The builder proposes each limit with a citation. The user approves them before the first
report that gates a release. Until then, the report shows them as "proposed".

**Pass/fail.**
- A **case** passes when SNAP runs without error and every **truth** metric is within its limit.
- A **peer** metric outside its limit fails the case unless the case YAML carries a recorded
  `diagnosis:` (text plus a reference). In that case the metric shows as *diagnosed* with the note on the
  detail page, and the scorecard cell shows the note marker.
- A **feature** gets the worst verdict of its cases, combined with its efficiency verdict:
  - **PASS**: all cases pass and efficiency is within 5% of the baseline;
  - **PASS\***: efficiency is worse by 5–10%;
  - **FAIL**: any case fails, or efficiency is worse by more than 10%.
- A feature with no baseline yet (its first run) shows efficiency as "baseline", not as a delta.

**Case YAML.** Extend the existing `validation/cases` format with these fields:

```yaml
feature: grd_tc                 # id from features.yaml
reference: {kind: truth|peer, source: cr/rosamond | opera-rtc-s1 | isce3}
metrics:
  - name: ale_2d_rms_m          # function in sv/metrics
    limit: 3.0
    direction: below            # below | above | within
    source: "S1 Product Specification, IW GRDH absolute location accuracy"
efficiency: true                # this case is the feature's efficiency workload
diagnosis: null                 # or {text, ref} for an accepted peer disagreement
```

## 7. Efficiency measurement

Each feature has exactly one **efficiency case**: a full-scene, end-to-end workflow. All measurements run
`gpt` as a child process.

| Measure | How |
|---|---|
| Wall time | Two runs at the standard heap from `machine.yaml`. Report the faster one, because the slower one mostly reflects machine noise |
| Peak RSS | Sampled from the process tree with `psutil` |
| GC share | `-Xlog:gc*:file=...`; the share of wall time spent in GC pauses |
| **Minimum heap** | The smallest `-Xmx` that completes with GC share < 20% **and** whose output matches the generous-heap output: per-band statistics within 1e-6 relative. A run that finishes but gives different output fails the rung. Search on a 256 MB ladder |

The delta against the baseline is computed per measure. The scorecard verdict uses wall time and
minimum heap only. Peak RSS and GC share appear on the detail page.

**Keeping it overnight-sized.** The target is a full first-wave run in about 12 hours. This is a target,
not a hard limit. *(My calls.)*
- The minimum-heap search starts at the baseline's minimum heap. It probes one rung below it, then
  walks up or down. It bisects from scratch only when there is no baseline.
- Each rung runs once. Only the lowest passing rung runs a second time; if it fails, the answer moves one rung up. *(Changed 2026-11-22: the highest fail is no longer re-run, since a pass on repeat never changed the answer.)*
- A rung is compared with the generous-heap output on the chain's **final** products only. The reference statistics are read once per feature. *(Changed 2026-11-22: comparing every intermediate re-read ~40 GB per tops_insar rung.)*
- With 256 MB steps, minimum heap counts as **worse** only when it rose by more than one rung **and**
  by more than the 5% / 10% thresholds. A one-rung move is shown on the detail page, but never changes
  the verdict.

### 7.1 Old releases
- Old releases use the **same graph** wherever it runs on them.
- If an operator parameter was renamed, the case may carry a per-version graph override. The override is
  recorded in the run's provenance.
- If the feature does not exist in a release (e.g. GSLC before 14), that release has no point for it.
  The row shows "baseline" until two releases exist.

### 7.2 Memory workflows outside the scorecard
The memory spec's suite also has non-scorecard workflows:
- S1: the 10-date stack (W3) and 100-date SBAS (W5);
- other missions: quad-pol (W4) and CSG/ICEYE stripmap (W6);
- SNAP Desktop (W7).

None of these is in the initial pass. The runner must support an **efficiency-only case**: no
correctness metrics, a memory and time row only. That lets the S1 workflows W3 and W5 be added later
without a redesign. *(My call.)* The non-S1 workflows wait until those missions join. Desktop W7 stays
with the memory workstream.

**Baseline.** The baseline is the most recent run in `history/` whose label is a release (not an RC or
snapshot) and whose `machine_id` matches. Releases that predate this repository get a baseline by
installing them and running `sv run --label 13.0.0 --baseline-only`. That command runs the efficiency
pass and the correctness pass, but does not gate. **At minimum SNAP 13.0** must be backfilled before the
first SNAP 14 report. Backfilling 12.0 as well gives the mockup's three-point trend.

## 8. The report

The layout follows the mockup, organised into pages.

**Navigation (user decision, 2026-11-20).** A fixed left sidebar lists the report's pages in this order:
1. **Overview**
2. **Calibration**
3. **Terrain Correction**
4. **InSAR**: TOPS InSAR and GSLC.
5. **Corner Reflectors**: every corner reflector case across features, per site, with a geolocation scatter, the radiometric bias and a per-reflector table built from each case's `cr_points.json`.
6. **Performance**: wall time, minimum heap, peak RSS and GC share for every feature, with trends.

Only one page is visible at a time. Pages are selected by URL hash, so the browser's back button works. The report remains one self-contained HTML file. When printed, the sidebar is hidden and every page is printed in sidebar order, each starting on a new page.

The topic pages come from a `topics:` list in `features.yaml` and a `topic:` field on each feature. The Corner Reflectors and Performance pages are built automatically from the run data.

**Front page (Overview)**
- A header band: title, build label and git hash, compared release.
- A run-details strip: build, run start → end, machine, peer-tool versions.
- Four summary cards: features passing, metrics within limits, efficiency warnings, median change in
  minimum heap.
- **The scorecard table**: feature (linked) · verdict badge · key accuracy metric with its limit · peer
  agreement · wall-time trend line and delta · minimum-heap bars and delta. The trend covers the
  releases in history.
- A legend for the verdicts.

**Detail page per feature**
- A header with the verdict and the SNAP graph used.
- Plot cards that depend on the case type:
  - CR cases: geolocation scatter (range vs azimuth error, limit circle, mean marker, one colour per
    site); radiometric bias per reflector.
  - Peer cases: difference map with a diverging colour scale; histogram with the bias marked.
  - Always: the history of time and minimum heap across releases.
- A metrics table: value · limit · source of limit · previous release · verdict.
- Diagnosis notes.
- Provenance: inputs (scene IDs), truth files, peer product IDs and image digests, SNAP build, machine,
  and the path of the logs and outputs.

**Style.**
- SNAP house palette: DARK `#003249`, MINT `#75C8AE`, CORAL `#F06669`, TEAL `#335E6E`, BLUE `#009BDA`.
  Arial. These are the same constants as the `creating-snap-docs-and-decks` skill.
- Pass is mint, warning is a coral outline, fail is solid coral.
- Cards have rounded corners, soft drop shadows and hover lift.

**Format.**
- A single self-contained HTML file per run: inline CSS, charts as inline SVG.
- A PDF printed from the same HTML for the ESA archive.
- Charts come from matplotlib with a house-style helper and are emitted as SVG, so they stay crisp in
  print. *(My call. An interactive chart library is possible later, but static SVG keeps the archive
  self-contained and printable.)*

**Archive.** `sv report --publish` copies the report into `reports/releases/<version>/` for committing.

## 9. What moves where

| Source | Destination | Notes |
|---|---|---|
| `microwave-toolbox/validation/` engines, compare, cases, docker-compose | `snap-validation` (`engines/`, `sv/metrics/`, `toolboxes/microwave/cases/`) | Move with history (`git filter-repo --subdirectory-filter validation`). Leave a README pointer in microwave-toolbox |
| `validation/drivers/` (`ven_*`, `napa_*`, `cf_*`), `gslc_parity/`, `experiment_2x2.ps1`, `overnight.ps1` | Not moved | One-off GSLC investigation scripts. They stay in microwave-toolbox history; delete them from the tree after the move |
| `sar-test-stacks` CR tests (`CRValidation*`, `BaseCRTest`, `FindCRPosition`, `Plot`) and the CR CSV/KMZ lists | `snap-validation` cases + `truth/cr/` + a Python peak finder in `sv/metrics/` | GRD tests go to the GRD → TC row; SLC tests go to the Calibration row. The Java tests assert nothing today, so first record one run of them (their printed per-reflector shifts) as a reference. "Reproduced" means the ported cases' shifts agree with that reference within 0.1 pixel for every reflector. Only then delete the Java tests |
| `sar-test-stacks` stack tests (Create Stack, coregistration, Interferogram, ERS/Envisat reader) | Stay in microwave-toolbox | They are source-level regression tests |
| `microwavetbx-benchmark` | Stays in microwave-toolbox as the developer profiling tool | Replace the hard-coded `DISABLE_BENCHMARKS = true` with a system property, and add the memory readings from memory spec §5.1. It is not used by the release report |

**Corner reflector analysis.** The user-facing CR and point-target tools (peak finding, geolocation
error, RCS, IRF metrics, visualisation) belong in microwave-toolbox, and are out of scope here. Until they
exist, the CR cases measure with the Python peak finder in `sv/metrics/`. When the toolbox tool ships,
the CR cases call it through `gpt` as well. The Python implementation remains as an independent
cross-check, and must agree with it. Keep the CR measurement step behind one function so the switch is
local.

## 10. Portability (AWS later)

Nothing is built for AWS now. These rules keep it possible:
- No PowerShell-only steps: the runner is Python, and engines are Docker or `gpt`.
- `data_root` may be a local path or an `s3://` URI; reads go through one data-access layer.
- All paths come from `machine.yaml`, never from code.
- Cases are resumable (needed for spot instances).
- A `machine_id` on every result, so baselines never mix machines.

Rough cost when it is wanted: one `r7i.4xlarge` for about 24 h is about $15–30 per run (spot vs
on-demand), plus about $13 of scratch disk. The instance must be in the same region as
`skywatch-image-processing-test`, so that reading the data costs nothing; from another region it costs
about $0.02/GB. Storage of the first-wave subset is small.

## 11. Constraints

- The SNAP under test is the **installed** Windows build, invoked as `gpt`. The runner never uses
  `esa_snappy`.
- Peer tools run in Docker Desktop (WSL2). On Windows, invoke container paths without MSYS path rewriting
  (see the existing `validation/README.md`).
- The machine is also a development workstation. The preflight check (§5) and the overnight schedule
  are what keep the timings honest.
- The repository is test tooling, not shipped code. Large outputs never get committed; only `history/`
  JSON and archived release reports do.

## 12. Left to the builder

- The minimum Python packages and the environment tool (uv or pip-tools); the HTML templating library;
  the HTML → PDF printer (headless Chromium or similar).
- How `gpt` heap and parallelism are passed on the Windows launcher. Check how snap-gpt-tests applies
  `configVM`, and use the same mechanism.
- How the Python CR peak finder is structured (porting `FindCRPosition`: coarse peak, then upsampled
  fine peak).
- Choosing the OPERA RTC-S1 frame and the CR site with ETAD for GSLC, from what is in `E:\TestData` or on
  ASF.
- The schema of `history/<run-id>.json`, as long as it carries everything §5 step 4 lists.
- Unit tests for metrics, threshold evaluation, baseline lookup and report rendering, on synthetic data.

## 13. Open

- **Hosting and visibility of the repository** (senbox-org GitHub, ESA GitLab or a private SkyWatch
  repo; public or private). This decides where the `git filter-repo` move goes. It also decides what may
  be committed in `history/` and the release reports: machine names, local paths, bucket keys.
  - Until it is decided, start the repository locally.
  - Keep machine-specific values (`machine.yaml`, absolute paths, bucket names) out of committed files.
    Provenance records them in the run's work directory and puts only relative paths and the
    `machine_id` in `history/`.
