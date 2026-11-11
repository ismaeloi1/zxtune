// zxtune: libvgm devices on ymfm cores (https://github.com/aaronsgiles/ymfm, revision 81aec25, BSD-3-Clause)
// Chips are clocked at their native sample rate, SSG parts are forwarded to the linked AY8910 device (as MAME core
// of libvgm does), per-channel outputs are tapped before the DAC (see RWF_VOICES).

#include <stdlib.h>
#include <string.h>
#include <math.h>

#include <memory>
#include <vector>

#include "../../stdtype.h"
#include "../EmuStructs.h"
#include "../SoundDevs.h"
#include "../EmuCores.h"
#include "../EmuHelper.h"
#include "../SoundEmu.h"
#include "../snddef.h"

#include "ymfmintf.h"
#include "2612intf.h"
#include "ayintf.h"	// for AY8910_CFG
#include "ymfmintf_device.h"

// engine templates are instantiated by their users, so chips implementations are compiled here
#include "ymfm/ymfm_opn.cpp"

namespace ymfmintf
{

#include "fmopn_2608rom.h"


// Output levels match MAME cores of libvgm, so chips are balanced with others (e.g. SSG) by VGM mixing volumes

class OPN : public Device, public ymfm::ym2203
{
public:
	explicit OPN(UINT32 clock)
		: Device(clock, 3)
		, ymfm::ym2203(static_cast<ymfm::ymfm_interface&>(*this))
	{
		ymfm::ym2203::ssg_override(*this);
		Rate = GetRate();
	}

	void Reset() override
	{
		ResetTimers();
		ymfm::ym2203::reset();
		memset(KeyOn, 0, sizeof(KeyOn));
	}

	void Write(UINT8 port, UINT8 data) override
	{
		MirrorWrite(port & 1, data);
		if ((port & 1) && m_address == 0x28)
			TrackOpnKeyOn(data, 3);
		ymfm::ym2203::write(port & 1, data);
	}

	UINT8 Read(UINT8 port) override
	{
		return ymfm::ym2203::read(port & 1);
	}

	UINT32 GetRate() const override
	{
		return Clock / (m_fm.clock_prescale() * 12);
	}

	void Generate(INT32* out, bool voices) override
	{
		m_fm.clock(fm_engine::ALL_CHANNELS);
		ymfm::ymfm_output<1> sum;
		sum.clear();
		for (UINT32 ch = 0; ch < 3; ch ++)
		{
			ymfm::ymfm_output<1> chan;
			// OPN is full 14-bit with no intermediate clipping
			if (voices || !((MuteMask >> ch) & 1))
				m_fm.output(chan.clear(), 0, 32767, 1 << ch);
			else
				chan.clear();
			if ((MuteMask >> ch) & 1)
				chan.clear();
			// mono output is put to both sides
			if (voices)
				Voices[ch] = chan.data[0] * OUTPUT_GAIN * TAP_GAIN;
			sum.data[0] += chan.data[0];
		}
		// 10.3 floating point DAC (YM3014)
		sum.roundtrip_fp();
		out[0] = out[1] = sum.data[0] * OUTPUT_GAIN / 2;
	}

	UINT32 GetVoicesState(UINT32 count, DEV_VOICE_STATE* states) override
	{
		for (UINT32 ch = 0; ch < 3 && ch < count; ch ++)
			FillFmVoice(m_fm, ch, DEVVOICE_FM_CHIP_OPN, GetRate(), &states[ch]);
		return 3;
	}

protected:
	UINT32 GetSsgClock() const override
	{
		return ssg_effective_clock(Clock);
	}

private:
	static const INT32 OUTPUT_GAIN = 2;
	static const INT32 TAP_GAIN = 2;
};

// YM2608/YM2610: 6 FM channels, 6 ADPCM-A, 1 ADPCM-B
template<class Chip>
class OPNAB : public Device, public Chip
{
public:
	OPNAB(UINT32 clock, UINT8 chipType, UINT8 fmMask)
		: Device(clock, 13)
		, Chip(static_cast<ymfm::ymfm_interface&>(*this))
		, ChipType(chipType)
		, FmMask(fmMask)
	{
		Chip::ssg_override(*this);
		Rate = GetRate();
	}

