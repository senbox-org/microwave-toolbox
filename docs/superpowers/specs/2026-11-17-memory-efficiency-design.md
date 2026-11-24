# Memory Efficiency: Design

**Date:** 2026-11-17
**Status:** proposed. **§5.2 (heap sweep in snap-gpt-tests) superseded 2026-11-20:** the release heap sweep is built in `snap-validation` ([design](2026-11-20-snap-validation-design.md) §7), S1 only for now. Order of work: roadmap §9.0.
**Roadmap:** [§8 Memory efficiency](../../roadmap.md#8-memory-efficiency-workstream)
**Owner:** microwave-toolbox. Items marked **[Cross-repo]** need changes in snap-engine or snap-installer.
**Companion:** [Validation framework design](2026-11-17-validation-framework-design.md). Its tier T2 runs the heap sweep defined here.

---

## 1. Problem

Users say SNAP uses too much memory and that this makes it slow. The symptoms are:
- GC thrash: the heap is nearly full and the JVM spends its time collecting garbage.
- OS swapping when `-Xmx` is set close to physical RAM.
- `OutOfMemoryError` on 8 to 16 GB laptops for workflows that other tools run in a few GB.

We cannot say today which workflows are affected, by how much, or what holds the memory, because nothing measures it:

| Instrument | What it records | Why it does not answer the question |
|---|---|---|
| `microwavetbx-benchmark` (`Benchmark.java:182-187`) | Run times (min/max/avg/median/stddev) and an environment record with `maxHeapMB` and `jaiCacheMB` | Records no memory use at all |
| `snap-gpt-tests` performance DB (`pygpt/core/dbadaptor.py:459-463`) | Process `memory_avg` and `memory_max` (RSS) | Every test runs at a fixed `configVM.xmx` (for example 5G). A JVM grows towards `-Xmx` whatever it needs, so the RSS mostly shows the setting, not the requirement |

The shipped defaults also decide what users experience. The design has to measure *with* them:
- `snap.conf` uses `-Xmx${installer:maxHeapSize}`, which the installer sets from physical RAM.
- `snap.properties` sets `snap.jai.defaultTileSize=512`. `snap.jai.tileCacheSize` is commented out (1024), so the code default applies.
- The snap-engine `ProductCache` budget scales with the heap (since the accounting fix).

## 2. Goals and non-goals

**Goals**
- **G1 Measure.** Know the **minimum heap** of each standard workflow (§3), and track it per release.
- **G2 Reduce.** Cut the minimum heap of the three worst workflows by at least 30%, with no workflow getting worse and outputs unchanged (bit-identical, or within the T2 tolerance where float reordering is unavoidable).
- **G3 Remove the anti-pattern.** No full-scene allocation or full-scene `getSourceTile` in operator code, except reviewed and listed exceptions.
- **G4 Guard.** CI fails when a change raises the memory a workflow needs.
- **G5 Inform.** Publish a "how much memory do I need" table per workflow, and review the shipped defaults against it.

**Non-goals**
- Replacing JAI or the GPF tile scheduler. Proposals for snap-engine are written as cross-repo design notes, not changed here.
- Off-heap or native storage of tiles.
- Desktop UI memory beyond the single "open, view and pan a large product" scenario (W7).

## 3. Metrics

All metrics are measured on the **installed build run through `gpt`** (or SNAP Desktop for W7), with the shipped defaults unless stated otherwise. That is what users run.

| Metric | Definition |
|---|---|
| **MinHeap(w)** | The smallest `-Xmx` on the ladder {1.5, 2, 3, 4, 6, 8, 12, 16} GB at which workflow *w* (a) finishes, (b) writes output that passes its T2 comparison, and (c) spends **less than 20% of wall time in GC pauses**. A run that finishes but thrashes counts as a **fail**, because thrashing is what users see as "slow". Then bisect between the last failing and first passing rungs, in 256 MB steps. |
| **Wall(w, X)** | Wall time at `-Xmx` X = 4, 8 and 16 GB. |
| **GCShare(w, X)** | Total GC pause time / wall time, from `-Xlog:gc*`. |
| **PeakLive(w)** | The largest heap occupancy *after* a GC, from the JFR `GCHeapSummary` "After GC" events. This is the live working set, separate from garbage not yet collected. |
| **AllocRate(w)** | MB/s allocated, from JFR `ObjectAllocationSample`. A high rate with a low PeakLive points to churn (temporary arrays per tile), not retention. |
| **Retainers(w)** | Retained size by owner (operator class, reader, cache, metadata tree), from a heap dump taken at PeakLive. |

The cache budgets scale with the heap, so MinHeap includes the caches by design. To separate the working set from the cache, each workflow also runs once with the caches pinned (`snap.jai.tileCacheSize=256`, ProductCache disabled).

## 4. Workflow suite

Each workflow has a **full** variant (a real scene, for the sweep) and a **crop** variant (a committed or small TestData fixture, for CI guard rails, §7.3). Inputs come from the shared `TestData.inputSAR` tree (`E:\TestData\s1tbx\SAR`, synced to OVH S3); the exact products are chosen in phase P0.

| ID | Workflow | Why it is in the suite |
|---|---|---|
| W1 | S1 IW GRDH → Apply Orbit → Thermal Noise → Calibrate → Speckle Filter → Terrain Correction | The most-used chain; the first thing a new user runs |
| W2 | S1 IW SLC pair → TOPS Split → Apply Orbit → Back-Geocoding → ESD → Interferogram → Deburst → TopoPhaseRemoval → Goldstein → Terrain Correction | The most-used InSAR chain; many bands at full SLC resolution |
| W3 | 10-date S1 IW SLC stack: classical (Back-Geocoding stack) and GSLC (Create Stack) | Stack size scales memory; this is where the 3×S1 SLC OOM was found |
| W4 | RS2 or RCM quad-pol → Polarimetric Matrices (T3) → Decomposition → Wishart classification | The full-scene Wishart arrays (§6) |
| W5 | SBAS inversion on a 100-date, about 300-pair network | Source tiles grow with the pair count (roadmap §7.3) |
| W6 | Large stripmap or spotlight product (CSG or ICEYE SLC) → Calibrate → Multilook → Terrain Correction | Readers of large products; the striped-TIFF and CSG cases |
| W7 | SNAP Desktop: open a full S1 SLC, open an image view, pan and zoom for 60 s | The Desktop path users complain about; measured via a JFR recording of the Desktop JVM |

## 5. Measurement architecture

There are three instruments, each where its runtime already lives.

### 5.1 In-process probe (developer loop): `microwavetbx-benchmark`
- Add a `MemoryProbe` class next to `Benchmark.java`:
  - registers a `GarbageCollectorMXBean` notification listener (`com.sun.management.GarbageCollectionNotificationInfo`) to record heap-after-GC and pause durations;
  - reads `MemoryPoolMXBean.getPeakUsage()` for the old gen;
  - reads `ThreadMXBean.getTotalThreadAllocatedBytes` (or the per-thread `getThreadAllocatedBytes`) for allocation.
- `Benchmark.writeResults` writes `peakLiveMB`, `allocMB`, `gcPauseMs` and `gcShare` next to the run times.
- The probe gives developers a quick before-and-after signal at a fixed heap. It does **not** give MinHeap, because `-Xmx` cannot change inside one JVM.

### 5.2 Heap sweep (release tracking): `snap-gpt-tests`
- snap-gpt-tests already launches `gpt` with a per-test `configVM` (`xmx`, `cacheSize`, `parallelism`) and stores performance rows in its DB. The sweep extends it rather than adding a new runner.
- Add a new optional test-JSON field `"heapSweep": {"ladder": ["1500M","2G",...], "gcShareMax": 0.2}`. When it is present, the runner:
  1. runs the test once per rung, as a forked `gpt` with `-Xmx<rung> -Xlog:gc*:file=<run>/gc.log -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<run>`;
  2. parses `gc.log` for GCShare;
  3. bisects to MinHeap;
  4. writes `min_heap_mb`, `gc_share` and `wall_s` per rung to two new columns and a new table in the DB.
- The suite W1–W6 becomes six gpt tests with `heapSweep` set, scoped as `memory`, and run per release candidate. The sweep is slow (up to 8 runs per workflow), so it does not run nightly.

### 5.3 Profiling procedure (diagnosis)
A documented, manual procedure in `microwavetbx-benchmark/MEMORY-PROFILING.md`:
1. Run the workflow at MinHeap + 1 GB with `-XX:StartFlightRecording=settings=profile,filename=<w>.jfr` (allocation profiling and Old Object Sample are on in the `profile` settings).
2. Take a heap dump at PeakLive: `jcmd <pid> GC.heap_dump`, triggered when the probe sees heap-after-GC above 80% of the maximum.
3. Analyse it with Eclipse MAT: run the dominator tree, then group by class loader / package to attribute memory to an operator, reader, cache or metadata.
4. Record the top 10 retainers with MB in the ranking table (§8 phase P1).

## 6. Known suspects (from a code scan; P1 confirms or clears each)

| # | Location | Allocation | Size at a typical scene | Proposed fix | Test |
|---|---|---|---|---|---|
| S1 | `rstb-op-classification/.../GeneralWishart.java:213-215` | `int[H][W]` category + `int[H][W]` cluster + `double[H][W]` dominantPower = 16 B/px | 1.6 GB at 10k×10k | Two passes: (1) train the cluster centres on a strided sample (or per-tile accumulators reduced at the end); (2) classify per tile against the centres. Only the centres are kept between tiles | The tile-size invariance contract (validation T1) plus a golden comparison on the RS2 quad fixture; the class map must match the current one except at sampling-induced boundary pixels (define the tolerance as a % of pixels) |
| S2 | `rstb-op-classification/.../FreemanDurdenWishart.java:162-163` | `int[H][W]` + `double[H][W]` = 12 B/px | 1.2 GB at 10k×10k | Same as S1 | Same as S1 |
| S3 | `sar-op-feature-extraction/.../ForestAreaClassificationOp.java:225` | `byte[H][W]` mask | 0.4 GB at a GRD scene | Compute per tile with an overlap margin, or a band-backed (disk) image | Tile-size invariance |
| S4 | `sar-op-sar-processing/.../RangeDopplerGeocodingOp.java:1192` | `byte[H][W]` layoverShadowMask (source geometry) | about 420 MB at an S1 GRDH (25k × 16.7k) | It is filled by a pre-pass over `sourceImageWidth × 10` tiles. Replace it with a per-target-tile computation over the source footprint plus a margin, or keep it as a lazily computed source-geometry image with its own tile cache | TC output with and without `saveLayoverShadowMask` must be bit-identical to today |
| S5 | `sar-op-insar/.../EmpiricalTropoCorrectionOp.java:308-309` | `getSourceTile(0,0,W,H)` for phase and coherence | 8 B/px: gigabytes on an unmultilooked IW interferogram | Read only the strided rows, `getSourceTile(0, y, W, 1)` for y in steps of `stride`, or one decimated `SubsampledImage`. The code comment already describes this intent | The fitted coefficients must be identical to today on a fixture where the stride sample is the same set of pixels |
| S6 | SBAS (`SBASInversionOp`) | Source tiles for all pairs per target tile | about 600 MiB per tile at 300 pairs | Choose the tile height from the pair count; a Cholesky solve in a reused workspace (roadmap §7.3) | W5 MinHeap before and after |

**Checked and cleared** (not hotspots; recorded so nobody re-audits them):
- `TerraSarXProductDirectory.java:703`: `readRasterDataFully` on the `COMMON_AUXRASTER` shift grid, which is a small auxiliary raster.
- `ETADUtils.java:436-438`: `readRasterDataFully` on an ETAD layer band plus a `double[azimuthExtent][rangeExtent]` copy. The extents are the ETAD **grid** dimensions from the annotation `gridDimensions` (coarse), not SLC pixels.
- The `float[gridWidth * gridHeight]` arrays in the RS1/RS2/RCM/Capella/ALOS readers are tie-point grids.
- `BackGeocodingOp.java:582-587, 1510-1515`: `numLines × numPixels` is the DEM extent of one burst, not the scene.

## 7. Systemic issues (measure in P1, then decide)

### 7.1 Metadata duplication **[Decision] [Cross-repo]**
118 operator classes in this repo call `ProductUtils.copyProductNodes` or `copyMetadata`. Each node in a graph therefore holds its own deep copy of the metadata tree. For S1 SLC that tree includes `Original_Product_Metadata`: the annotation, calibration and noise vectors for every swath and polarisation.

Measure this first: in the W2 and W3 heap dumps, record the retained size of all `MetadataElement` and `MetadataAttribute` objects. If it is more than about 10% of PeakLive, the options in order of effort are:
1. **Slim the readers.** S1, RCM and RS2 load the noise and calibration vectors into metadata eagerly. Keep them in the reader's own structures and expose them through the calibrator (the calibrators already parse them).
2. **Shared read-only `Original_Product_Metadata`.** Operators copy `Abstracted_Metadata` (small, edited by operators) but *share* the original tree by reference. This needs a read-only `MetadataElement` view in snap-engine.
3. **Copy-on-write metadata** in snap-engine. This is the most general option, and the most invasive.

### 7.2 Competing caches **[Decision] [Cross-repo]**
Four memory consumers each size themselves on their own:
- the JAI tile cache (`snap.jai.tileCacheSize`);
- the snap-engine `ProductCache` (heap-scaled);
- the DEM tile caches, which may be one per operator instance (to be checked in P1: is the Copernicus DEM cache shared across Terrain Correction, Back-Geocoding and Terrain Flattening in one graph?);
- the orbit and ephemeris caches.

Together they can claim more than the heap minus the working set. P1 measures each one's peak in W1–W3. If the sum exceeds 60% of `-Xmx` at MinHeap, propose a **memory governor** in snap-engine: one budget split between the caches, with the working-set headroom reserved first.

### 7.3 Lifecycle leaks
The `sar-test-stacks` OOM showed operator chains that are never disposed (DEM caches and metadata copies stay reachable). Audit the paths users take:
- the SNAP Desktop single-operator dialog "Run", and the Graph Builder;
- Batch Processing (one graph per product in one JVM): MinHeap must not grow with the number of products. Test this with a 10-product batch and compare heap-after-GC after product 1 and after product 10;
- `gpt` with `-c` and multiple graphs.

The `Operator.finalize` fix (`Reference.reachabilityFence`) is related: unreferenced operators must be disposed deliberately, not by GC.

### 7.4 Data types and tile geometry **[Hygiene]**
- Intermediates kept as float64 where float32 is enough. Survey the `ProductData.TYPE_FLOAT64` target bands.
- Tiles that span the full width on S1. A 25k-wide tile row × bands × threads multiplies quickly; check the operators that force `sourceImageWidth`-wide tiles (for example the Terrain Correction pre-pass and `RemoveGRDBorderNoiseOp:658`).

### 7.5 Shipped defaults **[Decision] [Cross-repo]**
After P1, review these against the MinHeap table:
- the installer `maxHeapSize` fraction;
- `snap.jai.tileCacheSize` (the default when it is commented out);
- `snap.jai.defaultTileSize=512`;
- `snap.parallelism`, since a higher thread count means more tiles in flight.

Publish the table in the user docs (guides-docs-tutorials), with the minimum and the recommended heap per workflow.

## 8. Phases

| Phase | Work | Exit criterion |
|---|---|---|
| **P0 Instrument** | `MemoryProbe` (§5.1); `heapSweep` in snap-gpt-tests (§5.2); choose W1–W7 inputs; write MEMORY-PROFILING.md | A baseline MinHeap / Wall / GCShare table for W1–W6 on the current build **and on SNAP 13**, committed to `docs/superpowers/reports/` |
| **P1 Profile and rank** | JFR plus a heap dump for each workflow (§5.3); confirm or clear S1–S6; measure metadata, cache and leak shares (§7) | A ranked retainer table with MB per owner per workflow; decisions taken on §7.1, §7.2 and §7.5 |
| **P2 Local fixes** | Fix the suspects confirmed in P1, worst first. Each fix ships with its test from §6 and a before-and-after MinHeap | G2 met for the workflows the local fixes affect |
| **P3 Systemic** | Metadata (§7.1 option 1 in this repo; options 2–3 as a snap-engine PR); the cache governor proposal; lifecycle fixes | Each change has a MinHeap delta; the snap-engine design notes have been filed |
| **P4 Guard rails** | §9 | CI fails on a seeded regression (prove it by deliberately reintroducing S5 on a branch) |

## 9. Guard rails

1. **Bounded-heap tests.** For each suite workflow, the crop variant runs under `@RunWith(LongTestRunner.class)` in a forked surefire JVM with a fixed small `-Xmx`: crop MinHeap from P0 + 25%, at least 768 MB. Use a dedicated surefire execution, so the fork's `argLine` carries the limit. If the test runs out of memory, or the probe reports GCShare above 0.2, it fails.
2. **Static check.** A unit test in `sar-commons` scans the compiled operator classes (or an ArchUnit rule) and fails on:
   - `getSourceTile` with a `Rectangle(0, 0, <scene width>, <scene height>)` inside `computeTile` or `computeTileStack`;
   - `new <primitive>[sceneHeight][sceneWidth]`.

   Reviewed exceptions sit in an allow-list with a reason. A pragmatic first version greps the sources, using the patterns from this spec's scan, and asserts the hit list equals the allow-list.
3. **Release tracking.** The P0 table is regenerated for every release candidate by the snap-gpt-tests `memory` scope. Any MinHeap increase above 10% on any workflow blocks the release, unless it is explained in the release notes.

## 10. Risks

| Risk | Mitigation |
|---|---|
| MinHeap is noisy: GC timing and thread scheduling vary | Run each rung twice and take the worse result; bisect in 256 MB steps, not finer; fix `parallelism` in the sweep |
| Tiling the Wishart classifiers changes the class maps | That is accepted if the difference is limited to sampling. The tolerance is set in the S1/S2 test; document it in the release notes |
| The biggest wins are in snap-engine (metadata, caches), outside this repo's control | Do the in-repo option first (§7.1 option 1); file the snap-engine design notes early in P1 with measured numbers |
| The heap sweep makes the RC test run much longer | A `memory` scope runs only at RC time, on a dedicated agent; the crop guard rails give the per-commit signal |

## 11. Open decisions

1. The GC-share threshold for "thrashing": the 20% proposed here.
2. Whether the SNAP 13 baseline is worth the effort. It is recommended, because it tells users and ESA whether SNAP 14 got better or worse.
3. Which collector to ship. The default G1 is assumed. ZGC or Shenandoah could cut pauses for Desktop, but not the footprint; this is out of scope unless P1 shows that pause time rather than footprint dominates.
