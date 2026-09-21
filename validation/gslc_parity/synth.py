"""Synthetic second-acquisition generator for the parity campaign (R1 / R2-lite).

SLC_B is a copy of a real split SLC whose complex samples are multiplied by exp(-j*phi(range col))
and whose DATES are shifted by a whole number of days. Only the calendar date moves: every orbit
state vector, burst time and line time keeps its time of day, so the orbit-to-time mapping inside
the product is unchanged and the geometry of B is bit-identical to A's. That makes phi the ONLY
difference between the two legs, and lets a chain be judged against a known truth.
"""
from __future__ import annotations

import datetime as dt
import re
import shutil
import sys
import tempfile
from pathlib import Path

import numpy as np

AMP, CENTRE, SIGMA = 6.0, 11800.0, 1500.0     # the R1/R2 lobe: 6 rad, ~4 km sigma on the ground

_MON = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"]
_ISO = re.compile(r"(\d{4})-(\d{2})-(\d{2})(T\d{2}:\d{2}:\d{2}(?:\.\d+)?)")
_SNAP = re.compile(r"(\d{2})-(" + "|".join(_MON) + r")-(\d{4})( \d{2}:\d{2}:\d{2}(?:\.\d+)?)")


def shift_times(text: str, days: int) -> str:
    """Shift every ISO 'YYYY-MM-DDThh:mm:ss[.f]' and SNAP 'DD-MON-YYYY hh:mm:ss[.f]' date by +days."""
    d = dt.timedelta(days=days)

    def iso(m):
        s = dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3))) + d
        return f"{s.year:04d}-{s.month:02d}-{s.day:02d}{m.group(4)}"

    def snap(m):
        s = dt.date(int(m.group(3)), _MON.index(m.group(2)) + 1, int(m.group(1))) + d
        return f"{s.day:02d}-{_MON[s.month - 1]}-{s.year:04d}{m.group(4)}"

    return _SNAP.sub(snap, _ISO.sub(iso, text))


def lobe_phase(cols: np.ndarray, amp_rad: float, centre_col: float, sigma_cols: float) -> np.ndarray:
    """Range-only Gaussian 'deformation' lobe. Depends on the range column alone, so it is the
    same on the ground in every burst and continuous across burst overlaps."""
    return amp_rad * np.exp(-0.5 * ((np.asarray(cols, float) - centre_col) / sigma_cols) ** 2)


_ENVI = {1: np.uint8, 2: np.int16, 3: np.int32, 4: np.float32, 5: np.float64, 12: np.uint16}


def _hdr_dtype(hdr: Path) -> np.dtype:
    t = hdr.read_text()
    dtc = int(re.search(r"^data type\s*=\s*(\d+)", t, re.M).group(1))
    bo = int(re.search(r"^byte order\s*=\s*(\d)", t, re.M).group(1))
    return np.dtype(_ENVI[dtc]).newbyteorder(">" if bo == 1 else "<")


def _retype_band(dim_text: str, band: str, new_type: str) -> str:
    """Change <DATA_TYPE> inside one band's Spectral_Band_Info block of a .dim."""
    pat = re.compile(r"(<Spectral_Band_Info>(?:(?!</Spectral_Band_Info>).)*?<BAND_NAME>" + re.escape(band)
                     + r"</BAND_NAME>.*?)<DATA_TYPE>[^<]*</DATA_TYPE>", re.S)
    out, n = pat.subn(lambda m: m.group(1) + f"<DATA_TYPE>{new_type}</DATA_TYPE>", dim_text, count=1)
    if n != 1:
        raise ValueError(f"band {band!r} not declared in the .dim")
    return out


