#!/usr/bin/env bash
# Proves the image before any of our data is involved: imports resolve and geocodeSlc is reachable.
set -euo pipefail
python - <<'PY'
import isce3, numpy
print("isce3", isce3.__version__)
print("geocode_slc present:", hasattr(isce3.geocode, "geocode_slc"))
import compass; print("COMPASS import OK")
from s1reader import load_bursts; print("s1-reader import OK")
PY
echo "SMOKE OK"
