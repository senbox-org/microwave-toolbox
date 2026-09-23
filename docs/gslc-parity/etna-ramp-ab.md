# Is `subtractResidualRamp` still earning its place? — ERS-1/2 tandem, Etna, 2026-09-22

## Why this measurement

The GSLC data-driven ramp family — `subtractResidualRamp` and the two removals layered on it,
`residualRampRangeProfile` and `residualRamp2D` — was written for two observations:

1. cross-acquisition GSLC **TOPS** interferograms carried a large smooth per-burst surface;
2. a 1995 **ERS-1/ERS-2 tandem VMP** pair (Etna) carried ~150 rad of smooth arcs beyond a
   quadratic, and ~58 rad of nonlinear slant-range structure, attributed to the archive
   processor's phase-vs-position annotation.

Observation 1 has since been explained away: it was an inverted carrier-difference add-back
sign in `InterferogramOp`, corrected (default) since SNAP 14. With the sign right, the ramp
option *degrades* the Venezuela S1A×S1C pair — phase concentration against the classical chain
0.961 with it off, 0.334 with it on, and a retention test shows it absorbs ~2.4 % of a broad
6 rad lobe.

Observation 2 is what this A/B tests, on the pair that produced it.

## What was run

Same GSLC stack, two interferograms differing only in `subtractResidualRamp`; both scored
against a terrain-corrected **classical** interferogram of the same pair, on the classical
product's own lat/lon grid, by `validation/gslc_parity/chain_compare.py`.

| | |
|---|---|
| Pair | ERS-1 orbit 21159, 1 Aug 1995 × ERS-2 orbit 1486, 2 Aug 1995 (tandem, Etna) |
| GSLC chain | `GSLC-Terrain-Correction` → `CreateStack` (auto path) → `Interferogram` (flat-earth + topographic phase removed, Copernicus 30 m, cohWin 20×10) |
| Classical chain | `CreateStack` → `Cross-Correlation` → `Warp` → `Interferogram` → Goldstein → Terrain-Correction |
| Reference grid | 7424 × 6110 at 1.82e-4° (~20 m), decimated ×4; classical coherence ≥ 0.3 |
| Samples | 995 686 |

`conc_tile` is the median over 1177 tiles (~2.5 km) of the phase-only concentration of
GSLC×conj(classical) within the tile — local agreement, blind to a smooth offset between
tiles. `spread_tile` is the circular standard deviation of the per-tile means. A **small**
`spread_tile` is strong evidence that no scene-scale surface separates the two chains; a
**large** one is weak evidence, because the statistic is spatially blind, weights every tile
equally however noisy, and **saturates** once the tile means wrap over the full circle. For
1177 tiles a uniform scatter gives 2.659 rad, so anything at that value carries no magnitude
information. `conc_global` is the concentration over all samples at once.

## Result

| configuration | conc_tile | spread_tile (saturates at 2.659) | conc_global |
|---|---|---|---|
| `subtractResidualRamp=false` | **0.5655** | **0.1295 rad** | **0.5693** |
| `subtractResidualRamp=true` | 0.3430 | 2.6584 rad — **saturated** | 0.0302 |

Convention check (conjugating the GSLC phase) gives 0.1421 / 0.1177 — far lower, so the
comparison is oriented correctly.

**There is no smooth surface left to remove.** Ramp off, the two chains differ by 0.13 rad of
scene-scale phase over a 150 km scene. The ~150 rad arc field that motivated `residualRamp2D`
is not in the current GSLC interferogram at all — if it were, the tile means would be spread
over the full ±π (as the ramp-on row shows they can be).

**Enabling the ramp destroys the agreement.** The estimator found no burst annotation (ERS is
stripmap, log: `no burst annotation found — using the scene-global quadratic fit`) and fitted
a scene-global quadratic to the real fringe field: coefficients `[24.204 -1.0364 -0.59427
-0.59570 0.089626]`, centre gradient (0.0037, −0.0039) rad/px. Removing it drops local
agreement from 0.57 to 0.34 and global agreement from 0.57 to 0.03. It is removing
deformation, topographic residual and atmosphere, because that is all there is to remove.

**What `spread_tile = 2.6584` does and does not say.** It says the tile means are scattered
over the full circle: the value sits on the saturation reference for 1177 tiles (2.6591) to
four significant figures, which is exactly what a uniform scatter produces. So it means "no
scene-scale agreement at all", **not** "a 2.66 rad surface was manufactured" — that magnitude
is not recoverable from this statistic, and an earlier version of this note over-read it.
The load-bearing numbers are `conc_tile` and `conc_global`, which are ordinary concentrations
and do not saturate.

Absolute `conc_tile` is floor-limited, not a defect: the classical reference is
Goldstein-filtered and terrain-corrected while the GSLC interferograms are unfiltered single
look, and the pair is a real tandem scene over vegetated terrain. Both configurations were
sampled identically against the same reference, so the A/B ranking is unaffected by that floor.
The sampler is bilinear, which attenuates and aliases dense fringes and therefore flatters
whichever arm has the smoother fringe field — the ramp-ON arm. It still loses, so that bias is
conservative here; it is not neutral, as an earlier version of this note claimed.
The coherence gate is one-sided (classical coherence only), so GSLC decorrelation is mixed into
both arms equally.

## Consequence

Both original justifications for the data-driven ramp family are now gone — the TOPS one by
the sign fix, the archive one by this measurement. The parameters had not shipped, so they were
**removed** rather than deprecated: `subtractResidualRamp`, `residualRampDegree`,
`residualRampRangeProfile`, `residualRamp2D` and `residualRamp2DNodes`, together with their
estimators (block-gradient sampling, the polynomial fit and its robust iteration, the
piecewise-linear range profile, the ridge-regularised 2-D node surface and the per-burst ramp
fit) — 1581 lines out of `InterferogramOp`, plus the 924-line `TestGslcResidualRamp` and
`TestGslcPerBurstRampFit`.

What survives is the part that was doing the real work: the **exact deramp-model
subtraction**, which is automatic whenever both legs carry `azimuthCarrierPhase`
(`outputPhaseTerms=true`), and the **seam-step corrector**, now reachable on its own as
`subtractSeamSteps` — it measures each burst seam's discontinuity by across-seam differencing,
so anything continuous across a seam (deformation, atmosphere, orbital ramps) cancels and
cannot be absorbed.

Not measured here: `residualRampRangeProfile` and `residualRamp2D` in isolation. They are
additional removals layered on `subtractResidualRamp` and cannot be enabled without it, so a
destructive base makes them unreachable in a configuration that helps. One pair, one sensor —
the Venezuela TOPS result is the other.
