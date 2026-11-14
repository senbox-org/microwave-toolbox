"""ocean_2: CMOD5.N VV backscatter versus 10 m equivalent-neutral wind speed at 35 deg incidence,
for upwind, downwind and crosswind looks.

Model form and code: Hersbach (2003), ECMWF Tech. Memo. 395, Eqs. (1), (25)-(33) and Fig. 38
(the Fortran listing). Coefficients: CMOD5.N column of Table 1 in Hersbach (2008),
"CMOD5.N: A C-band geophysical model function for equivalent neutral wind",
ECMWF Tech. Memo. 554.
"""
import numpy as np

import figstyle

# CMOD5.N coefficients c1..c28 (ECMWF Tech. Memo. 554, Table 1)
C5N = [-0.6878, -0.7957, 0.3380, -0.1728, 0.0000, 0.0040, 0.1103, 0.0159, 6.7329, 2.7713,
       -2.2885, 0.4971, -0.7250, 0.0450, 0.0066, 0.3222, 0.0120, 22.700, 2.0813, 3.0000,
       8.3659, -3.3428, 1.3236, 6.2437, 2.3893, 0.3249, 4.1590, 1.6930]


def cmod(v, phi_deg, theta_deg, c=C5N):
    """sigma0_VV (linear) - a direct transcription of the CMOD5 Fortran routine (TM 395, Fig. 38)."""
    c = [None] + list(c)  # 1-based like the Fortran code
    thetm, thethr, zpow = 40.0, 25.0, 1.6
    y0, pn = c[19], c[20]
    a = y0 - (y0 - 1.0) / pn
    b = 1.0 / (pn * (y0 - 1.0) ** (pn - 1.0))
    csfi = np.cos(np.deg2rad(phi_deg))
    x = (theta_deg - thetm) / thethr
    xx = x * x
    a0 = c[1] + c[2] * x + c[3] * xx + c[4] * x * xx
    a1 = c[5] + c[6] * x
    a2 = c[7] + c[8] * x
    gam = c[9] + c[10] * x + c[11] * xx
    s0 = c[12] + c[13] * x
    s = a2 * v
    a3 = 1.0 / (1.0 + np.exp(-np.maximum(s, s0)))
    a3 = np.where(s < s0, a3 * (s / s0) ** (s0 * (1.0 - a3)), a3)
    b0 = (a3 ** gam) * 10.0 ** (a0 + a1 * v)
    b1 = c[15] * v * (0.5 + x - np.tanh(4.0 * (x + c[16] + c[17] * v)))
    b1 = (c[14] * (1.0 + x) - b1) / (np.exp(0.34 * (v - c[18])) + 1.0)
    v0 = c[21] + c[22] * x + c[23] * xx
    d1 = c[24] + c[25] * x + c[26] * xx
    d2 = c[27] + c[28] * x
    v2 = v / v0 + 1.0
    v2 = np.where(v2 < y0, a + b * (v2 - 1.0) ** pn, v2)
    b2 = (-d1 + d2 * v2) * np.exp(-v2)
    return b0 * (1.0 + b1 * csfi + b2 * (2.0 * csfi * csfi - 1.0)) ** zpow


if __name__ == "__main__":
    col = figstyle.COLORS
    v = np.linspace(2.0, 25.0, 400)
    theta = 35.0
    fig, ax = figstyle.figure(5.6, 3.2)
    for phi, name, cc, ls in ((0, "upwind (φ = 0°)", col["blue"], "-"),
                              (180, "downwind (φ = 180°)", col["orange"], "--"),
                              (90, "crosswind (φ = 90°)", col["green"], "-.")):
        ax.plot(v, 10 * np.log10(cmod(v, phi, theta)), color=cc, ls=ls, lw=2, label=name)
    for vv in (5, 10, 15):
        print(vv, [round(10 * np.log10(float(cmod(vv, p, theta))), 2) for p in (0, 90, 180)])
    ax.set_xlim(0, 25)
    ax.set_xlabel("10 m equivalent-neutral wind speed (m/s)")
    ax.set_ylabel("σ⁰ VV (dB)")
    ax.set_title("CMOD5.N at 35° incidence", fontsize=10)
    ax.grid(True, color="#DDDDDD", lw=0.6)
    ax.legend(frameon=False, loc="lower right")
    figstyle.save(fig, "ocean_2")
