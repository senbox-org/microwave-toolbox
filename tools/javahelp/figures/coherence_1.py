"""coherence_1: expected value of the sample coherence magnitude versus the true coherence,
for several numbers N of independent looks (circular complex Gaussian scatterers).

Solid lines: closed form of Touzi et al. (1999),
    E|g_hat| = Gamma(N) Gamma(3/2) / Gamma(N + 1/2) * 3F2(3/2, N, N; N + 1/2, 1; g^2) * (1 - g^2)^N
cross-checked here by Monte Carlo. Dashed line: the bias correction used by SNAP's Coherence
operator, g_c = max(0, g_hat - (1 - g_hat^2) / (2N)), applied per sample (Monte Carlo, N = 16).
"""
import mpmath as mp
import numpy as np

import figstyle

C = figstyle.COLORS
RNG = np.random.default_rng(1)


def expected_abs(g, n):
    g2 = mp.mpf(g) ** 2
    pref = mp.gamma(n) * mp.gamma(1.5) / mp.gamma(n + 0.5)
    return float(pref * mp.hyp3f2(1.5, n, n, n + 0.5, 1, g2) * (1 - g2) ** n)


def sample_coherence(g, n, trials=20000):
    """Sample coherence of n independent looks of a pair with true coherence g."""
    a = (RNG.standard_normal((trials, n)) + 1j * RNG.standard_normal((trials, n))) / np.sqrt(2)
    b = (RNG.standard_normal((trials, n)) + 1j * RNG.standard_normal((trials, n))) / np.sqrt(2)
    s1 = a
    s2 = g * a + np.sqrt(1 - g * g) * b
    num = np.abs(np.sum(s1 * np.conj(s2), axis=1))
    den = np.sqrt(np.sum(np.abs(s1) ** 2, axis=1) * np.sum(np.abs(s2) ** 2, axis=1))
    return num / den


gam = np.linspace(0.0, 0.999, 120)
fig, ax = figstyle.figure(5.6, 3.6)
ax.plot([0, 1], [0, 1], color=C["black"], lw=0.8, ls=":", label="unbiased")
colors = [C["red"], C["orange"], C["green"], C["blue"], C["purple"]]
for n, col in zip((4, 8, 16, 32, 64), colors):
    e = [expected_abs(g, n) for g in gam]
    ax.plot(gam, e, color=col, lw=1.6, label=f"N = {n}")

# Monte Carlo cross-check of the closed form
worst = 0.0
for n in (4, 16, 64):
    for g in (0.0, 0.3, 0.6, 0.9):
        worst = max(worst, abs(sample_coherence(g, n).mean() - expected_abs(g, n)))
print(f"max |MC - closed form| = {worst:.4f}")

# SNAP's bias correction, N = 16 (e.g. a 16-sample window of independent pixels)
n = 16
gc = []
for g in np.linspace(0.0, 0.99, 34):
    s = sample_coherence(g, n)
    gc.append((g, np.maximum(0.0, s - (1 - s * s) / (2 * n)).mean()))
gc = np.array(gc)
ax.plot(gc[:, 0], gc[:, 1], color=C["green"], lw=1.4, ls="--", label="N = 16, SNAP-corrected")

ax.set_xlim(0, 1)
ax.set_ylim(0, 1)
ax.set_xlabel("true coherence |γ|")
ax.set_ylabel("mean estimated coherence")
ax.legend(loc="lower right", frameon=False, ncol=2)
ax.grid(True, color="#DDDDDD", lw=0.6)
figstyle.save(fig, "coherence_1")
