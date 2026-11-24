# validation

The validation harness that lived here moved to the separate `snap-validation` repository
(`E:\ESA\snap-validation` locally).

It validates SNAP Sentinel-1 processing against truth data and against peer tools (ISCE3/COMPASS),
and produces a per-release scorecard report. The old corner-reflector tests in `sar-test-stacks`
were replaced by the same checks there.

See `docs/superpowers/specs/2026-11-20-snap-validation-design.md`.