	void Reset() override
	{
		ResetTimers();
		Chip::reset();
		memset(KeyOn, 0, sizeof(KeyOn));
	}

	void Write(UINT8 port, UINT8 data) override
	{
		MirrorWrite(port & 3, data);
		if (port == 1 && Latch[0] == 0x28)
			TrackOpnKeyOn(data, 6);
		Chip::write(port & 3, data);
	}

	UINT8 Read(UINT8 port) override
	{
		return Chip::read(port & 3);
	}

	UINT32 GetRate() const override
	{
		return Clock / (this->m_fm.clock_prescale() * 24);
	}

	void Generate(INT32* out, bool voices) override
	{
		using fm_engine = typename Chip::fm_engine;
		// top bit of the IRQ enable flags of YM2608 controls 3-channel vs 6-channel mode
		const UINT32 fmMask = ActiveFmMask() & ~MuteMask & 0x3f;
		const UINT32 env = this->m_fm.clock(fm_engine::ALL_CHANNELS);
		// ADPCM-A is clocked on every envelope cycle, YM2608 channels 4 and 5 every 2 cycles
		if (ymfm::bitfield(env, 0, 2) == 0)
			this->m_adpcm_a.clock(ChipType == DEVVOICE_FM_CHIP_OPNA && ymfm::bitfield(env, 2) ? 0x0f : 0x3f);
		this->m_adpcm_b.clock();
		ymfm::ymfm_output<2> sum;
		sum.clear();
		if (voices)
		{
			for (UINT32 ch = 0; ch < 6; ch ++)
			{
				ymfm::ymfm_output<2> chan;
				chan.clear();
				// OPNA and OPNB are 13-bit with no intermediate clipping
				if ((fmMask >> ch) & 1)
					this->m_fm.output(chan, 1, 32767, 1 << ch);
				Voices[ch] = Sum(chan) * OUTPUT_GAIN * TAP_GAIN;
				sum.data[0] += chan.data[0];
				sum.data[1] += chan.data[1];
			}
			for (UINT32 ch = 0; ch < 6; ch ++)
			{
				ymfm::ymfm_output<2> chan;
				chan.clear();
				if (!((MuteMask >> (6 + ch)) & 1))
					this->m_adpcm_a.output(chan, 1 << ch);
				Voices[6 + ch] = Sum(chan) * OUTPUT_GAIN * TAP_GAIN;
				sum.data[0] += chan.data[0];
				sum.data[1] += chan.data[1];
			}
			ymfm::ymfm_output<2> chan;
			chan.clear();
			if (!((MuteMask >> 12) & 1))
				this->m_adpcm_b.output(chan, 1);
			Voices[12] = Sum(chan) * OUTPUT_GAIN * TAP_GAIN;
			sum.data[0] += chan.data[0];
			sum.data[1] += chan.data[1];
			// ADPCM channels are active while they produce samples
			for (UINT32 ch = 6; ch < 13; ch ++)
			{
				if (Voices[ch] != 0)
					Active[ch - 6] = GetRate() / 20;
				else if (Active[ch - 6])
					--Active[ch - 6];
			}
		}
		else
		{
			this->m_fm.output(sum, 1, 32767, fmMask);
			this->m_adpcm_a.output(sum, ~(MuteMask >> 6) & 0x3f);
			if (!((MuteMask >> 12) & 1))
				this->m_adpcm_b.output(sum, 1);
		}
		sum.clamp16();
		out[0] = sum.data[0] * OUTPUT_GAIN;
		out[1] = sum.data[1] * OUTPUT_GAIN;
	}

