#!/usr/bin/env python3
"""Tests for gen_params.py (stdlib unittest).

    python -I tools/javahelp/test_gen_params.py
    python -I -m unittest discover -s tools/javahelp -p "test_gen_params.py"
"""
from __future__ import annotations

import contextlib
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:  # `python -I` drops the script folder from sys.path
    sys.path.insert(0, str(HERE))

import gen_params as gp  # noqa: E402

REPO = HERE.parents[1]
FIX = HERE / "test_fixtures"
SNAP_ENGINE = REPO.parent / "snap-engine"
GOLDSTEIN = REPO / "sar-op-insar/src/main/java/eu/esa/sar/insar/gpf/filtering/GoldsteinFilterOp.java"
SPECTRAL_DIVERSITY = REPO / "sar-op-sentinel1/src/main/java/eu/esa/sar/sentinel1/gpf/SpectralDiversityOp.java"
TERRAIN_CORRECTION = (REPO / "sar-op-sar-processing/src/main/java/eu/esa/sar/sar/gpf/geometric/"
                      "RangeDopplerGeocodingOp.java")
APPLY_ORBIT = REPO / "sar-io-ephemeris/src/main/java/eu/esa/sar/orbits/gpf/ApplyOrbitFileOp.java"
POL_SPECKLE = (REPO / "rstb/rstb-op-polarimetric-tools/src/main/java/org/csa/rstb/polarimetric/gpf/"
               "PolarimetricSpeckleFilterOp.java")

_ROOTS = None


def real_resolver() -> gp.ConstantResolver:
    global _ROOTS
    if _ROOTS is None:
        _ROOTS = gp.ConstantResolver(gp.default_roots(REPO))
    return _ROOTS


def by_name(op):
    return {p.name: p for p in op.params}


class TempRepo:
    """A throw-away workspace: <root>/mtb is the repo (fixture operators under src/main/java,
    pages, an override file) and <root>/snap-engine an optional sibling with external classes."""

    def __init__(self, pages: dict[str, bytes], overrides: dict[str, str],
                 java: dict[str, str] | None = None, engine: dict[str, str] | None = None):
        self.root = Path(tempfile.mkdtemp(prefix="gen_params_"))
        self.dir = self.root / "mtb"
        self.engine = self.root / "snap-engine"
        src = self.dir / "mod/src/main/java/eu/esa/sar/fixture"
        src.mkdir(parents=True)
        for f in FIX.glob("*.java"):
            shutil.copy(f, src / f.name)
        for name, body in (java or {}).items():
            (src / name).write_text(body, encoding="utf-8")
        for rel, body in (engine or {}).items():
            self.write_engine(rel, body)
        for rel, data in pages.items():
            p = self.dir / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(data)
        self.write_overrides(overrides)

    def write_engine(self, rel: str, body: str):
        p = self.engine / "mod/src/main/java" / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")

    def write_overrides(self, overrides: dict[str, str]):
        o = self.dir / "tools/javahelp/params_pages.txt"
        o.parent.mkdir(parents=True, exist_ok=True)
        o.write_text("".join(f"{a}|{p}\n" for a, p in overrides.items()), encoding="utf-8")

    def page(self, rel) -> Path:
        return self.dir / rel

    def cleanup(self):
        shutil.rmtree(self.root, ignore_errors=True)


EXT_JAVA = """package org.esa.ext;
public class Ext {
    public static final String NAME = "%s";
}
"""
EXT_OP = """package eu.esa.sar.fixture;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import %s.Ext;
@OperatorMetadata(alias = "%s")
public class %s extends Operator {
    @Parameter(valueSet = {Ext.NAME, "local"}, defaultValue = Ext.NAME, label = "External")
    private String ext = Ext.NAME;
}
"""


CRLF_HEAD = (b"<html>\r\n<head><title>T</title></head>\r\n<body>\r\n"
             b"<p>Hand-written caf\xc3\xa9 text about the dialog.</p>\r\n")
CRLF_TAIL = b"\r\n<p>More hand-written text.</p>\r\n<hr>\r\n</body>\r\n</html>\r\n"


