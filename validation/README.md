# Cross-tool validation harness

Compares Microwave Toolbox output against independent open-source implementations of the same
algorithms — ISCE3/COMPASS, Dolphin, InSAR.dev, RAiDER, MintPy — so that agreement (or a diagnosed
disagreement) can be shown to users and to ESA, and re-run by anyone.

Specification: [`docs/superpowers/plans/2026-07-28-cross-tool-validation-spec.md`](../docs/superpowers/plans/2026-07-28-cross-tool-validation-spec.md)

**This directory is not a Maven module** and is not part of the build or the shipped plugin. The root
`pom.xml` lists its modules explicitly, so nothing here enters the reactor. It lives in the repo so a
report can cite a single commit covering both the toolbox and the harness that tested it.

---

## Layout

```
engines/      one directory per peer tool; Dockerfile + a thin `run.sh` adapter
compare/      host-side Python. Reads georeferenced rasters. Knows nothing about engines.
cases/        one YAML per comparison case
reports/      generated; git-ignored
```

**SNAP is deliberately NOT containerised.** It is invoked as a native CLI (`gpt`) because the native
build is the artifact we ship and therefore the one that must be under test — a container would
validate something else. The harness treats it as just another engine: a command that consumes a case
and writes rasters.

## Setup

Docker Desktop (WSL2 backend) and a Python 3.11+ host environment.

```bash
cp .env.example .env      # then edit the two host paths
docker compose build
python -m compare.selftest        # metric library unit checks, no engines, no data
docker compose run --rm isce3 /engines/isce3/smoke.sh   # proves the image before our data
```

## Windows note

Git Bash rewrites absolute container paths (`/engines/...` becomes `C:/Program Files/Git/engines/...`).
Prefix every `docker compose run` that passes one with `MSYS_NO_PATHCONV=1`, or use PowerShell. The
case runner sets it internally; only manual invocations need it.

## Data

Mounted **once, from one place, at identical in-container paths**, so no case file differs by engine
and nothing is copied:

| Host | Container | Mode |
|---|---|---|
| `${DATA_DIR}` (e.g. `E:\TestData`) | `/data` | read-only |
| `${WORK_DIR}` (e.g. `E:\Output\harness`) | `/work` | read-write |

Each engine writes only to `/work/<engine>/<case>/`. Docker Desktop runs on WSL2, so a Windows bind
mount and `/mnt/e` share the same I/O path — there is no performance argument between them. The
argument for Docker is dependency isolation (ISCE3 via conda-forge vs `insardev_pygmtsar` needing
GMTSAR binaries) and having a compose file we can hand to a third party.

## Running a case

```bash
python -m compare.run_case cases/gslc_s1_tops.yml
```

The runner: validates the case, **checks the declared phase conventions agree across engines and
aborts if they do not**, invokes each engine, then runs the requested metrics on the outputs and
writes `reports/<case>.md`.

## Adding things

- **A new peer tool** — a directory under `engines/` with a Dockerfile and `run.sh`, plus a compose
  service. No change to `compare/`.
- **A new feature to validate** — a case YAML and, if needed, one metric function. No change to any
  engine.

That separation is the whole point: the comparison layer must stay ignorant of the engines, or every
new tool becomes a rewrite.

## Non-negotiables

Learned by getting each one wrong during the July 2026 GSLC work; see the spec's trap registry.

1. **One staged DEM file**, passed explicitly to every engine — never "Copernicus 30 m" by name to
   each tool, which silently gives them different data.
2. **Identical output grid** — same CRS, posting and origin, or differences are dominated by
   resampling rather than by the algorithm.
3. **Conventions declared and cross-checked before processing.** ISCE3 defaults to
   `flatten=True, reramp=True`; the toolbox defaults to the opposite on both axes. A
   default-vs-default comparison produces noise indistinguishable from a real defect.
4. **Assert pixel content, never band existence.** An empty interferogram exits 0 with correctly
   named, full-size, all-zero bands.
5. **Match the metric to the quantity.** Residue counts and small-window coherence measure *local*
   phase noise and are blind to a smooth scene-scale field.
