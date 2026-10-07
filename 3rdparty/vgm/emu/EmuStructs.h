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

typedef struct _device_voice_state
{
	float freq;		// Hz
	float level;	// dB relative to maximal channel volume (0 or negative)
	UINT8 flags;	// see DEVVOICE_ constants
	UINT8 kind;		// see DEVVOICE_KIND_ constants
	UINT8 fields[12];	// operator levels are 0..255, 255 is maximal
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
