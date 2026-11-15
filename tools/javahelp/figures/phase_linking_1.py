"""phase_linking_1: synthetic coherence matrix and EVD phase linking versus a single-reference pair.

SYNTHETIC example (no real data). N = 40 Sentinel-1-like dates, 12 days apart.
True coherence model (positive semi-definite by construction, unit diagonal):
    Gamma_ij = rho * (w_exp * exp(-|dt|/tau) + w_seas * (1 + cos(2 pi dt / 365)) / 2 + w_inf) + (1 - rho) * delta_ij
True phase theta_n: linear LOS motion plus an annual term, relative to the median (reference) epoch.
L samples ("SHPs") are drawn from a zero-mean circular complex Gaussian with covariance
C = D Gamma D^H, D = diag(exp(j theta)); the sample covariance is normalised to T_hat and
(a) |Gamma| is shown, (b) the EVD estimate (dominant eigenvector of T_hat, as EVDEstimator.java)
and the single-reference pairwise phase arg(T_hat[k, ref]) are compared with the truth.
"""
import numpy as np

import figstyle

C = figstyle.COLORS
N = 40
L = 50                      # number of SHPs (>= max(shpMin=20, N) as the operator requires)
dt_days = 12.0
t = np.arange(N) * dt_days
ref = N // 2                # median epoch, as PhaseLinkingOp.resolveReferenceEpoch()

lam = 0.0555                # C-band wavelength (m)
v = -0.008                  # LOS velocity (m/yr)
a_seas = 0.003              # annual amplitude (m)
d = v * (t - t[ref]) / 365.25 + a_seas * (np.sin(2 * np.pi * t / 365.25) - np.sin(2 * np.pi * t[ref] / 365.25))
theta = -4 * np.pi / lam * d    # phase of s_n; arg(s_i s_j*) = theta_i - theta_j

rho, w_exp, w_seas, w_inf, tau = 0.95, 0.55, 0.30, 0.15, 36.0
DT = np.abs(t[:, None] - t[None, :])
gamma = rho * (w_exp * np.exp(-DT / tau) + w_seas * 0.5 * (1 + np.cos(2 * np.pi * DT / 365.25)) + w_inf)
gamma[np.diag_indices(N)] = 1.0
assert np.linalg.eigvalsh(gamma).min() > 0

Dm = np.diag(np.exp(1j * theta))
Ctrue = Dm @ gamma @ Dm.conj().T
Lc = np.linalg.cholesky(Ctrue)


def wrap(p):
    return np.angle(np.exp(1j * p))


def realise(rng):
    z = (rng.standard_normal((N, L)) + 1j * rng.standard_normal((N, L))) / np.sqrt(2)
    s = Lc @ z                                   # N x L samples
    Chat = s @ s.conj().T / L                    # C_hat[i,j] = mean s_i conj(s_j)
    dg = np.sqrt(np.real(np.diag(Chat)))
    T = Chat / np.outer(dg, dg)
    w, V = np.linalg.eigh(T)
    u = V[:, -1]                                 # dominant eigenvector
    th_evd = wrap(np.angle(u) - np.angle(u[ref]))
    th_pair = np.angle(T[:, ref])                # single-reference interferogram phase vs ref
    iu = np.triu_indices(N, 1)
    res = np.angle(T[iu]) - (th_evd[:, None] - th_evd[None, :])[iu]
    gT = np.abs(np.exp(1j * res).sum()) / res.size    # as TemporalCoherence.java
    return th_evd, th_pair, gT


rng = np.random.default_rng(3)
th_evd, th_pair, gT = realise(rng)

# Monte Carlo RMS error (for the caption)
err_e, err_p = [], []
for _ in range(500):
    e, p, _g = realise(rng)
    m = np.arange(N) != ref
    err_e.append(wrap(e - theta)[m])
    err_p.append(wrap(p - theta)[m])
rms_e = np.sqrt(np.mean(np.square(err_e)))
rms_p = np.sqrt(np.mean(np.square(err_p)))

fig, (ax1, ax2) = figstyle.plt.subplots(1, 2, figsize=(5.6, 2.9), dpi=figstyle.DPI,
                                        gridspec_kw={"width_ratios": [1.0, 1.25]})
im = ax1.imshow(gamma, cmap="viridis", vmin=0, vmax=1, origin="upper",
                extent=[-0.5, N - 0.5, N - 0.5, -0.5])
ax1.set_title("(a) true |coherence| (synthetic)", fontsize=10)
ax1.set_xlabel("Date index j")
ax1.set_ylabel("Date index i")
ax1.set_xticks([0, 13, 26, 39])
ax1.set_yticks([0, 13, 26, 39])
cb = fig.colorbar(im, ax=ax1, fraction=0.046, pad=0.04)
cb.ax.tick_params(labelsize=9)

ax2.plot(t, theta, color=C["black"], lw=1.6, label="true")
ax2.plot(t, th_pair, "s", ms=3.5, mfc="none", color=C["orange"], label="single pair to ref.")
ax2.plot(t, th_evd, "o", ms=3.5, color=C["blue"], label="EVD (all pairs)")
ax2.axvline(t[ref], color=C["grey"], lw=0.8, ls=":")
ax2.set_title("(b) phase, L = %d SHPs" % L, fontsize=10)
ax2.set_xlabel("Time (days)")
ax2.set_ylabel("Phase (rad)")
ax2.set_ylim(-np.pi, np.pi)
ax2.set_yticks([-np.pi, 0, np.pi])
ax2.set_yticklabels([r"$-\pi$", "0", r"$\pi$"])
ax2.legend(loc="upper left", frameon=False, fontsize=9, borderaxespad=0.1)
ax2.text(0.98, 0.03, "synthetic", transform=ax2.transAxes, ha="right", va="bottom", fontsize=9,
         color=C["grey"])

figstyle.save(fig, "phase_linking_1")
print("temporal coherence (shown realisation): %.2f" % gT)
print("RMS error over 500 realisations: EVD %.2f rad, single pair %.2f rad" % (rms_e, rms_p))
