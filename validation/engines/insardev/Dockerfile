# InSAR.dev — independent geocode-first lineage (GMTSAR-derived, not ISCE-derived), so this engine
# provides a third opinion rather than a second one.
#
# insardev_pygmtsar drives GMTSAR BINARIES for SLC preprocessing, which is why this cannot run
# natively on Windows and is the heaviest of the three images.
FROM mambaorg/micromamba:1.5-jammy

ENV DEBIAN_FRONTEND=noninteractive
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
        gmt gmt-dcw gmt-gshhg libgmt6 libgmt-dev \
        csh autoconf make gcc g++ gfortran libtiff5-dev liblapack-dev libblas-dev libhdf5-dev \
        git wget ca-certificates \
        python3 python3-pip python3-dev \
 && rm -rf /var/lib/apt/lists/*

# GMTSAR from source: no apt/conda package provides the binaries insardev_pygmtsar shells out to.
ARG GMTSAR_REF=master
RUN git clone --depth 1 --branch ${GMTSAR_REF} https://github.com/gmtsar/gmtsar.git /usr/local/GMTSAR \
 && cd /usr/local/GMTSAR \
 && autoconf \
 && ./configure --with-orbits-dir=/tmp CFLAGS='-z muldefs' LDFLAGS='-z muldefs' \
 && make -j"$(nproc)" \
 && make install
ENV PATH="/usr/local/GMTSAR/bin:${PATH}"

USER $MAMBA_USER
RUN micromamba install -y -n base -c conda-forge \
        python=3.12 numpy scipy xarray dask pyyaml rasterio gdal h5py \
 && micromamba clean --all --yes

# zarr>=3 is what InSAR.dev is built on ("modern Zarr version 3"), and it requires Python >= 3.11 —
# which is why this image cannot use Ubuntu 22.04's system Python 3.10.
RUN micromamba run -n base python -m pip install --no-cache-dir \
        'zarr>=3' insardev-toolkit insardev-pygmtsar insardev

ENV MAMBA_DOCKERFILE_ACTIVATE=1

WORKDIR /work/insardev
