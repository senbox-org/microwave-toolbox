# Synthetic ICEYE AML/CPX fixtures

**These are not real ICEYE products.** Unlike the Open Data fixtures next door,
which are windows carved out of an actual delivery, no AML or CPX product was
available, so these are built from scratch by `make_amlcpx_fixtures.py`.

| file | TIFF raster | bands |
|---|---|---|
| `ICEYE_SYNTHETIC_LOOKLEFT_AML.tif` | 96 x 48 | 1 x `ui16` amplitude |
| `ICEYE_SYNTHETIC_LOOKLEFT_CPX.tif` | 96 x 48 | 2 x `f32` amplitude, phase |
| `ICEYE_SYNTHETIC_LOOKRIGHT_AML.tif` | 96 x 48 | 1 x `ui16` amplitude |

Each is a plain uncompressed GeoTIFF carrying the two tags
`IceyeAMLCPXProductReader` reads: a `ModelTransformation` (34264) and a GDAL
metadata tag (42112) whose `METADATA_JSON` item holds the nested
`data`/`collection` document, double-escaped the way ICEYE writes it.

## What they establish, and what they do not

They exist to pin the **raster orientation**. The rasters are deliberately
non-square - 96 azimuth columns by 48 range rows - and every sample holds
`100 * column + row`, so a missing or mirrored transpose is unmissable.
`TestIceyeAMLCPXOrientation` reads them and checks each sample lands where the
look side says it should.

That is all a synthetic fixture can honestly establish. They say nothing about
whether the schema mapping in `IceyeConstants` matches what ICEYE actually
ships, or whether the values are physically sensible - the metadata is invented,
with round numbers chosen to be readable. Only a real AML or CPX product can
settle those, and `TestIceyeAMLCPXProductReader` remains the test for that,
skipped until the products are synced to the shared test tree.

## Why they were needed

`IceyeAMLCPXProductReader` declared its bands transposed - width from the TIFF's
`ImageLength`, height from its `ImageWidth`, which is correct - but then handed
them the GDAL image *untransposed*. On a square frame that is invisible; on any
other the raster is wrong in both axes and reads near the far edge throw
`IllegalArgumentException`. Every AML/CPX frame at hand happened to be square,
so it sat unnoticed. Reverting the fix makes four of these six tests fail.

To regenerate:

```
python make_amlcpx_fixtures.py .
```
