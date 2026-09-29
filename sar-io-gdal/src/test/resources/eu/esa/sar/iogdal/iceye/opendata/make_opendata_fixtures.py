#!/usr/bin/env python3
"""Build small ICEYE Open Data COG fixtures from a full delivery.

The ICEYE Open Data Initiative ships GRD and SLC products as Cloud-Optimized
GeoTIFFs with a side-car STAC item.  The full frames are 0.5-16 GB, far too
large to commit, so this script carves a tile-aligned window out of a real
delivery and rewrites just enough of the header for the result to be a
self-consistent product:

  * every TIFF tag is copied byte-for-byte, so the tags the reader keys off
    (42112 GDAL_METADATA, 33922 ModelTiepoint, 34735 GeoKeyDirectory, the
    private ICEYE tag 50844) are exactly what ICEYE wrote;
  * ImageWidth / ImageLength / TileOffsets / TileByteCounts are recomputed for
    the window;
  * ModelTiepoint is regenerated from "proj:transform".  That affine is exact
    -- it reproduces the delivered tiepoints to ~1e-7 degrees -- so the
    fixture's geocoding is a faithful restriction of the original, not an
    interpolation of it;
  * "proj:shape", "proj:transform", "bbox" and "geometry" in both the embedded
    ICEYE_PROPERTIES item and the side-car STAC item are moved to the window.

SLC tiles are stored uncompressed (2 MB each); they are deflated on the way
out.  GRD tiles are already LZW-compressed and are copied verbatim.

Usage:
    python make_opendata_fixtures.py <source-dir> [<output-dir>]

where <source-dir> holds ICEYE_*_GRD.tif/.json and ICEYE_*_SLC.tif/.json.
"""

import json
import os
import struct
import sys
import zlib

# --- TIFF plumbing ---------------------------------------------------------

TYPE_SIZE = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8,
             11: 4, 12: 8, 13: 4, 16: 8, 17: 8, 18: 8}

T_IMAGE_WIDTH = 256
T_IMAGE_LENGTH = 257
T_COMPRESSION = 259
T_TILE_WIDTH = 322
T_TILE_LENGTH = 323
T_TILE_OFFSETS = 324
T_TILE_BYTE_COUNTS = 325
T_MODEL_TIEPOINT = 33922
T_GDAL_METADATA = 42112

COMPRESSION_DEFLATE = 8


class Tiff:
    """Minimal classic-TIFF / BigTIFF IFD0 reader."""

    def __init__(self, path):
        self.path = path
        self.f = open(path, 'rb')
        bo = self.f.read(2)
        self.e = '<' if bo == b'II' else '>'
        version = struct.unpack(self.e + 'H', self.f.read(2))[0]
        if version == 42:
            self.big = False
            ifd_off = struct.unpack(self.e + 'I', self.f.read(4))[0]
        elif version == 43:
            self.big = True
            self.f.read(4)  # offset size + reserved
            ifd_off = struct.unpack(self.e + 'Q', self.f.read(8))[0]
        else:
            raise ValueError('%s is not a TIFF (version %d)' % (path, version))

        self.f.seek(ifd_off)
        if self.big:
            count = struct.unpack(self.e + 'Q', self.f.read(8))[0]
            entry_size = 20
        else:
            count = struct.unpack(self.e + 'H', self.f.read(2))[0]
            entry_size = 12
        raw = self.f.read(count * entry_size)

        self.tags = {}  # tag -> (type, count, values)
        for i in range(count):
            entry = raw[i * entry_size:(i + 1) * entry_size]
            tag, typ = struct.unpack(self.e + 'HH', entry[:4])
            if self.big:
                n = struct.unpack(self.e + 'Q', entry[4:12])[0]
                value_field = entry[12:20]
            else:
                n = struct.unpack(self.e + 'I', entry[4:8])[0]
                value_field = entry[8:12]
            nbytes = TYPE_SIZE.get(typ, 1) * n
            if nbytes <= len(value_field):
                data = value_field[:nbytes]
            else:
                off = struct.unpack(
                    self.e + ('Q' if self.big else 'I'),
                    value_field[:8 if self.big else 4])[0]
                here = self.f.tell()
                self.f.seek(off)
                data = self.f.read(nbytes)
                self.f.seek(here)
            self.tags[tag] = (typ, n, self._decode(typ, data))

    def _decode(self, typ, data):
        if typ == 2:
            return data
        fmt = {1: 'B', 3: 'H', 4: 'I', 6: 'b', 8: 'h', 9: 'i',
               11: 'f', 12: 'd', 16: 'Q', 17: 'q'}.get(typ)
        if fmt is None:
            return data
        n = len(data) // TYPE_SIZE[typ]
        return list(struct.unpack(self.e + fmt * n, data[:n * TYPE_SIZE[typ]]))

    def value(self, tag):
        return self.tags[tag][2]

    def scalar(self, tag):
        return self.tags[tag][2][0]

    def read_tile(self, index):
        offsets = self.value(T_TILE_OFFSETS)
        counts = self.value(T_TILE_BYTE_COUNTS)
        self.f.seek(offsets[index])
        return self.f.read(counts[index])

    def close(self):
        self.f.close()


