#!/bin/zsh
set -euo pipefail

# Downloads KUAN's three typefaces (pinned google/fonts URLs) into a local
# cache, sets up the python venv, then subsets them for the APK via
# subset_cjk.py. The subset outputs under app/src/main/res/font/ and the
# OFL licenses under app/src/main/assets/licenses/ are committed, so this
# script only needs to run again if the type design changes.

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT_DIR/scripts/.fontcache"
mkdir -p "$CACHE"

BASE="https://raw.githubusercontent.com/google/fonts/main/ofl"

fetch() {
  local out="$CACHE/$1"
  if [ ! -s "$out" ]; then
    echo "fetching $1"
    # --globoff: the %5B..%5D brackets in variable-font names must not glob
    curl --globoff -fsSL "$2" -o "$out"
  else
    echo "cached   $1"
  fi
}

fetch "Fraunces.ttf"        "$BASE/fraunces/Fraunces%5BSOFT%2CWONK%2Copsz%2Cwght%5D.ttf"
fetch "OFL-Fraunces.txt"    "$BASE/fraunces/OFL.txt"
fetch "NotoSerifSC.ttf"     "$BASE/notoserifsc/NotoSerifSC%5Bwght%5D.ttf"
fetch "OFL-NotoSerifSC.txt" "$BASE/notoserifsc/OFL.txt"
fetch "MaShanZheng.ttf"     "$BASE/mashanzheng/MaShanZheng-Regular.ttf"
fetch "OFL-MaShanZheng.txt" "$BASE/mashanzheng/OFL.txt"

if [ ! -x "$ROOT_DIR/.venv/bin/python" ]; then
  echo "creating venv"
  python3 -m venv "$ROOT_DIR/.venv"
  "$ROOT_DIR/.venv/bin/pip" -q install fonttools
fi

LICENSES="$ROOT_DIR/app/src/main/assets/licenses"
mkdir -p "$LICENSES"
cp "$CACHE/OFL-Fraunces.txt" "$LICENSES/OFL-Fraunces.txt"
cp "$CACHE/OFL-NotoSerifSC.txt" "$LICENSES/OFL-NotoSerifSC.txt"
cp "$CACHE/OFL-MaShanZheng.txt" "$LICENSES/OFL-MaShanZheng.txt"

"$ROOT_DIR/.venv/bin/python" "$ROOT_DIR/scripts/subset_cjk.py"
