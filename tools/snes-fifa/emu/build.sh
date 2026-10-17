#!/bin/sh
# Fetch snes9x at a pinned commit, apply the instrumentation patch and build
# the headless tracer `snestrace` into $BUILD (default: tools/snes-fifa/build).
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
BUILD=${BUILD:-$HERE/../build}
SNES9X_REPO=${SNES9X_REPO:-https://github.com/snes9xgit/snes9x.git}
SNES9X_COMMIT=1bcc369e89f08243e0a462882fb1f3e42e51de3a
SRC=$BUILD/snes9x
mkdir -p "$BUILD"
if [ ! -d "$SRC/.git" ]; then
  if [ -n "${SNES9X_LOCAL:-}" ]; then git clone -q "$SNES9X_LOCAL" "$SRC"; else git clone -q "$SNES9X_REPO" "$SRC"; fi
fi
cd "$SRC"
if [ "$(git rev-parse HEAD)" != "$SNES9X_COMMIT" ] || [ ! -f snesfifa_hooks.h ]; then
  git fetch -q origin "$SNES9X_COMMIT" 2>/dev/null || true
  git checkout -q -f "$SNES9X_COMMIT"
  git clean -qfd
  git apply "$HERE/snes9x-hooks.patch"
fi
SOURCES=$(sed -n '/^SOURCES_CXX/,/libretro.cpp/p' libretro/Makefile.common | tr -d '\\' | tr ' \t' '\n\n' | grep '\.cpp$' | sed 's|\$(CORE_DIR)/||')
OBJ=$BUILD/obj
mkdir -p "$OBJ"
FLAGS="-O2 -std=c++17 -DRIGHTSHIFT_IS_SAR -D__LIBRETRO__ -DALLOW_CPU_OVERCLOCK -DHAVE_STDINT_H -I. -Ilibretro -Ilibretro/libretro-common/include -Iapu -Iapu/bapu -w"
OBJS=""
for s in $SOURCES; do
  o=$OBJ/$(echo "$s" | tr '/' '_').o
  if [ ! -f "$o" ] || [ "$s" -nt "$o" ] || [ "$HERE/snes9x-hooks.patch" -nt "$o" ]; then
    echo "  CXX $s"; g++ $FLAGS -c "$s" -o "$o" &
    while [ "$(jobs -p | wc -l)" -ge "$(nproc)" ]; do sleep 0.2; done
  fi
  OBJS="$OBJS $o"
done
gcc -O2 -w -Ilibretro/libretro-common/include -c filter/snes_ntsc.c -o "$OBJ/snes_ntsc.o"
wait
g++ $FLAGS -c "$HERE/snestrace.cpp" -o "$OBJ/snestrace.o"
g++ -o "$BUILD/snestrace" "$OBJ/snestrace.o" $OBJS "$OBJ/snes_ntsc.o" -lz -lpthread
echo "built $BUILD/snestrace"
