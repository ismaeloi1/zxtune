#ifndef __YMFMINTF_H__
#define __YMFMINTF_H__

// zxtune: Yamaha FM chips emulated by ymfm (BSD-3-Clause, see ymfm/LICENSE), Aaron Giles' cores used by MAME
// since 0.230, with per-channel output taps and voices state

#include "../EmuStructs.h"

#ifdef __cplusplus
extern "C"
{
#endif

extern const DEV_DEF devDef_YM2203_YMFM;
extern const DEV_DEF devDef_YM2608_YMFM;
extern const DEV_DEF devDef_YM2610_YMFM;
extern const DEV_DEF devDef_YM2612_YMFM;
extern const DEV_DEF devDef_YM2151_YMFM;

#ifdef __cplusplus
}
#endif

#endif	// __YMFMINTF_H__
