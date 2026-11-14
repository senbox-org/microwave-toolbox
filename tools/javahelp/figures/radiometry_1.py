"""radiometry_1: the three reference areas (beta0, sigma0, gamma0) and why slopes facing the radar look bright.

(a) Flat ground, cross-section in the range plane. One slant-range cell of extent dr (measured along the
    line of sight) covers the ground length dr / sin(theta) (A_sigma). Projected onto the plane perpendicular
    to the line of sight it has length dr / tan(theta) (A_gamma). The three form a right triangle.
(b) The same slant-range cell on a slope facing the radar (tilt alpha) covers more ground, so it collects
    more backscattered power: the local incidence angle is theta - alpha.
"""
import numpy as np

import figstyle

C = figstyle.COLORS
theta = np.radians(38.0)
los = np.array([np.sin(theta), -np.cos(theta)])   # propagation direction (radar on the left, looking down-right)
perp = np.array([np.cos(theta), np.sin(theta)])   # in-plane direction perpendicular to the line of sight

fig, axes = figstyle.plt.subplots(1, 2, figsize=(5.6, 3.0), dpi=figstyle.DPI)


def mid(p, q):
    return (p + q) / 2


# ---- (a) reference areas on flat ground -------------------------------------------------------------
ax = axes[0]
A = np.array([0.6, 0.0])
dg = 2.6
B = A + np.array([dg, 0.0])
Cpt = A + dg * np.cos(theta) * perp          # foot of the perpendicular from B: CB is along the line of sight

ax.plot([-0.1, 3.9], [0, 0], color=C["grey"], lw=1)
ax.fill_between([-0.1, 3.9], [-0.25, -0.25], [0, 0], color="#EEEEEE", lw=0)
ax.plot(*zip(A, B), color=C["orange"], lw=3.2, solid_capstyle="butt")
ax.plot(*zip(A, Cpt), color=C["green"], lw=3.2, solid_capstyle="butt")
ax.plot(*zip(Cpt, B), color=C["blue"], lw=3.2, solid_capstyle="butt")

# incoming rays (line of sight) ending at A and at C (the ray through C continues along CB to B)
for P, length in ((A, 2.0), (Cpt, 0.9)):
    ax.annotate("", xy=P, xytext=P - length * los,
                arrowprops=dict(arrowstyle="->", color=C["black"], lw=0.8, linestyle=(0, (4, 3))))
# vertical and incidence angle at B
ax.plot([B[0], B[0]], [0, 1.3], color=C["grey"], lw=0.8, ls=":")
arc = np.linspace(np.pi / 2, np.pi / 2 + theta, 30)
ax.plot(B[0] + 0.45 * np.cos(arc), 0.45 * np.sin(arc), color=C["black"], lw=0.8)
ax.text(B[0] + 0.08, 0.30, r"$\theta$", fontsize=10, ha="left")

ax.text(*(mid(A, B) + np.array([0.0, -0.17])), r"$A_\sigma$ (ground)", color=C["orange"], fontsize=9,
        ha="center", va="top")
ax.text(*(mid(A, Cpt) + np.array([-0.12, 0.10])), r"$A_\gamma$", color=C["green"], fontsize=10,
        ha="right", va="bottom")
ax.text(B[0] + 0.08, mid(Cpt, B)[1] + 0.25, r"$A_\beta$", color=C["blue"], fontsize=10,
        ha="left", va="bottom")
ax.text(-0.05, 1.75, "from the radar", fontsize=9, ha="left")
ax.set_title("(a) Reference areas, flat ground", fontsize=10)
ax.set_xlim(-0.2, 4.0)
ax.set_ylim(-0.6, 2.0)
ax.set_aspect("equal")
ax.axis("off")

# ---- (b) same slant-range cell on flat ground and on a slope facing the radar --------------------------
ax = axes[1]
alpha = np.radians(16.0)                      # terrain tilt towards the radar (local incidence theta - alpha)
dr = 0.75
O = np.array([0.6, 0.0])                      # first constant-range line meets flat ground here
W1, W2 = O, O + dr * los                      # points on the two constant-range lines


def cut(P, d):
    """Points where the line P + t d crosses the two constant-range lines."""
    t1 = np.dot(W1 - P, los) / np.dot(d, los)
    t2 = np.dot(W2 - P, los) / np.dot(d, los)
    return P + t1 * d, P + t2 * d


flat_dir = np.array([1.0, 0.0])
slope_dir = np.array([np.cos(alpha), np.sin(alpha)])
S0 = W1 - 1.6 * perp                          # the slope line starts on the first constant-range line
f1, f2 = cut(O, flat_dir)
s1, s2 = cut(S0, slope_dir)
for P0 in (W1, W2):
    ax.plot(*zip(P0 - 2.0 * perp, P0 + 1.1 * perp), color=C["blue"], lw=0.9, ls="--")
ax.annotate("", xy=W2 + 0.95 * perp, xytext=W1 + 0.95 * perp,
            arrowprops=dict(arrowstyle="<->", color=C["blue"], lw=0.9, shrinkA=0, shrinkB=0))
ax.text(*(W1 + 0.95 * perp + 0.5 * dr * los + np.array([0.12, 0.08])), r"$\delta r$", color=C["blue"],
        fontsize=10, ha="left")
ax.plot([-0.8, 3.0], [0, 0], color=C["grey"], lw=1)
ax.plot(*zip(S0 - 0.15 * slope_dir, S0 + 3.6 * slope_dir), color=C["grey"], lw=1)
ax.plot(*zip(f1, f2), color=C["orange"], lw=3.2, solid_capstyle="butt")
ax.plot(*zip(s1, s2), color=C["red"], lw=3.2, solid_capstyle="butt")
ax.text(f2[0] + 0.15, 0.05, "flat", color=C["orange"], fontsize=9, ha="left", va="bottom")
ax.text(s2[0] + 0.15, s2[1] - 0.12, "slope facing\nthe radar", color=C["red"], fontsize=9, ha="left",
        va="top")
ax.text(-0.8, -1.4, "Same " + r"$\delta r$" + ", more ground area:\nbrighter in "
        + r"$\beta^0$, $\sigma^0_E$, $\gamma^0_E$", fontsize=9, ha="left", va="top")
ax.set_title("(b) Same range cell on a slope", fontsize=10)
ax.set_xlim(-0.9, 3.1)
ax.set_ylim(-2.3, 1.2)
ax.set_aspect("equal")
ax.axis("off")

figstyle.save(fig, "radiometry_1")
