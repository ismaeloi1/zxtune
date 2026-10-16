# Oscilloscope renderer is a separate small library: UI process doesn't load the whole playback engine
dynamic_name := zxtunevk
dirs.root := ../../../../../..

source_dirs := .

libraries.android += android log dl

# custom Vulkan drivers (e.g. Mesa Turnip) loading for Adreno GPUs, supported only for arm64 devices
ifeq ($(arch),arm64-v8a)
libraries.3rdparty = adrenotools
# builds hook implementation library as well
depends += 3rdparty/adrenotools/main_hook
defines += VULKAN_SCOPE_ADRENOTOOLS
endif

ld_flags += -Wl,--version-script=libzxtunevk.version

include $(dirs.root)/makefile.mak
