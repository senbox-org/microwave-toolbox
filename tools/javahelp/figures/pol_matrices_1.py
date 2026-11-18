"""pol_matrices_1: from the scattering matrix S to the C3 and T3 matrices, with SNAP band names.

Top: the scattering matrix (8 i/q bands in SNAP) and the two target vectors. SNAP forms the
cross-polar element from both measured cross-polar channels, S_X = (S_HV + S_VH) / 2
(QuadPolProcessor.computeCovarianceMatrixC3 / computeCoherencyMatrixT3), which equals S_HV
under reciprocity.
  k_L = [S_HH, sqrt(2) S_X, S_VV]^T
  k_P = (1/sqrt(2)) [S_HH + S_VV, S_HH - S_VV, 2 S_X]^T = U3 k_L
Bottom: the 3x3 matrices <k k^H>. Diagonal cells are real powers (one band each). Each upper
off-diagonal element is stored as two bands, <name>_real and <name>_imag; the lower triangle is
the complex conjugate and is not stored. T3 diagonal colours follow the SNAP Pauli RGB
(T22 red = double bounce, T33 green = volume, T11 blue = surface/odd bounce).

The script also checks numerically that T3 = U3 C3 U3^H with
  U3 = (1/sqrt(2)) [[1, 0, 1], [1, 0, -1], [0, sqrt(2), 0]]
and that both traces equal the span |S_HH|^2 + 2|S_X|^2 + |S_VV|^2.
"""
import numpy as np
from matplotlib.patches import FancyArrowPatch, Rectangle

import figstyle

C = figstyle.COLORS

# ---- numerical check of the relations shown in the figure ---------------------------------
rng = np.random.default_rng(0)
U3 = np.array([[1, 0, 1], [1, 0, -1], [0, np.sqrt(2), 0]]) / np.sqrt(2)
S = rng.normal(size=(500, 2, 2)) + 1j * rng.normal(size=(500, 2, 2))
sx = (S[:, 0, 1] + S[:, 1, 0]) / 2
kL = np.stack([S[:, 0, 0], np.sqrt(2) * sx, S[:, 1, 1]], axis=1)
kP = np.stack([S[:, 0, 0] + S[:, 1, 1], S[:, 0, 0] - S[:, 1, 1], 2 * sx], axis=1) / np.sqrt(2)
C3 = np.einsum("ni,nj->ij", kL, kL.conj()) / len(S)
T3 = np.einsum("ni,nj->ij", kP, kP.conj()) / len(S)
span = np.mean(abs(S[:, 0, 0]) ** 2 + 2 * abs(sx) ** 2 + abs(S[:, 1, 1]) ** 2)
assert np.allclose(U3 @ U3.conj().T, np.eye(3))
assert np.allclose(T3, U3 @ C3 @ U3.conj().T)
assert np.isclose(np.trace(C3).real, span) and np.isclose(np.trace(T3).real, span)
print("check passed: T3 = U3 C3 U3^H, tr C3 = tr T3 = span")

# ---- drawing --------------------------------------------------------------------------------
W, H = 5.6, 4.4
fig, ax = figstyle.figure(W, H)
ax.set_xlim(0, W)
ax.set_ylim(-0.25, H - 0.25)
ax.axis("off")


def box(x, y, w, h, fc="#F4F4F4", ec=C["grey"]):
    ax.add_patch(Rectangle((x, y), w, h, fc=fc, ec=ec, lw=1.0))


def arrow(p0, p1, text=None, tx=0.0, ty=0.0, two_way=False, **kw):
    style = "<|-|>" if two_way else "-|>"
    ax.add_patch(FancyArrowPatch(p0, p1, arrowstyle=style, mutation_scale=10,
                                 lw=1.2, color=C["black"]))
    if text:
        mx, my = (p0[0] + p1[0]) / 2 + tx, (p0[1] + p1[1]) / 2 + ty
        ax.text(mx, my, text, ha="center", va="center", fontsize=9, **kw)


# Scattering matrix
box(0.10, 3.20, 1.60, 0.92)
ax.text(0.90, 3.93, "Scattering matrix", ha="center", fontsize=9, weight="bold")
ax.text(0.90, 3.58, r"$S_{HH}\;\;S_{HV}$" + "\n" + r"$S_{VH}\;\;S_{VV}$",
        ha="center", va="center", fontsize=10, linespacing=1.3)
