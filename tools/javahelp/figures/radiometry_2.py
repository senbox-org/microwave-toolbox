"""radiometry_2: averaging in dB underestimates the mean backscatter.

For fully developed speckle, L-look intensity I is Gamma distributed with mean mu. The mean of 10 log10(I)
is 10 log10(mu) + (10 / ln 10) (psi(L) - ln L), psi = digamma. The bias does not shrink when more pixels
are averaged; it depends only on the number of looks per pixel being averaged.
A Monte Carlo check is printed for the plotted points.
"""
import numpy as np
from scipy.special import digamma

import figstyle

C = figstyle.COLORS
L = np.logspace(0, 2, 200)
bias = 10.0 / np.log(10.0) * (digamma(L) - np.log(L))

rng = np.random.default_rng(1)
marks = [(1.0, "single-look SLC"), (4.4, "S-1 IW GRDH (ENL 4.4)"), (20.0, "after 20-look filtering")]
for Lm, _ in marks:
    sample = rng.gamma(shape=Lm, scale=1.0 / Lm, size=2_000_000)
    mc = np.mean(10 * np.log10(sample))
    an = 10.0 / np.log(10.0) * (digamma(Lm) - np.log(Lm))
    print(f"L={Lm:5.1f}: analytic {an:+.3f} dB, Monte Carlo {mc:+.3f} dB")

fig, ax = figstyle.figure(5.6, 2.8)
ax.plot(L, bias, color=C["blue"], lw=2)
for (Lm, label), dx, dy in zip(marks, (1.25, 1.25, 0.6), (-0.15, -0.35, -0.3)):
    b = 10.0 / np.log(10.0) * (digamma(Lm) - np.log(Lm))
    ax.plot([Lm], [b], "o", color=C["orange"], ms=6)
    ax.annotate(f"{label}: \u2212{abs(b):.2f} dB", xy=(Lm, b), xytext=(Lm * dx, b + dy), fontsize=9,
                va="top", ha="left")
ax.axhline(0, color=C["grey"], lw=0.8)
ax.set_xscale("log")
ax.set_xlim(1, 100)
ax.set_ylim(-2.8, 0.2)
ax.set_xlabel("Equivalent number of looks per averaged pixel, L")
ax.set_ylabel("Bias of mean taken in dB (dB)")
ax.set_xticks([1, 2, 5, 10, 20, 50, 100])
ax.set_xticklabels(["1", "2", "5", "10", "20", "50", "100"])
ax.grid(True, which="major", color="#DDDDDD", lw=0.6)
figstyle.save(fig, "radiometry_2")
