# Cross compilation for Windows x86_64 from Linux with llvm-mingw (UCRT), the same toolchain as Qt's win64_llvm_mingw
# binaries are built with, so C++ runtime (libc++) is shared by Qt and application.
# Usage: -DCMAKE_TOOLCHAIN_FILE=<this> -DLLVM_MINGW=<toolchain dir> -DCMAKE_PREFIX_PATH=<Qt for Windows>
#        -DQT_HOST_PATH=<Qt for Linux of the same version, provides moc/rcc>
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(LLVM_MINGW "$ENV{LLVM_MINGW}" CACHE PATH "llvm-mingw toolchain directory")
set(LLVM_MINGW_TRIPLE x86_64-w64-mingw32)
# compiler checks are separate projects, they get the toolchain location this way
list(APPEND CMAKE_TRY_COMPILE_PLATFORM_VARIABLES LLVM_MINGW CMAKE_PREFIX_PATH)

set(CMAKE_C_COMPILER "${LLVM_MINGW}/bin/${LLVM_MINGW_TRIPLE}-clang")
set(CMAKE_CXX_COMPILER "${LLVM_MINGW}/bin/${LLVM_MINGW_TRIPLE}-clang++")
set(CMAKE_RC_COMPILER "${LLVM_MINGW}/bin/${LLVM_MINGW_TRIPLE}-windres")
set(CMAKE_AR "${LLVM_MINGW}/bin/${LLVM_MINGW_TRIPLE}-ar" CACHE FILEPATH "")
set(CMAKE_RANLIB "${LLVM_MINGW}/bin/${LLVM_MINGW_TRIPLE}-ranlib" CACHE FILEPATH "")
set(CMAKE_STRIP "${LLVM_MINGW}/bin/${LLVM_MINGW_TRIPLE}-strip" CACHE FILEPATH "")

# Target headers, libraries and packages (Qt) are looked up in toolchain sysroot and prefix path only, so host ones
# (e.g. /usr/include/vulkan found by Qt's WrapVulkanHeaders) never get into Windows build. Host tools (moc etc) are
# taken from QT_HOST_PATH.
set(CMAKE_FIND_ROOT_PATH "${LLVM_MINGW}/${LLVM_MINGW_TRIPLE}" ${CMAKE_PREFIX_PATH})
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
