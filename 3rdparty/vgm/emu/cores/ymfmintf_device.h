#ifndef __YMFMINTF_DEVICE_H__
#define __YMFMINTF_DEVICE_H__

// zxtune: common part of libvgm devices on ymfm cores, see ymfmintf.cpp

#include <string.h>
#include <math.h>

#include <vector>

#include "../../stdtype.h"
#include "../EmuStructs.h"
#include "fmopn.h"	// for ssg_callbacks

#include "ymfm/ymfm.h"
#include "ymfm/ymfm_ssg.h"

namespace ymfmintf
{

// carriers per algorithm in operators order (OP1..OP4), as DEVVOICE_KIND_OPN_FM requires
static const UINT8 CARRIERS[8] = {0x08, 0x08, 0x08, 0x08, 0x0a, 0x0e, 0x0e, 0x0f};

static UINT8 LevelField(UINT32 att)
{
	if (att > 0x3ff)
		att = 0x3ff;
	return (UINT8)(255 - att * 255 / 0x3ff);
}

class Device : public ymfm::ymfm_interface, public ymfm::ssg_override
{
public:
	Device(UINT32 clock, UINT32 voices)
		: Clock(clock)
		, VoicesCount(voices)
		, Voices(voices)
	{
		memset(Regs, 0, sizeof(Regs));
		ResetTimers();
	}

	virtual ~Device() = default;

	virtual void Reset() = 0;
	virtual void Write(UINT8 port, UINT8 data) = 0;
	virtual UINT8 Read(UINT8 port) = 0;
	// one native sample to out[0..1], channels outputs to Voices if requested
	virtual void Generate(INT32* out, bool voices) = 0;
	virtual UINT32 GetVoicesState(UINT32 count, DEV_VOICE_STATE* states) = 0;
	virtual UINT32 GetRate() const = 0;

	void Update(UINT32 samples, DEV_SMPL** outputs)
	{
		for (UINT32 idx = 0; idx < samples; idx ++)
		{
			INT32 out[2];
			Generate(out, VoicesCb != NULL);
			outputs[0][idx] = out[0];
			outputs[1][idx] = out[1];
			if (VoicesCb)
				VoicesCb(VoicesParam, VoicesCount, Voices.data());
			ClockTimers();
		}
	}

	void SetMuteMask(UINT32 mask)
	{
		MuteMask = mask;
	}

	void SetVoicesCallback(DEVCB_VOICES cb, void* param)
	{
		VoicesCb = cb;
		VoicesParam = param;
	}

	void SetRateCallback(DEVCB_SRATE_CHG cb, void* param)
	{
		RateCb = cb;
		RateParam = param;
	}

	void LinkSsg(const ssg_callbacks* funcs, void* param)
	{
		if (funcs)
		{
			Ssg = *funcs;
			SsgParam = param;
		}
		else
		{
			SsgParam = NULL;
		}
		UpdateSsgClock();
	}

	void SetOptions(UINT32 options)
	{
		Options = options;
	}

	void WriteMemory(UINT8 type, UINT32 offset, UINT32 length, const UINT8* data)
	{
		std::vector<UINT8>& mem = type == 'A' ? MemoryA : MemoryB;
		if (offset >= mem.size())
			return;
		if (length > mem.size() - offset)
			length = (UINT32)(mem.size() - offset);
		memcpy(mem.data() + offset, data, length);
	}

	void AllocMemory(UINT8 type, UINT32 size)
	{
		std::vector<UINT8>& mem = type == 'A' ? MemoryA : MemoryB;
		mem.assign(size, 0xff);
	}

	// ymfm_interface
	void ymfm_set_timer(uint32_t tnum, int32_t duration_in_clocks) override
	{
		Timers[tnum & 1] = duration_in_clocks;
	}

	uint8_t ymfm_external_read(ymfm::access_class type, uint32_t address) override
	{
		switch (type)
		{
		case ymfm::ACCESS_ADPCM_A:
			return ReadAdpcmA(address);
		case ymfm::ACCESS_ADPCM_B:
			return address < MemoryB.size() ? MemoryB[address] : 0;
		default:
			return 0;
		}
	}

	virtual uint8_t ReadAdpcmA(uint32_t address) const
	{
		return address < MemoryA.size() ? MemoryA[address] : 0;
	}

