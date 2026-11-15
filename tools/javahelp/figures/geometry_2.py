"""Foreshortening, layover and shadow: a hill seen by a radar looking from the left at incidence theta.

Far-field approximation: the radar is far away to the upper left, so lines of equal slant range
(wavefronts) are straight and perpendicular to the look direction. Each terrain point p = (x, z) has
slant range r = x sin(theta) - z cos(theta) (up to a constant). The bar under each hill shows the radar
image, with r scaled by 1/sin(theta) so that flat ground maps onto itself: x_img = x - z cot(theta).
The dotted connectors are lines of equal slant range.
"""
import numpy as np

import figstyle

C = figstyle.COLORS
THETA = np.radians(35.0)
COT = 1.0 / np.tan(THETA)
H = 0.45          # hill height (schematic units)
YBAR = -0.30      # height at which the image bar is drawn

# (title, front-slope angle alpha, back-slope angle beta, x-limits of the panel)
cases = [
    ("Foreshortening", 26.0, 26.0, (-0.75, 1.95)),
    ("Layover", 62.0, 25.0, (-1.15, 1.45)),
    ("Shadow", 25.0, 72.0, (-0.75, 1.65)),
]


def img(p):
    return p[0] - p[1] * COT


def bar(ax, x0, x1, color, lo=-1.15, hi=2.75):
    """Draw image segment [x0, x1] (image coordinates), shifted along the equal-range lines to the bar."""
    s = YBAR * COT
    a, b = max(x0 + s, lo), min(x1 + s, hi)
    if b > a:
        ax.plot([a, b], [YBAR, YBAR], color=color, lw=5.0, solid_capstyle="butt")


widths = [c[3][1] - c[3][0] for c in cases]
fig, axes = figstyle.figure(5.6, 2.1, ncols=3, gridspec_kw={"width_ratios": widths})
for ax, (title, alpha, beta, (xl, xr)) in zip(axes, cases):
    ax.set_aspect("equal")
    ax.axis("off")
    A = np.array([0.0, 0.0])
    B = A + np.array([H / np.tan(np.radians(alpha)), H])
    Cp = B + np.array([H / np.tan(np.radians(beta)), -H])

    # terrain
    ax.plot([xl, xr], [0, 0], color=C["black"], lw=1.0)
    ax.fill([A[0], B[0], Cp[0]], [A[1], B[1], Cp[1]], color="#E8E8E8", lw=0)
    ax.plot([A[0], B[0]], [A[1], B[1]], color=C["orange"], lw=2.2)
    ax.plot([B[0], Cp[0]], [B[1], Cp[1]], color=C["blue"], lw=2.2)

    # radar shadow: the ray grazing the top B reaches the ground again at S
    shadow = np.radians(beta) > np.pi / 2 - THETA
    if shadow:
        S = np.array([B[0] + H * np.tan(THETA), 0.0])
        ax.fill([B[0], Cp[0], S[0]], [B[1], Cp[1], S[1]], color=C["grey"], alpha=0.55, lw=0)
        ax.plot([B[0], S[0]], [B[1], S[1]], color=C["red"], lw=0.8, ls="--")

    # incoming rays from the upper left
    u = np.array([np.sin(THETA), -np.cos(THETA)])
    for x0 in (xl + 0.1, xl + 0.45):
        p0 = np.array([x0, 0.95])
        ax.annotate("", xy=p0 + 0.42 * u, xytext=p0,
                    arrowprops=dict(arrowstyle="-|>", color=C["red"], lw=1.0))

    # image bar: flat ground (black), front slope (orange), back slope (blue) or shadow (grey)
    ia, ib, ic = img(A), img(B), img(Cp)
    bar(ax, xl - 1.0, min(ia, ib), C["black"], xl, xr)
    bar(ax, min(ia, ib), max(ia, ib), C["orange"], xl, xr)
    if shadow:
        iS = img(S)
        bar(ax, ib, iS, "#BBBBBB", xl, xr)
        bar(ax, iS, xr + 1.0, C["black"], xl, xr)
    else:
        bar(ax, ib, ic, C["blue"], xl, xr)
        bar(ax, ic, xr + 1.0, C["black"], xl, xr)
    if title == "Layover":
        # the front slope is folded over: ground before A and the slope share the same range cells
        bar(ax, ib, ia, C["orange"], xl, xr)

    # equal-range connectors and labels
    pts = [("A", A), ("B", B), ("C", Cp)]
    for name, p in pts:
        q = np.array([img(p) + YBAR * COT, YBAR])
        ax.plot([p[0], q[0]], [p[1], q[1]], color=C["grey"], lw=0.7, ls=":")
        dy = 0.06 if name == "B" else -0.05
        ax.text(p[0], p[1] + dy, name, fontsize=9, ha="center", va="bottom" if name == "B" else "top")
        if not (shadow and name == "C"):
            ax.text(q[0], YBAR - 0.09, name.lower() + "'", fontsize=9, ha="center", va="top")
    ax.set_title(title, fontsize=10)
    ax.set_xlim(xl, xr)
    ax.set_ylim(-0.55, 1.0)

figstyle.save(fig, "geometry_2")
