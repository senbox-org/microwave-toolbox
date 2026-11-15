#!/usr/bin/env python3
"""Tests for the `style` category of check_javahelp.py (stdlib unittest).

    python -I tools/javahelp/test_check_style.py
"""
from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:  # `python -I` drops the script folder from sys.path
    sys.path.insert(0, str(HERE))

import check_javahelp as cj  # noqa: E402

PAGE = "mod/src/main/resources/pkg/docs/operators/A.html"


class StyleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.allow = self.tmp / "style_allow.txt"

    def run_style(self, body, allow_text="", rel=PAGE):
        p = self.tmp / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(f"<html><head><title>T</title></head><body>\n{body}\n</body></html>", encoding="utf-8")
        self.allow.write_text(allow_text, encoding="utf-8")
        c = cj.Checker(self.tmp, [])
        c.style_allow_path = self.allow
        c.check_style()
        return [(k, m) for cat, k, m in c.errors if cat == "style"]

    def test_master_in_paragraph_flagged_with_page_line_and_phrase(self):
        (key, msg), = self.run_style("<p>The Master image is used.</p>")
        self.assertIn(f"{PAGE}:2: S1 'Master'", msg)
        self.assertIn("The Master image", msg)

    def test_master_in_code_not_flagged(self):
        self.assertEqual(self.run_style("<p>Use <code>Master</code>, <tt>slave</tt>, <kbd>mst</kbd></p>"
                                        "<pre>slave\nmaster</pre>"), [])

    def test_slave_plural_and_mst_flagged_but_not_substrings(self):
        r = self.run_style("<p>slaves and MST, but Mastering, mastery and Multimaster are fine.</p>")
        self.assertEqual(len(r), 2)

    def test_allow_token_suppresses_match(self):
        body = "<p>Use the Master extent here, and the Slave Metadata element.</p>"
        self.assertEqual(len(self.run_style(body)), 2)
        self.assertEqual(self.run_style(body, "Master extent|UI value\nSlave Metadata|literal\n"), [])

    def test_allow_token_spans_markup(self):
        body = "<p>Output Extent = <i>Master</i></p>"
        self.assertEqual(self.run_style(body, "Extent = Master|literal option value\n"), [])

    def test_params_block_ignored(self):
        body = '<!-- params:begin op="X" -->\n<ul><li>master band</li></ul>\n<!-- params:end -->'
        self.assertEqual(self.run_style(body), [])

    def test_dead_domain_flagged_in_href(self):
        r = self.run_style('<p><a href="http://nest.array.ca/x">link</a></p>')
        self.assertEqual(len(r), 1)
        self.assertIn(": S2 'nest.array.ca'", r[0][1])

    def test_dead_domain_in_text_and_legacy_names(self):
        r = self.run_style("<p>See sentinel.esa.int, the S1TBX and NEST4A in ~/.s1tbx.</p>")
        self.assertEqual(sorted(m.split("'")[1] for _k, m in r),
                         sorted(["NEST4A", "S1TBX", "sentinel.esa.int", ".s1tbx"]))

    def test_html_comment_ignored(self):
        self.assertEqual(self.run_style('<!-- the master <a href="http://envisat.esa.int"> -->\n<p>ok</p>'), [])

    def test_s3_statements(self):
        self.assertEqual(len(self.run_style("<p>The default DEM is SRTM 3Sec.</p>")), 1)
        self.assertEqual(len(self.run_style("<p>Sentinel-1B is currently operational.</p>")), 1)
        self.assertEqual(len(self.run_style("<p>Runs on Windows XP.</p>")), 1)
        self.assertEqual(len(self.run_style("<p>SRTM 3Sec (Auto Download)</p>")), 1)
        self.assertEqual(self.run_style("<p>SRTM is a DEM. Sentinel-1B failed in 2021.</p>"), [])

    def test_attribute_values_not_scanned_for_s1(self):
        self.assertEqual(self.run_style('<p><img alt="master" title="slave" src="a.png"></p>'), [])

    def test_allowlist_page_rule_and_hash_keys(self):
        r = self.run_style("<p>master</p>")
        (key, _), = r
        c = cj.Checker(self.tmp, [])
        c.style_allow_path = self.allow
        c.allow.add(("style", key))
        c.check_style()
        self.assertEqual([e for e in c.errors if e[0] == "style"], [])
        c = cj.Checker(self.tmp, [])
        c.style_allow_path = self.allow
        c.allow.add(("style", f"{PAGE}:S1"))
        c.check_style()
        self.assertEqual([e for e in c.errors if e[0] == "style"], [])

    def test_scope_docs_only_and_no_target(self):
        self.assertEqual(self.run_style("<p>master</p>", rel="mod/src/main/resources/pkg/other/B.html"), [])
        self.assertEqual(self.run_style("<p>master</p>", rel="mod/target/classes/pkg/docs/C.html"), [])

    def test_only_filter(self):
        p = self.tmp / PAGE
        p.parent.mkdir(parents=True)
        p.write_text("<body>master</body>", encoding="utf-8")
        self.allow.write_text("")
        c = cj.Checker(self.tmp, [])
        c.style_allow_path = self.allow
        self.assertFalse([e for e in c.run({"link"}) if e[0] == "style"])
        self.assertTrue([e for e in c.run({"style"}) if e[0] == "style"])


if __name__ == "__main__":
    unittest.main()
