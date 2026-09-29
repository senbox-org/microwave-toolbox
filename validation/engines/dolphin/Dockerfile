# Dolphin — phase-linking peer for PhaseLinkingOp (the OPERA DISP-S1 engine).
# Consumes a coregistered SLC stack, so a CSLC-compatible GSLC stack can be fed to it directly.
FROM mambaorg/micromamba:1.5-jammy

USER root
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates \
 && rm -rf /var/lib/apt/lists/*
USER $MAMBA_USER

# Pinned so a report can name the exact environment.
ARG DOLPHIN_VERSION=0.35.0
RUN micromamba install -y -n base -c conda-forge \
        python=3.11 dolphin=${DOLPHIN_VERSION} gdal h5py numpy scipy pyyaml \
 && micromamba clean --all --yes

ENV MAMBA_DOCKERFILE_ACTIVATE=1
WORKDIR /work/dolphin
