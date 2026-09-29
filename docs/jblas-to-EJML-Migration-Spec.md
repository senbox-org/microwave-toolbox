# jblas → EJML Migration Spec

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the dormant `org.jblas` dependency from `jlinda` and `sar-op-*` entirely, replacing it with EJML (real linear algebra) plus a small project-owned complex-array type (element-wise complex work), with no change in numerical output and no regression in wall-clock performance.

**Architecture:** Split the jblas surface by kind of work. (1) *Real linear algebra* — the small normal-equation solves and Cholesky/least-squares — moves to **EJML `DMatrixRMaj`**, following the pattern already used in `CPM.java`. (2) *Complex matrix work* — filtering, interferogram formation, coherence, offset tracking — is almost entirely element-wise over interleaved `[re,im]` data and is **not** linear algebra; it moves to a project-owned `DComplexMatrix` class backed by an interleaved `double[]`, reproducing exactly the jblas `ComplexDoubleMatrix` API subset in use. (3) *FFTs* stay on JTransforms (already a separate dependency; **not** part of this migration) — but the JTransforms **bridge is not a free swap**: jblas is column-major and `SpectralUtils` exploits that (explicit `transpose()`→`.data` dances, `System.arraycopy` on the raw `[re,im]` buffer). A row-major `DComplexMatrix` inverts those assumptions, so the FFT bridge gets its own dedicated task (Task 8a) validated against the existing `SpectralUtilsTest` dim1/dim2 oracle — do not treat it as mechanical.

**Tech Stack:** Java 21, Maven, EJML `ejml-ddense` 0.44.0 (already a managed dependency in `snap-engine`, exported as a public OSGi package from `snap-core`), JUnit 4, JTransforms (unchanged).

## Global Constraints

- **Scope = only what we use.** Do **not** reimplement the full jblas API. The "jblas API surface actually used" table below is the exhaustive list — port exactly those symbols and nothing more. The `DComplexMatrix`/`DComplex` shim reproduces only the `ComplexDoubleMatrix`/`ComplexDouble` methods that appear in the codebase; if a method isn't called anywhere, it isn't written. YAGNI — every task's parity test is the definition of "enough."
- **EJML version:** `org.ejml:ejml-ddense:0.44.0` only — the exact version already pinned in `snap-engine/pom.xml`. Do **not** introduce `ejml-zdense`, `ejml-simple`, or `ejml-all` without an explicit dependency-management entry; keep the footprint to `ejml-ddense`.
- **No new native dependencies.** The whole point is to remove native BLAS/LAPACK loading. Nothing added may ship `.so`/`.dll`/`.dylib` or libgfortran.
- **Numerical parity is a target to measure, not a guarantee to assert.** Aim for `1e-9` absolute on `double` paths and `1e-4` on `float`-truncated tile writes (the tolerances the existing tests already use — do not relax them). But note that switching solvers/factorizations (e.g. `dsysv`→Cholesky) legitimately changes rounding; if a migrated unit lands just outside `1e-9`, investigate whether the *reference* test was over-fitted to jblas's exact arithmetic before touching the code, and record the measured delta rather than silently widening the bound.
- **JTransforms stays.** Files that import only `edu.emory.mathcs.jtransforms.*` and no `org.jblas.*` are out of scope and must not be touched.
- **OSGi/NBM (critical — do NOT re-export `org.ejml.*`):** `snap-core` already declares `ejml-ddense` and exports `org.ejml.*` as a public NBM package, and jlinda-core already depends on `snap-core`. **Proof it already works:** `CPM.java` imports and uses `org.ejml` today with *zero* ejml entries in `jlinda-core/pom.xml` — EJML resolves transitively. Therefore modules must **not** add `<publicPackage>org.ejml.*</publicPackage>` (two NBM modules exporting the same package is a duplicate-export conflict) and must **not** bundle a second copy of the jar. Removing the `org.jblas.*` export happens in Task 10; nothing new is exported.
- **License headers:** every new `.java` file gets the standard SkyWatch GPLv3 header used across the toolbox (copy verbatim from any existing file, e.g. `TileUtilsDoris.java` lines 1–15).
- **Do not commit until a task's tests pass.** Follow the user's global rule: the human runs `git commit`; the plan's "Commit" steps stage and describe, they do not push.

---

## Background: exact current usage

### jblas dependency wiring
- `microwave-toolbox/pom.xml` — `<jblas.version>1.2.6-SNAP</jblas.version>` and the `org.jblas:jblas` dependencyManagement entry (lines ~46, ~454–457).
- `jlinda/jlinda-core/pom.xml` — direct dependency + `<publicPackage>org.jblas.*</publicPackage>` re-export (lines ~38, ~55).
- `jlinda/jlinda-nest/pom.xml` — direct dependency (lines ~58–59).
- `E:\ESA\jblas` — a vendored `1.2.6-SNAP` fork built in-tree. Removed at the end (Task 10).

### jblas API surface actually used (the complete list)

