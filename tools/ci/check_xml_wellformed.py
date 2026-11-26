"""Fail when a shipped XML resource is not well-formed.

Checks every *.xml under <module>/src/main/resources (processing graphs, layer.xml, help sets and maps),
which are mostly hand-edited and only parsed at run time. Stdlib only.

Usage:
    python -I tools/ci/check_xml_wellformed.py [--repo PATH]
"""
from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def find_xml(repo: Path):
    for path in sorted(repo.glob("**/src/main/resources/**/*.xml")):
        if "target" in path.relative_to(repo).parts:
            continue
        yield path


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    args = ap.parse_args(argv)
    repo = args.repo.resolve()

    checked = 0
    errors = []
    for path in find_xml(repo):
        checked += 1
        try:
            ET.parse(path)
        except ET.ParseError as e:
            errors.append(f"{path.relative_to(repo).as_posix()}: {e}")

    for err in errors:
        print(err)
    print(f"{checked} XML file(s) checked, {len(errors)} not well-formed")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
