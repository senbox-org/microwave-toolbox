"""interferogram_formation_2: what the Goldstein filter does to coherent and decorrelated phase.

Simulation: a 192 x 192 multilooked interferogram (4 looks) of a smooth deformation bowl plus a
linear ramp. True coherence is 0.7 everywhere except a decorrelated patch (coherence 0, e.g. a
lake) on the right. Coherence is estimated from the simulated data with a 7 x 7 window, as an
Interferogram coh band would be.

The filter mirrors SNAP's GoldsteinPhaseFiltering (GoldsteinFilterOp): 32 x 32 FFT blocks
stepped by a quarter block, amplitude spectrum |Z| smoothed with a 3 x 3 box, weight
H = (smoothed |Z|)^alpha, triangular blending of the overlapping blocks. Adaptive alpha follows
the operator default: alpha = clamp(1 - mean block coherence, [0.2, 1.0]) (Baran et al. 2003).

Panels: (a) input phase, (b) after adaptive Goldstein filtering, (c) the local filter exponent
alpha, here evaluated as clamp(1 - mean coherence over a 32 x 32 neighbourhood) for display.

Printed diagnostics (seed 7): phase error std in the coherent area falls from about 0.49 to
0.18 rad. Inside the decorrelated patch the mean |phase gradient| stays at about pi/2 (pure
noise): the filter does not invent signal there, but within about 8 px of the patch edge the
filtered phase follows the neighbouring fringes (phase concentration about the true phase rises
from 0.04 to about 0.2 with adaptive alpha and about 0.3 with alpha = 1).
"""
import numpy as np
from scipy.ndimage import uniform_filter

import figstyle

RNG = np.random.default_rng(7)
N = 192
LOOKS = 4
FFT = 32
STEP = FFT // 4

y, x = np.mgrid[0:N, 0:N].astype(float)
phi_true = 2 * np.pi * (3.2 * np.exp(-((x - 80) ** 2 + (y - 96) ** 2) / (2 * 38.0 ** 2)) + x / 48.0)
gamma = np.full((N, N), 0.7)
lake = (x > 112) & (x < 186) & (y > 24) & (y < 168)
gamma[lake] = 0.0


def simulate():
    """Return a LOOKS-look complex interferogram and the per-look products for coherence."""
    ifg = np.zeros((N, N), complex)
    p1 = np.zeros((N, N))
    p2 = np.zeros((N, N))
    for _ in range(LOOKS):
        a = (RNG.standard_normal((N, N)) + 1j * RNG.standard_normal((N, N))) / np.sqrt(2)
        b = (RNG.standard_normal((N, N)) + 1j * RNG.standard_normal((N, N))) / np.sqrt(2)
        s1 = a
        s2 = (gamma * a + np.sqrt(1 - gamma ** 2) * b) * np.exp(-1j * phi_true)
        ifg += s1 * np.conj(s2)
        p1 += np.abs(s1) ** 2
        p2 += np.abs(s2) ** 2
    return ifg / LOOKS, p1 / LOOKS, p2 / LOOKS


def box(a, w):
    k = np.ones(w) / w
    a = np.apply_along_axis(lambda r: np.convolve(r, k, mode="same"), 1, a)
    return np.apply_along_axis(lambda c: np.convolve(c, k, mode="same"), 0, a)


def goldstein(ifg, coh, adaptive=True, alpha_fixed=1.0, amin=0.2, amax=1.0):
    out = np.zeros_like(ifg)
    wsum = np.zeros(ifg.shape)
    tri = 1 - np.abs(np.arange(FFT) - FFT / 2 + 0.5) / (FFT / 2)
    w2 = np.outer(tri, tri)
    origins = list(range(0, N - FFT + 1, STEP))
    if origins[-1] != N - FFT:
        origins.append(N - FFT)
    for r0 in origins:
        for c0 in origins:
            blk = ifg[r0:r0 + FFT, c0:c0 + FFT]
            Z = np.fft.fft2(blk)
            S = box(np.abs(Z), 3)
            if adaptive:
                alpha = float(np.clip(1 - coh[r0:r0 + FFT, c0:c0 + FFT].mean(), amin, amax))
            else:
                alpha = alpha_fixed
            out[r0:r0 + FFT, c0:c0 + FFT] += np.fft.ifft2(Z * S ** alpha) * w2
            wsum[r0:r0 + FFT, c0:c0 + FFT] += w2
    return out / np.maximum(wsum, 1e-12)


ifg, p1, p2 = simulate()
num = box(ifg, 7)
coh = np.abs(num) / np.sqrt(box(p1, 7) * box(p2, 7))
flt = goldstein(ifg, coh)
alpha_map = np.clip(1 - uniform_filter(coh, FFT, mode="nearest"), 0.2, 1.0)

print(f"mean estimated coherence: coherent area {coh[~lake].mean():.2f}, lake {coh[lake].mean():.2f}")
err_in = np.angle(ifg * np.exp(-1j * phi_true))[~lake].std()
err_f = np.angle(flt * np.exp(-1j * phi_true))[~lake].std()
print(f"phase error std outside the lake: input {err_in:.2f} rad, filtered {err_f:.2f} rad")
core = np.zeros_like(lake)
core[50:140, 140:175] = True


def mean_grad(p):
    return np.abs(np.angle(p[:, 1:] * np.conj(p[:, :-1])))[core[:, 1:]].mean()


print(f"lake core mean |phase gradient|: input {mean_grad(ifg):.2f}, filtered {mean_grad(flt):.2f} rad/px")
flt1 = goldstein(ifg, coh, adaptive=False, alpha_fixed=1.0)
edge = lake & (x <= 120)
for name, arr in (("input", ifg), ("adaptive", flt), ("alpha=1", flt1)):
    conc = abs(np.mean(np.exp(1j * (np.angle(arr[edge]) - phi_true[edge]))))
    print(f"phase concentration about the truth, first 8 px inside the patch, {name}: {conc:.2f}")

fig = figstyle.plt.figure(figsize=(5.6, 1.95), dpi=figstyle.DPI)
w = 0.255
h = w * 5.6 / 1.95  # square image axes
axes = [fig.add_axes([0.01 + k * (w + 0.065), 0.04, w, h]) for k in range(3)]
cax_a = fig.add_axes([0.01 + 3 * w + 2 * 0.065 + 0.012, 0.04, 0.014, h])
for ax, img, t in zip(axes[:2], (ifg, flt), ("(a) input phase", "(b) Goldstein output")):
    im = ax.imshow(np.angle(img), cmap="twilight", vmin=-np.pi, vmax=np.pi, interpolation="nearest")
    ax.contour(lake.astype(float), levels=[0.5], colors="white", linewidths=0.9)
    ax.set_title(t, fontsize=9.5)
im_a = axes[2].imshow(alpha_map, cmap="viridis", vmin=0, vmax=1, interpolation="nearest")
axes[2].contour(lake.astype(float), levels=[0.5], colors="white", linewidths=0.9)
axes[2].set_title("(c) adaptive α", fontsize=9.5)
for ax in axes:
    ax.set_xticks([])
    ax.set_yticks([])
    for sp in ax.spines.values():
        sp.set_visible(True)
cb = fig.colorbar(im_a, cax=cax_a, ticks=[0, 0.5, 1])
cb.ax.tick_params(labelsize=9)
fig.tight_layout = lambda *a, **k: None  # axes are placed by hand
figstyle.save(fig, "interferogram_formation_2")