| jblas symbol | Kind | Used in | EJML / shim replacement |
|---|---|---|---|
| `DoubleMatrix` | real dense | ~20 files | `org.ejml.data.DMatrixRMaj` |
| `Solve.solve` | LU solve `Ax=b` | InterferogramOp, SarUtils, FindCRPosition(test) | `LinearSolverFactory_DDRM.lu(n)` or `CommonOps_DDRM.solve` |
| `Solve.solveSymmetric` | symmetric solve | PolyUtils, Slant2Height | `LinearSolverFactory_DDRM.symmPosDef(n)` (AᵀA is SPD) |
| `Solve.solvePositive` | Cholesky solve | Baseline | `LinearSolverFactory_DDRM.chol(n)` |
| `Solve.solveLeastSquares` | LS solve | Gaofen3ProductDirectory | `LinearSolverFactory_DDRM.leastSquares(m,n)` |
| `Solve.pinv` | pseudo-inverse | FindCRPosition(test) | `CommonOps_DDRM.pinv(A, out)` |
| `Decompose.cholesky` | Cholesky factor | Baseline | `DecompositionFactory_DDRM.chol(n,true)` |
| `DoubleMatrix.eye(n)` | identity | PolyUtils, Slant2Height, Baseline | `CommonOps_DDRM.identity(n)` |
| `.transpose().mmul(x)` | AᵀB | PolyUtils, Baseline, InterferogramOp, LinearAlgebraUtils | `CommonOps_DDRM.multTransA(A,B,C)` |
| `.mmul` / `.mmuli` | matmul | many | `CommonOps_DDRM.mult(A,B,C)` |
| `.mul` / `.muli` / `.div` / `.divi` | element-wise (Hadamard) mul/div | PolyUtils:79, filters | `CommonOps_DDRM.elementMult(a,b,out)` / `.elementDiv` (confirmed present in 0.44) |
| `.sub` / `.add` (matrix) | element add/sub | many | `CommonOps_DDRM.subtract` / `.add` |
| `.sub(double)` / `.div(double)` scalar | scalar shift/scale | PolyUtils.normalize:34 | `CommonOps_DDRM.subtract(m,s,out)` / `CommonOps_DDRM.scale(1/s,m)` |
| `DoubleMatrix.concatHorizontally` | horizontal concat | PolyUtils:83 | `CommonOps_DDRM.concatColumns(a,b,out)` |
| `.putColumn/getColumn/putRow/getRow` | row/col access | PolyUtils, SpectralUtils, many | `CommonOps_DDRM.extractColumn/extract` + `insert` |
| `.dup()` / `.copy()` | copy | many | `m.copy()` / `new DMatrixRMaj(m)` |
| `.toArray2()` → `double[][]` | export | tests (LinearAlgebraUtilsTest, TopoPhaseTest…) | **no EJML equivalent** — add `double[][]`↔`DMatrixRMaj` helper (row-major); verify orientation with a test |
| `DoubleMatrix.ones/zeros` | factories | tests, code | `CommonOps_DDRM.fill(m,1.0)` / `new DMatrixRMaj(r,c)` |
| `DoubleMatrix.randn` | gaussian fill | SarUtilsTest | `RandomMatrices_DDRM.fillGaussian(m,0,1,rand)` (ejml-ddense) |
| `.normmax()` / `.max()` of abs | max\|.\| | PolyUtils, Baseline | `CommonOps_DDRM.elementMaxAbs` |
| `MatrixFunctions.pow/powi/sqrt/abs/cos/sin` | element-wise | PolyUtils, SarUtils, filters, DInSAR, Coherence, Interferogram | `ElementMath` helper (Task 3) |
| `Geometry.center` | column-mean centering | CoregistrationUtils | `ElementMath.center` helper (Task 3) |

> **Not jblas — do not "migrate":** `LinearAlgebraUtils.invertChol`, `solve22`, `solve33`, `matrixPower(double[][])` are hand-rolled `double[][]`/`DoubleMatrix` algorithms (`LinearAlgebraUtils.java:110–183`), **not** jblas calls. For these, swap only the `DoubleMatrix` container to `DMatrixRMaj` (field renames `rows`→`numRows`, `get/put` unchanged) and **keep the algorithm** — do not substitute an EJML solver, which would change the numerics.
| `ComplexDoubleMatrix` | complex dense | ~20 files | `DComplexMatrix` shim (Task 4) |
| `ComplexDouble` | complex scalar | filters, TileUtilsDoris, LUT, PhaseFilter | `DComplex` value type (Task 4) |
| `FloatMatrix` | float dense | SarUtilsTest, filter tests, DataReader, TileUtilsDoris | `FMatrixRMaj` (EJML) or `float[]` — see Task 6 |

### Files in scope (main sources)

`jlinda-core`: `Baseline`, `Orbit`, `io/DataReader`, `geocode/Slant2Height`, `geocode/DInSAR`, `utils/{PolyUtils, SarUtils, MathUtils, WeightWindows, LinearAlgebraUtils, SpectralUtils, TileUtilsDoris}`, `filtering/{AzimuthFilter, RangeFilter, PhaseFilter, PhaseFilterUtils, SlcDataFilter, ProductDataFilter}`, `coregistration/{SimpleLUT, LUT, utils/CoregistrationUtils, cross/CrossGeometry}`. (`coregistration/CPM` already uses EJML — reference only.)

`jlinda-nest`: `gpf/{SubtRefDemOp, Slant2HeightOp, RangeFilterOp, PhaseFilterOp, AzimuthFilterOp, DInSAROp}`.

`sar-op-insar`: `gpf/{InterferogramOp, CoherenceOp, OffsetTrackingOp, coregistration/CrossCorrelationOp}`. (`PCAOp` uses Jama, not jblas — out of scope.)

`sar-op-sentinel1`: `gpf/{RangeShiftOp, SpectralDiversityOp, etadcorrectors/ETADUtils}`.

`sar-io`: `gaofen3/Gaofen3ProductDirectory`.

Test sources mirroring the above (must migrate with their production class): `PolyUtilsTest`, `SpectralUtilsTest`, `SarUtilsTest`, `MathUtilsTest`, `LinearAlgebraUtilsTest`, `WeightWindowsTest`, `Slant2HeightTest`, `DInSARTest`, filter tests, `CoregistrationUtilsTest`, `CrossGeometryTest`, `FindCRPosition`.

### Two facts that de-risk the whole migration
1. **No large dense LAPACK anywhere.** Every `Solve.*`/`Decompose.*` call operates on the normal matrix `N = AᵀA`, sized (numCoeffs × numCoeffs): 6×6 for a degree-2 2-D polynomial, ~21×21 at degree 5. At this size native BLAS is *slower* than pure Java (JNI marshalling dominates), so EJML is expected to match or beat jblas.
2. **`ComplexDoubleMatrix` is not doing complex linear algebra** — no complex solve/SVD/eig. It is interleaved `double[]` element-wise math plus a JTransforms FFT container. That is exactly what the `DComplexMatrix` shim provides, which is why we do **not** need EJML's thinner `ZMatrixRMaj` module.

