"""multitemporal_1: synthetic one-year backscatter time series of three land covers, and their temporal statistics.

SYNTHETIC data, for illustration only. 31 dates at a 12-day repeat. Each date of each pixel is the "true"
gamma0 (a smooth seasonal model, in linear units) times an independent unit-mean Gamma speckle variable:
  - crop and forest: L = 4.4 looks (the ENL of a Sentinel-1 IW GRDH product);
  - urban: modelled as a stable dominant scatterer with low fluctuation, Gamma with L = 30.
Crop model: bare soil in winter (-14 dB) with two rain events (+3 dB for one date each), growth to -8.5 dB in
early summer, harvest drop to -15 dB in August, ploughed soil (-12 dB) in autumn. Forest: constant -7 dB.
Urban: constant -1 dB.

(a) single-pixel samples (markers) and true curves (lines), in dB for display. For the forest pixel the
    temporal mean taken in linear units (solid grey) and the mean of the dB values (dotted) are drawn; the
    dB mean is biased low by about 0.5 dB at L = 4.4.
(b) temporal coefficient of variation CV = std / mean of the linear intensity over the 31 dates, for the
    same pixels. Dashed line: CV of speckle alone, 1/sqrt(L) = 0.48 at L = 4.4. The values are printed.
"""
import numpy as np

import figstyle

C = figstyle.COLORS
rng = np.random.default_rng(20261112)

N = 31
doy = 1 + 12 * np.arange(N)            # day of year of each acquisition
db = lambda x: 10 * np.log10(x)        # noqa: E731
lin = lambda x: 10 ** (x / 10)         # noqa: E731

# --- true seasonal curves, dB -------------------------------------------------------------------------
t = np.linspace(1, 366, 400)


def crop_true(d):
    d = np.asarray(d, float)
    winter, peak, harvest, plough = -14.0, -8.5, -15.0, -12.0
    grow = winter + (peak - winter) / (1 + np.exp(-(d - 130) / 12))            # spring growth
    out = np.where(d < 215, grow, harvest)                                      # harvest at day 215
    out = np.where(d >= 275, plough, out)                                       # ploughing at day 275
    return out


crop_db_true = crop_true(doy)
rain = np.zeros(N)
rain[[3, 24]] = 3.0                      # two rain events: wet soil, +3 dB for one date each
crop_db_true_dates = crop_db_true + rain
forest_db_true = np.full(N, -7.0)
urban_db_true = np.full(N, -1.0)

# --- speckled samples, linear ---------------------------------------------------------------------------
L_dist, L_urban = 4.4, 30.0


def speckle(L, n):
    return rng.gamma(L, 1.0 / L, n)      # unit mean, variance 1/L


crop = lin(crop_db_true_dates) * speckle(L_dist, N)
forest = lin(forest_db_true) * speckle(L_dist, N)
urban = lin(urban_db_true) * speckle(L_urban, N)


def cv(x):
    return np.std(x) / np.mean(x)


stats = {}
for name, x in (("crop", crop), ("forest", forest), ("urban", urban)):
    stats[name] = (db(np.mean(x)), np.mean(db(x)), cv(x))
    print(f"{name:6s}: mean(lin) = {stats[name][0]:6.2f} dB, mean(dB) = {stats[name][1]:6.2f} dB, "
          f"CV = {stats[name][2]:.2f}")
print(f"speckle-only CV at L = {L_dist}: {1 / np.sqrt(L_dist):.2f}")

# --- plot -------------------------------------------------------------------------------------------------
import matplotlib.pyplot as plt  # noqa: E402

fig = plt.figure(figsize=(5.6, 3.6), dpi=figstyle.DPI)
gs = fig.add_gridspec(1, 2, width_ratios=[2.4, 1.0])
ax = fig.add_subplot(gs[0])
bx = fig.add_subplot(gs[1])

series = (("Crop", crop, crop_true(t), C["orange"], "o"),
          ("Forest", forest, np.full_like(t, -7.0), C["green"], "s"),
          ("Urban", urban, np.full_like(t, -1.0), C["blue"], "^"))
for name, x, truth, col, mk in series:
    ax.plot(t, truth, color=col, lw=1.6)
    ax.plot(doy, db(x), mk, color=col, ms=3.5, ls="none", label=name)
# rain events on the crop pixel
for i in np.flatnonzero(rain):
    ax.annotate("rain", (doy[i], db(crop[i])), xytext=(doy[i] + 8, db(crop[i]) + 3.0), fontsize=9,
                bbox=dict(boxstyle="square,pad=0.1", fc="white", ec="none"),
                color=C["black"], arrowprops=dict(arrowstyle="-", lw=0.6, color=C["black"]))
# forest: mean in linear units vs mean of dB
m_lin, m_db = stats["forest"][0], stats["forest"][1]
ax.axhline(m_lin, color=C["grey"], lw=1.2, xmin=0.0, xmax=1.0)
ax.axhline(m_db, color=C["black"], lw=1.0, ls=":")
ax.text(366, -25.6, f"Forest mean: {m_lin:.1f} dB from linear values (grey)\n"
        f"{m_db:.1f} dB if averaged in dB (dotted)", fontsize=9, ha="right", va="bottom")
ax.set_xlim(0, 370)
ax.set_ylim(-26, 8)
months = [1, 32, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335]
ax.set_xticks(months)
ax.set_xticklabels(list("JFMAMJJASOND"))
ax.set_xlabel("Month (12-day repeat, 31 dates)")
ax.set_ylabel(r"$\gamma^0_T$ (dB, display only)")
ax.set_title("(a) Synthetic single-pixel series", fontsize=10, loc="left")
ax.legend(frameon=False, loc="upper center", ncol=3, fontsize=9, bbox_to_anchor=(0.5, 1.0))

names = ["Crop", "Forest", "Urban"]
vals = [stats["crop"][2], stats["forest"][2], stats["urban"][2]]
cols = [C["orange"], C["green"], C["blue"]]
bx.bar(names, vals, color=cols, width=0.6)
for i, v in enumerate(vals):
    bx.text(i, v + 0.02, f"{v:.2f}", ha="center", va="bottom", fontsize=9)
cv0 = 1 / np.sqrt(L_dist)
bx.axhline(cv0, color=C["black"], lw=1.0, ls="--")
bx.text(2.45, 0.99, "dashed: speckle\nonly, L = 4.4", fontsize=9, ha="right", va="top")
bx.set_ylim(0, 1.0)
bx.set_ylabel("Temporal CV of intensity")
bx.set_title("(b) Temporal CV", fontsize=10, loc="left")

figstyle.save(fig, "multitemporal_1")
