#!/usr/bin/env python3
"""Build small synthetic ICEYE AML/CPX GeoTIFF fixtures.

These are NOT carved from a real delivery - unlike the Open Data fixtures next
door, no AML or CPX product was available. They are built from scratch to the
shape IceyeAMLCPXProductReader expects: a GeoTIFF whose GDAL metadata tag
carries a METADATA_JSON item holding the nested data/collection document, plus a
ModelTransformation tag (34264) to geocode from.

They exist to pin the raster orientation, which is what a synthetic fixture can
honestly establish: the rasters are deliberately non-square (96 azimuth columns
x 48 range rows) and every sample encodes its own position, so a missing or
mirrored transpose is unmissable. They say nothing about whether the schema
mapping matches what ICEYE really ships - only a real product can settle that.

Usage:
    python make_amlcpx_fixtures.py [<output-dir>]
"""

import json
import os
import struct
import sys

# TIFF columns are azimuth, rows are range - the transpose of SNAP's raster.
AZIMUTH_COLUMNS = 96
RANGE_ROWS = 48

# lon = a*column + b*row + d, lat = e*column + f*row + h
GEO_A, GEO_B, GEO_D = 1.0e-4, 2.0e-4, -77.0
GEO_E, GEO_F, GEO_H = 3.0e-4, -1.0e-4, -9.5

T_IMAGE_WIDTH = 256
T_IMAGE_LENGTH = 257
T_BITS_PER_SAMPLE = 258
T_COMPRESSION = 259
T_PHOTOMETRIC = 262
T_STRIP_OFFSETS = 273
T_SAMPLES_PER_PIXEL = 277
T_ROWS_PER_STRIP = 278
T_STRIP_BYTE_COUNTS = 279
T_PLANAR_CONFIG = 284
T_SAMPLE_FORMAT = 339
T_GEO_KEY_DIRECTORY = 34735
T_MODEL_TRANSFORMATION = 34264
T_GDAL_METADATA = 42112

TYPE_SHORT, TYPE_LONG, TYPE_ASCII, TYPE_DOUBLE = 3, 4, 2, 12


def escape(text):
    """ICEYE escapes the embedded JSON twice, so the XML parser leaves one layer."""
    once = (text.replace('&', '&amp;').replace('"', '&quot;')
                .replace('<', '&lt;').replace('>', '&gt;'))
    return once.replace('&', '&amp;')


def metadata_json(product_type, look_side, complex_product):
    """The nested data/collection document, with the keys IceyeConstants names."""
    orbit_states = []
    for i in range(5):
        t = '2024-01-22T21:56:0%d.000000Z' % i
        orbit_states.append({
            'time': t,
            'position': [1256493.68 + i, -6785812.73 + i, -980007.074 + i],
            'velocity': [-1698.783 + i, 749.488 + i, -7419.8 + i],
        })

    centroid_estimates = [
        {'time': '2024-01-22T21:56:0%d.000000Z' % i, 'coeffs': [-1851.83 + 100.0 * i]}
        for i in range(3)
    ]

    scene = {
        'average_scene_height': 120.0,
        'incidence_angle': {
            'near': 28.0,
            'far': 32.0,
            # a polynomial in the range sample index: 28 deg at 0, 32 deg at 48
            'coefficients': [28.0, 4.0 / RANGE_ROWS],
        },
        'projection': {
            'plane': 'slant' if complex_product else 'ground',
            'grsr': {
                'coefficients': [800000.0, 0.95, 1.0e-6],
                'zero_doppler_time_utc': '2024-01-22T21:56:01.000000Z',
            },
        },
        'slant_range_to_first_pixel': 800000.0,
    }

    return {
        'orbit_direction': 'DESCENDING',
        'data': {
            'file': 'ICEYE_SYNTHETIC_%s.tif' % product_type,
            'type': product_type,
            'calibration_factor': 3.5e-05,
            'looks': {'az': {'count': 1 if complex_product else 4},
                      'rg': {'count': 1}},
            'processing': {
                'version': 'ICEYE_P_SYNTHETIC',
                'end': '2024-01-23T00:00:00.000000Z',
                'zero_doppler_start_utc': '2024-01-22T21:56:00.000000Z',
                'zero_doppler_end_utc': '2024-01-22T21:56:04.000000Z',
                'prf': 2000.0,
                'bandwidth': {'az': 1500.0},
            },
            'sample': {
                'size': {'rg': RANGE_ROWS, 'az': AZIMUTH_COLUMNS},
                'spacing': {'rg': 2.5, 'az': 4.0},
            },
            'scene': scene,
        },
        'collection': {
            'id': 3303779,
            'platform': 'ICEYE-X13',
            'mode': 'spotlight',
            'look_side': look_side,
            'polarization': ['VV'],
            'start': '2024-01-22T21:56:00.000000Z',
            'end': '2024-01-22T21:56:04.000000Z',
            'carrier_frequency': 9650000000.0,
            'range_sampling_rate': 300000000.0,
            'chirp_bandwidth': 299000000.0,
            'orbit': {'states': orbit_states},
            'doppler_parameters': {
                'centroid_estimates': centroid_estimates,
                'rate_coeffs': [1651.75, -1119565.07],
            },
        },
    }


