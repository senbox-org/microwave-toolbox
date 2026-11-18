#!/usr/bin/env python3
"""Tests for gen_index.py (stdlib unittest).

    python -m unittest test_gen_index          # from tools/javahelp
    python -I tools/javahelp/test_gen_index.py
"""
from __future__ import annotations

import random
import re
import shutil
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:  # `python -I` drops the script folder from sys.path
    sys.path.insert(0, str(HERE))

import gen_index as gi  # noqa: E402

HELP_HS = """<?xml version='1.0' encoding='ISO-8859-1' ?>
<!DOCTYPE helpset
        PUBLIC "-//Sun Microsystems Inc.//DTD JavaHelp HelpSet Version 2.0//EN"
        "http://java.sun.com/products/javahelp/helpset_2_0.dtd">

<helpset version="2.0">
    <title>Help</title>
    <maps>
        <homeID>top</homeID>
        <mapref location="map.jhm"/>
    </maps>
    <view mergetype="javax.help.UniteAppendMerge">
        <name>TOC</name>
        <label>Contents</label>
        <type>javax.help.TOCView</type>
        <data>toc.xml</data>
    </view>
    <view>
        <name>Search</name>
        <label>Search</label>
        <type>javax.help.SearchView</type>
        <data engine="com.sun.java.help.search.DefaultSearchEngine">JavaHelpSearch</data>
    </view>
</helpset>
""".replace("\n", "\r\n")


def page(title: str, body: str = "") -> str:
    return f"<html>\r\n<head>\r\n<title>{title}</title>\r\n</head>\r\n<body>\r\n{body}\r\n</body>\r\n</html>\r\n"


def map_jhm(ids) -> str:
    rows = "".join(f'    <mapID target="{t}" url="{u}"/>\r\n' for t, u in ids)
    return f"<?xml version='1.0' encoding='ISO-8859-1' ?>\r\n<map version=\"2.0\">\r\n{rows}</map>\r\n"


def toc_xml(items) -> str:
    rows = "".join(f'    <tocitem text="{t}" target="{g}"/>\r\n' for t, g in items)
    return f"<?xml version='1.0' encoding='ISO-8859-1' ?>\r\n<toc version=\"2.0\">\r\n{rows}</toc>\r\n"


class Fixture:
    """A throw-away repo with two helpsets: ops (operator pages) and rcp (glossary)."""

    def __init__(self):
        self.root = Path(tempfile.mkdtemp(prefix="gen_index_"))
        self.ops = self.root / "mod-a/src/main/resources/a/docs"
        self.rcp = self.root / "mod-b/src/main/resources/b/docs"
        self.write(self.ops / "operators/WarpOp.html", page("Help - Warp Operator"))
        self.write(self.ops / "operators/DeburstOp.html", page("Sentinel-1"))
        self.write(self.ops / "operators/SplitOp.html", page("Sentinel-1"))
        self.write(self.ops / "operators/RisatOp.html", page("Import RISAT &amp; EOS-04"))
        self.write(self.ops / "map.jhm", map_jhm([
            ("WarpOp", "operators/WarpOp.html"), ("WarpAlias", "operators/WarpOp.html"),
            ("DeburstOp", "operators/DeburstOp.html"), ("SplitOp", "operators/SplitOp.html"),
            ("RisatOp", "operators/RisatOp.html"), ("ImportEOS04", "operators/RisatOp.html#eos04")]))
        self.write(self.ops / "toc.xml", toc_xml([
            ("Warp", "WarpAlias"), ("TOPSAR Deburst", "DeburstOp"), ("TOPSAR Split", "SplitOp"),
            ("EOS-04", "ImportEOS04")]))
        self.write(self.rcp / "concepts/glossary.html", page("Help - SAR Glossary", (
            "<p><a href=\"#A\">A</a></p>\r\n"
            "<p><b>Alpha angle (&alpha;).</b> The mean angle.</p>\r\n"
            "<p class=\"x\"><b>Sigma\r\n nought (&sigma;<sup>0</sup>).</b> Backscatter.</p>\r\n"
            "<p><b>Coherence (&gamma;).</b> See below.</p>\r\n"
            "<p><b>Coregistration.</b> Alignment.</p>\r\n"
            "<p>No <b>bold</b> lead-in here.</p>")))
        self.write(self.rcp / "concepts/coregistration.html", page("Coregistration of SAR Images"))
        self.write(self.rcp / "map.jhm", map_jhm([
            ("Concept_Glossary", "concepts/glossary.html"),
            ("Concept_Coregistration", "concepts/coregistration.html")]))
        self.write(self.rcp / "toc.xml", toc_xml([
            ("SAR Glossary", "Concept_Glossary"), ("Coregistration", "Concept_Coregistration")]))
        for d in (self.ops, self.rcp):
            self.write(d / "help.hs", HELP_HS)
        self.aliases = {"Warp": self.ops / "operators/WarpOp.html",
                        "TOPSAR-Deburst": self.ops / "operators/DeburstOp.html",
                        "Elsewhere": self.root / "mod-c/docs/Other.html"}

    @staticmethod
    def write(p: Path, text: str):
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(text.encode("latin-1"))

    def close(self):
        shutil.rmtree(self.root, ignore_errors=True)


