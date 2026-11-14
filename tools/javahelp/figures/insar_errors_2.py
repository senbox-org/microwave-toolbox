"""insar_errors_2: why the accuracy of a displacement difference depends on the distance r between the
two points. Illustrative error terms for one Sentinel-1 12-day pair, as the standard deviation of the
LOS difference between two pixels r apart.

- Decorrelation noise: independent per pixel, so sqrt(2) * sigma whatever r is. sigma is the Monte Carlo
  phase standard deviation for coherence 0.5 and L = 20 independent looks (0.30 rad = 1.3 mm).
- Turbulent troposphere: empirical model of Emardson, Simons & Webb (2003), sigma = c * r^alpha with
  c = 2.5 mm, alpha = 0.5 (r in km), from GPS zenith delays in southern California; stated valid for
  about 10-800 km, so it is dotted below 10 km.
- Orbit: a residual perpendicular-baseline error dB leaves a ramp of about dB * dtheta of LOS across the
  swath (dtheta = 0.22 rad over the 250 km IW swath); dB = 1 cm gives 2.2 mm per 250 km, linear in r.
- Ionosphere (example): a differential TEC gradient of 1 TECU per 250 km; 1 TECU is
  K / f^2 = 40.28e16 / 5.405e9^2 = 13.8 mm of LOS at C-band; linear in r.
The black line is the root-sum-square of the four (independent terms add in variance).
"""
import numpy as np
from matplotlib.ticker import FuncFormatter

import figstyle

C = figstyle.COLORS
r = np.logspace(-1, np.log10(300), 200)  # km

sigma_noise = 1.31  # mm, MC value for coherence 0.5, L = 20 (see insar_errors_1.py)
noise = np.full_like(r, np.sqrt(2) * sigma_noise)
tropo = 2.5 * np.sqrt(r)
orbit = 0.01 * 0.22 * 1000.0 / 250.0 * r  # mm
iono_per_tecu_mm = 40.28e16 / 5.405e9 ** 2 * 1000.0
iono = iono_per_tecu_mm / 250.0 * r
total = np.sqrt(noise ** 2 + tropo ** 2 + orbit ** 2 + iono ** 2)
print(f"1 TECU at C-band = {iono_per_tecu_mm:.1f} mm LOS; orbit ramp {orbit[-1] / r[-1] * 250:.1f} mm per 250 km")
for rk in (1, 10, 50, 100, 250):
    i = np.argmin(abs(r - rk))
    print(f"r={rk:>3} km: noise {noise[i]:.1f}, tropo {tropo[i]:.1f}, orbit {orbit[i]:.2f}, "
          f"iono {iono[i]:.1f}, total {total[i]:.1f} mm")

fig, ax = figstyle.figure(5.6, 3.4)
lo = r < 10
ax.plot(r, noise, color=C["green"], lw=1.6, label="decorrelation (|γ| = 0.5, L = 20)")
ax.plot(r[~lo], tropo[~lo], color=C["blue"], lw=1.6, label="turbulent troposphere (Emardson 2003)")
ax.plot(r[lo], tropo[lo], color=C["blue"], lw=1.6, ls=":")
ax.plot(r, iono, color=C["purple"], lw=1.6, label="ionosphere, 1 TECU per 250 km")
ax.plot(r, orbit, color=C["orange"], lw=1.6, label="orbit, 1 cm baseline error")
ax.plot(r, total, color=C["black"], lw=2.0, label="root-sum-square")
ax.set_xscale("log")
ax.set_yscale("log")
ax.set_xlim(0.1, 300)
ax.set_ylim(0.01, 1000)
plain = FuncFormatter(lambda v, _: f"{v:g}")
ax.xaxis.set_major_formatter(plain)
ax.yaxis.set_major_formatter(plain)
ax.set_xlabel("distance between the two points (km)")
ax.set_ylabel("std. dev. of LOS difference (mm)")
ax.grid(True, which="major", color="#E4E4E4", lw=0.6)
ax.legend(loc="upper left", frameon=False, fontsize=9)
figstyle.save(fig, "insar_errors_2")
