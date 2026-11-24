# snap-validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Python repo, `snap-validation`. One command, `sv run`, validates an installed SNAP build
on the four Sentinel-1 first-wave features, and `sv report` turns the results into the scorecard
report: correctness against truth and peer tools, plus speed and memory against the last release.

**Architecture:**
- `sv` is a toolbox-agnostic Python package. It holds config, data, cases, verdicts, engines,
  measurement, history, runner and report.
- `toolboxes/microwave/` holds the feature list, case YAMLs, graphs and reference data.
- SNAP runs as the installed `gpt`. The peer tools run as Docker Compose services, and their outputs
  are cached by image digest plus an input hash.
- Every run writes one JSON record to `history/`. The report is rendered from that record and the
  baseline record.

**Tech Stack:**
- Python 3.12; pytest; numpy, scipy, rasterio; PyYAML; psutil; boto3; Jinja2; matplotlib (SVG
  output); Playwright/Chromium (HTML → PDF).
- Docker Desktop (WSL2).
- SNAP 14 `gpt` on Windows.

**Spec:** `E:\ESA\microwave-toolbox\docs\superpowers\specs\2026-11-20-snap-validation-design.md`.
The report mockup sits next to it: `2026-11-20-snap-validation-report-mockup.html`. Read both before
Task 1.

## Global Constraints

- **Never run `git commit` or `git push`.** The user reviews and commits. Each task ends with a
  checkpoint: list the changed files and stop for the user.
- Repo location: `E:\ESA\snap-validation`, a clone of the GitHub `snap-validation` repo the user creates.
- Python **3.12**. Dependencies are pinned in a committed lock file.
- SNAP under test = the **installed** Windows build, invoked as `gpt`. Never `esa_snappy`. Never rely on
  the installed `gpt.vmoptions`: parallelism (`-q`), tile cache (`-c`) and `-Xmx` are set explicitly on
  every call.
- No PowerShell-only steps: the runner is Python, and the engines are Docker or `gpt`.
- Never commit machine-specific values: absolute paths, bucket names, machine names other than
  `machine_id`. `config/machine.yaml` is git-ignored; only `machine.example.yaml` is committed.
  `history/` JSON holds relative paths and the `machine_id` only.
- S3 copy of the data: `s3://skywatch-image-processing-test/SNAPTestData/`, holding only what the
  first-wave cases need.
- Scope: Sentinel-1 only. Four scorecard features: `calibration`, `grd_tc`, `tops_insar` (classical
  chain), `gslc`.
- Verdict rules:
  - **PASS**: all cases pass, and efficiency is ≤ 5% worse than the baseline.
  - **PASS\***: efficiency is 5–10% worse.
  - **FAIL**: a case fails, or efficiency is > 10% worse.
  - Minimum heap counts as worse only if it rose by **> 1 rung (256 MB)** AND by more than the %
    threshold.
- Minimum heap = the smallest `-Xmx` on a **256 MB** ladder (floor **1024 MB**) that finishes with
  **GC share < 0.20**, and whose output per-band statistics match the generous-heap run within
  **1e-6 relative**.
- Wall time = the faster of **two** runs at the standard heap.
- House palette: DARK `#003249`, MINT `#75C8AE`, CORAL `#F06669`, TEAL `#335E6E`, BLUE `#009BDA`;
  Arial.
- Pytest markers: `snap` (needs installed SNAP), `docker` (needs peer images), `data` (needs real
  test data). None of them runs by default (`addopts = -m "not snap and not docker and not data"`).

## Review Focus

1. **gpt exits 0 but writes partial or empty output** (missing band, zero-byte `.img`). The case must
   be ERROR with the reason, never metrics on garbage. *(Task 6:
   `test_run_fails_when_expected_output_missing`.)*
2. **A run interrupted mid-case** (Ctrl-C, reboot). `--resume` must rerun that case and never trust its
   partial outputs. *(Task 11: `test_resume_reruns_case_without_marker`,
   `test_marker_written_last`.)*
3. **A crash while `gpt.vmoptions` is modified** (only if Task 6 needs the vmoptions fallback). The
   next run must restore the original before doing anything. *(Task 6:
   `test_stale_vmoptions_backup_restored_on_start`.)*
4. **SNAP Desktop or another `java`/`gpt` starts during the efficiency pass.** That timing must be
   flagged *contaminated* and re-measured, not reported. *(Task 11:
   `test_timing_rerun_when_foreign_java_appears`.)*
5. **A baseline measured with different efficiency settings** (parallelism, tile cache or standard
   heap changed in `machine.yaml`). It must not be compared; the report shows "baseline" with a note.
   *(Task 10: `test_baseline_ignored_when_settings_fingerprint_differs`.)*

---

## Phase A: Foundation

### Task 1: Migrate the harness with history and scaffold the repo

**Files:**
- Create in `E:\ESA\snap-validation`: `pyproject.toml`, `uv.lock` (or `requirements.lock`),
  `.gitignore`, `README.md`, `config/machine.example.yaml`, `sv/__init__.py`, `sv/cli.py`,
  `tests/conftest.py`, `docs/` (copies of the spec, mockup and this plan)
- Move (from the filtered history): `engines/{isce3,dolphin,insardev,snap}/`, `docker-compose.yml`,
  `compare/metrics.py` → `sv/metrics/peer.py`, `compare/selftest.py` → `tests/test_metrics_peer.py`,
  `cases/*.yml` → `toolboxes/microwave/cases/legacy/`
- Delete after the move: `drivers/`, `gslc_parity/`, `experiment_2x2.ps1`, `overnight.ps1`,
  `gslc_equivalence.py`, `compare/render_*.py`, `compare/seam_*.py`, `compare/diff_*.py`
- Modify: `docker-compose.yml`. Remove the hard-coded `E:\Output\exp2x2` mount and the `./compare`
  mount; mount `./sv/metrics` read-only.