	UINT32 GetVoicesState(UINT32 count, DEV_VOICE_STATE* states) override
	{
		const double rate = GetRate();
		for (UINT32 ch = 0; ch < 13 && ch < count; ch ++)
		{
			if (ch < 6 && ((FmMask >> ch) & 1))
			{
				FillFmVoice(this->m_fm, ch, ChipType, rate, &states[ch]);
			}
			else if (ch >= 6)
			{
				// ADPCM: audio only
				ClearVoice(&states[ch]);
				const bool key = Active[ch - 6] != 0;
				states[ch].flags = key ? DEVVOICE_KEYON : 0;
				states[ch].level = key ? 0.0f : -96.0f;
			}
			else
			{
				ClearVoice(&states[ch]);
			}
		}
		return 13;
	}

protected:
	virtual UINT32 ActiveFmMask() const
	{
		return FmMask;
	}

private:
	static const INT32 OUTPUT_GAIN = 2;
	static const INT32 TAP_GAIN = 1;
	const UINT8 ChipType;
	const UINT8 FmMask;
	// samples to keep ADPCM channels active after the last non-zero output
	UINT32 Active[7] = {0};

protected:
	UINT32 GetSsgClock() const override
	{
		return this->ssg_effective_clock(Clock);
	}
};

class OPNA : public OPNAB<ymfm::ym2608>
{
public:
	explicit OPNA(UINT32 clock)
		: OPNAB<ymfm::ym2608>(clock, DEVVOICE_FM_CHIP_OPNA, 0x3f)
	{}

protected:
	UINT32 ActiveFmMask() const override
	{
		// top bit of the IRQ enable register switches 3/6 channels mode
		return ymfm::bitfield(m_irq_enable, 7) ? 0x3f : 0x07;
	}

	// rhythm samples are in internal ROM
	uint8_t ReadAdpcmA(uint32_t address) const override
	{
		return address < sizeof(YM2608_ADPCM_ROM) ? YM2608_ADPCM_ROM[address] : 0;
	}
};

// YM2610 has no FM channels 1 and 4, YM2610B has all of them
template<class Chip>
class OPNB : public OPNAB<Chip>
{
public:
	OPNB(UINT32 clock, UINT8 fmMask)
		: OPNAB<Chip>(clock, DEVVOICE_FM_CHIP_OPNB, fmMask)
	{}

	UINT32 GetRate() const override
	{
		// fixed prescaler
		return this->Clock / 144;
	}
};

class OPN2 : public Device, public ymfm::ym2612
{
public:
	explicit OPN2(UINT32 clock)
		: Device(clock, 7)
		, ymfm::ym2612(static_cast<ymfm::ymfm_interface&>(*this))
	{
		Rate = GetRate();
	}

	void Reset() override
	{
		ResetTimers();
		ymfm::ym2612::reset();
		memset(KeyOn, 0, sizeof(KeyOn));
	}

	void Write(UINT8 port, UINT8 data) override
	{
		MirrorWrite(port & 3, data);
		if (port == 1 && Latch[0] == 0x28)
			TrackOpnKeyOn(data, 6);
		ymfm::ym2612::write(port & 3, data);
	}

	UINT8 Read(UINT8 port) override
	{
		return ymfm::ym2612::read(port & 3);
	}

	UINT32 GetRate() const override
	{
		return Clock / 144;
	}

