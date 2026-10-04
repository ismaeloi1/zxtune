/*! \file resid/sid.h */

//  ---------------------------------------------------------------------------
//  This file is part of reSID, a MOS6581 SID emulator engine.
//  Copyright (C) 2010  Dag Lem <resid@nimrod.no>
//
//  This program is free software; you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation; either version 2 of the License, or
//  (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this program; if not, write to the Free Software
//  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//  ---------------------------------------------------------------------------

#ifndef RESID_SID_H
#define RESID_SID_H

#include "resid-config.h"
#include "voice.h"
#if NEW_8580_FILTER
#include "filter8580new.h"
#else
#include "filter.h"
#endif
#include "extfilt.h"
#include "pot.h"

namespace reSID
{

class SID
{
public:
  SID();
  ~SID();

  void set_chip_model(chip_model model);
  void set_voice_mask(reg4 mask);
  void enable_filter(bool enable);
  void adjust_filter_bias(double dac_bias);
  void enable_external_filter(bool enable);
  bool set_sampling_parameters(double clock_freq, sampling_method method,
  double sample_freq, double pass_freq = -1,
  double filter_scale = 0.97);
  void adjust_sampling_frequency(double sample_freq);
  void enable_raw_debug_output(bool enable);

  // Optional per-voice output tap (zxtune oscilloscope).
  // When set, clock() also stores 7 values per output sample: 3 filtered voices, 3 oscillators, volume.
  void set_voice_output(short* buf) { voice_output = buf; }

  // Oscillator and envelope outputs of any voice, as OSC3/ENV3 registers
  reg8 read_osc(int i) { return voice[i].wave.readOSC(); }
  reg8 read_env(int i) { return voice[i].envelope.readENV(); }

  void clock();
  void clock(cycle_count delta_t);
  int clock(cycle_count& delta_t, short* buf, int n, int interleave = 1);
  void reset();

  // Read/write registers.
  reg8 read(reg8 offset);
  void write(reg8 offset, reg8 value);

  // Read/write state.
  class State
  {
  public:
    State();

    char sid_register[0x20];

    reg8 bus_value;
    cycle_count bus_value_ttl;
    cycle_count write_pipeline;
    reg8 write_address;
    reg4 voice_mask;

    reg24 accumulator[3];
    reg24 shift_register[3];
    cycle_count shift_register_reset[3];
    cycle_count shift_pipeline[3];
    reg16 pulse_output[3];
    cycle_count floating_output_ttl[3];

    reg16 rate_counter[3];
    reg16 rate_counter_period[3];
    reg16 exponential_counter[3];
    reg16 exponential_counter_period[3];
    reg8 envelope_counter[3];
    EnvelopeGenerator::State envelope_state[3];
    bool hold_zero[3];
    cycle_count envelope_pipeline[3];
  };

  State read_state();
  void write_state(const State& state);

  // 16-bit input (EXT IN).
  void input(short sample);

  // 16-bit output (AUDIO OUT).
  int output();

  void debugoutput(void);

 protected:
  static double I0(double x);
  int clock_fast(cycle_count& delta_t, short* buf, int n, int interleave);
  int clock_interpolate(cycle_count& delta_t, short* buf, int n, int interleave);
  int clock_resample(cycle_count& delta_t, short* buf, int n, int interleave);
  int clock_resample_fastmem(cycle_count& delta_t, short* buf, int n, int interleave);
  void write();

  chip_model sid_model;
  Voice voice[3];
  Filter filter;
  ExternalFilter extfilt;
  Potentiometer potx;
  Potentiometer poty;

  reg8 bus_value;
  cycle_count bus_value_ttl;

  // The data bus TTL for the selected chip model
  cycle_count databus_ttl;

  // Pipeline for writes on the MOS8580.
  cycle_count write_pipeline;
  reg8 write_address;

  double clock_frequency;

  // Used to amplify the output by scaleFactor/2 to get an adequate playback volume
  int scaleFactor;

