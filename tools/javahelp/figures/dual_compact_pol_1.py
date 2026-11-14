"""dual_compact_pol_1: effect of the noise floor on dual-pol (VV+VH) measurements.

Additive thermal noise of power N (the NESZ, here -22 dB as specified for Sentinel-1 IW)
adds to both channels and is uncorrelated between them.
Left: bias of the measured cross-pol backscatter, 10 log10(1 + N / sigma0_VH), versus the true
VH backscatter.
Right: dual-pol radar vegetation index DpRVI = 1 - m * beta (Mandal et al. 2020) for a target
with uncorrelated channels, C2 = diag(sigma0_VV, sigma0_VH), sigma0_VV = -8 dB, with and without
noise. For a diagonal C2 the eigenvalues are the two channel powers, so
m = (l1 - l2) / (l1 + l2) and beta = l1 / (l1 + l2).
"""
import numpy as np
import matplotlib.pyplot as plt

import figstyle

C = figstyle.COLORS


def db2lin(x):
    return 10.0 ** (np.asarray(x) / 10.0)


nesz_db = -22.0
N = db2lin(nesz_db)
vh_db = np.linspace(-32, -12, 300)
vh = db2lin(vh_db)
vv = db2lin(-8.0)

fig, (a1, a2) = plt.subplots(1, 2, figsize=(5.6, 2.8), dpi=figstyle.DPI)

bias = 10 * np.log10(1 + N / vh)
a1.plot(vh_db, bias, color=C["red"])
a1.axvline(nesz_db, color=C["grey"], ls="--", lw=1)
a1.text(nesz_db + 0.4, 7.2, "NESZ", fontsize=9, color=C["grey"])
a1.set_xlabel("true σ⁰ VH (dB)")
a1.set_ylabel("measured − true (dB)")
a1.set_ylim(0, 8)


def dprvi(l_a, l_b):
    l1, l2 = np.maximum(l_a, l_b), np.minimum(l_a, l_b)
    m = (l1 - l2) / (l1 + l2)
    beta = l1 / (l1 + l2)
    return 1 - m * beta


a2.plot(vh_db, dprvi(vv, vh), color=C["blue"], label="no noise")
a2.plot(vh_db, dprvi(vv + N, vh + N), color=C["orange"], label="NESZ −22 dB")
a2.axvline(nesz_db, color=C["grey"], ls="--", lw=1)
a2.set_xlabel("true σ⁰ VH (dB)")
a2.set_ylabel("DpRVI")
a2.set_ylim(0, 1)
a2.legend(frameon=False, loc="upper left")

figstyle.save(fig, "dual_compact_pol_1")
