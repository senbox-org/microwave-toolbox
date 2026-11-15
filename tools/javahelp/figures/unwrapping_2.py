"""unwrapping_2: residues. Wrapped phase (in cycles) at the four pixels of a 2 x 2 loop.
Going round the loop, each difference is wrapped into [-0.5, 0.5) cycles and summed.
A consistent loop sums to 0; a residue sums to +1 or -1 cycle."""
import matplotlib.patches as mpatches
import numpy as np

import figstyle

C = figstyle.COLORS


def wrapc(d):
    return (d + 0.5) % 1.0 - 0.5


def draw(ax, vals, title):
    pos = [(0, 1), (1, 1), (1, 0), (0, 0)]       # clockwise from top-left
    total = 0.0
    for k in range(4):
        p, q = np.array(pos[k], float), np.array(pos[(k + 1) % 4], float)
        d = wrapc(vals[(k + 1) % 4] - vals[k])
        total += d
        ax.annotate("", xy=p + 0.78 * (q - p), xytext=p + 0.22 * (q - p),
                    arrowprops=dict(arrowstyle="-|>", color=C["grey"], lw=1.2))
        m = 0.5 * (p + q)
        off = np.array([0.0, 0.12]) if p[1] == q[1] else np.array([0.14, 0.0])
        if (p[1] == q[1] and p[1] == 0) or (p[0] == q[0] and p[0] == 0):
            off = -off
        ax.text(*(m + off), f"{d:+.1f}", ha="center", va="center", fontsize=9, color=C["black"])
    for (px, py), v in zip(pos, vals):
        ax.add_patch(mpatches.Circle((px, py), 0.17, facecolor="white", edgecolor=C["blue"], lw=1.4))
        ax.text(px, py, f"{v:.1f}", ha="center", va="center", fontsize=10, color=C["blue"])
    col = C["red"] if abs(round(total)) else C["green"]
    ax.text(0.5, 0.5, "sum\n" + (f"{round(total):+d}" if round(total) else "0"), ha="center", va="center", fontsize=10, color=col,
            fontweight="bold")
    ax.set_title(title, fontsize=10)
    ax.set_xlim(-0.45, 1.45)
    ax.set_ylim(-0.45, 1.45)
    ax.set_aspect("equal")
    ax.axis("off")


fig, (a1, a2) = figstyle.figure(5.6, 2.8, ncols=2)
draw(a1, [0.0, 0.1, 0.2, 0.1], "consistent loop (cycles)")
draw(a2, [0.0, 0.3, 0.6, 0.9], "residue (cycles)")
figstyle.save(fig, "unwrapping_2")
