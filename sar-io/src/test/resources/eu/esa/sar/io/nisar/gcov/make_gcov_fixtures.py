"""Write tiny NISAR L2 GCOV fixtures for the GCOV reader tests (SNAP-4384).

The metadata (identification, orbit, attitude, processing information, a
subsampled radarGrid and calibrationInformation) is cloned from a real GCOV
product so the reader opens the fixtures exactly as it opens a real file.
The grids are replaced by small rasters with known values, built by the
formulas below. TestNisarGCOVReader recomputes the same formulas, so change
both together.

Usage (needs numpy and h5py):
    python -I make_gcov_fixtures.py <template GCOV .h5> <output dir>

Template used for the committed fixtures:
    NISAR_L2_PR_GCOV_036_066_A_004_2005_DHDH_A_20261121T181649_20261121T181653_P05036_N_P_J_001.h5
(an operational product from ASF; the fixture file names are kept from the earlier template)
"""
import os
import sys

import h5py
import numpy as np

ROWS, COLS = 6, 8
GRID_STEP = 50          # subsampling of the template's radarGrid / calibration cubes
COMPLEX64 = np.dtype([('r', '<f4'), ('i', '<f4')])
COMPLEX32 = np.dtype([('r', '<f2'), ('i', '<f2')])   # NISAR float16 complex


def diagonal(k):
    """Known real covariance value for term index k."""
    r, c = np.mgrid[0:ROWS, 0:COLS].astype(np.float32)
    return (0.1 * (k + 1) + 0.01 * r + 0.001 * c).astype(np.float32)


def off_diagonal(k):
    """Known complex covariance value for term index k; real and imaginary differ in sign and slope."""
    r, c = np.mgrid[0:ROWS, 0:COLS].astype(np.float32)
    return (0.05 * (k + 1) + 0.002 * r).astype(np.float32), (-0.03 * (k + 1) - 0.004 * c).astype(np.float32)


def copy_subsampled(src, dst):
    """Copy a group tree, striding any array with a dimension larger than GRID_STEP."""
    for name, obj in src.attrs.items():
        dst.attrs[name] = obj
    for name, obj in src.items():
        if isinstance(obj, h5py.Group):
            copy_subsampled(obj, dst.create_group(name))
            continue
        data = obj[()]
        if isinstance(data, np.ndarray) and data.ndim >= 1 and max(data.shape) > GRID_STEP:
            data = data[tuple(slice(None, None, GRID_STEP) if n > GRID_STEP else slice(None) for n in data.shape)]
        out = dst.create_dataset(name, data=data)
        for a, v in obj.attrs.items():
            if a not in ('DIMENSION_LIST', 'REFERENCE_LIST', 'CLASS', 'NAME', '_Netcdf4Dimid'):
                out.attrs[a] = v


def write_term(grid, name, data, dtype=None):
    if isinstance(data, tuple):
        arr = np.empty((ROWS, COLS), dtype=dtype or COMPLEX64)
        arr['r'], arr['i'] = data
    else:
        arr = data
    ds = grid.create_dataset(name, data=arr)
    ds.attrs['_FillValue'] = np.float32(np.nan)
    return ds


