#!/bin/bash
# llvm-mingw cross toolchain, same release as make/docker/builds/Dockerfile.mingw uses
# Usage: mingw-toolchain.sh <toolchains dir>

set -euo pipefail

toolchains=$(realpath -m "$1")
llvm_mingw=${llvm_mingw:-20230614}

mkdir -p "${toolchains}"
cd "${toolchains}"
if [ ! -x llvm-mingw/bin/clang ]; then
  wget -q "https://github.com/mstorsjo/llvm-mingw/releases/download/${llvm_mingw}/llvm-mingw-${llvm_mingw}-msvcrt-ubuntu-20.04-x86_64.tar.xz" -O - | tar -xJ
  mv llvm-mingw-${llvm_mingw}-msvcrt-ubuntu-20.04-x86_64 llvm-mingw
fi
