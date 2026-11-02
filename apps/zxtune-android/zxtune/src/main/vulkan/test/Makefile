# Host test of renderer, e.g. with lavapipe:
# make platform=linux arch=x86_64 && VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json <binary> <png_dir>
# Optional: <binary> <png_dir> <driver or -> <traces dump> <points per channel> <channels> <first shown channel>
binary_name := vulkan_scope_test
dirs.root := ../../../../../../..

source_files := ../api.cpp ../device.cpp ../renderer.cpp offscreen.cpp

include $(dirs.root)/makefile.mak
