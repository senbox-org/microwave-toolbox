"""unwrapping_1: 1-D illustration of phase wrapping and of an unwrapping error.
Top: true (absolute) phase and the wrapped phase the interferogram records, in (-pi, pi].
Bottom: a correct unwrapped profile and one in which a noisy patch made the unwrapper
pick the wrong integer cycle, leaving a 2*pi step over the rest of the profile."""
import numpy as np

import figstyle

C = figstyle.COLORS
x = np.linspace(0, 100, 1001)
true = 0.22 * x + 4.0 * np.sin(2 * np.pi * x / 70.0) - 6.0     # radians
wrapped = np.angle(np.exp(1j * true))                          # (-pi, pi]

fig, (a1, a2) = figstyle.figure(5.6, 4.2, nrows=2, sharex=True)
a1.plot(x, true, color=C["blue"], lw=1.6, label="true phase")
a1.plot(x, wrapped, color=C["orange"], lw=1.2, label="wrapped phase")
a1.axhline(np.pi, color=C["grey"], lw=0.6, ls=":")
a1.axhline(-np.pi, color=C["grey"], lw=0.6, ls=":")
a1.set_ylabel("phase (rad)")
a1.legend(loc="upper left", frameon=False)
a1.text(101, np.pi, "+π", va="center", fontsize=9, color=C["grey"])
a1.text(101, -np.pi, "−π", va="center", fontsize=9, color=C["grey"])

wrong = true.copy()
wrong[x > 58] -= 2 * np.pi
a2.plot(x, true, color=C["blue"], lw=1.6, label="correct unwrapping")
a2.plot(x, wrong, color=C["red"], lw=1.4, ls="--", label="unwrapping error")
a2.axvspan(55, 61, color="#EEEEEE", lw=0)
a2.annotate("2π jump at a\nlow-coherence patch", xy=(58, true[580] - np.pi), xytext=(66, -6.5),
            fontsize=9, arrowprops=dict(arrowstyle="->", color=C["black"], lw=0.8))
a2.set_ylabel("phase (rad)")
a2.set_xlabel("pixel along a profile")
a2.legend(loc="upper left", frameon=False)
a2.set_xlim(0, 100)
figstyle.save(fig, "unwrapping_1")
