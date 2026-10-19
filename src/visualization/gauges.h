/**
 *
 * @file
 *
 * @brief Accessors to gauges data of Sound::Scope::GetGauges and Sound::Scope::GetVoiceGauges
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <cstdint>
#include <cstring>
#include <string>

namespace Visualization
{
  //! What gauges data is requested together with waveforms
  enum class GaugesKind
  {
    NONE,
    CHIPS,
    VOICES
  };

  //! History of values by columns
  class GaugesData
  {
  public:
    virtual ~GaugesData() = default;

    //! @return min/max level (0..255) of gauge column
    virtual int Min(int gauge, int column) const = 0;
    virtual int Max(int gauge, int column) const = 0;
  };

  //! SID registers snapshot
  class ChipState
  {
  public:
    static const int SIZE = 32;

    explicit ChipState(const uint8_t* data)
      : Data(data)
    {}

    int Frequency(int voice) const
    {
      return Reg(voice * 7) | (Reg(voice * 7 + 1) << 8);
    }

    int PulseWidth(int voice) const
    {
      return Reg(voice * 7 + 2) | ((Reg(voice * 7 + 3) & 0x0f) << 8);
    }

    int Control(int voice) const
    {
      return Reg(voice * 7 + 4);
    }

    int AttackDecay(int voice) const
    {
      return Reg(voice * 7 + 5);
    }

    int SustainRelease(int voice) const
    {
      return Reg(voice * 7 + 6);
    }

    int Oscillator(int voice) const
    {
      return Reg(0x19 + voice);
    }

    int Envelope(int voice) const
    {
      return Reg(0x1c + voice);
    }

    int Cutoff() const
    {
      return (Reg(0x15) & 7) | (Reg(0x16) << 3);
    }

    int Resonance() const
    {
      return Reg(0x17) >> 4;
    }

    int Routing() const
    {
      return Reg(0x17) & 0x0f;
    }

    int Mode() const
    {
      return Reg(0x18) >> 4;
    }

    int Volume() const
    {
      return Reg(0x18) & 0x0f;
    }

  private:
    int Reg(int idx) const
    {
      return Data[idx];
    }

  private:
    const uint8_t* const Data;
  };

  //! Single chip of Sound::Scope::GetGauges
  class ChipGauges : public GaugesData
  {
  public:
    // see Sound::Scope::GAUGES_SIZE
    static const int GAUGES = 12;
    static const int COLUMNS = 256;
    static const int SIZE = GAUGES * COLUMNS * 2 + ChipState::SIZE;

    static const int VOLUME = 9;
    static const int RESONANCE = 10;
    static const int CUTOFF = 11;

    explicit ChipGauges(const uint8_t* data)
      : Data(data)
    {}

    int Min(int gauge, int column) const override
    {
      return Data[(gauge * COLUMNS + column) * 2];
    }

    int Max(int gauge, int column) const override
    {
      return Data[(gauge * COLUMNS + column) * 2 + 1];
    }

    ChipState State() const
    {
      return ChipState(Data + GAUGES * COLUMNS * 2);
    }

    //! Progress (0..1) of the last column of slow gauges, history is scrolled by it for smooth movement
    float Phase() const
    {
      return Data[SIZE - 1] / 256.f;
    }

    static int Wave(int voice)
    {
      return voice;
    }

    static int Envelope(int voice)
    {
      return 3 + voice;
    }

    static int Frequency(int voice)
    {
      return 6 + voice;
    }

    //! wave and volume columns are 128 CPU cycles, others are 16384 cycles
    static bool IsSlow(int gauge)
    {
      return (gauge >= 3 && gauge <= 8) || gauge == RESONANCE || gauge == CUTOFF;
    }

  private:
    const uint8_t* const Data;
  };

  //! Mirrors Module::VoiceState::Kind
  namespace VoiceKind
  {
    const int GENERIC = 0;
    const int OPN_FM = 1;
    const int OPN_DAC = 2;
    const int PSG_TONE = 3;
    const int PSG_NOISE = 4;
    const int OPL_2OP = 5;
    const int OPL_4OP = 6;
    const int OPL_RHYTHM = 7;
    const int SPC_DSP = 8;
    const int MT32_PART = 9;
    const int MT32_PARTIAL = 10;
    const int NES_PULSE = 11;
    const int NES_TRIANGLE = 12;
    const int NES_NOISE = 13;
    const int NES_DMC = 14;
  }  // namespace VoiceKind

  //! Single voice of Sound::Scope::GetVoiceGauges
  class VoiceGauges : public GaugesData
  {
  public:
    // see Sound::Scope::VOICE_GAUGES_SIZE
    static const int GAUGES = 3;
    static const int COLUMNS = 256;
    static const int SIZE = GAUGES * COLUMNS * 2 + 48;

    static const int WAVE = 0;
    static const int LEVEL = 1;
    static const int FREQUENCY = 2;

    explicit VoiceGauges(const uint8_t* data)
      : Data(data)
    {}

    int Min(int gauge, int column) const override
    {
      return Data[(gauge * COLUMNS + column) * 2];
    }

    int Max(int gauge, int column) const override
    {
      return Data[(gauge * COLUMNS + column) * 2 + 1];
    }

    //! Hz, valid if HasFrequency
    float Frequency() const
    {
      return Float(STATE);
    }

    //! dB, 0 or negative, valid if HasLevel
    float Level() const
    {
      return Float(STATE + 4);
    }

    bool IsKeyOn() const
    {
      return 0 != (Flags() & 1);
    }

    bool HasFrequency() const
    {
      return 0 != (Flags() & 2);
    }

    bool HasLevel() const
    {
      return 0 != (Flags() & 4);
    }

    bool IsNoise() const
    {
      return 0 != (Flags() & 8);
    }

    int Kind() const
    {
      return Data[STATE + 9];
    }

    //! Chip specific field, meaning depends on Kind (see Module::VoiceState)
    int Field(int idx) const
    {
      return Data[STATE + 10 + idx];
    }

    int SignedField(int idx) const
    {
      return static_cast<int8_t>(Data[STATE + 10 + idx]);
    }

    //! Progress (0..1) of the last level and frequency column
    float Phase() const
    {
      return Data[SIZE - 1] / 256.f;
    }

    //! Text of voice (e.g. instrument name), empty if not provided
    std::string Text() const;

  private:
    static const int STATE = GAUGES * COLUMNS * 2;
    static const int FIELDS = 12;
    static const int TEXT = 16;

    int Flags() const
    {
      return Data[STATE + 8];
    }

    // little endian as stored by Sound::Scope
    float Float(int at) const
    {
      const uint32_t bits = Data[at] | (Data[at + 1] << 8) | (Data[at + 2] << 16) | (uint32_t(Data[at + 3]) << 24);
      float result = 0;
      std::memcpy(&result, &bits, sizeof(result));
      return result;
    }

  private:
    const uint8_t* const Data;
  };
}  // namespace Visualization
