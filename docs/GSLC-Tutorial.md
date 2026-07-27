# GSLC Terrain Correction — User Tutorial

**Geocoded, phase-preserving InSAR of the 2026 Venezuela earthquake, in SNAP Desktop and from the command line.**

*This is a hands-on tutorial built on a real Sentinel-1 pair. For the algorithm and the design rationale, read the companion `GSLC-Geocoding-Explained.md`.*

---

## Scenario

On **24 June 2026** an earthquake **doublet** struck northern Venezuela: **Mw 7.2 at 18:04:33 local** (22:04:33 UTC), followed just **39 seconds later** by the **Mw 7.5 mainshock at 18:05:11 local** (22:05:11 UTC), on the right-lateral **San Sebastián fault**. To map the coseismic deformation with the **GSLC** (Geocoded SLC) approach we need an interferometric **pair** — one acquisition before the event and one after:

| Role | Satellite | Acquisition (local / UTC) | Timing vs mainshock |
|------|-----------|---------------------------|---------------------|
| **Reference** (pre-event) | Sentinel-1A | **23 Jun 2026, 18:50:50 / 22:50:50** | ~23.2 h before |
| **Secondary** (post-event) | Sentinel-1C | **24 Jun 2026, 18:49:58 / 22:49:58** | **~45 min after** |

> **Two products, not three.** GSLC InSAR is ordinary *pairwise* interferometry: one reference + one secondary = one interferogram, so **two products** are enough. (This is the key difference from the Phase Linking tutorial, whose distributed-scatterer covariance needs **≥ 3** epochs.) The pair is chosen for the event timing, and the timing here is exceptional: S1C acquired the same track (**relative orbit 106** — shared by S1A, S1C and S1D) just **44 minutes 47 seconds after the mainshock**. S1A the evening before + S1C 45 minutes after therefore bracket the event about as tightly as Sentinel-1 permits: an almost purely **coseismic** pair with a **1-day temporal baseline**, which keeps coherence high even over the tropical vegetation of the epicentral region, and which captures the displacement field before any appreciable afterslip has accumulated. (The 30 Jun *S1D* acquisition makes a good optional **post-seismic** pair against S1C — see the note at the end.)

