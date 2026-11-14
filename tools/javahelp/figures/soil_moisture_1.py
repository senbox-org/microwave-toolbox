"""soil_moisture_1: real part of the soil relative permittivity versus volumetric moisture for two
textures, from the Hallikainen et al. (1985) polynomial

    eps' = (a0 + a1 S + a2 C) + (b0 + b1 S + b2 C) mv + (c0 + c1 S + c2 C) mv^2

with S, C = sand and clay content (%) and mv = volumetric moisture (m^3/m^3).

The coefficients are the ones SNAP itself uses: rstb/rstb-op-soil-moisture/src/main/resources/
auxdata/sm_luts/soil_moisture.config (Hallikainen.a0 ... c2), read by HallikainenDielectricModel.
They are Hallikainen's real-part fit at 6 GHz.
"""
import numpy as np

import figstyle

a = (1.993, 0.002, 0.015)
b = (38.086, -0.176, -0.633)
c = (10.720, 1.256, 1.522)


def eps_real(mv, sand, clay):
    A = a[0] + a[1] * sand + a[2] * clay
    B = b[0] + b[1] * sand + b[2] * clay
    Cq = c[0] + c[1] * sand + c[2] * clay
    return A + B * mv + Cq * mv * mv


if __name__ == "__main__":
    col = figstyle.COLORS
    mv = np.linspace(0.0, 0.5, 300)
    fig, ax = figstyle.figure(5.6, 3.2)
    for sand, clay, name, cc, ls in ((60.0, 10.0, "sandy loam (sand 60 %, clay 10 %)", col["orange"], "-"),
                                     (5.0, 50.0, "clay-rich soil (sand 5 %, clay 50 %)", col["blue"], "--")):
        ax.plot(mv, eps_real(mv, sand, clay), color=cc, ls=ls, lw=2, label=name)
        print(name, [round(float(eps_real(m, sand, clay)), 2) for m in (0.0, 0.1, 0.2, 0.3, 0.4)])
    ax.set_xlim(0, 0.5)
    ax.set_ylim(0, 40)
    ax.set_xlabel("Volumetric soil moisture m$_v$ (m³/m³)")
    ax.set_ylabel("Real relative permittivity ε′ (-)")
    ax.set_title("Hallikainen et al. (1985) fit at 6 GHz, as used in SNAP", fontsize=10)
    ax.grid(True, color="#DDDDDD", lw=0.6)
    ax.legend(frameon=False, loc="upper left")
    figstyle.save(fig, "soil_moisture_1")
