# Classical-control recipes (per mission)

This directory holds two reproducible "traditional" (non-GSLC) InSAR control chains,
used as the comparison baseline for GSLC-vs-classical parity checks
(`validation/gslc_equivalence.py`, `validation/README-equivalence.md`):

- `graphs/trad_ers_control.xml` — ERS classical stripmap chain: coregistration
  (CreateStack + Cross-Correlation + Warp) + Interferogram, in one graph, plus a
  second-step TopoPhaseRemoval.
- `graphs/trad_s1_control.xml` — Sentinel-1 IW TOPS classical chain: Back-Geocoding +
  Enhanced-Spectral-Diversity + Interferogram (topo phase removed inline) +
  TOPSAR-Deburst.

Both graphs are parameterized with gpt graph variables `${input1}`, `${input2}`,
`${output}` — no absolute paths are baked in.

## Running the graphs

Run from the **repo root** using the maven-exec GPT idiom (this repo's modules are not
published/installed, so `gpt` on PATH will not resolve the InSAR/Sentinel-1 SPIs unless
they come from the reactor classpath):

### ERS control (step 1: stack + coregistration + interferogram)

```bash
MAVEN_OPTS="-Xmx20g" mvn -q -pl sar-op-insar exec:java \
  -Dexec.mainClass=org.esa.snap.core.gpf.main.GPT \
  -Dexec.classpathScope=compile \
  '-Dexec.args=validation/graphs/trad_ers_control.xml -Pinput1=E:/Output/ers/ERS-1_..._Orb.dim -Pinput2=E:/Output/ers/ERS-2_..._Orb.dim -Poutput=E:/Output/ers/trad_ers_ifg.dim'
```

### ERS control (step 2: TopoPhaseRemoval, run against step 1's output)

`TopoPhaseRemoval`'s SPI lives in `jlinda-nest` (alias `TopoPhaseRemoval`,
`org.jlinda.nest.gpf.SubtRefDemOp`), not `sar-op-insar`, and it has no cross-process GCP
dependency (see constraint below), so it is run as an **independent, second gpt
invocation** in single-operator mode (no graph file needed) directly against step 1's
disk output:

```bash
MAVEN_OPTS="-Xmx20g" mvn -q -pl jlinda/jlinda-nest exec:java \
  -Dexec.mainClass=org.esa.snap.core.gpf.main.GPT \
  -Dexec.classpathScope=compile \
  '-Dexec.args=TopoPhaseRemoval -SsourceProduct=E:/Output/ers/trad_ers_ifg.dim -PdemName="Copernicus 30m Global DEM" -t E:/Output/ers/trad_ers_ifg_TC.dim -f BEAM-DIMAP'
```

### Sentinel-1 TOPS control (single step)

```bash
MAVEN_OPTS="-Xmx20g" mvn -q -pl sar-op-sentinel1 exec:java \
  -Dexec.mainClass=org.esa.snap.core.gpf.main.GPT \
  -Dexec.classpathScope=compile \
  '-Dexec.args=validation/graphs/trad_s1_control.xml -Pinput1=/data/S1_ref.zip -Pinput2=/data/S1_sec.zip -Poutput=E:/Output/s1/trad_s1_ifg.dim'
```

(`sar-op-sentinel1` is used as the `-pl` module purely to get a reactor classpath that
includes `sar-op-sentinel1` + its `sar-op-insar` dependency; any module that depends on
both would work equally well.)

## CRITICAL constraint: the ERS coregistration chain must be ONE graph

`CreateStack`, `Cross-Correlation`, and `Warp` **must run inside a single graph / single
gpt process** — this is not a style preference, it is required for correctness.

`GCPManager` is an **in-memory singleton** keyed by product instance. `Cross-Correlation`
computes ground-control points and registers them with the live in-memory `GCPManager`
instance attached to the product object in that JVM. Those GCPs are **not** persisted as
part of the product when it is written to disk (BEAM-DIMAP or otherwise). If you split the
chain across two gpt invocations — e.g. `CreateStack` → write → `Cross-Correlation` → write
→ (new gpt process) `Warp` → write — the second process re-reads the product from disk with
**no GCPs attached**, `Warp` finds zero (or a stale/default) GCP set, and it **silently
writes a zeroed-out product**. There is no error, exception, or warning; the failure only
shows up as garbage/blank output when you inspect the result.

This constraint was hit and confirmed experimentally in this project. The core of
`trad_ers_control.xml` (`Read x2 -> CreateStack -> Cross-Correlation -> Warp -> Write`) is
not merely theoretical — it is the exact chain that was already run successfully,
end-to-end, in this session as `E:/ESA/snap_tmp/ers_trad_warp_graph.xml`, which produced a
non-degenerate `trad_warp_stack.dim`. `trad_ers_control.xml` extends that proven graph by
adding the `Interferogram` node after `Warp`, in the same single graph, and keeps
`TopoPhaseRemoval` out (as a deliberate second step — see above) since it has no such
in-process state dependency.

