#!/usr/bin/env python3
"""Generate the consolidated Bibliography help page (concepts/bibliography.html).

Collects the References sections of the concept, workflow and operator pages,
deduplicates by DOI (else first-author surname + year + title prefix), assigns
each entry to one primary topic by weighted majority vote of the citing pages,
and writes the page.  Citation text is never altered beyond whitespace.

Usage: python -I tools/javahelp/build_bibliography.py [--dump] [--no-write] [--check] [--report FILE]

--check writes nothing and exits 1 when bibliography.html is stale, when two pages spell
one entry differently, or when a reference item carries annotation text instead of a citation.
Stdlib only.
"""
import re
import html
import argparse
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import defaultdict, Counter

REPO = Path(__file__).resolve().parents[2]
DOCS = REPO / "microwavetbx-rcp/src/main/resources/eu/esa/microwave/dat/docs"
CONCEPTS = DOCS / "concepts"
WORKFLOWS = DOCS / "workflows"
OUT = CONCEPTS / "bibliography.html"
MAX_LINKS = 6

SECTIONS = [
    ("sar", "SAR fundamentals &amp; geometry"),
    ("radiometry", "Radiometry, calibration &amp; noise"),
    ("speckle", "Speckle &amp; filtering"),
    ("dem", "DEMs &amp; geocoding"),
    ("insar", "Interferometry (InSAR, coherence, unwrapping, displacement)"),
    ("timeseries", "InSAR time series"),
    ("atmos", "Atmosphere &amp; ionosphere"),
    ("tops", "Sentinel-1 TOPS, orbits &amp; ETAD"),
    ("pol", "Polarimetry (decompositions, dual/compact pol, Wishart, classification)"),
    ("change", "Change detection"),
    ("ocean", "Ocean"),
    ("soil", "Soil moisture"),
    ("fex", "Feature extraction &amp; texture"),
    ("docs", "ESA/mission technical documents"),
]
SEC_IDS = [s for s, _ in SECTIONS]

CONCEPT_TOPIC = {
    "sar_fundamentals": "sar", "geometry": "sar", "glossary": "sar",
    "radiometry": "radiometry", "speckle": "speckle", "height_references": "dem",
    "insar": "insar", "coherence": "insar", "unwrapping": "insar", "displacement": "insar",
    "time_series": "timeseries", "atmosphere": "atmos",
    "tops": "tops", "orbits_etad": "tops",
    "polarimetry": "pol", "decompositions": "pol", "dual_compact_pol": "pol", "wishart": "pol",
    "change_detection": "change", "ocean": "ocean", "soil_moisture": "soil",
}
WORKFLOW_TOPIC = {
    "workflow_dem_generation": "dem", "workflow_grd_backscatter": "radiometry",
    "workflow_gslc": "dem", "workflow_ocean": "ocean", "workflow_offset_tracking": "insar",
    "workflow_s1_dinsar": "insar", "workflow_stripmap_dinsar": "insar",
    "workflow_time_series": "timeseries",
}
# operator page-name token (CamelCase word, lower-case; prefix match for tokens >= 5 chars) -> topic
OP_KEYWORDS = [
    ("speckle", "speckle"), ("multilook", "speckle"), ("multitemporal", "speckle"),
    ("oilspill", "ocean"), ("ocean", "ocean"), ("wind", "ocean"), ("ship", "ocean"), ("wave", "ocean"),
    ("calibration", "radiometry"), ("noise", "radiometry"), ("flattening", "radiometry"),
    ("radiometric", "radiometry"), ("thermal", "radiometry"), ("antenna", "radiometry"),
    ("phase", "insar"),
    ("terrain", "dem"), ("doppler", "dem"), ("elevation", "dem"), ("dem", "dem"), ("geocoding", "dem"),
    ("gslc", "dem"), ("mosaic", "dem"), ("ellipsoid", "dem"), ("geolocation", "dem"), ("srgr", "dem"),
    ("unwrapping", "insar"), ("snaphu", "insar"), ("coherence", "insar"), ("interferogram", "insar"),
    ("displacement", "insar"), ("offset", "insar"), ("deramp", "insar"), ("flatten", "insar"),
    ("azimuth", "insar"), ("range", "insar"), ("coregistration", "insar"), ("goldstein", "insar"),
    ("linking", "timeseries"), ("sbas", "timeseries"), ("stamps", "timeseries"), ("reference", "timeseries"),
    ("tropo", "atmos"), ("ionospheric", "atmos"), ("atmos", "atmos"),
    ("etad", "tops"), ("tops", "tops"), ("deburst", "tops"), ("burst", "tops"), ("orbit", "tops"),
    ("change", "change"),
]
MODULE_TOPIC = {
    "sar-op-calibration-ui": "radiometry", "sar-op-insar-ui": "insar", "jlinda-nest-ui": "insar",
    "sar-op-sentinel1-ui": "tops", "sar-io-ephemeris-ui": "tops",
    "rstb-op-polarimetric-tools-ui": "pol", "rstb-op-classification-ui": "pol",
    "rstb-op-soil-moisture-ui": "soil", "sar-op-feature-extraction-ui": "fex",
    "sar-op-sar-processing-ui": "sar", "sar-op-utilities-ui": "sar",
}
# modules whose topic is fixed regardless of page-name keywords
MODULE_STRONG = {"rstb-op-polarimetric-tools-ui", "rstb-op-classification-ui",
                 "rstb-op-soil-moisture-ui", "sar-op-feature-extraction-ui"}