def write_classic_tiff(path, tags, tile_data):
    """Write a little-endian classic TIFF.

    ``tags`` maps tag -> (type, count, payload-bytes).  TileOffsets is patched
    once the tile positions are known, so it must already be present with the
    right count.
    """
    ordered = sorted(tags)
    n = len(ordered)

    header = 8
    ifd_size = 2 + n * 12 + 4
    pool_start = header + ifd_size

    # Lay out the out-of-line values.
    pool = bytearray()
    value_pos = {}
    for tag in ordered:
        typ, count, payload = tags[tag]
        if len(payload) > 4:
            if len(pool) % 2:
                pool += b'\x00'
            value_pos[tag] = pool_start + len(pool)
            pool += payload

    data_start = pool_start + len(pool)
    if data_start % 2:
        data_start += 1

    # Tiles follow the value pool.
    offsets, pos = [], data_start
    for tile in tile_data:
        offsets.append(pos)
        pos += len(tile)

    tags[T_TILE_OFFSETS] = (4, len(offsets),
                            struct.pack('<' + 'I' * len(offsets), *offsets))
    # Re-lay the pool now that TileOffsets has its final content (its length is
    # unchanged, so positions stay valid).
    pool = bytearray()
    for tag in ordered:
        typ, count, payload = tags[tag]
        if len(payload) > 4:
            if len(pool) % 2:
                pool += b'\x00'
            pool += payload

    with open(path, 'wb') as out:
        out.write(b'II')
        out.write(struct.pack('<HI', 42, header))
        out.write(struct.pack('<H', n))
        for tag in ordered:
            typ, count, payload = tags[tag]
            out.write(struct.pack('<HHI', tag, typ, count))
            if len(payload) <= 4:
                out.write(payload.ljust(4, b'\x00'))
            else:
                out.write(struct.pack('<I', value_pos[tag]))
        out.write(struct.pack('<I', 0))  # no next IFD
        out.write(pool)
        while out.tell() < data_start:
            out.write(b'\x00')
        for tile in tile_data:
            out.write(tile)


# --- GDAL_METADATA plumbing ------------------------------------------------

def unescape(text):
    # ICEYE double-escapes the JSON blob, so &amp;quot; -> &quot; -> ".
    for _ in range(2):
        text = (text.replace('&amp;', '&').replace('&quot;', '"')
                    .replace('&lt;', '<').replace('&gt;', '>'))
    return text


def escape(text):
    text = (text.replace('&', '&amp;').replace('"', '&quot;')
                .replace('<', '&lt;').replace('>', '&gt;'))
    return text.replace('&', '&amp;')


def parse_items(xml):
    """Split a <GDALMetadata> document into (name, attrs, raw-text) triples."""
    items, pos = [], 0
    while True:
        start = xml.find('<Item ', pos)
        if start < 0:
            return items
        head_end = xml.index('>', start)
        head = xml[start + 6:head_end]
        end = xml.index('</Item>', head_end)
        raw = xml[head_end + 1:end]
        name = head.split('name="', 1)[1].split('"', 1)[0]
        attrs = head.replace('name="%s"' % name, '', 1).strip()
        items.append((name, attrs, raw))
        pos = end + 7


