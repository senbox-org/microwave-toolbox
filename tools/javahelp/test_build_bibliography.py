#!/usr/bin/env python3
"""Tests for build_bibliography.py --check (stdlib unittest).

    python -I tools/javahelp/test_build_bibliography.py
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

import build_bibliography as bb  # noqa: E402
import check_javahelp as cj  # noqa: E402

CONCEPTS = "microwavetbx-rcp/src/main/resources/eu/esa/microwave/dat/docs/concepts"
GOLD = ("Goldstein, R. M., Werner, C. L. (1998). Radar interferogram filtering for geophysical applications. "
        "<i>Geophysical Research Letters</i>, 25(21), 4035&#8211;4038. doi:10.1029/1998GL900033")
PAGE = ('<html><head><title>%s</title></head><body><h1>%s</h1><p>Text.</p>\n'
        '<h3>References</h3>\n<ol>\n%s\n</ol>\n</body></html>')


def li(*items):
    return "\n".join("    <li>%s</li>" % i for i in items)


class BiblioCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.addCleanup(bb.configure, HERE.parents[1])  # the module globals are process-wide
        (self.tmp / CONCEPTS).mkdir(parents=True)

    def page(self, name, *items):
        (self.tmp / CONCEPTS / (name + ".html")).write_text(PAGE % (name, name, li(*items)), encoding="ascii")

    def regenerate(self):
        bb.configure(self.tmp)
        entries = bb.build(bb.collect())
        bb.OUT.write_bytes(bb.render(entries).encode("ascii"))

    def problems(self):
        return bb.run_check(self.tmp)

    def test_clean_repo_has_no_problems(self):
        self.page("speckle", GOLD)
        self.page("insar", GOLD)
        self.regenerate()
        self.assertEqual([], self.problems())

    def test_stale_page_is_reported(self):
        self.page("speckle", GOLD)
        self.regenerate()
        self.assertEqual([], self.problems())
        self.page("insar", GOLD, "Hanssen, R. F. (2001). <i>Radar Interferometry</i>. Kluwer, Dordrecht. "
                                 "doi:10.1007/0-306-47633-9")
        msgs = self.problems()
        self.assertEqual(1, len(msgs), msgs)
        self.assertIn("stale", msgs[0])

    def test_missing_page_is_stale(self):
        self.page("speckle", GOLD)
        bb.configure(self.tmp)
        self.assertTrue(any("stale" in m for m in bb.run_check(self.tmp)))

    def test_two_spellings_of_one_entry_are_reported(self):
        self.page("speckle", GOLD)
        self.page("insar", GOLD.replace("4035&#8211;4038", "4035-4038"))
        self.regenerate()
        msgs = self.problems()
        self.assertEqual(1, len(msgs), msgs)
        self.assertTrue(msgs[0].startswith("variants: 2 spellings of 'Goldstein"), msgs[0])
        self.assertIn("insar.html", msgs[0])

    def test_annotation_text_is_reported(self):
        cases = {
            "leading note": "Background only (not used by SNAP): " + GOLD,
            "not independently verified": GOLD + " (not independently verified)",
            "as cited in": GOLD + " Taken as cited in the source code.",
            "trailing remark": GOLD[:-1] + " (slant-to-height method)",
        }
        for label, raw in cases.items():
            with self.subTest(label):
                self.page("speckle", raw)
                self.regenerate()
                msgs = self.problems()
                self.assertEqual(1, len(msgs), msgs)
                self.assertTrue(msgs[0].startswith("annotation:"), msgs[0])

    def test_legitimate_trailing_parentheses_pass(self):
        ok = [
            GOLD[:-1] + " (2nd ed.)",
            "Hanssen, R. F. (2001). <i>Radar Interferometry</i>. Kluwer, Dordrecht. ISBN (0-7923-6945-9)",
            "ESA Sentinel-1 Mission Performance Centre. <i>Thermal Denoising</i>. Document MPC-0392 (DI-MPC-TN).",
            "S1-ETAD Project: Product Definition Document, Doc. ID ETAD-DLR-PS-0002, Issue 1.",
        ]
        for raw in ok:
            self.assertIsNone(bb.annotation_problem(raw), raw)

    def test_contact_note_is_not_a_citation(self):
        self.page("speckle", GOLD, "For further details, contact the COSMO-SkyMed Help Desk at "
                                   "<a href=\"mailto:info@example.org\">info@example.org</a>.")
        self.regenerate()
        self.assertEqual([], self.problems())
        self.assertNotIn(b"Help Desk", bb.OUT.read_bytes())

    def test_house_style_beats_legacy_variant(self):
        legacy = ("R. M. Goldstein and C. L. Werner, Radar interferogram filtering for geophysical applications, "
                  "Geophysical Research Letters, Vol. 25, No. 21, pp. 4035-4038, Nov. 1998. doi:10.1029/1998GL900033")
        self.page("speckle", GOLD)
        self.page("insar", legacy)
        bb.configure(self.tmp)
        entries = bb.build(bb.collect())
        (entry,) = entries.values()
        self.assertEqual(GOLD, entry["best"])

    def test_checker_runs_biblio_category(self):
        self.page("speckle", GOLD)
        self.regenerate()
        self.page("insar", GOLD, "Background only (x): " + GOLD)
        chk = cj.Checker(self.tmp, [])
        errors = chk.run({"biblio"})
        cats = {e[0] for e in errors}
        self.assertIn("biblio", cats)
        self.assertTrue(any("annotation" in e[2] for e in errors if e[0] == "biblio"))


if __name__ == "__main__":
    unittest.main()