	void ymfm_external_write(ymfm::access_class type, uint32_t address, uint8_t data) override
	{
		if (type == ymfm::ACCESS_ADPCM_B && address < MemoryB.size())
			MemoryB[address] = data;
	}

	// ssg_override
	void ssg_reset() override
	{
		if (SsgParam)
			Ssg.reset(SsgParam);
	}

	uint8_t ssg_read(uint32_t regnum) override
	{
		if (!SsgParam)
			return 0;
		Ssg.write(SsgParam, 0, (UINT8)regnum);
		return Ssg.read(SsgParam, 0);
	}

	void ssg_write(uint32_t regnum, uint8_t data) override
	{
		if (!SsgParam)
			return;
		Ssg.write(SsgParam, 0, (UINT8)regnum);
		Ssg.write(SsgParam, 1, data);
	}

	void ssg_prescale_changed() override
	{
		UpdateSsgClock();
		// FM sample rate follows prescaler as well
		const UINT32 rate = GetRate();
		if (rate != Rate)
		{
			Rate = rate;
			if (RateCb)
				RateCb(RateParam, rate);
		}
	}

protected:
	virtual UINT32 GetSsgClock() const
	{
		return 0;
	}

	void ResetTimers()
	{
		Timers[0] = Timers[1] = -1;
	}

	// Timers are required for CSM mode (key on by timer A overflow), so they are clocked by samples
	void ClockTimers()
	{
		const INT32 clocks = (INT32)(Clock / GetRate());
		for (UINT32 tnum = 0; tnum < 2; tnum ++)
		{
			if (Timers[tnum] < 0)
				continue;
			Timers[tnum] -= clocks;
			if (Timers[tnum] <= 0)
			{
				const INT32 over = Timers[tnum];
				Timers[tnum] = -1;
				m_engine->engine_timer_expired(tnum);
				if (Timers[tnum] >= 0)
					Timers[tnum] += over;
			}
		}
	}

	void UpdateSsgClock()
	{
		const UINT32 clock = GetSsgClock();
		if (SsgParam && clock)
			Ssg.set_clock(SsgParam, clock);
	}

	// registers copy (two banks) for voices state, cores keep decoded values only
	void MirrorWrite(UINT8 port, UINT8 data)
	{
		const UINT32 bank = (port >> 1) & 1;
		if (port & 1)
			Regs[(bank << 8) | Latch[bank]] = data;
		else
			Latch[bank] = data;
	}

