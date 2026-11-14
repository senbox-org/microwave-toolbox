"""change_detection_1: log-ratio of two unchanged speckled images, and the false-alarm rate of a threshold.

Two independent L-look intensities with the same mean: D = ln(I1/I2) has the density
    p(D) = Gamma(2L) / Gamma(L)^2 * exp(L D) / (1 + exp(D))^(2L)
which does not depend on the mean backscatter (the reason ratio detectors suit multiplicative speckle).
Dashed lines: the SNAP default thresholds +/-2 (natural log, i.e. a factor e^2 = 7.4, 8.7 dB) and +/-ln 2 (3 dB).
The false-alarm probabilities P(|D| > t) are printed and shown in the legend.
"""
import numpy as np
from scipy.integrate import quad
from scipy.special import gammaln

import figstyle


def pdf(d, L):
    return np.exp(gammaln(2 * L) - 2 * gammaln(L) + L * d - 2 * L * np.logaddexp(0.0, d))


def pfa(t, L):
    return 2 * quad(pdf, t, 60, args=(L,))[0]


def pct(p):
    return "<0.1%" if p < 0.001 else f"{100 * p:.1f}%"


C = figstyle.COLORS
PM = "\u00b1"
d = np.linspace(-4, 4, 800)
fig, ax = figstyle.figure(5.6, 3.4)
for L, col in ((1, C["blue"]), (4.4, C["orange"]), (16, C["green"])):
    p2, p3 = pfa(2.0, L), pfa(np.log(2.0), L)
    print(f"L={L:5.1f}: P(|D|>2) = {p2:.4f}, P(|D|>ln2 = 3 dB) = {p3:.4f}")
    ax.plot(d, pdf(d, L), color=col, lw=2,
            label=f"L = {L:g}: false alarms {pct(p3)} at |D| > ln 2 (3 dB), {pct(p2)} at |D| > 2")
for t, col, lab in ((2.0, C["black"], PM + "2 (SNAP default)"), (np.log(2.0), C["red"], PM + "3 dB")):
    for sgn in (-1, 1):
        ax.axvline(sgn * t, color=col, lw=0.9, ls="--")
    ax.text(t + (0.05 if t == 2.0 else 0.0), 1.21, lab, fontsize=9, color=col,
            ha="left" if t == 2.0 else "center", va="bottom")
ax.set_xlim(-4, 4)
ax.set_ylim(0, 1.2)
ax.set_xlabel(r"Log-ratio $D = \ln(I_1/I_2)$  (multiply by 4.34 for dB)")
ax.set_ylabel("Probability density (no change)")
ax.legend(frameon=False, loc="upper center", fontsize=9, bbox_to_anchor=(0.45, -0.2))
figstyle.save(fig, "change_detection_1")
