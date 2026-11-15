"""stack_design_2: choosing the Reference and a disconnected small-baseline network.

Synthetic Sentinel-1-like stack: 30 dates every 12 days; 84 days added after date 15 (96-day interval,
seven missed acquisitions);
B_perp uniform within +-100 m, Doppler-centroid differences within +-15 Hz.
(a) Modelled mean stack coherence for each candidate Reference, computed as InSARStackOverview does:
    mean over all N dates (the candidate itself included) of
    g(B_perp, 1200 m) * g(B_t, 1095 d) * g(df_DC, 1380 Hz), g(x, c) = 1 - |x|/c, or 0.01 when |x| > c
    (C-band constants of InSARStackOverview.Band.C).
(b) Small-baseline network with B_t <= 48 d and |dB_perp| <= 150 m: the gap splits it into two
    unconnected groups of dates.
"""
import numpy as np

import figstyle

C = figstyle.COLORS
rng = np.random.default_rng(11)

n = 30
t = np.arange(n) * 12.0
t[15:] += 84.0                      # acquisition gap
bp = rng.uniform(-100.0, 100.0, n)
fdc = rng.uniform(-15.0, 15.0, n)


def g(x, crit):
    x = np.abs(x)
    return np.where(x > crit, 0.01, 1.0 - x / crit)


coh = np.array([np.mean(g(bp - bp[i], 1200.0) * g(t - t[i], 1095.0) * g(fdc - fdc[i], 1380.0))
                for i in range(n)])
best = int(np.argmax(coh))

fig, (ax1, ax2) = figstyle.plt.subplots(1, 2, figsize=(5.6, 3.0), dpi=figstyle.DPI)

ax1.plot(t, coh, color=C["grey"], lw=0.8, zorder=1)
ax1.scatter(t, coh, s=18, color=C["blue"], zorder=2)
ax1.scatter([t[best]], [coh[best]], s=90, marker="*", color=C["red"], zorder=3, label="highest (chosen)")
ax1.scatter([t[0]], [coh[0]], s=40, marker="s", facecolors="none", edgecolors=C["black"], zorder=3,
            label="first date")
ax1.set_xlabel("Candidate Reference date (days)")
ax1.set_ylabel("modelled stack coherence")
ax1.set_title("(a) choosing the Reference", fontsize=10)
ax1.legend(loc="lower center", frameon=False, fontsize=9, borderaxespad=0.1)
lo, hi = coh.min(), coh.max()
ax1.set_ylim(lo - 0.35 * (hi - lo), hi + 0.15 * (hi - lo))

pairs = [(i, j) for i in range(n) for j in range(i + 1, n)
         if t[j] - t[i] <= 48.0 and abs(bp[j] - bp[i]) <= 150.0]
ax2.axvspan(t[14], t[15], color="#EEEEEE", zorder=0)
ax2.text(0.5 * (t[14] + t[15]), 112, "gap", ha="center", va="top", fontsize=9, color="#555555")
for i, j in pairs:
    col = C["green"] if j < 15 else C["orange"]
    ax2.plot([t[i], t[j]], [bp[i], bp[j]], color=col, lw=0.9, zorder=1)
ax2.scatter(t, bp, s=16, color=C["blue"], zorder=2)
ax2.set_xlabel("Time (days)")
ax2.set_ylabel(r"$B_\perp$ (m)")
ax2.set_ylim(-125, 115)
ax2.set_title("(b) disconnected network", fontsize=10)

figstyle.save(fig, "stack_design_2")
print("best index", best, "t", t[best], "coh", round(coh[best], 3), "first", round(coh[0], 3), "pairs", len(pairs))
