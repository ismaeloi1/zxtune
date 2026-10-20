# Lhuna desktop

Qt6 desktop port of the Android player features (not based on `apps/zxtune-qt`).

## Architecture

- `src/visualization` - toolkit independent C++20 library (built by make system like other core libraries):
  ports of Android views logic (`ChipColumns`, `SidDescription`, `SidDashboardText`, `SidFilterModel`,
  `ChipWidgets`, `SidDashboardView`, `ScopeGrid`, `TriggerPresets`, `EmulationContext`). Drawing goes via
  abstract `Visualization::Canvas`, so the same code can be used by other frontends.
- `engine/` - Qt free playback facade: modules detection, sound backends of `src/sound/backends`
  (ALSA first on Linux, WinMM/DirectSound on Windows), live synchronized options container, `Sound::Scope` access.
- `ui/` - Qt6 Widgets frontend: playlist and files browser, transport, now playing, oscilloscope and dashboard
  (`QPainter` over `QtCanvas`), semi-transparent live settings panel (oscilloscope, trigger presets, settings of the
  playing tune's chips, playback), dark theme.
- `core/Makefile` - builds all the needed core libraries via make system and reports their list (`link_info`).

## Build

Requirements: GNU make, C++20 compiler, CMake 3.21+, Qt6 base (`qt6-base-dev` on Debian/Ubuntu), Boost headers,
ALSA/PulseAudio headers on Linux.

```sh
cmake -S apps/lhuna-desktop -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build
./build/lhuna-desktop samples/chiptunes/MOS6581/sid/Love_Is_a_Shield.sid
```

Core libraries are built by `make -C apps/lhuna-desktop/core platform=<platform> deps` as part of the build
(disable with `-DLHUNA_BUILD_CORE=OFF` to use prebuilt ones). Extra make arguments are passed via
`-DZXTUNE_MAKE_ARGS="jumbo.enabled=1 arch=x86_64 ..."`, platform via `-DZXTUNE_PLATFORM=linux|mingw|darwin`.

GPU oscilloscope (`ui/gpu_scope_view.cpp`, `QRhiWidget`: Vulkan, OpenGL 3.3+, Direct3D 11 on Windows) requires Qt 6.7+
with private headers of Gui module and Qt Shader Tools (`qt6-base-private-dev qt6-shadertools-dev` on Debian/Ubuntu,
Ubuntu 24.04 ships Qt 6.4), it's disabled otherwise (or by `-DLHUNA_GPU_SCOPE=OFF`) and raster view is used.
Backend is selected by "Graphics backend" setting (applied on restart): Auto probes Vulkan, then Direct3D 11 (Windows)
and OpenGL, skipping CPU rasterizers (llvmpipe, lavapipe, WARP) which compose the window slower than raster widgets;
native Wayland uses OpenGL only in Auto mode. Selected backend, GPU name and frame statistics are displayed in status lines
below dashboard.

Windows: MSYS2 MINGW64 shell with `mingw-w64-x86_64-{gcc,cmake,ninja,qt6-base,boost}` (plus `qt6-shadertools` and `vulkan-headers` for GPU
oscilloscope with Vulkan) and msys `make`,
`-DZXTUNE_MAKE_ARGS="host=linux arch=x86_64 jumbo.enabled=1"`.

## Headless check

```sh
QT_QPA_PLATFORM=offscreen ./build/lhuna-desktop --size 1400x860 --delay 4000 --settings-panel \
  --screenshot shot.png samples/chiptunes/Multi/vgm/EarthwormJim.vgm
```

Keys: Space - play/pause, F2 - live settings, Delete - remove playlist item.
