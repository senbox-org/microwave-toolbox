"""wishart_1: entropy estimated from a sample coherency matrix is biased low for few looks.

Monte Carlo: target vectors k ~ CN(0, T) with a diagonal true T3 chosen for a given true entropy,
L independent looks averaged into <T> = (1/L) sum k k^H (complex Wishart with L degrees of freedom),
then H = -sum p_i log3 p_i from the eigenvalues of <T>. Curves show the mean estimate over
2000 trials per point. The true value is the dotted line.
"""
import numpy as np

import figstyle

C = figstyle.COLORS
rng = np.random.default_rng(1)


def entropy(eigs):
    p = np.clip(eigs / eigs.sum(axis=-1, keepdims=True), 1e-15, 1)
    return -np.sum(p * np.log(p), axis=-1) / np.log(3)


def true_eigs_for(h_target):
    # eigenvalues (1, m, m): monotonic in m, solve by bisection
    lo, hi = 0.0, 1.0
    for _ in range(60):
        mid = 0.5 * (lo + hi)
        if entropy(np.array([1, mid, mid])) < h_target:
            lo = mid
        else:
            hi = mid
    return np.array([1.0, lo, lo])


looks = np.array([3, 4, 6, 9, 16, 25, 49, 81, 121, 225])
trials = 2000
fig, ax = figstyle.figure(5.6, 3.2)
for h_true, col in ((0.3, C["blue"]), (0.6, C["orange"]), (0.9, C["green"])):
    lam = true_eigs_for(h_true)
    sd = np.sqrt(lam / 2)
    means = []
    for L in looks:
        k = (rng.standard_normal((trials, L, 3)) + 1j * rng.standard_normal((trials, L, 3))) * sd
        T = np.einsum("tli,tlj->tij", k, k.conj()) / L
        ev = np.linalg.eigvalsh(T)
        means.append(entropy(ev).mean())
    ax.plot(looks, means, "o-", color=col, ms=4, label=f"true H = {h_true}")
    ax.axhline(h_true, color=col, ls=":", lw=1)

ax.set_xscale("log")
ax.set_xticks([3, 5, 10, 25, 50, 100, 225])
ax.set_xticklabels(["3", "5", "10", "25", "50", "100", "225"])
ax.set_xlabel("number of independent looks L in the averaged matrix")
ax.set_ylabel("mean estimated entropy")
ax.set_ylim(0, 1)
ax.legend(loc="lower right", frameon=False)
figstyle.save(fig, "wishart_1")