Download the two products (free, by name) from the **Copernicus Data Space Ecosystem** (<https://dataspace.copernicus.eu>) or **ASF Vertex** (<https://search.asf.alaska.edu>) into a working folder. We process polarisation **VV**, subswath **IW3**. The full product names:

```text
S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5.SAFE
S1C_IW_SLC__1SDV_20260624T224958_20260624T225025_008254_010515_304A.SAFE
```

The pair brackets the earthquake by about a day before and 45 minutes after, so the interferometric phase carries essentially pure coseismic ground displacement, with only the first ~45 minutes of afterslip included. Much of the affected terrain is rural; GSLC's phase-preserving geocoding puts both scenes on a common map grid so the deformation fringes can be formed and interpreted directly.

> **Where the scene sits relative to the rupture.** The doublet's epicentre (**10.435° N, 68.472° W**) lies just *east* of this scene's eastern edge, and the rupture extends ~200 km along the San Sebastián fault. So the interferogram images a substantial western portion of the rupture rather than the epicentral maximum — expect strong fringes across the scene, but do not read the peak displacement here as the event's peak. Published finite-fault models (USGS, INGV, Peking University) put maximum slip at 3.6–4.5 m.

## What you will do

1. Generate the **reference GSLC** from S1A (IW3 / VV) — a phase-preserving, geocoded complex image.
2. Feed the **raw secondary** (S1C) together with the reference GSLC to **`CreateStack`**, which auto-coregisters it onto the reference grid.
3. Form the **interferogram** (removing flat-earth **and** topographic phase), filter and unwrap it to a coseismic displacement map.

You will do it in the **SNAP Desktop** GUI and with **`gpt`** on the command line.

## Why use it

Traditional terrain correction geocodes amplitude and discards phase, so the classical InSAR chain stays in slant range through a long pipeline (Back-Geocoding → ESD → Interferogram → Deburst → Merge → Terrain-Correction) and geocodes last. **GSLC geocodes up front, preserving phase**, so each acquisition becomes a phase-preserving complex product on a **common map grid**. The benefits: fewer steps (no deburst/merge/separate TC), each scene processed independently and in parallel, and coregistration folded into `CreateStack`. It is the "geocode-first" architecture behind global-scale InSAR systems.

## Prerequisites

- **SNAP 14+** with the SNAP Microwave Toolbox. Confirm the operator:
  ```
  gpt GSLC-Terrain-Correction -h
  ```
- The two Sentinel-1 SLC products above, downloaded to a working folder. SNAP reads either the unzipped `.SAFE` folder or the `.zip` directly.
- **Sentinel-1 IW** input must be a **single subswath from `TOPSAR-Split`** (burst-level). **Debursted** TOPS is **rejected**; **GRD** has no phase and is not usable. (Stripmap SLC — e.g. S-1 SM, ENVISAT ASAR IMS — is a direct input after `Apply-Orbit-File`.)
- Internet access for auto-downloaded precise orbits and the **Copernicus 30 m DEM**.

---

## Part A — SNAP Desktop (GUI)

### A1. Generate the reference GSLC (S1A, IW3 / VV)

1. **Prepare the input.** *File ▸ Open Product…* the S1A `.SAFE`, then **Radar ▸ Apply Orbit File**, then **Radar ▸ Sentinel-1 TOPS ▸ S-1 TOPS Split** and select **IW3** + **VV**.
2. **Launch GSLC.** Menu **Radar ▸ Geometric ▸ Terrain Correction ▸ Geocoded SLC Terrain Correction** (dialog title *"GSLC Terrain Correction"*).
3. **I/O Parameters tab.** Source = the orbit-applied, split S1A product; target name gets suffix `_GSLC`; keep `BEAM-DIMAP`.
4. **Processing Parameters tab.** The fields that matter:

   | Field | Default | Notes |
   |-------|---------|-------|
   | Digital Elevation Model | Copernicus 30m Global | Auto-download; or point *External DEM* at a local file |
   | Image Resampling Method | BiSinc 5-point | **Keep a sinc kernel for InSAR** phase fidelity |
   | Pixel Spacing (m / deg) | 0 (auto) | Set explicitly to lock resolution across the pair; becomes the **east** step when a north spacing is also set |
   | Pixel Spacing North (m / deg) | 0 (square) | Optional **rectangular cells** to preserve the SLC's anisotropic native resolution (S1 IW ≈ 3.4 m ground range × 14 m azimuth → set ≈ 3.4 m east × 7.5 m north; the north step must be finer than native azimuth because the orbit heading rotates the radar axes from the map axes). Leave 0 for square cells |
   | Map Projection | WGS84(DD) | Any WKT CRS; use UTM for a metric grid |
   | Output phase-flattened complex data | **false** | **Leave false for InSAR.** True only for amplitude/PolSAR single-date use |
   | Restore TOPS azimuth carrier | **false** | **Leave false.** The carrier is acquisition-specific and does not cancel between acquisitions — restoring it corrupts cross-acquisition InSAR (~tens of spurious fringes per burst) |
   | Apply Solid Earth Tide / Tropospheric | off | Turn on for displacement-grade (sub-decimetre) InSAR |

5. **Run.** The `S1A…_GSLC` product is a phase-preserving complex image on the map grid.

### A2. Build the coseismic interferogram (reference GSLC + raw secondary)

You only geocode the **reference** explicitly; `CreateStack` auto-coregisters the raw secondary.

1. **Prepare the secondary.** Apply-Orbit-File + TOPSAR-Split (**IW3**, **VV**) to **S1C**, but **do not** run GSLC on it.
2. **Create Stack** — *Radar ▸ Coregistration ▸ Stack Tools ▸ Create Stack*: add the **reference GSLC** (S1A) and the **raw split secondary** (S1C). CreateStack reads the reference's `gslc_source_slc_path` stamp, cross-correlates against the secondary to estimate the (Δrange, Δazimuth) bias, rebuilds the secondary GSLC with that bias, and stacks the two on the reference grid.
3. **Interferogram Formation** — *Radar ▸ Interferometric ▸ Products ▸ Interferogram Formation*. In *Processing Parameters*:
   - Keep **Subtract flat-earth phase** ticked.
   - **✔ Tick "Subtract topographic phase".** This simulates the topographic fringes from the DEM and removes them, so the interferogram shows **deformation + residual atmosphere** rather than topography — essential for reading the coseismic signal.
   - **✔ Tick "Subtract Residual Ramp (GSLC)".** Cross-acquisition GSLC interferograms carry a smooth residual ramp (~1 fringe per 80 px) from small differences between the two acquisitions' Doppler annotations — the GSLC-domain analogue of why classical TOPS InSAR needs ESD. This option estimates it robustly (a low-order polynomial fitted to block-wise fringe gradients, too rigid to absorb the localized earthquake signal) and removes it. Without it the ramp survives into unwrapping. Caveat: like classical orbital deramping, it would also absorb a genuine *scene-wide linear* deformation gradient.
   - Keep **Include coherence** ticked.
4. **Goldstein Phase Filtering** → **SnaphuExport → SNAPHU → SnaphuImport** (unwrap) → **Phase to Displacement** (line-of-sight displacement in metres).

   Because the GSLC pair is **already geocoded**, the displacement map is already in map coordinates — **no final Range-Doppler Terrain Correction is needed** (one fewer step than the traditional chain).

> **Consistency rule:** reference and secondary must share the **same** `Output phase-flattened` setting **and** the same `Restore TOPS azimuth carrier` setting (leave the latter at its default, off — restoring the carrier corrupts cross-acquisition interferograms with tens of spurious fringes per burst). Mixing conventions yields a meaningless (noise) interferogram. The CreateStack auto-coregister path enforces both from the reference's metadata stamps.

---

## Part B — Command line (`gpt`)

Run these from your working folder; the graphs take the input products as parameters, so no paths are hard-coded.

### Step 1 — generate the reference GSLC (S1A)

Save as `gslc_reference.xml`:

```xml
<graph id="GSLC-Reference">
  <version>1.0</version>
  <node id="Read"><operator>Read</operator><sources/>
    <parameters><file>${s1a}</file></parameters>
  </node>
  <node id="Apply-Orbit-File"><operator>Apply-Orbit-File</operator>
    <sources><sourceProduct refid="Read"/></sources>
    <parameters><orbitType>Sentinel Precise (Auto Download)</orbitType><continueOnFail>false</continueOnFail></parameters>
  </node>
  <node id="TOPSAR-Split"><operator>TOPSAR-Split</operator>
    <sources><sourceProduct refid="Apply-Orbit-File"/></sources>
    <parameters><subswath>IW3</subswath><selectedPolarisations>VV</selectedPolarisations></parameters>
  </node>
  <node id="GSLC-Terrain-Correction"><operator>GSLC-Terrain-Correction</operator>
    <sources><sourceProduct refid="TOPSAR-Split"/></sources>
    <parameters>
      <demName>Copernicus 30m Global DEM</demName>
      <imgResamplingMethod>BISINC_5_POINT_INTERPOLATION</imgResamplingMethod>
      <pixelSpacingInMeter>0</pixelSpacingInMeter>
      <mapProjection>WGS84(DD)</mapProjection>
      <outputFlattened>false</outputFlattened>
      <applySolidEarthTide>false</applySolidEarthTide>
      <applyTroposphericCorrection>false</applyTroposphericCorrection>
    </parameters>
  </node>
  <node id="Write"><operator>Write</operator>
    <sources><sourceProduct refid="GSLC-Terrain-Correction"/></sources>
    <parameters><file>${output}</file><formatName>BEAM-DIMAP</formatName></parameters>
  </node>
</graph>
```

```
gpt gslc_reference.xml ^
    -Ps1a=S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5.SAFE ^
    -Poutput=S1A_GSLC.dim -c 12G -q 8
```

For **displacement-grade** work, add `-PapplySolidEarthTide=true -PapplyTroposphericCorrection=true` (and use the **same** setting for the secondary, which CreateStack rebuilds from the reference's stamp).

### Step 2 — GSLC interferogram, end to end

`reference GSLC + raw secondary (S1C) → CreateStack (auto-coregister) → Interferogram (flat-earth + topo removed) → Goldstein → Write`. Save as `gslc_insar.xml`:

```xml
<graph id="GSLC-InSAR">
  <version>1.0</version>
  <node id="ReadReference"><operator>Read</operator><sources/>
    <parameters><file>${reference_gslc}</file></parameters>
  </node>

  <!-- raw secondary: orbit + split only (NOT geocoded); CreateStack geocodes it -->
  <node id="ReadSecondary"><operator>Read</operator><sources/>
    <parameters><file>${secondary}</file></parameters>
  </node>
  <node id="OrbitSecondary"><operator>Apply-Orbit-File</operator>
    <sources><sourceProduct refid="ReadSecondary"/></sources>
    <parameters><orbitType>Sentinel Precise (Auto Download)</orbitType><continueOnFail>false</continueOnFail></parameters>
  </node>
  <node id="SplitSecondary"><operator>TOPSAR-Split</operator>
    <sources><sourceProduct refid="OrbitSecondary"/></sources>
    <parameters><subswath>IW3</subswath><selectedPolarisations>VV</selectedPolarisations></parameters>
  </node>

  <node id="CreateStack"><operator>CreateStack</operator>
    <sources>
      <sourceProduct refid="ReadReference"/>
      <sourceProduct.1 refid="SplitSecondary"/>
    </sources>
    <parameters><resamplingType>NONE</resamplingType></parameters>
  </node>
  <node id="Interferogram"><operator>Interferogram</operator>
    <sources><sourceProduct refid="CreateStack"/></sources>
    <parameters>
      <subtractFlatEarthPhase>true</subtractFlatEarthPhase>
      <subtractTopographicPhase>true</subtractTopographicPhase>
      <subtractResidualRamp>true</subtractResidualRamp>
      <demName>Copernicus 30m Global DEM</demName>
      <includeCoherence>true</includeCoherence>
    </parameters>
  </node>
  <node id="GoldsteinPhaseFiltering"><operator>GoldsteinPhaseFiltering</operator>
    <sources><sourceProduct refid="Interferogram"/></sources>
    <parameters/>
  </node>
  <node id="Write"><operator>Write</operator>
    <sources><sourceProduct refid="GoldsteinPhaseFiltering"/></sources>
    <parameters><file>${output}</file><formatName>BEAM-DIMAP</formatName></parameters>
  </node>
</graph>
```

```
gpt gslc_insar.xml ^
    -Preference_gslc=S1A_GSLC.dim ^
    -Psecondary=S1C_IW_SLC__1SDV_20260624T224958_20260624T225025_008254_010515_304A.SAFE ^
    -Poutput=ifg_GSLC.dim -c 12G -q 8
```

The **`subtractTopographicPhase`** flag is the command-line equivalent of the *Subtract topographic phase* checkbox — it removes the DEM-simulated topographic fringes so the coseismic deformation signal remains. **`subtractResidualRamp`** removes the smooth residual ramp specific to cross-acquisition GSLC interferometry (~1 fringe per 80 px, from small differences in the two acquisitions' Doppler annotations); enable it for visualization and unwrapping, but be aware that — like classical orbital deramping — it would also absorb a genuine scene-wide linear deformation gradient. Export → SNAPHU → import to unwrap, then **Phase to Displacement**; the result is already geocoded.

For the full worked example with figures, see the notebook **`snap-nb-sar-gslc-insar`**.

## How does it compare to the traditional chain?

For the same S1A × S1C pair, the traditional pipeline (`Back-Geocoding → Interferogram → Deburst → Goldstein → Terrain-Correction`) produces this:

![Traditional-chain interferogram of the same pair: Goldstein-filtered in radar geometry, then terrain-corrected. Visually it can appear smoother at a glance, because Terrain-Correction's interpolation of the wrapped phase acts as cosmetic smoothing — but that same interpolation is destructive at fringes, where it averages values across ±π boundaries.](images/gslc_s1a_s1c_ifg_trad_tc.png)

Measured objectively on the same ground pixels (both products on ~14 m map grids), the GSLC interferogram is the better input for unwrapping — decisively so in the coseismic fringe zone:

| Zone | Phase residues / 10⁴ px (lower = unwraps better) | 5×5 local phase coherence (higher = cleaner) |
|------|-----------------------------------|-----------------------------|
| **Near-fault fringes** | **GSLC 588** vs traditional 998 | **GSLC 0.75** vs traditional 0.65 |
| North (atmosphere) | GSLC 460 vs traditional 407 | 0.79 vs 0.78 |
| South (vegetation) | GSLC 1604 vs traditional 1685 | 0.46 vs 0.44 |

Two lessons:

- **GSLC never resamples wrapped phase.** The interferogram is born on the map grid, filtered there, and is final — there is no post-filter geometric resampling anywhere in the chain. The traditional quick-look above terrain-corrects a *wrapped* phase raster, which smears exactly the dense fringes that carry the deformation signal (41% more residues in the near-fault zone). In the traditional chain the proper practice for displacement products is to unwrap in radar geometry *first* and terrain-correct the unwrapped phase — GSLC sidesteps the issue entirely.
- **If you want the GSLC result to look smoother too**, give the Goldstein filter more samples: the square 14 m grid carries ~4× fewer independent range samples per filter window than the native SLC sampling. Rerunning the GSLC step with **rectangular cells** (Pixel Spacing ≈ 3.4 m east × 7.5 m north, see the table in A1) restores the native sample density — smoother filtered output *and* fewer residues, still with no wrapped-phase resampling.

> **Optional extension — post-seismic pair.** The 30 Jun **S1D** acquisition (same track, `S1D_IW_SLC__1SDV_20260630T225009_20260630T225040_003472_006226_2543.SAFE`) pairs with S1C (24 Jun) to image the **first six days of post-seismic motion**: repeat the same workflow with S1C as reference and S1D as secondary. Comparing the coseismic and post-seismic interferograms is a compact demonstration of why acquisition timing matters as much as processing.

---

## Reading / validating the output

- The `S1A_GSLC` product is a geocoded complex image (`i_/q_` bands) plus any diagnostic bands you enabled (DEM, lat/lon, incidence, layover-shadow, simulated phase).
- After `Interferogram`, inspect the **coherence** band and the **wrapped phase** (fringes). Over stable terrain coherence should be high and fringes smooth; the fringes that crowd toward the fault trace are the coseismic deformation. Sanity-check against a traditional-pipeline interferogram of the same S1A/S1C pair. Because this is a long right-lateral strike-slip rupture, expect **fault-parallel, elongated** fringe bands whose spacing tightens toward the fault (each fringe = 2.8 cm of line-of-sight displacement) — not concentric lobes about a point — with narrow decorrelated strips where the near-field gradient exceeds one fringe per pixel.

![GSLC coherence of the S1A 23 Jun × S1C 24 Jun coseismic pair (IW3, VV). The 1-day temporal baseline keeps coherence high across most of the scene; dark patches are water, dense vegetation and steep slopes.](images/gslc_s1a_s1c_coherence.png)

![Goldstein-filtered GSLC interferogram of the same pair, produced with `subtractTopographicPhase` and `subtractResidualRamp`. Coseismic fringes crowd toward the San Sebastián fault trace — each fringe is 2.8 cm of line-of-sight displacement; the phase decorrelates only in the near field where the deformation gradient exceeds one fringe per pixel. Larger smooth patches to the north are residual atmosphere.](images/gslc_s1a_s1c_ifg_filtered.png)
- **Judge the phase after filtering or multilooking, not at full resolution.** A single-look interferogram at realistic coherence (γ ≈ 0.2 over vegetation) looks like noise on screen even when it is perfectly correct — and a terrain-corrected comparison image has been implicitly smoothed by its resampling. Apply Goldstein filtering or a 4×4 multilook before deciding whether the interferogram "worked".
- **Write the stack to disk before running `Interferogram` on large scenes** (as the graphs in this tutorial do). Chaining Interferogram directly onto an in-memory CreateStack→GSLC graph recomputes geocoding tiles many times over and can appear to hang.

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| Operator rejects a debursted TOPS product | GSLC needs a **split (burst-level)** TOPS product or a Stripmap SLC; debursted TOPS is explicitly rejected. **GRD** has no phase — it isn't blocked but yields a degenerate product, so don't use it. |
| Interferogram is pure noise | (1) `outputFlattened` mismatched between reference and secondary — must be identical; (2) large residual misregistration — use the CreateStack auto-coregister path. |
| Coherence lower than expected | Check DEM quality; for displacement scenes enable SET + troposphere; verify like-for-like comparison (same multilook, same area). Note S1A↔S1C is a cross-platform constellation pair — confirm they share the relative orbit/track (this pair: both on relative orbit 106). |
| Reference and secondary don't align | Ensure both use the same pixel spacing (GSLC always snaps to the shared standard grid automatically); let CreateStack set the sub-pixel `rangeOffsetPixels`/`azimuthOffsetPixels`. |
| Topographic fringes still dominate the interferogram | Enable **Subtract topographic phase** (`subtractTopographicPhase=true`) with a valid DEM. |
| Regular fringes / wavy "fringe packets" across the whole scene that the traditional interferogram doesn't show | The cross-acquisition GSLC residual ramp (~1 fringe per 80 px; at a decimated screen zoom it aliases into wavy packets). Enable **Subtract Residual Ramp (GSLC)** (`subtractResidualRamp=true`) on the Interferogram step. |
| Slow / huge output over sea | Keep *Mask out areas with no elevation* (`nodataValueAtSea=true`) on. |

## Next steps

- **Jupyter notebooks** — full, runnable SAR/InSAR workflows: <https://github.com/senbox-org/snap-jupyter-notebooks>. See `snap-nb-sar-gslc-insar` for the complete GSLC-InSAR example with figures.
- Algorithm, parameters, and the coregistration model: `GSLC-Geocoding-Explained.md`.
- Operator parameter reference: in-app Help (F1 in the dialog) → *GSLC Terrain Correction*.
