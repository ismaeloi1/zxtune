#!/bin/bash
# Static boost and Qt for mingw cross builds on Linux hosts.
# Mirrors make/docker/builds/Dockerfile.mingw so CI binaries match docker-made releases.
# Usage: mingw-prebuilt.sh <prebuilt dir> <toolchains dir>
# Expects llvm-mingw toolchain at <toolchains dir>/llvm-mingw (see mingw-toolchain.sh)

set -euo pipefail

prebuilt=$(realpath -m "$1")
toolchains=$(realpath -m "$2")
arch=${arch:-x86_64}
execprefix=${arch/x86/i686}
execprefix=${execprefix/i686_64/x86_64}
execprefix=${execprefix/arm64/aarch64}-w64-mingw32-
addressmodel=$([ "${arch}" = x86 ] && echo 32 || echo 64)
boost=${boost:-1.82.0}
qt=${qt:-5.15.2}
cores=${cores:-$(nproc)}
work=${work:-$(mktemp -d)}

export PATH=${toolchains}/llvm-mingw/bin:${PATH}

mkdir -p "${prebuilt}" "${work}"
cd "${work}"

if [ ! -d "${prebuilt}/boost-${boost}-mingw-${arch}/lib" ]; then
  wget -q "https://github.com/boostorg/boost/releases/download/boost-${boost}/boost-${boost}.tar.xz" -O - | tar -xJ
  (cd boost-${boost} &&
    ./bootstrap.sh clang &&
    # mimic gcc toolchain to avoid .lib extension for artifacts
    echo "using gcc : windows : ${execprefix}g++ ;" > tools/build/src/user-config.jam &&
    ./b2 toolset=gcc-windows link=static threading=multi target-os=windows variant=release --layout=system \
      address-model=${addressmodel} cxxflags=-mno-ms-bitfields cxxflags=-ffunction-sections cxxflags=-fdata-sections \
      --with-locale --with-program_options -j${cores} \
      --includedir=${prebuilt}/boost-${boost}/include --libdir=${prebuilt}/boost-${boost}-mingw-${arch}/lib install)
  rm -Rf boost-${boost}
fi

if [ ! -d "${prebuilt}/qt-${qt}-mingw-${arch}/lib" ]; then
  # only qtbase is required, it contains all the used modules and plugins
  git clone -q --depth=1 --branch=v${qt} https://github.com/qt/qtbase.git qtbase-${qt}
  (cd qtbase-${qt} &&
    (cd src/corelib && sed -i '46i#include <limits>' global/qfloat16.h text/qbytearraymatcher.h global/qendian.h) &&
    ./configure -xplatform win32-clang-g++ -device-option CROSS_COMPILE=${execprefix} \
      -prefix ${prebuilt}/qt-${qt}-mingw-${arch} -release -opensource -static -confirm-license -no-rpath \
      -nomake examples -nomake tests \
      -no-dbus -no-opengl -no-sql-sqlite \
      -no-ico -no-gif -no-pch -no-glib \
      -no-feature-testlib -no-feature-sql \
      -qt-pcre -qt-zlib -qt-libpng -qt-libjpeg -qt-freetype -qt-harfbuzz -schannel &&
    make -j${cores} &&
    make install)
  rm -Rf qtbase-${qt}
fi
