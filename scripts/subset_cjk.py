#!/usr/bin/env python3
"""Subset KUAN's typefaces for the APK.

CJK faces (Noto Serif SC, Ma Shan Zheng) keep only the glyphs that can
actually appear: every CJK character found in this repo's sources, in
workout2-app's sources (the behavioral reference whose seeds carry form
and move names), in the seed database's text columns, plus the curated
allowlist. Anything missing at runtime falls through to the system CJK
font, so a miss degrades gracefully rather than breaking.

Fraunces is trimmed to Latin + the handful of symbols the app sets in
type (×, –, arrows), keeping all variable axes (SOFT/WONK/opsz/wght).
"""

import sqlite3
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "scripts" / ".fontcache"
OUT = ROOT / "app" / "src" / "main" / "res" / "font"
V2 = ROOT.parent / "workout2-app"
SEED_DB = ROOT / "app" / "src" / "main" / "assets" / "workouts.db"
ALLOWLIST = ROOT / "scripts" / "hanzi-allowlist.txt"


def is_cjk(ch: str) -> bool:
    cp = ord(ch)
    return (
        0x2E80 <= cp <= 0x9FFF      # radicals, kangxi, CJK unified
        or 0x3400 <= cp <= 0x4DBF   # ext A (inside range above, kept for clarity)
        or 0xF900 <= cp <= 0xFAFF   # compatibility ideographs
        or 0xFF00 <= cp <= 0xFFEF   # fullwidth forms
    )


def chars_from_sources() -> set[str]:
    found: set[str] = set()
    for repo in (ROOT / "app" / "src", V2 / "app" / "src"):
        for f in repo.rglob("*.kt"):
            # Simplify.kt is the trad->simp lookup table, not display text —
            # its thousands of mapping chars would defeat the whole subset.
            if f.name == "Simplify.kt":
                continue
            found.update(c for c in f.read_text(encoding="utf-8") if is_cjk(c))
    return found


def chars_from_seed_db() -> set[str]:
    found: set[str] = set()
    if not SEED_DB.exists():
        return found
    con = sqlite3.connect(f"file:{SEED_DB}?immutable=1", uri=True)
    try:
        tables = [r[0] for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")]
        for t in tables:
            for row in con.execute(f'SELECT * FROM "{t}"'):
                for cell in row:
                    if isinstance(cell, str):
                        found.update(c for c in cell if is_cjk(c))
    finally:
        con.close()
    return found


def chars_from_allowlist() -> set[str]:
    found: set[str] = set()
    for line in ALLOWLIST.read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0]
        found.update(c for c in line if is_cjk(c))
    return found


def with_simplified(chars: set[str]) -> set[str]:
    """Add the simplified form of every collected character. Ma Shan Zheng is
    simplified-only and KUAN maps traditional -> simplified at render time
    (design/Simplify.kt), so the subset must carry the simplified targets even
    when only a traditional form appears in source or data."""
    import opencc  # provided by the project .venv
    cc = opencc.OpenCC("t2s")
    out = set(chars)
    for c in chars:
        s = cc.convert(c)
        if len(s) == 1:
            out.add(s)
    return out


def subset(src: Path, dst: Path, unicodes: str, extra: list[str] | None = None) -> None:
    cmd = [
        # Run fontTools through the venv interpreter rather than the pyftsubset
        # console script: its shebang kept the pre-rename Workout3-app path and
        # broke after the repo moved (17 Oct 2026).
        str(ROOT / ".venv" / "bin" / "python"), "-m", "fontTools.subset",
        str(src),
        f"--output-file={dst}",
        f"--unicodes={unicodes}",
        "--layout-features=*",
        "--name-IDs=1,2,13,14",  # family, style, license, license URL
        "--drop-tables+=DSIG",
    ] + (extra or [])
    subprocess.run(cmd, check=True)
    print(f"  {dst.name}: {dst.stat().st_size / 1024:.0f} KB "
          f"(from {src.stat().st_size / 1024 / 1024:.1f} MB)")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)

    cjk = chars_from_sources() | chars_from_seed_db() | chars_from_allowlist()
    cjk |= {"　"}  # ideographic space
    cjk = with_simplified(cjk)
    print(f"CJK glyph set: {len(cjk)} characters")

    cjk_unicodes = ",".join(f"U+{ord(c):04X}" for c in sorted(cjk))

    print("subsetting Ma Shan Zheng")
    subset(CACHE / "MaShanZheng.ttf", OUT / "ma_shan_zheng.ttf", cjk_unicodes)

    print("subsetting Fraunces")
    latin = ",".join([
        "U+0020-007E",   # ASCII
        "U+00A0-00FF",   # Latin-1 (×, °, accents)
        "U+0131",        # dotless i (case feature)
        "U+2010-2015",   # hyphens and dashes
        "U+2018-201F",   # curly quotes
        "U+2022",        # bullet
        "U+2026",        # ellipsis
        "U+2030",        # per mille
        "U+2190-2193",   # arrows
        "U+2212",        # minus
    ])
    subset(CACHE / "Fraunces.ttf", OUT / "fraunces.ttf", latin)

    for f in sorted(OUT.glob("*.ttf")):
        if f.stat().st_size > 1_500_000:
            sys.exit(f"{f.name} is suspiciously large — check the glyph scan")
    print("done")


if __name__ == "__main__":
    main()
