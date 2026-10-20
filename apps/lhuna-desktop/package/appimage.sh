#!/bin/sh
# Portable Linux package: AppImage with Qt libraries and both Wayland and X11 (xcb) platform plugins.
# Usage: appimage.sh <cmake build dir> <output dir>
# Environment: LINUXDEPLOY - linuxdeploy executable (linuxdeploy-plugin-qt should be next to it or in PATH),
#              QMAKE - qmake of Qt used for build (qmake6 by default), VERSION - version in file name.
set -eu

BUILD=$(realpath "$1")
OUT=$(realpath "$2")
APP_ID=app.zxtune.Lhuna
APPDIR="$BUILD/AppDir"
LINUXDEPLOY=${LINUXDEPLOY:-linuxdeploy}

rm -rf "$APPDIR"
# debug info of core libraries makes the binary several times bigger
DESTDIR="$APPDIR" cmake --install "$BUILD" --prefix /usr --strip

export QMAKE=${QMAKE:-qmake6}
# Wayland platform is selected on Wayland sessions, xcb is the default and fallback one
export EXTRA_PLATFORM_PLUGINS="libqwayland-egl.so;libqwayland-generic.so;libqxcb.so"
# headless runs (e.g. CI screenshots) use offscreen platform
export EXTRA_PLATFORM_PLUGINS="$EXTRA_PLATFORM_PLUGINS;libqoffscreen.so"
# bundled strip is too old for binaries of current toolchains (.relr.dyn sections)
export NO_STRIP=1
# FUSE is not available in containers
export APPIMAGE_EXTRACT_AND_RUN=1
export LINUXDEPLOY_OUTPUT_VERSION=${VERSION:-continuous}

mkdir -p "$OUT"
cd "$OUT"
"$LINUXDEPLOY" --appdir "$APPDIR" \
  --desktop-file "$APPDIR/usr/share/applications/$APP_ID.desktop" \
  --icon-file "$APPDIR/usr/share/icons/hicolor/256x256/apps/$APP_ID.png" \
  --plugin qt \
  --output appimage
ls -l "$OUT"/*.AppImage
