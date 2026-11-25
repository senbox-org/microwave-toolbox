#!/usr/bin/env python3
"""Structural integrity check for the Microwave Toolbox JavaHelp.

Checks (all case-sensitive, so problems that only show up inside the JAR or on
Linux are caught on Windows too):

  registration  every help.hs is registered exactly once (@HelpSetRegistration
                package-info or a layer.xml JavaHelp helpset.xml reference)
  map           every map.jhm url exists; no duplicate map IDs across helpsets
  toc           every toc.xml target exists in its helpset's map
  orphan        every page is mapped and every mapped page is in a TOC
  helpid        every helpId used in layer.xml / Java resolves to a map ID
  link          every relative href/src and nbdocs:// link in a page resolves
  html          every page has exactly one <title>, inside <head>
  params        every generated parameter block (<!-- params:begin op=... -->) matches
                the operator source; see gen_params.py
  refs          every operator page has a References section (heading containing
                "Reference" followed by a list item or a paragraph with a 4-digit
                year) outside the generated params/footer blocks, or is listed in
                refs_exempt.txt as `path|reason`
  biblio        concepts/bibliography.html is up to date with the References sections, every
                entry is spelled identically on every page that cites it, and no reference
                item carries annotation text; see build_bibliography.py --check
  style         outdated terminology and dead/legacy references in the visible text
                of help pages (master/slave wording, dead domains, stale product
                statements); see style_allow.txt and the README
  index         every help.hs declares the Index view (javax.help.IndexView), every
                index.xml target is a map ID of its own helpset, and every index.xml
                is up to date; see gen_index.py
  search        at most one @HelpSetRegistration helpset with a Search view per module (the
                build-time indexer only works once per javac run; a second helpset gets an
                empty JavaHelpSearch and the whole Help window fails with "view is invalid"),
                and, when the module's jar is built, every Search view's JavaHelpSearch in
                target/*.jar is complete (DOCS, DOCS.TAB, OFFSETS, POSITIONS, SCHEMA, TMAP)

Usage:  python tools/javahelp/check_javahelp.py [--repo DIR] [--external DIR ...]

--external points at sibling repos (default: snap-desktop, snap-engine and
optical-toolbox next to this repo, when present) so nbdocs:// links and
helpIds into SNAP core can be resolved. Unresolvable external targets whose
repo is absent are skipped, not reported.

Known, intentional exceptions live in allowlist.txt next to this script as
`category|key|reason` lines. Exit status is 1 when any error remains.
"""
from __future__ import annotations

import argparse
import os
import hashlib
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
SKIP_DIRS = {"target", ".git", "node_modules", ".idea"}



# ---------------------------------------------------------------- helpers ---

def exists_exact(path: Path) -> bool:
    """True if `path` exists with exactly this letter case (Windows-safe)."""
    path = Path(os.path.normpath(path))
    if not path.exists():
        return False
    parts = path.parts
    cur = Path(parts[0])
    for part in parts[1:]:
        try:
            names = os.listdir(cur)
        except OSError:
            return False
        if part not in names:
            return False
        cur = cur / part
    return True


def walk(root: Path, filename_pred):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for f in filenames:
            if filename_pred(f):
                yield Path(dirpath) / f


def parse_xml(path: Path):
    text = path.read_text(encoding="utf-8", errors="replace")
    text = re.sub(r"<!DOCTYPE[^>]*>", "", text, flags=re.S)  # no DTD fetches
    return ET.fromstring(text.split("?>", 1)[1] if text.lstrip().startswith("<?xml") else text)


class Module:
    def __init__(self, root: Path, codebase: str):
        self.root = root
        self.codebase = codebase
        self.res_roots = [root / "src/main/resources", root / "src/main/javahelp"]


