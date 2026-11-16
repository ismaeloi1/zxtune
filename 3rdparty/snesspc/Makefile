library_name := snesspc
dirs.root := ../..
include $(dirs.root)/make/default.mak

includes.dirs += .
source_files := $(addprefix snes_spc/,SNES_SPC.cpp SNES_SPC_misc.cpp SPC_DSP.cpp SPC_Filter.cpp)
# ares S-SMP/S-DSP, see ares/README.zxtune
source_files += $(addprefix ares/,ares_spc.cpp spc700/spc700.cpp dsp/dsp.cpp smp/smp.cpp)

include $(dirs.root)/makefile.mak
