#!/usr/bin/env bash
# ISCE3/COMPASS engine adapter. Invoked as: run.sh /cases/<case>.yml
#
# Contract with the harness: consume a case YAML, write georeferenced output under
# /work/isce3/<case>/, and report which datasets were produced. Nothing here knows about the
# comparison — the host-side comparator reads whatever lands in /work.
set -euo pipefail

CASE="${1:?usage: run.sh /cases/<case>.yml}"
NAME="$(basename "$CASE" .yml)"
OUT="/work/isce3/${NAME}"
mkdir -p "$OUT"
LOG="${OUT}/engine.log"

{
  echo "=== isce3 engine adapter ==="
  date -u +"start %Y-%m-%dT%H:%M:%SZ"
  # Version provenance: a report must be able to name the exact environment it came from.
  python -c "import isce3;print('isce3',isce3.__version__)"
  python -c "import compass,os;print('compass',getattr(compass,'__version__','(unversioned)'),os.path.dirname(compass.__file__))"

  # Does the case have a secondary? If so BOTH dates are geocoded, each into its own subdir, and an
  # interferogram is formed. Roles go through identical translation and mosaic code -- an asymmetry
  # between reference and secondary would appear as interferometric phase.
  HAS_SEC=$(CASE="$CASE" python -c "
import os, yaml
c = yaml.safe_load(open(os.environ['CASE']))
print('1' if ((c.get('secondary') or {}).get('slc')) else '0')
")
  NEED_CARRIER=$(CASE="$CASE" python -c "
import os, yaml
c = yaml.safe_load(open(os.environ['CASE']))
print('1' if ((c.get('engine_params') or {}).get('isce3') or {}).get('analytic_carrier_removal') else '0')
")
  POL=$(CASE="$CASE" python -c "
import os, yaml
c = yaml.safe_load(open(os.environ['CASE']))
print(((c.get('scene') or {}).get('polarisation') or 'VV'))
")
  ROLES="ref"
  [ "$HAS_SEC" = "1" ] && ROLES="ref sec"
  echo "roles to process: $ROLES   carrier_removal=$NEED_CARRIER   pol=$POL"

  shopt -s nullglob
  for ROLE in $ROLES; do
    RDIR="${OUT}/${ROLE}"
    mkdir -p "$RDIR"
    echo "=================== role ${ROLE} ==================="
    python /engines/isce3/make_runconfig.py "$CASE" --out "$RDIR" --role "$ROLE"
    python -m compass.s1_geocode_slc "${RDIR}/runconfig.yaml"

    # COMPASS writes CSLC products NESTED: product/<burst_id>/<date>/<burst>_<date>.h5. A flat
    # `product/*.h5` glob therefore finds only the top-level sas_output_file entry -- which is a
    # DIRECTORY here, producing "IsADirectoryError: Unable to synchronously open file". Use a
    # recursive, FILE-ONLY search so every burst is picked up and nothing that is not a file is.
    mapfile -t PRODUCED < <(find "${RDIR}/product" -type f -name '*.h5' ! -name '*_carrierfree.h5' | sort)
    [ ${#PRODUCED[@]} -gt 0 ] || { echo "role ${ROLE}: NO CSLC PRODUCED"; exit 6; }
    echo "role ${ROLE}: ${#PRODUCED[@]} CSLC file(s)"

    if [ "$NEED_CARRIER" = "1" ]; then
      echo "--- role ${ROLE}: removing azimuth carrier analytically ---"
      for h5 in "${PRODUCED[@]}"; do python /engines/isce3/remove_carrier.py "$h5"; done
    fi

    echo "--- role ${ROLE}: mosaicking bursts onto one lattice ---"
    mapfile -t CF < <(find "${RDIR}/product" -type f -name '*_carrierfree.h5' | sort)
    if [ ${#CF[@]} -gt 0 ]; then
      echo "  using ${#CF[@]} carrier-free file(s)"
      python /engines/isce3/mosaic_bursts.py "${CF[@]}" --out "${RDIR}/mosaic.tif" --pol "$POL"
    else
      echo "  using ${#PRODUCED[@]} raw CSLC file(s) (carrier still present)"
      python /engines/isce3/mosaic_bursts.py "${PRODUCED[@]}" --out "${RDIR}/mosaic.tif" --pol "$POL"
    fi
  done

  if [ "$HAS_SEC" = "1" ]; then
    echo "--- forming the interferogram from the two mosaics ---"
    python /engines/isce3/form_ifg.py "${OUT}/ref/mosaic.tif" "${OUT}/sec/mosaic.tif"         --out "${OUT}/ifg.tif"
  fi

  date -u +"end %Y-%m-%dT%H:%M:%SZ"
} 2>&1 | tee "$LOG"

echo "isce3 adapter: outputs and log under ${OUT}"
