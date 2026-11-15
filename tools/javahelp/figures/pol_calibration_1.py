"""pol_calibration_1: one-way Faraday rotation angle against radar frequency for three TEC levels.

Faraday rotation (one-way, SI units; Freeman 2004, Wright et al. 2003):
    Omega = K * B_par * TEC_s / f^2,   K = e^3 / (8 pi^2 eps0 m_e^2 c) = 2.365e4 A m^2 kg^-1
    B_par : geomagnetic field component along the propagation path (T), 40 uT assumed here
    TEC_s : slant total electron content (electrons/m^2), 1 TECU = 1e16 el/m^2
    f     : radar frequency (Hz)

The script also prints the numbers quoted on the concept page:
  * the Faraday angle table (P, L, S, C, X band; 5/20/50 TECU),
  * a Monte-Carlo example of what Faraday rotation and cross-talk do to the measured HV channel
    of a reflection-symmetric distributed target, and what they do to the HV-VH coherence that
    Cross-Channel SNR Correction uses.
"""
import numpy as np

import figstyle

C = figstyle.COLORS
e, eps0, me, c = 1.602176634e-19, 8.8541878128e-12, 9.1093837015e-31, 299792458.0
K = e ** 3 / (8 * np.pi ** 2 * eps0 * me ** 2 * c)   # 2.365e4
B_PAR = 40e-6                                        # T
TECU = 1e16

bands = [("P", 0.435e9), ("L", 1.2575e9), ("S", 3.2e9), ("C", 5.405e9), ("X", 9.65e9)]
tecs = [(5, C["sky"]), (20, C["blue"]), (50, C["red"])]


def omega_deg(f_hz, tec_u):
    return np.rad2deg(K * B_PAR * tec_u * TECU / f_hz ** 2)


# ---- numbers for the page -------------------------------------------------------------
print(f"K = {K:.4e} A m^2/kg, B_par = {B_PAR * 1e6:.0f} uT")
print("Band  f(GHz)   " + "  ".join(f"{t:>3d} TECU" for t, _ in tecs))
for name, fb in bands:
    print(f"{name:4s} {fb / 1e9:7.4f}  " + "  ".join(f"{omega_deg(fb, t):8.2f}" for t, _ in tecs))

rng = np.random.default_rng(1)
N = 400000


def cgauss(p):
    return np.sqrt(p / 2) * (rng.standard_normal(N) + 1j * rng.standard_normal(N))


def target(hv_db, rho=0.5):
    hh = cgauss(1.0)
    vv = rho * hh + np.sqrt(1 - rho ** 2) * cgauss(1.0)
    hv = cgauss(10 ** (hv_db / 10))
    return hh, hv, vv


def coh(a, b):
    return abs(np.mean(a * np.conj(b))) / np.sqrt(np.mean(abs(a) ** 2) * np.mean(abs(b) ** 2))


print("\nFaraday rotation, target HH=VV=1, HH-VV corr 0.5, HV/HH = -15 dB:")
hh, hv, vv = target(-15.0)
S = np.array([[hh, hv], [hv, vv]])
for om in (0, 2, 5, 10):
    o = np.deg2rad(om)
    R = np.array([[np.cos(o), np.sin(o)], [-np.sin(o), np.cos(o)]])
    M = np.einsum("ij,jkn,kl->iln", R, S, R)
    dhv = 10 * np.log10(np.mean(abs(M[0, 1]) ** 2) / np.mean(abs(hv) ** 2))
    print(f"  Omega {om:2d} deg: HV power {dhv:+.2f} dB, |coh(HV,VH)| = {coh(M[0, 1], M[1, 0]):.2f}")

print("\nCross-talk (all four |delta| equal, 200 random phase draws), same target:")
for d_db in (-30, -25):
    r = []
    for _ in range(200):
        d = 10 ** (d_db / 20) * np.exp(1j * rng.uniform(0, 2 * np.pi, 4))
        Rx = np.array([[1, d[0]], [d[1], 1]])
        Tx = np.array([[1, d[2]], [d[3], 1]])
        M = np.einsum("ij,jkn,kl->iln", Rx, S, Tx)
        r.append(10 * np.log10(np.mean(abs(M[0, 1]) ** 2) / np.mean(abs(hv) ** 2)))
    print(f"  delta {d_db} dB: HV power +{min(r):.2f} to +{max(r):.2f} dB (median +{np.median(r):.2f})")

print("\nAdditive noise, uncorrelated between HV and VH:")
for snr_db in (10, 3, 0):
    p = 10 ** (-15 / 10)
    n = p / 10 ** (snr_db / 10)
    a, b = hv + cgauss(n), hv + cgauss(n)
    g = coh(a, b)
    true_p, meas_p = np.mean(abs(hv) ** 2), np.mean(abs(a) ** 2)
    print(f"  SNR {snr_db:2d} dB: gamma = {g:.2f}, measured/true power {10 * np.log10(meas_p / true_p):+.2f} dB,"
          f" gamma*P/true {10 * np.log10(g * meas_p / true_p):+.2f} dB,"
          f" gamma^2*P/true {10 * np.log10(g ** 2 * meas_p / true_p):+.2f} dB")

# ---- figure -------------------------------------------------------------------------------
f = np.logspace(np.log10(0.35e9), np.log10(12e9), 300)
fig, ax = figstyle.figure(5.6, 3.3)
for tec, col in tecs:
    ax.loglog(f / 1e9, omega_deg(f, tec), color=col, lw=2, label=f"{tec} TECU")
ax.axhline(5, color=C["grey"], lw=1, ls="--")
ax.text(8.9, 5 * 1.12, "5°", ha="right", va="bottom", fontsize=9, color="#444444")
ax.axhline(45, color=C["black"], lw=1, ls="--")
ax.text(10.8, 45 * 1.12, "45° estimator limit", ha="right", va="bottom", fontsize=9)
for name, fb in bands:
    ax.axvline(fb / 1e9, color=C["grey"], lw=0.6, ls=":")
    ax.text(fb / 1e9, 300, name, ha="center", va="bottom", fontsize=9)
ax.set_xlim(0.35, 12)
ax.set_ylim(0.01, 300)
ax.set_xticks([0.5, 1, 2, 5, 10])
ax.set_xticklabels(["0.5", "1", "2", "5", "10"])
ax.set_yticks([0.01, 0.1, 1, 10, 100])
ax.set_yticklabels(["0.01", "0.1", "1", "10", "100"])
ax.minorticks_off()
ax.set_xlabel("Radar frequency (GHz)")
ax.set_ylabel("One-way Faraday rotation Ω (deg)")
ax.legend(loc="lower left", frameon=False, title="slant TEC", title_fontsize=9)
figstyle.save(fig, "pol_calibration_1")