def read_modules(repo: Path) -> dict[str, Module]:
    """Map NetBeans code-name-base (groupId + artifactId with '-'->'.') to module."""
    modules = {}
    for pom in walk(repo, lambda f: f == "pom.xml"):
        try:
            root = ET.parse(pom).getroot()
        except ET.ParseError:
            continue
        top = {c.tag.split("}")[-1]: c for c in root}  # poms with and without xmlns
        parent = {c.tag.split("}")[-1]: c for c in top["parent"]} if "parent" in top else {}
        aid = top["artifactId"].text.strip() if "artifactId" in top else None
        g = top.get("groupId") if top.get("groupId") is not None else parent.get("groupId")
        gid = g.text.strip() if g is not None else None
        if not aid or not gid:
            continue
        cb = f"{gid}.{aid.replace('-', '.')}"
        modules[cb] = Module(pom.parent, cb)
    return modules


class Helpset:
    def __init__(self, hs: Path, module_root: Path):
        self.hs = hs
        self.dir = hs.parent
        self.module_root = module_root
        res = next(r for r in (module_root / "src/main/resources", module_root / "src/main/javahelp")
                   if str(hs).startswith(str(r)))
        self.pkg_path = self.dir.relative_to(res).as_posix()  # e.g. eu/esa/sar/sar/docs
        self.map: dict[str, str] = {}
        self.dup_ids: list[str] = []
        self.toc_targets: list[str] = []

    @property
    def name(self):
        return self.pkg_path


# ----------------------------------------------------------------- checks ---