	void Generate(INT32* out, bool voices) override
	{
		const bool ymf276 = (Options & OPT_YM2612_TYPE_YMF276) != 0;
		const bool ladder = !ymf276 && ((Options >> 4) & 3) != 1 && !(Options & OPT_YM2612_NO_LADDER);
		m_fm.clock(fm_engine::ALL_CHANNELS);
		INT32 sum[2] = {0, 0};
		for (UINT32 ch = 0; ch < 7; ch ++)
		{
			ymfm::ymfm_output<2> chan;
			const bool muted = (MuteMask >> ch) & 1;
			if (ch < 6 && (ch < 5 || !m_dac_enable))
			{
				// YMF276 is 14-bit, OPN2 9-bit with intermediate clipping
				if (ymf276)
					m_fm.output(chan.clear(), 0, 8191, 1 << ch);
				else
					m_fm.output(chan.clear(), 5, 256, 1 << ch);
			}
			else if (ch == 6 && m_dac_enable)
			{
				// DAC replaces channel 6
				const INT32 dac = (int16_t(m_dac_data << 7) >> 7) * (ymf276 ? 32 : 1);
				chan.data[0] = m_fm.regs().ch_output_0(0x102) ? dac : 0;
				chan.data[1] = m_fm.regs().ch_output_1(0x102) ? dac : 0;
			}
			else
			{
				// silent output of multiplexed DAC
				chan.clear();
				if (ch == 6 || !ladder)
				{
					if (voices)
						Voices[ch] = 0;
					continue;
				}
			}
			if (muted)
				chan.clear();
			if (ladder)
			{
				// YM2612 DAC discontinuity of each channel (even silent one), see ymfm::ym2612::generate
				chan.data[0] = dac_discontinuity(chan.data[0]);
				chan.data[1] = dac_discontinuity(chan.data[1]);
			}
			sum[0] += chan.data[0];
			sum[1] += chan.data[1];
			if (voices)
				Voices[ch] = Scale(chan.data[0] + chan.data[1], ymf276, ladder) * TAP_GAIN;
		}
		out[0] = Scale(sum[0], ymf276, ladder);
		out[1] = Scale(sum[1], ymf276, ladder);
	}

	UINT32 GetVoicesState(UINT32 count, DEV_VOICE_STATE* states) override
	{
		for (UINT32 ch = 0; ch < 6 && ch < count; ch ++)
		{
			FillFmVoice(m_fm, ch, DEVVOICE_FM_CHIP_OPN2, GetRate(), &states[ch]);
			if (ch == 5 && m_dac_enable)
			{
				states[ch].flags = 0;
				states[ch].level = -96.0f;
			}
		}
		if (count > 6)
		{
			// DAC: audio only
			ClearVoice(&states[6]);
			states[6].level = 0.0f;
			states[6].flags = m_dac_enable ? DEVVOICE_KEYON : 0;
			states[6].kind = DEVVOICE_KIND_OPN_DAC;
			states[6].fields[0] = m_dac_enable;
			states[6].fields[1] = (UINT8)(((m_dac_data >> 1) & 0xff) ^ 0x80);
		}
		return 7;
	}

protected:
	bool DacEnabled() const override
	{
		return m_dac_enable != 0;
	}

private:
	// Multiplexed output is averaged over the 6 channels (with 64/65 factor of discontinuity), see
	// ymfm::ym2612::generate; levels of the other modes are the same as of YM2612
	static INT32 Scale(INT32 val, bool ymf276, bool ladder)
	{
		if (ymf276)
			return val * 128 * OUTPUT_GAIN_NUM / (6 * 32 * OUTPUT_GAIN_DEN);
		return ladder ? val * 128 * 64 * OUTPUT_GAIN_NUM / (6 * 65 * OUTPUT_GAIN_DEN)
			: val * 128 * OUTPUT_GAIN_NUM / (6 * OUTPUT_GAIN_DEN);
	}