# (regex on plain citation text, topic): applied after the vote; first match wins
OVERRIDE_PATTERNS = [
    (r"Sentinel-1 TOPS|TOPSAR", "tops"),
    (r"time-series approach to estimate soil moisture", "pol"),
    (r"SAR interferometric model for soil moisture", "timeseries"),
    (r"Flattening [Gg]amma", "radiometry"),
    (r"multitemporal compositing", "radiometry"),
    (r"SAR geocoding: data and systems|SAR Geocoding: Data and Systems", "dem"),
    (r"Geometrical SAR image registration", "insar"),
    (r"Positional Errors and Geometric Distortions", "dem"),
    (r"speckle filtering|sigma filter|adaptive-neighborhood", "speckle"),
    (r"[Oo]il spill", "ocean"),
    (r"wind vectors", "ocean"),
    (r"soil moisture", "soil"),
    (r"test statistic in the complex Wishart", "change"),
]

# no-DOI citations matching DOCS_PAT (and not JOURNAL_PAT) are mission/agency technical documents
DOCS_PAT = re.compile(r"\b(ESA|ESRIN|European Space Agency|Issue \d|Revision|Rev\.|Doc\. ID|MPC-\d|"
                      r"RADARSAT|TerraSAR|ALOS|Cosmo-SkyMed|ENVI-|RSL-|Product (?:Specification|Format|Definition))")
JOURNAL_PAT = re.compile(r"Transactions|Journal|Letters|Review|Proceedings|IGARSS|Conference")
# list items in a References section that are contact notes, not citations
NON_CITATION = re.compile(r"please contact|Help Desk", re.I)
# house style: "Surname, I." author start and "(Year)." after the authors
HOUSE_AUTHOR = re.compile(r"^(?:van |de |De )?[A-Z][^,]*, [A-Z]\.")
HOUSE_YEAR = re.compile(r"\(\d{4}[a-z]?\)\.")


def configure(repo):
    """Point the module at another checkout (used by check_javahelp.py and the tests)."""
    global REPO, DOCS, CONCEPTS, WORKFLOWS, OUT
    REPO = Path(repo).resolve()
    DOCS = REPO / "microwavetbx-rcp/src/main/resources/eu/esa/microwave/dat/docs"
    CONCEPTS = DOCS / "concepts"
    WORKFLOWS = DOCS / "workflows"
    OUT = CONCEPTS / "bibliography.html"


