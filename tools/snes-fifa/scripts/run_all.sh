#!/bin/sh
# Build the tools, run the tests, and extract every song from your own dump.
# usage: FIFA_ROM=/path/to/your/dump.sfc scripts/run_all.sh [OUTDIR]
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:-$HERE/out}
: "${FIFA_ROM:?set FIFA_ROM to your own cartridge dump}"
"$HERE/emu/build.sh"
"$HERE/replay/build.sh"
cd "$HERE"
python3 -m unittest discover -s tests -t . -v
python3 -m snesfifa.cli identify --rom "$FIFA_ROM"
python3 -m snesfifa.cli all --rom "$FIFA_ROM" --out "$OUT"