### Key risks (call these out during review of each task)
- **Storage order — bigger than it looks.** jblas is **column-major** (verified: `jblas/DoubleMatrix.java:331` "Data must be stored by column"; `ComplexDoubleMatrix` is `[re,im]` interleaved, `2*rows*columns`, `ComplexDoubleMatrix.java:82`). EJML `DMatrixRMaj` is **row-major**. `.get(r,c)`/`.set(r,c,v)` semantics match, so *index-based* code ports safely — but direct `.data` access is **pervasive, not rare**: ~375 field-access hits across 39 files (`SpectralUtils` 45, `LinearAlgebraUtils` 49, `SarUtils` 32, `LUT` 22). Every such site is storage-order-sensitive and must be audited, not swapped. The worst offender is `SpectralUtils` (see Task 8a).
- **Field renames are churn, not a footnote.** jblas exposes public `rows`, `columns`, `data`; EJML exposes `numRows`, `numCols`, `data`. Every `.rows`→`.numRows`, `.columns`→`.numCols`, and every raw `.data` read must be touched. This is broad field-level rewriting across the ~39 files above — size tasks accordingly; it is **not** an import-swap.
- **`new DoubleMatrix(double[][])` / `.toArray2()`.** These reorder between column-major storage and `double[][]` and are used widely in tests. EJML has `new DMatrixRMaj(double[][])` (row-major) but **no `toArray2()`** — add a small `double[][]`↔`DMatrixRMaj` helper and verify orientation with a test.
- **Complex Hadamard/conjugate multiply.** The single most common complex op is conjugate-multiply (interferogram) and phase-ramp multiply. The shim must implement `muli`/`mul`, `conji`, `addi`, `real()`, `imag()` faithfully — these are the correctness-critical methods. (`mmul` may not be needed at all — see Task 4.)
- **`float` matrices.** `FloatMatrix` is used only in tile push/pull and a few tests. Prefer plain `float[]`/`double[]` at those call sites rather than pulling in EJML's `FMatrixRMaj`, to avoid a second matrix type.
- **Solver change ⇒ parity is a hypothesis, not a guarantee.** `Solve.solveSymmetric` is LAPACK `dsysv` (symmetric-**indefinite** LDLᵀ); mapping it to `symmPosDef`/`chol` changes the factorization and therefore the rounding. `AᵀA` is SPD so it *should* hold at `1e-9`, but treat the tolerance as something each task **measures**, not asserts up front. See [[project_cpm_datasnooping_outlier_regression]] and [[project_envisat_orbit_reader_singleton_race]] for prior non-determinism traps here.
- **Do not reach for `ZMatrixRMaj` ops.** The `ZMatrixRMaj` *type* is on the classpath (ships in `ejml-core`), but the complex operation classes (`CommonOps_ZDRM`) are in `ejml-zdense`, which is **not** a dependency and the Global Constraints forbid adding it. So EJML complex *operations* are unavailable — this is exactly why the complex path is the project-owned shim, not EJML.

---

## Task 1: Confirm EJML is on the classpath (mostly a verification, not a wiring change)

EJML already reaches `jlinda-core` transitively through `snap-core` (proven by `CPM.java`). This task **verifies** that and adds a *compile-scope* dependency **only** to modules that turn out not to inherit it — it does **not** add bundling dependencies and does **not** touch `<publicPackages>` (see the OSGi/NBM constraint).

**Files:**
- Possibly modify (only if `dependency:tree` shows EJML absent): `sar-op-insar/pom.xml`, `sar-op-sentinel1/pom.xml`, `sar-io/pom.xml`.
- Do **not** modify `jlinda-core`/`jlinda-nest` poms here (they get EJML from `snap-core`; jblas removal is Task 10).

**Interfaces:**
- Produces: `org.ejml.data.DMatrixRMaj` et al. resolvable on the compile classpath of every in-scope module.

- [ ] **Step 1: Verify jlinda already sees EJML.** Run: `mvn -q -pl jlinda/jlinda-core dependency:tree | grep ejml`
Expected: `org.ejml:ejml-ddense:jar:0.44.0` present (transitive via `snap-core`). If present, jlinda needs no pom change.

- [ ] **Step 2: Check the operator modules.** Run the same `dependency:tree | grep ejml` for `sar-op-insar`, `sar-op-sentinel1`, `sar-io`.

- [ ] **Step 3: Only for a module where EJML is absent**, add a non-bundling dependency (scope `provided`, since the class is supplied at runtime by the `snap-core` NBM module):

```xml
<dependency>
    <groupId>org.ejml</groupId>
    <artifactId>ejml-ddense</artifactId>
    <scope>provided</scope>
</dependency>
```

- [ ] **Step 4: Confirm a throwaway `import org.ejml.data.DMatrixRMaj;` compiles** in each module (e.g. add to an existing test, compile, remove). Expected: compiles.

- [ ] **Step 5: Commit** (only if any pom changed)

```bash
git add microwave-toolbox/sar-op-insar/pom.xml microwave-toolbox/sar-op-sentinel1/pom.xml microwave-toolbox/sar-io/pom.xml
git commit -m "build: ensure ejml-ddense on compile classpath (provided) where not inherited"
```

---

## Task 2: Real-matrix migration of the flagship — `PolyUtils`

`PolyUtils` is the template for every real-matrix file. Migrate it first and in full; later real-matrix tasks copy its idioms.

**Files:**
- Modify: `jlinda/jlinda-core/src/main/java/org/jlinda/core/utils/PolyUtils.java`
- Test: `jlinda/jlinda-core/src/test/java/org/jlinda/core/utils/PolyUtilsTest.java`

**Interfaces:**
- Consumes: `ElementMath.pow` is **not yet available** — for Task 2, inline the vector power (integer exponent) as a local loop; refactor to `ElementMath` in Task 3.
- Produces: no signature change. `polyFit(...)`, `polyFit2D(...)`, `polyval*(...)` keep their current signatures and return types (`double[]`).

- [ ] **Step 1: Pin the current behavior with a characterization test.** Add to `PolyUtilsTest` (before touching production code) a test that fits a known polynomial and asserts coefficients to `1e-9`:

```java
@Test
public void testPolyFit1D_parity() {
    DoubleMatrix t = new DoubleMatrix(new double[]{0,1,2,3,4,5});
    DoubleMatrix y = new DoubleMatrix(new double[]{1,3,7,13,21,31}); // 1 + x + x^2
    double[] c = PolyUtils.polyFit(t, y, 2);
    Assert.assertArrayEquals(new double[]{1,1,1}, c, 1e-9);
}
```

- [ ] **Step 2: Run it against the current jblas implementation to establish the baseline**

Run: `mvn -q -pl jlinda/jlinda-core test -Dtest=PolyUtilsTest#testPolyFit1D_parity`
Expected: PASS (this is the reference behavior we must preserve).

- [ ] **Step 3: Replace the jblas body.** Swap imports and rewrite `polyFit`/`polyFit2D`. Reference conversion for the solve block (lines 88–98):