def read_text(f):
    b = f.read_bytes()
    try:
        return b.decode("utf-8")
    except UnicodeDecodeError:
        return b.decode("latin-1")


def ws(s):
    return re.sub(r"\s+", " ", s).strip()


def strip_tags(s):
    return ws(html.unescape(re.sub(r"<[^>]+>", " ", s)))


def read_modules():
    mods = {}
    for pom in REPO.rglob("pom.xml"):
        if "target" in pom.parts:
            continue
        try:
            root = ET.parse(pom).getroot()
        except ET.ParseError:
            continue
        top = {c.tag.split("}")[-1]: c for c in root}
        parent = {c.tag.split("}")[-1]: c for c in top["parent"]} if "parent" in top else {}
        aid = top["artifactId"].text.strip() if "artifactId" in top else None
        g = top.get("groupId") if top.get("groupId") is not None else parent.get("groupId")
        if aid and g is not None:
            mods[pom.parent] = (f"{g.text.strip()}.{aid.replace('-', '.')}", aid)
    return mods


HEAD = re.compile(r"<h([234])[^>]*>\s*(?:Further reading|References?|Bibliography)\s*</h\1>", re.I)
ITEM = re.compile(r"<li[^>]*>(.*?)</li\s*>", re.I | re.S)


def extract_refs(text):
    out = []
    for m in HEAD.finditer(text):
        rest = text[m.end():]
        nxt = re.search(r"<h[1-6][\s>]|<!--\s*(?:params|footer):begin|<hr", rest, re.I)
        block = rest[:nxt.start()] if nxt else rest
        lm = re.search(r"<(ol|ul)[^>]*>(.*?)</\1\s*>", block, re.I | re.S)
        if lm:
            out += [ws(it.group(1)) for it in ITEM.finditer(lm.group(2))]
    return out


DOI_RE = re.compile(r"(?:doi:\s*|https?://(?:dx\.)?doi\.org/)(10\.\d{4,9}/[^\s<\"]+)", re.I)


def get_doi(s):
    m = DOI_RE.search(s)
    return m.group(1).rstrip(".,;)").lower() if m else None


def fallback_key(s):
    plain = strip_tags(s)
    sur = re.match(r"([^,]+),", plain)
    sur = sur.group(1).lower() if sur else plain.split()[0].lower()
    y = re.search(r"\((\d{4}[a-z]?)\)", plain) or re.search(r"\b(1[89]\d\d|20\d\d)\b", plain)
    y = y.group(1) if y else ""
    parts = re.split(r"\)\.?\s*", plain, maxsplit=1)
    title = re.sub(r"[^a-z0-9]", "", (parts[1] if len(parts) > 1 else plain).lower())[:40]
    return f"{sur}|{y}|{title}"


def is_house_style(s):
    plain = strip_tags(s)
    return bool(HOUSE_AUTHOR.search(plain) and HOUSE_YEAR.search(plain))


def completeness(s):
    sc = len(strip_tags(s))
    if is_house_style(s):  # a legacy variant never beats a house-style one
        sc += 2000
    if get_doi(s):
        sc += 1000
    if re.search(r"\d+\s*(?:&#8211;|&ndash;|-|–)\s*\d+", s):
        sc += 500
    if "&#8211;" in s:  # typographic en dash in page ranges beats a plain hyphen
        sc += 5
    return sc


def page_title(text, fallback):
    m = re.search(r"<h1[^>]*>(.*?)</h1>", text, re.I | re.S)
    if m:
        return strip_tags(m.group(1))
    m = re.search(r"<title>(.*?)</title>", text, re.I | re.S)
    return strip_tags(m.group(1)).replace("Help - ", "") if m else fallback


