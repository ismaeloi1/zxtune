/**
 *
 * @file
 *
 * @brief Oscilloscope data provider interface
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "module/voices_scope.h"
#include "sound/chunk.h"

#include "string_type.h"
#include "types.h"

#include <memory>

namespace Sound
{
  //! @brief Collects separate voices (if supported) or master output and provides triggered waveforms
  class Scope : public Module::VoicesScope
  {
  public:
    using Ptr = std::shared_ptr<Scope>;

    //! @brief Called after each rendered chunk of module, flushes collected voices data
    virtual void Commit(const Chunk& master) = 0;

    //! @brief Called on each output buffer request
    //! @param start index of first sample in buffer (in samples since start)
    //! @param samples output buffer size
    virtual void Played(uint64_t start, uint_t samples) = 0;

    //! @brief Seek happened, reset all the state
    virtual void Reset() = 0;

    //! @brief Output delay not covered by Played calls (e.g. device buffers filled by blocking writes)
    //! @param ms compensated when currently playing sample is estimated internally
    virtual void SetLatency(uint_t ms) = 0;

    struct Layout
    {
      //! channels count actually stored
      uint_t Channels = 0;
      //! voices layout version, changed on each SetVoicesGroups, see GetLayout
      uint_t Id = 0;
    };

    //! @param maxChannels maximum channels count to get
    //! @param points points per channel to get
    //! @param playing index of currently heard sample (as passed to Played), negative to estimate it internally
    //! @param windowMs displayed waveform duration (corrscope's render_ms)
    //! @param target [channels][points] array of normalized samples
    virtual Layout Get(uint_t maxChannels, uint_t points, int64_t playing, uint_t windowMs, int16_t* target) = 0;

    //! Gauges layout per chip (JSIDPlay2-like):
    //! - GAUGES_COUNT gauges by GAUGE_COLUMNS columns of (min, max) bytes, normalized to 0..255
    //!   order: wave1..3, envelope1..3, frequency1..3, volume, resonance, cutoff
    //!   wave and volume gauges cover requested window, others are 16384 cycles per column (~4.2s)
    //! - last chip registers 0x00..0x18 snapshot padded to STATE_RECORD_SIZE
    static const uint_t GAUGES_COUNT = 12;
    static const uint_t GAUGE_COLUMNS = 256;
    static const uint_t STATE_RECORD_SIZE = 32;
    static const uint_t GAUGES_SIZE = GAUGES_COUNT * GAUGE_COLUMNS * 2 + STATE_RECORD_SIZE;

    //! @param maxChips maximum chips count to get
    //! @param playing see Get
    //! @param waveWindowMs duration of wave and volume gauges
    //! @param target [chips][GAUGES_SIZE]
    //! @return chips count actually stored
    virtual uint_t GetGauges(uint_t maxChips, int64_t playing, uint_t waveWindowMs, uint8_t* target) = 0;

    //! @return voices layout description, empty for single master channel:
    //! - first line: "registers" (SID-like chips, see GetGauges) or "audio" (see GetVoiceGauges)
    //! - next lines: group (chip) name and its voices names separated by tabs
    virtual String GetLayout() const = 0;

    //! Gauges layout per voice of chips without registers:
    //! - VOICE_GAUGES_COUNT gauges by GAUGE_COLUMNS columns of (min, max) bytes, normalized to 0..255
    //!   order: wave (covers requested window), level (dB down to -48dB), frequency (log scale, 7 octaves from A0)
    //!   level and frequency are 16384 C64 cycles per column, as for SID gauges
    //! - last voice state: frequency (float Hz), level (float dB), flags, kind (see Module::VoiceState),
    //!   12 bytes of chip specific fields, 16 bytes of text, padded to VOICE_STATE_SIZE, little endian
    static const uint_t VOICE_GAUGES_COUNT = 3;
    static const uint_t VOICE_STATE_SIZE = 48;
    static const uint_t VOICE_GAUGES_SIZE = VOICE_GAUGES_COUNT * GAUGE_COLUMNS * 2 + VOICE_STATE_SIZE;

    //! @param maxVoices maximum voices count to get
    //! @param playing see Get
    //! @param waveWindowMs duration of wave gauge
    //! @param target [voices][VOICE_GAUGES_SIZE]
    //! @return voices count actually stored
    virtual uint_t GetVoiceGauges(uint_t maxVoices, int64_t playing, uint_t waveWindowMs, uint8_t* target) = 0;

    //! @return emulation description and scope statistics
    virtual String GetStatus() const = 0;

    static Ptr Create(uint_t samplerate);
  };
}  // namespace Sound