ax.text(0.90, 3.27, "i_/q_ bands, 8 in all", ha="center", fontsize=9, color="#444444")

# Target vectors
box(0.10, 2.62, 2.30, 0.42)
ax.text(0.18, 2.83, r"$k_L = [\,S_{HH},\ \sqrt{2}\,S_X,\ S_{VV}\,]^T$", va="center", fontsize=10)
box(2.05, 3.48, 3.45, 0.60)
ax.text(2.12, 3.78, r"$k_P = \frac{1}{\sqrt{2}}\,[\,S_{HH}+S_{VV},\ S_{HH}-S_{VV},\ 2S_X\,]^T$",
        va="center", fontsize=10)
ax.text(2.55, 3.20, r"with $S_X = (S_{HV}+S_{VH})/2$", ha="left", fontsize=9, color="#444444")
arrow((0.90, 3.18), (0.90, 3.06))
arrow((1.72, 3.75), (2.03, 3.75))

# Matrix grids
cell = 0.60
names_c = [["C11", "C12", "C13"], ["", "C22", "C23"], ["", "", "C33"]]
names_t = [["T11", "T12", "T13"], ["", "T22", "T23"], ["", "", "T33"]]
diag_c = [r"$\langle|S_{HH}|^2\rangle$", r"$2\langle|S_X|^2\rangle$", r"$\langle|S_{VV}|^2\rangle$"]
diag_t = ["surface", "double\nbounce", "volume"]
diag_t_col = ["#CCE3F0", "#F6D5C2", "#C8EBDF"]   # light tints of Okabe-Ito blue, red, green


def grid(x0, y_top, names, diag, diag_col, title):
    ax.text(x0 + 1.5 * cell, y_top + 0.12, title, ha="center", fontsize=9, weight="bold")
    for r in range(3):
        for c in range(3):
            x, y = x0 + c * cell, y_top - (r + 1) * cell
            if r == c:
                box(x, y, cell, cell, fc=diag_col[r])
                ax.text(x + cell / 2, y + cell * 0.70, names[r][c], ha="center", va="center",
                        fontsize=9, weight="bold")
                ax.text(x + cell / 2, y + cell * 0.32, diag[r], ha="center", va="center",
                        fontsize=9, linespacing=1.0)
            elif c > r:
                box(x, y, cell, cell, fc="white")
                ax.text(x + cell / 2, y + cell * 0.66, names[r][c], ha="center", va="center",
                        fontsize=9)
                ax.text(x + cell / 2, y + cell * 0.30, "re, im", ha="center", va="center",
                        fontsize=9, color="#444444")
            else:
                box(x, y, cell, cell, fc="#E6E6E6", ec="#BBBBBB")
                ax.text(x + cell / 2, y + cell / 2, "conj.", ha="center", va="center",
                        fontsize=9, color="#777777")


y_top = 2.20
xc, xt = 0.25, 3.45
grid(xc, y_top, names_c, diag_c, ["#EFEFEF"] * 3, "Covariance C3")
grid(xt, y_top, names_t, diag_t, diag_t_col, "Coherency T3")

arrow((xc + 1.5 * cell, 2.60), (xc + 1.5 * cell, y_top + 0.29), r"$\langle k_L k_L^H\rangle$",
      tx=0.65, ty=0.0)
arrow((xt + 1.5 * cell, 3.46), (xt + 1.5 * cell, y_top + 0.29), r"$\langle k_P k_P^H\rangle$",
      tx=0.55, ty=-0.25)
arrow((xc + 3 * cell + 0.08, y_top - 1.5 * cell), (xt - 0.08, y_top - 1.5 * cell),
      two_way=True)
ax.text((xc + 3 * cell + xt) / 2, y_top - 1.5 * cell + 0.22, r"$T_3 = U_3\,C_3\,U_3^H$",
        ha="center", fontsize=9.5)
ax.text((xc + 3 * cell + xt) / 2, y_top - 1.5 * cell - 0.24, "exact,\nper pixel",
        ha="center", va="center", fontsize=9, color="#444444", linespacing=1.1)
ax.text(W / 2, 0.08, "Each off-diagonal element is stored as two bands, e.g. C12_real and C12_imag",
        ha="center", fontsize=9, color="#444444")
ax.text(W / 2, -0.17, r"$\langle\,\rangle$ = average over looks or a window; trace of C3 = trace of T3 = span",
        ha="center", fontsize=9, color="#444444")

figstyle.save(fig, "pol_matrices_1")