class ParseTests(unittest.TestCase):

    def test_multiline_annotation_and_comments(self):
        op = gp.parse_operator(FIX / "MultilineFixtureOp.java", gp.ConstantResolver([FIX]))
        self.assertEqual(op.alias, "Multiline-Fixture")
        self.assertEqual(op.cls, "MultilineFixtureOp")
        self.assertEqual([p.name for p in op.params],
                         ["fftSize", "sourceBandNames", "useAlpha", "weights", "legacy", "separator",
                          "hiddenCounter"])
        p = by_name(op)
        fft = p["fftSize"]
        self.assertEqual(fft.type, "String")
        self.assertEqual(fft.value_set, ["32", "64", "128", "256"])
        self.assertEqual(fft.interval, "(0, 1]")
        self.assertEqual(fft.unit, "pixels")
        self.assertEqual(fft.default, "64")
        self.assertEqual(fft.label, "FFT Size")
        self.assertEqual(fft.description, 'FFT size used for the block filter '
                                          '(a power of two; {braces} and "quotes" inside a string)')
        self.assertEqual(p["sourceBandNames"].alias, "sourceBands")
        self.assertEqual(p["sourceBandNames"].type, "String[]")
        self.assertEqual(p["useAlpha"].type, "Boolean")
        self.assertEqual(p["useAlpha"].default, "true")
        self.assertEqual(p["weights"].type, "List<String>")
        self.assertTrue(p["weights"].not_null)
        self.assertEqual(p["weights"].pattern, "[0-9,]+")
        self.assertFalse(p["fftSize"].not_null)
        self.assertEqual(p["hiddenCounter"].default, "0")  # GPF zero default for a primitive without defaultValue
        self.assertEqual(p["legacy"].type, "double[]")
        self.assertEqual(p["legacy"].default, "0.5")
        self.assertEqual(p["separator"].default, "\\u0000")  # GPF zero for char, initializer ignored
        self.assertEqual(op.warnings, [])

    def test_resolve_same_class_constant(self):
        res = gp.ConstantResolver([FIX])
        path = FIX / "ConstantsFixtureOp.java"
        self.assertEqual(res.resolve("A", path), "x")
        self.assertEqual(res.resolve("C", path), "z")
        op = gp.parse_operator(path, res)
        est = by_name(op)["estimator"]
        self.assertEqual(est.value_set, ["x", "y", "z"])
        self.assertEqual(est.default, "y")

    def test_resolve_concat_inherited_enum_and_unresolved(self):
        res = gp.ConstantResolver([FIX])
        op = gp.parse_operator(FIX / "ConstantsFixtureOp.java", res)
        # the abstract base contributes its parameter first
        self.assertEqual([p.name for p in op.params], ["N", "estimator", "orbit", "mode", "inherited"])
        p = by_name(op)
        self.assertEqual(p["N"].default, "5")
        self.assertEqual(p["orbit"].value_set, ["x (Auto Download)", "Prefix base", "Unknown.VALUE"])
        self.assertEqual(p["orbit"].default, "x (Auto Download)")
        self.assertEqual(p["inherited"].default, "from-base")   # superclass constant, itself a concatenation
        self.assertEqual(p["mode"].default, "FAST")              # enum constant initializer -> its name
        self.assertEqual(len(op.warnings), 1)
        self.assertIn("Unknown.VALUE", op.warnings[0])
        self.assertIsNone(res.resolve("Unknown.VALUE", FIX / "ConstantsFixtureOp.java"))
        self.assertIsNone(gp.parse_operator(FIX / "BaseFixtureOp.java", res))  # abstract: skipped

    @unittest.skipUnless(SNAP_ENGINE.is_dir(), "needs ../snap-engine")
    def test_resolve_other_class_constant(self):
        res = real_resolver()
        # snap-engine-utilities/.../gpf/FilterWindow.java: public static final String SIZE_3x3 = "3x3";
        self.assertEqual(res.resolve("FilterWindow.SIZE_3x3", POL_SPECKLE), "3x3")
        self.assertEqual(res.resolve("org.esa.snap.engine_utilities.gpf.FilterWindow.SIZE_17x17",
                                     POL_SPECKLE), "17x17")
        op = gp.parse_operator(POL_SPECKLE, res)
        self.assertEqual(by_name(op)["targetWindowSizeStr"].value_set, ["3x3", "5x5"])
        self.assertEqual(by_name(op)["targetWindowSizeStr"].default, "3x3")

    def test_goldstein_real(self):
        op = gp.parse_operator(GOLDSTEIN, real_resolver())
        self.assertEqual(op.alias, "GoldsteinPhaseFiltering")
        self.assertEqual(len(op.params), 9)
        p = by_name(op)
        self.assertEqual(p["useAdaptiveAlpha"].default, "true")
        self.assertEqual(p["FFTSizeString"].value_set, ["32", "64", "128", "256"])
        self.assertEqual(p["FFTSizeString"].default, "64")
        self.assertEqual(p["coherenceThreshold"].default, "0.2")
        self.assertEqual(p["alpha"].interval, "(0, 1]")
        self.assertEqual(op.warnings, [])

    def test_spectral_diversity_commented_params_excluded(self):
        op = gp.parse_operator(SPECTRAL_DIVERSITY, real_resolver())
        names = [p.name for p in op.params]
        for gone in ("optObjective", "noOfCandidateSolutions", "optTolerance", "optMaxIterations"):
            self.assertNotIn(gone, names)
        self.assertIn("integrationMethod", names)
        im = by_name(op)["integrationMethod"]
        self.assertEqual(im.value_set, ["L1", "L2", "L1 and L2"])
        self.assertEqual(im.default, "L1 and L2")

    def test_resolve_in_repo_cross_file_constants(self):
        res = gp.ConstantResolver([REPO])  # this repo only: no snap-engine, no external cache
        op = gp.parse_operator(TERRAIN_CORRECTION, res)
        aux = by_name(op)["auxFile"]  # valueSet = {CalibrationOp.LATEST_AUX, ...} in sar-op-calibration
        self.assertEqual(aux.value_set, ["Latest Auxiliary File", "Product Auxiliary File",
                                         "External Auxiliary File"])
        self.assertEqual(aux.default, "Latest Auxiliary File")
        orbit = by_name(gp.parse_operator(APPLY_ORBIT, res))["orbitType"]
        self.assertEqual(orbit.default, "Sentinel Precise (Auto Download)")  # SentinelPODOrbitFile.PRECISE + "..."
        self.assertIn("DORIS Precise VOR (ENVISAT) (Auto Download)", orbit.value_set)

    def test_external_cache_resolves_without_snap_engine(self):
        res = gp.ConstantResolver([REPO], external=gp.load_external(REPO))
        op = gp.parse_operator(TERRAIN_CORRECTION, res)
        self.assertEqual(op.warnings, [])
        self.assertEqual(by_name(op)["demResamplingMethod"].default, "BILINEAR_INTERPOLATION")
        self.assertIsNone(gp.ConstantResolver([REPO]).resolve("ResamplingFactory.BILINEAR_INTERPOLATION_NAME",
                                                              TERRAIN_CORRECTION))

    @unittest.skipUnless(SNAP_ENGINE.is_dir(), "needs ../snap-engine")
    def test_external_cache_renders_like_snap_engine(self):
        cached = gp.ConstantResolver([REPO], external=gp.load_external(REPO))
        for path in (TERRAIN_CORRECTION, POL_SPECKLE):
            self.assertEqual(gp.render_block(gp.parse_operator(path, cached)),
                             gp.render_block(gp.parse_operator(path, real_resolver())))