	static const INT32 OUTPUT_GAIN_NUM = 3;
	static const INT32 OUTPUT_GAIN_DEN = 2;
	static const INT32 TAP_GAIN = 2;
};

// libvgm device data starts with DEV_DATA
struct Holder
{
	DEV_DATA _devData;
	Device* Impl;
};

static Device* Get(void* info)
{
	return static_cast<Holder*>(info)->Impl;
}

static void Stop(void* info)
{
	Holder* holder = static_cast<Holder*>(info);
	delete holder->Impl;
	delete holder;
}

static void Reset(void* info)
{
	Get(info)->Reset();
}

static void Update(void* info, UINT32 samples, DEV_SMPL** outputs)
{
	Get(info)->Update(samples, outputs);
}

static void SetOptions(void* info, UINT32 options)
{
	Get(info)->SetOptions(options);
}

static void SetMuteMask(void* info, UINT32 mask)
{
	Get(info)->SetMuteMask(mask);
}

static void SetRateCallback(void* info, DEVCB_SRATE_CHG cb, void* param)
{
	Get(info)->SetRateCallback(cb, param);
}

static void Write(void* info, UINT8 port, UINT8 data)
{
	Get(info)->Write(port, data);
}

static UINT8 Read(void* info, UINT8 port)
{
	return Get(info)->Read(port);
}

static void SetVoicesCallback(void* info, DEVCB_VOICES cb, void* param)
{
	Get(info)->SetVoicesCallback(cb, param);
}

static UINT32 GetVoicesState(void* info, UINT32 count, DEV_VOICE_STATE* states)
{
	return Get(info)->GetVoicesState(count, states);
}

static void WriteMemoryA(void* info, UINT32 offset, UINT32 length, const UINT8* data)
{
	Get(info)->WriteMemory('A', offset, length, data);
}

static void AllocMemoryA(void* info, UINT32 size)
{
	Get(info)->AllocMemory('A', size);
}

static void WriteMemoryB(void* info, UINT32 offset, UINT32 length, const UINT8* data)
{
	Get(info)->WriteMemory('B', offset, length, data);
}

static void AllocMemoryB(void* info, UINT32 size)
{
	Get(info)->AllocMemory('B', size);
}

#define LINKDEV_SSG	0x00

static UINT8 LinkSsg(void* info, UINT8 linkID, const DEV_INFO* devInfLink)
{
	ssg_callbacks funcs;
	if (linkID != LINKDEV_SSG)
		return EERR_UNK_DEVICE;
	if (devInfLink == NULL)
	{
		Get(info)->LinkSsg(NULL, NULL);
		return 0x00;
	}
	const DEV_DEF* def = devInfLink->devDef;
	if (SndEmu_GetDeviceFunc(def, RWF_REGISTER | RWF_WRITE, DEVRW_A8D8, 0, (void**)&funcs.write)
		|| SndEmu_GetDeviceFunc(def, RWF_REGISTER | RWF_READ, DEVRW_A8D8, 0, (void**)&funcs.read)
		|| SndEmu_GetDeviceFunc(def, RWF_CLOCK | RWF_WRITE, DEVRW_VALUE, 0, (void**)&funcs.set_clock)
		|| def->Reset == NULL)
		return 0xFF;
	funcs.reset = def->Reset;
	Get(info)->LinkSsg(&funcs, devInfLink->dataPtr);
	return 0x00;
}

// SSG of OPN chips is AY8910 compatible device with own clock divider, see opnintf.c
static void InitSsgLink(DEV_INFO* devInf, const DEV_GEN_CFG* cfg, UINT32 clockDiv, UINT8 ssgType)
{
	AY8910_CFG* ssgCfg = (AY8910_CFG*)calloc(1, sizeof(AY8910_CFG));
	ssgCfg->_genCfg = *cfg;
	ssgCfg->_genCfg.clock = cfg->clock / clockDiv / 2;
	ssgCfg->_genCfg.flags = 0x00;
	ssgCfg->_genCfg.emuCore = 0;
	ssgCfg->chipType = ssgType;
	ssgCfg->chipFlags = YM2149_PIN26_HIGH;

	devInf->linkDevCount = 1;
	devInf->linkDevs = (DEVLINK_INFO*)calloc(1, sizeof(DEVLINK_INFO));
	devInf->linkDevs[0].devID = DEVID_AY8910;
	devInf->linkDevs[0].linkID = LINKDEV_SSG;
	devInf->linkDevs[0].cfg = (DEV_GEN_CFG*)ssgCfg;
}

static UINT8 StartDevice(Device* impl, const DEV_DEF* def, DEV_INFO* retDevInf)
{
	Holder* holder = new Holder;
	holder->_devData.chipInf = holder;
	holder->Impl = impl;
	impl->Reset();
	INIT_DEVINF(retDevInf, &holder->_devData, impl->GetRate(), def);
	return 0x00;
}

static UINT8 StartYM2203(const DEV_GEN_CFG* cfg, DEV_INFO* retDevInf);
static UINT8 StartYM2608(const DEV_GEN_CFG* cfg, DEV_INFO* retDevInf);
static UINT8 StartYM2610(const DEV_GEN_CFG* cfg, DEV_INFO* retDevInf);
static UINT8 StartYM2612(const DEV_GEN_CFG* cfg, DEV_INFO* retDevInf);
static UINT8 StartYM2151(const DEV_GEN_CFG* cfg, DEV_INFO* retDevInf);

static const DEVDEF_RWFUNC FUNCS_OPN[] =
{
	{RWF_REGISTER | RWF_WRITE, DEVRW_A8D8, 0, (void*)Write},
	{RWF_REGISTER | RWF_READ, DEVRW_A8D8, 0, (void*)Read},
	{RWF_CHN_MUTE | RWF_WRITE, DEVRW_ALL, 0, (void*)SetMuteMask},
	{RWF_VOICES | RWF_WRITE, DEVRW_ALL, 0, (void*)SetVoicesCallback},
	{RWF_VOICES | RWF_READ, DEVRW_ALL, 0, (void*)GetVoicesState},
	{0x00, 0x00, 0, NULL}
};

static const DEVDEF_RWFUNC FUNCS_OPNA[] =
{
	{RWF_REGISTER | RWF_WRITE, DEVRW_A8D8, 0, (void*)Write},
	{RWF_REGISTER | RWF_READ, DEVRW_A8D8, 0, (void*)Read},
	{RWF_MEMORY | RWF_WRITE, DEVRW_BLOCK, 'B', (void*)WriteMemoryB},
	{RWF_MEMORY | RWF_WRITE, DEVRW_MEMSIZE, 'B', (void*)AllocMemoryB},
	{RWF_CHN_MUTE | RWF_WRITE, DEVRW_ALL, 0, (void*)SetMuteMask},
	{RWF_VOICES | RWF_WRITE, DEVRW_ALL, 0, (void*)SetVoicesCallback},
	{RWF_VOICES | RWF_READ, DEVRW_ALL, 0, (void*)GetVoicesState},
	{0x00, 0x00, 0, NULL}
};

static const DEVDEF_RWFUNC FUNCS_OPNB[] =
{
	{RWF_REGISTER | RWF_WRITE, DEVRW_A8D8, 0, (void*)Write},
	{RWF_REGISTER | RWF_READ, DEVRW_A8D8, 0, (void*)Read},
	{RWF_MEMORY | RWF_WRITE, DEVRW_BLOCK, 'A', (void*)WriteMemoryA},
	{RWF_MEMORY | RWF_WRITE, DEVRW_MEMSIZE, 'A', (void*)AllocMemoryA},
	{RWF_MEMORY | RWF_WRITE, DEVRW_BLOCK, 'B', (void*)WriteMemoryB},
	{RWF_MEMORY | RWF_WRITE, DEVRW_MEMSIZE, 'B', (void*)AllocMemoryB},
	{RWF_CHN_MUTE | RWF_WRITE, DEVRW_ALL, 0, (void*)SetMuteMask},
	{RWF_VOICES | RWF_WRITE, DEVRW_ALL, 0, (void*)SetVoicesCallback},
	{RWF_VOICES | RWF_READ, DEVRW_ALL, 0, (void*)GetVoicesState},
	{0x00, 0x00, 0, NULL}
};

}	// namespace ymfmintf

