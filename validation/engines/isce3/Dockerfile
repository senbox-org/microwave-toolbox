# ISCE3 + COMPASS — algorithmic peer for GSLC (the OPERA CSLC-S1 producer).
# conda-forge builds isce3 for linux-64 / osx only, which is why this is containerised on Windows.
FROM mambaorg/micromamba:1.5-jammy

USER root
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates \
 && rm -rf /var/lib/apt/lists/*
USER $MAMBA_USER

# Pin so a report can cite an exact environment; bump deliberately, never implicitly.
ARG ISCE3_VERSION=0.25.7
RUN micromamba install -y -n base -c conda-forge \
        python=3.11 isce3=${ISCE3_VERSION} gdal h5py numpy scipy pyyaml shapely \
        libgdal-netcdf libgdal-hdf5 \
 && micromamba clean --all --yes

# s1-reader and COMPASS are installed --no-deps below, so that pip cannot re-resolve (and downgrade)
# the conda-provided isce3/GDAL stack. Their pure-Python runtime deps must therefore be named here.
# Kept as a SEPARATE layer so adding one does not invalidate the expensive conda solve above.
# COMPASS declares NO runtime requirements in its metadata (only docs extras), so this list was
# derived by scanning its source for imports and checking which are unresolvable — not guessed.
# RAiDER is imported only on the optional troposphere path and is deliberately omitted.
RUN micromamba install -y -n base -c conda-forge         lxml requests packaging pyproj pandas pillow yamale matplotlib-base scikit-image  && micromamba clean --all --yes
# NOTE: do NOT `pip install journal`. COMPASS's `journal` is the pyre framework's logging module,
# which ISCE3 already provides; the unrelated PyPI package of the same name shadows it and breaks
# runconfig loading with "module 'journal' has no attribute 'error'".

ARG COMPASS_REF=main
RUN git clone --depth 1 --branch ${COMPASS_REF} https://github.com/opera-adt/COMPASS.git /tmp/COMPASS \
 && micromamba run -n base python -m pip install --no-deps /tmp/COMPASS \
 && git clone --depth 1 https://github.com/opera-adt/s1-reader.git /tmp/s1-reader \
 && micromamba run -n base python -m pip install --no-deps /tmp/s1-reader

# --- patch one upstream ordering bug in COMPASS ------------------------------------------------
# geo_runconfig.py reads burst_database_file (optional per its own schema) and immediately calls
# os.path.isfile() on it, BEFORE the `if burst_database_file is None:` branch ~30 lines later. So a
# null raises "TypeError: stat: path should be string, not NoneType" and the None branch -- which
# calls generate_geogrids(bursts, geocoding_dict, dem_file) and is exactly the DEM-driven grid the
# harness wants -- is unreachable. Omitting the key does not help: COMPASS merges our runconfig over
# defaults/s1_cslc_geo.yaml, which supplies an empty value.
#
# The patch only adds the missing None guard; it changes no behaviour when a database IS supplied.
# Written in Python with an assert rather than sed so the build FAILS LOUDLY if upstream edits this
# line -- a silent no-op patch would leave us debugging an image that was never actually changed.
RUN micromamba run -n base python - <<'PY'
import io, os, compass
p = os.path.join(os.path.dirname(compass.__file__), "utils", "geo_runconfig.py")
s = io.open(p, encoding="utf-8").read()
old = "        if not os.path.isfile(burst_database_file):"
new = "        if burst_database_file is not None and not os.path.isfile(burst_database_file):"
assert s.count(old) == 1, f"PATCH TARGET NOT FOUND EXACTLY ONCE (count={s.count(old)}) in {p}"
io.open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
print(f"patched {p}: burst_database_file None guard added")
PY

# --- make COMPASS run under NumPy 2 ------------------------------------------------------------
# conda-forge resolves numpy 2.x for isce3 0.25.7, but COMPASS still calls `np.string_`, which NumPy
# 2.0 REMOVED. It dies at s1_geocode_slc.py writing HDF5 attributes -- after geocoding work is
# already done, so the failure is late and expensive.
#
# `np.string_` was only ever an ALIAS for `np.bytes_`, so this rename is exact and semantics-
# preserving. Chosen over pinning numpy<2 deliberately: downgrading would force a re-solve of the
# stack isce3 was compiled against, trading a mechanical rename for an ABI risk.
# Asserts both that occurrences were found AND that none survive, so the build cannot pass while
# silently changing nothing.
RUN micromamba run -n base python - <<'PY'
import io, os, re, compass
root = os.path.dirname(compass.__file__)
pat = re.compile(r"\bnp\.string_\b")
total = files = 0
for dirpath, _, names in os.walk(root):
    for n in names:
        if not n.endswith(".py"):
            continue
        p = os.path.join(dirpath, n)
        s = io.open(p, encoding="utf-8").read()
        c = len(pat.findall(s))
        if c:
            io.open(p, "w", encoding="utf-8").write(pat.sub("np.bytes_", s))
            total += c; files += 1
assert total > 0, "no np.string_ found -- COMPASS changed; re-check this patch"
left = sum(len(pat.findall(io.open(os.path.join(d, n), encoding="utf-8").read()))
           for d, _, ns in os.walk(root) for n in ns if n.endswith(".py"))
assert left == 0, f"{left} np.string_ occurrences survived the patch"
print(f"patched {total} np.string_ -> np.bytes_ across {files} file(s)")
PY

ENV MAMBA_DOCKERFILE_ACTIVATE=1
WORKDIR /work/isce3