def header_title(text):
    m = re.search(r'<td[^>]*class="header"[^>]*>(.*?)</td>', text, re.I | re.S)
    if not m:
        return ""
    s = ws(strip_tags(m.group(1)).replace("\xa0", " "))
    return re.sub(r"\s*\(InSAR operator\)$", "", s).strip()


def disambiguate_titles(pages):
    """Operator pages whose <title>/<h1> is shared, equals the file stem or contains a quote get the
    header-cell text; any collision left after that gets the file stem appended."""
    count = Counter(p["title"].lower() for p in pages)
    for p in pages:
        if p["kind"] != "operator":
            continue
        t = p["title"]
        if count[t.lower()] > 1 or t == p["name"] or '"' in t:
            if p.get("header"):
                p["title"] = p["header"]
    count = Counter(p["title"].lower() for p in pages)
    for p in pages:
        if p["kind"] == "operator" and count[p["title"].lower()] > 1:
            p["title"] = "%s (%s)" % (p["title"], p["name"])


def op_topic(name, module):
    if module in MODULE_STRONG:
        return MODULE_TOPIC[module]
    toks = [t.lower() for t in re.findall(r"[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\d+", name)]
    for kw, tp in OP_KEYWORDS:
        if any(t == kw or (len(kw) >= 5 and t.startswith(kw)) for t in toks):
            return tp
    return MODULE_TOPIC.get(module, "sar")


def collect():
    mods = read_modules()
    pages = []
    for f in sorted(CONCEPTS.glob("*.html")):
        if f.name == "bibliography.html":
            continue
        t = read_text(f)
        pages.append(dict(kind="concept", name=f.stem, href=f.name, topic=CONCEPT_TOPIC.get(f.stem),
                          weight=3, refs=extract_refs(t), title=page_title(t, f.stem)))
    if WORKFLOWS.is_dir():
        for f in sorted(WORKFLOWS.glob("*.html")):
            t = read_text(f)
            pages.append(dict(kind="workflow", name=f.stem, href="../workflows/" + f.name,
                              topic=WORKFLOW_TOPIC.get(f.stem), weight=3, refs=extract_refs(t),
                              title=page_title(t, f.stem)))
    for f in sorted(REPO.rglob("docs/operators/*.html")):
        if "target" in f.parts:
            continue
        t = read_text(f)
        mroot = next((p for p in f.parents if p in mods), None)
        if mroot is None:
            continue
        cb, aid = mods[mroot]
        resdir = next(r for r in (mroot / "src/main/resources", mroot / "src/main/javahelp")
                      if str(f).startswith(str(r)))
        pages.append(dict(kind="operator", name=f.stem,
                          href=f"nbdocs://{cb}/{f.relative_to(resdir).as_posix()}",
                          topic=op_topic(f.stem, aid), weight=1, refs=extract_refs(t),
                          title=page_title(t, f.stem), header=header_title(t), module=aid))
    disambiguate_titles(pages)
    return pages


def build(pages):
    entries = {}
    for p in pages:
        for raw in p["refs"]:
            if NON_CITATION.search(strip_tags(raw)):
                continue
            doi = get_doi(raw)
            key = ("doi", doi) if doi else ("fb", fallback_key(raw))
            e = entries.setdefault(key, dict(key=key, variants=defaultdict(list), citers=[]))
            e["variants"][raw].append(p["href"])
            if p not in e["citers"]:
                e["citers"].append(p)
    # a DOI-less variant of a DOI'd entry merges via the fallback key
    fb_of_doi = {}
    for k, e in entries.items():
        if k[0] == "doi":
            for raw in e["variants"]:
                fb_of_doi[fallback_key(raw)] = k
    for k in [k for k in entries if k[0] == "fb"]:
        tgt = fb_of_doi.get(k[1])
        if tgt:
            e = entries.pop(k)
            for raw, hs in e["variants"].items():
                entries[tgt]["variants"][raw].extend(hs)
            for p in e["citers"]:
                if p not in entries[tgt]["citers"]:
                    entries[tgt]["citers"].append(p)
    merge_similar(entries)
    for e in entries.values():
        e["best"] = max(e["variants"], key=completeness)
        e["doi"] = get_doi(e["best"])
        votes = Counter()
        for p in e["citers"]:
            votes[p["topic"] or "sar"] += p["weight"]
        top = sorted(votes.items(), key=lambda kv: (-kv[1], SEC_IDS.index(kv[0])))
        e["topic"] = top[0][0]
        plain = strip_tags(e["best"])
        for rx, tp in OVERRIDE_PATTERNS:
            if re.search(rx, plain):
                e["topic"] = tp
                break
        if not e["doi"] and DOCS_PAT.search(plain) and not JOURNAL_PAT.search(plain):
            e["topic"] = "docs"
    return entries