class RenderTests(unittest.TestCase):

    def test_render_escapes_and_ascii(self):
        op = gp.parse_operator(FIX / "MultilineFixtureOp.java", gp.ConstantResolver([FIX]))
        op.params.append(gp.Param(name="mu", type="double", description="Scale in µm² (> 0)"))
        html = gp.render_table(op)
        self.assertTrue(html.isascii())
        self.assertTrue(html.startswith("<p>GPT operator: <code>Multiline-Fixture</code>. Generated from the "
                                        "operator source; parameter names are those used in GPT and graph "
                                        "XML.</p>"))
        self.assertIn("Use alpha &lt; 1 &amp; more", html)       # label stands in for missing description
        self.assertIn("&#181;m&#178; (&gt; 0)", html)
        self.assertIn("<code>sourceBands</code>", html)          # GPT name is the alias
        self.assertNotIn("<code>sourceBandNames</code>", html)
        self.assertIn("List&lt;String&gt;", html)
        self.assertIn("32, 64, 128, 256", html)
        self.assertNotIn("hiddenCounter", html)                 # "internal" in description
        rows = [l for l in html.splitlines() if l.startswith("<tr><td>")]
        self.assertEqual([r.split("<code>")[1].split("</code>")[0] for r in rows],
                         ["fftSize", "sourceBands", "useAlpha", "weights", "legacy", "separator", "mu"])

    def test_internal_filter_matches_leading_word_only(self):
        def visible(**kw):
            return bool(gp.Operator("X", "X", Path("X.java"), [gp.Param(name="p", **kw)]).visible_params)
        self.assertFalse(visible(label="Internal counter"))
        self.assertFalse(visible(description="internal: set by the graph builder"))
        self.assertFalse(visible(label="Hidden", description="  Internal use only"))
        self.assertTrue(visible(description="Uses internally cached values"))
        self.assertTrue(visible(label="Not internal"))
        self.assertTrue(visible(label="Internals of the filter"))


