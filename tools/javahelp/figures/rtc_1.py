"""rtc_1: angle-based versus area-based normalisation across a hill, computed in 1-D (range plane only).

Model. Plane-wave illumination from the left at incidence theta = 35 deg over a synthetic profile h(x)
sampled every 1 m. The radar measures slant range R = x sin(theta) - h cos(theta) in bins of 10 m.
Each 1 m facet contributes its area projected perpendicular to the line of sight,
dA_gamma = dx cos(theta) + dh sin(theta), to the bin its slant range falls into (Small 2011); facets hidden
from the radar (shadow test along the ray) contribute nothing. For a surface of uniform gamma0 = g:

    beta0(bin) = g * sum(dA_gamma in bin) / dR                (dR = bin size: the beta0 reference area)

Three estimates are mapped back to each ground position x:
    ellipsoid:     gamma0_E  = beta0(bin(x)) * tan(theta)
    angle-based:   gamma0_a  = beta0(bin(x)) * tan(theta_loc(x)),  theta_loc = theta - slope(x)
                   (the projected-local-incidence form of Terrain Correction's normalisation; undefined
                    where theta_loc <= 0, i.e. the slope is steeper than theta)
    area-based:    gamma0_T  = beta0(bin(x)) * dR / sum(dA_gamma in bin)
The plot shows each estimate relative to the true g, in dB. gamma0_T equals g wherever the bin received
any area, because the simulation and the normalisation use the same area model; the point of the figure is
where the single-angle correction departs from it (layover, slopes close to theta) and how large the
uncorrected terrain effect is.
"""
import numpy as np

import figstyle

C = figstyle.COLORS
theta = np.radians(35.0)
dx = 1.0
dR = 10.0
x = np.arange(0.0, 6000.0 + dx, dx)

# Asymmetric ridge: moderate-to-steep fore-slope (towards the radar on the left), steep back-slope.
x0 = 3000.0
h = np.where(x < x0,
             1000.0 * np.exp(-((x - x0) / 1100.0) ** 2),
             1000.0 * np.exp(-((x - x0) / 520.0) ** 2))
h += 120.0 * np.exp(-((x - 1200.0) / 300.0) ** 2)   # small hill: foreshortening only

slope = np.arctan(np.gradient(h, dx))                 # positive = facing the radar
R = x * np.sin(theta) - h * np.cos(theta)

# Shadow: a point is hidden if terrain nearer the radar rises above the ray towards the sensor.
u = h + x / np.tan(theta)
shadow = u < np.maximum.accumulate(u) - 1e-9

# Layover: the slant-range ordering is broken (a point is reached by the range of another branch).
lay = (R < np.maximum.accumulate(R) - 1e-9) | (R > np.minimum.accumulate(R[::-1])[::-1] + 1e-9)

# Facet areas (between consecutive samples), assigned to the bin of the facet centre.
dA = np.maximum(np.diff(x) * np.cos(theta) + np.diff(h) * np.sin(theta), 0.0)
Rm = 0.5 * (R[1:] + R[:-1])
visible = ~(shadow[1:] | shadow[:-1])
r0 = R.min()
nb = int(np.ceil((R.max() - r0) / dR)) + 1
bin_f = ((Rm - r0) // dR).astype(int)
area = np.bincount(bin_f[visible], weights=dA[visible], minlength=nb)

g = 1.0
beta0_bin = g * area / dR
bin_x = np.clip(((R - r0) // dR).astype(int), 0, nb - 1)
b = beta0_bin[bin_x]
valid = (b > 0) & ~shadow

with np.errstate(divide="ignore", invalid="ignore"):
    gE = b * np.tan(theta)
    tloc = theta - slope
    ga = np.where(tloc > 0, b * np.tan(tloc), np.nan)
    gT = b * dR / area[bin_x]
    to_db = lambda v: 10 * np.log10(v / g)  # noqa: E731
    eE = np.where(valid, to_db(gE), np.nan)
    ea = np.where(valid, to_db(ga), np.nan)
    eT = np.where(valid, to_db(gT), np.nan)

fig, axes = figstyle.plt.subplots(2, 1, figsize=(5.6, 4.4), dpi=figstyle.DPI, sharex=True,
                                  gridspec_kw={"height_ratios": [1.0, 1.6]})
km = x / 1000.0


def spans(mask):
    idx = np.flatnonzero(np.diff(np.r_[0, mask.astype(int), 0]))
    return zip(idx[0::2], idx[1::2] - 1)


for ax in axes:
    for a, e in spans(lay):
        ax.axvspan(km[a], km[e], color=C["orange"], alpha=0.18, lw=0)
    for a, e in spans(shadow):
        ax.axvspan(km[a], km[e], color=C["grey"], alpha=0.30, lw=0)

ax = axes[0]
ax.plot(km, h, color=C["black"], lw=1.2)
ax.set_ylabel("Height (m)")
ax.set_ylim(0, 1150)
ax.text(0.02, 0.88, "radar looks from the left, θ = 35°", transform=ax.transAxes, fontsize=9)
ax.text(2.2, 60, "layover", color=C["red"], fontsize=9, ha="center")
ax.text(3.45, 60, "shadow", color="#333333", fontsize=9, ha="center")
ax.set_title("(a) Terrain profile", fontsize=10, loc="left")

ax = axes[1]
ax.axhline(0, color=C["grey"], lw=0.8)
ax.plot(km, eE, color=C["orange"], lw=1.2, label="ellipsoid γ⁰ (no RTC)")
ax.plot(km, ea, color=C["blue"], lw=1.0, label="angle-based (θ local)")
ax.plot(km, eT, color=C["green"], lw=1.6, ls="--", label="area-based γ⁰ₜ")
ax.set_ylim(-10, 10)
ax.set_ylabel("Error vs. true γ⁰ (dB)")
ax.set_xlabel("Ground range (km)")
ax.set_xlim(0, 5.9)
ax.legend(loc="upper right", frameon=False, fontsize=9)
ax.set_title("(b) Retrieved γ⁰ of a uniform surface", fontsize=10, loc="left")

figstyle.save(fig, "rtc_1")

# Diagnostics for the page text
fs = slope[x < x0].max()
print(f"max fore-slope {np.degrees(fs):.1f} deg, max back-slope {np.degrees(-slope[x > x0].min()):.1f} deg")
print(f"layover fraction {lay.mean():.3f}, shadow fraction {shadow.mean():.3f}")
sm = (x > 900) & (x < 1200)
print(f"small hill fore-slope: ellipsoid err max {np.nanmax(eE[sm]):.2f} dB, angle err max {np.nanmax(np.abs(ea[sm])):.3f} dB")
print(f"in layover: angle-based finite {np.isfinite(ea[lay]).mean():.2f}, median |err| {np.nanmedian(np.abs(ea[lay])):.2f} dB")
