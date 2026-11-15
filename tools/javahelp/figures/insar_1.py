"""insar_1: repeat-pass InSAR geometry - baseline B split into B-parallel and B-perpendicular
relative to the line of sight, look angle theta, ranges R1/R2, target height h.
Schematic, not to scale (the baseline is exaggerated about 1000x)."""
import numpy as np

import figstyle

C = figstyle.COLORS
fig, ax = figstyle.figure(5.6, 3.8)

S1 = np.array([0.0, 6.6])            # Reference antenna
P = np.array([6.6, 0.6])              # target at height h above the reference surface
P0 = np.array([P[0], 0.0])            # foot of the target on the reference surface
u = (P - S1) / np.linalg.norm(P - S1) # line-of-sight unit vector (Reference)
n = np.array([u[1], -u[0]])           # perpendicular to the LOS, pointing up-right
if n[1] < 0:
    n = -n
B_par, B_perp = 0.9, 2.1             # exaggerated components
S2 = S1 + B_par * u + B_perp * n      # Secondary antenna
F = S1 + B_par * u                    # foot point of B-perp on the Reference LOS

# ground and nadir
ax.plot([-1.0, 8.6], [0, 0], color=C["black"], lw=1.2)
ax.fill_between([-1.0, 8.6], [0, 0], [-0.5, -0.5], color="#EEEEEE", lw=0)
ax.plot([S1[0], S1[0]], [S1[1], 0], color=C["grey"], ls=":", lw=1)
ax.text(S1[0] - 0.15, 0.25, "nadir", ha="right", va="bottom", fontsize=9, color=C["grey"])

# target with height
ax.plot([P0[0], P[0]], [P0[1], P[1]], color=C["green"], lw=2)
ax.text(P[0] + 0.15, 0.25, "h", color=C["green"], fontsize=10, va="center")
ax.plot(*P, "o", color=C["black"], ms=4)
ax.text(P[0] + 0.15, P[1] + 0.15, "P", fontsize=10)

# ranges
ax.plot([S1[0], P[0]], [S1[1], P[1]], color=C["blue"], lw=1.4)
ax.plot([S2[0], P[0]], [S2[1], P[1]], color=C["orange"], lw=1.4)
m1 = S1 + 0.55 * (P - S1)
m2 = S2 + 0.45 * (P - S2)
ax.text(m1[0] - 0.25, m1[1] - 0.1, "R$_1$", color=C["blue"], fontsize=10, ha="right")
ax.text(m2[0] + 0.2, m2[1] + 0.1, "R$_2$", color=C["orange"], fontsize=10)

# baseline and its components
ax.annotate("", xy=S2, xytext=S1, arrowprops=dict(arrowstyle="-|>", color=C["black"], lw=1.4))
ax.annotate("", xy=F, xytext=S1, arrowprops=dict(arrowstyle="-|>", color=C["red"], lw=1.6))
ax.annotate("", xy=S2, xytext=F, arrowprops=dict(arrowstyle="-|>", color=C["purple"], lw=1.6))
mb = 0.5 * (S1 + S2)
ax.text(mb[0] - 0.2, mb[1] + 0.2, "B", fontsize=11, ha="right", fontweight="bold")
ax.text(F[0] + 0.15, F[1] - 0.15, "B$_\\parallel$", color=C["red"], fontsize=10, ha="left", va="top")
mp = 0.5 * (F + S2)
ax.text(mp[0] + 0.2, mp[1] - 0.3, "B$_\\perp$", color=C["purple"], fontsize=10)

# look angle
r = 1.6
a0 = np.degrees(np.arctan2(-1, 0))
a1 = np.degrees(np.arctan2(u[1], u[0]))
t = np.radians(np.linspace(a0, a1, 40))
ax.plot(S1[0] + r * np.cos(t), S1[1] + r * np.sin(t), color=C["black"], lw=0.9)
ax.text(S1[0] + 0.25, S1[1] - 2.2, "$\\theta$", fontsize=11)

# antennas
for S, lab, col in ((S1, "Reference", C["blue"]), (S2, "Secondary", C["orange"])):
    ax.plot(*S, marker="s", color=col, ms=8)
for S, lab, col, dx in ((S1, "Reference", C["blue"], -0.25), (S2, "Secondary", C["orange"], 0.25)):
    ax.text(S[0] + dx, S[1] + 0.35, lab, color=col, fontsize=10,
            ha="right" if dx < 0 else "left")

ax.text(8.5, 8.4, "not to scale\n(baseline exaggerated)", ha="right", va="top", fontsize=9, color=C["grey"])
ax.set_xlim(-2.0, 8.6)
ax.set_ylim(-0.5, 8.6)
ax.set_aspect("equal")
ax.axis("off")
figstyle.save(fig, "insar_1")
