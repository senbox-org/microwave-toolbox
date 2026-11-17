# Validation Framework: Design

**Date:** 2026-11-17
**Status:** proposed
**Roadmap:** [§9 Validation framework](../../roadmap.md#9-validation-framework-workstream)
**Owner:** microwave-toolbox. The T2 changes land in snap-gpt-tests and snap-reader-tests; T3 is a new repository.
**Companion:** [Memory efficiency design](2026-11-17-memory-efficiency-design.md). The memory guard rails reuse the T1 and T2 machinery defined here.

---

## 1. Purpose

For every feature of the Microwave Toolbox (every reader, operator and shipped graph), we want to be able to show three things:

1. **It runs correctly.** It behaves as specified on known input, at any tile size and thread count, and handles no-data and units correctly.
2. **It is comparable to or better than other tools.** Its output agrees with independent implementations such as ISCE3, Dolphin, MintPy and PolSARpro, or the disagreement is diagnosed.
3. **It is scientifically correct.** Its output agrees with ground truth (corner reflectors, GNSS, AIS, reference products) within a stated threshold.

The evidence must be re-runnable, tied to an identified SNAP build, and published per release.

## 2. Scope and what already exists

**Inventory** (from `META-INF/services` and the graph resources on 2026-11-17):

| Feature type | Count |
|---|---|
| Operators (`OperatorSpi`) | 115 |
| Readers (`ProductReaderPlugIn`) | 39 |
| Writers (`ProductWriterPlugIn`) | 7 |
| Shipped graphs (`*-ui/src/main/resources/**/graphs/*.xml`) | 97 |

**Existing assets.** The design builds on these rather than replacing them.

| Asset | Where | State |
|---|---|---|
| Module unit tests and long-gated integration tests | microwave-toolbox, `@RunWith(LongTestRunner.class)` + `-Denable.long.tests=true`; data via `TestData.inputSAR` | Uneven coverage per operator |
| An all-plug-ins sweep pattern | `sar-op-insar-ui/.../TestOperatorUIDefaults.java`: discovers every `*OpUI`, asserts the expected count, and documents what it does *not* catch | The precedent for the T1 contract sweeps |
| Reader acceptance tests | **snap-reader-tests** (`ProductReaderAcceptanceTest`) consumes `<PlugIn>-data.json` descriptors from each reader module's test resources (`snap.reader.tests.data.dir`) | **23 descriptors covering 22 of the 39 readers**, about 66 product ids. They check band names, sizes, no-data, geo-coordinates and sampled pixels with a relative tolerance of 1e-6 (`ContentAssert.computeAssertDelta`) |
| gpt graph tests | **snap-gpt-tests** `gpt-tests-resources/tests/s1tbx/` | 35 JSON test files (Standard, InSAR, PolSAR, FeatureExtraction, Writers, missions/Sentinel1). The expected outputs use the same `ExpectedContent` JSON as the reader tests. They have per-test `configVM` and a performance DB with `memory_max` |
| Cross-tool harness | `microwave-toolbox/validation/` (not a Maven module) | Docker engines for ISCE3/COMPASS, Dolphin and InSAR.dev; native `gpt` as the SNAP engine; `compare/` metrics; 8 YAML cases (Venezuela orbit 106, ETAD) |
| Corner-reflector validation | `sar-test-stacks` `CRValidation{Rosamond,Surat}{GRD,SLC}Test` | Excluded by default; run with `-Pcorner-reflectors` |
| Truth datasets | Campi Flegrei P-SBAS; the Venezuela 3-day fixture | Used in the GSLC campaign |

**Main gaps:**
- No generic checks run over all plug-ins, so each defect class is rediscovered operator by operator.
- 17 readers have no acceptance descriptor.
- Most of the 97 shipped graphs have no gpt test, and `missions/` covers only Sentinel-1.
- The tolerance model is "exact at a few pixels", so legitimate changes cannot be accepted deliberately.
- There is no register, so nobody can say which features are validated at which level.
- There is no per-release validation report.

## 3. Tier model and placement

### 3.1 The three tiers

| Tier | Question | Inputs | Comparison against | Cadence | Home |
|---|---|---|---|---|---|
| **T1 Contract** | Does it behave correctly on known input? | Synthetic products, tiny committed fixtures | Invariants and analytic oracles | Every commit (CI) | **microwave-toolbox** |
| **T2 Regression** | Does the **installed build** give the same answer on real data, through gpt and the graphs users run? | Real products from the shared test-data tree | The previous accepted output (golden), within tolerance | Nightly; full set per RC | **snap-gpt-tests** (graphs, operators) and **snap-reader-tests** (readers; the descriptors stay in the reader modules) |
| **T3 Scientific** | Is it right against ground truth, and how does it compare with peer tools? | Reference sites, truth data, peer-tool outputs | Truth or peer, with a metric and a threshold | Per release / quarterly | **New repo `microwave-validation`** |

Each tier answers a question the others cannot:
- T1 can prove invariants but not realism.
- T2 detects change but not whether the old answer was right.
- T3 establishes rightness, but is too slow and data-heavy to gate commits.

Reader, operator and graph validation each appear in all three tiers:

| | T1 | T2 | T3 |
|---|---|---|---|
| **Readers** | `ReaderContractTest` on fixtures | `-data.json` descriptors run by snap-reader-tests on full products | Geolocation and radiometry at corner reflectors; metadata against mission specs |
| **Operators** | `OperatorContractTest` + algorithm oracles | gpt tests of single-operator graphs | Peer and truth cases per operator family |
| **Graphs** | Graph XML parses and every node's operator and parameters resolve (static) | gpt test per shipped graph | End-to-end products (for example a velocity map) against truth |

### 3.2 Why T3 gets its own repository

- **Different runtime.** Python and Docker peer tools (ISCE3, Dolphin, MintPy, PolSARpro, RAiDER), not Maven.
- **Different data.** Multi-GB truth data and peer outputs, with licences and provenance to track.
- **Different cadence and audience.** It runs per release, and its output is a report for ESA and users, not a CI signal.
- **It tests the installed build.** A report pins the SNAP build (version plus the module git hash, read from the installation). That replaces the "single commit covers the toolbox and the harness" argument in `validation/README.md`, which was the reason for keeping the harness in this repo.

### 3.3 Alternatives considered

- **Keep everything in microwave-toolbox.** This bloats the build repo with Docker and Python, and puts release-cadence data next to per-commit code. Rejected.
- **One new repo for T2 and T3.** This would duplicate snap-gpt-tests (runner, expected outputs, performance DB), which ESA already operates on Jenkins. Rejected.
- **A new contract-test tool outside the build.** T1 must fail the commit that breaks it, so it belongs in the reactor. Rejected.

## 4. T1: contract tests (microwave-toolbox)

### 4.1 Module
Add a new test-only reactor module, **`sar-contract-tests`**, following the `sar-test-stacks` pattern. It has no main code. It depends on every `sar-*`, `rstb-*` and `jlinda` operator and IO module, so service discovery sees all plug-ins. The contracts run in the default build. Any check that needs real data uses `LongTestRunner`.

### 4.2 Fixture registry
Operators need different inputs, so a registry maps each operator alias to a **fixture kind** plus parameters. It lives in `src/test/resources/contract-fixtures.yaml`.

```yaml
Calibration:          {fixture: s1-grd-mini, params: {outputSigmaBand: true}}
Terrain-Correction:   {fixture: s1-grd-mini, params: {demName: "External DEM", externalDEMFile: "@flat-dem"}}
Interferogram:        {fixture: slc-pair-coregistered-synthetic}
Polarimetric-Decomposition: {fixture: quad-t3-synthetic, params: {decomposition: "H-A-Alpha Quad Pol Decomposition"}}
Write:                {exempt: "I/O sink; covered by writer round-trip test"}
```

- **Synthetic fixtures** are generated in code by a `SyntheticProducts` factory. Kinds include:
  - detected GRD with abstracted metadata;
  - SLC i/q;
  - a coregistered SLC pair with a known phase ramp;
  - a C2 or T3 matrix product;
  - an interferogram stack with coherence;
  - a TOPS-like product with burst metadata.
- **Mini real fixtures** are crops of at most 2 MB, committed under `src/test/resources` (as with the ICEYE crop fixtures).
- `@flat-dem` is a committed synthetic GeoTIFF DEM, so DEM-dependent operators run offline.
- **Coverage pressure.** An operator with neither a fixture nor an `exempt:` reason **fails** the sweep, with "register a contract fixture". A new operator cannot ship uncovered. The expected operator count is asserted, as `TestOperatorUIDefaults` does.

### 4.3 Operator contract checks

| ID | Check | Definition | Defect class it catches (2026 examples) |
|---|---|---|---|
| C1 | Initialises with defaults | `initialize()` succeeds on the fixture with only the registry parameters; every target band has a unit and a no-data setting | The MuLog annotation; missing no-data on PhaseToDisplacement |
| C2 | **Tile-size invariance** | Compute the full target at tile sizes 64, 257 (odd, not a power of two) and 512. All three must be equal: bit-identical by default, or within a declared `tolerance:` for operators whose numerics depend on block layout (each such case documented) | RangeFilter tile overlap; Goldstein no-data strip; Object-Discrimination double count at tile edges |
| C3 | **Thread determinism** | `parallelism=1` against `parallelism=8`, twice each. All runs must be bit-identical | CPM GCP-order and outlier regression; EnvisatOrbitReader singleton; ProductCache TimSort race |
| C4 | **No-data propagation** | Inject a no-data block into the fixture. Output pixels whose input footprint (the operator's window plus a declared margin) lies entirely inside the block must be no-data, never finite | Horizontal/Vertical Motion; StackAveraging; PhaseToElevation NaN seeds |
| C5 | **Unit guard** | Relabel the input band as dB (`unit=intensity_db`). The operator must either refuse it with a clear message or produce an output identical to the linear run converted to dB. It must not silently compute on dB values | Terrain Flattening; LogRatio; AdaptiveThresholding; OilSpill |
| C6 | Parameter and UI parity | Every `@Parameter` label equals the OperatorUI label, where a UI exists; `valueSet` is declared for enumerated strings | The TOPSARSplit `subswath`; the S1 ETAD dialog labels |
| C7 | Metadata hygiene | The target's abstracted metadata exists and keeps its mandatory keys. The target shares no `MetadataElement` *instance* with the source (identity check) | The MultiMasterStackGenerator shared metadata |
| C8 | Bounded allocation | The bytes allocated per `computeTile` (from `ThreadMXBean`) must be at most *k* × (tile pixels × target bands × 8). *k* defaults to 64 and can be overridden per operator with a reason | Full-scene allocations (memory spec §6) |
| C9 | Dispose | After `dispose()`, the operator releases its source products and caches: a weak reference to its target product clears after GC | Leaked operator chains (`sar-test-stacks` OOM) |

### 4.4 Reader contract checks
Parametrised over every SAR `ProductReaderPlugIn` that has at least one fixture. A mini crop is preferred; otherwise a long-gated product from the TestData tree.

| ID | Check | Defect class it catches |
|---|---|---|
| R1 | **Exclusive qualification.** `INTENDED` for its own fixtures; never `INTENDED` for another reader's fixture (cross-matrix over all fixtures) | EOS-04/RISAT-1 both claiming `BAND_META.txt`; RCM accepting S1 `manifest.safe` |
| R2 | **Mandatory abstracted metadata.** Mission, product type, pass, radar frequency, PRF, range and azimuth spacing, slant range to the first pixel, first and last line time and orbit state vectors (at least 3, time-ordered, covering the scene) are present and inside physical ranges per band (for example radar frequency within the band's IEEE range) | Hard-coded frequency and PRF (QPS-SAR, ALOS-2/4); a single made-up orbit vector; mission recorded as `RS1` |
| R3 | **Region read equals the crop of a full read**, at 3 random windows per band | Offset and stride bugs in random-access readers |
| R4 | **Bands are distinct.** In a multi-band product, no two bands return identical data | GenericReader band 1 for every band |
| R5 | **Data type fidelity.** The band data type matches the source file's sample type | SNAPHU `q_band` float32 for float64 data |
| R6 | **Geocoding round trip.** pixel → geo → pixel within 0.5 px at the corners and centre | The ETAD lat/lon box |
| R7 | **Handles released.** After `dispose()` the fixture file can be deleted (Windows) or has no open descriptors (Linux `/proc/self/fd`) | Stream leaks |
| R8 | **Unknown-format tolerance.** A fixture with an unrecognised `format_version` either opens or is refused with a clear message | CIMR `format_version` |

### 4.5 Algorithm oracles
The contracts check behaviour; oracles check the *algorithm*. Each operator family gets at least one closed-form synthetic case:

| Family | Oracle |
|---|---|
| Terrain Correction / GSLC | A point target at a known slant range and zero-Doppler time, on the flat DEM, lands at the analytic lat/lon within 0.1 px |
| Calibration | A constant DN with a known LUT gives σ0, β0 and γ0 that match the closed form |
| Interferogram / Coherence | A known linear phase ramp between the pair gives the analytic fringe rate. Coherence of two correlated Gaussian fields with known ρ is within the estimator bias for window N |
| Phase linking / SBAS | A synthetic network with a known displacement history is inverted exactly (noise-free), and with bounded error under noise. Disconnected epochs give NaN |
| Polarimetry | An analytic T3 for pure surface, dipole and volume scatterers gives the known H/A/α and Freeman/Yamaguchi powers |
| Speckle filters | A constant field with multiplicative gamma noise: the ENL gain and mean preservation match theory |
| Change detection | Two constant fields with a known ratio give the known log-ratio. Thresholds apply in the declared units |

Existing oracles count: the GSLC test pyramid, PhaseLinking, SBAS and Goldstein tests. The register (§7) records which families still lack one.

### 4.6 Graph static check (T1)
For each of the 97 shipped graph XMLs:
- the graph parses;
- every node's operator alias resolves to a registered SPI;
- every parameter name exists on that operator;
- value-set parameters hold allowed values.

This catches graphs that drift after a parameter is renamed or removed. It is cheap and needs no data.

## 5. T2: regression on real data

### 5.1 Readers (snap-reader-tests)
- Write `-data.json` descriptors for the **17 readers that have none**. Each reader gets at least one product per product type it supports (GRD/SLC, and each acquisition mode where the layout differs).
- Extend the existing descriptors with SAR-specific **expected metadata** (the `ExpectedMetadata` type already exists): the R2 key set with values, so a reader change that alters radar frequency, PRF or orbit vectors is caught on full products, not just on mini fixtures.
- Descriptors stay in the reader modules' test resources, as now, so a reader and its expectations change in the same commit.

### 5.2 Graphs and operators (snap-gpt-tests)
- **Graph coverage.** Every shipped graph XML gets at least one gpt test. A script (`pygpt/check_graph_coverage.py`) lists the shipped graphs from the microwave-toolbox checkout and the `graphPath` values in `tests/s1tbx/**`, and fails when a shipped graph is uncovered or a test references a graph that no longer exists. The roadmap §3 finding that the shipped TOPS graphs lack ESD is exactly what this surfaces.
- **Mission coverage.** Add `missions/<Mission>/` tests for each supported mission: reader → Calibration → Terrain Correction (and Deburst/TOPS where it applies). The missions are S1, RS2, RCM, CSK/CSG, TSX/TDX/PAZ, ICEYE, Capella, Umbra, SAOCOM, ALOS-2/4, NISAR, BIOMASS, ERS/ASAR, Gaofen-3, K5 and Spacety, as the test-data tree allows.

### 5.3 Tolerance model (a cross-repo change in snap-gpt-tests' `gpt-tests-common` / ExpectedContent)
Today the check is pixel-exact at a few points (relative delta 1e-6). Add the following:
- **Band statistics**: valid-pixel count, mean, standard deviation, and the 1/50/99 percentiles, each with an explicit tolerance. Statistics catch broad shifts that a few sampled pixels miss (for example a −0.75 dB Terrain Flattening offset), and tolerate legitimate float reordering.
- **A per-test `tolerance` block** (absolute or relative, per band, overriding the default).
- **A regeneration policy.** `gpt-regen` stays, but every regenerated expected file must come with an entry in `expectedOutputs/CHANGELOG.md`: the test id, the reason, and the commit or roadmap item. A reviewer can then see that a change was deliberate.

### 5.4 Memory scope
The `heapSweep` field and the `memory` scope are defined in the memory spec §5.2. They run on the same runner.

## 6. T3: scientific validation (`microwave-validation` repository)

### 6.1 Layout
The existing `validation/` layout is kept and extended:

```
engines/     snap (native gpt), isce3, dolphin, insardev  ← moved from microwave-toolbox/validation
             + mintpy, polsarpro, opera-rtc (reference products), raider
truth/       adapters that turn truth data into the comparison grid: cr (corner reflectors),
             gnss (NGL/EPN time series), ais, ascat (wind), opera (RTC-S1, CSLC-S1 products)
sites/       site catalogue: AOI, sensors, dates, data sources, licence, provenance
cases/       one YAML per case (existing format, extended in §6.2)
compare/     metric library (existing) + per-metric threshold evaluation
report/      per-release report generator (HTML + PDF)
reports/     generated, git-ignored; published reports archived per release
```

### 6.2 Case schema additions
These fields are added to the existing case YAML (`case`, `feature`, `engines`, `scene`, `grid`, ...):

```yaml
feature: Terrain-Correction             # the register id (§7)
tier: T3
reference:
  kind: truth | peer
  source: cr/rosamond | isce3 | opera-rtc-s1
metrics:
  - name: absolute_ale_m                # implemented in compare/metrics.py
    threshold: 1.0                      # proposed; the basis is cited in the register
    direction: below
snap_build: auto                        # read from the installation: version + module git hashes
```

### 6.3 Initial case catalogue
The thresholds here are **proposals**. Each one must be justified in the register by a mission requirement or published result before it gates a release.

| Area | Reference | Metric | Proposed threshold |
|---|---|---|---|
| Radiometric calibration | CRs (Rosamond, Surat; add DLR Oberpfaffenhofen and the Queensland array) | Absolute σ0 / RCS bias; temporal stability | Bias within ±0.5 dB; S1 mission requirement 1 dB (3σ) |
| Geolocation (TC, GSLC) | CR surveyed positions | ALE (m) in range and azimuth | Below 1 resolution cell; with ETAD below 0.2 m |
| RTC (Terrain Flattening + TC) | OPERA RTC-S1; ISCE3 RTC | Bias and RMSE (dB) per land-cover and slope class | Bias within ±0.3 dB on flat ground |
| Thermal noise removal | S1 annotated NESZ; RCM noise | Residual noise floor; inter-swath step | Step below 0.5 dB |
| Coregistration | ISCE3/COMPASS CSLC | Residual offset (px); ESD residual | Below 0.001 px azimuth (TOPS) |
| Interferogram / coherence | ISCE3, Dolphin | Circular std of the phase difference; coherence bias | Below 0.1 rad where coherence > 0.7 |
| Time series (Phase Linking, SBAS) | Dolphin, MintPy; P-SBAS Campi Flegrei; GNSS | LOS velocity RMSE against GNSS | Below 2 mm/yr |
| Polarimetry | PolSARpro | H/A/α and Freeman/Yamaguchi relative difference | Below 1% |
| Ocean wind | ASCAT / ECMWF | Wind speed RMSE | Below 2 m/s |
| Ship detection | AIS | Pd / Pfa | Pd > 0.9 for ships > 50 m |

**Agreement with a peer tool is not correctness.** When truth exists, it is the reference. When two peers disagree with each other, the case records a diagnosis, not a pass. The GSLC campaign's approach (each tool's own reproducibility floor) is the model.

### 6.4 Per-release report
- One page per feature, with a scorecard (pass/fail per metric), the plots, the input and truth provenance, and the exact versions of the SNAP build and peer images.
- A summary table across all features with the T1/T2/T3 status from the register.
- Archived per release; this is the deliverable for ESA reviews.

### 6.5 Migration
1. Create the `microwave-validation` repo. Move `validation/` across with its history (`git filter-repo --subdirectory-filter validation`), then remove it from microwave-toolbox and leave a README pointer.
2. Move `sar-test-stacks` `CRValidation*` into `cases/` as T3 cases. Port `FindCRPosition` and `Plot` into `compare/` and `truth/cr`. The Java CR tests stay until the ported cases reproduce their numbers.
3. Update `validation/README.md`'s rationale section to the §3.2 reasoning.

## 7. Validation register

The register makes "each feature is validated" checkable.

- **Static definitions live in microwave-toolbox**, in `docs/validation/register.yaml`, next to the code they describe. A T1 test in `sar-contract-tests` fails when a registered SPI, reader or shipped graph is missing from the register (drift check, as `check_params.py` does for the docs).
- **Dynamic results live with the runs.** T2 results are in the gpt-tests DB and reports; T3 results are in the microwave-validation reports. The report generator joins them to the register by feature id.

```yaml
- id: Terrain-Correction
  kind: operator                 # operator | reader | writer | graph
  module: sar-op-sar-processing
  priority: P1                   # P1 most-used ... P3 long tail
  t1: {contracts: [C1-C9], oracles: [point-target-flat-dem]}
  t2: {gpt: [s1tbx.standard.terraincorrect.*], readers: []}
  t3:
    - case: tc_geolocation_cr_rosamond
      metric: absolute_ale_m
      threshold: 1.0
      basis: "<mission spec / paper citation>"
  owner: <name>
  notes: ""
```

A generator script, `tools/validation/seed_register.py`, writes the first version from `META-INF/services` and the graph directories, with empty tiers. The register then shows the gaps directly.

**Release gate.** No feature may drop a tier relative to the previous release. Every P1 feature must have T1 and T2 green, and T3 within threshold or a documented diagnosis.

## 8. Phases

| Phase | Work | Exit criterion |
|---|---|---|
| **V0 Scaffold** | The `sar-contract-tests` module; the `SyntheticProducts` factory; the fixture registry; a seeded register; the microwave-validation repo created with the migrated harness | The register lists 115 + 39 + 7 + 97 entries; the harness self-test passes in its new home |
| **V1 T1 sweep** | C1–C9, R1–R8 and the graph static check over all plug-ins | Every failure is either fixed, or exempted with a roadmap item and a reason. *Expect a large first-run failure list; triaging it is the main deliverable, and many failures will map onto roadmap §3–§7 items* |
| **V2 T2 coverage** | 17 reader descriptors; graph-coverage script and the missing graph tests; mission tests; the tolerance model and changelog policy (snap-gpt-tests PR) | The graph-coverage script is green; every reader has a descriptor |
| **V3 T3 core** | Cases for the P1 workflows: S1 GRD→TC (CR radiometry and geolocation, OPERA RTC), S1 TOPS InSAR (ISCE3/Dolphin), Calibration, Speckle, PolSAR decomposition (PolSARpro), SBAS/Phase Linking (Campi Flegrei, GNSS) | Each P1 feature has at least one T3 case with a cited threshold |
| **V4 Report and gate** | Report generator; release gate wired into the RC checklist | The first published per-release validation report |

**Priority.** Most-used workflows first (P1: S1 GRD→TC, TOPS InSAR, Calibration, Speckle, PolSAR decomposition, GSLC). The long tail of readers (roadmap §4) gets T1 contracts in V1, because R1–R5 catch most known reader bugs without real data.

## 9. Risks

| Risk | Mitigation |
|---|---|
| The first T1 run fails on many operators and the sweep is switched off | Land the sweep with a committed **known-failures list**: the test fails only on *new* failures, or on a listed failure that has started passing (so the list shrinks). Each entry links a roadmap item |
| Synthetic fixtures miss real-data behaviour | That is acceptable for T1, whose job is invariants. Realism belongs to T2 and T3. Mini real crops are used where the metadata structure matters (readers, TOPS) |
| C2 bit-identity is too strict for FFT or block operators | A per-operator `tolerance:` with a written reason. The default stays strict, because block-layout dependence was the bug in Goldstein |
| Truth data licences prevent redistribution | `sites/` records the licence. Cases fetch the data from the origin rather than redistributing it; reports show metrics and plots, not the data |
| Peer tools change between versions | Pin the peer Docker images by digest; record the versions in every report |
| Maintenance cost of the expected outputs | Statistics-based tolerances (§5.3) cut churn; the changelog policy makes each regeneration cheap to review |

## 10. Open decisions

1. Hosting of `microwave-validation` (senbox-org GitHub next to snap-gpt-tests, or ESA GitLab), and whether the reports are public.
2. Which runner executes T3 (the existing Jenkins with a Docker-capable agent, or a dedicated machine with the truth data mounted).
3. The source of each T3 threshold: mission requirement documents versus published SNAP or peer results. Each threshold needs a citation before it gates a release.
4. Whether `sar-contract-tests` runs in the default build from V1, or is opt-in until the known-failures list is below a target size.
