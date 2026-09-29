#!/usr/bin/env bash
# Proves both halves: the Python packages import AND the GMTSAR binaries they shell out to exist.
set -euo pipefail
echo "--- GMTSAR binaries ---"
for b in esarp xcorr phasediff make_s1a_tops; do
  if command -v "$b" >/dev/null 2>&1; then echo "  ok   $b -> $(command -v $b)"; else echo "  MISS $b"; fi
done
echo "--- python packages ---"
python - <<'PY'
import insardev_toolkit, insardev_pygmtsar
print("insardev_toolkit", getattr(insardev_toolkit, "__version__", "(unversioned)"))
print("insardev_pygmtsar", getattr(insardev_pygmtsar, "__version__", "(unversioned)"))
import sys
# insardev_pygmtsar is REQUIRED (the geocode-first half, which is what Phase 1e compares).
# insardev (core analysis) is required for the interferometry/time-series phases. Both must import,
# and a failure must be fatal — an earlier version of this script caught the error and still printed
# SMOKE OK, which is a green light for an unusable engine.
try:
    import insardev
    print("insardev", getattr(insardev, "__version__", "(unversioned)"))
except Exception as e:
    print(f"FAIL insardev: {type(e).__name__}: {e}", file=sys.stderr)
    sys.exit(1)
PY
echo "SMOKE OK"