def gdal_metadata(product_type, look_side, complex_product):
    blob = json.dumps(metadata_json(product_type, look_side, complex_product),
                      separators=(',', ':'))
    return ('<GDALMetadata>\n'
            '  <Item name="METADATA_JSON">%s</Item>\n'
            '</GDALMetadata>\n' % escape(blob)).encode('utf-8') + b'\x00'


def raster(complex_product):
    """Every sample encodes its own (column, row), so a bad transpose is obvious."""
    payload = bytearray()
    for row in range(RANGE_ROWS):
        for col in range(AZIMUTH_COLUMNS):
            if complex_product:
                payload += struct.pack('<ff', 100.0 * col + row, (col - row) / 100.0)
            else:
                payload += struct.pack('<H', 100 * col + row)
    return bytes(payload)


def write_tiff(path, tags, payload):
    ordered = sorted(tags)
    n = len(ordered)
    pool_start = 8 + 2 + n * 12 + 4

    pool, value_pos = bytearray(), {}
    for tag in ordered:
        typ, count, data = tags[tag]
        if len(data) > 4:
            if len(pool) % 2:
                pool += b'\x00'
            value_pos[tag] = pool_start + len(pool)
            pool += data

    data_start = pool_start + len(pool)
    if data_start % 2:
        data_start += 1

    tags[T_STRIP_OFFSETS] = (TYPE_LONG, 1, struct.pack('<I', data_start))
    pool, value_pos = bytearray(), {}
    for tag in ordered:
        typ, count, data = tags[tag]
        if len(data) > 4:
            if len(pool) % 2:
                pool += b'\x00'
            value_pos[tag] = pool_start + len(pool)
            pool += data

    with open(path, 'wb') as out:
        out.write(b'II')
        out.write(struct.pack('<HI', 42, 8))
        out.write(struct.pack('<H', n))
        for tag in ordered:
            typ, count, data = tags[tag]
            out.write(struct.pack('<HHI', tag, typ, count))
            out.write(data.ljust(4, b'\x00') if len(data) <= 4
                      else struct.pack('<I', value_pos[tag]))
        out.write(struct.pack('<I', 0))
        out.write(pool)
        while out.tell() < data_start:
            out.write(b'\x00')
        out.write(payload)


def build(path, product_type, look_side, complex_product):
    payload = raster(complex_product)
    samples = 2 if complex_product else 1
    bits = [32, 32] if complex_product else [16]
    # 3 = IEEE float, 1 = unsigned integer
    formats = [3, 3] if complex_product else [1]

    transform = [GEO_A, GEO_B, 0.0, GEO_D,
                 GEO_E, GEO_F, 0.0, GEO_H,
                 0.0, 0.0, 0.0, 0.0,
                 0.0, 0.0, 0.0, 1.0]
    # minimal geographic WGS 84 key set
    geo_keys = [1, 1, 0, 3, 1024, 0, 1, 2, 1025, 0, 1, 1, 2048, 0, 1, 4326]

    tags = {
        T_IMAGE_WIDTH: (TYPE_LONG, 1, struct.pack('<I', AZIMUTH_COLUMNS)),
        T_IMAGE_LENGTH: (TYPE_LONG, 1, struct.pack('<I', RANGE_ROWS)),
        T_BITS_PER_SAMPLE: (TYPE_SHORT, samples, struct.pack('<' + 'H' * samples, *bits)),
        T_COMPRESSION: (TYPE_SHORT, 1, struct.pack('<H', 1)),
        T_PHOTOMETRIC: (TYPE_SHORT, 1, struct.pack('<H', 1)),
        T_STRIP_OFFSETS: (TYPE_LONG, 1, struct.pack('<I', 0)),
        T_SAMPLES_PER_PIXEL: (TYPE_SHORT, 1, struct.pack('<H', samples)),
        T_ROWS_PER_STRIP: (TYPE_LONG, 1, struct.pack('<I', RANGE_ROWS)),
        T_STRIP_BYTE_COUNTS: (TYPE_LONG, 1, struct.pack('<I', len(payload))),
        T_PLANAR_CONFIG: (TYPE_SHORT, 1, struct.pack('<H', 1)),
        T_SAMPLE_FORMAT: (TYPE_SHORT, samples, struct.pack('<' + 'H' * samples, *formats)),
        T_MODEL_TRANSFORMATION: (TYPE_DOUBLE, 16, struct.pack('<16d', *transform)),
        T_GEO_KEY_DIRECTORY: (TYPE_SHORT, len(geo_keys),
                              struct.pack('<' + 'H' * len(geo_keys), *geo_keys)),
    }
    blob = gdal_metadata(product_type, look_side, complex_product)
    tags[T_GDAL_METADATA] = (TYPE_ASCII, len(blob), blob)

    write_tiff(path, tags, payload)
    print('%s  %d x %d (azimuth x range)  %d bytes'
          % (os.path.basename(path), AZIMUTH_COLUMNS, RANGE_ROWS, os.path.getsize(path)))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(__file__) or '.'
    build(os.path.join(out, 'ICEYE_SYNTHETIC_LOOKLEFT_AML.tif'), 'AML', 'left', False)
    build(os.path.join(out, 'ICEYE_SYNTHETIC_LOOKLEFT_CPX.tif'), 'CPX', 'left', True)
    build(os.path.join(out, 'ICEYE_SYNTHETIC_LOOKRIGHT_AML.tif'), 'AML', 'right', False)


if __name__ == '__main__':
    main()