	template<class Engine>
	void FillFmVoice(const Engine& fm, UINT32 ch, UINT8 chip, double rate, DEV_VOICE_STATE* state)
	{
		auto* channel = fm.debug_channel(ch);
		const UINT32 choffs = channel->choffs();
		const bool opm = chip == DEVVOICE_FM_CHIP_OPM;
		const UINT8 alg = opm ? (Regs[0x20 + choffs] & 7) : (Regs[0xb0 + choffs] & 7);
		const UINT8 fb = opm ? ((Regs[0x20 + choffs] >> 3) & 7) : ((Regs[0xb0 + choffs] >> 3) & 7);
		UINT32 minAtt = 0x3ff;
		UINT8 keyOn = 0;

		memset(state->fields, 0, sizeof(state->fields));
		for (UINT32 op = 0; op < 4; op ++)
		{
			const auto* oper = channel->debug_operator(op);
			const UINT32 r = oper->opoffs();
			const UINT32 eg = oper->debug_eg_attenuation();
			const UINT32 tl = Regs[(opm ? 0x60 : 0x40) + r] & 0x7f;
			const UINT32 att = eg + (tl << 3);
			const UINT32 egState = oper->debug_eg_state();
			const UINT8 kon = (KeyOn[ch] >> op) & 1;
			UINT8* out = &state->fields[DEVVOICE_FM_OPS + op * DEVVOICE_FM_OP_SIZE];
			UINT8 stage = egState >= ymfm::EG_ATTACK && egState <= ymfm::EG_RELEASE ? (UINT8)egState : 0;

			if (stage == 4 && eg >= 0x3f0)
				stage = 0;
			state->fields[2 + op] = LevelField(att);
			state->fields[DEVVOICE_FM_EG + op] = LevelField(eg);
			if (kon)
				state->fields[10] |= 1 << op;
			if (CARRIERS[alg] & (1 << op))
			{
				if (kon)
					keyOn = 1;
				if (att < minAtt)
					minAtt = att;
			}
			if (opm)
			{
				out[DEVVOICE_FM_OP_MUL] = Regs[0x40 + r] & 0x0f;
				out[DEVVOICE_FM_OP_TL] = (UINT8)tl;
				out[DEVVOICE_FM_OP_AR] = Regs[0x80 + r] & 0x1f;
				out[DEVVOICE_FM_OP_DR] = Regs[0xa0 + r] & 0x1f;
				out[DEVVOICE_FM_OP_SR] = (Regs[0xc0 + r] & 0x1f) | ((Regs[0x80 + r] >> 6) << 5);
				out[DEVVOICE_FM_OP_SLRR] = (Regs[0xe0 + r] >> 4) | ((Regs[0xe0 + r] & 0x0f) << 4);
				out[DEVVOICE_FM_OP_FLAGS] = stage | ((Regs[0xa0 + r] & 0x80) ? DEVVOICE_FM_OPF_AM : 0);
				out[DEVVOICE_FM_OP_EXTRA] = ((Regs[0x40 + r] >> 4) & 7) | ((Regs[0xc0 + r] >> 6) << 3);
			}
			else
			{
				out[DEVVOICE_FM_OP_MUL] = Regs[0x30 + r] & 0x0f;
				out[DEVVOICE_FM_OP_TL] = (UINT8)tl;
				out[DEVVOICE_FM_OP_AR] = Regs[0x50 + r] & 0x1f;
				out[DEVVOICE_FM_OP_DR] = Regs[0x60 + r] & 0x1f;
				out[DEVVOICE_FM_OP_SR] = (Regs[0x70 + r] & 0x1f) | ((Regs[0x50 + r] >> 6) << 5);
				out[DEVVOICE_FM_OP_SLRR] = (Regs[0x80 + r] >> 4) | ((Regs[0x80 + r] & 0x0f) << 4);
				out[DEVVOICE_FM_OP_FLAGS] = stage | ((Regs[0x60 + r] & 0x80) ? DEVVOICE_FM_OPF_AM : 0);
				out[DEVVOICE_FM_OP_EXTRA] = ((Regs[0x30 + r] >> 4) & 7) | ((Regs[0x90 + r] & 0x0f) << 3);
			}
		}
		if (minAtt > 0x3ff)
			minAtt = 0x3ff;
		state->level = -(float)(minAtt * (96.0 / 1024));
		state->flags = DEVVOICE_FREQ | DEVVOICE_LEVEL | (keyOn ? DEVVOICE_KEYON : 0);
		state->kind = DEVVOICE_KIND_OPN_FM;
		state->fields[0] = alg;
		state->fields[1] = fb;
		state->fields[6] = CARRIERS[alg];
		state->fields[DEVVOICE_FM_CHIP] = chip;
		if (opm)
		{
			// semitones of key codes in octave, starting from C#
			static const UINT8 NOTES[16] = {0, 1, 2, 2, 3, 4, 5, 5, 6, 7, 8, 8, 9, 10, 11, 11};
			const UINT32 kc = Regs[0x28 + choffs] & 0x7f;
			const UINT32 kf = Regs[0x30 + choffs] >> 2;
			const double note = ((kc >> 4) & 7) * 12 + NOTES[kc & 15] + kf / 64.0;
			const UINT8 rl = Regs[0x20 + choffs] >> 6;
			// A4 (KC=0x4A) is 440Hz at 3.58MHz
			state->freq = (float)(440.0 * pow(2.0, (note - 56) / 12.0) * Clock / 3579545.0);
			// RL bits are L=1, R=2
			state->fields[7] = (UINT8)(((rl & 1) << 1) | ((rl >> 1) & 1));
			state->fields[8] = Regs[0x38 + choffs] & 3;
			state->fields[9] = (Regs[0x38 + choffs] >> 4) & 7;
			state->fields[11] = (UINT8)((kc >> 4) & 7);
			state->fields[DEVVOICE_FM_FNUM] = (UINT8)kc;
			state->fields[DEVVOICE_FM_FNUM + 1] = (UINT8)kf;
			state->fields[DEVVOICE_FM_GLOBAL] = (UINT8)((Regs[0x1b] & 3) | ((Regs[0x0f] & 0x80) ? 0x04 : 0));
			state->fields[DEVVOICE_FM_GLOBAL2] = Regs[0x18];
			state->fields[DEVVOICE_FM_AMD] = AmDepth;
			state->fields[DEVVOICE_FM_PMD] = PmDepth;
			state->fields[DEVVOICE_FM_NOISE] = Regs[0x0f] & 0x1f;
			state->fields[DEVVOICE_FM_TIMER_A] = (UINT8)((Regs[0x10] << 2) | (Regs[0x11] & 3));
			state->fields[DEVVOICE_FM_TIMER_A + 1] = (UINT8)(Regs[0x10] >> 6);
		}
		else
		{
			const UINT32 bank = choffs & 0x100;
			const UINT32 local = choffs & 0xff;
			const UINT32 fnum = ((Regs[bank + 0xa4 + local] & 7) << 8) | Regs[bank + 0xa0 + local];
			const UINT32 block = (Regs[bank + 0xa4 + local] >> 3) & 7;
			const UINT8 pan = Regs[bank + 0xb4 + local];
			// fnote = fnum * rate * 2^(block-1) / 2^20
			state->freq = (float)(fnum * rate * (1 << block) / (double)(1 << 21));
			// OPN has no panning, so both outputs are on
			state->fields[7] = chip == DEVVOICE_FM_CHIP_OPN ? 3 : (UINT8)(pan >> 6);
			state->fields[8] = (pan >> 4) & 3;
			state->fields[9] = pan & 7;
			state->fields[11] = (UINT8)block;
			state->fields[DEVVOICE_FM_FNUM] = (UINT8)(fnum & 0xff);
			state->fields[DEVVOICE_FM_FNUM + 1] = (UINT8)(fnum >> 8);
			state->fields[DEVVOICE_FM_GLOBAL] = (UINT8)((Regs[0x22] & 0x0f) | ((Regs[0x27] >> 6) == 1 ? 0x10 : 0)
				| ((Regs[0x27] >> 6) == 2 ? 0x20 : 0) | (DacEnabled() ? 0x40 : 0));
			state->fields[DEVVOICE_FM_TIMER_A] = (UINT8)((Regs[0x24] << 2) | (Regs[0x25] & 3));
			state->fields[DEVVOICE_FM_TIMER_A + 1] = (UINT8)(Regs[0x24] >> 6);
		}
		const UINT32 egRate = (UINT32)(rate / 3 + 0.5);
		state->fields[DEVVOICE_FM_EG_RATE] = (UINT8)(egRate & 0xff);
		state->fields[DEVVOICE_FM_EG_RATE + 1] = (UINT8)(egRate >> 8);
	}

