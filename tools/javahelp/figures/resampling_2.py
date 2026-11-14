"""resampling_2: coherence between an interpolated complex signal and the true signal, as a function of where the
signal spectrum is centred (f_c), for SNAP's interpolation kernels.

Model: a complex signal with a flat spectrum of width B = 0.8 f_s (oversampling 1.25) centred at f_c is sampled at
f_s = 1 and interpolated at fractional positions mu, uniformly distributed in [0, 1). For position mu the discrete
weights w_k = h(mu - k) (normalised to sum 1, as SNAP does) give the transfer function
H_mu(f) = sum_k w_k exp(j 2 pi f (k - mu)).  Averaging over the signal band and over mu:
    gamma = |<H>| / sqrt(<|H|^2>)
This is the coherence loss caused by the interpolation of one image alone (Hanssen & Bamler 1999 use the same idea).
f_c = 0 is a deramped (baseband) signal; |f_c| up to f_s/2 is what a non-deramped TOPS burst or a squinted
stripmap SLC presents to the kernel.

Kernels as implemented: Keys cubic convolution a = -0.5 (snap-core CubicConvolutionResampling); 6-point cubic
convolution alpha = -0.5, beta = 0.5 (jlinda LUT.cc6, Warp default); BISINC_n = sinc * Hann(n - 1), |x| <= n // 2.
"""
import matplotlib.pyplot as plt
import numpy as np

import figstyle

C = figstyle.COLORS
B = 0.8


def keys(x, a=-0.5):
    ax = np.abs(x)
    y = np.zeros_like(x)
    m1 = ax < 1
    m2 = (ax >= 1) & (ax < 2)
    y[m1] = (a + 2) * ax[m1] ** 3 - (a + 3) * ax[m1] ** 2 + 1
    y[m2] = a * ax[m2] ** 3 - 5 * a * ax[m2] ** 2 + 8 * a * ax[m2] - 4 * a
    return y


def cc6(x, a=-0.5, b=0.5):
    ax = np.abs(x)
    y = np.zeros_like(x)
    m1 = ax < 1
    m2 = (ax >= 1) & (ax < 2)
    m3 = (ax >= 2) & (ax < 3)
    y[m1] = (a - b + 2) * ax[m1] ** 3 - (a - b + 3) * ax[m1] ** 2 + 1
    y[m2] = a * ax[m2] ** 3 - (5 * a - b) * ax[m2] ** 2 + (8 * a - 3 * b) * ax[m2] - (4 * a - 2 * b)
    y[m3] = b * ax[m3] ** 3 - 8 * b * ax[m3] ** 2 + 21 * b * ax[m3] - 18 * b
    return y


def bisinc(x, n):
    half = n // 2
    w = 0.5 * (1 + np.cos(2 * np.pi * x / (n - 1)))
    return np.where(np.abs(x) <= half, np.sinc(x) * w, 0.0)


KERNELS = [
    ("nearest neighbour", lambda x: np.where(np.abs(x) < 0.5, 1.0, 0.0), C["grey"], "-"),
    ("bilinear", lambda x: np.clip(1 - np.abs(x), 0, None), C["orange"], "-"),
    ("cubic convolution (4 pt)", keys, C["green"], "-"),
    ("cubic convolution 6 pt (Warp)", cc6, C["purple"], "-."),
    ("bisinc 5", lambda x: bisinc(x, 5), C["blue"], "-"),
    ("bisinc 21", lambda x: bisinc(x, 21), C["red"], "--"),
]

mus = (np.arange(64) + 0.5) / 64
taps = np.arange(-11, 13)
fcs = np.linspace(0, 0.5, 51)


def coherence(kernel, fc):
    f = fc + np.linspace(-B / 2, B / 2, 201)
    num = 0.0
    den = 0.0
    for mu in mus:
        w = kernel(mu - taps)
        w = w / w.sum()
        H = np.exp(2j * np.pi * np.outer(f, taps - mu)) @ w
        num += H.mean()
        den += (np.abs(H) ** 2).mean()
    return np.abs(num / len(mus)) / np.sqrt(den / len(mus))


fig, ax = figstyle.figure(5.6, 3.3)
for name, k, col, ls in KERNELS:
    g = np.array([coherence(k, fc) for fc in fcs])
    ax.plot(fcs, g, color=col, ls=ls, lw=1.5, label=name)
    print(f"{name:32s} fc=0: {g[0]:.4f}  fc=0.25: {g[25]:.4f}  fc=0.5: {g[-1]:.4f}")

ax.set_xlim(0, 0.5)
ax.set_ylim(0.4, 1.01)
ax.set_xlabel("Centre of the signal spectrum f$_c$ / f$_s$")
ax.set_ylabel("Coherence after interpolation")
ax.annotate("deramped data (baseband)", xy=(0.004, 0.997), xytext=(0.24, 0.965), fontsize=9,
            arrowprops=dict(arrowstyle="->", color=C["black"], lw=0.9))
ax.legend(loc="lower left", fontsize=9, frameon=False, ncol=1)
figstyle.save(fig, "resampling_2")
