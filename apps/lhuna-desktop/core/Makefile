# Builds zxtune core static libraries for lhuna-desktop using the regular make system.
# The Qt6 application itself is built by CMake (see ../CMakeLists.txt), which runs
# 'make deps' here and queries 'make link_info' for libraries list and their location.
binary_name := lhuna_core
dirs.root := ../../..
include $(dirs.root)/make/default.mak

libraries.common = analysis async \
                  binary binary_compression binary_format \
                  core core_plugins_players core_plugins_archives \
                  debug devices_aym devices_beeper devices_dac devices_aym_dumper devices_fm devices_saa devices_z80 \
                  formats_archived formats_archived_multitrack formats_chiptune formats_packed formats_multitrack \
                  io \
                  l10n_stub \
                  module module_conversion module_properties \
                  parameters platform platform_version \
                  sound sound_backends strings \
                  visualization \
                  tools
libraries.3rdparty = asap atrac9 ffmpeg FLAC gme he ht hvl lazyusf2 lhasa lzma mgba mpg123 mt32emu ogg openmpt opus sidplayfp snesspc sseqplayer unrar v2m vio2sf vgm vgmstream vorbis xmp z80ex zlib

libraries.windows = advapi32 ole32 oldnames shell32 user32
libraries.mingw = ole32 winmm

include $(dirs.root)/makefile.mak

# Single-line machine readable output for CMake, keep the order: it matters for static linking
link_info:
	@echo "LIBDIR=$(abspath $(libraries.dir))"
	@echo "LIBS=$(libraries)"
	@echo "SYSLIBS=$(sort $(libraries.$(platform)))"
	@echo "DEFINES=$(DEFINES)"