extern "C"
{

const DEV_DEF devDef_YM2203_YMFM =
{
	"YM2203", "ymfm", FCC_YMFM,
	ymfmintf::StartYM2203, ymfmintf::Stop, ymfmintf::Reset, ymfmintf::Update,
	NULL, ymfmintf::SetMuteMask, NULL, ymfmintf::SetRateCallback, NULL, ymfmintf::LinkSsg,
	ymfmintf::FUNCS_OPN,
};

const DEV_DEF devDef_YM2608_YMFM =
{
	"YM2608", "ymfm", FCC_YMFM,
	ymfmintf::StartYM2608, ymfmintf::Stop, ymfmintf::Reset, ymfmintf::Update,
	NULL, ymfmintf::SetMuteMask, NULL, ymfmintf::SetRateCallback, NULL, ymfmintf::LinkSsg,
	ymfmintf::FUNCS_OPNA,
};

const DEV_DEF devDef_YM2610_YMFM =
{
	"YM2610", "ymfm", FCC_YMFM,
	ymfmintf::StartYM2610, ymfmintf::Stop, ymfmintf::Reset, ymfmintf::Update,
	NULL, ymfmintf::SetMuteMask, NULL, NULL, NULL, ymfmintf::LinkSsg,
	ymfmintf::FUNCS_OPNB,
};

const DEV_DEF devDef_YM2612_YMFM =
{
	"YM2612", "ymfm", FCC_YMFM,
	ymfmintf::StartYM2612, ymfmintf::Stop, ymfmintf::Reset, ymfmintf::Update,
	ymfmintf::SetOptions, ymfmintf::SetMuteMask, NULL, NULL, NULL, NULL,
	ymfmintf::FUNCS_OPN,
};

const DEV_DEF devDef_YM2151_YMFM =
{
	"YM2151", "ymfm", FCC_YMFM,
	ymfmintf::StartYM2151, ymfmintf::Stop, ymfmintf::Reset, ymfmintf::Update,
	NULL, ymfmintf::SetMuteMask, NULL, NULL, NULL, NULL,
	ymfmintf::FUNCS_OPN,
};

}	// extern "C"

