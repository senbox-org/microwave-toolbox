# NISAR GCOV Reader: Design

**Date:** 2026-11-24
**Status:** proposed. No code changed yet.
**Roadmap:** [§4 Readers](../../roadmap.md#4-readers)
**Ticket:** [SNAP-4384](https://senbox.atlassian.net/browse/SNAP-4384)
**Module:** `sar-io` (`eu.esa.sar.io.nisar.subreaders`). One small change in `sar-commons` (`PolBandUtils`, §5.4).
**Trigger:** a review of the independent NISAR training toolkit ([dharmendraisro/nisar-polarimetric-training](https://github.com/dharmendraisro/nisar-polarimetric-training), Apache-2.0). Its Python notebooks run H/A/α, Pauli, Freeman–Durden, Yamaguchi and compact-pol m-χ/m-δ/m-α directly on NISAR L2 GCOV. SNAP has all of those operators, but cannot feed GCOV into them.

---

## 1. Problem

`NisarGCOVProductReader` (98 lines) treats every GCOV layer as a real intensity image.

| # | Defect | Where | Effect |
|---|---|---|---|
| D1 | Off-diagonal covariance terms (`HHHV`, `HHVV`, `HVVV`, `HHVH`, `HVVH`, `VHVV`) are HDF5 complex compounds, but each gets one float32 band with unit `INTENSITY` | `NisarGCOVProductReader.addBand` (line 81) | The base reader reads the imaginary part only for bands whose unit is `IMAGINARY` or whose name starts with `q_` (`NisarSubReader.java:195`). The band holds the **real part only**. The phase is lost and the unit is wrong |
| D2 | Compact-pol terms `RHRH`, `RHRV`, `RVRV` are missing from the term list | lines 52 and 66 | SSAR (and any LSAR) compact-pol GCOV opens with no data bands |
| D3 | GCOV is not presented as a polarimetric matrix product | — | `PolBandUtils.getSourceProductType` needs `C11`, `C12_real`, … bands; `polsar_data` and `compact_mode` are not set. Polarimetric Decomposition, Parameters, Speckle Filter, Wishart and the compact-pol operators reject GCOV |
| D4 | Auxiliary per-pixel layers are not exposed: `mask`, `numberOfLooks`, `rtcAreaNormalizationFactorGamma0ToSigma0` | — | No validity mask (transmit gaps, outside swath); no ENL for speckle filters and Wishart; no γ⁰→σ⁰ conversion |
| D5 | Bands use no-data value 0, but GCOV layers declare `_FillValue = NaN` | `addBand` | Fill pixels are NaN, not 0, so the declared no-data never matches. Statistics and display treat fill as data |
| D6 | (Latent) a compact-pol GCOV with only diagonal terms would be misdetected as raw `RCHCP` because `"RHRH".contains("RH")` | `PolBandUtils.java:84` | Once D2 is fixed, operators would look for `i_RCH`/`q_RCH` bands and fail with a confusing error |

## 2. Goals and non-goals

**Goals**
1. Every GCOV covariance term is read with its full value: real for diagonal terms, real and imaginary for off-diagonal terms.
2. Full-pol (quad), dual-pol and compact-pol GCOV with off-diagonal terms open as a valid C3 or C2 product, so the existing polarimetric operators run on them without manual band maths.
3. The validity mask, number of looks and the RTC γ⁰→σ⁰ factor are available as bands.
4. Existing graphs that use the current diagonal band names (`HHHH`, `HVHV`, `VVVV`, …) keep working.

**Non-goals**
- New polarimetric algorithms. SNAP already has more than the toolkit (generalized Freeman–Durden, MF3CF, CP_MF3CC, RVOG, Wishart, filters).
- Changing other NISAR sub-readers (RSLC, GSLC, GUNW, …).
- Calibration: `NisarCalibrator` correctly rejects GCOV (already γ⁰/σ⁰-normalised).

## 3. GCOV facts the design relies on

From the NISAR L2 GCOV product specification (JPL D-102274) and the local sample `E:\TestData\s1tbx\SAR\NISAR\NISAR_L2_PR_GCOV_001_005_A_219_4020_SHNA_…h5`:

- Layers live in `/science/{LSAR|SSAR}/GCOV/grids/frequency{A|B}/`.
- `listOfCovarianceTerms` lists the terms actually present. Use it in place of the hard-coded term list.
- Diagonal terms are float32 backscatter power: γ⁰ when `radiometricTerrainCorrectionFlag` is true, otherwise β⁰/σ⁰ per the product metadata. `_FillValue = NaN`.
- Off-diagonal terms are written only when the product was processed with full covariance. They are complex compounds (float32 pairs; the base reader also decodes NISAR float16 complex).
- Quad-pol GCOV symmetrises the cross-pol channel, so a quad product carries `HVHV` and `HHHV`/`HVVV`, not `VHVH`. Some products carry the `VH`-referenced forms (`HHVH`, `VHVV`) instead.
- Per-pixel auxiliary layers: `mask` (uint8), `numberOfLooks` (float32), `rtcAreaNormalizationFactorGamma0ToSigma0` (float32). The 2024 sample has the last two but no `mask`.
- Covariance convention: term `XYZW` = ⟨S_XY · S_ZW*⟩, on the plain lexicographic basis k = [S_HH, S_HV, S_VV]. **Verify against D-102274 before coding (§9, Q1).**

## 4. Design overview

Two layers, both in the reader:

1. **Native layer (fixes D1, D2, D4, D5).** One band per GCOV term with the GCOV name, read correctly. The names don't change, so existing graphs keep working. Off-diagonal terms split into a real and an imaginary band.
2. **Polarimetric layer (fixes D3).** When the product has a complete set of terms for a matrix, the reader adds **virtual bands** with the canonical `PolBandUtils` names (`C11`, `C12_real`, …), defined as expressions over the native bands. It also sets `polsar_data = 1` and, for compact-pol, `compact_mode`.

Why virtual bands rather than renaming or duplicating:
- Users who only want backscatter keep `HHHH`/`HVHV`. Polarimetric users get a product the operators accept with no extra step.
- No extra I/O or memory: the expressions are evaluated per tile from the native bands, and Write materialises them when the product is saved.
- The √2 and 2 scaling (§5.2) sits in one place, in the band expressions, where a user can inspect it.

Alternative considered: a separate "GCOV to Polarimetric Matrix" operator. Rejected as the default because it adds a manual step for every GCOV product and repeats what the reader already knows. It remains the fallback if virtual bands in a reader product cause problems in `PolBandUtils` consumers (§10, R1).

## 5. Detailed design

### 5.1 Native bands

Build the term list from `listOfCovarianceTerms` for each frequency (fallback: the current hard-coded list extended with `RHRH`, `RHRV`, `RVRV`).

| GCOV term kind | Band(s) | Unit | No-data |
|---|---|---|---|
| Diagonal (`HHHH`, `HVHV`, `VHVH`, `VVVV`, `RHRH`, `RVRV`) | `HHHH` | `intensity` | NaN |
| Off-diagonal (`HHHV`, `HHVV`, `HVVV`, `HHVH`, `HVVH`, `VHVV`, `RHRV`) | `HHHV_real`, `HHHV_imag` | `real`, `imaginary` | NaN |
| `mask` | `mask` | — (uint8) | 255 if the spec defines it as fill (§9, Q3) |
| `numberOfLooks` | `numberOfLooks` | — | NaN |
| `rtcAreaNormalizationFactorGamma0ToSigma0` | `rtcGamma0ToSigma0Factor` | — | NaN |

- `_imag` bands must reach the imaginary read path. Make the base reader's imaginary-band test (`NisarSubReader.java:195`) also accept `Unit.IMAGINARY` set by the GCOV reader. It already does; the GCOV reader just has to set the unit. Add a test that the two bands differ.
- Frequency B keeps the existing `_S` suffix (`NisarSubReader.java:278`), e.g. `HHHV_real_S`.
- Add a SNAP `Mask` named `gcov_valid` from the `mask` band (expression per §9, Q3), and set it as the valid-pixel expression of every data band only if that does not hide valid data in the sample products.

### 5.2 Polarimetric virtual bands

Created for **frequency A only** (§9, Q4), and only when every term the matrix needs is present. Names are exactly `PolBandUtils.getC3BandNames()` / `getC2BandNames()`.

**Quad-pol → C3.** SNAP's C3 uses k = [S_HH, √2·S_HV, S_VV], while GCOV uses [S_HH, S_HV, S_VV]. So:

| C3 band | Expression |
|---|---|
| `C11` | `HHHH` |
| `C12_real` / `C12_imag` | `√2 · HHHV_real` / `√2 · HHHV_imag` |
| `C13_real` / `C13_imag` | `HHVV_real` / `HHVV_imag` |
| `C22` | `2 · HVHV` |
| `C23_real` / `C23_imag` | `√2 · HVVV_real` / `√2 · HVVV_imag` |
| `C33` | `VVVV` |

If the product carries `HHVH` instead of `HHHV`, use its conjugate: `C12_real = √2·HHVH_real`, `C12_imag = −√2·HHVH_imag`. Same for `VHVV` → `C23`. With symmetrised cross-pol, HV and VH are interchangeable here; `VHVH` substitutes for `HVHV`.

**Dual-pol (HH+HV or VV+VH) → C2.** SNAP's dual-pol C2 uses k = [S_co, S_cross] with no scaling (`DualPolProcessor.computeCovarianceMatrixC2`):
`C11 = HHHH`, `C12 = HHHV`, `C22 = HVHV` (VV/VH: `VVVV`, `VVVH` or conj(`VHVV`), `VHVH`).
Set `polsar_data = 1`. Do **not** set `compact_mode`.

**Compact-pol (RH+RV) → C2.**
`C11 = RHRH`, `C12 = RHRV`, `C22 = RVRV`.
Set `polsar_data = 1` and `compact_mode` to `CompactPolProcessor.rch` ("Right Circular Hybrid Mode") or `.lch` from the transmit polarisation in the product metadata (§9, Q2). `CompactPolStokesParametersOp` and `CompactPolDecompositionOp` read `compact_mode` and accept `MATRIX.C2`.

**Diagonal-only products** (single-pol, or any product without off-diagonal terms): no virtual bands and no `polsar_data` flag. The polarimetric operators correctly refuse them (§5.4 makes the refusal clear).

### 5.3 Metadata

- Abstracted metadata: `polsar_data`, `compact_mode` as above. Leave `SAMPLE_TYPE = DETECTED`.
- Copy `listOfCovarianceTerms`, `radiometricTerrainCorrectionFlag` and the full-covariance processing flag into the original-product metadata (already partly copied by the generic HDF5 walk; check).
- Band descriptions say what each band is, e.g. "⟨S_HH·S_HV*⟩, real part" and "C3 element from GCOV (√2·HHHV)".

### 5.4 `PolBandUtils` detection fix (D6)

`getSourceProductType` matches `"RH"` as a substring, so `RHRH`/`RVRV` look like raw right-circular compact-pol. Restrict the `LCHCP`/`RCHCP` tests to the raw-channel band names those types actually use (the `i_`/`q_` pairs). A diagonal-only compact GCOV must return `UNKNOWN`, and a compact GCOV with the virtual C2 bands must return `C2`. Add both as tests in `sar-commons`.

This is the only change outside `sar-io`. Check every `getSourceProductType` caller still gets the same answer for RCM, RISAT-1 and the CompactPolDataSimulation outputs (their test products).

## 6. Testing

**T1, synthetic (committed, runs in CI).** A Python script under `sar-io/src/test/resources/…/nisar/` (with h5py) writes a tiny GCOV-shaped HDF5 (e.g. 8×8, a few KB) per case:

| Case | Terms | Checks |
|---|---|---|
| Quad full covariance | HHHH, HVHV, VVVV, HHHV, HHVV, HVVV with known complex values | `_real`/`_imag` bands equal the written values; C3 bands equal the §5.2 formulas; `getSourceProductType == C3`; H/A/α of an analytic case matches its closed form |
| Quad, VH-referenced off-diagonals | HHVH, VHVV | C12, C23 are the conjugates |
| Dual HH/HV | HHHH, HVHV, HHHV | `C2`, no `compact_mode` |
| Compact | RHRH, RVRV, RHRV | `C2`, `compact_mode` set; Stokes g0…g3 from `CompactPolStokesParametersOp` match the closed form |
| Compact diagonal only | RHRH, RVRV | `UNKNOWN`, no virtual bands |
| Fill and mask | NaN pixels, `mask` layer | NaN pixels are no-data; `gcov_valid` mask matches |
| float16 complex | one off-diagonal as float16 pair | decoded within float16 tolerance |

Commit the generated `.h5` files (small) as well as the script, so the Java tests need no Python.

**T2, real data (long-gated `TestNISARReader`).** Extend the existing GCOV tests:
- the 2024 single-pol sample (local): no regression, no virtual bands, `numberOfLooks` and the RTC factor present;
- a full-pol LSAR GCOV with full covariance, a dual-pol GCOV, and an SSAR compact-pol GCOV, once acquired (§8);
- for the full-pol product: run Polarimetric Decomposition (H/A/α) and check the values are finite and in range over valid pixels.

**T3 (`snap-validation`, later).** Compare H/A/α and Freeman–Durden from SNAP on a GCOV scene against PolSARpro, or against an independent numpy implementation built from the GCOV terms. The toolkit's `nisar_utils/polarimetry.py` is a ready reference for the conversion, but it calls its own Freeman–Durden/Yamaguchi "educational", so compare only the C3/T3 and eigen outputs with it.

## 7. Phases

| Phase | Scope | Fixes | Needs |
|---|---|---|---|
| P1 | Native layer: term list from `listOfCovarianceTerms`, complex split, compact terms, NaN no-data, auxiliary bands | D1, D2, D4, D5 | synthetic fixtures |
| P2 | `PolBandUtils` detection fix | D6 | — |
| P3 | Polarimetric virtual bands and metadata; operator smoke tests | D3 | P1, P2; Q1 and Q2 answered |
| P4 | Real-data tests and docs (reader help page: band list, scaling table, how to run the polarimetric operators on GCOV) | — | test products (§8) |

## 8. Test data to acquire

From ASF DAAC (NISAR L2 GCOV), copied to `E:\TestData\s1tbx\SAR\NISAR\` and the S3 test-data bucket:
- LSAR quad-pol GCOV processed with full covariance (off-diagonal terms present);
- LSAR dual-pol (`DHDH`) GCOV: `TestNISARReader` already names one (`…_DHDH_A_20260108T101627…`) that is not in the local test data;
- SSAR compact-pol GCOV, if distributed; otherwise LSAR compact-pol.

## 9. Open questions

- **Q1 Conjugation convention.** Confirm in D-102274 that `HHHV` = ⟨S_HH·S_HV*⟩ (not ⟨S_HV·S_HH*⟩). If it is the other way, the `_imag` signs in §5.2 flip. The synthetic test can't catch this; only the spec or a known target can.
- **Q2 Compact-pol chirality.** Where the product records the transmit polarisation (right or left circular), and how SNAP's `rch`/`lch` maps to it. The toolkit defaults to chirality sign −1; check that this agrees with SNAP's `StokesParameters` for `rch`.
- **Q3 Mask values.** The `mask` encoding (which values mean valid, out-of-swath, fill) for the current product version.
- **Q4 Frequency B.** `PolBandUtils` finds bands by name, so two C3 sets (A and `_S`) in one product would be ambiguous. Proposed: virtual bands for frequency A only; users who need B subset it first. Alternative: a reader option to pick the frequency.
- **Q5 Valid-pixel expression.** Set `gcov_valid` as the valid-pixel expression on all data bands, or only provide the mask? Setting it is safer for statistics, but changes what users see today.

## 10. Risks

- **R1** Some `PolBandUtils` consumers may assume a polarimetric product contains only matrix bands, or read band lists positionally. The extra native bands (`HHHH`, `mask`, …) could then confuse them. Mitigation: the P3 operator smoke tests cover every operator that accepts C3/C2. Fallback: the separate conversion operator (§4).
- **R2** Write materialises the virtual bands, doubling the size of a saved product. Acceptable; document it in the help page.
- **R3** The `PolBandUtils` change (P2) touches every polarimetric operator's input detection. Keep it narrow and test it on the existing RCM/RISAT-1 compact-pol cases.
