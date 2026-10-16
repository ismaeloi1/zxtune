dynamic_name := main_hook
dirs.root := ../../..
include $(dirs.root)/make/default.mak

source_files := ../src/hook/main_hook.c

includes.dirs += ../src/hook ../include

libraries.dynamic = hook_impl
libraries.android += dl
# hooks should take precedence over system symbols in driver's namespace
ld_flags += -Wl,-z,global

include $(dirs.root)/makefile.mak

# linked with hook implementation library, so it should be built first
$(target): | hook_impl_library

.PHONY: hook_impl_library
hook_impl_library:
	$(MAKE) -C ../hook_impl
