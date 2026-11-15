"""insar_errors_1: interferometric phase standard deviation versus coherence for L independent looks.

Solid lines: Monte Carlo standard deviation of the phase of an L-look interferogram of a
circular complex Gaussian pair with true coherence g (true phase 0, so the wrapped error is
the phase itself). Dashed lines: Cramer-Rao bound of Rodriguez & Martin (1992),
    sigma_phi = sqrt(1 - g^2) / (g * sqrt(2 L)).
Right axis: the same in millimetres of line-of-sight displacement for Sentinel-1
(lambda = c / 5.405 GHz = 5.547 cm, lambda / 4 pi = 4.41 mm/rad).
"""
import numpy as np
from matplotlib.ticker import FuncFormatter

import figstyle

C = figstyle.COLORS
RNG = np.random.default_rng(7)
LAMBDA_MM = 299792458.0 / 5.405e9 * 1000.0
MM_PER_RAD = LAMBDA_MM / (4.0 * np.pi)


def phase_std(g, n, trials=40000):
    a = (RNG.standard_normal((trials, n)) + 1j * RNG.standard_normal((trials, n))) / np.sqrt(2)
    b = (RNG.standard_normal((trials, n)) + 1j * RNG.standard_normal((trials, n))) / np.sqrt(2)
    s1 = a
    s2 = g * a + np.sqrt(1 - g * g) * b
    phi = np.angle(np.sum(s1 * np.conj(s2), axis=1))
    return np.sqrt(np.mean(phi ** 2))


def crb(g, n):
    return np.sqrt(1 - g * g) / (g * np.sqrt(2 * n))


gam = np.linspace(0.05, 0.98, 48)
fig, ax = figstyle.figure(5.6, 3.4)
for n, col in zip((4, 16, 64), (C["red"], C["green"], C["blue"])):
    mc = np.array([phase_std(g, n) for g in gam])
    ax.plot(gam, mc, color=col, lw=1.7, label=f"L = {n}")
    ax.plot(gam, crb(gam, n), color=col, lw=1.2, ls="--")
    print(f"L={n}: CRB / true at coherence 0.3, 0.5, 0.7, 0.9: " + ", ".join(
        f"{crb(g, n) / phase_std(g, n):.2f}" for g in (0.3, 0.5, 0.7, 0.9)))
ax.axhline(np.pi / np.sqrt(3), color=C["grey"], lw=0.9, ls=":")
ax.text(0.40, np.pi / np.sqrt(3) * 1.10, "uniform phase (pure noise), 1.81 rad", fontsize=9, color="#555555")
ax.plot([], [], color=C["black"], lw=1.2, ls="--", label="Cramér–Rao bound")
ax.set_yscale("log")
ax.set_xlim(0, 1)
ax.set_ylim(0.02, 4)
ax.set_xlabel("true coherence |γ|")
ax.set_ylabel("phase standard deviation (rad)")
ax.grid(True, which="both", color="#E4E4E4", lw=0.6)
ax.legend(loc="lower left", frameon=False, fontsize=9)

sec = ax.secondary_yaxis("right", functions=(lambda r: r * MM_PER_RAD, lambda m: m / MM_PER_RAD))
sec.set_ylabel("Sentinel-1 LOS (mm)")
plain = FuncFormatter(lambda v, _: f"{v:g}")
ax.yaxis.set_major_formatter(plain)
sec.yaxis.set_major_formatter(plain)
for g, n in ((0.3, 20), (0.5, 20), (0.7, 20), (0.5, 100), (0.9, 4)):
    print(f"g={g} L={n}: CRB {crb(g, n):.3f} rad = {crb(g, n) * MM_PER_RAD:.2f} mm, "
          f"MC {phase_std(g, n):.3f} rad = {phase_std(g, n) * MM_PER_RAD:.2f} mm")
figstyle.save(fig, "insar_errors_1")