def build_items(items):
    out = ['<GDALMetadata>']
    for name, attrs, raw in items:
        head = 'name="%s"' % name
        if attrs:
            head += ' ' + attrs
        out.append('  <Item %s>%s</Item>' % (head, raw))
    out.append('</GDALMetadata>')
    return '\n'.join(out) + '\n'


# --- window maths ----------------------------------------------------------

def crop_transform(transform, col0, row0):
    """Move a proj:transform origin to (col0, row0).

    ICEYE writes [lon0, d_lon/d_col, d_lon/d_row, lat0, d_lat/d_col,
    d_lat/d_row]; ModelTiepoint entry (i=col, j=row) is
    lon = lon0 + a*i + b*j, lat = lat0 + d*i + e*j.
    """
    lon0, a, b, lat0, d, e = transform
    return [lon0 + a * col0 + b * row0, a, b,
            lat0 + d * col0 + e * row0, d, e]


def tiepoints(transform, width, height, n=10):
    """A n x n ModelTiepoint grid spanning the raster, GeoTIFF (I,J,K,X,Y,Z)."""
    lon0, a, b, lat0, d, e = transform
    values = []
    for ci in range(n):
        i = round(ci * (width - 1) / (n - 1))
        for rj in range(n):
            j = round(rj * (height - 1) / (n - 1))
            values += [float(i), float(j), 0.0,
                       lon0 + a * i + b * j, lat0 + d * i + e * j, 0.0]
    return values


def corners(transform, width, height):
    lon0, a, b, lat0, d, e = transform
    pts = []
    for i, j in ((0, 0), (width - 1, 0), (width - 1, height - 1), (0, height - 1)):
        pts.append([lon0 + a * i + b * j, lat0 + d * i + e * j])
    return pts


def retarget(props, width, height, transform, filename):
    """Move a STAC properties dict onto the cropped window."""
    props = dict(props)
    props['proj:shape'] = [width, height]
    props['proj:transform'] = transform
    props['iceye:filename'] = filename
    ring = corners(transform, width, height)
    props['proj:centroid'] = {
        'lat': sum(p[1] for p in ring) / 4.0,
        'lon': sum(p[0] for p in ring) / 4.0,
    }
    return props, ring


# --- fixture construction --------------------------------------------------