def norm_text(raw):
    return re.sub(r"[^a-z0-9]", "", strip_tags(raw).lower())


def words(raw):
    return {w for w in re.findall(r"[a-z]{4,}", strip_tags(raw).lower())}


def first_surname(raw):
    head = strip_tags(raw).split(",")[0]
    toks = [re.sub(r"[^a-z]", "", t) for t in head.lower().split()]
    toks = [t for t in toks if len(t) >= 3]
    return max(toks, key=len) if toks else ""


def _similar(ea, eb):
    """Same year, first-author surname in both, and word overlap (Jaccard) >= 0.5."""
    ra = max(ea["variants"], key=completeness)
    rb = max(eb["variants"], key=completeness)
    ya = set(re.findall(r"\b(1[89]\d\d|20\d\d)\b", strip_tags(ra)))
    yb = set(re.findall(r"\b(1[89]\d\d|20\d\d)\b", strip_tags(rb)))
    if not (ya & yb):
        return False
    sa, sb = first_surname(ra), first_surname(rb)
    if not sa or sa not in norm_text(rb) or (sb and sb not in norm_text(ra)):
        return False
    wa, wb = words(ra), words(rb)
    return len(wa & wb) / max(1, len(wa | wb)) >= 0.5


def merge_similar(entries):
    """Merge differently formatted copies of one citation: same year, first-author surname and
    title snippet (the parsed title of one copy occurs in the other).  Two DOIs never merge."""
    changed = True
    while changed:
        changed = False
        for a in list(entries):
            for b in list(entries):
                if a == b or a not in entries or b not in entries or (a[0] == "doi" and b[0] == "doi"):
                    continue
                if _similar(entries[a], entries[b]):
                    tgt, src = (a, b) if a[0] == "doi" or b[0] != "doi" else (b, a)
                    for raw, hs in entries[src]["variants"].items():
                        entries[tgt]["variants"][raw].extend(hs)
                    for p in entries[src]["citers"]:
                        if p not in entries[tgt]["citers"]:
                            entries[tgt]["citers"].append(p)
                    del entries[src]
                    changed = True
                    break
            if changed:
                break


def sort_key(e):
    plain = strip_tags(e["best"]).lower()
    m = re.search(r"(1[89]\d\d|20\d\d)", plain)
    head = plain.split(",")[0] if is_house_style(e["best"]) else first_surname(e["best"])
    return (head, m.group(1) if m else "", plain)


def asciify(s):
    return "".join(c if ord(c) < 128 else "&#%d;" % ord(c) for c in s)