**Interfaces:**
- Produces: the package `sv`; the console script `sv` (`sv --help` lists `run`, `report`, `data`); the
  `Metric` dataclass and the existing peer metric functions under `sv.metrics.peer` (names unchanged:
  `phase_difference_smoothness`, `amplitude_ratio`, `rms_difference`, `residue_density`,
  `local_phase_coherence`, `circular_std_multilooked`, `safe_multilook_factor`, registry `ALL`).

- [ ] **Step 1: Filter the history.** `E:\ESA\snap-validation` already exists, with GitHub's initial
  commit (`.gitignore`, `LICENSE`).
  - In a scratch clone of microwave-toolbox (in the scratchpad, not `E:\ESA\microwave-toolbox`), run
    `git filter-repo --subdirectory-filter validation`.
  - Hand the user the commands that bring this history in. In `E:\ESA\snap-validation`:
    `git remote add harness <scratch clone>`, `git fetch harness`,
    `git merge --allow-unrelated-histories harness/master`. Merge the two `.gitignore` files.
  - Stop. The user runs the merge and the push.
- [ ] **Step 2: Restructure** to the layout in spec §4 (the moves and deletes above). Add
  `pyproject.toml`: Python 3.12, the dependencies from the Tech Stack, the console script
  `sv = "sv.cli:main"`, and the pytest `addopts` and markers from Global Constraints.
- [ ] **Step 3: Convert `selftest.py` to pytest.** Keep every existing assertion. Run
  `pytest tests/test_metrics_peer.py -v`. Expected: all pass.
- [ ] **Step 4: CLI skeleton.** `sv/cli.py: main(argv: list[str] | None = None) -> int` with argparse
  sub-commands `run`, `report`, `data`. Each stub prints "not implemented" and returns 2. Test
  `test_cli_lists_subcommands`: `main(["--help"])` exits 0, and its output contains `run`, `report` and
  `data`.
- [ ] **Step 5: Verify.** `pip install -e .` (or `uv sync`), then `sv --help` and `pytest`. Expected:
  all pass.
- [ ] **Step 6: Checkpoint.** List the changed files and stop for the user to commit.

### Task 2: Machine config

**Files:**
- Create: `sv/config.py`, `config/machine.example.yaml`
- Test: `tests/test_config.py`

**Interfaces:**
- Produces:

```python
@dataclass(frozen=True)
class MachineConfig:
    machine_id: str
    snap_home: Path
    roots: dict[str, Path]        # e.g. {"testdata": E:\TestData, "scenes": E:\Data}
    work_dir: Path
    s3_uri: str                   # s3://skywatch-image-processing-test/SNAPTestData/
    aws_profile: str | None
    standard_heap_mb: int
    parallelism: int
    tile_cache_mb: int
    docker_threads: int
    def settings_fingerprint(self) -> str   # sha256 of (standard_heap_mb, parallelism, tile_cache_mb)

class ConfigError(Exception): ...
def load_machine_config(path: Path) -> MachineConfig
```

- [ ] **Step 1: Write the failing tests.**
  - `test_load_example_config` round-trips the example file.
  - `test_missing_key_raises` names the missing key in the message.
  - `test_s3_uri_must_end_with_slash`.
  - `test_fingerprint_changes_with_parallelism`.
  - `test_example_has_no_absolute_windows_paths_except_placeholders`: the example uses `<...>`
    placeholders.
- [ ] **Step 2: Run.** `pytest tests/test_config.py -v`. Expected: FAIL (module missing).
- [ ] **Step 3: Implement** `sv/config.py` and the example YAML. `.gitignore` lists
  `config/machine.yaml`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Checkpoint.**

### Task 3: Data manifest and S3 layer

**Files:**
- Create: `sv/data.py`, `data/manifest.yaml` (empty `entries: []`)
- Modify: `sv/cli.py` (`sv data verify`, `sv data upload`, `sv data add <root> <relpath>`)
- Test: `tests/test_data.py`

**Interfaces:**
- Consumes: `MachineConfig` (Task 2).
- Produces:

```python
@dataclass(frozen=True)
class ManifestEntry: id: str; root: str; path: str; sha256: str; size: int   # path: posix, relative to root
def load_manifest(path: Path) -> dict[str, ManifestEntry]
class ObjectStore(Protocol):
    def exists(self, key: str) -> bool: ...
    def download(self, key: str, dest: Path) -> None: ...
    def upload(self, src: Path, key: str) -> None: ...
class S3Store:  # boto3; __init__(uri: str, profile: str | None)
class DataLayer:
    def __init__(self, cfg: MachineConfig, manifest: dict[str, ManifestEntry], store: ObjectStore): ...
    def key(self, entry: ManifestEntry) -> str          # f"{root}/{path}" under the s3 prefix
    def local_path(self, entry_id: str) -> Path         # downloads if missing, then verifies sha256
    def verify(self) -> list[str]                       # ids whose local file is missing or mismatched
    def upload_missing(self) -> list[str]               # ids uploaded
```

- [ ] **Step 1: Write the failing tests**, using an in-memory `FakeStore`:
  - `test_key_maps_root_and_path`: `testdata/s1tbx/SAR/x.zip`.
  - `test_local_path_downloads_when_missing`.
  - `test_local_path_raises_on_sha_mismatch`: the message names the id; a bad file is never left in
    place.
  - `test_upload_missing_skips_existing_keys`.
  - `test_add_computes_sha_and_size`.
  - `test_duplicate_ids_rejected`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** Hash in 8 MiB chunks. Download to `*.part`, then rename after the hash
  passes.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Check the bucket region.** Run `aws s3api get-bucket-location --bucket
  skywatch-image-processing-test` with the user's profile. Record the region in `README.md` (spec §4.1,
  §10).
- [ ] **Step 6: Checkpoint.**

### Task 4: Case and feature model

