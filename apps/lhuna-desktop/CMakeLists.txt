# Lhuna desktop player: Qt6 frontend over zxtune core libraries.
# Core libraries are built by the regular GNU make system (see core/Makefile), CMake only drives it and links
# resulting static libraries, so there's single source of truth for core build options.
cmake_minimum_required(VERSION 3.21)
project(lhuna-desktop LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

find_package(Qt6 REQUIRED COMPONENTS Widgets)
# MPRIS media controls (desktop shells, media keys, KDE Connect etc.), optional
if(UNIX AND NOT APPLE)
  find_package(Qt6 COMPONENTS DBus)
endif()

set(ZXTUNE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." CACHE PATH "zxtune repository root")
if(WIN32)
  set(ZXTUNE_DEFAULT_PLATFORM mingw)
elseif(APPLE)
  set(ZXTUNE_DEFAULT_PLATFORM darwin)
else()
  set(ZXTUNE_DEFAULT_PLATFORM linux)
endif()
set(ZXTUNE_PLATFORM "${ZXTUNE_DEFAULT_PLATFORM}" CACHE STRING "zxtune make system platform")
set(ZXTUNE_MAKE_ARGS "jumbo.enabled=1" CACHE STRING "additional arguments for zxtune make system (e.g. arch=x86_64)")
set(ZXTUNE_MAKE make CACHE STRING "GNU make executable")
option(LHUNA_BUILD_CORE "Build zxtune core libraries via make as part of the build" ON)

separate_arguments(ZXTUNE_MAKE_ARGS_LIST UNIX_COMMAND "${ZXTUNE_MAKE_ARGS}")
set(ZXTUNE_CORE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/core")
set(ZXTUNE_MAKE_COMMAND ${ZXTUNE_MAKE} -C "${ZXTUNE_CORE_DIR}" "platform=${ZXTUNE_PLATFORM}" ${ZXTUNE_MAKE_ARGS_LIST})

# Libraries list, order and location are taken from make system
execute_process(COMMAND ${ZXTUNE_MAKE_COMMAND} -s link_info
  OUTPUT_VARIABLE ZXTUNE_LINK_INFO RESULT_VARIABLE ZXTUNE_LINK_RESULT)
if(NOT ZXTUNE_LINK_RESULT EQUAL 0)
  message(FATAL_ERROR "Failed to query zxtune make system: ${ZXTUNE_LINK_RESULT}")
endif()
string(REGEX MATCH "LIBDIR=([^\n]*)" _ "${ZXTUNE_LINK_INFO}")
set(ZXTUNE_LIBDIR "${CMAKE_MATCH_1}")
# MSYS make reports POSIX-like paths (/c/dir), native CMake on Windows needs C:/dir
if(CMAKE_HOST_WIN32 AND ZXTUNE_LIBDIR MATCHES "^/([a-zA-Z])/(.*)$")
  set(ZXTUNE_LIBDIR "${CMAKE_MATCH_1}:/${CMAKE_MATCH_2}")
endif()
string(REGEX MATCH "LIBS=([^\n]*)" _ "${ZXTUNE_LINK_INFO}")
separate_arguments(ZXTUNE_LIBS UNIX_COMMAND "${CMAKE_MATCH_1}")
string(REGEX MATCH "SYSLIBS=([^\n]*)" _ "${ZXTUNE_LINK_INFO}")
separate_arguments(ZXTUNE_SYSLIBS UNIX_COMMAND "${CMAKE_MATCH_1}")
message(STATUS "zxtune core libraries at ${ZXTUNE_LIBDIR}")

set(ZXTUNE_LIB_FILES)
foreach(lib ${ZXTUNE_LIBS})
  list(APPEND ZXTUNE_LIB_FILES "${ZXTUNE_LIBDIR}/lib${lib}.a")
endforeach()

if(LHUNA_BUILD_CORE)
  cmake_host_system_information(RESULT CORES QUERY NUMBER_OF_LOGICAL_CORES)
  add_custom_target(zxtune_core
    COMMAND ${ZXTUNE_MAKE_COMMAND} -j${CORES} deps
    BYPRODUCTS ${ZXTUNE_LIB_FILES}
    COMMENT "Building zxtune core libraries"
    USES_TERMINAL)
endif()

set(LHUNA_SOURCES
  main.cpp
  engine/engine.cpp
  ui/about_dialog.cpp
  ui/all_settings_dialog.cpp
  ui/filter_curve_view.cpp
  ui/main_window.cpp
  ui/open_source_components.cpp
  ui/options_form.cpp
  ui/playlist_io.cpp
  ui/qt_canvas.cpp
  ui/scope_view.cpp
  ui/settings.cpp
  ui/settings_panel.cpp
  ui/spectrum_view.cpp
  ui/theme.cpp
  res/lhuna.qrc
)
if(WIN32)
  list(APPEND LHUNA_SOURCES ui/media_keys_win.cpp)
  set(LHUNA_ICO "${CMAKE_CURRENT_SOURCE_DIR}/res/lhuna.ico")
  configure_file(res/lhuna.rc.in "${CMAKE_CURRENT_BINARY_DIR}/lhuna.rc" @ONLY)
  list(APPEND LHUNA_SOURCES "${CMAKE_CURRENT_BINARY_DIR}/lhuna.rc")
elseif(TARGET Qt6::DBus)
  list(APPEND LHUNA_SOURCES ui/media_keys_mpris.cpp)
else()
  list(APPEND LHUNA_SOURCES ui/media_keys_stub.cpp)
endif()

add_executable(lhuna-desktop WIN32 ${LHUNA_SOURCES})
if(LHUNA_BUILD_CORE)
  add_dependencies(lhuna-desktop zxtune_core)
endif()

target_include_directories(lhuna-desktop PRIVATE "${ZXTUNE_ROOT}" "${ZXTUNE_ROOT}/include" "${ZXTUNE_ROOT}/src")
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
  # the same as zxtune make system uses, char signedness affects inline code of core headers
  target_compile_options(lhuna-desktop PRIVATE -funsigned-char -fno-strict-aliasing)
endif()

if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang" AND NOT APPLE)
  set(LINK_GROUP_BEGIN "-Wl,--start-group")
  set(LINK_GROUP_END "-Wl,--end-group")
endif()
target_link_libraries(lhuna-desktop PRIVATE
  Qt6::Widgets
  $<$<TARGET_EXISTS:Qt6::DBus>:Qt6::DBus>
  ${LINK_GROUP_BEGIN} ${ZXTUNE_LIB_FILES} ${LINK_GROUP_END}
  ${ZXTUNE_SYSLIBS}
)

install(TARGETS lhuna-desktop RUNTIME DESTINATION bin)
