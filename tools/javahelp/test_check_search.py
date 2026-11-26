#!/usr/bin/env python3
"""Tests for the `search` category of check_javahelp.py (stdlib unittest).

    python -I tools/javahelp/test_check_search.py
"""
from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:  # `python -I` drops the script folder from sys.path
    sys.path.insert(0, str(HERE))

import check_javahelp as cj  # noqa: E402

POM = """<project><groupId>g</groupId><artifactId>mod-ui</artifactId></project>"""
HS = """<?xml version='1.0' encoding='ISO-8859-1' ?>
<helpset version="2.0"><maps><mapref location="map.jhm"/></maps>{views}</helpset>"""
SEARCH = """<view><name>Search</name><type>javax.help.SearchView</type>
<data engine="com.sun.java.help.search.DefaultSearchEngine">JavaHelpSearch</data></view>"""
TOC = """<view><name>TOC</name><type>javax.help.TOCView</type><data>toc.xml</data></view>"""
FULL_DB = {"DOCS": 10, "DOCS.TAB": 5, "OFFSETS": 7, "POSITIONS": 9, "SCHEMA": 53, "TMAP": 2048}


class SearchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.mod = self.tmp / "mod-ui"
        self.mod.mkdir()
        (self.mod / "pom.xml").write_text(POM, encoding="utf-8")

    def helpset(self, pkg, views=SEARCH, annotated=True):
        d = self.mod / "src/main/resources" / pkg
        d.mkdir(parents=True)
        (d / "help.hs").write_text(HS.format(views=views), encoding="utf-8")
        if annotated:
            j = self.mod / "src/main/java" / pkg
            j.mkdir(parents=True)
            (j / "package-info.java").write_text(
                f'@HelpSetRegistration(helpSet = "help.hs", position = 1)\npackage {pkg.replace("/", ".")};\n',
                encoding="utf-8")

    def jar(self, entries: dict[str, int], path="target/mod-ui-1.0.jar", pkg="a/docs"):
        j = self.mod / path
        j.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(j, "w") as z:
            z.writestr(f"{pkg}/help.hs", b"<helpset/>")
            for name, size in entries.items():
                z.writestr(name, b"x" * size)

    def errors(self):
        c = cj.Checker(self.tmp, [])
        c.check_search()
        return [m for cat, _k, m in c.errors if cat == "search"]

    def test_single_searchable_helpset_passes(self):
        self.helpset("a/docs")
        self.assertEqual(self.errors(), [])

    def test_two_searchable_helpsets_in_one_module_fail(self):
        self.helpset("a/docs")
        self.helpset("b/docs")
        errs = self.errors()
        self.assertEqual(len(errs), 1)
        self.assertIn("2 @HelpSetRegistration helpsets", errs[0])

    def test_second_helpset_without_search_view_passes(self):
        self.helpset("a/docs")
        self.helpset("b/docs", views=TOC)
        self.assertEqual(self.errors(), [])

    def test_complete_database_in_jar_passes(self):
        self.helpset("a/docs")
        self.jar({f"a/docs/JavaHelpSearch/{f}": n for f, n in FULL_DB.items()})
        self.assertEqual(self.errors(), [])

    def test_empty_database_in_jar_fails(self):
        """The shape the second helpset of a module actually gets from the indexer."""
        self.helpset("a/docs")
        self.jar({"a/docs/JavaHelpSearch/DOCS": 0, "a/docs/JavaHelpSearch/OFFSETS": 6,
                  "a/docs/JavaHelpSearch/POSITIONS": 0, "a/docs/JavaHelpSearch/TMAP": 2048})
        errs = self.errors()
        self.assertEqual(len(errs), 1)
        self.assertIn("missing DOCS.TAB, SCHEMA", errs[0])

    def test_zero_byte_docs_fails(self):
        self.helpset("a/docs")
        self.jar({f"a/docs/JavaHelpSearch/{f}": (0 if f == "DOCS" else n) for f, n in FULL_DB.items()})
        self.assertIn("DOCS is empty", self.errors()[0])

    def test_javahelp_dir_helpset_is_checked_in_docs_jar(self):
        """src/main/javahelp help is indexed by nbm-maven-plugin into modules/docs/<cnb>.jar."""
        d = self.mod / "src/main/javahelp/a/docs"
        d.mkdir(parents=True)
        (d / "help.hs").write_text(HS.format(views=SEARCH), encoding="utf-8")
        self.jar({}, path="target/mod-ui-1.0.jar")  # main jar carries help.hs only by accident
        self.jar({f"a/docs/JavaHelpSearch/{f}": n for f, n in FULL_DB.items()},
                 path="target/nbm/clusters/c/modules/docs/g-mod-ui.jar")
        self.assertEqual(self.errors(), [])
        self.jar({"a/docs/JavaHelpSearch/TMAP": 2048}, path="target/nbm/clusters/c/modules/docs/g-mod-ui.jar")
        self.assertIn("g-mod-ui.jar", self.errors()[0])

    def test_jar_without_the_helpset_is_ignored(self):
        self.helpset("a/docs")
        self.jar({}, pkg="other/docs")
        self.assertEqual(self.errors(), [])

    def test_tests_jar_is_ignored(self):
        self.helpset("a/docs")
        (self.mod / "target").mkdir()
        with zipfile.ZipFile(self.mod / "target/mod-ui-1.0-tests.jar", "w") as z:
            z.writestr("dummy", b"")
        self.assertEqual(self.errors(), [])


if __name__ == "__main__":
    unittest.main()