```java
// jblas (before):
// DoubleMatrix N = A.transpose().mmul(A);
// DoubleMatrix rhs = A.transpose().mmul(y);
// DoubleMatrix x = Solve.solveSymmetric(N, rhs);
// DoubleMatrix Qx_hat = Solve.solveSymmetric(N, DoubleMatrix.eye(N.getRows()));
// double maxDeviation = (N.mmul(Qx_hat).sub(DoubleMatrix.eye(Qx_hat.rows))).normmax();

// EJML (after):
DMatrixRMaj N = new DMatrixRMaj(nUnknowns, nUnknowns);
CommonOps_DDRM.multTransA(A, A, N);            // N = A^T A
DMatrixRMaj rhs = new DMatrixRMaj(nUnknowns, 1);
CommonOps_DDRM.multTransA(A, y, rhs);          // rhs = A^T y

LinearSolverDense<DMatrixRMaj> solver = LinearSolverFactory_DDRM.symmPosDef(nUnknowns);
if (!solver.setA(N.copy())) throw new IllegalStateException("polyfit: singular normal matrix");
DMatrixRMaj x = new DMatrixRMaj(nUnknowns, 1);
solver.solve(rhs, x);

DMatrixRMaj Qx_hat = new DMatrixRMaj(nUnknowns, nUnknowns);
solver.invert(Qx_hat);                          // inv(N)

DMatrixRMaj chk = new DMatrixRMaj(nUnknowns, nUnknowns);
CommonOps_DDRM.mult(N, Qx_hat, chk);
CommonOps_DDRM.subtractEquals(chk, CommonOps_DDRM.identity(nUnknowns));
double maxDeviation = CommonOps_DDRM.elementMaxAbs(chk);
```

Imports:

```java
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.factory.LinearSolverFactory_DDRM;
import org.ejml.interfaces.linsol.LinearSolverDense;
```

- [ ] **Step 4: Run the parity test plus the full existing PolyUtilsTest**

Run: `mvn -q -pl jlinda/jlinda-core test -Dtest=PolyUtilsTest`
Expected: PASS, all cases, within existing tolerances.

- [ ] **Step 5: Commit**

```bash
git add microwave-toolbox/jlinda/jlinda-core/src/main/java/org/jlinda/core/utils/PolyUtils.java microwave-toolbox/jlinda/jlinda-core/src/test/java/org/jlinda/core/utils/PolyUtilsTest.java
git commit -m "refactor(jlinda): migrate PolyUtils from jblas to EJML"
```

---

## Task 3: Element-wise math helper `ElementMath`

Centralizes the `MatrixFunctions.*` and `Geometry.center` element-wise operations that EJML does not provide, so downstream files call one helper instead of re-implementing loops.

**Files:**
- Create: `jlinda/jlinda-core/src/main/java/org/jlinda/core/utils/ElementMath.java`
- Test: `jlinda/jlinda-core/src/test/java/org/jlinda/core/utils/ElementMathTest.java`

**Interfaces:**
- Produces:
  - `static DMatrixRMaj pow(DMatrixRMaj m, double exp)` — element-wise, returns new matrix
  - `static void sqrt(DMatrixRMaj m)` / `abs(DMatrixRMaj m)` — in place
  - `static DMatrixRMaj cos(DMatrixRMaj m)` / `sin(DMatrixRMaj m)` — returns new matrix
  - `static void center(DMatrixRMaj m)` — subtract per-column mean, in place (matches `org.jblas.Geometry.center`)

- [ ] **Step 1: Write failing tests** in `ElementMathTest`:

```java
@Test public void testPow() {
    DMatrixRMaj m = new DMatrixRMaj(new double[][]{{2,3}});
    DMatrixRMaj r = ElementMath.pow(m, 2);
    Assert.assertArrayEquals(new double[]{4,9}, r.data, 1e-12);
}
@Test public void testCenterColumns() {
    DMatrixRMaj m = new DMatrixRMaj(new double[][]{{1},{3}});
    ElementMath.center(m);                 // mean 2 -> {-1, 1}
    Assert.assertArrayEquals(new double[]{-1,1}, m.data, 1e-12);
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl jlinda/jlinda-core test -Dtest=ElementMathTest`
Expected: FAIL — `ElementMath` does not exist.

