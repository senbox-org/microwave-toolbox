#!/usr/bin/env python3
"""Tests for gen_missions.py (stdlib unittest).

    python -I tools/javahelp/test_gen_missions.py
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

import gen_missions as gm  # noqa: E402

REPO = HERE.parents[1]

PLUGIN_BASE = """package org.demo.base;
public abstract class BaseReaderPlugIn {
    protected static String[] FORMAT_NAMES = {"Base"};
    protected static String[] FORMAT_FILE_EXTENSIONS = {"base"};
    public String[] getFormatNames() { return FORMAT_NAMES; }
    public String[] getDefaultFileExtensions() { return FORMAT_FILE_EXTENSIONS; }
    public String getDescription(java.util.Locale l) { return "Base products"; }
}
"""

PLUGIN_SUB = """package org.demo.sub;
import org.demo.base.BaseReaderPlugIn;
public class SubReaderPlugIn extends BaseReaderPlugIn {
    private static final String[] SUB_NAMES = {"Sub Format"};
    public SubReaderPlugIn() {
        FORMAT_NAMES = SUB_NAMES;
        FORMAT_FILE_EXTENSIONS = SubConstants.getExtensions();
    }
}
"""

SUB_CONSTANTS = """package org.demo.sub;
public final class SubConstants {
    static final String EXT = ".sub";
    private static final String[] EXTENSIONS = {EXT, ".zip"};
    public static String[] getExtensions() { return EXTENSIONS.clone(); }
}
"""

PLUGIN_LITERAL = """package org.demo.lit;
public class LitReaderPlugIn {
    // return new String[]{"Commented"};
    public String[] getFormatNames() { return new String[]{"Literal \\"Q\\""}; }
    public String[] getDefaultFileExtensions() { return new String[]{"lit"}; }
    public String getDescription(java.util.Locale l) { return "Literal products"; }
}
"""

PAGE = ("<html><head><title>Help - Supported Missions</title></head><body>\r\n"
        "<p>intro</p>\r\n<!-- missions:begin -->\r\nold\r\n<!-- missions:end -->\r\n<p>tail</p>\r\n</body></html>\r\n")


class FixtureRepo:
    """A minimal repo: one module, three readers, a page and an overrides file."""

    def __init__(self):
        self.root = Path(tempfile.mkdtemp(prefix="gen_missions_"))
        java = self.root / "mod/src/main/java/org/demo"
        for rel, src in (("base/BaseReaderPlugIn.java", PLUGIN_BASE), ("sub/SubReaderPlugIn.java", PLUGIN_SUB),
                         ("sub/SubConstants.java", SUB_CONSTANTS), ("lit/LitReaderPlugIn.java", PLUGIN_LITERAL)):
            (java / rel).parent.mkdir(parents=True, exist_ok=True)
            (java / rel).write_text(src, encoding="utf-8")
        (self.root / "mod/pom.xml").write_text("<project/>", encoding="utf-8")
        svc = self.root / "mod/src/main/resources/META-INF/services" / gm.SERVICE
        svc.parent.mkdir(parents=True)
        svc.write_text("org.demo.sub.SubReaderPlugIn\n# comment\norg.demo.lit.LitReaderPlugIn\n", encoding="utf-8")
        docs = self.root / gm.DOCS
        (docs / "readers").mkdir(parents=True)
        (docs / "readers/Sub.html").write_text("<html></html>", encoding="utf-8")
        (docs / "readers/Lit.html").write_text("<html></html>", encoding="utf-8")
        (self.root / gm.PAGE).write_bytes(PAGE.encode("ascii"))
        (self.root / "tools/javahelp").mkdir(parents=True)
        self.overrides({
            "org.demo.sub.SubReaderPlugIn": {"mission": "Sub Mission", "help": "readers/Sub.html"},
            "org.demo.lit.LitReaderPlugIn": {"mission": "Lit Mission", "help": "readers/Lit.html"},
        })

    def overrides(self, readers, extra=()):
        data = {"readers": readers, "skip": {}, "extra": list(extra)}
        (self.root / "tools/javahelp/missions_overrides.json").write_text(json.dumps(data), encoding="utf-8")

    def run(self, *args) -> tuple[int, str]:
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            rc = gm.main([*args, "--repo", str(self.root)])
        return rc, buf.getvalue()

    def close(self):
        shutil.rmtree(self.root, ignore_errors=True)


class ParsingTest(unittest.TestCase):
    def setUp(self):
        self.fx = FixtureRepo()

    def tearDown(self):
        self.fx.close()

    def test_constructor_reassignment_constants_class_and_clone(self):
        readers = {r.fqn: r for r in gm.scan(self.fx.root)}
        sub = readers["org.demo.sub.SubReaderPlugIn"]
        self.assertEqual(sub.formats, ["Sub Format"])           # wins over the superclass "Base"
        self.assertEqual(sub.extensions, [".sub", ".zip"])      # via SubConstants.getExtensions().clone()
        self.assertEqual(sub.description, "Base products")      # inherited

    def test_literals_and_comments(self):
        lit = {r.fqn: r for r in gm.scan(self.fx.root)}["org.demo.lit.LitReaderPlugIn"]
        self.assertEqual(lit.formats, ['Literal "Q"'])           # commented-out return is ignored
        self.assertEqual(lit.extensions, ["lit"])

    def test_norm_ext(self):
        self.assertEqual(gm.norm_ext(["TIF", ".tif", "", ".XML"]), ".tif, .xml")


class WriteCheckTest(unittest.TestCase):
    def setUp(self):
        self.fx = FixtureRepo()

    def tearDown(self):
        self.fx.close()

    def test_write_then_check_passes_and_keeps_crlf_outside_block(self):
        rc, _ = self.fx.run("--write")
        self.assertEqual(rc, 0)
        text = (self.fx.root / gm.PAGE).read_bytes().decode("ascii")
        self.assertTrue(text.startswith("<html><head><title>Help - Supported Missions</title></head><body>\r\n<p>intro"))
        self.assertTrue(text.endswith("<!-- missions:end -->\r\n<p>tail</p>\r\n</body></html>\r\n"))
        self.assertNotIn("\n", text.replace("\r\n", ""))
        self.assertIn('<a href="readers/Lit.html">Lit Mission</a>', text)
        self.assertLess(text.index("Lit Mission"), text.index("Sub Mission"))  # sorted by mission
        self.assertEqual(self.fx.run("--check")[0], 0)

    def test_check_fails_when_block_is_stale(self):
        self.fx.run("--write")
        page = self.fx.root / gm.PAGE
        page.write_bytes(page.read_bytes().replace(b"Lit Mission", b"Old Name"))
        rc, out = self.fx.run("--check")
        self.assertEqual(rc, 1)
        self.assertIn("stale", out)

    def test_check_fails_for_reader_without_override_or_missing_page(self):
        self.fx.overrides({"org.demo.sub.SubReaderPlugIn": {"mission": "Sub", "help": "readers/Missing.html"}})
        self.fx.run("--write")
        rc, out = self.fx.run("--check")
        self.assertEqual(rc, 1)
        self.assertIn("org.demo.lit.LitReaderPlugIn", out)
        self.assertIn("readers/Missing.html does not exist", out)

    def test_extra_rows_are_listed(self):
        self.fx.overrides({
            "org.demo.sub.SubReaderPlugIn": {"mission": "Sub", "help": "readers/Sub.html"},
            "org.demo.lit.LitReaderPlugIn": {"mission": "Lit", "help": "readers/Lit.html"},
        }, extra=[{"mission": "Core", "reader": "CORE", "extensions": ".c", "help": "readers/Sub.html",
                   "note": "(SNAP core)"}])
        self.fx.run("--write")
        text = (self.fx.root / gm.PAGE).read_text(encoding="ascii")
        self.assertIn("<td>CORE (SNAP core)</td>", text)


class RealRepoTest(unittest.TestCase):
    def test_every_registered_reader_is_parsed_and_mapped(self):
        ov = gm.load_overrides()
        readers = gm.scan(REPO)
        self.assertGreater(len(readers), 30)
        for r in readers:
            with self.subTest(reader=r.fqn):
                self.assertIsNotNone(r.source, "PlugIn source not found")
                self.assertTrue(r.formats, "no format name parsed")
                self.assertTrue(r.fqn in ov["readers"] or r.fqn in ov["skip"], "no overrides entry")

    def test_committed_page_is_current(self):
        rc = gm.main(["--check"])
        self.assertEqual(rc, 0, "run: python -I tools/javahelp/gen_missions.py --write")


if __name__ == "__main__":
    unittest.main()