**Files:**
- Create: `sv/case.py`, `toolboxes/microwave/features.yaml`
- Test: `tests/test_case.py`

**Interfaces:**
- Produces:

```python
@dataclass(frozen=True)
class MetricSpec: name: str; limit: float | tuple[float, float]; direction: Literal["below","above","within"]
                  source: str; status: Literal["proposed","approved"]; kind: Literal["truth","peer"]
@dataclass(frozen=True)
class Diagnosis: text: str; ref: str
@dataclass(frozen=True)
class Case: id: str; feature: str; kind: str; engines: list[str]; reference_kind: Literal["truth","peer"]   # kind: cr | raster_compare | peer_ifg | efficiency_only
            reference_source: str; metrics: list[MetricSpec]; efficiency: bool; efficiency_only: bool
            diagnosis: Diagnosis | None; inputs: list[str]; raw: dict; path: Path
@dataclass(frozen=True)
class Feature: id: str; title: str; order: int; cases: list[str]
class CaseError(Exception): ...
def load_case(path: Path) -> Case
def load_features(path: Path, cases: dict[str, Case]) -> list[Feature]
```

- Schema: the spec §6 YAML block, plus `inputs:` (manifest ids) and `efficiency_only: false`.
- The peer-case rules (at least 2 engines, `conventions` check) are kept verbatim from the old
  `Case.validate`/`check_conventions` (`validation/compare/run_case.py:50-104`).

- [ ] **Step 1: Write the failing tests.**
  - `test_truth_case_allows_single_engine`.
  - `test_peer_case_requires_two_engines`.
  - `test_peer_case_convention_mismatch_raises`: port the old behaviour.
  - `test_metric_without_source_rejected`.
  - `test_status_defaults_to_proposed`.
  - `test_unknown_metric_name_rejected`: names are checked against the metric registry, `sv.metrics.ALL`.
  - `test_efficiency_only_case_has_no_metrics`.
  - `test_feature_needs_exactly_one_efficiency_case`.
  - `test_feature_references_unknown_case_rejected`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** `features.yaml` lists the four features (ids from Global Constraints,
  titles from the mockup) with empty `cases: []`. Tasks 13–17 fill the cases in.
  `sv/metrics/__init__.py` exposes `ALL` as a merged registry: peer metrics plus the CR and OPERA
  metrics added later.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Checkpoint.**

### Task 5: Verdict rules

**Files:**
- Create: `sv/verdict.py`
- Test: `tests/test_verdict.py`

**Interfaces:**
- Consumes: `MetricSpec`, `Case`, `Diagnosis` (Task 4).
- Produces:

```python
class Verdict(StrEnum): PASS="PASS"; WARN="PASS*"; FAIL="FAIL"; DIAGNOSED="DIAGNOSED"; BASELINE="BASELINE"; ERROR="ERROR"
@dataclass(frozen=True)
class MetricResult: spec: MetricSpec; value: float; unit: str; verdict: Verdict; previous: float | None
@dataclass(frozen=True)
class Efficiency: wall_s: float; min_heap_mb: int | None; peak_rss_mb: float; gc_share: float
@dataclass(frozen=True)
class EfficiencyVerdict: verdict: Verdict; wall_delta: float | None; heap_delta: float | None; note: str
def metric_verdict(spec: MetricSpec, value: float, diagnosis: Diagnosis | None) -> Verdict
def case_verdict(ran_ok: bool, results: list[MetricResult]) -> Verdict
def efficiency_verdict(cur: Efficiency, base: Efficiency | None, step_mb: int = 256) -> EfficiencyVerdict
def feature_verdict(cases: list[Verdict], eff: EfficiencyVerdict) -> Verdict
```

- [ ] **Step 1: Write the failing tests** with the spec's exact values.
  - `test_truth_metric_outside_limit_fails_even_with_diagnosis`.
  - `test_peer_metric_outside_limit_with_diagnosis_is_diagnosed`.
  - `test_peer_metric_outside_limit_without_diagnosis_fails`.
  - `test_within_direction_uses_both_bounds`.
  - `test_case_not_run_is_error`.
  - Efficiency, wall time: `test_wall_4pct_worse_is_pass`, `test_wall_7pct_worse_is_warn`,
    `test_wall_11pct_worse_is_fail`.
  - Efficiency, heap: `test_heap_one_rung_rise_never_changes_verdict` (2048 → 2304 MB is +12.5% but
    one rung → PASS); `test_heap_two_rungs_and_over_10pct_fails` (2048 → 2560 MB).
  - `test_no_baseline_gives_baseline_verdict`.
  - `test_feature_takes_worst_of_cases_and_efficiency`: DIAGNOSED counts as PASS; ERROR counts as FAIL.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** The deltas are relative to the baseline, so (cur − base) / base.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Checkpoint.**

## Phase B: Engines, measurement, history, runner

### Task 6: gpt engine with process measurement

**Files:**
- Create: `sv/engines/snap.py`, `tests/fakes/fake_gpt.py` (a Python stand-in for `gpt`: it sleeps,
  allocates, writes a `-Xlog:gc` style file and the expected outputs, configurable via environment
  variables)
- Test: `tests/test_engine_snap.py`

**Interfaces:**
- Consumes: `MachineConfig`.
- Produces:

```python
@dataclass(frozen=True)
class SnapBuild: version: str; modules: dict[str, str]          # from `gpt --diag` / module manifests
@dataclass(frozen=True)
class GptResult: exit_code: int; wall_s: float; peak_rss_mb: float; gc_log: Path; log: Path; outputs: list[Path]; ok: bool; reason: str
class Gpt:
    def __init__(self, cfg: MachineConfig, executable: Path | None = None): ...
    def build(self) -> SnapBuild
    def run(self, graph: Path, params: dict[str, str], xmx_mb: int, out_dir: Path, expect: list[str]) -> GptResult
```

