"""
Python helpers for the SNAP adapter.

These live in a real .py file rather than inside PowerShell here-strings. Embedding Python in a
.ps1 puts regex metacharacters ('[', '<', quotes) in front of the PowerShell parser, which reads
them as PowerShell syntax and fails at PARSE time -- i.e. before the script runs at all, so the
failure looks nothing like a Python error. Keeping the two languages in separate files removes that
whole class of bug.

Subcommands:
  read-case <case.yml>      -> the case as JSON on stdout (one YAML parser for the whole harness)
  describe <product.dim>    -> band names + geotransform of a BEAM-DIMAP product
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def read_case(path: str) -> int:
    import yaml
    print(json.dumps(yaml.safe_load(Path(path).read_text(encoding="utf-8"))))
    return 0


def describe(path: str) -> int:
    """Report what was actually produced, rather than what should have been."""
    dim = Path(path)
    if not dim.exists():
        print(f"DESCRIBE: {dim} does not exist", file=sys.stderr)
        return 1
    s = dim.read_text(encoding="utf-8", errors="replace")
    print(f"FILE {dim}")
    bands = re.findall(r"<BAND_NAME>([^<]+)</BAND_NAME>", s)
    for b in bands:
        print(f"  BAND {b}")
    if not bands:
        print("  WARNING: no bands in the product -- gpt exited 0 but produced nothing usable")
    t = re.findall(r"IMAGE_TO_MODEL_TRANSFORM>([^<]+)", s)
    if t:
        print(f"  TRANSFORM {t[0]}")
    return 0


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    cmd, arg = sys.argv[1], sys.argv[2]
    if cmd == "read-case":
        return read_case(arg)
    if cmd == "describe":
        return describe(arg)
    print(f"unknown subcommand {cmd!r}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
