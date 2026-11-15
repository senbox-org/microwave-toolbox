#!/usr/bin/env python3
"""Tests for the `refs` category of check_javahelp.py (stdlib unittest).

    python -I tools/javahelp/test_check_refs.py
    python -I -m unittest discover -s tools/javahelp -p "test_check_refs.py"
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

OPS = "mod-ui/src/main/resources/pkg/docs/operators"
GOOD = """<html><head><title>T</title></head><body><h1>Op</h1><p>Text.</p>
<h3>References</h3><ol><li>Goldstein, R. (1998)</li></ol></body></html>"""
FOOTER_ONLY = """<html><head><title>T</title></head><body><h1>Op</h1><p>Text.</p>
<!-- footer:begin --><h3>References</h3><ol><li>Goldstein 1998</li></ol><!-- footer:end -->
</body></html>"""
PARAMS_ONLY = """<html><head><title>T</title></head><body><h1>Op</h1>
<!-- params:begin op="X" --><h3>Parameters (reference)</h3><ul><li>a</li></ul><!-- params:end -->
</body></html>"""


class RefsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.exempt = self.tmp / "exempt.txt"

    def page(self, rel, text):
        p = self.tmp / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")

    def run_refs(self, exempt_text=""):
        self.exempt.write_text(exempt_text, encoding="utf-8")
        c = cj.Checker(self.tmp, [])
        c.refs_exempt_path = self.exempt
        c.check_refs()
        return [m for cat, _k, m in c.errors if cat == "refs"]

    def test_h3_references_with_li_passes(self):
        self.page(f"{OPS}/A.html", GOOD)
        self.assertEqual(self.run_refs(), [])

    def test_bold_heading_and_paragraph_year_passes(self):
        self.page(f"{OPS}/A.html", "<body><p><b>References</b></p><p>Smith (2004), J.</p></body>")
        self.assertEqual(self.run_refs(), [])

    def test_heading_without_entries_fails(self):
        self.page(f"{OPS}/A.html", "<body><h3>References</h3><p>See the manual.</p></body>")
        self.assertEqual(self.run_refs(), [f"{OPS}/A.html: no References section"])

    def test_footer_block_references_fail(self):
        self.page(f"{OPS}/A.html", FOOTER_ONLY)
        self.assertEqual(self.run_refs(), [f"{OPS}/A.html: no References section"])

    def test_params_block_reference_heading_fails(self):
        self.page(f"{OPS}/A.html", PARAMS_ONLY)
        self.assertEqual(len(self.run_refs()), 1)

    def test_exempt_page_passes(self):
        self.page(f"{OPS}/A.html", "<body><p>nothing</p></body>")
        self.assertEqual(self.run_refs(f"{OPS}/A.html|data handling\n"), [])

    def test_exempt_page_with_references_is_allowed(self):
        self.page(f"{OPS}/A.html", GOOD)
        self.assertEqual(self.run_refs(f"# c\n{OPS}/A.html|reason\n"), [])

    def test_stale_exemption_reported(self):
        self.assertEqual(self.run_refs(f"{OPS}/Gone.html|x\n"),
                         [f"{OPS}/Gone.html: refs_exempt.txt entry for missing page"])

    def test_non_operator_and_target_pages_ignored(self):
        self.page("mod/src/main/resources/pkg/docs/concepts/C.html", "<body>none</body>")
        self.page(f"mod/target/classes/pkg/docs/operators/B.html", "<body>none</body>")
        self.assertEqual(self.run_refs(), [])

    def test_analysis_rcp_and_wave_pages_included(self):
        base = "sar-op-analysis-ui/src/main/resources/eu/esa/sar"
        self.page(f"{base}/analysis/docs/rcp/T.html", "<body>none</body>")
        self.page(f"{base}/ocean/docs/wave/P.html", "<body>none</body>")
        self.assertEqual(len(self.run_refs()), 2)

    def test_only_filter_runs_refs(self):
        self.page(f"{OPS}/A.html", "<body>none</body>")
        c = cj.Checker(self.tmp, [])
        c.refs_exempt_path = self.exempt
        self.exempt.write_text("")
        self.assertFalse([e for e in c.run({"link"}) if e[0] == "refs"])
        c.errors.clear()
        self.assertTrue([e for e in c.run({"refs"}) if e[0] == "refs"])


if __name__ == "__main__":
    unittest.main()