- [ ] **Step 1: Decide the heap mechanism.** Run `gpt -J-Xmx2048m --diag` on the installed build. If
  the reported max memory is about 2 GB, pass `-J-Xmx<mb>m` (plus `-J-Xlog:gc*:file=<log>`) on the
  command line. Otherwise fall back to what snap-gpt-tests does
  (`gpt-tests-executer/.../TestExecutor.java:41-56`): rewrite `bin/gpt.vmoptions` inside a context
  manager that keeps a `gpt.vmoptionsORIGINAL` backup and restores it in `finally`. Record the
  decision in `sv/engines/snap.py`'s module docstring.
- [ ] **Step 2: Write the failing tests** against `fake_gpt`.
  - `test_run_passes_q_c_and_xmx`: inspect the recorded argv.
  - `test_wall_and_peak_rss_recorded`: RSS is sampled every 0.25 s over the whole process tree
    (`psutil`).
  - `test_nonzero_exit_is_not_ok`.
  - `test_run_fails_when_expected_output_missing`: Review Focus 1. Also covers zero-byte `.img`.
  - `test_build_parses_version`.
  - If the vmoptions fallback is used: `test_stale_vmoptions_backup_restored_on_start` (Review Focus 3).
- [ ] **Step 3: Run.** Expected: FAIL.
- [ ] **Step 4: Implement.** gpt's stdout and stderr go to `log`. Judge success by exit code plus
  `expect`, never by stderr content (the lesson from `run.ps1:30-55`).
- [ ] **Step 5: Run.** `pytest tests/test_engine_snap.py -v`. Expected: PASS. Then
  `pytest -m snap tests/test_engine_snap.py::test_real_gpt_diag`: `build().version` starts with
  `14.`.
- [ ] **Step 6: Checkpoint.**

### Task 7: GC parsing, output equality and minimum-heap search

**Files:**
- Create: `sv/measure.py`
- Test: `tests/test_measure.py`, `tests/data/gc_g1_sample.log` (captured from a real `gpt` run in
  Task 6)

**Interfaces:**
- Consumes: `GptResult` (Task 6).
- Produces:

```python
def gc_pause_seconds(gc_log: Path) -> float              # sum of pause durations in a JDK 21 -Xlog:gc* file
def gc_share(gc_log: Path, wall_s: float) -> float
def band_stats(out_dir: Path) -> dict[str, tuple[float, float, float, float]]   # mean, std, min, max per band (rasterio)
def outputs_match(a: Path, b: Path, rel_tol: float = 1e-6) -> bool
@dataclass(frozen=True)
class Rung: xmx_mb: int; passed: bool; gc_share: float; reason: str; repeat: bool
@dataclass(frozen=True)
class MinHeapResult: min_heap_mb: int | None; rungs: list[Rung]
def min_heap_search(run_at: Callable[[int], Rung], standard_mb: int, baseline_mb: int | None,
                    floor_mb: int = 1024, step_mb: int = 256) -> MinHeapResult
```

- Algorithm (spec §7, "Keeping it overnight-sized"):
  - With a baseline: probe `baseline_mb - step`. If it passes, walk down until a fail or the floor. If
    it fails, walk up from `baseline_mb` until a pass or `standard_mb`.
  - Without a baseline: bisect between the floor and `standard_mb` on the step grid.
  - Afterwards, re-run only the deciding pair (the lowest pass and the highest fail) once each; a
    disagreement takes the worse result.
  - `None` if `standard_mb` itself fails.

- [ ] **Step 1: Write the failing tests.**
  - `test_gc_pause_seconds_on_sample_log`: the expected value is hand-counted from the sample.
  - `test_outputs_match_identical_and_perturbed`: 1e-7 relative matches; 1e-5 does not.
  - `test_search_with_baseline_walks_down`: true threshold 1792, baseline 2048 → 1792; at most 4 runs
    before the repeats.
  - `test_search_without_baseline_bisects`.
  - `test_deciding_rungs_repeated_once`.
  - `test_flaky_deciding_rung_takes_worse`.
  - `test_standard_fails_returns_none`.
  - `test_never_below_floor`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Checkpoint.**

### Task 8: Port the SNAP chains (`run.ps1`) to Python

**Files:**
- Create: `sv/engines/snap_chains.py`; `toolboxes/microwave/graphs/*.xml` (one parameterised graph per
  gpt step that `run.ps1` invokes)
- Delete: `engines/snap/run.ps1` (after Step 5 passes)
- Test: `tests/test_snap_chains.py`

**Interfaces:**
- Consumes: `Gpt.run` (Task 6), `Case` (Task 4).
- Produces:

```python
@dataclass(frozen=True)
class Step: name: str; graph: Path; params: dict[str, str]; expect: list[str]
def chain_for(case: Case, variant: Literal["gslc", "classic", "graph"], work: Path) -> list[Step]
def run_chain(gpt: Gpt, steps: list[Step], xmx_mb: int) -> list[GptResult]   # stops at the first not-ok step
```

- The `graph` variant runs the case's own `snap.graph` + `snap.params` as a single step. The new CR and
  OPERA cases use it.
- `gslc` and `classic` reproduce `engines/snap/run.ps1` exactly: the same operators, parameters, order
  and capability probing.

- [ ] **Step 1: Read `run.ps1` fully.** List every gpt invocation, and its parameters, for both
  variants.
- [ ] **Step 2: Write the failing tests** using `fake_gpt`.
  - `test_gslc_chain_steps_match_run_ps1_inventory`.
  - `test_classic_chain_includes_back_geocoding_esd_deburst_tc_in_order`.
  - `test_chain_stops_at_first_failure`.
  - `test_case_subswath_and_polarisation_passed`.