def write_second_date(src_dim: Path, dst_dim: Path, days: int, phase_fn=None,
                      i_band: str = "i_IW3_VV", q_band: str = "q_IW3_VV", block_rows: int = 256) -> None:
    """Write dst_dim = src_dim with dates +days and i/q multiplied by exp(-j*phase_fn(col))."""
    src_dim, dst_dim = Path(src_dim), Path(dst_dim)
    src_data, dst_data = src_dim.with_suffix(".data"), dst_dim.with_suffix(".data")
    if dst_dim.exists() or dst_data.exists():
        raise FileExistsError(f"{dst_dim} already exists")
    shutil.copytree(src_data, dst_data)
    txt = src_dim.read_text(encoding="utf-8", errors="replace")
    txt = txt.replace(src_data.name, dst_data.name)
    txt = shift_times(txt, days)
    if phase_fn is None:
        dst_dim.write_text(txt, encoding="utf-8")
        return
    ih = dst_data / f"{i_band}.hdr"
    w = int(re.search(r"^samples\s*=\s*(\d+)", ih.read_text(), re.M).group(1))
    h = int(re.search(r"^lines\s*=\s*(\d+)", ih.read_text(), re.M).group(1))
    dtp = _hdr_dtype(ih)
    rot = np.exp(-1j * phase_fn(np.arange(w)))
    # Real S1 SLC bands are int16. Rotating by a phase gives non-integer samples, and rounding them
    # back to int16 would add quantisation noise unrelated to the phase under test - so an integer
    # source is written out as big-endian float32 and its .hdr and .dim declarations are retyped.
    out_dt = np.dtype(">f4") if dtp.kind in "iu" else dtp
    # write i and q together (a rotation mixes them), block by block, to side files first
    si = np.memmap(dst_data / f"{i_band}.img", dtype=dtp, mode="r", shape=(h, w))
    sq = np.memmap(dst_data / f"{q_band}.img", dtype=dtp, mode="r", shape=(h, w))
    ti, tq = dst_data / f"{i_band}.img.new", dst_data / f"{q_band}.img.new"
    oi = np.memmap(ti, dtype=out_dt, mode="w+", shape=(h, w))
    oq = np.memmap(tq, dtype=out_dt, mode="w+", shape=(h, w))
    for r0 in range(0, h, block_rows):
        z = (np.asarray(si[r0:r0 + block_rows], np.float64) + 1j * np.asarray(sq[r0:r0 + block_rows], np.float64)) * rot
        oi[r0:r0 + block_rows] = z.real.astype(out_dt)
        oq[r0:r0 + block_rows] = z.imag.astype(out_dt)
    oi.flush()
    oq.flush()
    del oi, oq, si, sq
    (dst_data / f"{i_band}.img").unlink()
    (dst_data / f"{q_band}.img").unlink()
    ti.rename(dst_data / f"{i_band}.img")
    tq.rename(dst_data / f"{q_band}.img")
    if out_dt != dtp:
        new_code = {np.dtype(v): k for k, v in _ENVI.items()}[np.dtype(out_dt.type)]
        for band in (i_band, q_band):
            hp = dst_data / f"{band}.hdr"
            hp.write_text(re.sub(r"^(data type\s*=\s*)\d+", rf"\g<1>{new_code}", hp.read_text(), flags=re.M))
            txt = _retype_band(txt, band, "float32")
    dst_dim.write_text(txt, encoding="utf-8")


def recovery_stats(rec_phase: np.ndarray, true_phase: np.ndarray, valid: np.ndarray) -> dict:
    """RMS of wrapped(recovered - true) about zero and about the mean, plus retention =
    least-squares slope of recovered on true (1.0 = lobe fully retained, 0 = removed)."""
    if not valid.any():
        return {"n": 0, "rms_rad": float("nan"), "rms_centred_rad": float("nan"),
                "mean_rad": float("nan"), "retention": float("nan")}
    d = np.angle(np.exp(1j * (rec_phase[valid] - true_phase[valid])))
    mean = float(d.mean())
    t = true_phase[valid]
    rec_unw = t + d                        # residual is small, so this is the unwrapped recovery
    tc, rc = t - t.mean(), rec_unw - rec_unw.mean()
    slope = float((tc * rc).sum() / (tc * tc).sum()) if (tc * tc).sum() > 0 else float("nan")
    return {"n": int(valid.sum()), "rms_rad": float(np.sqrt(np.mean(d ** 2))),
            "rms_centred_rad": float(np.sqrt(np.mean((d - mean) ** 2))), "mean_rad": mean,
            "retention": slope}


