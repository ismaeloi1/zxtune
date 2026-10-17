#!/bin/sh
# Build spcreplay against the snes_spc copy shipped in zxtune's 3rdparty/snesspc.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
BUILD=${BUILD:-$HERE/../build}
SPC=$HERE/../../../3rdparty/snesspc
mkdir -p "$BUILD"
g++ -O2 -std=c++14 -w -I"$SPC" -o "$BUILD/spcreplay" "$HERE/spcreplay.cpp" \
  "$SPC/snes_spc/SNES_SPC.cpp" "$SPC/snes_spc/SNES_SPC_misc.cpp" "$SPC/snes_spc/SNES_SPC_state.cpp" \
  "$SPC/snes_spc/SPC_DSP.cpp" "$SPC/snes_spc/SPC_Filter.cpp"
echo "built $BUILD/spcreplay"