class PageTests(unittest.TestCase):

    def setUp(self):
        self.repos = []

    def tearDown(self):
        for r in self.repos:
            r.cleanup()

    def repo(self, pages, overrides, **kw):
        r = TempRepo(pages, overrides, **kw)
        self.repos.append(r)
        return r

    def test_update_preserves_outside_text_and_crlf(self):
        block = b'<!-- params:begin op="Multiline-Fixture" -->\r\nhand edit\r\n<!-- params:end -->'
        r = self.repo({"docs/Page.html": CRLF_HEAD + block + CRLF_TAIL},
                      {"Multiline-Fixture": "docs/Page.html"})
        changed = gp.update_pages(r.dir)
        self.assertEqual(changed, [r.page("docs/Page.html")])
        data = r.page("docs/Page.html").read_bytes()
        self.assertTrue(data.startswith(CRLF_HEAD + b'<!-- params:begin op="Multiline-Fixture" -->'))
        self.assertTrue(data.endswith(b"<!-- params:end -->" + CRLF_TAIL))
        self.assertNotIn(b"hand edit", data)
        self.assertIn(b"\r\n<h3>Processing parameters (reference)</h3>\r\n", data)  # one block: no alias suffix
        self.assertIn(b"<p>GPT operator: <code>Multiline-Fixture</code>. Generated", data)
        self.assertIn(b"<code>fftSize</code>", data)
        self.assertEqual(data.count(b"\n"), data.count(b"\r\n"))  # every newline is CRLF
        self.assertEqual(gp.update_pages(r.dir), [])             # idempotent

    def test_insert_position(self):
        footer_page = (b"<html><head><title>A</title></head><body>\n<p>Dialog text.</p>\n"
                       b"<!-- footer:begin -->\n<h3>Background</h3>\n<!-- footer:end -->\n<hr>\n</body></html>\n")
        hr_page = (b"<html><head><title>B</title></head><body>\n<hr>\n<p>Dialog text.</p>\n"
                   b"<HR>\n</body></html>\n")
        r = self.repo({"docs/A.html": footer_page, "docs/B.html": hr_page, "docs/C.html": hr_page},
                      {"Multiline-Fixture": "docs/A.html", "Constants-Fixture": "docs/B.html",
                       "Internal-Fixture": "docs/C.html"})
        self.assertEqual(gp.update_pages(r.dir), [])             # nothing to update without insert
        changed = gp.update_pages(r.dir, insert=True, allow_unresolved=True)  # Constants-Fixture: Unknown.VALUE
        self.assertEqual(sorted(changed), [r.page("docs/A.html"), r.page("docs/B.html")])
        self.assertEqual(r.page("docs/C.html").read_bytes(), hr_page)  # internal op: never inserted

        a = r.page("docs/A.html").read_text(encoding="utf-8")
        self.assertIn('<!-- params:end -->\n<!-- footer:begin -->', a)
        self.assertLess(a.index("<p>Dialog text.</p>"), a.index("<!-- params:begin"))
        self.assertNotIn("\r", a)

        b = r.page("docs/B.html").read_text(encoding="utf-8")
        begin = b.index('<!-- params:begin op="Constants-Fixture" -->')
        self.assertLess(b.index("<hr>"), begin)                   # not before the first <hr>
        self.assertIn("<!-- params:end -->\n<HR>\n</body>", b)    # but before the final one
        self.assertEqual(gp.update_pages(r.dir, insert=True, allow_unresolved=True), [])  # no second block

    def test_check_detects_drift(self):
        r = self.repo({"docs/Page.html": CRLF_HEAD + CRLF_TAIL}, {"Multiline-Fixture": "docs/Page.html"})
        gp.update_pages(r.dir, insert=True)
        self.assertEqual(gp.check(r.dir), [])
        page = r.page("docs/Page.html")
        page.write_bytes(page.read_bytes().replace(b"FFT size used", b"FFT size (edited by hand) used"))
        msgs = gp.check(r.dir)
        self.assertEqual(len(msgs), 1)
        self.assertIn("docs/Page.html", msgs[0])
        self.assertIn("Multiline-Fixture", msgs[0])

        r.write_overrides({"Multiline-Fixture": "docs/Page.html", "Gone-Operator": "docs/Page.html"})
        gp.update_pages(r.dir)
        msgs = gp.check(r.dir)
        self.assertEqual(len(msgs), 1)
        self.assertIn("Gone-Operator", msgs[0])

    def test_check_detects_broken_block(self):
        r = self.repo({"docs/Page.html": CRLF_HEAD + b'<!-- params:begin op="Nope" -->\r\n'
                                                     b'<!-- params:end -->' + CRLF_TAIL}, {})
        msgs = gp.check(r.dir)
        self.assertEqual(len(msgs), 1)
        self.assertIn("Nope", msgs[0])

    def test_checker_reports_params_category(self):
        import check_javahelp
        r = self.repo({"docs/Page.html": CRLF_HEAD + CRLF_TAIL}, {"Multiline-Fixture": "docs/Page.html"})
        gp.update_pages(r.dir, insert=True)
        page = r.page("docs/Page.html")
        page.write_bytes(page.read_bytes().replace(b"<code>fftSize</code>", b"<code>fft</code>"))
        checker = check_javahelp.Checker(r.dir, [])
        checker.check_params()
        self.assertEqual([(c, k) for c, k, _ in checker.errors],
                         [("params", "docs/Page.html:Multiline-Fixture")])

    def test_write_insert_skip_unresolved(self):
        r = self.repo({"docs/B.html": CRLF_HEAD + CRLF_TAIL}, {"Constants-Fixture": "docs/B.html"})
        skipped = []
        self.assertEqual(gp.update_pages(r.dir, insert=True, skipped=skipped), [])
        self.assertEqual(skipped, ["Constants-Fixture"])           # Unknown.VALUE is unresolved
        self.assertEqual(r.page("docs/B.html").read_bytes(), CRLF_HEAD + CRLF_TAIL)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(gp.main(["--repo", str(r.dir), "--insert"]), 0)
        self.assertIn("Constants-Fixture", out.getvalue())
        self.assertIn("Unknown.VALUE", out.getvalue())
        self.assertEqual(gp.update_pages(r.dir, insert=True, allow_unresolved=True), [r.page("docs/B.html")])
        page = r.page("docs/B.html")                              # an existing block is not rewritten either
        page.write_bytes(page.read_bytes().replace(b"Estimator used", b"Hand edit"))
        self.assertEqual(gp.update_pages(r.dir), [])
        self.assertIn(b"Hand edit", page.read_bytes())
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(gp.main(["--repo", str(r.dir), "--write", "--allow-unresolved"]), 0)
        self.assertNotIn(b"Hand edit", page.read_bytes())

    def test_check_compares_blocks_with_unresolved_expressions(self):
        r = self.repo({"docs/B.html": CRLF_HEAD + CRLF_TAIL}, {"Constants-Fixture": "docs/B.html"})
        gp.update_pages(r.dir, insert=True, allow_unresolved=True)
        self.assertEqual(gp.check(r.dir), [])
        page = r.page("docs/B.html")
        page.write_bytes(page.read_bytes().replace(b"Estimator used", b"Hand edit"))
        msgs = gp.check(r.dir)                                    # no snap-engine, yet still compared
        self.assertEqual(len(msgs), 1)
        self.assertIn("Constants-Fixture", msgs[0])

    def test_several_blocks_on_one_page_name_their_operator(self):
        r = self.repo({"docs/Page.html": CRLF_HEAD + CRLF_TAIL},
                      {"Multiline-Fixture": "docs/Page.html", "Constants-Fixture": "docs/Page.html"})
        gp.update_pages(r.dir, insert=True, allow_unresolved=True)
        data = r.page("docs/Page.html").read_text(encoding="utf-8")
        self.assertIn("<h3>Processing parameters (reference) &#8212; Multiline-Fixture</h3>", data)
        self.assertIn("<h3>Processing parameters (reference) &#8212; Constants-Fixture</h3>", data)
        self.assertIn("<p>GPT operator: <code>Constants-Fixture</code>.", data)
        self.assertEqual(gp.check(r.dir), [])
        self.assertEqual(gp.update_pages(r.dir, allow_unresolved=True), [])

    def test_refresh_external_and_stale_cache(self):
        r = self.repo({}, {}, java={"ExtFixtureOp.java": EXT_OP % ("org.esa.ext", "Ext-Fixture", "ExtFixtureOp")},
                      engine={"org/esa/ext/Ext.java": EXT_JAVA % "ext-value"})
        self.assertEqual(gp.refresh_external(r.dir), {"Ext.NAME": "ext-value"})
        cache = r.dir / gp.EXTERNAL
        self.assertEqual(json.loads(cache.read_text(encoding="utf-8")), {"Ext.NAME": "ext-value"})
        self.assertEqual(gp.check(r.dir), [])

        r.write_engine("org/esa/ext/Ext.java", EXT_JAVA % "changed")  # snap-engine moved on
        msgs = gp.check(r.dir)
        self.assertEqual(len(msgs), 1)
        self.assertIn("external_constants.json", msgs[0])
        self.assertIn("Ext.NAME", msgs[0])
        self.assertIn("--refresh-external", msgs[0])

        shutil.rmtree(r.engine)                                   # CI: no snap-engine, the cache answers
        op = gp.operators(r.dir)["Ext-Fixture"]
        self.assertEqual(op.warnings, [])
        self.assertEqual(op.params[0].default, "ext-value")
        self.assertEqual(op.params[0].value_set, ["ext-value", "local"])
        self.assertEqual(gp.check(r.dir), [])
        with self.assertRaises(SystemExit):
            gp.refresh_external(r.dir)                            # needs ../snap-engine

    def test_refresh_external_fails_on_ambiguous_name(self):
        r = self.repo({}, {}, java={"ExtFixtureOp.java": EXT_OP % ("org.esa.ext", "Ext-Fixture", "ExtFixtureOp"),
                                    "Ext2FixtureOp.java": EXT_OP % ("org.esa.other", "Ext2-Fixture",
                                                                    "Ext2FixtureOp")},
                      engine={"org/esa/ext/Ext.java": EXT_JAVA % "ext-value",
                              "org/esa/other/Ext.java": (EXT_JAVA % "other").replace("org.esa.ext",
                                                                                      "org.esa.other")})
        with self.assertRaises(ValueError) as cm:
            gp.refresh_external(r.dir)
        self.assertIn("Ext.NAME", str(cm.exception))
        self.assertFalse((r.dir / gp.EXTERNAL).exists())

    def test_cli_unknown_alias_and_duplicates(self):
        dup = (FIX / "InternalFixtureOp.java").read_text(encoding="utf-8").replace(
            "InternalFixtureOp", "InternalCopyOp")
        r = self.repo({}, {}, java={"InternalCopyOp.java": dup})
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(gp.main(["--repo", str(r.dir), "--write", "Nope"]), 2)
            self.assertEqual(gp.main(["--repo", str(r.dir), "--insert", "Multiline-Fixture", "Nope"]), 2)
        self.assertIn("Nope", err.getvalue())
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(gp.main(["--repo", str(r.dir), "--list"]), 0)
        self.assertIn("duplicate alias 'Internal-Fixture'", out.getvalue())

    def test_checker_runs_params_only_when_selected(self):
        import check_javahelp
        r = self.repo({}, {})
        calls = []
        real = gp.check
        gp.check = lambda repo, *a, **k: calls.append(repo) or []
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                check_javahelp.main(["--repo", str(r.dir), "--external", "none", "--only", "link,html"])
                self.assertEqual(calls, [])
                check_javahelp.main(["--repo", str(r.dir), "--external", "none", "--only", "params"])
                self.assertEqual(len(calls), 1)
                check_javahelp.main(["--repo", str(r.dir), "--external", "none"])
                self.assertEqual(len(calls), 2)
        finally:
            gp.check = real

    def test_page_map_known(self):
        m = gp.page_map(REPO)
        self.assertIn("GoldsteinPhaseFiltering", m)  # alias from @OperatorMetadata in GoldsteinFilterOp.java
        self.assertEqual(m["GoldsteinPhaseFiltering"].as_posix().split("/")[-3:],
                         ["docs", "operators", "GoldsteinFilterOp.html"])
        self.assertTrue(m["GoldsteinPhaseFiltering"].is_file())


if __name__ == "__main__":
    unittest.main()
