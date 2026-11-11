#ifndef __YM3438_H__
#define __YM3438_H__

#include "../../stdtype.h"
#include "../snddef.h"
#include "../EmuStructs.h"

void nukedopn2_write(void *chip, UINT8 port, UINT8 data);
UINT8 nukedopn2_read(void *chip, UINT8 port);
void nukedopn2_update(void *chip, UINT32 numsamples, DEV_SMPL **sndptr);
void nukedopn2_set_options(void *chip, UINT32 flags);
void nukedopn2_set_mute_mask(void *chip, UINT32 mute);
void* nukedopn2_init(UINT32 clock, UINT32 rate);
void nukedopn2_shutdown(void *chip);
void nukedopn2_reset_chip(void *chip);
void nukedopn2_set_voices_cb(void *chip, DEVCB_VOICES cb, void* param);
UINT32 nukedopn2_get_voices_state(void *chip, UINT32 count, DEV_VOICE_STATE* states);

#endif	// __YM3438_H__
