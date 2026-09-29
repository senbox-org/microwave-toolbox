"""Assert that a gpt/maven log proves the code path we depend on actually ran.

WHY. CreateStackOp logs a warning and KEEPS ZERO BIAS when estimation fails
(CreateStackOp.java:629-633), and the grid lock degrades silently to an unlocked slave
grid (:1911-1914). Both let a run finish "successfully" having done nothing. Gating on a
log line is the only available proof.
"""
from __future__ import annotations

import argparse
import re
import sys


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("logfile")
    ap.add_argument("--require", action="append", default=[])
    ap.add_argument("--forbid", action="append", default=[])
    args = ap.parse_args()

    text = open(args.logfile, encoding="utf-8", errors="replace").read()
    failed = False
    for pat in args.require:
        if re.search(pat, text):
            print(f"REQUIRE ok   {pat}")
        else:
            print(f"REQUIRE MISS {pat}")
            failed = True
    for pat in args.forbid:
        m = re.search(pat, text)
        if m:
            print(f"FORBID  HIT  {pat}  ->  {m.group(0)[:120]}")
            failed = True
        else:
            print(f"FORBID  ok   {pat}")
    print("LOG ASSERTIONS", "FAILED" if failed else "OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
