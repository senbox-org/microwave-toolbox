#!/usr/bin/env python3
"""Generate the Supported Missions table of the sar-io JavaHelp.

Every reader registered in a `META-INF/services/org.esa.snap.core.dataio.ProductReaderPlugIn`
file of this repo is listed with its format name(s), default file extensions and help page.
Nothing is instantiated: the PlugIn's Java source (plus the superclasses and *Constants
classes it references) is parsed with simple regular expressions.

The table is written between the markers

    <!-- missions:begin -->
    ...generated...
    <!-- missions:end -->

of sar-io/.../docs/SupportedMissions.html. Only that block is rewritten; the page's line
endings are kept.

Mission names and help pages come from missions_overrides.json next to this script:

    "readers": {"<PlugIn class FQN>": {"mission": "...", "help": "readers/X.html" | "nbdocs://..." }}
    "skip":    {"<PlugIn class FQN>": "reason"}       # registered, but not a mission reader
    "extra":   [{"mission", "reader", "extensions", "help", "note"}]  # readers outside this repo

Usage:
    python -I tools/javahelp/gen_missions.py           # print the table rows (mission | reader | extensions | help)
    python -I tools/javahelp/gen_missions.py --write   # rewrite the block in SupportedMissions.html
    python -I tools/javahelp/gen_missions.py --check   # exit 1 if the block is stale, a reader has no
                                                       # override entry, or a help page is missing
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
SERVICE = "org.esa.snap.core.dataio.ProductReaderPlugIn"
SKIP_DIRS = {"target", ".git", "node_modules", ".idea", "test_fixtures", "__pycache__"}
DOCS = Path("sar-io/src/main/javahelp/eu/esa/microwavetbx/sar/io/docs")
PAGE = DOCS / "SupportedMissions.html"
OVERRIDES = HERE / "missions_overrides.json"
BEGIN = "<!-- missions:begin -->"
END = "<!-- missions:end -->"

RETURN_RE = re.compile(r"\breturn\s+(.*?);", re.S)
TOKEN_RE = re.compile(r'"(?:[^"\\]|\\.)*"|(?:\w+\.)*\w+\s*\(\s*(?:null|\w+)?\s*\)|(?:\w+\.)*[A-Za-z_]\w*')
IGNORED = {"new", "String", "return", "null", "this", "super", "final", "static", "private",
           "public", "protected", "Locale", "toUpperCase", "toLowerCase"}


# ----------------------------------------------------------------- discovery ---

def walk(root: Path, pred):
    stack = [root]
    while stack:
        d = stack.pop()
        try:
            entries = sorted(d.iterdir())
        except OSError:
            continue
        for e in entries:
            if e.is_dir():
                if e.name not in SKIP_DIRS:
                    stack.append(e)
            elif pred(e):
                yield e


def service_files(repo: Path) -> list[Path]:
    return sorted(walk(repo, lambda f: f.name == SERVICE and f.parent.name == "services"
                       and "src/main/resources" in f.as_posix()),
                  key=lambda p: p.as_posix())


def read_service(path: Path) -> list[str]:
    out = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            out.append(line)
    return out


class SourceIndex:
    """Simple-name -> Java files of the repo's src/main/java trees."""

    def __init__(self, repo: Path):
        self.by_simple: dict[str, list[Path]] = {}
        for f in walk(repo, lambda p: p.suffix == ".java" and "/src/main/java/" in p.as_posix()):
            self.by_simple.setdefault(f.stem, []).append(f)
        self._text: dict[Path, str] = {}

    def text(self, f: Path) -> str:
        if f not in self._text:
            raw = f.read_text(encoding="utf-8", errors="replace")
            raw = re.sub(r"/\*.*?\*/", " ", raw, flags=re.S)        # block comments
            raw = re.sub(r"(?m)^\s*//.*$", " ", raw)                  # line comments
            self._text[f] = raw
        return self._text[f]

    def find_fqn(self, fqn: str) -> Path | None:
        rel = fqn.replace(".", "/") + ".java"
        for f in self.by_simple.get(fqn.rsplit(".", 1)[-1], []):
            if f.as_posix().endswith("/src/main/java/" + rel):
                return f
        return None

    def resolve(self, simple: str, ctx: Path) -> Path | None:
        """A simple class name used in ctx: imported, same package, or unique in the repo."""
        cands = self.by_simple.get(simple, [])
        if not cands:
            return None
        txt = self.text(ctx)
        m = re.search(r"\bimport\s+([\w.]+\." + re.escape(simple) + r")\s*;", txt)
        if m:
            return self.find_fqn(m.group(1))
        for c in cands:
            if c.parent == ctx.parent:
                return c
        return cands[0] if len(cands) == 1 else None