class TitleAndGlossaryTest(unittest.TestCase):
    def test_title_extraction(self):
        self.assertEqual(gi.title_from_html(page("Help - Warp Operator")), "Warp Operator")
        self.assertEqual(gi.title_from_html(page("Help -  Import  RISAT &amp; EOS-04")), "Import RISAT & EOS-04")
        self.assertEqual(gi.title_from_html(page("Help")), "")
        self.assertEqual(gi.title_from_html("<html><body>no title</body></html>"), "")
        self.assertEqual(gi.title_from_html(page("Helpful Hints")), "Helpful Hints")  # prefix needs the dash

    def test_glossary_terms(self):
        fx = Fixture()
        try:
            text = (fx.rcp / "concepts/glossary.html").read_text(encoding="latin-1")
            self.assertEqual(gi.glossary_terms_from_html(text),
                             ["Alpha angle (α)", "Sigma nought (σ0)", "Coherence (γ)", "Coregistration"])
        finally:
            fx.close()


class CollectTest(unittest.TestCase):
    def setUp(self):
        self.fx = Fixture()

    def tearDown(self):
        self.fx.close()

    def entries(self, d):
        return [(e.text, e.target) for e in gi.collect(d, self.fx.aliases)]

    def test_pages_toc_labels_and_aliases(self):
        got = self.entries(self.fx.ops)
        # title without "Help - ", on the ID the TOC uses for that page
        self.assertIn(("Warp Operator", "WarpAlias"), got)
        self.assertIn(("Warp", "WarpAlias"), got)  # TOC label as a synonym
        self.assertNotIn(("Warp Operator", "WarpOp"), got)  # one ID per page
        # a title shared by two pages is replaced by their TOC labels
        self.assertNotIn("Sentinel-1", [t for t, _ in got])
        self.assertIn(("TOPSAR Deburst", "DeburstOp"), got)
        self.assertIn(("TOPSAR Split", "SplitOp"), got)
        # an anchored map ID gets its TOC label, not the page title
        self.assertIn(("EOS-04", "ImportEOS04"), got)
        self.assertIn(("Import RISAT & EOS-04", "RisatOp"), got)
        # aliases of pages in this helpset only
        self.assertIn(("Warp (GPT operator)", "WarpAlias"), got)
        self.assertIn(("TOPSAR-Deburst (GPT operator)", "DeburstOp"), got)
        self.assertFalse(any("Elsewhere" in t for t, _ in got))
        # every target is a map ID of this helpset; sorted case-insensitively
        ids = {t for t, _ in gi.read_map(self.fx.ops)}
        self.assertTrue(all(target in ids for _, target in got))
        self.assertEqual([t for t, _ in got], sorted((t for t, _ in got), key=str.casefold))

    def test_glossary_entries_only_in_glossary_helpset(self):
        got = self.entries(self.fx.rcp)
        self.assertIn(("Alpha angle (α)", "Concept_Glossary"), got)
        self.assertIn(("Sigma nought (σ0)", "Concept_Glossary"), got)
        # same text as a TOC label of another page: disambiguated
        self.assertIn(("Coregistration (glossary)", "Concept_Glossary"), got)
        self.assertIn(("Coregistration", "Concept_Coregistration"), got)
        self.assertFalse(any(t == "bold" for t, _ in got))
        self.assertFalse(any(target == "Concept_Glossary" for _, target in self.entries(self.fx.ops)))


class RenderAndCheckTest(unittest.TestCase):
    def setUp(self):
        self.fx = Fixture()

    def tearDown(self):
        self.fx.close()

    def test_render_is_deterministic_and_well_formed(self):
        entries = gi.collect(self.fx.rcp, self.fx.aliases) + gi.collect(self.fx.ops, self.fx.aliases)
        shuffled = list(entries)
        random.Random(7).shuffle(shuffled)
        a, b = gi.render(gi.sort_entries(entries)), gi.render(gi.sort_entries(shuffled + entries[:3]))
        self.assertEqual(a, b)
        self.assertEqual(a.count("\r\n"), a.count("\n"))  # CRLF only
        comment = a[a.index("<!--"):a.index("-->")]
        self.assertNotIn("--", comment[4:])  # "--" is illegal inside an XML comment
        root = ET.fromstring(re.sub(r"<!DOCTYPE[^>]*>", "", a.split("?>", 1)[1]))
        self.assertEqual(root.tag, "index")
        self.assertEqual(len(root.findall("indexitem")), len(gi.sort_entries(entries)))

    def test_index_view_insertion(self):
        out = gi.with_index_view(HELP_HS)
        self.assertEqual(gi.with_index_view(out), out)  # idempotent
        self.assertEqual(out.count("\r\n"), out.count("\n"))
        toc, idx, search = out.index("javax.help.TOCView"), out.index("javax.help.IndexView"), \
            out.index("javax.help.SearchView")
        self.assertTrue(toc < idx < search)
        self.assertIn('    <view mergetype="javax.help.SortMerge">\r\n        <name>Index</name>\r\n', out)
        self.assertIn("<data>index.xml</data>", out)

    def test_check_reports_missing_then_stale(self):
        root = self.fx.root
        msgs = gi.check(root)
        self.assertTrue(any("no javax.help.IndexView" in m for m in msgs))
        self.assertTrue(any(m.endswith("missing (run gen_index.py --write)") for m in msgs))
        changed = gi.write(root)
        self.assertEqual(len(changed), 4)  # 2 x help.hs + 2 x index.xml
        self.assertEqual(gi.check(root), [])
        self.assertEqual(gi.write(root), [])  # nothing left to do
        hs = (self.fx.ops / "help.hs").read_bytes()
        self.assertEqual(hs.count(b"\r\n"), hs.count(b"\n"))
        Fixture.write(self.fx.ops / "operators/WarpOp.html", page("Help - Warp (renamed)"))
        msgs = gi.check(root)
        self.assertEqual(len(msgs), 1)
        self.assertIn("mod-a/src/main/resources/a/docs/index.xml: stale", msgs[0])
        self.assertEqual(gi.main(["--repo", str(root), "--check"]), 1)


if __name__ == "__main__":
    unittest.main()
