#!/usr/bin/env python3
"""Generate operator parameter reference tables for the JavaHelp pages.

The tables are built from the @Parameter annotations in the Java source (not from
`gpt -h`, so no SNAP install is needed and the source version is what is documented).
Each table lives in its own block on the operator's help page:

    <!-- params:begin op="<alias>" -->
    <h3>Processing parameters (reference)</h3>
    ...generated...
    <!-- params:end -->

Only text between the markers is ever rewritten; the hand-written page stays untouched.

Usage:
    python -I tools/javahelp/gen_params.py --list               # alias | class | page | #params | #unresolved
    python -I tools/javahelp/gen_params.py --show ALIAS         # print one table
    python -I tools/javahelp/gen_params.py --write [ALIAS ...]  # refresh existing blocks
    python -I tools/javahelp/gen_params.py --insert [ALIAS ...] # also add blocks to pages without one
    python -I tools/javahelp/gen_params.py --check              # exit 1 when a block is stale or broken
    python -I tools/javahelp/gen_params.py --refresh-external   # rewrite external_constants.json (needs ../snap-engine)

--write/--insert skip operators with unresolved expressions unless --allow-unresolved.

Constants used in annotations are resolved across this repo and ../snap-engine (when
present). The snap-engine constants that annotations reference are cached in
external_constants.json next to this script, so the tables come out the same without
../snap-engine (as on CI). An expression that cannot be resolved is shown as written
and counted as unresolved in --list.

Operator -> page comes from layer.xml actions (operatorName + helpId) and map.jhm, plus
the overrides in params_pages.txt next to this script (`alias|relative/page.html`).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
SKIP_DIRS = {"target", ".git", "node_modules", ".idea", "test_fixtures", "__pycache__"}
OVERRIDES = "tools/javahelp/params_pages.txt"
EXTERNAL = "tools/javahelp/external_constants.json"
H3 = "<h3>Processing parameters (reference)</h3>"
PREFIX = ("<p>GPT operator: <code>{alias}</code>. Generated from the operator source; parameter names "
          "are those used in GPT and graph XML.</p>")
END = "<!-- params:end -->"
BEGIN_RE = re.compile(r'<!--\s*params:begin\s+op="([^"]*)"\s*-->')
PRIMITIVE_ZERO = {"boolean": "false", "int": "0", "long": "0", "short": "0", "byte": "0",
                  "double": "0.0", "float": "0.0", "char": "\\u0000"}  # GPF default without defaultValue
MODIFIERS = r"\b(?:public|protected|private|static|final|transient|volatile)\b"
JAVA_LANG = {"Object", "String", "Boolean", "Byte", "Character", "Short", "Integer", "Long", "Float", "Double",
             "Number", "Math", "StrictMath", "System", "Enum", "Comparable", "Iterable", "Runnable", "Thread",
             "Class", "CharSequence", "Cloneable", "AutoCloseable"}


# ------------------------------------------------------------------ model ---

@dataclass
class Param:
    name: str                      # Java field name
    alias: str = ""                # @Parameter(alias=...)
    type: str = ""
    label: str = ""
    description: str = ""
    default: str = ""
    value_set: list[str] = field(default_factory=list)
    interval: str = ""
    unit: str = ""
    not_null: bool = False
    pattern: str = ""

    @property
    def gpt_name(self) -> str:
        return self.alias or self.name

    @property
    def internal(self) -> bool:
        return any(re.match(r"\s*internal\b", t, re.I) for t in (self.label, self.description))


@dataclass
class Operator:
    alias: str
    cls: str
    path: Path
    params: list[Param]
    internal: bool = False
    warnings: list[str] = field(default_factory=list)  # unresolved expressions

    @property
    def visible_params(self) -> list[Param]:
        return [p for p in self.params if not p.internal]


# ------------------------------------------------------------ java lexing ---

_LEX = re.compile(r'//[^\n]*|/\*.*?(?:\*/|\Z)|"""(?:\\.|.)*?(?:"""|\Z)'
                  r'|"(?:\\.|[^"\\\n])*"?|\'(?:\\.|[^\'\\\n])*\'?', re.S)


def _blank_str(s: str) -> str:
    return re.sub(r"[^\n]", " ", s)


def _scan(src: str) -> tuple[str, str]:
    """Return (text, code), both with comments blanked; `code` also has the contents of
    string and char literals blanked. Offsets and newlines are preserved."""
    text, code, pos = [], [], 0
    for m in _LEX.finditer(src):
        s = m.group(0)
        text.append(src[pos:m.start()])
        code.append(src[pos:m.start()])
        if s.startswith("/"):
            text.append(_blank_str(s))
            code.append(_blank_str(s))
        else:
            q = 3 if s.startswith('"""') else 1
            closed = len(s) >= 2 * q and s.endswith(s[:q])
            text.append(s)
            code.append(s[:q] + _blank_str(s[q:len(s) - q if closed else len(s)]) + (s[:q] if closed else ""))
        pos = m.end()
    text.append(src[pos:])
    code.append(src[pos:])
    return "".join(text), "".join(code)


def _match(code: str, i: int) -> int:
    """Index of the bracket closing the one at code[i] (any of ([{ nest)."""
    depth = 0
    for j in range(i, len(code)):
        ch = code[j]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                return j
    return len(code) - 1


def _split_top(text: str, code: str, sep: str) -> list[str]:
    """Split `text` on `sep` where `code` (its blanked twin) is outside any bracket."""
    out, depth, start = [], 0, 0
    for k, ch in enumerate(code):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == sep and depth == 0:
            if sep == "+" and k > 0 and code[k - 1] in "eE" and k > 1 and code[k - 2].isdigit():
                continue  # exponent, e.g. 1e+5
            out.append(text[start:k])
            start = k + 1
    out.append(text[start:])
    return out


_ESC = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", "s": " ", '"': '"', "'": "'", "\\": "\\"}


def _unescape(s: str) -> str:
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            d = s[i + 1]
            if d == "u":
                j = i + 1
                while j < len(s) and s[j] == "u":
                    j += 1
                out.append(chr(int(s[j:j + 4], 16)))
                i = j + 4
                continue
            m = re.match(r"[0-7]{1,3}", s[i + 1:])
            if m:
                out.append(chr(int(m.group(0), 8)))
                i += 1 + len(m.group(0))
                continue
            out.append(_ESC.get(d, d))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _read_text(path: Path) -> str:
    data = Path(path).read_bytes()
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError:
        return data.decode("cp1252", errors="replace")


def _strip_generics(s: str) -> str:
    prev = None
    while prev != s:
        prev, s = s, re.sub(r"<[^<>]*>", "", s)
    return s


def _norm_type(t: str) -> str:
    t = re.sub(r"\s+", " ", t.strip())
    t = re.sub(r"\s*([<>\[\]])\s*", r"\1", t)
    return re.sub(r"\s*,\s*", ", ", t)


def _ws(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip()


# ------------------------------------------------------------- java files ---

@dataclass
class JClass:
    name: str
    kind: str              # class | interface | enum | @interface
    start: int             # offset of the keyword
    body: int              # offset of '{'
    end: int               # offset of the matching '}'
    modifiers: str
    extends: list[str]
    implements: list[str]
    outer: "JClass | None" = None


_CLASS_RE = re.compile(r"(?<![\w$.@])(@\s*interface|class|interface|enum)\s+([A-Za-z_$][\w$]*)")
_PARAM_RE = re.compile(r"@\s*(?:[\w$]+\s*\.\s*)*Parameter(?![\w$])")
_META_RE = re.compile(r"@\s*(?:[\w$]+\s*\.\s*)*OperatorMetadata(?![\w$])")


class JavaFile:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.text, self.code = _scan(_read_text(self.path))
        m = re.search(r"\bpackage\s+([\w.]+)\s*;", self.code)
        self.package = m.group(1) if m else ""
        self.imports = [(bool(s), _ws(n).replace(" ", "")) for s, n in
                        re.findall(r"\bimport\s+(static\s+)?([\w.\s]+(?:\.\s*\*)?)\s*;", self.code)]
        self.classes = self._find_classes()

    def _find_classes(self) -> list[JClass]:
        out = []
        for m in _CLASS_RE.finditer(self.code):
            brace = self.code.find("{", m.end())
            if brace < 0 or ";" in self.code[m.end():brace]:
                continue
            head = _strip_generics(self.code[m.end():brace])
            ext = re.search(r"\bextends\s+(.+?)(?=\bimplements\b|$)", head, re.S)
            imp = re.search(r"\bimplements\s+(.+)$", head, re.S)
            mods = re.search(r"((?:\b(?:public|protected|private|abstract|static|final|strictfp|sealed|"
                             r"non-sealed)\s+)*)$", self.code[max(0, m.start() - 300):m.start()])
            kind = "@interface" if m.group(1).startswith("@") else m.group(1)
            out.append(JClass(m.group(2), kind, m.start(), brace, _match(self.code, brace),
                              mods.group(1) if mods else "",
                              [x.strip() for x in ext.group(1).split(",") if x.strip()] if ext else [],
                              [x.strip() for x in imp.group(1).split(",") if x.strip()] if imp else []))
        for c in out:
            enclosing = [o for o in out if o is not c and o.body < c.start < o.end]
            c.outer = max(enclosing, key=lambda o: o.body) if enclosing else None
        return out

    def find(self, name: str, outer: JClass | None = None) -> JClass | None:
        for c in self.classes:
            if c.name == name and (outer is None or c.outer is outer):
                return c
        return None

    def top(self, name: str) -> JClass | None:
        return next((c for c in self.classes if c.name == name and c.outer is None), None)

    def is_direct_member(self, cls: JClass, pos: int) -> bool:
        seg = self.code[cls.body + 1:pos]
        return cls.body < pos < cls.end and seg.count("{") == seg.count("}")

    def enum_constants(self, cls: JClass) -> list[str]:
        if cls.kind != "enum":
            return []
        body_t, body_c = self.text[cls.body + 1:cls.end], self.code[cls.body + 1:cls.end]
        depth, cut = 0, len(body_c)  # the constant list ends at the first top-level ';'
        for k, ch in enumerate(body_c):
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
            elif ch == ";" and depth == 0:
                cut = k
                break
        names = []
        for piece in _split_top(body_t[:cut], body_c[:cut], ","):
            m = re.match(r"\s*(?:@[\w.]+(?:\s*\([^)]*\))?\s*)*([A-Za-z_$][\w$]*)", piece)
            if m:
                names.append(m.group(1))
        return names

    def constant_expr(self, cls: JClass, name: str) -> str | None:
        """Initializer text of the constant `name` declared directly in `cls`."""
        body = self.code[cls.body + 1:cls.end]
        for m in re.finditer(r"(?<![\w$.])" + re.escape(name) + r"\s*=(?!=)", body):
            pos = cls.body + 1 + m.start()
            if not self.is_direct_member(cls, pos):
                continue
            stmt_start = max(self.code.rfind(ch, cls.body, pos) for ch in ";{}") + 1
            stmt = self.code[stmt_start:pos]
            if cls.kind not in ("interface", "@interface") and not re.search(r"\bfinal\b", stmt):
                continue
            start = cls.body + 1 + m.end()
            depth, k = 0, start
            while k < cls.end:
                ch = self.code[k]
                if ch in "([{":
                    depth += 1
                elif ch in ")]}":
                    depth -= 1
                elif ch in ";," and depth == 0:
                    break
                k += 1
            return self.text[start:k].strip()
        return None

    def operator_metadata(self) -> tuple[dict[str, str], JClass] | None:
        for m in _META_RE.finditer(self.code):
            args, i = {}, m.end()
            while i < len(self.code) and self.code[i].isspace():
                i += 1
            if i < len(self.code) and self.code[i] == "(":
                j = _match(self.code, i)
                args = _parse_args(self.text[i + 1:j], self.code[i + 1:j])
                i = j + 1
            cls = next((c for c in self.classes if c.start >= i), None)
            if cls is not None:
                return args, cls
        return None

    def parameter_fields(self, cls: JClass) -> list[dict]:
        """Raw @Parameter fields declared directly in `cls`, in source order."""
        out = []
        code, text = self.code, self.text
        for m in _PARAM_RE.finditer(code, cls.body, cls.end):
            if not self.is_direct_member(cls, m.start()):
                continue
            i, args = m.end(), {}
            while code[i].isspace():
                i += 1
            if code[i] == "(":
                j = _match(code, i)
                args = _parse_args(text[i + 1:j], code[i + 1:j])
                i = j + 1
            while True:  # further annotations
                while code[i].isspace():
                    i += 1
                a = re.match(r"@\s*[\w$.]+\s*", code[i:])
                if not a:
                    break
                i += a.end()
                if code[i] == "(":
                    i = _match(code, i) + 1
            k = i
            while k < cls.end and code[k] not in "=;(":
                k += 1
            if code[k] == "(":
                continue  # not a field
            decl = re.sub(MODIFIERS, " ", code[i:k])
            d = re.search(r"([A-Za-z_$][\w$]*)\s*((?:\[\s*\]\s*)*)$", decl.strip())
            if not d:
                continue
            ftype = _norm_type(decl.strip()[:d.start()] + d.group(2).replace(" ", ""))
            init = None
            if code[k] == "=":
                e = k + 1
                depth = 0
                while e < cls.end:
                    ch = code[e]
                    if ch in "([{":
                        depth += 1
                    elif ch in ")]}":
                        depth -= 1
                    elif ch == ";" and depth == 0:
                        break
                    e += 1
                init = text[k + 1:e].strip()
            out.append({"name": d.group(1), "type": ftype, "args": args, "init": init})
        return out


def _parse_args(text: str, code: str) -> dict[str, str]:
    """Annotation arguments: key -> raw expression text (a lone value is key 'value')."""
    args = {}
    if not text.strip():
        return args
    codes = _split_top(code, code, ",")
    pos = 0
    for piece_c in codes:
        piece_t = text[pos:pos + len(piece_c)]
        pos += len(piece_c) + 1
        m = re.match(r"\s*([A-Za-z_$][\w$]*)\s*=(?!=)", piece_c)
        if m:
            args[m.group(1)] = piece_t[m.end():].strip()
        elif piece_c.strip():
            args["value"] = piece_t.strip()
    return args


# --------------------------------------------------------------- resolver ---

class _Enum(str):
    """Marker for an enum constant (resolves to its name)."""


class ConstantResolver:
    """Resolves annotation expressions (literals, constants, concatenations) across
    the Java sources under `roots`."""

    _NUM = re.compile(r"[-+]?(?:0[xX][0-9a-fA-F_]+|(?:\d[\d_]*)?\.?\d[\d_]*(?:[eE][-+]?\d+)?)[fFdDlL]?")
    _IDENT = re.compile(r"[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*")

    def __init__(self, roots: list[Path], external: dict[str, str] | None = None):
        """`roots[0]` is the home repo. `external` maps "SimpleClass.FIELD" to a value; it is
        consulted only when the class is not found under the roots."""
        self.roots = [Path(r) for r in roots if Path(r).is_dir()]
        self.home = os.path.normcase(os.path.abspath(roots[0])) if roots else None
        self.external = dict(external or {})
        # "SimpleClass.FIELD" -> {(declaring file, value)} for constants that home-repo
        # annotations take from classes outside the home repo (the external cache content)
        self.external_hits: dict[str, set[tuple[str, str]]] = defaultdict(set)
        self._files: dict[Path, JavaFile] = {}
        self._stems: dict[str, list[Path]] | None = None
        self._decls: dict[str, list[Path]] | None = None

    # -- indexes
    def file(self, path: Path) -> JavaFile:
        key = Path(os.path.normpath(path))
        if key not in self._files:
            self._files[key] = JavaFile(key)
        return self._files[key]

    def stems(self) -> dict[str, list[Path]]:
        if self._stems is None:
            self._stems = defaultdict(list)
            for root in self.roots:
                for p in _walk(root, lambda f: f.endswith(".java"), skip_tests=True):
                    self._stems[p.stem].append(p)
        return self._stems

    def decls(self) -> dict[str, list[Path]]:
        """Simple class name -> files declaring it (nested classes included). Built on first miss."""
        if self._decls is None:
            self._decls = defaultdict(list)
            for paths in self.stems().values():
                for p in paths:
                    try:
                        raw = p.read_text(encoding="utf-8", errors="replace")
                    except OSError:
                        continue
                    for n in set(re.findall(r"(?<![\w$.@])(?:class|interface|enum)\s+([A-Za-z_$][\w$]*)", raw)):
                        self._decls[n].append(p)
        return self._decls

    # -- classes
    def _nested(self, found, names: list[str]):
        jf, c = found
        for n in names:
            c = jf.find(n, outer=c)
            if c is None:
                return None
        return jf, c

    def _in_package(self, name: str, pkg: str):
        for p in self.stems().get(name, []):
            jf = self.file(p)
            if jf.package == pkg and jf.top(name):
                return jf, jf.top(name)
        return None

    def find_class(self, name: str, jf: JavaFile, _seen=None, deep: bool = True):
        """(JavaFile, JClass) for a simple, nested or qualified class name seen from `jf`.
        `deep` allows the slow fallback: a scan of every file for a nested declaration."""
        parts = [p.strip() for p in name.split(".")]
        if len(parts) > 1:
            if parts[0][:1].islower():  # package-qualified: pkg.Top[.Nested...]
                for k in range(1, len(parts)):
                    r = self._in_package(parts[k], ".".join(parts[:k]))
                    if r:
                        return self._nested(r, parts[k + 1:])
                return None
            r = self.find_class(parts[0], jf, _seen, deep)  # Outer.Inner
            return self._nested(r, parts[1:]) if r else None
        return self._find_simple(name, jf, _seen if _seen is not None else set(), deep)

    def _find_simple(self, name: str, jf: JavaFile, seen: set, deep: bool):
        c = jf.find(name)
        if c:
            return jf, c
        for static, imp in jf.imports:
            if not static and imp.endswith("." + name):
                return self.find_class(imp, jf, seen)  # explicit import: outside the roots if not found
        if name in JAVA_LANG:
            return None
        r = self._in_package(name, jf.package)
        if r:
            return r
        for static, imp in jf.imports:
            if not static and imp.endswith(".*"):
                r = self._in_package(name, imp[:-2])
                if r is None and imp[:-2].split(".")[-1][:1].isupper():  # import pkg.Outer.*
                    r = self.find_class(imp[:-2] + "." + name, jf, seen, deep=False)
                if r:
                    return r
        if jf.path not in seen:  # member types inherited from supertypes of the classes in this file
            seen.add(jf.path)
            for cls in jf.classes:
                for sup in cls.extends + cls.implements:
                    if sup.split(".")[-1] == name:
                        continue
                    r = self.find_class(sup, jf, seen, deep=False)
                    if r and r[0].path != jf.path:
                        hit = r[0].find(name)
                        if hit:
                            return r[0], hit
        cands = [p for p in self.stems().get(name, []) if self.file(p).top(name)]
        if len(cands) == 1:
            jf2 = self.file(cands[0])
            return jf2, jf2.top(name)
        if not cands and deep:
            hits = []
            for p in self.decls().get(name, []):
                jf2 = self.file(p)
                hits += [(jf2, c) for c in jf2.classes if c.name == name]
            if len(hits) == 1:
                return hits[0]
        return None

    def superclass_chain(self, jf: JavaFile, cls: JClass) -> list[tuple[JavaFile, JClass]]:
        """Superclasses found under the roots, base-most first."""
        chain, seen = [], {(jf.path, cls.name)}
        while cls.extends and cls.kind == "class":
            r = self.find_class(cls.extends[0], jf)
            if not r or (r[0].path, r[1].name) in seen:
                break
            jf, cls = r
            seen.add((jf.path, cls.name))
            chain.append(r)
        return chain[::-1]

    # -- constants
    def _lookup(self, jf: JavaFile, cls: JClass, name: str, seen: set):
        key = (jf.path, cls.name, name)
        if key in seen:
            return None
        seen.add(key)
        if name in jf.enum_constants(cls):
            return _Enum(name)
        expr = jf.constant_expr(cls, name)
        if expr is not None:
            return expr, jf, cls
        for sup in cls.extends + cls.implements:
            r = self.find_class(sup, jf)
            if r:
                hit = self._lookup(r[0], r[1], name, seen)
                if hit is not None:
                    return hit
        return None

    def _is_home(self, jf: JavaFile) -> bool:
        return self.home is not None and os.path.normcase(os.path.abspath(jf.path)).startswith(
            self.home + os.sep)

    @staticmethod
    def _key(class_ref: str, field_name: str) -> str:
        """Cache key: the class reference without its package, then the field."""
        return ".".join([p.strip() for p in class_ref.split(".") if not p.strip()[:1].islower()] + [field_name])

    def _from_class(self, class_ref: str, field_name: str, jf: JavaFile, depth: int):
        """Value of `class_ref.field_name`; the external cache answers when the class is not
        under the roots. Constants taken from outside the home repo are recorded."""
        key = self._key(class_ref, field_name)
        r = self.find_class(class_ref, jf)
        if r is None:
            return (self.external[key], "cached") if key in self.external else None
        hit = self._lookup(r[0], r[1], field_name, set())
        if hit is None:
            return None
        if isinstance(hit, _Enum):
            v = (str(hit), "enum")
        else:
            v = self._eval(hit[0], hit[1], hit[2], depth + 1)
        if v is not None and self._is_home(jf) and not self._is_home(r[0]):
            self.external_hits[key].add((r[0].path.as_posix(), v[0]))
        return v

    def _ident(self, chain: str, jf: JavaFile, cls: JClass | None, depth: int):
        parts = [p.strip() for p in chain.split(".")]
        hit = None
        if len(parts) == 1:
            name = parts[0]
            scopes = []
            c = cls
            while c is not None:
                scopes.append(c)
                c = c.outer
            scopes += [c for c in jf.classes if c not in scopes]
            seen = set()
            for c in scopes:
                hit = self._lookup(jf, c, name, seen)
                if hit is not None:
                    break
            if hit is None:
                for static, imp in jf.imports:
                    if static and (imp.endswith("." + name) or imp.endswith(".*")):
                        v = self._from_class(imp.rsplit(".", 1)[0], name, jf, depth)
                        if v is not None:
                            return v
        else:
            return self._from_class(".".join(parts[:-1]), parts[-1], jf, depth)
        if hit is None:
            return None
        if isinstance(hit, _Enum):
            return str(hit), "enum"
        expr, jf2, cls2 = hit
        return self._eval(expr, jf2, cls2, depth + 1)

    def _eval(self, expr: str, jf: JavaFile, cls: JClass | None, depth: int = 0):
        """(value, kind) with kind in str|num|bool|enum, or None."""
        if expr is None or depth > 30:
            return None
        text, code = _scan(expr)
        if len(text.strip()) != len(text):
            lead = len(text) - len(text.lstrip())
            text, code = text.strip(), code[lead:lead + len(text.strip())]
        if not text:
            return None
        while text[0] == "(" and _match(code, 0) == len(code) - 1:
            text, code = text[1:-1].strip(), code[1:-1].strip()
        parts = _split_top(text, code, "+")
        if len(parts) > 1 and parts[0].strip():
            vals = [self._eval(p, jf, cls, depth + 1) for p in parts]
            if any(v is None for v in vals) or not any(v[1] in ("str", "cached") for v in vals):
                return None
            return "".join(v[0] for v in vals), "str"
        if text.startswith('"""'):
            return None
        if text[0] == '"' and text[-1] == '"' and code.count('"') == 2:
            return _unescape(text[1:-1]), "str"
        if text[0] == "'" and text[-1] == "'" and len(text) >= 3:
            return _unescape(text[1:-1]), "str"
        if text in ("true", "false"):
            return text, "bool"
        if self._NUM.fullmatch(text):
            return text.rstrip("fFdDlL").replace("_", "") if not text.lower().startswith(("0x", "-0x")) \
                else text.replace("_", ""), "num"
        m = re.fullmatch(r"(.+?)\s*\.\s*name\s*\(\s*\)", text, re.S)
        if m:
            v = self._eval(m.group(1), jf, cls, depth + 1)
            return (v[0], "str") if v and v[1] in ("enum", "cached") else None
        if self._IDENT.fullmatch(text) and text not in ("null", "this"):
            return self._ident(text, jf, cls, depth)
        return None

    def resolve(self, expr: str, context_cls_path: Path, cls_name: str | None = None) -> str | None:
        jf = self.file(context_cls_path)
        cls = jf.find(cls_name) if cls_name else None
        v = self._eval(expr, jf, cls)
        return v[0] if v else None


def default_roots(repo: Path) -> list[Path]:
    repo = Path(repo)
    return [repo] + [r for r in (repo.parent / "snap-engine",) if r.is_dir()]


def load_external(repo: Path) -> dict[str, str]:
    f = Path(repo) / EXTERNAL
    return json.loads(f.read_text(encoding="utf-8")) if f.exists() else {}


def default_resolver(repo: Path) -> ConstantResolver:
    return ConstantResolver(default_roots(repo), external=load_external(repo))


def external_values(resolver: ConstantResolver) -> dict[str, str]:
    """The external constants the resolver saw; ValueError when one key has two values."""
    out, clashes = {}, []
    for key in sorted(resolver.external_hits):
        values = sorted({v for _, v in resolver.external_hits[key]})
        if len(values) > 1:
            where = "; ".join(f"{v!r} in {f}" for f, v in sorted(resolver.external_hits[key]))
            clashes.append(f"'{key}' is ambiguous: {where}")
        else:
            out[key] = values[0]
    if clashes:
        raise ValueError("; ".join(clashes))
    return out


def refresh_external(repo: Path) -> dict[str, str]:
    """Rewrite external_constants.json from ../snap-engine; returns the new content."""
    repo = Path(repo)
    engine = repo.parent / "snap-engine"
    if not engine.is_dir():
        raise SystemExit(f"--refresh-external needs the snap-engine sources at {engine}")
    resolver = ConstantResolver(default_roots(repo))  # no cache: every value comes from source
    operators(repo, resolver)
    values = external_values(resolver)
    f = repo / EXTERNAL
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_bytes((json.dumps(values, indent=2, sort_keys=True) + "\n").encode("utf-8"))
    return values


# ---------------------------------------------------------------- parsing ---

def _attr(res: ConstantResolver, raw: str, jf: JavaFile, cls: JClass, where: str, warnings: list) -> str:
    v = res._eval(raw, jf, cls)
    if v is None:
        warnings.append(f"{where}: {_ws(raw)}")
        return _ws(raw)
    return v[0]


def _param(res: ConstantResolver, jf: JavaFile, cls: JClass, f: dict, op_cls: str, warnings: list) -> Param:
    a = f["args"]
    where = f"{op_cls}.{f['name']}"

    def s(key):
        return _attr(res, a[key], jf, cls, f"{where} {key}", warnings) if key in a else ""

    vs = []
    if "valueSet" in a:
        raw = a["valueSet"].strip()
        if raw.startswith("{") and raw.endswith("}"):
            inner_t = raw[1:-1]
            inner_c = _scan(inner_t)[1]
            vs = [_attr(res, x, jf, cls, f"{where} valueSet", warnings)
                  for x in _split_top(inner_t, inner_c, ",") if x.strip()]
        else:
            vs = [s("valueSet")]
    if "defaultValue" in a:
        default = s("defaultValue")
    elif f["type"] in PRIMITIVE_ZERO:
        default = PRIMITIVE_ZERO[f["type"]]
    else:
        v = res._eval(f["init"], jf, cls) if f["init"] else None
        default = v[0] if v else ""
    return Param(name=f["name"], alias=s("alias"), type=f["type"], label=s("label"),
                 description=s("description"), default=default, value_set=vs, interval=s("interval"),
                 unit=s("unit"), not_null=a.get("notNull", "").strip() == "true", pattern=s("pattern"))


def parse_operator(java_path: Path, resolver: ConstantResolver) -> Operator | None:
    """The operator declared in `java_path`, or None for abstract classes, classes without
    @OperatorMetadata and classes that do not extend anything (not GPF operators)."""
    jf = resolver.file(java_path)
    meta = jf.operator_metadata()
    if meta is None:
        return None
    args, cls = meta
    if cls.kind != "class" or re.search(r"\babstract\b", cls.modifiers) or not cls.extends:
        return None
    warnings: list[str] = []
    alias = _attr(resolver, args["alias"], jf, cls, f"{cls.name} alias", warnings) if "alias" in args \
        else cls.name
    params: dict[str, Param] = {}
    for f2, c2 in resolver.superclass_chain(jf, cls) + [(jf, cls)]:
        for raw in f2.parameter_fields(c2):
            params[raw["name"]] = _param(resolver, f2, c2, raw, cls.name, warnings)
    return Operator(alias, cls.name, Path(java_path), list(params.values()),
                    internal=args.get("internal", "").strip() == "true", warnings=warnings)


def operators(repo: Path, resolver: ConstantResolver | None = None,
              duplicates: list | None = None) -> dict[str, Operator]:
    """alias -> Operator for every operator under src/main/java in `repo` (first one wins;
    later ones are appended to `duplicates` as (alias, path))."""
    repo = Path(repo)
    resolver = resolver or default_resolver(repo)
    ops = {}
    for p in _walk(repo, lambda f: f.endswith(".java"), skip_tests=True):
        if "/src/main/java/" not in p.as_posix():
            continue
        if "OperatorMetadata" not in p.read_text(encoding="utf-8", errors="replace"):
            continue
        op = parse_operator(p, resolver)
        if op is None:
            continue
        if op.alias in ops:
            if duplicates is not None:
                duplicates.append((op.alias, p))
            continue
        ops[op.alias] = op
    return ops


# -------------------------------------------------------------- rendering ---

def _esc(s: str) -> str:
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\t", " ")
    return "".join(c if ord(c) < 128 else f"&#{ord(c)};" for c in s)


def _cell(s: str) -> str:
    lines = [_ws(x) for x in _esc(s).strip().split("\n")]
    return "<br>".join(x for x in lines if x) or "&nbsp;"


def render_table(op: Operator) -> str:
    """Swing-safe (HTML 3.2, ASCII) parameter table for one operator."""
    out = [PREFIX.format(alias=_esc(op.alias))]
    rows = op.visible_params
    if not rows:
        out.append("<p>This operator has no processing parameters.</p>")
        return "\n".join(out)
    out.append('<table border="1" cellpadding="3" cellspacing="0">')
    out.append("<tr><th>Parameter</th><th>Type</th><th>Default</th><th>Allowed values</th>"
               "<th>Unit</th><th>Description</th></tr>")
    for p in rows:
        allowed = ", ".join(p.value_set) if p.value_set else p.interval
        if not allowed and p.pattern:
            allowed = f"pattern: {p.pattern}"
        out.append(f"<tr><td><code>{_esc(p.gpt_name)}</code></td><td>{_cell(p.type)}</td>"
                   f"<td>{_cell(p.default)}</td><td>{_cell(allowed)}</td><td>{_cell(p.unit)}</td>"
                   f"<td>{_cell(p.description or p.label)}</td></tr>")
    out.append("</table>")
    return "\n".join(out)


def render_block(op: Operator, multi: bool = False) -> str:
    """The whole marker block. On a page with several blocks the heading names the operator."""
    h3 = H3.replace("</h3>", f" &#8212; {_esc(op.alias)}</h3>") if multi else H3
    return f'<!-- params:begin op="{op.alias}" -->\n{h3}\n{render_table(op)}\n{END}'


# ------------------------------------------------------------------ pages ---

def _walk(root: Path, pred, skip_tests: bool = False):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS
                             and not (skip_tests and d == "test" and os.path.basename(dirpath) == "src"))
        for f in sorted(filenames):
            if pred(f):
                yield Path(dirpath) / f


def _parse_xml(path: Path):
    text = path.read_text(encoding="utf-8", errors="replace")
    text = re.sub(r"<!DOCTYPE[^>]*>", "", text, flags=re.S)
    if text.lstrip().startswith("<?xml"):
        text = text.split("?>", 1)[1]
    return ET.fromstring(text)


def read_overrides(repo: Path) -> list[tuple[str, str]]:
    f = Path(repo) / OVERRIDES
    out = []
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "|" in line:
                alias, rel = line.split("|", 1)
                out.append((alias.strip(), rel.strip()))
    return out


def page_map(repo: Path) -> dict[str, Path]:
    """Operator alias -> help page: params_pages.txt overrides, then layer.xml
    (operatorName + helpId) through map.jhm."""
    repo = Path(repo)
    pages = {alias: Path(os.path.normpath(repo / rel)) for alias, rel in read_overrides(repo)}
    ids: dict[str, Path] = {}
    for m in _walk(repo, lambda f: f == "map.jhm"):
        try:
            for e in _parse_xml(m).iter("mapID"):
                if e.get("target") and e.get("url"):
                    ids.setdefault(e.get("target"), Path(os.path.normpath(m.parent / e.get("url").split("#")[0])))
        except ET.ParseError:
            pass
    for layer in _walk(repo, lambda f: f == "layer.xml"):
        try:
            root = _parse_xml(layer)
        except ET.ParseError:
            continue
        for f in root.iter("file"):
            attrs = {a.get("name"): a.get("stringvalue") for a in f.findall("attr")}
            op, hid = attrs.get("operatorName"), attrs.get("helpId")
            if op and hid and hid in ids:
                pages.setdefault(op, ids[hid])
    return pages


def _decode(raw: bytes) -> tuple[str, str]:
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw.decode("utf-8-sig"), "utf-8-sig"
    try:
        return raw.decode("utf-8"), "utf-8"
    except UnicodeDecodeError:
        return raw.decode("latin-1"), "latin-1"  # byte-exact round trip; the block is ASCII


def _newline(text: str) -> str:
    return "\r\n" if text.count("\r\n") * 2 > text.count("\n") else "\n"


def _blocks(text: str):
    """(alias, start, end) per marker block; end is None when the end marker is missing."""
    found = list(BEGIN_RE.finditer(text))
    for k, m in enumerate(found):
        e = text.find(END, m.end())
        nxt = found[k + 1].start() if k + 1 < len(found) else len(text)
        yield m.group(1), m.start(), (e + len(END) if 0 <= e < nxt else None)


def _insert_at(text: str) -> int:
    low = text.lower()
    i = low.find("<!-- footer:begin")
    if i >= 0:
        return i
    body = low.rfind("</body")
    if body < 0:
        return len(text)
    hrs = list(re.finditer(r"<hr\b[^>]*>", low[:body]))
    return hrs[-1].start() if hrs else body


def marker_pages(repo: Path) -> list[Path]:
    out = []
    for p in _walk(Path(repo), lambda f: f.lower().endswith((".html", ".htm"))):
        if b"params:begin" in p.read_bytes():
            out.append(p)
    return out


def update_pages(repo: Path, aliases=None, insert: bool = False,
                 resolver: ConstantResolver | None = None, allow_unresolved: bool = False,
                 skipped: list | None = None) -> list[Path]:
    """Rewrite the marker blocks (and with insert=True add missing ones). Returns changed pages.

    `aliases` limits the pages touched to those carrying (or receiving) one of these operators;
    every block on such a page is refreshed. Operators with unresolved expressions are left
    alone, and appended to `skipped`, unless `allow_unresolved`."""
    repo = Path(repo)
    ops = operators(repo, resolver)
    wanted = set(aliases) if aliases else None
    targets: dict[Path, list[str]] = defaultdict(list)
    if insert:
        for alias, page in sorted(page_map(repo).items()):
            op = ops.get(alias)
            if op and not op.internal and page.is_file():
                targets[page].append(alias)

    def ok(alias: str) -> bool:
        if allow_unresolved or not ops[alias].warnings:
            return True
        if skipped is not None and alias not in skipped:
            skipped.append(alias)
        return False

    changed = []
    for page in sorted(set(marker_pages(repo)) | set(targets)):
        raw = page.read_bytes()
        text, enc = _decode(raw)
        existing = [b[0] for b in _blocks(text)]
        new_ones = [a for a in targets.get(page, []) if a not in existing]
        if wanted is not None and not wanted & (set(existing) | set(new_ones)):
            continue
        new_ones = [a for a in new_ones if ok(a)]
        multi = len(set(existing) | set(new_ones)) > 1
        nl = _newline(text)
        new = text
        for alias, start, end in sorted(_blocks(text), key=lambda b: -b[1]):
            if end is None or alias not in ops or not ok(alias):
                continue
            new = new[:start] + render_block(ops[alias], multi).replace("\n", nl) + new[end:]
        for alias in new_ones:
            i = _insert_at(new)
            block = render_block(ops[alias], multi).replace("\n", nl) + nl
            if i > 0 and new[i - 1] != "\n":
                block = nl + block
            new = new[:i] + block + new[i:]
        if new != text:
            page.write_bytes(new.encode(enc))
            changed.append(page)
    return changed


def check(repo: Path, resolver: ConstantResolver | None = None) -> list[str]:
    """One message per stale or broken block, per override naming no operator and, when
    ../snap-engine is present, per stale entry of the external-constant cache."""
    repo = Path(repo)
    cache = load_external(repo)
    resolver = resolver or ConstantResolver(default_roots(repo), external=cache)
    ops = operators(repo, resolver)
    msgs = []

    def rel(p: Path) -> str:
        try:
            return p.relative_to(repo).as_posix()
        except ValueError:
            return p.as_posix()

    for page in marker_pages(repo):
        text, _ = _decode(page.read_bytes())
        blocks = list(_blocks(text))
        multi = len({b[0] for b in blocks}) > 1
        for alias, start, end in blocks:
            if end is None:
                msgs.append(f"{rel(page)}: params block for '{alias}' has no {END}")
            elif alias not in ops:
                msgs.append(f"{rel(page)}: params block names unknown operator '{alias}'")
            elif text[start:end].replace("\r\n", "\n") != render_block(ops[alias], multi):
                msgs.append(f"{rel(page)}: params block for '{alias}' is stale "
                            f"(run gen_params.py --write {alias})")
    for alias, page in read_overrides(repo):
        if alias not in ops:
            msgs.append(f"{OVERRIDES}: '{alias}' matches no operator")
        elif not (repo / page).is_file():
            msgs.append(f"{OVERRIDES}: page for '{alias}' does not exist: {page}")
    if (repo.parent / "snap-engine").is_dir():
        hint = "(run gen_params.py --refresh-external)"
        try:
            fresh = external_values(resolver)
        except ValueError as e:
            msgs.append(f"{EXTERNAL}: {e}")
            fresh = None
        if fresh is not None:
            for key in sorted(set(fresh) | set(cache)):
                if key not in cache:
                    msgs.append(f"{EXTERNAL}: '{key}' is missing {hint}")
                elif key not in fresh:
                    msgs.append(f"{EXTERNAL}: '{key}' is no longer referenced {hint}")
                elif fresh[key] != cache[key]:
                    msgs.append(f"{EXTERNAL}: '{key}' is stale: cached {cache[key]!r}, "
                                f"snap-engine has {fresh[key]!r} {hint}")
    return msgs


# -------------------------------------------------------------------- cli ---

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--repo", type=Path, default=HERE.parents[1])
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--list", action="store_true", help="list operators, pages and unresolved counts")
    g.add_argument("--show", metavar="ALIAS", help="print the table for one operator")
    g.add_argument("--write", nargs="*", metavar="ALIAS", help="refresh existing blocks")
    g.add_argument("--insert", nargs="*", metavar="ALIAS", help="refresh blocks and add missing ones")
    g.add_argument("--check", action="store_true", help="exit 1 when a block or the cache is stale")
    g.add_argument("--refresh-external", action="store_true",
                   help=f"rewrite {EXTERNAL} from ../snap-engine")
    ap.add_argument("--allow-unresolved", action="store_true",
                    help="--write/--insert: also write operators with unresolved expressions")
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="backslashreplace")
    repo = a.repo.resolve()

    if a.check:
        msgs = check(repo)
        for m in msgs:
            print(m)
        print(f"{len(msgs)} params problem(s)")
        return 1 if msgs else 0
    if a.refresh_external:
        try:
            values = refresh_external(repo)
        except ValueError as e:
            print(f"error: {e}", file=sys.stderr)
            return 1
        print(f"{EXTERNAL}: {len(values)} constant(s)")
        return 0
    resolver = default_resolver(repo)
    duplicates: list = []
    ops = operators(repo, resolver, duplicates)
    if a.write is not None or a.insert is not None:
        names = a.insert if a.insert is not None else a.write
        unknown = [n for n in names if n not in ops]
        if unknown:
            print(f"unknown operator alias(es): {', '.join(unknown)}", file=sys.stderr)
            return 2
        skipped: list = []
        changed = update_pages(repo, names or None, insert=a.insert is not None, resolver=resolver,
                               allow_unresolved=a.allow_unresolved, skipped=skipped)
        for p in changed:
            print(p.relative_to(repo).as_posix())
        for alias in skipped:
            print(f"skipped {alias} (unresolved: {'; '.join(ops[alias].warnings)}); "
                  f"use --allow-unresolved to write it anyway")
        print(f"{len(changed)} page(s) changed, {len(skipped)} operator(s) skipped")
        return 0
    if a.show:
        if a.show not in ops:
            print(f"unknown operator alias: {a.show}", file=sys.stderr)
            return 2
        print(render_table(ops[a.show]))
        return 0

    pages = page_map(repo)
    for alias in sorted(ops, key=str.lower):
        op = ops[alias]
        page = pages.get(alias)
        shown = page.relative_to(repo).as_posix() if page else "-"
        tag = " (internal)" if op.internal else ""
        print(f"{alias}{tag} | {op.cls} | {shown} | {len(op.visible_params)} | {len(op.warnings)}")
    total = sum(len(o.warnings) for o in ops.values())
    nopage = sorted((a for a in ops if a not in pages), key=str.lower)
    print(f"\noperators: {len(ops)} ({sum(o.internal for o in ops.values())} internal)")
    for alias, path in duplicates:
        print(f"WARNING: duplicate alias '{alias}' in {path.relative_to(repo).as_posix()} "
              f"(listed: {ops[alias].path.relative_to(repo).as_posix()})")
    print(f"mapped to a page: {len(ops) - len(nopage)}")
    print(f"unresolved expressions: {total}")
    worst = sorted((o for o in ops.values() if o.warnings), key=lambda o: (-len(o.warnings), o.alias))[:10]
    for o in worst:
        print(f"  {o.alias} ({len(o.warnings)}): " + "; ".join(o.warnings))
    print(f"no page ({len(nopage)}): " + ", ".join(nopage))
    return 0


if __name__ == "__main__":
    sys.exit(main())
