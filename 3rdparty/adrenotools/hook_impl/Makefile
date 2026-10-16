dynamic_name := hook_impl
dirs.root := ../../..
include $(dirs.root)/make/default.mak

source_files := ../src/hook/hook_impl.cpp

includes.dirs += ../include ../lib/linkernsbypass

libraries.3rdparty = adrenotools
libraries.android += log dl

include $(dirs.root)/makefile.mak