@dataclass
class Reader:
    fqn: str
    module: str
    source: Path | None
    formats: list[str] = field(default_factory=list)
    extensions: list[str] = field(default_factory=list)
    description: str = ""


def related_sources(idx: SourceIndex, start: Path, depth: int = 4) -> list[Path]:
    """The PlugIn file, its in-repo superclasses and the *Constants classes they use."""
    seen: list[Path] = []
    todo = [(start, 0)]
    while todo:
        f, d = todo.pop(0)
        if f in seen:
            continue
        seen.append(f)
        if d >= depth:
            continue
        txt = idx.text(f)
        names = set(re.findall(r"\bextends\s+(\w+)", txt))
        names |= set(re.findall(r"\b(\w+Constants)\b", txt))
        names |= set(re.findall(r"\bnew\s+(\w+)\s*\(\s*\)", txt)) & {n for n in idx.by_simple if n.endswith("Constants")}
        for n in sorted(names):
            p = idx.resolve(n, f)
            if p and p not in seen:
                todo.append((p, d + 1))
    return seen


# ------------------------------------------------------------- value parsing ---

def method_bodies(texts: list[str], name: str) -> list[str]:
    out = []
    pat = re.compile(r"\b" + re.escape(name) + r"\s*\([^)]*\)\s*(?:throws[^{]*)?\{")
    for t in texts:
        for m in pat.finditer(t):
            i, depth = m.end(), 1
            while i < len(t) and depth:
                depth += {"{": 1, "}": -1}.get(t[i], 0)
                i += 1
            out.append(t[m.end():i - 1])
    return out


def field_values(texts: list[str], name: str) -> list[str]:
    """Right-hand side of the first assignment to `name`, searching the PlugIn file first.

    A plain assignment in the PlugIn (`FORMAT_NAMES = COSMO_FORMAT_NAMES;` in a constructor)
    therefore wins over the declaration in a superclass."""
    pat = re.compile(r"(?<![\w.=!<>])" + re.escape(name) + r"\s*=(?!=)\s*(.*?);", re.S)
    for t in texts:
        m = pat.search(t)
        if m:
            return [m.group(1)]
    return []


def values_of(expr: str, texts: list[str], seen: set[str], depth: int = 0) -> list[str]:
    """String values of a Java expression: literals, constants and no-arg getter calls."""
    out: list[str] = []
    if depth > 8:
        return out
    expr = re.sub(r"\.clone\(\s*\)", "", expr)
    for tok in TOKEN_RE.findall(expr):
        tok = tok.strip()
        if tok.startswith('"'):
            out.append(bytes(tok[1:-1], "utf-8").decode("unicode_escape"))
            continue
        call = re.match(r"(?:\w+\.)*(\w+)\s*\(", tok)
        name = call.group(1) if call else tok.rsplit(".", 1)[-1]
        if name in IGNORED or name[0].isdigit():
            continue
        if call:
            for body in method_bodies(texts, name):
                if body in seen:
                    continue
                seen.add(body)
                for r in RETURN_RE.findall(body):
                    out.extend(values_of(r, texts, seen, depth + 1))
        elif name not in seen:
            seen.add(name)
            for rhs in field_values(texts, name):
                out.extend(values_of(rhs, texts, seen, depth + 1))
    return out


def plugin_values(texts: list[str], method: str) -> list[str]:
    vals: list[str] = []
    for body in method_bodies(texts[:1], method) or method_bodies(texts, method):
        for r in RETURN_RE.findall(body):
            vals.extend(values_of(r, texts, {body}))
        if vals:
            break
    out = []
    for v in vals:
        if v not in out:
            out.append(v)
    return out


def scan(repo: Path = REPO) -> list[Reader]:
    idx = SourceIndex(repo)
    readers = []
    for svc in service_files(repo):
        module = svc.as_posix().split("/src/main/resources/")[0]
        module = Path(module).relative_to(repo).as_posix()
        for fqn in read_service(svc):
            src = idx.find_fqn(fqn)
            r = Reader(fqn, module, src)
            if src:
                texts = [idx.text(p) for p in related_sources(idx, src)]
                r.formats = plugin_values(texts, "getFormatNames")
                r.extensions = [e for e in plugin_values(texts, "getDefaultFileExtensions") if e]
                desc = plugin_values(texts, "getDescription")
                r.description = desc[0] if desc else ""
            readers.append(r)
    return readers


# --------------------------------------------------------------------- table ---

