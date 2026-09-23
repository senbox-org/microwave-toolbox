<#
    S1 Napa stripmap fixtures (Tier D source 2 of the GSLC parity campaign).

    WINDOW: a box centred on the 24 Aug 2014 South Napa M6.0 epicentre (38.215 N, 122.312 W),
    derived PER PRODUCT from that product's own geolocation grid (annotation XML):

        leg A  20140807  52999 x 21733   epicentre line 5768  pixel 7609   region 4609,2768,6000,6000
        leg B  20140831  52975 x 21754   epicentre line 22248 pixel 7616   region 4616,19248,6000,6000

    = 26.8 km range x 21.8 km azimuth on each leg. The per-leg derivation is mandatory (spec):
    the datatakes start ~9 s apart in ANX-relative time, so the same ground point sits ~16.5k
    lines later in leg B - the legs CANNOT share a line range.

    DELIBERATE DEVIATION FROM SPEC RULE 2 ("Stripmap: crop azimuth, never range"), on the user's
    instruction 2026-09-22. Rule 2 exists because every recorded defect is range-varying, so a
    range crop discards the axis where the errors live; R5 numbers from this fixture are
    therefore less representative in range than Venezuela's and must be labelled as such.
    What the deviation buys: the full-range strip put the GSLC at ~785 M output pixels (the
    97 km range width, 3x oversampled at the 1.5 m slant-derived east step) and it had not
    reached its first progress tick after 35 minutes. The epicentre box is ~1/5 of that, and it
    is the area the published solution covers, which is what R6 needs.

    Rule 2's OTHER constraint still holds and is respected: >= 1539 azimuth lines, or
    GSLCGeocodingOp.estimateFdcFromData silently returns null and the fixture stops being the
    same experiment as the full scene. 6000 >> 1539.

    Outputs in E:\Output\parity\napa:
      <stem>_epi.dim        epicentre box
      <stem>_epi_orb.dim    + precise orbit
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$OUT = 'E:\Output\parity\napa'
Set-ParityLog "$OUT\fixture.log"

$SRC = 'E:\TestData\s1tbx\SAR\S1\Stripmap'
$legs = @(
    @{ N = 'A'; F = "$SRC\S1A_S1_SLC__1SSV_20140807T142342_20140807T142411_001835_001BC1_05AA.SAFE.zip";
       X = 4609; Y = 2768;  W = 6000; H = 6000 },
    @{ N = 'B'; F = "$SRC\S1A_S1_SLC__1SSV_20140831T142335_20140831T142403_002185_002356_C2E5.SAFE.zip";
       X = 4616; Y = 19248; W = 6000; H = 6000 }
)

foreach ($l in $legs) {
    if (-not (Test-Path $l.F)) { Log "ABORT: source $($l.F) not found"; exit 1 }
    $stem = [IO.Path]::GetFileNameWithoutExtension($l.F) -replace '\.SAFE$', ''
    $sub = Join-Path $OUT "$stem`_epi.dim"
    $orb = Join-Path $OUT "$stem`_epi_orb.dim"

    # SubsetOp copyMetadata defaults true (verified in SubsetOp.java) - without it every
    # downstream operator loses the abstracted metadata and fails far from here.
    $ok = Step "subset-$($l.N)" $sub { Invoke-Gpt @('Subset', "-Ssource=$($l.F)",
            "-Pregion=$($l.X),$($l.Y),$($l.W),$($l.H)", '-PcopyMetadata=true',
            '-t', $sub, '-f', 'BEAM-DIMAP', '-q', '8') }
    if (-not $ok) { Log "ABORT subset-$($l.N)"; exit 1 }

    $ok = Step "orbit-$($l.N)" $orb { Invoke-Gpt @('Apply-Orbit-File', "-Ssource=$sub",
            '-PorbitType=Sentinel Precise (Auto Download)', '-PcontinueOnFail=false',
            '-t', $orb, '-f', 'BEAM-DIMAP', '-q', '8') }
    if (-not $ok) { Log "ABORT orbit-$($l.N)"; exit 1 }
}
Log 'napa fixture complete'
Log 'VERIFY slant_range_to_first_pixel moved with the range offset before trusting any geocoding'
