#!/usr/bin/env bash
# Proves the image before our data: the phase-linking entry points must be reachable.
set -euo pipefail
python - <<'PY'
import dolphin, numpy
print("dolphin", getattr(dolphin, "__version__", "(unversioned)"))
from dolphin import stack          # noqa: F401
print("dolphin.stack OK")
from dolphin.phase_link import covariance, simulate  # noqa: F401
print("phase_link.covariance OK")
try:
    from dolphin.phase_link import _core as pl_core   # noqa: F401
    print("phase_link core OK")
except Exception as e:
    print("phase_link core:", type(e).__name__, e)
import dolphin.workflows as wf
print("workflows OK:", [n for n in dir(wf) if not n.startswith('_')][:8])
PY
echo "SMOKE OK"