- [ ] **Step 3: Implement.** Extract the graphs and the step lists.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Equivalence check** (`-m "snap and data"`). Run both variants on
  `toolboxes/microwave/cases/legacy/gslc_s1_venezuela_orbit106.yml` with the old `run.ps1` (still
  present) and with the new chain. `outputs_match` must be True for both. Then delete `run.ps1`.
- [ ] **Step 6: Checkpoint.**

### Task 9: Peer engines with caching

**Files:**
- Create: `sv/engines/peers.py`
- Modify: `engines/isce3/run.sh`, so that it takes the case path and an output dir as arguments (it
  currently derives them from the mounts)
- Test: `tests/test_peers.py`

**Interfaces:**
- Consumes: `Case`, `DataLayer.local_path`, `MachineConfig.work_dir`.
- Produces:

```python
def image_digest(service: str, compose_file: Path) -> str
def case_input_hash(case: Case, manifest: dict[str, ManifestEntry]) -> str   # sha256 over the sorted input shas + the case's peer-relevant YAML (engine_params, conventions, grid, dem)
class PeerEngine:
    def __init__(self, service: str, compose_file: Path, cfg: MachineConfig): ...
    def output_dir(self, case: Case, input_hash: str) -> Path   # work_dir/peers/<service>/<digest12>/<case.id>/<input_hash12>/
    def run(self, case: Case, input_hash: str) -> Path           # cached: returns at once if output_dir has a .complete marker
```

- [ ] **Step 1: Write the failing tests**, with `subprocess` mocked.
  - `test_cache_hit_skips_docker`.
  - `test_cache_miss_after_dem_change`: the hash includes the DEM entry's sha.
  - `test_cache_miss_after_image_rebuild`.
  - `test_failed_peer_run_leaves_no_marker`.
  - `test_compose_called_without_msys_path_conversion`: `MSYS_NO_PATHCONV=1` is set in the
    environment.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS. Then `pytest -m docker
  tests/test_peers.py::test_isce3_smoke_through_peer_engine`.
- [ ] **Step 5: Checkpoint.**

### Task 10: History and baseline

**Files:**
- Create: `sv/history.py`, `history/.gitkeep`
- Test: `tests/test_history.py`

**Interfaces:**
- Consumes: `Verdict`, `MetricResult`, `Efficiency`, `EfficiencyVerdict` (Task 5); `SnapBuild` (Task 6).
- Produces:

```python
@dataclass
class CaseRecord: case_id: str; verdict: Verdict; metrics: list[MetricResult]; artifacts: dict[str, str]  # relative paths of plots/data for the report
@dataclass
class FeatureRecord: feature_id: str; verdict: Verdict; cases: list[CaseRecord]; efficiency: Efficiency | None; efficiency_verdict: EfficiencyVerdict | None; graph: str
@dataclass
class RunRecord: run_id: str; label: str; started: str; finished: str; machine_id: str; settings_fingerprint: str
                 build: SnapBuild; peers: dict[str, str]; features: list[FeatureRecord]; baseline_only: bool
def is_release_label(label: str) -> bool                     # ^\d+\.\d+\.\d+$
def write_run(rec: RunRecord, history_dir: Path) -> Path     # history/<run_id>.json, atomic write
def load_runs(history_dir: Path) -> list[RunRecord]
def find_baseline(runs: list[RunRecord], current: RunRecord) -> RunRecord | None
def trend(runs: list[RunRecord], feature_id: str, machine_id: str) -> list[tuple[str, Efficiency]]   # release labels, oldest first
```

- [ ] **Step 1: Write the failing tests.**
  - `test_roundtrip_json`.
  - `test_baseline_is_latest_release_same_machine`: RCs and snapshots are ignored.
  - `test_baseline_excludes_current_run`.
  - `test_baseline_ignored_when_settings_fingerprint_differs` (Review Focus 5).
  - `test_record_contains_no_absolute_paths`: scan the serialised JSON for drive letters and leading
    `/`.
  - `test_trend_orders_by_version_not_date`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** Order versions with `packaging.version`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Checkpoint.**

### Task 11: Runner and `sv run`

**Files:**
- Create: `sv/runner.py`
- Modify: `sv/cli.py`
- Test: `tests/test_runner.py` (fake gpt, fake peers, a toy toolbox under `tests/data/toolbox/`)

**Interfaces:**
- Consumes: everything above.
- Produces:

```python
@dataclass
class RunContext: cfg: MachineConfig; run_id: str; gpt: Gpt; data: DataLayer; manifest: dict[str, ManifestEntry]
                  case_dir: Path; artifacts_dir: Path; previous: RunRecord | None   # previous = baseline, for MetricResult.previous
def preflight(cfg: MachineConfig, cases: list[Case], data: DataLayer) -> list[str]   # problems; empty = OK
def foreign_java_running(own_pids: set[int]) -> list[str]
def run(cfg: MachineConfig, toolbox: Path, label: str, features: list[str] | None, resume: str | None,
        skip_efficiency: bool, baseline_only: bool) -> RunRecord
```

- CLI: `sv run --label L [--toolbox toolboxes/microwave] [--features a,b] [--resume RUN_ID]
  [--skip-efficiency] [--baseline-only]`.
- Flow, per spec §5:
  1. Preflight.
  2. Correctness pass: chain at `standard_heap_mb`; peers; metrics; verdicts; plots written to
     `work_dir/<run_id>/<case>/artifacts/`.
  3. Efficiency pass: two timed runs, then `min_heap_search` with
     `run_at = chain at xmx + outputs_match against the correctness output`.
  4. `write_run`.
- A case's `.complete` marker is written **after** its metrics and artifacts are on disk.
- `--baseline-only` writes the record with `baseline_only=True`, and verdicts never gate.
- Correctness functions for a case are looked up by `case.kind` in a registry
  (`sv/runner.py: CASE_KINDS: dict[str, Callable[[Case, RunContext], CaseRecord]]`). Tasks 13–17
  register theirs. `efficiency_only` cases skip the correctness pass and get only the efficiency pass
  (spec §7.2).

