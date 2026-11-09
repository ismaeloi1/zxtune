#ifndef __EMUSTRUCTS_H__
#define __EMUSTRUCTS_H__

#ifdef __cplusplus
extern "C"
{
#endif

#ifdef _MSC_VER
#pragma warning (disable: 4200)	// disable warning for "T arr[];" in structs
#endif

#include "../stdtype.h"
#include "snddef.h"

typedef UINT8 DEV_ID;
typedef struct _device_core_definition DEV_DEF;
typedef struct _device_declaration DEV_DECL;
typedef struct _device_info DEV_INFO;
typedef struct _device_link_ids DEVLINK_IDS;
typedef struct _device_link_info DEVLINK_INFO;
typedef struct _device_generic_config DEV_GEN_CFG;


typedef const char* (*DEVDECLFUNC_NAME)(const DEV_GEN_CFG* devCfg);
typedef UINT16 (*DEVDECLFUNC_CHNCOUNT)(const DEV_GEN_CFG* devCfg);
typedef const char** (*DEVDECLFUNC_CHNNAMES)(const DEV_GEN_CFG* devCfg);
typedef const DEVLINK_IDS* (*DEVDECLFUNC_LINKIDS)(const DEV_GEN_CFG* devCfg);


typedef void (*DEVCB_SRATE_CHG)(void* userParam, UINT32 newSRate);
typedef void (*DEVCB_LOG)(void* userParam, void* source, UINT8 level, const char* message);

typedef UINT8 (*DEVFUNC_START)(const DEV_GEN_CFG* cfg, DEV_INFO* retDevInf);
typedef void (*DEVFUNC_CTRL)(void* info);
typedef void (*DEVFUNC_UPDATE)(void* info, UINT32 samples, DEV_SMPL** outputs);
typedef void (*DEVFUNC_OPTMASK)(void* info, UINT32 optionBits);
typedef void (*DEVFUNC_PANALL)(void* info, const INT16* channelPanVal);
typedef void (*DEVFUNC_SRCCB)(void* info, DEVCB_SRATE_CHG SmpRateChgCallback, void* paramPtr);
typedef UINT8 (*DEVFUNC_LINKDEV)(void* info, UINT8 linkID, const DEV_INFO* devInfLink);
typedef void (*DEVFUNC_SETLOGCB)(void* info, DEVCB_LOG logFunc, void* userParam);

typedef UINT8 (*DEVFUNC_READ_A8D8)(void* info, UINT8 addr);
typedef UINT16 (*DEVFUNC_READ_A8D16)(void* info, UINT8 addr);
typedef UINT8 (*DEVFUNC_READ_A16D8)(void* info, UINT16 addr);
typedef UINT16 (*DEVFUNC_READ_A16D16)(void* info, UINT16 addr);
typedef UINT32 (*DEVFUNC_READ_CLOCK)(void* info);
typedef UINT32 (*DEVFUNC_READ_SRATE)(void* info);
typedef UINT32 (*DEVFUNC_READ_VOLUME)(void* info);

typedef void (*DEVFUNC_WRITE_A8D8)(void* info, UINT8 addr, UINT8 data);
typedef void (*DEVFUNC_WRITE_A8D16)(void* info, UINT8 addr, UINT16 data);
typedef void (*DEVFUNC_WRITE_A16D8)(void* info, UINT16 addr, UINT8 data);
typedef void (*DEVFUNC_WRITE_A16D16)(void* info, UINT16 addr, UINT16 data);
typedef void (*DEVFUNC_WRITE_MEMSIZE)(void* info, UINT32 memsize);
typedef void (*DEVFUNC_WRITE_BLOCK)(void* info, UINT32 offset, UINT32 length, const UINT8* data);
typedef void (*DEVFUNC_WRITE_CLOCK)(void* info, UINT32 clock);
typedef void (*DEVFUNC_WRITE_VOLUME)(void* info, INT32 volume);	// 16.16 fixed point
typedef void (*DEVFUNC_WRITE_VOL_LR)(void* info, INT32 volL, INT32 volR);

#define RWF_WRITE		0x00
#define RWF_READ		0x01
#define RWF_QUICKWRITE	(0x02 | RWF_WRITE)
#define RWF_QUICKREAD	(0x02 | RWF_READ)
#define RWF_REGISTER	0x00	// register r/w
#define RWF_MEMORY		0x10	// memory (RAM) r/w
// Note: These chip setting constants can be ORed with RWF_WRITE/RWF_READ.
#define RWF_CLOCK		0x80	// chip clock
#define RWF_SRATE		0x82	// sample rate
#define RWF_VOLUME		0x84	// volume (all speakers)
#define RWF_VOLUME_LR	0x86	// volume (left/right separately)
#define RWF_CHN_MUTE	0x90	// set channel muting (DEVRW_VALUE = single channel, DEVRW_ALL = mask)
#define RWF_CHN_PAN		0x92	// set channel panning (DEVRW_VALUE = single channel, DEVRW_ALL = array)
#define RWF_VOICES		0xA0	// per-channel output tap (zxtune extension)
							//  RWF_WRITE: DEVFUNC_SET_VOICES_CB, RWF_READ: DEVFUNC_GET_VOICES_STATE

// register/memory DEVRW constants
#define DEVRW_A8D8		0x11	//  8-bit address,  8-bit data
#define DEVRW_A8D16		0x12	//  8-bit address, 16-bit data
#define DEVRW_A16D8		0x21	// 16-bit address,  8-bit data
#define DEVRW_A16D16	0x22	// 16-bit address, 16-bit data
#define DEVRW_BLOCK		0x80	// write sample ROM/RAM
#define DEVRW_MEMSIZE	0x81	// set ROM/RAM size
// chip setting DEVRW constants
#define DEVRW_VALUE		0x00
#define DEVRW_ALL		0x01

#define DEVLOG_OFF		0x00
#define DEVLOG_ERROR	0x01
#define DEVLOG_WARN		0x02
#define DEVLOG_INFO		0x03
#define DEVLOG_DEBUG	0x04
#define DEVLOG_TRACE	0x05

// Per-channel output tap (zxtune extension).
// Called once per emulated (native rate) sample with output value of each channel, before panning.
// Values are scaled so that single channel at full volume is roughly in 16-bit range.
typedef void (*DEVCB_VOICES)(void* param, UINT32 count, const INT32* values);
typedef void (*DEVFUNC_SET_VOICES_CB)(void* info, DEVCB_VOICES cb, void* param);

#define DEVVOICE_KEYON		0x01	// key is pressed/channel is enabled
#define DEVVOICE_FREQ		0x02	// freq field is valid
#define DEVVOICE_LEVEL		0x04	// level field is valid
#define DEVVOICE_NOISE		0x08	// channel produces noise, freq is noise generator rate

// chip specific voice kinds, define meaning of DEV_VOICE_STATE::fields
#define DEVVOICE_KIND_GENERIC		0x00
#define DEVVOICE_KIND_OPN_FM		0x01	// algorithm, feedback, op1..4 levels, carriers mask, pan (L=2, R=1), AMS, PMS, keys mask, block
#define DEVVOICE_KIND_OPN_DAC		0x02	// enabled, last value (unsigned 8 bit)
#define DEVVOICE_KIND_PSG_TONE		0x03	// attenuation 0..15, period low, period high, stereo (L=2, R=1)
#define DEVVOICE_KIND_PSG_NOISE		0x04	// attenuation 0..15, white, rate 0..3 (3 - tone 3), stereo
#define DEVVOICE_KIND_OPL_2OP		0x05	// connection (1 - additive), feedback, op1..2 levels, op1..2 waveforms, key, pan (L=2, R=1), block
#define DEVVOICE_KIND_OPL_4OP		0x06	// algorithm 0..3, feedback, op1..4 levels, op1..4 waveforms, key, pan
#define DEVVOICE_KIND_OPL_RHYTHM	0x07	// instrument (0 BD, 1 SD, 2 TT, 3 CY, 4 HH), level, key, enabled

// FM kinds (OPN_FM, OPL_2OP, OPL_4OP) extension of fields, all zero if not provided by core (see DEVVOICE_FM_CHIP)
// operators parameters, 8 bytes per operator in the same order as levels fields (OP1..OP4)
#define DEVVOICE_FM_OPS			16
#define DEVVOICE_FM_OP_SIZE		8
#define DEVVOICE_FM_OP_MUL		0	// multiplier 0..15
#define DEVVOICE_FM_OP_TL		1	// total level (attenuation by 0.75dB), OPN/OPM 0..127, OPL 0..63
#define DEVVOICE_FM_OP_AR		2	// attack rate, OPN/OPM 0..31, OPL 0..15
#define DEVVOICE_FM_OP_DR		3	// decay rate (D1R), OPN/OPM 0..31, OPL 0..15
#define DEVVOICE_FM_OP_SR		4	// OPN/OPM: sustain rate (D2R) 0..31 | key scale 0..3 << 5; OPL: KSL 0..3
#define DEVVOICE_FM_OP_SLRR		5	// sustain level 0..15 | release rate 0..15 << 4
#define DEVVOICE_FM_OP_FLAGS	6	// envelope stage 0..4 (off, attack, decay, sustain, release) | DEVVOICE_FM_OPF_*
#define DEVVOICE_FM_OP_EXTRA	7	// OPN: detune 0..7 | SSG-EG 0..15 << 3; OPM: DT1 0..7 | DT2 0..3 << 3; OPL: waveform 0..7
#define DEVVOICE_FM_OPF_AM		0x08	// LFO amplitude modulation (tremolo)
#define DEVVOICE_FM_OPF_VIB		0x10	// OPL vibrato
#define DEVVOICE_FM_OPF_EGT		0x20	// OPL sustained envelope (EG-TYP)
#define DEVVOICE_FM_OPF_KSR		0x40	// OPL key scale rate
// envelope generator output of operators (without TL), 0..255 as levels
#define DEVVOICE_FM_EG			48
#define DEVVOICE_FM_FNUM		52	// 2 bytes: OPN 11 bits, OPL 10 bits frequency number (block at bits 12..14); OPM key code and key fraction
#define DEVVOICE_FM_GLOBAL		54	// OPN: LFO frequency 0..7 | LFO on 0x08 | CH3 mode (1 special, 2 CSM) << 4 | DAC on 0x40;
									// OPL: rhythm 0x01 | AM depth 0x02 | VIB depth 0x04 | OPL3 0x08 | CSM 0x10 | NTS 0x20 | WSE 0x40
									// OPM: LFO waveform 0..3 | noise on 0x04
#define DEVVOICE_FM_GLOBAL2		55	// OPM: LFO frequency; OPL3: 4 operators connections (0x104)
#define DEVVOICE_FM_AMD			56	// OPM: AM depth 0..127
#define DEVVOICE_FM_PMD			57	// OPM: PM depth 0..127
#define DEVVOICE_FM_NOISE		58	// OPM: noise frequency 0..31
#define DEVVOICE_FM_CHIP		59	// see DEVVOICE_FM_CHIP_*
#define DEVVOICE_FM_EG_RATE		60	// 2 bytes: envelope generator clock in Hz
#define DEVVOICE_FM_TIMER_A		62	// 2 bytes: timer A value (CSM key on rate)
#define DEVVOICE_FM_CHIP_NONE	0
#define DEVVOICE_FM_CHIP_OPN	1	// YM2203
#define DEVVOICE_FM_CHIP_OPN2	2	// YM2612/YM3438
#define DEVVOICE_FM_CHIP_OPNA	3	// YM2608
#define DEVVOICE_FM_CHIP_OPNB	4	// YM2610/YM2610B
#define DEVVOICE_FM_CHIP_OPM	5	// YM2151
#define DEVVOICE_FM_CHIP_OPL	6	// YM3526/Y8950
#define DEVVOICE_FM_CHIP_OPL2	7	// YM3812
#define DEVVOICE_FM_CHIP_OPL3	8	// YMF262

// PCM slot of wavetable chips (YMW258/MultiPCM, SCSP), raw registers are in chip's own units (see DEVVOICE_PCM_CHIP)
#define DEVVOICE_KIND_PCM_SLOT		0x0F
#define DEVVOICE_PCM_CHIP		0	// see DEVVOICE_PCM_CHIP_*
#define DEVVOICE_PCM_STATE		1	// envelope stage 0..4 (off, attack, decay 1, decay 2, release) | DEVVOICE_PCM_ST_*
#define DEVVOICE_PCM_EG			2	// envelope level 0..255 (96dB range, 255 is maximal)
#define DEVVOICE_PCM_TONE		3	// 2 bytes: tone (sample header) number, YMW258 only
#define DEVVOICE_PCM_START		5	// 3 bytes: start address of sample in bytes
#define DEVVOICE_PCM_LOOP		8	// 2 bytes: loop start in samples
#define DEVVOICE_PCM_END		10	// 2 bytes: loop end (sample length) in samples
#define DEVVOICE_PCM_POS		12	// 2 bytes: current position in samples
#define DEVVOICE_PCM_FORMAT		14	// bits 0..1: 0 8 bit, 1 12 bit, 2 16 bit; bits 2..3: loop mode (0 none, 1 forward,
									// 2 reverse, 3 ping-pong); bits 4..5: source (0 memory, 1 noise, 2 silence)
#define DEVVOICE_PCM_OCT		15	// octave, signed
#define DEVVOICE_PCM_FNUM		16	// 2 bytes: 10 bits F-number
#define DEVVOICE_PCM_TL			18	// total level, YMW258 0..127 (0.375dB step), SCSP 0..255 (bits are 0.4..48dB)
#define DEVVOICE_PCM_PAN		19	// YMW258 0..15 (0 center, 1..7 right louder, 9..15 left louder, 8 muted)
									// SCSP DIPAN 0..31 (0..15 right attenuated, 16..31 left attenuated)
#define DEVVOICE_PCM_SEND		20	// direct send level 0..7 (SCSP DISDL, 7 for YMW258)
#define DEVVOICE_PCM_AR			21	// envelope registers: AR, D1R, DL, D2R, RR, KRS (YMW258 0..15, SCSP rates and DL 0..31)
#define DEVVOICE_PCM_D1R		22
#define DEVVOICE_PCM_DL			23
#define DEVVOICE_PCM_D2R		24
#define DEVVOICE_PCM_RR			25
#define DEVVOICE_PCM_KRS		26
#define DEVVOICE_PCM_RATES		27	// 4 bytes: effective (key scaled) rates 0..63 of AR, D1R, D2R, RR, 0 is infinite
#define DEVVOICE_PCM_LFOF		31	// LFO frequency, YMW258 0..7, SCSP 0..31
#define DEVVOICE_PCM_PLFO		32	// pitch LFO depth 0..7 | waveform 0..3 << 4 (SCSP)
#define DEVVOICE_PCM_ALFO		33	// amplitude LFO depth 0..7 | waveform 0..3 << 4 (SCSP)
#define DEVVOICE_PCM_MODE		34	// DEVVOICE_PCM_MODE_* flags
#define DEVVOICE_PCM_MDL		35	// SCSP modulation level 0..15 (0 none, 5..15 is +-pi/16..+-64pi)
#define DEVVOICE_PCM_MDXSL		36	// SCSP modulation sources 0..63 (ring buffer offsets, i.e. slot + N)
#define DEVVOICE_PCM_MDYSL		37
#define DEVVOICE_PCM_ISEL		38	// SCSP DSP input mixer register 0..15
#define DEVVOICE_PCM_IMXL		39	// SCSP DSP send level 0..7
#define DEVVOICE_PCM_EFSDL		40	// SCSP effect return level 0..7 of DSP output with slot's number (slots 0..17)
#define DEVVOICE_PCM_EFPAN		41	// SCSP effect return pan 0..31
#define DEVVOICE_PCM_SBCTL		42	// SCSP sample bits inversion 0..3
#define DEVVOICE_PCM_CLOCK		48	// 4 bytes: chip clock in Hz (same in all slots)
#define DEVVOICE_PCM_RATE		52	// 2 bytes: output (and envelope) sample rate in Hz
#define DEVVOICE_PCM_MVOL		54	// SCSP master volume 0..15 (15 for YMW258)
#define DEVVOICE_PCM_GLOBAL		55	// DEVVOICE_PCM_GL_* flags
#define DEVVOICE_PCM_BANK0		56	// 2 bytes: YMW258 Sega banking low bank address >> 16
#define DEVVOICE_PCM_BANK1		58	// 2 bytes: YMW258 Sega banking high bank address >> 16
#define DEVVOICE_PCM_ACTIVE		60	// slots playing now (same in all slots)
#define DEVVOICE_PCM_CHIP_YMW258	1
#define DEVVOICE_PCM_CHIP_SCSP		2
#define DEVVOICE_PCM_ST_KEY		0x08	// key is on (not released)
#define DEVVOICE_PCM_ST_PLAYING	0x10	// slot is generating samples
#define DEVVOICE_PCM_ST_REVERSE	0x20	// sample is played backwards
#define DEVVOICE_PCM_ST_LOOPED	0x40	// position is past loop start
#define DEVVOICE_PCM_MODE_FM		0x01	// SCSP: phase is modulated by ring buffer (MDL/MDXSL/MDYSL)
#define DEVVOICE_PCM_MODE_NOTRING	0x02	// SCSP STWINH: output is not written to ring buffer
#define DEVVOICE_PCM_MODE_DIRECT	0x04	// SCSP SDIR: envelope, LFO and TL are bypassed
#define DEVVOICE_PCM_MODE_EGHOLD	0x08	// SCSP: attack holds maximal level
#define DEVVOICE_PCM_MODE_LPSLNK	0x10	// SCSP: decay starts at loop start
#define DEVVOICE_PCM_MODE_INTERP	0x20	// YMW258: TL is interpolated
#define DEVVOICE_PCM_GL_DSP		0x01	// SCSP effects DSP is emulated (not bypassed)
#define DEVVOICE_PCM_GL_DAC18B	0x02	// SCSP 18 bit DAC output
#define DEVVOICE_PCM_GL_MEM4MB	0x04	// SCSP 4 Mbit memory
#define DEVVOICE_PCM_GL_BANKING	0x10	// YMW258 Sega banking is enabled

typedef struct _device_voice_state
{
	float freq;		// Hz
	float level;	// dB relative to maximal channel volume (0 or negative)
	UINT8 flags;	// see DEVVOICE_ constants
	UINT8 kind;		// see DEVVOICE_KIND_ constants
	UINT8 fields[64];	// operator levels are 0..255, 255 is maximal
} DEV_VOICE_STATE;
// fill state of up to count channels, returns channels count
typedef UINT32 (*DEVFUNC_GET_VOICES_STATE)(void* info, UINT32 count, DEV_VOICE_STATE* states);

typedef struct _devdef_readwrite_function
{
	UINT8 funcType;	// function type, see RWF_ constants
	UINT8 rwType;	// read/write function type, see DEVRW_ constants
	UINT16 user;	// user-defined value
	void* funcPtr;
} DEVDEF_RWFUNC;

struct _device_core_definition
{
	const char* name;	// name of the device
	const char* author;	// author/origin of emulation
	UINT32 coreID;		// 4-character identifier ID to distinguish between
						// multiple emulators of a device
	
	DEVFUNC_START Start;
	DEVFUNC_CTRL Stop;
	DEVFUNC_CTRL Reset;
	DEVFUNC_UPDATE Update;
	
	DEVFUNC_OPTMASK SetOptionBits;
	DEVFUNC_OPTMASK SetMuteMask;
	DEVFUNC_PANALL SetPanning;		// **NOTE: deprecated, moved to rwFuncs**
	DEVFUNC_SRCCB SetSRateChgCB;	// used to set callback function for realtime sample rate changes
	DEVFUNC_SETLOGCB SetLogCB;		// set callback for logging
	DEVFUNC_LINKDEV LinkDevice;		// used to link multiple devices together
	
	const DEVDEF_RWFUNC* rwFuncs;	// terminated by (funcPtr == NULL)
};	// DEV_DEF
struct _device_declaration
{
	DEV_ID deviceID;				// device ID (DEVID_ constant), for device enumeration
	DEVDECLFUNC_NAME name;			// return name of the device
	DEVDECLFUNC_CHNCOUNT channelCount;	// return number of channels (for muting / panning)
	DEVDECLFUNC_CHNNAMES channelNames;	// return list of names for each channel or NULL (no special channel names)
	DEVDECLFUNC_LINKIDS linkDevIDs;	// return list of device IDs that the sound cores will likely expect to be linked or NULL (no linked devices)
	const DEV_DEF* cores[];			// list of supported sound cores, terminated by NULL pointer
};	// DEV_DECL
struct _device_info
{
	DEV_DATA* dataPtr;		// points to chip data structure
	UINT32 sampleRate;		// sample rate of the Update() function
	const DEV_DEF* devDef;	// points to device definition
	const DEV_DECL* devDecl;	// points to device declaration (will be NULL when calling DEV_DEV::Start() directly)
	
	UINT32 linkDevCount;	// number of link-able devices
	DEVLINK_INFO* linkDevs;	// [freed by caller]
};	// DEV_INFO

struct _device_link_ids
{
	UINT32 devCount;	// number of link-able devices
	DEV_ID devIDs[];	// list of device IDs that can be expected to be linked
};	// DEVLINK_IDS
struct _device_link_info
{
	DEV_ID devID;		// device ID (DEVID_ constant)
	UINT8 linkID;		// device link ID
	DEV_GEN_CFG* cfg;	// pointer to DEV_GEN_CFG structures and derivates [freed by caller]
};	// DEVLINK_INFO


// device resampling info constants
#define DEVRI_SRMODE_NATIVE		0x00
#define DEVRI_SRMODE_CUSTOM		0x01
#define DEVRI_SRMODE_HIGHEST	0x02
struct _device_generic_config
{
	UINT32 emuCore;		// emulation core (4-character code, 0 = default)
	UINT8 srMode;		// sample rate mode
	
	UINT8 flags;		// chip flags
	UINT32 clock;		// chip clock
	UINT32 smplRate;	// sample rate for SRMODE_CUSTOM/DEVRI_SRMODE_HIGHEST
						// Note: Some cores ignore the srMode setting and always use smplRate.
};	// DEV_GEN_CFG

#ifdef __cplusplus
}
#endif

#endif	// __EMUSTRUCTS_H__