def make(template, out_path, band, terms, pols, float16_terms=()):
    """terms: ordered list of covariance term names; diagonal terms are those whose halves match."""
    with h5py.File(template, 'r') as src, h5py.File(out_path, 'w') as dst:
        for a, v in src.attrs.items():
            dst.attrs[a] = v
        s_src = src['science/LSAR']
        s_dst = dst.create_group('science').create_group(band)
        copy_subsampled(s_src['identification'], s_dst.create_group('identification'))
        g_dst = s_dst.create_group('GCOV')
        copy_subsampled(s_src['GCOV/metadata'], g_dst.create_group('metadata'))

        src_grid = s_src['GCOV/grids/frequencyA']
        grid = g_dst.create_group('grids').create_group('frequencyA')
        # scalar grid parameters (spacings, bandwidths, projection, flags); the set varies between product versions
        scalars = [n for n, d in src_grid.items() if isinstance(d, h5py.Dataset) and d.ndim == 0]
        for name in scalars:
            ds = grid.create_dataset(name, data=src_grid[name][()])
            for a, v in src_grid[name].attrs.items():
                if a not in ('DIMENSION_LIST', 'REFERENCE_LIST', 'CLASS', 'NAME', '_Netcdf4Dimid'):
                    ds.attrs[a] = v
        for name, n in (('xCoordinates', COLS), ('yCoordinates', ROWS)):
            ds = grid.create_dataset(name, data=src_grid[name][:n])
            for a, v in src_grid[name].attrs.items():
                if a not in ('DIMENSION_LIST', 'REFERENCE_LIST', 'CLASS', 'NAME', '_Netcdf4Dimid'):
                    ds.attrs[a] = v

        grid.create_dataset('listOfPolarizations', data=np.array([p.encode() for p in pols]))
        grid.create_dataset('listOfCovarianceTerms', data=np.array([t.encode() for t in terms]))

        for k, term in enumerate(terms):
            if term[:2] == term[2:]:
                data = diagonal(k)
                data[0, 0] = np.nan                       # fill pixel
                write_term(grid, term, data)
            else:
                re, im = off_diagonal(k)
                re[0, 0] = im[0, 0] = np.nan
                write_term(grid, term, (re, im), COMPLEX32 if term in float16_terms else COMPLEX64)

        mask = np.ones((ROWS, COLS), dtype=np.uint8)
        mask[0, 0] = 255                                  # fill
        mask[1, :] = 0                                    # invalid (e.g. transmit gap)
        grid.create_dataset('mask', data=mask)
        r, c = np.mgrid[0:ROWS, 0:COLS].astype(np.float32)
        grid.create_dataset('numberOfLooks', data=(10 + r + c).astype(np.float32))
        grid.create_dataset('rtcGammaToSigmaFactor', data=(0.5 + 0.01 * c).astype(np.float32))


def main():
    template, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    prefix = 'NISAR_L2_PR_GCOV_001_005_A_219_4020_'
    suffix = '_A_20081012T060910_20081012T060926_P01101_F_N_J_001.h5'
    make(template, os.path.join(out_dir, prefix + 'QPQP' + suffix), 'LSAR',
         ['HHHH', 'HVHV', 'VVVV', 'HHHV', 'HHVV', 'HVVV'], ['HH', 'HV', 'VV'])
    # Operational quad-pol products are not cross-pol symmetrised: all ten terms.
    make(template, os.path.join(out_dir, prefix + 'QPDH' + suffix), 'LSAR',
         ['HHHH', 'HHHV', 'HHVH', 'HHVV', 'HVHV', 'HVVH', 'HVVV', 'VHVH', 'VHVV', 'VVVV'],
         ['HH', 'HV', 'VH', 'VV'])
    make(template, os.path.join(out_dir, prefix + 'DHDH' + suffix), 'LSAR',
         ['HHHH', 'HVHV', 'HHHV'], ['HH', 'HV'], float16_terms=('HHHV',))
    make(template, os.path.join(out_dir, prefix + 'CPCP' + suffix), 'SSAR',
         ['RHRH', 'RVRV', 'RHRV'], ['RH', 'RV'])
    # VV/VH dual-pol lists the cross term as VHVV, so C12 = <S_VV S_VH*> is its conjugate.
    make(template, os.path.join(out_dir, prefix + 'DVDV' + suffix), 'LSAR',
         ['VVVV', 'VHVH', 'VHVV'], ['VV', 'VH'])
    make(template, os.path.join(out_dir, prefix + 'CLCL' + suffix), 'SSAR',
         ['LHLH', 'LVLV', 'LHLV'], ['LH', 'LV'])
    # Quad-pol with VH but no HV: S_X is S_VH.
    make(template, os.path.join(out_dir, prefix + 'QPVH' + suffix), 'LSAR',
         ['HHHH', 'HHVH', 'HHVV', 'VHVH', 'VHVV', 'VVVV'], ['HH', 'VH', 'VV'])
    # Quad-pol missing HVVH: C22 cannot be formed, so no matrix at all.
    make(template, os.path.join(out_dir, prefix + 'QPNX' + suffix), 'LSAR',
         ['HHHH', 'HHHV', 'HHVH', 'HHVV', 'HVHV', 'HVVV', 'VHVH', 'VHVV', 'VVVV'], ['HH', 'HV', 'VH', 'VV'])


if __name__ == '__main__':
    main()
