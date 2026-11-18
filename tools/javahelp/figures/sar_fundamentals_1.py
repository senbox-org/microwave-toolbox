"""Side-looking SAR imaging geometry (oblique sketch): platform, beam, footprint, near/far range, swath, azimuth."""
import matplotlib.patches as mpatches

import figstyle

C = figstyle.COLORS


def P(x, y, z):
    """Oblique projection: x = along-track (azimuth), y = cross-track (ground range), z = height."""
    return (y + 0.55 * x, z + 0.32 * x)


fig, ax = figstyle.figure(5.6, 3.4)
ax.set_aspect("equal")
ax.axis("off")

ZS = 6.0          # platform altitude (schematic)
XMAX = 9.0        # along-track extent drawn
YN, YF = 3.6, 8.6  # near / far edge of the swath (ground range)
X0, X1 = 3.0, 6.0  # azimuth extent of the instantaneous footprint
XS = 4.5          # platform along-track position

# ground plane
g = [P(0, -0.6, 0), P(XMAX, -0.6, 0), P(XMAX, 10.0, 0), P(0, 10.0, 0)]
ax.add_patch(mpatches.Polygon(g, closed=True, fc="#F4F4F4", ec=C["grey"], lw=0.6))

# swath strip
s = [P(0, YN, 0), P(XMAX, YN, 0), P(XMAX, YF, 0), P(0, YF, 0)]
ax.add_patch(mpatches.Polygon(s, closed=True, fc=C["sky"], ec="none", alpha=0.25))
for y in (YN, YF):
    ax.plot(*zip(P(0, y, 0), P(XMAX, y, 0)), color=C["blue"], lw=1.0, ls="--")

# nadir track and flight track
ax.plot(*zip(P(0, 0, 0), P(XMAX, 0, 0)), color=C["grey"], lw=1.0, ls=":")
ax.annotate("", xy=P(XMAX, 0, ZS), xytext=P(0, 0, ZS),
            arrowprops=dict(arrowstyle="-|>", color=C["black"], lw=1.2))
ax.text(*P(XMAX - 0.6, 0.3, ZS - 0.2), "flight direction\n(azimuth)", fontsize=9, ha="left", va="top")

# platform
sx, sy = P(XS, 0, ZS)
ax.plot(sx, sy, marker="s", ms=8, color=C["black"])
ax.text(sx - 0.25, sy + 0.25, "SAR", fontsize=9, ha="right", va="bottom")
ax.plot(*zip(P(XS, 0, ZS), P(XS, 0, 0)), color=C["grey"], lw=0.8, ls=":")
ax.text(*P(XS, -0.35, 0), "nadir", fontsize=9, ha="right", va="top")

# beam to instantaneous footprint
corners = [P(X0, YN, 0), P(X1, YN, 0), P(X1, YF, 0), P(X0, YF, 0)]
for c in corners:
    ax.plot([sx, c[0]], [sy, c[1]], color=C["orange"], lw=0.8)
ax.add_patch(mpatches.Polygon(corners, closed=True, fc=C["orange"], ec=C["orange"], alpha=0.45))

# slant range to near and far edge (side-looking: beam points away from nadir)
mid_near = P(0.5 * (X0 + X1), YN, 0)
mid_far = P(0.5 * (X0 + X1), YF, 0)
ax.plot([sx, mid_near[0]], [sy, mid_near[1]], color=C["red"], lw=1.3)
ax.plot([sx, mid_far[0]], [sy, mid_far[1]], color=C["red"], lw=1.3)
ax.text(0.5 * (sx + mid_far[0]) + 0.25, 0.5 * (sy + mid_far[1]) + 0.1, "slant range R",
        fontsize=9, color=C["red"], ha="left", va="bottom")

# labels on the swath
ax.text(*P(0.3, YN - 0.15, 0), "near range", fontsize=9, color=C["blue"], ha="left", va="top")
ax.text(*P(XMAX - 0.2, YF + 0.2, 0), "far range", fontsize=9, color=C["blue"], ha="left", va="bottom")
bx, by = P(XMAX - 1.0, 0.5 * (YN + YF), 0)
ax.text(bx + 0.3, by, "swath", fontsize=10, color=C["blue"], ha="left", va="center")

# range direction arrow on the ground
ax.annotate("", xy=P(0, 9.5, -0.9), xytext=P(0, 0.5, -0.9),
            arrowprops=dict(arrowstyle="-|>", color=C["black"], lw=1.0))
ax.text(*P(0, 5.0, -1.05), "range (cross-track)", fontsize=9, ha="center", va="top")

ax.text(*P(X0 - 0.2, YN + 0.2, 0), "beam\nfootprint", fontsize=9, color=C["orange"], ha="right", va="bottom")

ax.set_xlim(-1.0, 15.6)
ax.set_ylim(-2.2, 9.6)
figstyle.save(fig, "sar_fundamentals_1")