namespace ymfmintf
{

static UINT8 StartYM2203(const DEV_GEN_CFG* cfg, DEV_INFO* retDevInf)
{
	StartDevice(new OPN(cfg->clock), &devDef_YM2203_YMFM, retDevInf);
	InitSsgLink(retDevInf, cfg, 1, AYTYPE_YM2203);
	return 0x00;
}

static UINT8 StartYM2608(const DEV_GEN_CFG* cfg, DEV_INFO* retDevInf)
{
	StartDevice(new OPNA(cfg->clock), &devDef_YM2608_YMFM, retDevInf);
	InitSsgLink(retDevInf, cfg, 2, AYTYPE_YM2608);
	return 0x00;
}

static UINT8 StartYM2610(const DEV_GEN_CFG* cfg, DEV_INFO* retDevInf)
{
	if (cfg->flags)
		StartDevice(new OPNB<ymfm::ym2610b>(cfg->clock, 0x3f), &devDef_YM2610_YMFM, retDevInf);
	else
		StartDevice(new OPNB<ymfm::ym2610>(cfg->clock, 0x36), &devDef_YM2610_YMFM, retDevInf);
	InitSsgLink(retDevInf, cfg, 2, AYTYPE_YM2610);
	return 0x00;
}

static UINT8 StartYM2612(const DEV_GEN_CFG* cfg, DEV_INFO* retDevInf)
{
	return StartDevice(new OPN2(cfg->clock), &devDef_YM2612_YMFM, retDevInf);
}

static UINT8 StartYM2151(const DEV_GEN_CFG* cfg, DEV_INFO* retDevInf)
{
	return StartDevice(CreateOPM(cfg->clock), &devDef_YM2151_YMFM, retDevInf);
}

}	// namespace ymfmintf