`TOPSAR-Deburst`/`Back-Geocoding`/`Enhanced-Spectral-Diversity`/`Interferogram` in the S1
TOPS chain have no equivalent GCPManager dependency, so `trad_s1_control.xml` has no
"must be one graph" requirement — it is a single graph purely for convenience.

## Operator alias verification

Each operator alias used in the two graphs was checked against its
`@OperatorMetadata(alias = "...")` in this repo:

| Alias used in graph | Class | Module |
|---|---|---|
| `CreateStack` | `CreateStackOp` | `sar-op-insar` |
| `Cross-Correlation` | (Cross-Correlation SPI) | `sar-op-insar` |
| `Warp` | (Warp SPI) | `sar-op-insar` |
| `Interferogram` | `InterferogramOp` | `sar-op-insar` |
| `TopoPhaseRemoval` | `SubtRefDemOp` | `jlinda/jlinda-nest` |
| `Back-Geocoding` | `BackGeocodingOp` | `sar-op-sentinel1` |
| `Enhanced-Spectral-Diversity` | `SpectralDiversityOp` | `sar-op-sentinel1` |
| `TOPSAR-Deburst` | `TOPSARDeburstOp` | `sar-op-sentinel1` |

No alias mismatches were found; all match the graph XML exactly.

## Terrain-Correction traps when comparing against GSLC products

When running `Terrain-Correction` on the output of either control chain (or on a GSLC
product) to bring it onto a common geocoded grid for comparison, three traps were measured
in this project:

**(a) TC on a product with `Phase` virtual bands crashes with `Undefined symbol`.**
Complex interferogram products carry derived virtual bands (e.g. `Phase_ifg...`,
`Intensity_ifg...`) computed from the underlying `i_ifg`/`q_ifg` raw bands via a band-math
expression. If you run `Terrain-Correction` on the whole product without explicitly
selecting source bands, it may attempt to geocode/resample a virtual `Phase` band whose
expression references bands that are not resolvable in the internal processing context,
and it fails with `Undefined symbol` (band-math expression evaluation error), not a
useful geocoding error. **Fix: always pass an explicit `-PsourceBands=...` selection** so
`Phase`/other virtual bands are never implicitly pulled in.

**(b) Explicit `-PsourceBands` selection disables InSAR auto-complex handling.**
Terrain-Correction has an auto-detect path that recognizes `i_*`/`q_*` real/imaginary band
pairs and geocodes them together as a complex pair (interpolating amplitude/phase
correctly rather than treating them as two independent real rasters). That
auto-complex-detection path is **only entered when no explicit `sourceBands` parameter is
given**. As soon as you set `-PsourceBands=...` to work around trap (a), you disable the
auto-complex path, and the `i`/`q` bands get collapsed into a plain `Intensity` band
(losing phase information) instead of being kept as a complex pair.

**(c) The working combination: explicit `i`/`q` band selection + `outputComplex=true`.**
The trap (a)/(b) combination is resolved by *both*:
```
-PsourceBands=i_ifg_<tag>,q_ifg_<tag>   # explicit i/q selection avoids trap (a)
-PoutputComplex=true                     # explicitly re-enables complex-pair handling, avoiding trap (b)
```
i.e. select exactly the `i`/`q` raw bands you need (never the virtual `Phase`/`Intensity`
bands) **and** set `outputComplex=true` explicitly so TC treats the selected pair as a
complex pair even though an explicit band list was given.

**Bonus gotcha:** Terrain-Correction output bands gain a mission/polarization suffix —
e.g. `i_ifg_01Aug1995_02Aug1995` becomes `i_ifg_01Aug1995_02Aug1995_VV` after TC. Any
downstream tooling that matches band names exactly (rather than by glob/prefix) must
account for this `_VV` suffix. `validation/gslc_equivalence.py` already glob-matches
`i_ifg*`/`q_ifg*` for exactly this reason (see `validation/README-equivalence.md`).

## Validation performed for this task

Both graph files were checked for XML well-formedness with:

```bash
python -c "
import xml.etree.ElementTree as ET
for f in ['validation/graphs/trad_ers_control.xml', 'validation/graphs/trad_s1_control.xml']:
    tree = ET.parse(f)
    print(f, 'OK, root=', tree.getroot().tag)
"
```

Both parsed cleanly (`root=graph`). The full chains were **not** executed here (each run
is hours of wall-clock time). Instead, the ERS graph's coregistration core
(`CreateStack -> Cross-Correlation -> Warp`) is already proven correct in this session by
the prior successful run of `E:/ESA/snap_tmp/ers_trad_warp_graph.xml`, which produced a
valid, non-degenerate `trad_warp_stack.dim` — `trad_ers_control.xml` is that exact proven
graph with an `Interferogram` node appended. The S1 TOPS graph and the ERS
`TopoPhaseRemoval` second step have not been executed end-to-end as part of this task;
their operator aliases and parameter names were instead verified directly against the
`@OperatorMetadata`/`@Parameter` annotations in source (see table above), and remain to be
exercised against real fixture data in a later task.