  enum {
    // Resampling constants.
    // The error in interpolated lookup is bounded by 1.234/L^2,
    // while the error in non-interpolated lookup is bounded by
    // 0.7854/L + 0.4113/L^2, see
    // http://www-ccrma.stanford.edu/~jos/resample/Choice_Table_Size.html
    // For a resolution of 16 bits this yields L >= 285 and L >= 51473,
    // respectively.
    FIR_N = 125,
    FIR_RES = 285,
    FIR_RES_FASTMEM = 51473,
    FIR_SHIFT = 15,

    RINGSIZE = 1 << 14,
    RINGMASK = RINGSIZE - 1,

    // Fixed point constants (16.16 bits).
    FIXP_SHIFT = 16,
    FIXP_MASK = 0xffff
  };

  // Sampling variables.
  sampling_method sampling;
  cycle_count cycles_per_sample;
  cycle_count sample_offset;
  int sample_index;
  short sample_prev, sample_now;
  int fir_N;
  int fir_RES;
  double fir_beta;
  double fir_f_cycles_per_sample;
  double fir_filter_scale;

  // Ring buffer with overflow for contiguous storage of RINGSIZE samples.
  short* sample;

  // FIR_RES filter tables (FIR_N*FIR_RES).
  short* fir;

  bool raw_debug_output; // FIXME: should be private?

  // Per-voice output tap, see set_voice_output()
  short* voice_output;
  // Shadow filters fed by single voice each to get voice output after filtering.
  // Registers are always updated, clocking is done only while voice output is enabled
  Filter voice_filter[3];
  ExternalFilter voice_extfilt[3];

  void clock_voice_filters(int v1, int v2, int v3)
  {
    voice_filter[0].clock(v1, 0, 0);
    voice_filter[1].clock(0, v2, 0);
    voice_filter[2].clock(0, 0, v3);
    for (int i = 0; i < 3; i++) {
      voice_extfilt[i].clock(voice_filter[i].output());
    }
  }

  void clock_voice_filters(cycle_count delta_t, int v1, int v2, int v3)
  {
    voice_filter[0].clock(delta_t, v1, 0, 0);
    voice_filter[1].clock(delta_t, 0, v2, 0);
    voice_filter[2].clock(delta_t, 0, 0, v3);
    for (int i = 0; i < 3; i++) {
      voice_extfilt[i].clock(delta_t, voice_filter[i].output());
    }
  }

  void write_voice_output(int s);
};


// ----------------------------------------------------------------------------
// Inline functions.
// The following functions are defined inline because they are called every
// time a sample is calculated.
// ----------------------------------------------------------------------------

#if RESID_INLINING || defined(RESID_SID_CC)

// ----------------------------------------------------------------------------
// Read 16-bit sample from audio output.
// ----------------------------------------------------------------------------
RESID_INLINE
int SID::output()
{
  return extfilt.output();
}


// ----------------------------------------------------------------------------
// SID clocking - 1 cycle.
// ----------------------------------------------------------------------------
RESID_INLINE
void SID::clock()
{
  int i;

  // Clock amplitude modulators.
  for (i = 0; i < 3; i++) {
    voice[i].envelope.clock();
  }

  // Clock oscillators.
  for (i = 0; i < 3; i++) {
    voice[i].wave.clock();
  }

  // Synchronize oscillators.
  for (i = 0; i < 3; i++) {
    voice[i].wave.synchronize();
  }

  // Calculate waveform output.
  for (i = 0; i < 3; i++) {
    voice[i].wave.set_waveform_output();
  }

  // Clock filter.
  const int v1 = voice[0].output();
  const int v2 = voice[1].output();
  const int v3 = voice[2].output();
  filter.clock(v1, v2, v3);
  if (unlikely(voice_output != 0)) {
    clock_voice_filters(v1, v2, v3);
  }

  // Clock external filter.
  extfilt.clock(filter.output());

  // Pipelined writes on the MOS8580.
  if (unlikely(write_pipeline)) {
    write();
  }

  // Age bus value.
  if (unlikely(!--bus_value_ttl)) {
    bus_value = 0;
  }

  if (unlikely(raw_debug_output)) {
    debugoutput();
  }
}

#endif // RESID_INLINING || defined(RESID_SID_CC)

} // namespace reSID

#endif // not RESID_SID_H