- [ ] **Step 1: Write the failing tests.**
  - `test_preflight_reports_missing_data_docker_and_foreign_java`.
  - `test_resume_reruns_case_without_marker` (Review Focus 2).
  - `test_marker_written_last`: kill the run after the metrics but before the marker, and the case
    reruns.
  - `test_timing_rerun_when_foreign_java_appears` (Review Focus 4): check before each timed run;
    contaminated timings are discarded and re-measured once, then marked `note="contaminated"`.
  - `test_feature_filter`.
  - `test_efficiency_only_case_measured_without_metrics`.
  - `test_baseline_only_never_fails`.
  - `test_run_record_written_with_provenance`: build, peers' digests, settings fingerprint.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Checkpoint.**

## Phase C: Report

### Task 12: House-style charts and the report renderer

**Files:**
- Create: `sv/report/charts.py`, `sv/report/render.py`, `sv/report/templates/report.html.j2`,
  `sv/report/templates/style.css` (taken from the mockup's CSS), `sv/report/pdf.py`
- Modify: `sv/cli.py` (`sv report [--run RUN_ID] [--pdf] [--publish]`)
- Test: `tests/test_report.py`

**Interfaces:**
- Consumes: `RunRecord`, `find_baseline`, `trend` (Task 10).
- Produces:

```python
# charts.py: each returns an inline <svg> string (matplotlib, SVG backend, house palette, Arial)
def sparkline(values: list[float], highlight_last: bool, bad: bool) -> str
def heap_bars(values: list[float]) -> str
def cr_scatter(points: dict[str, list[tuple[float, float]]], limit_m: float) -> str    # site -> (range_err, az_err)
def diff_histogram(diff: np.ndarray, bias: float, unit: str) -> str
def diff_map(diff: np.ndarray, vmax: float) -> str                                      # diverging TEAL–white–CORAL, decimated to ≤ 800 px
def history_chart(labels: list[str], wall_min: list[float], heap_gb: list[float]) -> str
# render.py
def render_report(run: RunRecord, baseline: RunRecord | None, runs: list[RunRecord], work_dir: Path, out: Path) -> Path
# pdf.py
def html_to_pdf(html: Path, pdf: Path) -> None     # Playwright Chromium, print background on, A4 landscape
def publish(run: RunRecord, html: Path, pdf: Path | None, reports_dir: Path) -> Path   # reports/releases/<label>/
```

- Layout and content follow the mockup exactly:
  - Front page: header band, run details strip, 4 summary cards, scorecard table, legend.
  - One detail section per feature: plot cards, metrics table with the limit's source and `status`,
    diagnosis notes, provenance.
- Metrics with `status: proposed` show a "proposed" tag next to the limit.
- A feature with a BASELINE efficiency verdict shows "baseline" instead of a delta.
- The output is a single self-contained HTML file: inline CSS and inline SVG, with no network fetches.

- [ ] **Step 1: Write the failing tests.**
  - `test_report_is_self_contained`: no `http`/`https` `src=` or `href=` except anchors.
  - `test_scorecard_row_per_feature_with_link_to_detail`.
  - `test_warn_and_fail_badges`.
  - `test_proposed_limit_tagged`.
  - `test_baseline_feature_shows_baseline_not_delta`.
  - `test_charts_use_only_house_palette`: parse the SVG fills and strokes against the palette plus the
    greys `#cfe0e6`, `#dbe3e7`, `#5f7480`.
  - `test_publish_copies_to_release_dir`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** Render from a synthetic `RunRecord` fixture modelled on the mockup's
  numbers.
- [ ] **Step 4: Run.** Expected: PASS. Then generate the report from the fixture, open it in a browser
  and compare it side by side with the mockup. **Show the user** both the HTML and the PDF before
  moving on.
- [ ] **Step 5: Checkpoint.**

## Phase D: First-wave features

Each feature task adds:
- its manifest entries (`sv data add`), case YAMLs and graphs;
- its metric functions, registered in `sv.metrics.ALL`;
- its case kind, registered in `CASE_KINDS`;
- the plots for its detail card;
- each limit with `source:` filled in by citation research and `status: proposed`. The user approves
  the limits after Task 18.

### Task 13: Corner reflector truth and measurement

**Files:**
- Create: `truth/cr/` (move the 4 CSVs and 3 KMZs from
  `microwave-toolbox/sar-test-stacks/src/test/resources/eu/esa/sar/teststacks/corner_reflectors/`),
  `sv/cr/reflectors.py`, `sv/cr/peak.py`, `sv/cr/metrics.py`
- Test: `tests/test_cr.py`

**Interfaces:**
- Produces:

```python
@dataclass(frozen=True)
class Reflector: id: str; lat: float; lon: float; h: float; side_m: float | None; az_deg: float | None; tilt_deg: float | None; rcs_dbsm: dict[str, float] | None
def load_jpl_csv(path: Path) -> list[Reflector]      # JPL "with_plate_motion" format
def load_ga_csv(path: Path) -> list[Reflector]       # GA Surat format; all-zero RCS columns -> rcs_dbsm=None
def trihedral_rcs_dbsm(side_m: float, wavelength_m: float, boresight_off_az_deg: float, boresight_off_el_deg: float) -> float
def find_peak(img: np.ndarray, expected_rc: tuple[float, float], half: int = 32, upsample: int = 8) -> tuple[float, float] | None
def geolocation_errors_m(measured_rc, expected_rc, spacing_rg_m, spacing_az_m) -> tuple[float, float]
def ale_2d_rms_m(errors: list[tuple[float, float]]) -> float
def integrated_energy_rcs_dbsm(slc_patch: np.ndarray, peak_rc, beta0_scale: float, pixel_area_m2: float) -> float
```

- `find_peak` ports `FindCRPosition`:
  1. Coarse maximum in the 65×65 patch around the expected position.
  2. Re-centre the patch on it and apply a separable Hamming window.
  3. 8× upsampling by **FFT zero-padding**.
  4. Fine maximum, mapped back as `coarse - half + fine / upsample`.
  5. `None` if the patch leaves the image.
- Trihedral RCS: σ_max = 4π a⁴ / (3 λ²) for a triangular trihedral with leg length `a`, reduced by the
  standard boresight-offset pattern. Cite the formula's source in the docstring.

- [ ] **Step 1: Write the failing tests.**
  - `test_jpl_csv_parses_side_length`.
  - `test_ga_zero_rcs_becomes_none`.
  - `test_find_peak_recovers_subpixel_sinc_target`: a synthetic 2-D sinc at (40.37, 51.62) is
    recovered within 0.02 px.
  - `test_find_peak_none_at_edge`.
  - `test_trihedral_rcs_at_boresight`: a = 2.4384 m at the S1 wavelength (λ = 0.05546576 m) matches
    the closed form.
  - `test_integrated_energy_on_synthetic_target`: a known-energy target is recovered within 0.05 dB.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Checkpoint.**

### Task 14: Java CR reference, then the Calibration and GRD → TC corner reflector cases

**Files:**
- Create: `toolboxes/microwave/reference/java_cr_shifts.json`;
  `toolboxes/microwave/cases/calibration_{rosamond,surat}_slc.yml`;
  `toolboxes/microwave/cases/grd_tc_{rosamond,surat}_grd.yml`; their graphs; `sv/cases/cr_case.py`
  (case kind `cr`)
- Test: `tests/test_cr_case.py`

**Interfaces:**
- Consumes: Task 13; `chain_for(..., "graph")`; `CASE_KINDS`.
- Produces: the case kind `cr`, with artifacts `cr_scatter.svg`, `cr_points.json` and, for SLC,
  `rcs_bias.svg`.
- Metrics:
  - `ale_2d_rms_m`;
  - `ale_range_m`, `ale_azimuth_m`;
  - for Rosamond SLC only: `rcs_bias_db` and `rcs_scatter_db`. Surat is geolocation-only (spec §6).

- [ ] **Step 1: Record the Java reference.** In microwave-toolbox, run the four `CRValidation*` tests
  with `-Denable.long.tests=true -Pcorner-reflectors`. Parse the printed tables (`ID CR_x CR_y
  exp_CR_x exp_CR_y xShift yShift`) into `java_cr_shifts.json`, keyed by test and reflector id. The
  scenes are the ones the Java tests name (`S1/corner_reflectors/JPL/Rosamond/...`,
  `.../GA/Surat/...`). Add them to the manifest.
- [ ] **Step 2: Write the failing tests.**
  - `test_cr_case_reproduces_java_shifts_within_0p1_px` (`-m "snap and data"`): every reflector in all
    four cases.
  - `test_cr_case_registers_metrics_and_artifacts` (fake gpt plus a synthetic product).
- [ ] **Step 3: Implement.** Write the graphs so they match the Java tests' operator chains.
- [ ] **Step 4: Run** both tests. Expected: PASS.
- [ ] **Step 5: Wire up.** Add the cases to `features.yaml`. `grd_tc_rosamond_grd` is the
  `efficiency: true` case for `grd_tc`, and `calibration_rosamond_slc` for `calibration`.
- [ ] **Step 6: Checkpoint.**

### Task 15: OPERA RTC-S1 case (γ0 against OPERA)

**Files:**
- Create: `truth/opera/rtc.py`; `toolboxes/microwave/cases/grd_tc_opera_rtc.yml`; its graph (Calibration
  β0 → Terrain-Flattening → Terrain-Correction onto the OPERA grid); `sv/cases/raster_compare_case.py`
  (case kind `raster_compare`)
- Test: `tests/test_opera.py`

**Interfaces:**
- Produces:

```python
def load_opera_rtc(h5_or_tif: Path, pol: str) -> tuple[np.ndarray, Affine, CRS]
def reproject_to(src: Path, band: str, dst_transform: Affine, dst_crs: CRS, shape: tuple[int, int]) -> np.ndarray
def db_bias_rmse(a_lin: np.ndarray, b_lin: np.ndarray, mask: np.ndarray) -> tuple[float, float]
```

- The case kind `raster_compare` writes `diff_map.svg` and `diff_hist.svg`.
- Metrics: `gamma0_bias_db` (limit ±0.3, `within`) and `gamma0_rmse_db`.

- [ ] **Step 1: Choose inputs.** Find an OPERA RTC-S1 burst product whose source GRD/SLC is already in
  `E:\TestData` or on ASF. Prefer one over Rosamond, to share data with Task 14. Add the product and
  its source scene to the manifest.
- [ ] **Step 2: Write the failing tests.**
  - `test_db_bias_rmse_known_offset`: an offset of 0.5 dB gives bias 0.5 and RMSE 0.5.
  - `test_reproject_identity`.
  - `test_mask_excludes_nodata_and_layover`.
  - `test_opera_case_end_to_end` (`-m "snap and data"`).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Checkpoint.**

### Task 16: TOPS InSAR (classical chain) against ISCE3/COMPASS

**Files:**
- Create: `toolboxes/microwave/cases/tops_insar_venezuela_classic.yml`, modelled on
  `legacy/insar_classic_vs_gslc_venezuela.yml` but with engines `snap-classic` and `isce3`;
  `sv/cases/peer_ifg_case.py` (case kind `peer_ifg`)
- Test: `tests/test_peer_ifg_case.py`

**Interfaces:**
- Consumes: `chain_for(..., "classic")`, `PeerEngine("isce3")`, `sv.metrics.peer.*`.
- Metrics:
  - `circular_std_multilooked` (limit 0.1 rad, `below`), restricted to coherence > 0.7;
  - coherence bias (new: `coherence_bias(snap_coh, peer_coh, mask) -> Metric` in
    `sv/metrics/peer.py`);
  - `esd_residual_px`, read from SNAP's ESD metadata in the output `.dim`.
- Artifacts: `diff_map.svg` (phase difference) and `diff_hist.svg`.

- [ ] **Step 1: Write the failing tests.**
  - `test_coherence_bias_known_offset`.
  - `test_esd_residual_read_from_dim` (a committed small `.dim` fixture).
  - `test_coherence_mask_applied_to_circular_std`.
  - `test_peer_ifg_case_end_to_end` (`-m "snap and docker and data"`).
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Wire up.** This case is the `efficiency: true` case for `tops_insar`.
- [ ] **Step 6: Checkpoint.**

### Task 17: GSLC cases

**Files:**
- Create: `toolboxes/microwave/cases/gslc_venezuela_vs_compass.yml` (from
  `legacy/gslc_s1_venezuela_orbit106.yml`); `gslc_venezuela_ifg.yml` (from
  `legacy/insar_s1_venezuela_orbit106.yml`); `gslc_vs_classic_coherence.yml`; `gslc_cr_etad.yml` (case
  kind `cr`, GSLC graph with ETAD)
- Test: `tests/test_gslc_cases.py`

**Interfaces:**
- Consumes: `peer_ifg` (Task 16), `cr` (Task 14), `chain_for(..., "gslc")`.
- Metrics:
  - CR ALE with ETAD (limit 0.2 m);
  - phase agreement vs COMPASS (the legacy metrics and tolerances, carried over);
  - `coherence_not_worse_than_classic`: GSLC mean coherence ≥ classical mean coherence − 0.01, on the
    common valid mask.

- [ ] **Step 1: Choose the CR-with-ETAD scene.** Rosamond or Surat, with a matching ETAD product. Add
  both to the manifest.
- [ ] **Step 2: Write the failing tests.**
  - `test_legacy_cases_load_under_new_schema`.
  - `test_coherence_not_worse_than_classic_rule`.
  - `test_gslc_cases_end_to_end` (`-m "snap and docker and data"`).
- [ ] **Step 3: Implement.** Delete `toolboxes/microwave/cases/legacy/` once every legacy case either
  has a successor or is recorded as dropped in `README.md`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Wire up.** `gslc_venezuela_ifg` is the `efficiency: true` case for `gslc`.
- [ ] **Step 6: Checkpoint.**

## Phase E: Data, baselines, first report, toolbox clean-up

### Task 18: Upload the first-wave data and propose thresholds

- [ ] **Step 1:** `sv data verify`. Expected: empty (every manifest entry present locally with a
  matching sha256).
- [ ] **Step 2:** `sv data upload`. Then `sv data verify` against a scratch `roots` folder, forcing
  download from `s3://skywatch-image-processing-test/SNAPTestData/`. Expected: all downloaded and
  verified.
- [ ] **Step 3:** Write `docs/thresholds.md`: one row per metric with its limit, direction, citation
  and rationale. **Stop for the user** to approve. Flip the approved limits to `status: approved`.
- [ ] **Step 4: Checkpoint.**

### Task 19: Baselines and the first report

- [ ] **Step 1:** Install SNAP 13.0 (and 12.0 if available) side by side. For each, run
  `sv run --label 13.0.0 --baseline-only` with `snap_home` pointing at that install. A feature that
  cannot run on an old release gets no point (spec §7.1). Record any per-version graph override in the
  case.
- [ ] **Step 2:** Run `sv run --label <current 14.x build>` overnight. Expected: a complete
  `history/<run_id>.json`.
- [ ] **Step 3:** `sv report --pdf`. Review it with the user against the mockup. Fix any layout issues
  in Task 12's templates.
- [ ] **Step 4: Checkpoint.**

### Task 20: microwave-toolbox clean-up

**Files (in `E:\ESA\microwave-toolbox`):**
- Modify: `microwavetbx-benchmark/src/test/java/eu/esa/microwave/benchmark/Benchmark.java`. Replace
  `DISABLE_BENCHMARKS = true` with `!Boolean.getBoolean("benchmark.enable")`. Add the memory readings
  from memory spec §5.1: `MemoryProbe` with `peakLiveMB`, `allocMB`, `gcPauseMs` and `gcShare` in
  `writeResults`.
- Delete: `sar-test-stacks/.../corner_reflectors/**` (Java tests plus resources), **only after Task 14
  Step 4 passed**; the `-Pcorner-reflectors` profile in `sar-test-stacks/pom.xml`; `validation/`
  (replace it with `validation/README.md` pointing to `snap-validation`).
- Modify: `docs/roadmap.md` §9.1/§9.4 and the 2026-11-17 validation spec §6. Each gets a one-line
  pointer to the 2026-11-20 design.
- Test: `microwavetbx-benchmark/src/test/java/eu/esa/microwave/benchmark/MemoryProbeTest.java`

- [ ] **Step 1: Write the failing test** `MemoryProbeTest`, annotated `@STTM("SNAP-NNNN")` with the
  ticket from the branch name; ask the user if the branch has none.
  - `testAllocationMeasured`: allocating 64 MB inside the probe reports `allocMB >= 64`.
  - `testDisabledByDefault`: without `-Dbenchmark.enable=true`, `run()` returns without executing.
- [ ] **Step 2: Run** `mvn -pl microwavetbx-benchmark test -Dtest=MemoryProbeTest`. Expected: FAIL.
- [ ] **Step 3: Implement** `MemoryProbe` and the property switch.
- [ ] **Step 4: Run.** Expected: PASS. Then run `mvn -pl sar-test-stacks -am test` after the deletions.
  Expected: BUILD SUCCESS, with the stack tests unaffected.
- [ ] **Step 5: Checkpoint.** List the changed files; the user commits.
