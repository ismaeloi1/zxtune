# Lhuna desktop

Qt6 desktop port of the Android player features (not based on `apps/zxtune-qt`).

## Architecture

- `src/visualization` - toolkit independent C++20 library (built by make system like other core libraries):
  ports of Android views logic (`ChipColumns`, `SidDescription`, `SidDashboardText`, `SidFilterModel`,
  `ChipWidgets`, `SidDashboardView`, `ScopeGrid`, `TriggerPresets`, `EmulationContext`, `EngineBrands`/`StatusHeader`).
  Drawing goes via abstract `Visualization::Canvas`, so the same code can be used by other frontends.
- `engine/` - Qt free playback facade: modules detection (including archives content), sound backends of
  `src/sound/backends` (ALSA first on Linux, WinMM/DirectSound on Windows) with device selection and latency
  estimate, live synchronized options container, `Sound::Scope` and spectrum analyzer access.
- `ui/` - Qt6 Widgets frontend: playlist (kept between sessions, XSPF/M3U8 import and export compatible with
  the Android app) and files browser (archives and multitrack files contents), transport, now playing with original
  driver and engine lines, oscilloscope, spectrum analyzer and dashboard (`QPainter` over `QtCanvas`),
  semi-transparent live settings panel (oscilloscope, trigger presets, settings of the playing tune's chips,
  playback), all settings dialog, About dialog with open source components, MPRIS (Linux) and global media keys
  (Windows), dark theme.
- `core/Makefile` - builds all the needed core libraries via make system and reports their list (`link_info`).
- `res/` - icons and freedesktop files (desktop entry, AppStream metainfo), `app.zxtune.Lhuna` is used as Wayland
  app_id, desktop file and AppStream id.
- `package/` - AppImage script, llvm-mingw toolchain file and Windows deployment script used by CI.

## Build

Requirements: GNU make, C++20 compiler, CMake 3.21+, Qt6 base (`qt6-base-dev` on Debian/Ubuntu), Boost headers,
ALSA/PulseAudio headers on Linux. Optional: Qt6 DBus (MPRIS), `qt6-wayland` at runtime for native Wayland.
GPU oscilloscope needs Qt >= 6.7 with shader tools and Gui private headers (older Qt, e.g. 6.4 of Ubuntu 24.04,
gets QPainter one), CI uses official Qt 6.8 via aqtinstall:
`aqt install-qt linux desktop 6.8.3 linux_gcc_64 --archives qtbase qtwayland qtsvg icu -m qtshadertools -O qt`
and `-DCMAKE_PREFIX_PATH=$PWD/qt/6.8.3/gcc_64` (AppImage script needs `QMAKE=$PWD/qt/6.8.3/gcc_64/bin/qmake`).

```sh
cmake -S apps/lhuna-desktop -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build
./build/lhuna-desktop samples/chiptunes/MOS6581/sid/Love_Is_a_Shield.sid
```

Core libraries are built by `make -C apps/lhuna-desktop/core platform=<platform> deps` as part of the build
(disable with `-DLHUNA_BUILD_CORE=OFF` to use prebuilt ones). Extra make arguments are passed via
`-DZXTUNE_MAKE_ARGS="jumbo.enabled=1 arch=x86_64 ..."`, platform via `-DZXTUNE_PLATFORM=linux|mingw|darwin`.

`cmake --install build` installs binary, desktop entry, metainfo and icons. Portable package:
`LINUXDEPLOY=/path/to/linuxdeploy apps/lhuna-desktop/package/appimage.sh build dist` (linuxdeploy-plugin-qt
should be next to linuxdeploy).

Linux session: native Wayland is used on Wayland sessions (X11 if Wayland platform plugin is missing),
`QT_QPA_PLATFORM` overrides the choice.

### Windows (cross compilation from Linux)

The same way as other zxtune Windows builds: llvm-mingw (UCRT) toolchain and official Qt build of the same
compiler, see `.github/workflows/lhuna-desktop.yml` for exact versions.

```sh
pip install aqtinstall
aqt install-qt linux desktop 6.8.3 linux_gcc_64 --archives qtbase qtshadertools icu -m qtshadertools -O qt
aqt install-qt windows desktop 6.8.3 win64_llvm_mingw --archives qtbase qtshadertools -m qtshadertools -O qt
export LLVM_MINGW=/path/to/llvm-mingw-20231128-ucrt-ubuntu-20.04-x86_64
mkdir -p prebuilt/boost-system/include && ln -s /usr/include/boost prebuilt/boost-system/include/boost
cmake -S apps/lhuna-desktop -B build-win -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=$PWD/apps/lhuna-desktop/package/llvm-mingw.cmake \
  -DCMAKE_PREFIX_PATH=$PWD/qt/6.8.3/llvm-mingw_64 -DQT_HOST_PATH=$PWD/qt/6.8.3/gcc_64 \
  -DZXTUNE_MAKE_ARGS="arch=x86_64 host=linux compiler=clang execprefix=$LLVM_MINGW/bin/x86_64-w64-mingw32- \
    jumbo.enabled=1 tools.python=python3 prebuilt.dir=$PWD/prebuilt boost.version=system"
cmake --build build-win
python3 apps/lhuna-desktop/package/windows_deploy.py build-win/lhuna-desktop.exe dist/lhuna \
  --qt qt/6.8.3/llvm-mingw_64 --toolchain $LLVM_MINGW
```

## Headless check

```sh
QT_QPA_PLATFORM=offscreen ./build/lhuna-desktop --no-session --size 1400x860 --delay 4000 --settings-panel \
  --screenshot shot.png samples/chiptunes/Multi/vgm/EarthwormJim.vgm
```

Other checks: `--spectrum`, `--browse <archive>`, `--dialog about|settings[:page]` (screenshot of dialog),
`--export <playlist.xspf|m3u8>`.

## Keyboard

| Action | Keys |
|---|---|
| Play/Pause | Space, media keys |
| Stop / Next / Previous | Ctrl+. / Ctrl+Right / Ctrl+Left |
| Seek | Shift+Right, Shift+Left |
| Shuffle / Repeat mode | Ctrl+H / Ctrl+R |
| Add files / folder | Ctrl+O / Ctrl+Shift+O |
| Import / export playlist | Ctrl+I / Ctrl+S |
| Playlist / Files tab | Ctrl+1 / Ctrl+2 |
| Live settings / Spectrum / Dashboard | F2 / F3 / F4 |
| All settings | Ctrl+, |
| Full screen | F11 |
| Shortcuts list | F1 |
| Remove from playlist / leave archive | Delete / Backspace |