class Checker:
    def __init__(self, repo: Path, externals: list[Path]):
        self.repo = repo
        self.externals = [e for e in externals if e.is_dir()]
        self.errors: list[tuple[str, str, str]] = []  # (category, key, message)
        self.allow = self._read_allowlist()
        self.modules = read_modules(repo)
        self.ext_modules: dict[str, Module] = {}
        for e in self.externals:
            self.ext_modules.update(read_modules(e))
        self.helpsets = self._find_helpsets(repo)
        self.ext_map_ids = self._external_map_ids()

    # -- bookkeeping
    def _read_allowlist(self):
        allow = set()
        f = HERE / "allowlist.txt"
        if f.exists():
            for line in f.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line and not line.startswith("#"):
                    cat, key, *_ = line.split("|")
                    allow.add((cat.strip(), key.strip()))
        return allow

    def err(self, cat, key, msg):
        if (cat, key) not in self.allow:
            self.errors.append((cat, key, msg))

    def rel(self, p: Path) -> str:
        try:
            return p.relative_to(self.repo).as_posix()
        except ValueError:
            return p.as_posix()

    # -- discovery
    def _find_helpsets(self, repo: Path) -> list[Helpset]:
        out = []
        for hs in walk(repo, lambda f: f == "help.hs"):
            mod = hs
            while not (mod / "pom.xml").exists():
                mod = mod.parent
            h = Helpset(hs, mod)
            m = h.dir / "map.jhm"
            if m.exists():
                for e in parse_xml(m).iter("mapID"):
                    if e.get("target") in h.map:
                        h.dup_ids.append(e.get("target"))
                    h.map[e.get("target")] = e.get("url")
            t = h.dir / "toc.xml"
            if t.exists():
                h.toc_targets = [e.get("target") for e in parse_xml(t).iter("tocitem") if e.get("target")]
            out.append(h)
        return out

    def _external_map_ids(self) -> dict[str, str]:
        """map ID -> defining map.jhm, for helpsets of sibling repos (SNAP core, optical)."""
        ids = {}
        for e in self.externals:
            for m in walk(e, lambda f: f == "map.jhm"):
                try:
                    for x in parse_xml(m).iter("mapID"):
                        ids.setdefault(x.get("target"), m.relative_to(e.parent).as_posix())
                except ET.ParseError:
                    pass
        return ids

    # -- registration
    def check_registration(self):
        refs = defaultdict(list)  # pkg_path -> [how]
        for java in walk(self.repo, lambda f: f == "package-info.java"):
            txt = java.read_text(encoding="utf-8", errors="replace")
            if "@HelpSetRegistration" in txt:
                m = re.search(r"package\s+([\w.]+)\s*;", txt)
                hs = re.search(r'helpSet\s*=\s*"([^"]+)"', txt)
                if m and hs:
                    refs[m.group(1).replace(".", "/")].append(f"{self.rel(java)} ({hs.group(1)})")
        for x in walk(self.repo, lambda f: f.endswith(".xml") and "helpset" in f.lower()):
            txt = x.read_text(encoding="utf-8", errors="replace")
            m = re.search(r'helpsetref\s+url="nbdocs:/+([^"]+)/help\.hs"', txt)
            if m:
                refs[m.group(1)].append(self.rel(x))
        for h in self.helpsets:
            n = len(refs.get(h.pkg_path, []))
            if n == 0:
                self.err("registration", h.pkg_path, f"helpset {self.rel(h.hs)} is never registered")
            elif n > 1:
                self.err("registration", h.pkg_path,
                         f"helpset {h.pkg_path} registered {n}x: {', '.join(refs[h.pkg_path])}")

    # -- map / toc / orphans
    def check_maps_and_tocs(self):
        owner = defaultdict(list)
        for h in self.helpsets:
            for mid, url in h.map.items():
                owner[mid].append(h.name)
                if not exists_exact(h.dir / url.split("#")[0]):
                    self.err("map", f"{h.name}:{mid}", f"{h.name}/map.jhm: {mid} -> {url} (missing file)")
            for t in h.toc_targets:
                if t not in h.map:
                    self.err("toc", f"{h.name}:{t}", f"{h.name}/toc.xml: target '{t}' not in map")
            mapped_files = {u.split("#")[0] for u in h.map.values()}
            for page in walk(h.dir, lambda f: f.lower().endswith((".html", ".htm"))):
                rp = page.relative_to(h.dir).as_posix()
                if rp not in mapped_files:
                    self.err("orphan", f"{h.name}/{rp}", f"{h.name}/{rp} is not in map.jhm")
            in_toc = set(h.toc_targets)
            files_in_toc = {h.map[t].split("#")[0] for t in in_toc if t in h.map}
            for mid, url in h.map.items():
                if mid not in in_toc and url.split("#")[0] not in files_in_toc:
                    self.err("orphan", f"{h.name}:{mid}", f"{h.name}: '{mid}' ({url}) is mapped but in no TOC entry")
        for h in self.helpsets:
            for mid in h.dup_ids:
                self.err("map", f"dup:{mid}", f"{h.name}/map.jhm defines '{mid}' more than once")
        for mid, hs in owner.items():
            if len(hs) > 1:
                self.err("map", f"dup:{mid}", f"map ID '{mid}' defined in several helpsets: {hs}")
            if mid in self.ext_map_ids:
                self.err("map", f"dup:{mid}",
                         f"map ID '{mid}' ({hs[0]}) collides with SNAP/optical {self.ext_map_ids[mid]}")

    # -- help IDs used by actions / UIs
    def check_help_ids(self):
        ids = set(self.ext_map_ids)
        for h in self.helpsets:
            ids.update(h.map)
        pat_layer = re.compile(r'name="helpId"\s+stringvalue="([^"]+)"')
        pat_java = re.compile(r'(?:helpId\s*=\s*|setHelpId\(\s*|new\s+HelpCtx\(\s*)"([^"]+)"')
        for f in walk(self.repo, lambda n: n == "layer.xml" or n.endswith(".java")):
            if "/test/" in f.as_posix():
                continue
            txt = f.read_text(encoding="utf-8", errors="replace")
            if f.name != "layer.xml":  # blank out // comments, keep offsets for line numbers
                txt = re.sub(r"//[^\n]*", lambda c: " " * len(c.group(0)), txt)
            for m in (pat_layer if f.name == "layer.xml" else pat_java).finditer(txt):
                hid = m.group(1)
                if hid not in ids:
                    line = txt.count("\n", 0, m.start()) + 1
                    self.err("helpid", hid, f"{self.rel(f)}:{line}: helpId '{hid}' resolves to no map ID")

    # -- links inside pages
    def _resolve_nbdocs(self, url: str):
        """Return True/False if resolvable/broken, None if target repo unavailable."""
        m = re.match(r"nbdocs:/+([^/]+)/(.+)", url)
        if not m:
            return False
        cb, path = m.group(1), m.group(2).split("#")[0]
        mod = self.modules.get(cb) or self.ext_modules.get(cb)
        if mod is None:
            local = cb.startswith(("eu.esa.microwavetbx", "org.jlinda", "org.csa.rstb"))
            return False if local or self.externals else None
        return any(exists_exact(r / path) for r in mod.res_roots)

    def check_links(self):
        attr = re.compile(r'(?:href|src)\s*=\s*"([^"]+)"', re.I)
        for h in self.helpsets:
            for page in walk(h.dir, lambda f: f.lower().endswith((".html", ".htm"))):
                txt = page.read_text(encoding="utf-8", errors="replace")
                for m in attr.finditer(txt):
                    url = m.group(1).strip()
                    if re.match(r"(https?|mailto|ftp|javascript):|#", url, re.I):
                        continue
                    line = txt.count("\n", 0, m.start()) + 1
                    key = f"{self.rel(page)}:{url}"
                    if url.lower().startswith("nbdocs:"):
                        ok = self._resolve_nbdocs(url)
                    else:
                        ok = exists_exact(page.parent / url.split("#")[0].split("?")[0])
                    if ok is False:
                        self.err("link", key, f"{self.rel(page)}:{line}: broken link '{url}'")

    # -- minimal html sanity
    def check_html(self):
        for h in self.helpsets:
            for page in walk(h.dir, lambda f: f.lower().endswith((".html", ".htm"))):
                txt = page.read_text(encoding="utf-8", errors="replace").lower()
                n = len(re.findall(r"<title[\s>]", txt))
                head_end = txt.find("</head>")
                t = txt.find("<title")
                if n != 1:
                    self.err("html", self.rel(page), f"{self.rel(page)}: {n} <title> elements (expected 1)")
                elif head_end == -1 or t > head_end:
                    self.err("html", self.rel(page), f"{self.rel(page)}: <title> is not inside <head>")
                body = txt.find("<body")
                if body != -1 and head_end > body:
                    self.err("html", self.rel(page), f"{self.rel(page)}: <head> comes after <body>")
                if len(re.findall(r"<html[\s>]", txt)) > 1:
                    self.err("html", self.rel(page), f"{self.rel(page)}: more than one <html> element")

    # -- generated parameter tables
    def check_params(self):
        if str(HERE) not in sys.path:  # `python -I` drops the script folder from sys.path
            sys.path.insert(0, str(HERE))
        import gen_params
        for msg in gen_params.check(self.repo):
            m = re.match(r"(.*?): .*?'([^']*)'", msg)  # key: <page or params_pages.txt>:<alias>
            self.err("params", f"{m.group(1)}:{m.group(2)}" if m else msg, msg)

    # -- literature references on operator pages
    refs_exempt_path = HERE / "refs_exempt.txt"
    _BLOCKS = re.compile(r"<!--\s*(params|footer):begin\b.*?<!--\s*(?:params|footer):end\s*-->", re.S | re.I)
    _HEADING = re.compile(r"<(h[1-4])\b[^>]*>(.*?)</\1\s*>|<(b|strong)\b[^>]*>(.*?)</\3\s*>", re.S | re.I)

    @classmethod
    def page_has_references(cls, text: str) -> bool:
        text = cls._BLOCKS.sub("", text)
        for m in cls._HEADING.finditer(text):
            label = re.sub(r"<[^>]+>", "", m.group(2) if m.group(1) else m.group(4))
            if "reference" not in label.lower():
                continue
            if m.group(3) and len(label.strip()) > 40:  # a long bold run is prose, not a heading
                continue
            rest = text[m.end():]
            if re.search(r"<li[\s>]", rest, re.I):
                return True
            if any(re.search(r"(?<!\d)\d{4}(?!\d)", re.sub(r"<[^>]+>", "", p))
                   for p in re.findall(r"<p(?:\s[^>]*)?>(.*?)</p\s*>", rest, re.S | re.I)):
                return True
        return False

    def _operator_pages(self):
        for page in walk(self.repo, lambda f: f.lower().endswith(".html")):
            parts = page.relative_to(self.repo).parts[:-1]
            pairs = set(zip(parts, parts[1:]))
            if ("docs", "operators") in pairs or (
                    "sar-op-analysis-ui" in parts and ({("docs", "rcp"), ("docs", "wave")} & pairs)):
                yield page

    def check_biblio(self):
        concepts = self.repo / "microwavetbx-rcp/src/main/resources/eu/esa/microwave/dat/docs/concepts"
        if not concepts.is_dir():
            return
        if str(HERE) not in sys.path:
            sys.path.insert(0, str(HERE))
        import build_bibliography as bb
        for msg in bb.run_check(self.repo):
            self.err("biblio", hashlib.sha1(msg.encode("utf-8")).hexdigest()[:10], msg)

    def check_refs(self):
        exempt = {}
        if self.refs_exempt_path.exists():
            for line in self.refs_exempt_path.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line and not line.startswith("#"):
                    path, _, reason = line.partition("|")
                    exempt[path.strip()] = reason.strip()
        pages = {self.rel(p): p for p in self._operator_pages()}
        for rp, page in pages.items():
            if rp in exempt:
                continue
            if not self.page_has_references(page.read_text(encoding="utf-8", errors="replace")):
                self.err("refs", rp, f"{rp}: no References section")
        for path in exempt:
            if not exists_exact(self.repo / path):
                self.err("refs", path, f"{path}: refs_exempt.txt entry for missing page")

    # -- outdated terminology and legacy references (visible text only)
    style_allow_path = HERE / "style_allow.txt"
    _STYLE_HIDDEN = re.compile(
        r"<!--.*?-->|<(code|tt|pre|kbd)\b[^>]*>.*?</\1\s*>", re.S | re.I)
    _STYLE_PARAMS = re.compile(r"<!--\s*params:begin\b.*?<!--\s*params:end\s*-->", re.S | re.I)
    _STYLE_TAG = re.compile(r"<[^>]*>", re.S)
    _STYLE_URL_ATTR = re.compile(r"""\b(?:href|src)\s*=\s*(?:"([^"]*)"|'([^']*)')""", re.I)
    STYLE_RULES = {
        "S1": re.compile(r"\b(?:masters?|slaves?|mst|slv)\b", re.I),
        "S2": re.compile(
            r"C:\\Program Files\\NEST|sentinel\.esa\.int|earth\.esa\.int/pub|envisat\.esa\.int|"
            r"nest\.array\.ca|directory\.eoportal\.org|xftp\.jrc\.it|desktopdefault\.aspx|"
            r"NEST4A|\.s1tbx|s1tbx", re.I),
        "S3": re.compile(
            r"SRTM 3Sec \(Auto Download\)"
            r"|SRTM.{0,80}?(?:default DEM|default is SRTM)|(?:default DEM|default is SRTM).{0,80}?SRTM"
            r"|Sentinel-1B.{0,80}?(?:present|operational)"
            r"|Windows XP", re.I | re.S),
    }

    @staticmethod
    def _blank(m) -> str:
        return re.sub(r"[^\n]", " ", m.group(0))

    def _style_allow_patterns(self):
        pats = []
        if self.style_allow_path.exists():
            for line in self.style_allow_path.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line and not line.startswith("#"):
                    tok = line.partition("|")[0].strip()
                    if tok:
                        pats.append(re.compile(r"\s+".join(re.escape(w) for w in tok.split())))
        return pats

    def _style_pages(self):
        for page in walk(self.repo, lambda f: f.lower().endswith((".html", ".htm"))):
            parts = page.relative_to(self.repo).parts
            if "docs" in parts[:-1] and parts[0] != "docs" and not parts[0].startswith("."):
                yield page

    @classmethod
    def style_findings(cls, text: str, allow_pats=()):
        """[(rule, line, phrase, context, line_hash)] for one page's text."""
        scrub = cls._STYLE_PARAMS.sub(cls._blank, text)
        scrub = cls._STYLE_HIDDEN.sub(cls._blank, scrub)
        urls = [(m.start(1 if m.group(1) is not None else 2), m.group(1) if m.group(1) is not None else m.group(2))
                for m in cls._STYLE_URL_ATTR.finditer(scrub)]
        visible = cls._STYLE_TAG.sub(cls._blank, scrub)
        lines = visible.split("\n")
        allowed = [m.span() for pat in allow_pats for m in pat.finditer(visible)]
        out = []

        def add(rule, pos, phrase, src):
            line = text.count("\n", 0, pos) + 1
            a = max(0, pos - 40)
            ctx = re.sub(r"\s+", " ", src[a:pos + len(phrase) + 40]).strip()
            norm = re.sub(r"\s+", " ", lines[line - 1] if src is visible else ctx).strip()
            out.append((rule, line, re.sub(r"\s+", " ", phrase), ctx,
                        hashlib.sha1(norm.encode("utf-8")).hexdigest()[:8]))

        for rule, rx in cls.STYLE_RULES.items():
            for m in rx.finditer(visible):
                if any(a <= m.start() and m.end() <= b for a, b in allowed):
                    continue
                add(rule, m.start(), m.group(0), visible)
        for pos, url in urls:  # dead domains also count inside href/src values
            for m in cls.STYLE_RULES["S2"].finditer(url):
                add("S2", pos + m.start(), m.group(0), scrub)
        return sorted(out, key=lambda f: (f[1], f[0]))

    def check_style(self):
        allow_pats = self._style_allow_patterns()
        for page in self._style_pages():
            rp = self.rel(page)
            for rule, line, phrase, ctx, h in self.style_findings(
                    page.read_text(encoding="utf-8", errors="replace"), allow_pats):
                if ("style", f"{rp}:{rule}") in self.allow:
                    continue
                self.err("style", f"{rp}:{rule}:{h}",
                         f"{rp}:{line}: {rule} '{phrase}' in \"...{ctx}...\" [{rule}:{h}]")

    # -- keyword index (JavaHelp IndexView)
    _INDEX_VIEW = re.compile(r"<view\b[^>]*>(?:(?!</view>).)*?<type>\s*javax\.help\.IndexView\s*</type>"
                             r"(?:(?!</view>).)*?<data[^>]*>\s*([^<\s]+)\s*</data>", re.S)

    def check_index(self):
        """Index views and targets. JavaHelp resolves an index item with Map.ID.create(target, hs)
        on the index's own helpset, so a target must be a map ID of that same helpset."""
        if str(HERE) not in sys.path:
            sys.path.insert(0, str(HERE))
        import gen_index
        for h in self.helpsets:
            m = self._INDEX_VIEW.search(h.hs.read_text(encoding="utf-8", errors="replace"))
            if not m:
                self.err("index", f"{h.name}:view", f"{self.rel(h.hs)}: no javax.help.IndexView view")
                continue
            idx = h.dir / m.group(1)
            if not exists_exact(idx):
                self.err("index", f"{h.name}:{m.group(1)}", f"{self.rel(h.hs)}: Index view data '{m.group(1)}' missing")
                continue
            try:
                targets = gen_index.index_targets(idx)
            except ET.ParseError as e:
                self.err("index", f"{h.name}:{m.group(1)}", f"{self.rel(idx)}: not well-formed XML ({e})")
                continue
            for t in targets:
                if t not in h.map:
                    self.err("index", f"{h.name}:{t}", f"{self.rel(idx)}: target '{t}' is not a map ID of this helpset")
        for msg in gen_index.check(self.repo):
            self.err("index", msg.split(":", 1)[0], msg)

    # -- full-text search database (JavaHelp SearchView)
    _SEARCH_VIEW = re.compile(r"<view\b[^>]*>(?:(?!</view>).)*?<type>\s*javax\.help\.SearchView\s*</type>"
                              r"(?:(?!</view>).)*?</view>", re.S)
    _VIEW_DATA = re.compile(r"<data\b[^>]*>\s*([^<\s]+)\s*</data>")
    SEARCH_DB_FILES = ("DOCS", "DOCS.TAB", "OFFSETS", "POSITIONS", "SCHEMA", "TMAP")

    def _search_db(self, h: Helpset) -> str | None:
        """Name of the helpset's search database folder, or None without a Search view."""
        m = self._SEARCH_VIEW.search(h.hs.read_text(encoding="utf-8", errors="replace"))
        d = self._VIEW_DATA.search(m.group(0)) if m else None
        return d.group(1) if d else None

    def check_search(self):
        """The @HelpSetRegistration processor indexes each helpset with JavaHelp's Indexer, whose
        indexer kit is a static singleton: only the first helpset indexed in a javac run gets
        a usable database. A broken database makes JHelp throw "view is invalid" for all help."""
        by_module = defaultdict(list)
        for h in self.helpsets:
            db = self._search_db(h)
            if db:
                by_module[h.module_root].append((h, db))
        for mod, hss in by_module.items():
            annotated = [h for h, _ in hss if "@HelpSetRegistration" in self._read(
                mod / "src/main/java" / h.pkg_path / "package-info.java")]
            if len(annotated) > 1:
                self.err("search", self.rel(mod),
                         f"{self.rel(mod)}: {len(annotated)} @HelpSetRegistration helpsets with a Search view "
                         f"({', '.join(h.pkg_path for h in annotated)}); only the first gets a search index, "
                         "merge them into one helpset")
            # resources/ help ships in the module jar; javahelp/ help is indexed by nbm-maven-plugin
            # into a separate docs jar (modules/docs/<cnb>.jar)
            main = [j for j in sorted((mod / "target").glob("*.jar"))
                    if not j.name.endswith(("-tests.jar", "-sources.jar", "-javadoc.jar"))]
            docs = sorted((mod / "target/nbm/clusters").glob("*/modules/docs/*.jar"))
            for h, db in hss:
                from_javahelp = str(h.hs).startswith(str(mod / "src/main/javahelp"))
                self._check_search_jars(h, db, docs if from_javahelp else main)

    def _check_search_jars(self, h: Helpset, db: str, jars: list[Path]):
        for jar in jars:
            try:
                with zipfile.ZipFile(jar) as z:
                    sizes = {i.filename: i.file_size for i in z.infolist()}
            except (zipfile.BadZipFile, OSError):
                continue
            if f"{h.pkg_path}/{h.hs.name}" not in sizes:
                continue  # this jar does not carry the helpset
            base = f"{h.pkg_path}/{db}/"
            missing = [f for f in self.SEARCH_DB_FILES if base + f not in sizes]
            if missing or sizes.get(base + "DOCS") == 0:
                what = f"missing {', '.join(missing)}" if missing else "DOCS is empty (no pages indexed)"
                self.err("search", f"{h.pkg_path}:{jar.name}",
                         f"{self.rel(jar)}: search database {base} is broken: {what}")

    @staticmethod
    def _read(p: Path) -> str:
        return p.read_text(encoding="utf-8", errors="replace") if p.exists() else ""

    def run(self, only: set[str] | None = None):
        self.check_registration()
        self.check_search()
        self.check_maps_and_tocs()
        self.check_help_ids()
        self.check_links()
        self.check_html()
        if only is None or "params" in only:  # parses every operator: run it only when asked for
            self.check_params()
        if only is None or "refs" in only:
            self.check_refs()
        if only is None or "style" in only:
            self.check_style()
        if only is None or "biblio" in only:
            self.check_biblio()
        if only is None or "index" in only:
            self.check_index()
        return self.errors


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--repo", type=Path, default=HERE.parents[1])
    ap.add_argument("--external", type=Path, action="append")
    ap.add_argument("--only", help="comma-separated categories to report")
    a = ap.parse_args(argv)
    repo = a.repo.resolve()
    ext = a.external if a.external is not None else [
        repo.parent / n for n in ("snap-desktop", "snap-engine", "optical-toolbox")]
    keep = set(a.only.split(",")) if a.only else None
    errors = Checker(repo, [e.resolve() for e in ext]).run(keep)
    if keep:
        errors = [e for e in errors if e[0] in keep]
    by_cat = defaultdict(list)
    for cat, _key, msg in errors:
        by_cat[cat].append(msg)
    for cat in sorted(by_cat):
        print(f"\n[{cat}] {len(by_cat[cat])}")
        for msg in sorted(by_cat[cat]):
            print(f"  {msg}")
    print(f"\n{len(errors)} error(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
