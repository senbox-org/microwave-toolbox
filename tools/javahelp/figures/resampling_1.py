"""resampling_1: interpolation kernels used by SNAP and their magnitude frequency responses.

Kernels as implemented in snap-engine org.esa.snap.core.dataop.resamp:
  NEAREST_NEIGHBOUR        rect, width 1
  BILINEAR_INTERPOLATION   triangle, half-width 1
  CUBIC_CONVOLUTION        Keys (1981) cubic convolution with a = -0.5 (CubicConvolutionResampling)
  BISINC_n_POINT           sinc(x) * Hann window 0.5(1 + cos(2 pi x / (n - 1))), |x| <= n // 2
                           (BiSincInterpolationResampling; weights also normalised to sum 1 at each shift)
The frequency response H(f) is the continuous Fourier transform of each kernel, computed numerically.
Frequency is in units of the sampling rate f_s. The shaded band is the signal band of data oversampled by
1.25 (B / f_s = 0.8); the grey band is where its first spectral replica lies.
"""
import matplotlib.pyplot as plt
import numpy as np

import figstyle

C = figstyle.COLORS


def keys(x, a=-0.5):
    ax = np.abs(x)
    y = np.zeros_like(x)
    m1 = ax < 1
    m2 = (ax >= 1) & (ax < 2)
    y[m1] = (a + 2) * ax[m1] ** 3 - (a + 3) * ax[m1] ** 2 + 1
    y[m2] = a * ax[m2] ** 3 - 5 * a * ax[m2] ** 2 + 8 * a * ax[m2] - 4 * a
    return y


def bisinc(x, n):
    half = n // 2
    w = 0.5 * (1 + np.cos(2 * np.pi * x / (n - 1)))
    return np.where(np.abs(x) <= half, np.sinc(x) * w, 0.0)


KERNELS = [
    ("nearest neighbour", lambda x: np.where(np.abs(x) < 0.5, 1.0, 0.0), C["grey"], "-"),
    ("bilinear", lambda x: np.clip(1 - np.abs(x), 0, None), C["orange"], "-"),
    ("cubic convolution", keys, C["green"], "-"),
    ("bisinc 5", lambda x: bisinc(x, 5), C["blue"], "-"),
    ("bisinc 21", lambda x: bisinc(x, 21), C["red"], "--"),
]

dx = 1e-3
x = np.arange(-12, 12, dx) + dx / 2
f = np.linspace(0, 1.5, 601)
B = 0.8

fig = plt.figure(figsize=(5.6, 3.7), dpi=figstyle.DPI)
gs = fig.add_gridspec(2, 2, width_ratios=[1, 1.25], height_ratios=[1, 0.16])
ax1 = fig.add_subplot(gs[0, 0])
ax2 = fig.add_subplot(gs[0, 1])
axl = fig.add_subplot(gs[1, :])
axl.axis("off")

xp = np.linspace(-3.5, 3.5, 1401)
for name, k, col, ls in KERNELS:
    ax1.plot(xp, k(xp), color=col, ls=ls, lw=1.4, label=name)
    h = k(x)
    h = h / (h.sum() * dx)
    H = np.abs(np.exp(-2j * np.pi * np.outer(f, x)) @ h) * dx
    ax2.plot(f, H, color=col, ls=ls, lw=1.4, label=name)

ax1.axhline(0, color=C["black"], lw=0.6)
ax1.set_xlim(-3.5, 3.5)
ax1.set_ylim(-0.25, 1.1)
ax1.set_xlabel("Offset [samples]")
ax1.set_ylabel("Kernel weight")
ax1.set_title("kernel h(x)")

ax2.axvspan(0, B / 2, color=C["green"], alpha=0.15, lw=0)
ax2.axvspan(1 - B / 2, 1 + B / 2, color=C["grey"], alpha=0.2, lw=0)
ax2.text(B / 4, 1.13, "signal", ha="center", fontsize=9)
ax2.text(1.0, 1.13, "first replica", ha="center", fontsize=9)
ax2.axvline(0.5, color=C["black"], lw=0.6, ls=":")
ax2.set_xlim(0, 1.5)
ax2.set_ylim(0, 1.22)
ax2.set_xlabel("Frequency f / f$_s$")
ax2.set_ylabel("|H(f)|")
ax2.set_title("magnitude response")
axl.legend(*ax1.get_legend_handles_labels(), loc="center", ncol=3, fontsize=9, frameon=False,
           handlelength=1.6, columnspacing=1.2)

figstyle.save(fig, "resampling_1")