- [ ] **Step 3: Implement `ElementMath`** (GPLv3 header; loops over `m.data`; `pow` uses `Math.pow`, `cos/sin/sqrt/abs` map to `java.lang.Math`; `center` computes column means row-major-aware via `m.get(r,c)`).

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn -q -pl jlinda/jlinda-core test -Dtest=ElementMathTest`
Expected: PASS.

- [ ] **Step 5: Refactor PolyUtils' inlined power (from Task 2) to use `ElementMath.pow`; rerun `PolyUtilsTest`.**

Run: `mvn -q -pl jlinda/jlinda-core test -Dtest=PolyUtilsTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add microwave-toolbox/jlinda/jlinda-core/src/main/java/org/jlinda/core/utils/ElementMath.java microwave-toolbox/jlinda/jlinda-core/src/test/java/org/jlinda/core/utils/ElementMathTest.java microwave-toolbox/jlinda/jlinda-core/src/main/java/org/jlinda/core/utils/PolyUtils.java
git commit -m "feat(jlinda): add ElementMath helper for element-wise ops"
```

---

## Task 4: `DComplexMatrix` / `DComplex` shim (the complex core)

Drop-in replacement for **only the jblas `ComplexDoubleMatrix`/`ComplexDouble` methods the codebase calls** — backed by an interleaved `double[]` (`[re0,im0,re1,im1,...]`, row-major). This is a data container plus element-wise ops — **not** linear algebra. The Interfaces block below is the complete method list derived from grepping actual call sites; do not add methods speculatively. Before writing, re-run `grep -rn "ComplexDoubleMatrix\|ComplexDouble" microwave-toolbox --include=*.java` and reconcile the method set — drop any interface member that turns out to have no caller (e.g. `mmul`; see Task 4 Self-Review note).

**Files:**
- Create: `jlinda/jlinda-core/src/main/java/org/jlinda/core/utils/complex/DComplexMatrix.java`
- Create: `jlinda/jlinda-core/src/main/java/org/jlinda/core/utils/complex/DComplex.java`
- Test: `jlinda/jlinda-core/src/test/java/org/jlinda/core/utils/complex/DComplexMatrixTest.java`

**Interfaces (must match jblas semantics exactly — this is the full set the call sites need, incl. `SpectralUtils`):**
- `DComplexMatrix(int rows, int cols)` — zero-filled
- `DComplexMatrix(DMatrixRMaj real, DMatrixRMaj imag)`
- **Public fields matching jblas names:** `public double[] data` (interleaved `[re,im]`), `public int rows, columns, length` — `SpectralUtils` reads `.data`, `.rows`, `.columns` directly and even reassigns `.data`, so these are fields, not just getters. `length` = `rows*columns` (element count, as in jblas).
- `DComplex get(int r, int c)` / `void put(int r, int c, DComplex v)` / `void put(int r,int c,double re,double im)`
- `DMatrixRMaj real()` / `DMatrixRMaj imag()` — extracted copies
- `DComplexMatrix dup()` / `void copy(DComplexMatrix src)` / `boolean isVector()`
- `DComplexMatrix transpose()` — **required by `SpectralUtils.fft2D`**; must preserve the interleaved layout
- `DComplexMatrix muli(DComplexMatrix b)` / `mul(DComplexMatrix b)` — element-wise (Hadamard), in place / copy
- `DComplexMatrix addi(DComplexMatrix b)`
- `DComplexMatrix conji()` — in-place conjugate
- `DComplexMatrix getRow(int r)` / `getColumn(int c)` / `putRow(int r, DComplexMatrix v)` / `putColumn(int c, DComplexMatrix v)`
- `DComplexMatrix mmul(DComplexMatrix b)` — complex matrix product. **Verify it is actually called** (grep `AzimuthFilter`/`RangeFilter`); jblas `.mmul` on complex may be element-wise diagonal use. If no genuine matrix-product caller exists, **drop it** (Self-Review).
- `DComplex` value type: `double real, imag`; `mul`, `add`, `conj`, `abs`.

> **Storage-order decision (affects Task 8a).** The shim is **row-major** `[re,im]`. jblas's `ComplexDoubleMatrix` is **column-major**. `SpectralUtils`'s existing `transpose()` dances were written to convert jblas column-major into the row-major flat buffer JTransforms expects — so once the shim is row-major, those transposes must be re-derived (some removed), which is exactly why `SpectralUtils` is split out into Task 8a rather than bulk-converted in Task 8.

- [ ] **Step 1: Write failing tests** covering the correctness-critical ops (conjugate multiply == interferogram kernel):

```java
@Test public void testConjugateMultiply() {
    DComplexMatrix a = new DComplexMatrix(1,1); a.put(0,0,1,2);   // 1+2i
    DComplexMatrix b = new DComplexMatrix(1,1); b.put(0,0,3,4);   // 3+4i
    DComplexMatrix ifg = a.mul(b.dup().conji());                  // a * conj(b) = 11 + 2i
    Assert.assertEquals(11.0, ifg.get(0,0).real, 1e-12);
    Assert.assertEquals( 2.0, ifg.get(0,0).imag, 1e-12);
}
@Test public void testRealImagRoundTrip() {
    DMatrixRMaj re = new DMatrixRMaj(new double[][]{{1,2}});
    DMatrixRMaj im = new DMatrixRMaj(new double[][]{{3,4}});
    DComplexMatrix m = new DComplexMatrix(re, im);
    Assert.assertArrayEquals(re.data, m.real().data, 1e-12);
    Assert.assertArrayEquals(im.data, m.imag().data, 1e-12);
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl jlinda/jlinda-core test -Dtest=DComplexMatrixTest`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Implement `DComplex` then `DComplexMatrix`** (GPLv3 header). Storage `double[] data` length `2*rows*cols`, index `(r*cols+c)*2`. `mmul` is a triple loop with complex accumulate (matrices here are small/structured; a naive loop is adequate — do not over-engineer). Match jblas rounding order in `mmul` (accumulate real and imag separately).

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q -pl jlinda/jlinda-core test -Dtest=DComplexMatrixTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add microwave-toolbox/jlinda/jlinda-core/src/main/java/org/jlinda/core/utils/complex/ microwave-toolbox/jlinda/jlinda-core/src/test/java/org/jlinda/core/utils/complex/
git commit -m "feat(jlinda): add DComplexMatrix/DComplex shim for complex element-wise math"
```

---

## Task 5: Migrate the complex bridge — `TileUtilsDoris`

Everything that reads/writes complex tiles funnels through here, so migrate it right after the shim exists. Index-based `.get/.put(r,c)` ports cleanly (no storage-order trap).

**Files:**
- Modify: `jlinda/jlinda-core/src/main/java/org/jlinda/core/utils/TileUtilsDoris.java`
- Test: add `TileUtilsDorisTest` (round-trip tile → matrix → tile) if none exists.

**Interfaces:**
- Consumes: `DComplexMatrix`, `DComplex` (Task 4); `DMatrixRMaj` (EJML).
- Produces: same method names, with `ComplexDoubleMatrix` → `DComplexMatrix` and `DoubleMatrix` → `DMatrixRMaj` in signatures. **This changes public signatures used by ~15 downstream files** — those are updated in Tasks 7–9. List the new signatures here so downstream tasks can rely on them:
  - `DComplexMatrix pullComplexDoubleMatrix(Tile t1, Tile t2)`
  - `DMatrixRMaj pullDoubleMatrix(Tile t)`
  - `void pushComplexDoubleMatrix(DComplexMatrix data, Tile tI, Tile tQ, Rectangle rect)`
  - `void pushDoubleMatrix(DMatrixRMaj data, Tile t, Rectangle rect)` (+ overloads)
  - `float[]`-based push replaces the `FloatMatrix` overloads (see Task 6).

- [ ] **Step 1: Write a round-trip parity test** (fill two tiles with known I/Q, pull to `DComplexMatrix`, push back, assert equality at `1e-4` float tolerance).

- [ ] **Step 2: Run to verify it fails to compile / fails** (method still returns jblas type).

- [ ] **Step 3: Rewrite** using `result.put(y, x, re, im)`, `data.real()`/`data.imag()` → `DMatrixRMaj.get(r,c)`. Direct index math is identical.

- [ ] **Step 4: Run**

Run: `mvn -q -pl jlinda/jlinda-core test -Dtest=TileUtilsDorisTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add microwave-toolbox/jlinda/jlinda-core/src/main/java/org/jlinda/core/utils/TileUtilsDoris.java microwave-toolbox/jlinda/jlinda-core/src/test/java/org/jlinda/core/utils/TileUtilsDorisTest.java
git commit -m "refactor(jlinda): migrate TileUtilsDoris to DComplexMatrix/DMatrixRMaj"
```

---

## Task 6: Retire `FloatMatrix`

`FloatMatrix` appears only in tile push/pull overloads (`TileUtilsDoris`, `DataReader`) and a few tests. Replace with plain `float[]` at those sites rather than adding EJML `FMatrixRMaj`.

**Files:**
- Modify: `jlinda/jlinda-core/src/main/java/org/jlinda/core/utils/TileUtilsDoris.java` (float overloads)
- Modify: `jlinda/jlinda-core/src/main/java/org/jlinda/core/io/DataReader.java`
- Modify: tests referencing `FloatMatrix` (`SarUtilsTest`, `RangeFilterTest`, `PhaseFilterTest`, `AzimuthFilterTest`)

- [ ] **Step 1:** Grep for remaining `FloatMatrix` references: `grep -rn "FloatMatrix" microwave-toolbox --include=*.java`.
- [ ] **Step 2:** Replace each with `float[]` (or `double[]`) accessors; delete the `pushFloatMatrix(FloatMatrix,...)` overload, keep the `float[]`/`DMatrixRMaj` ones.
- [ ] **Step 3:** Run: `mvn -q -pl jlinda/jlinda-core test` — Expected: PASS.
- [ ] **Step 4: Commit** `refactor(jlinda): remove FloatMatrix usage`.

---

## Task 7: Migrate remaining `jlinda-core` real-matrix files

Mechanical, using the PolyUtils/CPM idioms and `ElementMath`. One task per file *would* be ideal; group them here since each is small and shares one test run. Migrate in this dependency order (leaves first):

`MathUtils` → `WeightWindows` → `LinearAlgebraUtils` → `SarUtils` → `Baseline` → `geocode/Slant2Height` → `Orbit` → `coregistration/{CrossGeometry, SimpleLUT, CoregistrationUtils}`.

**Reference conversion for `Baseline` (lines 323, 333–336) — mind what is and isn't jblas.**
Only `Decompose.cholesky(...)` and `Solve.solvePositive(...)` are jblas. `LinearAlgebraUtils.invertChol(...)` is the module's **own** hand-rolled inverse (see `LinearAlgebraUtils.java:152-183`) — migrate *its* container in Task 7's `LinearAlgebraUtils` step and keep the algorithm; do not replace it with an EJML inverse here.

```java
// jblas parts (before):
// DoubleMatrix cholFactor = Decompose.cholesky(nMatrix).transpose();   // <-- jblas
// DoubleMatrix Qx_hat = LinearAlgebraUtils.invertChol(cholFactor);     // <-- NOT jblas: keep
// rhsBperp = Solve.solvePositive(nMatrix, rhsBperp);                   // <-- jblas

// EJML (after) — replace only the jblas calls:
CholeskyDecomposition_F64<DMatrixRMaj> chol = DecompositionFactory_DDRM.chol(nMatrix.numRows, false); // upper, matches .transpose()
if (!chol.decompose(nMatrix.copy())) throw new IllegalStateException("Baseline: non-SPD normal matrix");
DMatrixRMaj cholFactor = chol.getT(null);
DMatrixRMaj Qx_hat = LinearAlgebraUtils.invertChol(cholFactor);         // unchanged hand-rolled inverse

// solvePositive(nMatrix, rhs) -> Cholesky solve:
LinearSolverDense<DMatrixRMaj> solver = LinearSolverFactory_DDRM.chol(nMatrix.numRows);
if (!solver.setA(nMatrix.copy())) throw new IllegalStateException("Baseline: non-SPD normal matrix");
solver.solve(rhsBperp, rhsBperp);   // repeat for rhsBpar, rhsTheta, rhsThetaInc (setA once, reuse)
```

Extra imports for this block: `import org.ejml.dense.row.factory.DecompositionFactory_DDRM;` and `import org.ejml.interfaces.decomposition.CholeskyDecomposition_F64;`. **Parity note:** confirm the upper/lower convention (`chol(n,false)` vs the old `.transpose()`) reproduces the pre-migration `BaselineTest` values; if it lands just outside `1e-9`, check the reference before widening (per Global Constraints).

- [ ] **Step 1 (per file): Run the file's existing test first to capture baseline** (e.g. `-Dtest=SarUtilsTest`). Expected: PASS on jblas.
- [ ] **Step 2 (per file):** Swap imports; convert per the mapping table and reference blocks; route element-wise ops through `ElementMath`.
- [ ] **Step 3 (per file):** Run the file's test. Expected: PASS at existing tolerance.
- [ ] **Step 4:** After the group compiles, run the whole module: `mvn -q -pl jlinda/jlinda-core test`. Expected: PASS.
- [ ] **Step 5: Commit** per file (`refactor(jlinda): migrate <File> to EJML`).

---

## Task 8: Migrate remaining `jlinda-core` complex files

Uses `DComplexMatrix` (Task 4) and the migrated `TileUtilsDoris` (Task 5). Order: `filtering/{PhaseFilterUtils, PhaseFilter, AzimuthFilter, RangeFilter, SlcDataFilter, ProductDataFilter}` → `geocode/DInSAR` → `coregistration/LUT` → `utils/CoregistrationUtils`. (**`SpectralUtils` is deliberately excluded here** — it is the storage-order-sensitive FFT bridge and gets Task 8a.)

- [ ] **Step 1 (per file): capture baseline test.**
- [ ] **Step 2 (per file): convert** `ComplexDoubleMatrix`→`DComplexMatrix`, `ComplexDouble`→`DComplex`, `MatrixFunctions.cos/sin`→`ElementMath.cos/sin` used to build the complex matrix. Field access on the **shim** stays `.rows`/`.columns` (it keeps jblas names); only **real** `DMatrixRMaj` operands switch to `.numRows`/`.numCols`.
- [ ] **Step 3 (per file): run test.** Expected: PASS.
- [ ] **Step 4: Run whole module** `mvn -q -pl jlinda/jlinda-core test`. Expected: PASS.
- [ ] **Step 5: Commit** per file.

---

## Task 8a: Migrate `SpectralUtils` — the storage-order-sensitive FFT bridge

**This is the highest-risk file in the migration and must not be lumped with the mechanical complex files.** `SpectralUtils` does not go through `get/put`; it manipulates the raw interleaved buffer directly and was written around jblas's **column-major** layout:
- `fft1D_inplace`/`invfft1D` hand `vector.data` straight to `DoubleFFT_1D.complexForward/Inverse` (`SpectralUtils.java:30,35,40,46`).
- `fft2D_inplace` does `A.transpose()` → `fft2d.complexForward(aTemp.data)` → `A.data = aTemp.transpose().data` (`:101-105`); `invfft2D_inplace` mirrors it (`:122-124`). These transposes exist **only** to reconcile column-major storage with JTransforms' row-major expectation.
- `fftshift`/`ifftshift` `System.arraycopy` on `.data` using `cplxMatrixLength = 2*length` and hard-coded start offsets (`:139-145`, `:187-196`).

Because `DComplexMatrix` is **row-major**, the correct migration is **not** to copy the code — it is to re-derive it: for a row-major interleaved buffer, the per-dimension FFT and the 2-D transform simplify (the transpose dances likely disappear or invert). The `SpectralUtilsTest` dim1/dim2/2-D expected matrices are the oracle that proves you got it right.

**Files:**
- Modify: `jlinda/jlinda-core/src/main/java/org/jlinda/core/utils/SpectralUtils.java`
- Test: `jlinda/jlinda-core/src/test/java/org/jlinda/core/utils/SpectralUtilsTest.java` (already exists with exact expected matrices — do not weaken its assertions)

- [ ] **Step 1: Run the existing `SpectralUtilsTest` on jblas to lock the oracle.** Run: `mvn -q -pl jlinda/jlinda-core test -Dtest=SpectralUtilsTest`. Expected: PASS. These expected matrices encode the dim1-vs-dim2 orientation — they are the contract.
- [ ] **Step 2: Add `DComplexMatrix.data`/`rows`/`columns` usage** (fields already defined in Task 4). Rewrite `fft1D`/`invfft1D` to pass `vector.data` (1-D interleaved is layout-agnostic — should be a near-straight swap).
- [ ] **Step 3: Rewrite `fft2D_inplace`/`invfft2D_inplace` for row-major.** Do **not** transliterate the transpose dance; derive the correct row-major sequence and rely on Step 1's test to confirm. If a transpose is still needed, it must be justified against the row-major layout, not copied from the jblas version.
- [ ] **Step 4: Rewrite `fftshift`/`ifftshift`** for the row-major interleaved buffer; re-derive the start offsets (the jblas `+1`/`-1` offsets are layout-specific).
- [ ] **Step 5: Run `SpectralUtilsTest`.** Run: `mvn -q -pl jlinda/jlinda-core test -Dtest=SpectralUtilsTest`. Expected: PASS at the **existing** tolerances. A dim1/dim2 swap in the output is the signature of a storage-order bug — if you see it, the 2-D transform is wrong.
- [ ] **Step 6: Commit** `refactor(jlinda): migrate SpectralUtils FFT bridge to DComplexMatrix (row-major)`.

---

## Task 9: Migrate the operator modules

Depends on a fully-migrated `jlinda-core`. Files: `jlinda-nest/gpf/*` (6 files), `sar-op-insar/gpf/{InterferogramOp, CoherenceOp, OffsetTrackingOp, coregistration/CrossCorrelationOp}`, `sar-op-sentinel1/gpf/{RangeShiftOp, SpectralDiversityOp, etadcorrectors/ETADUtils}`, `sar-io/gaofen3/Gaofen3ProductDirectory`, and the test `sar-test-stacks/.../corner_reflectors/FindCRPosition.java` (`ComplexDoubleMatrix.zeros`, `.toArray2()`, `Solve.pinv`, `Solve.solve`) — verify `sar-test-stacks/pom.xml` sees EJML (`dependency:tree`; add `provided` if not).

**Reference — `InterferogramOp.estimateFlatEarthPolynomial` (line 1186):** identical pattern to PolyUtils Task 2 (`Solve.solve(N, rhs)` → `LinearSolverFactory_DDRM.lu` or `CommonOps_DDRM.solve`). **`Gaofen3ProductDirectory`:** `Solve.solveLeastSquares` → `LinearSolverFactory_DDRM.leastSquares(m,n)`.

- [ ] **Step 1:** Confirm these modules' EJML classpath was settled in Task 1 (`dependency:tree | grep ejml`); add a `provided` dep only if still missing. Do **not** add `<publicPackage>org.ejml.*</publicPackage>`.
- [ ] **Step 2 (per file): capture baseline**, convert, run the module's tests.
- [ ] **Step 3:** Run the operator benchmark smoke test: `mvn -q -pl microwavetbx-benchmark test -Dtest=TestBenchmark_InSAR` (see Benchmarks section for the real-data run). Expected: PASS.
- [ ] **Step 4: Commit** per file.

---

## Task 10: Remove jblas entirely

Only after Tasks 2–9 are green and `grep -rn "org.jblas" microwave-toolbox --include=*.java` returns nothing.

**Files:**
- Modify: `microwave-toolbox/pom.xml` (remove `jblas.version` + dependencyManagement entry)
- Modify: `jlinda/jlinda-core/pom.xml`, `jlinda/jlinda-nest/pom.xml` (remove dependency + `org.jblas.*` publicPackage)
- Modify: `microwavetbx-benchmark/src/test/java/eu/esa/microwave/benchmark/TestMatrices.java` (drop the jblas cases — the EJML/shim cases were added in Benchmarks Layer A)
- Delete: `E:\ESA\jblas` vendored module (and any parent-pom module reference)

- [ ] **Step 1:** `grep -rn "org.jblas" microwave-toolbox --include=*.java` → Expected: no matches. If any remain, they belong to a skipped task — stop and migrate them.
- [ ] **Step 2:** Remove all jblas pom entries and the vendored module.
- [ ] **Step 3:** Full reactor build + test: `mvn -q -pl jlinda/jlinda-core,jlinda/jlinda-nest,sar-op-insar,sar-op-sentinel1,sar-io -am test`. Expected: PASS.
- [ ] **Step 4:** Confirm no native libs remain on the classpath: `mvn -q -pl jlinda/jlinda-core dependency:tree | grep -i jblas` → Expected: empty.
- [ ] **Step 5: Commit** `chore: remove jblas dependency and vendored module`.

---

## Benchmarks

Two layers, both required. Layer A is authored and run as part of this migration (extends the existing `microwavetbx-benchmark` module). Layer B is the acceptance gate on real data.

### Layer A — micro-benchmarks (jblas vs EJML vs shim)

**Module dep:** add `ejml-ddense` (test scope) to `microwavetbx-benchmark/pom.xml` if `dependency:tree` shows it absent — the new EJML/shim cases need it at test compile time.

**Timing discipline (do this or the small-matrix numbers are noise):** the current `TestMatrices` uses raw loop timing with `iterations = 1` (`TestMatrices.java:29`) — useless for 6×6 matrices where JIT warmup and per-iteration allocation dominate. Either (a) use JMH (preferred: add `jmh-core`/`jmh-generator-annprocess`, `@Benchmark` methods, `Blackhole` to defeat dead-code elimination), or (b) at minimum add a warmup loop (≥10k iters discarded), hoist all allocation out of the timed loop, consume results via a running checksum, and time with `System.nanoTime()`. Also **fix a pre-existing mislabel**: the current "matrixMult" cases call jblas `.mul` (element-wise Hadamard), not `.mmul` (matrix product) — relabel them and add genuine `.mmul` cases so the report measures what it claims.

**File:** rework `TestMatrices.java` accordingly. It already covers add/multiply for Commons-Math, Jama, and jblas at sizes 3 and 100. Add:

1. **EJML `DMatrixRMaj`** rows for add and multiply (parallel to the existing three libraries).
2. **`DComplexMatrix`** rows for complex element-wise multiply and conjugate-multiply.
3. **Operation-representative micro-benchmarks** matching real jlinda call shapes, not just add/mult:
   - `solveSymmetric` on `N=AᵀA` at the true sizes: **6×6, 15×15, 21×21** (poly degrees 2/4/5) — jblas `Solve.solveSymmetric` vs EJML `symmPosDef`.
   - `cholesky` at those sizes — jblas `Decompose.cholesky` vs EJML `chol`.
   - complex conjugate-multiply over a **tile-sized** matrix (e.g. 512×512 and 1024×1024) — jblas `ComplexDoubleMatrix` vs `DComplexMatrix` (this is the interferogram kernel).

Run: `mvn -q -pl microwavetbx-benchmark test -Dtest=TestMatrices`. Emit results through the existing `BenchmarkReport` harness.

**Acceptance thresholds (Layer A):**
- Small solves/Cholesky (≤21×21): EJML **≥** jblas throughput (expected faster — no JNI). Fail the gate if EJML is >10% slower.
- Tile-sized complex conjugate-multiply: `DComplexMatrix` within **20%** of jblas wall-clock (memory-bandwidth-bound; parity expected).
- Numerical: every micro-benchmark cross-checks EJML/shim output against jblas at `1e-9`.

**Projected results (to be replaced by measured numbers when run — these set expectations, not acceptance):**

| Operation | Size | jblas (native) | EJML / shim (Java) | Expected ratio |
|---|---|---|---|---|
| `solveSymmetric` | 6×6 | baseline | **~0.3–0.7×** time | EJML faster (JNI dominates jblas) |
| `solveSymmetric` | 21×21 | baseline | **~0.6–0.9×** time | EJML faster/parity |
| `cholesky` | 15×15 | baseline | **~0.5–0.9×** time | EJML faster/parity |
| conj-multiply | 1024×1024 | baseline | **~0.9–1.2×** time | parity (bandwidth-bound) |
| dense matmul | 1000×1000 | baseline | **~3–8×** time | **jblas faster** — but this shape does **not** occur in jlinda |

The last row is the honest caveat: if a large dense matmul existed, native would win. The usage inventory above confirms none does, which is why the migration is expected to be perf-neutral-to-positive on the real workload.

### Layer B — real-data end-to-end (the acceptance gate)

Uses the existing benchmark module with real scenes. The graphs are already present:
- `microwavetbx-benchmark/.../graphs/Sentinel1-TOPS-Coregistration.xml`
- `microwavetbx-benchmark/.../graphs/Sentinel1-TOPS-Coregistration-Ifg.xml`
driven by `TestBenchmark_InSAR` (and `TestBenchmark_ETAD` for the ETAD path that touches `ETADUtils`).

**Procedure:**
1. **Before** (on the pre-migration commit): run `TestBenchmark_InSAR` + `TestBenchmark_ETAD` against the standard test scenes (via `TestData.inputSAR`; see [[project_microwave_testdata_layout]]). Record wall-clock per graph from `BenchmarkReport`, and archive the output products.
2. **After** (post-Task 10): rerun identically.
3. **Compare:**
   - **Wall-clock:** end-to-end coregistration+interferogram time within **±5%** of baseline (no regression).
   - **Numerical:** output interferogram phase and coherence bands must match the archived baseline. Use a band-difference check; require max abs phase diff `< 1e-4` rad and coherence diff `< 1e-4`. Any larger diff must be explained by solver-rounding and reviewed against the parity budget, not waved through.

**Commands:**
```bash
# baseline (before migration)
git stash && mvn -q -pl microwavetbx-benchmark test -Dtest=TestBenchmark_InSAR,TestBenchmark_ETAD
# after migration
mvn -q -pl microwavetbx-benchmark test -Dtest=TestBenchmark_InSAR,TestBenchmark_ETAD
```

**Layer B is the release gate.** Layer A guides implementation; Layer B decides whether the migration ships.

---

## Self-Review

- **Spec coverage:** every jblas symbol in the usage table maps to a task (real → Tasks 2/3/7/9; complex → Tasks 4/5/8/8a/9; float → Task 6; removal → Task 10; benchmarks → Layer A/B). Every in-scope file is named in Tasks 5–9, including `SpectralUtils` (8a) and `FindCRPosition` (9).
- **Storage order** is treated as a first-class hazard: called out in Global Constraints + Key Risks, isolated into Task 8a for the FFT bridge, and flagged for `.toArray2()`/`new DoubleMatrix(double[][])` sites.
- **OSGi/NBM:** Task 1 no longer adds a duplicate `org.ejml.*` export or a bundling dependency — EJML is inherited from `snap-core` (proven by `CPM.java`); only `provided`-scope compile deps are added where a module doesn't inherit it.
- **Algorithm vs container:** the mapping table's "Not jblas" note and the corrected Baseline block ensure hand-rolled routines (`LinearAlgebraUtils.invertChol`, `solve22/33`) keep their algorithm and only swap the container.
- **Type consistency:** `DComplexMatrix`/`DComplex`/`ElementMath`/`DMatrixRMaj` names are used identically across Tasks 3–9; the new `TileUtilsDoris` signatures are declared in Task 5's Interfaces block and consumed unchanged in Tasks 8–9. Note the deliberate asymmetry: the shim keeps jblas field names (`rows`/`columns`) while EJML real matrices use `numRows`/`numCols`.
- **Open item to confirm during Task 4:** whether the few `mmul` complex uses (filters) need a genuine complex matrix product or are effectively diagonal/element-wise — if all are element-wise, `DComplexMatrix.mmul` can be dropped, shrinking the shim. Verify against `AzimuthFilter`/`RangeFilter` before finalizing the shim API.
- **Parity is measured, not assumed:** solver/factorization swaps (`dsysv`→Cholesky, upper/lower conventions) can shift rounding; each task records the measured delta and investigates the reference test before widening any bound.

## Execution Handoff

**Plan complete and saved to `microwave-toolbox/docs/jblas-to-EJML-Migration-Spec.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session with checkpoints for review.

**Which approach?**