def load_overrides(path: Path = OVERRIDES) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    data.setdefault("readers", {})
    data.setdefault("skip", {})
    data.setdefault("extra", [])
    return data


def norm_ext(exts: list[str]) -> str:
    seen = []
    for e in exts:
        e = e.strip()
        if not e:
            continue
        if not e.startswith("."):
            e = "." + e
        e = e.lower()
        if e not in seen:
            seen.append(e)
    return ", ".join(seen)


def rows(readers: list[Reader], ov: dict) -> tuple[list[dict], list[str]]:
    """Table rows and problems (readers without an override entry)."""
    out, problems = [], []
    for r in readers:
        if r.fqn in ov["skip"]:
            continue
        o = ov["readers"].get(r.fqn)
        if o is None:
            problems.append(f"{r.fqn} ({r.module}): no entry in missions_overrides.json "
                            f"(formats {r.formats}, description {r.description!r})")
            o = {}
        if not r.formats:
            problems.append(f"{r.fqn}: format name not found in the source")
        out.append({
            "mission": o.get("mission") or r.description or r.fqn.rsplit(".", 1)[-1],
            "reader": ", ".join(o.get("reader", r.formats)),
            "extensions": o.get("extensions", norm_ext(r.extensions)),
            "help": o.get("help", ""),
            "note": o.get("note", ""),
        })
    for e in ov["extra"]:
        out.append({k: e.get(k, "") for k in ("mission", "reader", "extensions", "help", "note")})
    out.sort(key=lambda x: (x["mission"].lower(), x["reader"].lower()))
    return out, problems


def help_problems(table: list[dict], docs: Path) -> list[str]:
    out = []
    for r in table:
        h = r["help"]
        if not h:
            out.append(f"{r['mission']} / {r['reader']}: no help page")
        elif not h.startswith("nbdocs://") and not (docs / h.split("#")[0]).is_file():
            out.append(f"{r['mission']} / {r['reader']}: help page {h} does not exist")
    return out


def render(table: list[dict], nl: str = "\n") -> str:
    lines = [BEGIN,
             '<table border="1" cellpadding="4" cellspacing="0">',
             "    <tr><th>Mission or format</th><th>Reader (format name)</th>"
             "<th>File extensions</th><th>Help page</th></tr>"]
    for r in table:
        ext = html.escape(r["extensions"]) if r["extensions"] else "(folder or file name)"
        note = f" {html.escape(r['note'])}" if r["note"] else ""
        link = f'<a href="{html.escape(r["help"])}">{html.escape(r["mission"])}</a>' if r["help"] else "&nbsp;"
        lines.append(f"    <tr><td>{html.escape(r['mission'])}</td><td>{html.escape(r['reader'])}{note}</td>"
                     f"<td>{ext}</td><td>{link}</td></tr>")
    lines.append("</table>")
    lines.append(END)
    return nl.join(lines)


def splice(page_text: str, block: str) -> str:
    i, j = page_text.find(BEGIN), page_text.find(END)
    if i < 0 or j < i:
        raise ValueError(f"markers {BEGIN} / {END} not found")
    return page_text[:i] + block + page_text[j + len(END):]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--write", action="store_true", help="rewrite the block in SupportedMissions.html")
    g.add_argument("--check", action="store_true", help="exit 1 when the block is stale or entries are missing")
    ap.add_argument("--repo", type=Path, default=REPO, help=argparse.SUPPRESS)
    args = ap.parse_args(argv)

    repo = args.repo.resolve()
    ov = load_overrides(repo / "tools/javahelp/missions_overrides.json")
    table, problems = rows(scan(repo), ov)
    problems += help_problems(table, repo / DOCS)
    page = repo / PAGE

    if not args.write and not args.check:
        for r in table:
            print(f"{r['mission']} | {r['reader']} | {r['extensions']} | {r['help']}")
        for p in problems:
            print("WARNING:", p, file=sys.stderr)
        return 0

    raw = page.read_bytes().decode("ascii")
    nl = "\r\n" if "\r\n" in raw else "\n"
    new = splice(raw, render(table, nl))

    if args.write:
        if new != raw:
            page.write_bytes(new.encode("ascii", "xmlcharrefreplace"))
            print(f"wrote {PAGE.as_posix()} ({len(table)} rows)")
        else:
            print(f"{PAGE.as_posix()} is up to date ({len(table)} rows)")
        for p in problems:
            print("WARNING:", p, file=sys.stderr)
        return 0

    ok = True
    if new != raw:
        print(f"{PAGE.as_posix()}: missions block is stale (run gen_missions.py --write)")
        ok = False
    for p in problems:
        print(p)
        ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
