# GSLC Terrain Correction — User Tutorial

**Geocoded, phase-preserving InSAR of the 2026 Venezuela earthquake, in SNAP Desktop and from the command line.**

*This is a hands-on tutorial built on a real Sentinel-1 pair. For the algorithm and the design rationale, read the companion `GSLC-Geocoding-Explained.md`.*

---

## Scenario

In June 2026 an earthquake struck Venezuela. To map the coseismic deformation with the **GSLC** (Geocoded SLC) approach we need an interferometric **pair** — one acquisition before the event and one after:

| Role | Satellite | Acquisition |
|------|-----------|-------------|
| **Master** (reference / pre-event) | Sentinel-1A | **23 Jun 2026** |
| **Slave** (post-event) | Sentinel-1D | **30 Jun 2026** |

> **Two products, not three.** GSLC InSAR is ordinary *pairwise* interferometry: one master + one slave = one interferogram, so **two products** are enough. (This is the key difference from the Phase Linking tutorial, whose distributed-scatterer covariance needs **≥ 3** epochs. The extra 24 Jun *S1C* acquisition used there is simply not required here — though you could form additional GSLC pairs with it.)

Download the two products (free, by name) from the **Copernicus Data Space Ecosystem** (<https://dataspace.copernicus.eu>) or **ASF Vertex** (<https://search.asf.alaska.edu>) into a working folder. We process polarisation **VV**, subswath **IW3**. The full product names:

```text
S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5.SAFE
S1D_IW_SLC__1SDV_20260630T225009_20260630T225040_003472_006226_2543.SAFE
```

The pair spans the earthquake, so the interferometric phase carries the coseismic (and early post-seismic) ground displacement. Much of the epicentral terrain is rural; GSLC's phase-preserving geocoding puts both scenes on a common map grid so the deformation fringes can be formed and interpreted directly.

## What you will do

1. Generate the **master GSLC** from S1A (IW3 / VV) — a phase-preserving, geocoded complex image.
2. Feed the **raw slave** (S1D) together with the master GSLC to **`CreateStack`**, which auto-coregisters it onto the master grid.
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

### A1. Generate the master GSLC (S1A, IW3 / VV)

1. **Prepare the input.** *File ▸ Open Product…* the S1A `.SAFE`, then **Radar ▸ Apply Orbit File**, then **Radar ▸ Sentinel-1 TOPS ▸ S-1 TOPS Split** and select **IW3** + **VV**.
2. **Launch GSLC.** Menu **Radar ▸ Geometric ▸ Terrain Correction ▸ Geocoded SLC Terrain Correction** (dialog title *"GSLC Terrain Correction"*).
3. **I/O Parameters tab.** Source = the orbit-applied, split S1A product; target name gets suffix `_GSLC`; keep `BEAM-DIMAP`.
4. **Processing Parameters tab.** The fields that matter:

   | Field | Default | Notes |
   |-------|---------|-------|
   | Digital Elevation Model | Copernicus 30m Global | Auto-download; or point *External DEM* at a local file |
   | Image Resampling Method | BiSinc 5-point | **Keep a sinc kernel for InSAR** phase fidelity |
   | Pixel Spacing (m / deg) | 0 (auto) | Set explicitly to lock resolution across the pair |
   | Map Projection | WGS84(DD) | Any WKT CRS; use UTM for a metric grid |
   | Align to standard grid | **true** | **Leave on** — guarantees the pair shares one lattice |
   | Output phase-flattened complex data | **false** | **Leave false for InSAR.** True only for amplitude/PolSAR single-date use |
   | Apply Solid Earth Tide / Tropospheric | off | Turn on for displacement-grade (sub-decimetre) InSAR |
   | Apply Ionospheric | off | L-band only; currently a stub that logs a warning |

5. **Run.** The `S1A…_GSLC` product is a phase-preserving complex image on the map grid.

### A2. Build the coseismic interferogram (master GSLC + raw slave)

You only geocode the **master** explicitly; `CreateStack` auto-coregisters the raw slave.

1. **Prepare the slave.** Apply-Orbit-File + TOPSAR-Split (**IW3**, **VV**) to **S1D**, but **do not** run GSLC on it.
2. **Create Stack** — *Radar ▸ Coregistration ▸ Stack Tools ▸ Create Stack*: add the **master GSLC** (S1A) and the **raw split slave** (S1D). CreateStack reads the master's `gslc_source_slc_path` stamp, cross-correlates against the slave to estimate the (Δrange, Δazimuth) bias, rebuilds the slave GSLC with that bias, and stacks the two on the master grid.
3. **Interferogram Formation** — *Radar ▸ Interferometric ▸ Products ▸ Interferogram Formation*. In *Processing Parameters*:
   - Keep **Subtract flat-earth phase** ticked.
   - **✔ Tick "Subtract topographic phase".** This simulates the topographic fringes from the DEM and removes them, so the interferogram shows **deformation + residual atmosphere** rather than topography — essential for reading the coseismic signal.
   - Keep **Include coherence** ticked.
4. **Goldstein Phase Filtering** → **SnaphuExport → SNAPHU → SnaphuImport** (unwrap) → **Phase to Displacement** (line-of-sight displacement in metres).

   Because the GSLC pair is **already geocoded**, the displacement map is already in map coordinates — **no final Range-Doppler Terrain Correction is needed** (one fewer step than the traditional chain).

> **Consistency rule:** master and slave must share the **same** `Output phase-flattened` setting. Mixing flattened and non-flattened GSLCs yields a meaningless (noise) interferogram. The CreateStack auto-coregister path enforces this from the master's metadata stamp.

---

## Part B — Command line (`gpt`)

Run these from your working folder; the graphs take the input products as parameters, so no paths are hard-coded.

### Step 1 — generate the master GSLC (S1A)

Save as `gslc_master.xml`:

```xml
<graph id="GSLC-Master">
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
      <alignToStandardGrid>true</alignToStandardGrid>
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
gpt gslc_master.xml ^
    -Ps1a=S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5.SAFE ^
    -Poutput=S1A_GSLC.dim -c 12G -q 8
```

For **displacement-grade** work, add `-PapplySolidEarthTide=true -PapplyTroposphericCorrection=true` (and use the **same** setting for the slave, which CreateStack rebuilds from the master's stamp).

### Step 2 — GSLC interferogram, end to end

`master GSLC + raw slave (S1D) → CreateStack (auto-coregister) → Interferogram (flat-earth + topo removed) → Goldstein → Write`. Save as `gslc_insar.xml`:

```xml
<graph id="GSLC-InSAR">
  <version>1.0</version>
  <node id="ReadMaster"><operator>Read</operator><sources/>
    <parameters><file>${master_gslc}</file></parameters>
  </node>

  <!-- raw slave: orbit + split only (NOT geocoded); CreateStack geocodes it -->
  <node id="ReadSlave"><operator>Read</operator><sources/>
    <parameters><file>${s1d}</file></parameters>
  </node>
  <node id="OrbitSlave"><operator>Apply-Orbit-File</operator>
    <sources><sourceProduct refid="ReadSlave"/></sources>
    <parameters><orbitType>Sentinel Precise (Auto Download)</orbitType><continueOnFail>false</continueOnFail></parameters>
  </node>
  <node id="SplitSlave"><operator>TOPSAR-Split</operator>
    <sources><sourceProduct refid="OrbitSlave"/></sources>
    <parameters><subswath>IW3</subswath><selectedPolarisations>VV</selectedPolarisations></parameters>
  </node>

  <node id="CreateStack"><operator>CreateStack</operator>
    <sources>
      <sourceProduct refid="ReadMaster"/>
      <sourceProduct.1 refid="SplitSlave"/>
    </sources>
    <parameters><resamplingType>NONE</resamplingType></parameters>
  </node>
  <node id="Interferogram"><operator>Interferogram</operator>
    <sources><sourceProduct refid="CreateStack"/></sources>
    <parameters>
      <subtractFlatEarthPhase>true</subtractFlatEarthPhase>
      <subtractTopographicPhase>true</subtractTopographicPhase>
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
    -Pmaster_gslc=S1A_GSLC.dim ^
    -Ps1d=S1D_IW_SLC__1SDV_20260630T225009_20260630T225040_003472_006226_2543.SAFE ^
    -Poutput=ifg_GSLC.dim -c 12G -q 8
```

The **`subtractTopographicPhase`** flag is the command-line equivalent of the *Subtract topographic phase* checkbox — it removes the DEM-simulated topographic fringes so the coseismic deformation signal remains. Export → SNAPHU → import to unwrap, then **Phase to Displacement**; the result is already geocoded.

For the full worked example with figures, see the notebook **`snap-nb-sar-gslc-insar`**.

---

## Reading / validating the output

- The `S1A_GSLC` product is a geocoded complex image (`i_/q_` bands) plus any diagnostic bands you enabled (DEM, lat/lon, incidence, layover-shadow, simulated phase).
- After `Interferogram`, inspect the **coherence** band and the **wrapped phase** (fringes). Over stable terrain coherence should be high and fringes smooth; concentric fringes near the epicentre are the coseismic deformation. Sanity-check against a traditional-pipeline interferogram of the same S1A/S1D pair.

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| Operator rejects a debursted TOPS product | GSLC needs a **split (burst-level)** TOPS product or a Stripmap SLC; debursted TOPS is explicitly rejected. **GRD** has no phase — it isn't blocked but yields a degenerate product, so don't use it. |
| Interferogram is pure noise | (1) `outputFlattened` mismatched between master and slave — must be identical; (2) master and slave not on the same grid — keep `alignToStandardGrid=true`; (3) large residual misregistration — use the CreateStack auto-coregister path. |
| Coherence lower than expected | Check DEM quality; for displacement scenes enable SET + troposphere; verify like-for-like comparison (same multilook, same area). Note S1A↔S1D is a cross-platform constellation pair — confirm they share the relative orbit/track. |
| Master and slave don't align | Both must share `alignToStandardGrid=true` and the same pixel spacing; let CreateStack set the sub-pixel `rangeOffsetPixels`/`azimuthOffsetPixels`. |
| Topographic fringes still dominate the interferogram | Enable **Subtract topographic phase** (`subtractTopographicPhase=true`) with a valid DEM. |
| Slow / huge output over sea | Keep *Mask out areas with no elevation* (`nodataValueAtSea=true`) on. |

## Next steps

- **Jupyter notebooks** — full, runnable SAR/InSAR workflows: <https://github.com/senbox-org/snap-jupyter-notebooks>. See `snap-nb-sar-gslc-insar` for the complete GSLC-InSAR example with figures.
- Algorithm, parameters, and the coregistration model: `GSLC-Geocoding-Explained.md`.
- Operator parameter reference: in-app Help (F1 in the dialog) → *GSLC Terrain Correction*.