def build(src_tif, src_json, dst_tif, dst_json, tile_col0, tile_row0,
          tiles_across, tiles_down, recompress):
    tiff = Tiff(src_tif)

    width = tiff.scalar(T_IMAGE_WIDTH)
    height = tiff.scalar(T_IMAGE_LENGTH)
    tile_w = tiff.scalar(T_TILE_WIDTH)
    tile_h = tiff.scalar(T_TILE_LENGTH)
    tiles_per_row = (width + tile_w - 1) // tile_w

    col0 = tile_col0 * tile_w
    row0 = tile_row0 * tile_h
    new_width = tiles_across * tile_w
    new_height = tiles_down * tile_h
    if col0 + new_width > width or row0 + new_height > height:
        raise ValueError('window falls outside %s' % src_tif)

    payloads = []
    for r in range(tiles_down):
        for c in range(tiles_across):
            index = (tile_row0 + r) * tiles_per_row + (tile_col0 + c)
            blob = tiff.read_tile(index)
            if recompress:
                blob = zlib.compress(blob, 9)
            payloads.append(blob)

    stac = json.load(open(src_json))
    transform = crop_transform(stac['properties']['proj:transform'], col0, row0)
    name = os.path.basename(dst_tif)
    props, ring = retarget(stac['properties'], new_width, new_height,
                           transform, name)

    # --- rebuild the tag set --------------------------------------------
    tags = {}
    for tag, (typ, count, values) in tiff.tags.items():
        if tag in (T_TILE_OFFSETS, T_TILE_BYTE_COUNTS):
            continue
        if typ == 2:
            payload = values
        else:
            fmt = {1: 'B', 3: 'H', 4: 'I', 6: 'b', 8: 'h', 9: 'i',
                   11: 'f', 12: 'd', 16: 'Q', 17: 'q'}[typ]
            payload = struct.pack('<' + fmt * len(values), *values)
        tags[tag] = (typ, count, payload)

    def put(tag, typ, values):
        fmt = {3: 'H', 4: 'I', 12: 'd'}[typ]
        tags[tag] = (typ, len(values),
                     struct.pack('<' + fmt * len(values), *values))

    put(T_IMAGE_WIDTH, 4, [new_width])
    put(T_IMAGE_LENGTH, 4, [new_height])
    put(T_MODEL_TIEPOINT, 12, tiepoints(transform, new_width, new_height))
    put(T_TILE_BYTE_COUNTS, 4, [len(p) for p in payloads])
    put(T_TILE_OFFSETS, 4, [0] * len(payloads))
    if recompress:
        put(T_COMPRESSION, 3, [COMPRESSION_DEFLATE])
    # BigTIFF stores TileOffsets as LONG8; the fixture is classic TIFF.
    if tiff.tags[T_TILE_OFFSETS][0] == 16:
        pass  # already re-typed to LONG above

    # --- rewrite the embedded metadata ----------------------------------
    xml = tiff.tags[T_GDAL_METADATA][2].split(b'\x00')[0].decode('utf-8')
    rebuilt = []
    for item_name, attrs, raw in parse_items(xml):
        if item_name == 'ICEYE_PROPERTIES':
            blob = json.dumps(props, separators=(',', ':'))
            rebuilt.append((item_name, attrs, escape(blob)))
        elif item_name == 'PRODUCT_FILE':
            rebuilt.append((item_name, attrs, name))
        elif item_name == 'PRODUCT_NAME':
            rebuilt.append((item_name, attrs, name[:-len('.tif')]))
        else:
            rebuilt.append((item_name, attrs, raw))
    blob = build_items(rebuilt).encode('utf-8') + b'\x00'
    tags[T_GDAL_METADATA] = (2, len(blob), blob)

    write_classic_tiff(dst_tif, tags, payloads)
    tiff.close()

    # --- side-car STAC item ---------------------------------------------
    stac['properties'] = props
    stac['bbox'] = [min(p[0] for p in ring), min(p[1] for p in ring),
                    max(p[0] for p in ring), max(p[1] for p in ring)]
    stac['geometry'] = {'type': 'Polygon', 'coordinates': [ring + [ring[0]]]}
    for asset in stac.get('assets', {}).values():
        href = asset.get('href', '')
        if href.endswith('.tif'):
            asset['href'] = name
        elif href.endswith('.json'):
            asset['href'] = os.path.basename(dst_json)
    with open(dst_json, 'w') as out:
        json.dump(stac, out, indent=1)

    print('%s  %d x %d  %.2f MB' %
          (name, new_width, new_height, os.path.getsize(dst_tif) / 1e6))


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else '.'
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.dirname(__file__) or '.'

    found = {}
    for entry in os.listdir(src):
        upper = entry.upper()
        if upper.startswith('ICEYE_') and upper.endswith(('_GRD.TIF', '_SLC.TIF')):
            found['SLC' if upper.endswith('_SLC.TIF') else 'GRD'] = entry
    if len(found) != 2:
        raise SystemExit('expected one _GRD.tif and one _SLC.tif in %s' % src)

    # Windows are taken from the middle of each frame so the fixtures carry
    # real backscatter rather than the dark scene margin.  The GRD window is
    # deliberately non-square: the delivered frames happen to be square, which
    # hides any confusion between the TIFF axes (column = azimuth, row =
    # range) and SNAP's (x = range, y = azimuth).
    plan = {
        # kind: (tile col, tile row, tiles across, tiles down, recompress)
        'GRD': (19, 19, 2, 1, False),
        'SLC': (112, 15, 1, 1, True),
    }
    for kind, tif_name in sorted(found.items()):
        base = tif_name[:-len('_%s.tif' % kind)]
        out_base = '%s_crop_%s' % (base, kind)
        build(os.path.join(src, tif_name),
              os.path.join(src, tif_name[:-4] + '.json'),
              os.path.join(dst, out_base + '.tif'),
              os.path.join(dst, out_base + '.json'),
              *plan[kind])


if __name__ == '__main__':
    main()