def esc(s):
    return asciify(s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def links_for(e):
    order = {"concept": 0, "workflow": 1, "operator": 2}
    cs = sorted(e["citers"], key=lambda p: (order[p["kind"]], p["title"].lower()))
    s = "Cited on: " + ", ".join('<a href="%s">%s</a>' % (p["href"], esc(p["title"])) for p in cs[:MAX_LINKS])
    if len(cs) > MAX_LINKS:
        s += " and %d more" % (len(cs) - MAX_LINKS)
    return s + "."


def render(entries):
    by = defaultdict(list)
    for e in entries.values():
        by[e["topic"]].append(e)
    L = []
    a = L.append
    a('<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">')
    a("<html>")
    a("<head>")
    a("    <title>Help - Bibliography</title>")
    a('    <meta http-equiv="Content-Type" content="text/html; charset=iso-8859-1">')
    a('    <link rel="stylesheet" href="../style.css">')
    a('    <style type="text/css">')
    a("        p.lead { font-size: 13; margin-top: 4pt; margin-bottom: 10pt; }")
    a("        p.eq { font-family: Times New Roman, serif; font-size: 14; margin-left: 30; }")
    a("        p.figcap { font-size: 11; color: #444444; margin-left: 6pt; margin-top: 2pt; }")
    a("        th { background-color: #E8E8E8; }")
    a("    </style>")
    a("</head>")
    a("<body>")
    a('<table class="header" width="100%">')
    a("    <tbody>")
    a('    <tr class="header">')
    a('        <td class="header">&nbsp;&nbsp;SAR Concepts: Bibliography</td>')
    a('        <td class="header" align="right"><a href="nbdocs://org.esa.snap.snap.help/org/esa/snap/snap/help/docs/general/overview/SnapOverview.html"><img src="../images/snap_header.jpg" border="0"></a></td>')
    a("    </tr>")
    a("    </tbody>")
    a("</table>")
    a("")
    a("<h1>Bibliography</h1>")
    a("")
    a('<p class="lead">This page lists the literature cited in the References sections of the Microwave '
      "Toolbox SAR concept pages and operator pages, grouped by topic. Journal articles and books with a DOI "
      "were checked against Crossref. Technical notes and product specifications are cited as issued by the "
      "space agency or mission operator. Each entry links to the pages that cite it.</p>")
    a("")
    a("<h2>Topics</h2>")
    a("<ul>")
    for sid, title in SECTIONS:
        if by.get(sid):
            a('    <li><a href="#%s">%s</a> (%d)</li>' % (sid, title, len(by[sid])))
    a("</ul>")
    for sid, title in SECTIONS:
        items = sorted(by.get(sid, []), key=sort_key)
        if not items:
            continue
        a("")
        a('<h2><a name="%s"></a>%s</h2>' % (sid, title))
        a("<ul>")
        for e in items:
            a("    <li>%s<br>" % asciify(e["best"]))
            a("    <i>%s</i></li>" % links_for(e))
        a("</ul>")
    a("")
    a("<hr>")
    a("</body>")
    a("</html>")
    return "\r\n".join(L) + "\r\n"


ANNOT_LEAD = re.compile(r"^[^,]{0,80}:\s+[A-Z][\w'&;. -]*, [A-Z]\.")
ANNOT_WORDS = re.compile(r"not implemented|not independently verified|as cited in", re.I)
TRAIL_PAREN = re.compile(r"\(([^()]*)\)\.?\s*$")
TRAIL_OK = re.compile(r"^(?:\d{4}[a-z]?|[A-Z0-9][A-Z0-9/ \-]*|ISBN.*|.*\b(?:ed|Ed|edition)\.?)$")


def annotation_problem(raw):
    """Why a reference item looks like an annotated citation, else None."""
    plain = strip_tags(raw)
    if ANNOT_LEAD.search(plain):
        return "text before the first author ends in a colon"
    if ANNOT_WORDS.search(plain):
        return "annotation wording (not implemented / not independently verified / as cited in)"
    m = TRAIL_PAREN.search(plain)
    if m and not TRAIL_OK.match(m.group(1).strip()):
        return "trailing parenthesis that is not a year, ISBN, edition or document id"
    return None


def check_problems(entries, rendered, current):
    """Messages for --check: stale page, entries spelled differently on different pages, annotations."""
    out = []
    # line endings aside: a Linux checkout keeps the LF that git stores, the page is rendered with CRLF
    if current is None or current.replace(b"\r\n", b"\n") != rendered.replace(b"\r\n", b"\n"):
        out.append("bibliography.html is stale; run: python -I tools/javahelp/build_bibliography.py")
    for e in sorted(entries.values(), key=sort_key):
        label = strip_tags(e["best"])[:70]
        if len(e["variants"]) > 1:
            names = sorted({h.split("/")[-1] for hs in e["variants"].values() for h in hs})
            out.append("variants: %d spellings of '%s' (pages: %s); make them identical"
                       % (len(e["variants"]), label, ", ".join(names[:5])))
        for raw, hs in e["variants"].items():
            why = annotation_problem(raw)
            if why:
                out.append("annotation: %s in '%s' (page: %s)" % (why, strip_tags(raw)[:70], hs[0].split("/")[-1]))
    return out


def run_check(repo=None):
    if repo is not None:
        configure(repo)
    entries = build(collect())
    rendered = render(entries).encode("ascii")
    current = OUT.read_bytes() if OUT.exists() else None
    return check_problems(entries, rendered, current)


def write_report(path, pages, entries, counts):
    kinds = Counter(p["kind"] for p in pages)
    L = ["# Task 5.1 - Bibliography report", "",
         "Generated by `tools/javahelp/build_bibliography.py`.", "",
         "Pages scanned: %d (concept %d, workflow %d, operator %d). Unique entries: %d." % (
             len(pages), kinds["concept"], kinds["workflow"], kinds["operator"], len(entries)), "",
         "## Unique entries per section", ""]
    for sid, title in SECTIONS:
        L.append("- %s: %d" % (strip_tags(title), counts.get(sid, 0)))
    L += ["", "## Duplicates with differing text", ""]
    nd = 0
    for e in sorted(entries.values(), key=sort_key):
        if len(e["variants"]) > 1:
            nd += 1
            L.append("### %s" % (e["doi"] or e["key"][1]))
            for raw, hs in e["variants"].items():
                names = ", ".join(h.split("/")[-1] for h in hs[:3]) + (" +%d" % (len(hs) - 3) if len(hs) > 3 else "")
                L.append("- %s%s" % (names, "  **(kept)**" if raw == e["best"] else ""))
                L.append("  `%s`" % raw)
            L.append("")
    if not nd:
        L.append("None.")
    L += ["", "## Entries without DOI", ""]
    for e in sorted(entries.values(), key=sort_key):
        if not e["doi"]:
            L.append("- [%s] %s (cited: %s)" % (e["topic"], strip_tags(e["best"]),
                                                ", ".join(p["name"] for p in e["citers"][:4])))
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text("\n".join(L) + "\n", encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", action="store_true")
    ap.add_argument("--no-write", action="store_true")
    ap.add_argument("--check", action="store_true",
                    help="write nothing; exit 1 if the page is stale, entries disagree or carry annotations")
    ap.add_argument("--report")
    args = ap.parse_args()
    if args.check:
        problems = run_check()
        for m in problems:
            print(m)
        print("%d bibliography problem(s)" % len(problems))
        raise SystemExit(1 if problems else 0)
    pages = collect()
    entries = build(pages)
    if args.dump:
        for e in sorted(entries.values(), key=lambda e: (SEC_IDS.index(e["topic"]), sort_key(e))):
            print(e["topic"], "|", strip_tags(e["best"])[:100], "|",
                  [(p["name"][:18]) for p in e["citers"][:3]])
    if not args.no_write:
        OUT.write_bytes(render(entries).encode("ascii"))
    counts = Counter(e["topic"] for e in entries.values())
    print("pages scanned:", len(pages), "entries:", len(entries))
    for sid, _ in SECTIONS:
        print("  %-11s %d" % (sid, counts.get(sid, 0)))
    if args.report:
        write_report(args.report, pages, entries, counts)


if __name__ == "__main__":
    main()
