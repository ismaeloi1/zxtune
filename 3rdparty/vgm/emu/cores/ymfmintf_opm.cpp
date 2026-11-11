// zxtune: YM2151 on ymfm core, see ymfmintf.cpp

#include "ymfmintf_device.h"

// engine templates are instantiated by their users, so chip implementation is compiled here (separately from OPN as
// they share engine implementation file)
#include "ymfm/ymfm_opm.cpp"

namespace ymfmintf
{

class OPM : public Device, public ymfm::ym2151
{
public:
	explicit OPM(UINT32 clock)
		: Device(clock, 8)
		, ymfm::ym2151(static_cast<ymfm::ymfm_interface&>(*this))
	{
		Rate = GetRate();
	}

	void Reset() override
	{
		ResetTimers();
		ymfm::ym2151::reset();
		memset(KeyOn, 0, sizeof(KeyOn));
		AmDepth = PmDepth = 0;
	}

	void Write(UINT8 port, UINT8 data) override
	{
		MirrorWrite(port & 1, data);
		if (port & 1)
		{
			if (Latch[0] == 0x08)
				// register bits are M1, C1, M2, C2 as ymfm operators of channel
				KeyOn[data & 7] = (UINT8)((data >> 3) & 0x0f);
			else if (Latch[0] == 0x19)
				(data & 0x80 ? PmDepth : AmDepth) = data & 0x7f;
		}
		ymfm::ym2151::write(port & 1, data);
	}

	UINT8 Read(UINT8 port) override
	{
		return ymfm::ym2151::read(port & 1);
	}

	UINT32 GetRate() const override
	{
		return Clock / 64;
	}

	void Generate(INT32* out, bool voices) override
	{
		m_fm.clock(fm_engine::ALL_CHANNELS);
		ymfm::ymfm_output<2> sum;
		if (voices)
		{
			sum.clear();
			for (UINT32 ch = 0; ch < 8; ch ++)
			{
				ymfm::ymfm_output<2> chan;
				chan.clear();
				// OPM is full 14-bit with no intermediate clipping
				if (!((MuteMask >> ch) & 1))
					m_fm.output(chan, 0, 32767, 1 << ch);
				Voices[ch] = Sum(chan) * OUTPUT_GAIN * TAP_GAIN / 2;
				sum.data[0] += chan.data[0];
				sum.data[1] += chan.data[1];
			}
		}
		else
		{
			m_fm.output(sum.clear(), 0, 32767, ~MuteMask & 0xff);
		}
		// YM3012 DAC with mantissa/exponent format
		sum.roundtrip_fp();
		out[0] = sum.data[0] * OUTPUT_GAIN / 2;
		out[1] = sum.data[1] * OUTPUT_GAIN / 2;
	}

	UINT32 GetVoicesState(UINT32 count, DEV_VOICE_STATE* states) override
	{
		for (UINT32 ch = 0; ch < 8 && ch < count; ch ++)
			FillFmVoice(m_fm, ch, DEVVOICE_FM_CHIP_OPM, GetRate(), &states[ch]);
		return 8;
	}

private:
	static const INT32 OUTPUT_GAIN = 2;
	static const INT32 TAP_GAIN = 2;
};

Device* CreateOPM(UINT32 clock)
{
	return new OPM(clock);
}

}	// namespace ymfmintf
