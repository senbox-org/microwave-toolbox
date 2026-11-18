"""orbits_etad_1: Sentinel-1 orbit products, latency versus accuracy.

Requirements from the Copernicus POD service product requirements (AUX_PREORB 30 min / 1 m 2D RMS,
AUX_RESORB 180 min / 10 cm 2D RMS, AUX_POEORB 20 days / 5 cm 3D RMS); typical values
(RESORB < 5 cm, POEORB < 1 cm) from the AWS Open Data registry entry for the S-1 POD products.
"""
import figstyle

C = figstyle.COLORS
HOURS = {"AUX_PREORB\n(predicted)": 0.5, "AUX_RESORB\n(restituted)": 3.0, "AUX_POEORB\n(precise)": 20 * 24.0}
REQ_CM = [100.0, 10.0, 5.0]
TYP_CM = [None, 5.0, 1.0]

fig, ax = figstyle.figure(5.6, 3.2)
xs = list(HOURS.values())
ax.loglog(xs, REQ_CM, "o", color=C["blue"], ms=8, label="accuracy requirement (RMS)")
for x, y in zip(xs, TYP_CM):
    if y is not None:
        ax.loglog([x], [y], "s", mfc="white", mec=C["orange"], mew=2, ms=8)
ax.loglog([], [], "s", mfc="white", mec=C["orange"], mew=2, ms=8, label="typical achieved")
for (name, x), y in zip(HOURS.items(), REQ_CM):
    ax.text(x * 1.35, y * 1.15, name, fontsize=9, va="bottom")
ax.set_xlim(0.2, 3000)
ax.set_ylim(0.5, 300)
ax.set_xticks([0.5, 3, 24, 480])
ax.set_xticklabels(["30 min", "3 h", "1 day", "20 days"])
ax.set_yticks([1, 5, 10, 100])
ax.set_yticklabels(["1", "5", "10", "100"])
ax.set_xlabel("Latency after sensing")
ax.set_ylabel("Orbit position error [cm]")
ax.legend(loc="lower left", frameon=False)
figstyle.save(fig, "orbits_etad_1")