	virtual bool DacEnabled() const
	{
		return false;
	}

	// key on register writes, bits are slots 1..4 as ymfm operators of channel
	void TrackOpnKeyOn(UINT8 data, UINT32 channels)
	{
		static const UINT8 CHANNELS[8] = {0, 1, 2, 0xff, 3, 4, 5, 0xff};
		const UINT8 ch = CHANNELS[data & 7];
		if (ch < channels)
			KeyOn[ch] = (UINT8)(data >> 4);
	}

	static void ClearVoice(DEV_VOICE_STATE* state)
	{
		memset(state, 0, sizeof(*state));
		state->level = -96.0f;
	}

	static INT32 Sum(const ymfm::ymfm_output<2>& out)
	{
		return out.data[0] + out.data[1];
	}

	const UINT32 Clock;
	const UINT32 VoicesCount;
	std::vector<INT32> Voices;
	UINT32 Rate = 0;
	UINT32 MuteMask = 0;
	UINT32 Options = 0;
	UINT8 Regs[0x200];
	UINT8 Latch[2] = {0, 0};
	UINT8 KeyOn[8] = {0};
	UINT8 AmDepth = 0;
	UINT8 PmDepth = 0;
	INT32 Timers[2];
	std::vector<UINT8> MemoryA;
	std::vector<UINT8> MemoryB;
	DEVCB_VOICES VoicesCb = NULL;
	void* VoicesParam = NULL;
	DEVCB_SRATE_CHG RateCb = NULL;
	void* RateParam = NULL;
	ssg_callbacks Ssg;
	void* SsgParam = NULL;
};

Device* CreateOPM(UINT32 clock);

}	// namespace ymfmintf

#endif	// __YMFMINTF_DEVICE_H__
