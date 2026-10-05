library_name := mt32emu
dirs.root := ../..
include $(dirs.root)/make/default.mak

includes.dirs += src
source_files := $(addprefix src/,Analog.cpp BReverbModel.cpp Display.cpp File.cpp FileStream.cpp \
  LA32FloatWaveGenerator.cpp LA32Ramp.cpp LA32WaveGenerator.cpp MidiStreamParser.cpp Part.cpp Partial.cpp \
  PartialManager.cpp Poly.cpp ROMInfo.cpp Synth.cpp Tables.cpp TVA.cpp TVF.cpp TVP.cpp sha1/sha1.cpp)

include $(dirs.root)/makefile.mak
