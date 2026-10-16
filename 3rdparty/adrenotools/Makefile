library_name := adrenotools
dirs.root := ../..
include $(dirs.root)/make/default.mak

# only custom driver loading is used, see https://github.com/bylaws/libadrenotools
source_files := src/driver.cpp \
                lib/linkernsbypass/android_linker_ns.cpp \
                lib/linkernsbypass/elf_soname_patcher.cpp

includes.dirs += include lib/linkernsbypass

include $(dirs.root)/makefile.mak
