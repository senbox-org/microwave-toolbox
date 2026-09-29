# ICEYE Open Data COG test fixtures

Small, self-consistent ICEYE Open Data products used by the `IceyeOpenData*`
reader tests. They are carved out of a real delivery from the
[ICEYE Open Data Initiative](https://www.iceye.com/free-sar-data), scene
`6Q31WW` (Peru, 2025-11-10, ICEYE-X56, spotlight, VV):

| file | TIFF raster | layout |
|---|---|---|
| `ICEYE_6Q31WW_..._crop_GRD.tif` | 1024 x 512 | `ui16`, LZW, 512 px tiles |
| `ICEYE_6Q31WW_..._crop_SLC.tif` | 512 x 512 | 2 x `f32` (amplitude, phase), Deflate, 512 px tiles |

Each is accompanied by its side-car STAC item, exactly as ICEYE delivers it.

## How they were made

`make_opendata_fixtures.py` carves a tile-aligned window out of the full frame.
Everything that matters to the reader survives untouched:

* every TIFF tag is copied byte-for-byte, so tag 42112 (`GDAL_METADATA`, which
  carries the `ICEYE_PROPERTIES` STAC blob), 33922 (`ModelTiepoint`), 34735
  (`GeoKeyDirectory`) and the private ICEYE tag 50844 are what ICEYE wrote;
* the tile payloads are the delivered bytes. The GRD tiles are copied verbatim;
  the SLC tiles are stored uncompressed in the delivery (2 MB each) and are
  Deflate-compressed on the way out, which does not change a single sample.

Only the window-dependent header fields are rewritten: `ImageWidth`,
`ImageLength`, `TileOffsets`, `TileByteCounts`, `ModelTiepoint`, and
`proj:shape` / `proj:transform` / `bbox` / `geometry` in both the embedded
`ICEYE_PROPERTIES` item and the side-car item.

`ModelTiepoint` is regenerated from `proj:transform` rather than interpolated
from the delivered tiepoints. That affine is exact — it reproduces the
delivered tiepoints to ~1e-7 degrees — so the fixture's geocoding is a faithful
restriction of the original, not an approximation of it.

To regenerate against another delivery:

```
python make_opendata_fixtures.py <dir-with-ICEYE_*_GRD.tif-and-_SLC.tif> .
```

## What is not adjusted

Only the raster window and its geocoding are moved. Scene-level properties -
`iceye:incidence_angle_near`/`_far`, `iceye:range_near`/`_far`, the orbit states,
the Doppler estimates, `iceye:calibration_factor` - keep their full-frame values,
because they describe the acquisition rather than the window. So
`getIncidenceAngle(proj:shape[1])` on a fixture returns the angle at the far edge
of the *original* frame, not of the crop. The tests assert the delivered values.

## Why the GRD window is not square

Both delivered frames are square in one axis or another by coincidence (the GRD
is 20000 x 20000), which hides any confusion between the TIFF axes and SNAP's.
The GRD window is deliberately 1024 x 512 so the tests pin the orientation
contract down.

## Orientation contract

ICEYE writes these COGs "shadows-down", with **azimuth along the TIFF columns
and range along the TIFF rows** — the transpose of SNAP's SAR convention. Two
independent checks in the delivered metadata establish this:

* `iceye:incidence_angle_coeffs` is a polynomial in the *range sample index*.
  Evaluated at 0 it returns `iceye:incidence_angle_near`; evaluated at
  `proj:shape[1]` (= `ImageLength`, the TIFF row count) it returns
  `iceye:incidence_angle_far` to within 3e-5 deg. Evaluated at `proj:shape[0]`
  it returns nonsense.
* Solving the zero-Doppler condition against `iceye:orbit_states` for the ground
  points at either end of the column axis puts column 0 at the *end* of the
  aperture and the last column at the start.

So range runs down the TIFF rows and azimuth runs backwards along the TIFF
columns:

```
SNAP(x, y) == TIFF(column = width - 1 - y, row = x)
```

with SNAP width = `ImageLength` (range) and SNAP height = `ImageWidth`
(azimuth). `IceyeTransposedMultiLevelSource` applies this per pyramid level.