def _selftest() -> int:
    ok = True
    t = ("<a>23-JUN-2026 22:50:50.409240</a><b>2026-06-23T22:50:59.847329</b>"
         "<c>2026-06-30T00:00:00</c><d>30-JUN-2026 23:59:59.5</d><e>x_2026 no date</e>")
    s = shift_times(t, 12)
    print(s)
    if not ("05-JUL-2026 22:50:50.409240" in s and "2026-07-05T22:50:59.847329" in s
            and "2026-07-12T00:00:00" in s and "12-JUL-2026 23:59:59.5" in s and "x_2026 no date" in s):
        print("  FAIL: date shift")
        ok = False
    if shift_times("31-DEC-2026 01:02:03.5", 1) != "01-JAN-2027 01:02:03.5":
        print("  FAIL: year roll")
        ok = False
    p = lobe_phase(np.array([0, 500, 1000]), 6.0, 500, 200)
    if abs(p[1] - 6.0) > 1e-12 or abs(p[0] - 6.0 * np.exp(-3.125)) > 1e-12 or abs(p[0] - p[2]) > 1e-12:
        print("  FAIL: lobe")
        ok = False

    tmp = Path(tempfile.mkdtemp())
    try:
        src = tmp / "S1_x.dim"
        (tmp / "S1_x.data").mkdir()
        src.write_text("<D>S1_x.data/i_IW3_VV.hdr 23-JUN-2026 22:50:50.4</D>")
        h, w = 6, 32
        rng = np.random.default_rng(1)
        z0 = rng.normal(size=(h, w)) + 1j * rng.normal(size=(h, w))
        for nm, v in (("i_IW3_VV", z0.real), ("q_IW3_VV", z0.imag)):
            v.astype(">f4").tofile(tmp / "S1_x.data" / f"{nm}.img")
            (tmp / "S1_x.data" / f"{nm}.hdr").write_text(
                f"samples = {w}\nlines = {h}\nbands = 1\ndata type = 4\nbyte order = 1\n")
        dst = tmp / "S1_y.dim"
        ph = lambda c: 0.02 * np.asarray(c, float)
        write_second_date(src, dst, 12, ph)
        out = dst.read_text()
        if "S1_y.data/i_IW3_VV.hdr" not in out or "05-JUL-2026" not in out or "S1_x" in out:
            print("  FAIL: dim rewrite:", out)
            ok = False
        zi = np.fromfile(tmp / "S1_y.data" / "i_IW3_VV.img", ">f4").reshape(h, w)
        zq = np.fromfile(tmp / "S1_y.data" / "q_IW3_VV.img", ">f4").reshape(h, w)
        z1 = zi + 1j * zq
        rec = np.angle(z0.astype(np.complex64) * np.conj(z1.astype(np.complex64)))
        tru = np.broadcast_to(ph(np.arange(w)), (h, w))
        st = recovery_stats(rec, tru, np.ones((h, w), bool))
        print(f"toy recovery: rms {st['rms_rad']:.2e} retention {st['retention']:.4f}")
        if not (st["rms_rad"] < 1e-5 and abs(st["retention"] - 1) < 1e-3):
            print("  FAIL: injected phase must be recovered as master*conj(B)")
            ok = False
        if (tmp / "S1_x.data" / "i_IW3_VV.img").read_bytes() != z0.real.astype(">f4").tobytes():
            print("  FAIL: source was modified")
            ok = False
        try:
            write_second_date(src, dst, 12)
            print("  FAIL: must refuse to overwrite")
            ok = False
        except FileExistsError:
            pass

        # int16 source (what a real S1 split SLC stores): the output must be float32, retyped in both
        # the .hdr and the .dim, and the phase must survive WITHOUT int16 rounding noise
        zi16 = np.round(300 * (rng.normal(size=(h, w)) + 1j * rng.normal(size=(h, w))))
        d16 = tmp / "S1_i.dim"
        (tmp / "S1_i.data").mkdir()
        d16.write_text(
            "<Dimap><Spectral_Band_Info><BAND_INDEX>0</BAND_INDEX><BAND_NAME>i_IW3_VV</BAND_NAME>"
            "<DATA_TYPE>int16</DATA_TYPE></Spectral_Band_Info>"
            "<Spectral_Band_Info><BAND_INDEX>1</BAND_INDEX><BAND_NAME>q_IW3_VV</BAND_NAME>"
            "<DATA_TYPE>int16</DATA_TYPE></Spectral_Band_Info>S1_i.data/ 23-JUN-2026 22:50:50.4</Dimap>")
        for nm, v in (("i_IW3_VV", zi16.real), ("q_IW3_VV", zi16.imag)):
            v.astype(">i2").tofile(tmp / "S1_i.data" / f"{nm}.img")
            (tmp / "S1_i.data" / f"{nm}.hdr").write_text(
                f"samples = {w}\nlines = {h}\nbands = 1\ndata type = 2\nbyte order = 1\n")
        o16 = tmp / "S1_o.dim"
        write_second_date(d16, o16, 12, ph)
        hdr_o = (tmp / "S1_o.data" / "i_IW3_VV.hdr").read_text()
        dim_o = o16.read_text()
        if "data type = 4" not in hdr_o or dim_o.count("<DATA_TYPE>float32</DATA_TYPE>") != 2 or "int16" in dim_o:
            print("  FAIL: int16 source must be retyped to float32 in .hdr and .dim:", hdr_o, dim_o)
            ok = False
        oi = np.fromfile(tmp / "S1_o.data" / "i_IW3_VV.img", ">f4").reshape(h, w)
        oq = np.fromfile(tmp / "S1_o.data" / "q_IW3_VV.img", ">f4").reshape(h, w)
        rec16 = np.angle(zi16 * np.conj(oi + 1j * oq))
        st16 = recovery_stats(rec16, tru, np.ones((h, w), bool))
        print(f"int16 source: rms {st16['rms_rad']:.2e}")
        if not st16["rms_rad"] < 1e-5:
            print("  FAIL: an int16 source must not pick up rounding noise")
            ok = False
        if (tmp / "S1_i.data" / "i_IW3_VV.img").read_bytes() != zi16.real.astype(">i2").tobytes():
            print("  FAIL: int16 source was modified")
            ok = False
        if list((tmp / "S1_o.data").glob("*.new")) or list((tmp / "S1_o.data").glob("*.tmp")):
            print("  FAIL: side files left behind")
            ok = False
        st = recovery_stats(0.6 * tru, tru, np.ones((h, w), bool))
        if abs(st["retention"] - 0.6) > 1e-6:
            print("  FAIL: retention measure")
            ok = False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["--selftest"]:
        raise SystemExit(_selftest())
    if len(a) == 5 and a[0] == "make":            # make <src.dim> <dst.dim> <days> <lobe|zero>
        fn = (lambda c: lobe_phase(c, AMP, CENTRE, SIGMA)) if a[4] == "lobe" else None
        write_second_date(Path(a[1]), Path(a[2]), int(a[3]), fn)
        print(f"wrote {a[2]} (+{a[3]} days, phase={a[4]})")
        raise SystemExit(0)
    print("usage: synth.py make <src.dim> <dst.dim> <days> <lobe|zero>   |  --selftest")
    raise SystemExit(2)
