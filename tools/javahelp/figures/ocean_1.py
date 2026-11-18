"""ocean_1: Bragg resonance geometry.

Two parallel radar rays at incidence angle theta hit neighbouring crests of a surface wave of
wavelength lambda_B. The second ray travels an extra one-way path lambda_B * sin(theta), i.e.
2 * lambda_B * sin(theta) two-way. Echoes add in phase when this equals the radar wavelength:
lambda_B = lambda / (2 sin theta).
"""
import numpy as np

import figstyle

C = figstyle.COLORS
theta = np.deg2rad(35.0)
lam_b = 1.0  # surface wavelength (plot units)
amp = 0.07

fig, ax = figstyle.figure(5.6, 3.0)
x = np.linspace(-0.6, 3.6, 800)
ax.fill_between(x, -0.45, amp * np.cos(2 * np.pi * x / lam_b), color=C["sky"], alpha=0.35, lw=0)
ax.plot(x, amp * np.cos(2 * np.pi * x / lam_b), color=C["blue"], lw=1.6)

# incoming rays: direction of propagation points down and to the right (radar on the left)
d = np.array([np.sin(theta), -np.cos(theta)])
L = 1.5
crests = [1.0, 2.0]
perp = np.array([np.cos(theta), np.sin(theta)])
# both rays start on a common wavefront (perpendicular to the rays)
p1 = np.array([crests[0], amp]) - L * d
p2_on_wf = p1 + (crests[1] - crests[0]) * np.cos(theta) * perp
for start, xc in ((p1, crests[0]), (p2_on_wf, crests[1])):
    ax.annotate("", xy=(xc, amp), xytext=start,
                arrowprops=dict(arrowstyle="-|>", color=C["red"], lw=1.6))

# wavefront through both ray starts
wf = np.vstack([p1 - 0.2 * perp, p1 + 1.05 * perp])
ax.plot(wf[:, 0], wf[:, 1], color=C["grey"], lw=1.0, ls="--")
ax.text(wf[1, 0] + 0.05, wf[1, 1] - 0.05, "wavefront", fontsize=9, color=C["grey"])

# extra one-way path for the second ray: from the wavefront to the level of the first ray's end
extra_end = p2_on_wf + (crests[1] - crests[0]) * np.sin(theta) * d
seg = np.vstack([p2_on_wf, extra_end]) + 0.06 * perp
ax.plot(seg[:, 0], seg[:, 1], color=C["orange"], lw=4, solid_capstyle="butt")
ax.text(seg[0, 0] + 0.25, seg[0, 1] - 0.12, r"extra path $\lambda_B \sin\theta$ (one way)",
        fontsize=9, color=C["black"])

# lambda_B dimension
yb = -0.3
ax.annotate("", xy=(crests[1], yb), xytext=(crests[0], yb),
            arrowprops=dict(arrowstyle="<->", color=C["black"], lw=1.0))
ax.text(1.5, yb - 0.11, r"$\lambda_B$", ha="center", fontsize=10)

# incidence angle at the first crest
ax.plot([crests[0], crests[0]], [amp, amp + 0.75], color=C["black"], lw=0.8, ls=":")
arc_t = np.linspace(np.pi / 2, np.pi / 2 + theta, 30)
r = 0.42
ax.plot(crests[0] + r * np.cos(arc_t), amp + r * np.sin(arc_t), color=C["black"], lw=0.8)
ax.text(crests[0] - 0.22, amp + 0.50, r"$\theta$", fontsize=10)

ax.text(2.45, 0.75, "In phase when\n" + r"$2\,\lambda_B \sin\theta = \lambda$", fontsize=10,
        va="top")
ax.text(-0.25, 1.62, "from radar", fontsize=9, color=C["red"])
ax.set_xlim(-0.6, 3.6)
ax.set_ylim(-0.5, 2.05)
ax.set_aspect("equal")
ax.axis("off")
figstyle.save(fig, "ocean_1")
