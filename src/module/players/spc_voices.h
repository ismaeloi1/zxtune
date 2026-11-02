/**
 *
 * @file
 *
 * @brief  S-DSP voices state for visualization, shared by SPC and SNSF players
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "module/voices_scope.h"

#include "types.h"

#include "3rdparty/snesspc/snes_spc/SPC_DSP.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>

namespace Module
{
  //! Fills VoiceState::SPC_DSP states (see its layout) from DSP registers and RAM
  class SpcVoicesState
  {
  public:
    static const uint_t VOICES = ::SPC_DSP::voice_count;

    void Fill(const ::SPC_DSP& dsp, std::array<VoiceState, VOICES>& states)
    {
      std::array<uint8_t, GLOBALS> globals = {};
      const uint8_t globalRegs[] = {::SPC_DSP::r_mvoll, ::SPC_DSP::r_mvolr, ::SPC_DSP::r_evoll, ::SPC_DSP::r_evolr,
                                    ::SPC_DSP::r_efb,   ::SPC_DSP::r_edl,   ::SPC_DSP::r_flg,   ::SPC_DSP::r_esa};
      for (uint_t idx = 0; idx < 8; ++idx)
      {
        globals[idx] = static_cast<uint8_t>(dsp.read(globalRegs[idx]));
        globals[8 + idx] = static_cast<uint8_t>(dsp.read(::SPC_DSP::r_fir + idx * 0x10));
      }
      const uint8_t moreRegs[] = {::SPC_DSP::r_dir, ::SPC_DSP::r_non,  ::SPC_DSP::r_eon, ::SPC_DSP::r_pmon,
                                  ::SPC_DSP::r_kon, ::SPC_DSP::r_koff, ::SPC_DSP::r_endx};
      for (uint_t idx = 0; idx < sizeof(moreRegs); ++idx)
      {
        globals[16 + idx] = static_cast<uint8_t>(dsp.read(moreRegs[idx]));
      }
      const auto* const ram = dsp.ram();
      const auto dir = uint_t(dsp.read(::SPC_DSP::r_dir)) * 0x100;
      const auto endx = dsp.read(::SPC_DSP::r_endx);
      // samples are rarely changed, so only one voice is rescanned at a time to keep it cheap
      const auto rescan = (++Snapshots % RESCAN_PERIOD) == 0 ? (Snapshots / RESCAN_PERIOD) % VOICES : VOICES;
      for (uint_t idx = 0; idx < VOICES; ++idx)
      {
        ::SPC_DSP::voice_state_t in;
        dsp.get_voice_state(idx, &in);
        auto& out = states[idx];
        out = VoiceState();
        // envelope is 11 bit, volume is signed 8 bit
        const auto vol = std::max(std::abs(in.vol_l), std::abs(in.vol_r));
        const auto level = (in.env / 2047.0f) * (vol / 128.0f);
        out.Level = level > 0.0000158f ? 20 * std::log10(level) : -96.0f;
        out.Flags = VoiceState::HAS_LEVEL;
        if (!in.released && in.env > 0)
        {
          out.Flags |= VoiceState::KEY_ON;
        }
        // pitch depends on sample contents, so frequency is estimated from output
        if (in.noise)
        {
          out.Flags |= VoiceState::NOISE;
        }
        out.Kind = VoiceState::SPC_DSP;
        auto& f = out.Fields;
        f[0] = static_cast<uint8_t>(in.env >> 4);
        // ADSR bit of ADSR1 register selects envelope mode, release is used in both cases
        f[1] = static_cast<uint8_t>(!in.released && !(in.adsr0 & 0x80) ? 4 : in.env_mode);
        f[2] = static_cast<uint8_t>(in.adsr0);
        f[3] = static_cast<uint8_t>(in.adsr1);
        f[4] = static_cast<uint8_t>(in.gain);
        f[5] = static_cast<uint8_t>(in.srcn);
        f[6] = static_cast<uint8_t>(in.vol_l);
        f[7] = static_cast<uint8_t>(in.vol_r);
        f[8] = static_cast<uint8_t>(in.pitch & 0xff);
        f[9] = static_cast<uint8_t>(in.pitch >> 8);
        f[10] = static_cast<uint8_t>(in.echo | (in.pmod << 1) | (in.noise << 2));
        const auto entry = dir + uint_t(in.srcn) * 4;
        const auto start = Word(ram, entry);
        const auto loop = Word(ram, entry + 2);
        auto& sample = Samples[idx];
        if (idx == rescan || sample.Dir != dir || sample.Start != start || sample.Loop != loop)
        {
          sample.Scan(ram, dir, start, loop);
        }
        uint8_t flags = endx & (1 << idx) ? SAMPLE_END : 0;
        if (sample.Blocks)
        {
          flags |= SAMPLE_INFO | (sample.Loops ? SAMPLE_LOOP : 0);
          PutWord(&f[12], sample.Blocks);
          PutWord(&f[14], sample.LoopBlock);
          const auto offset = (uint_t(in.brr_addr) - start) & 0xffff;
          if (offset % BLOCK_SIZE == 0 && offset / BLOCK_SIZE < sample.Blocks)
          {
            flags |= SAMPLE_POSITION;
            PutWord(&f[16], offset / BLOCK_SIZE);
          }
        }
        f[11] = flags;
        std::copy(globals.begin(), globals.end(), f.begin() + 18);
      }
    }

  private:
    static uint_t Word(const uint8_t* ram, uint_t addr)
    {
      return ram[addr & 0xffff] | (uint_t(ram[(addr + 1) & 0xffff]) << 8);
    }

    static void PutWord(uint8_t* out, uint_t val)
    {
      out[0] = static_cast<uint8_t>(val);
      out[1] = static_cast<uint8_t>(val >> 8);
    }

    static const uint_t BLOCK_SIZE = 9;
    static const uint_t MAX_BLOCKS = 0x10000 / BLOCK_SIZE + 1;
    // ~0.5s per voice for 1ms snapshots
    static const uint_t RESCAN_PERIOD = 64;
    static const uint_t GLOBALS = 24;

    enum : uint8_t
    {
      SAMPLE_INFO = 1,
      SAMPLE_LOOP = 2,
      SAMPLE_END = 4,
      SAMPLE_POSITION = 8,
    };

    struct Sample
    {
      uint_t Dir = ~0u;
      uint_t Start = 0;
      uint_t Loop = 0;
      // 0 if end block is not found
      uint_t Blocks = 0;
      // 0xffff if loop address is not at block of this sample
      uint_t LoopBlock = 0xffff;
      bool Loops = false;

      // walks BRR blocks till the one with END flag, as DSP does while playing
      void Scan(const uint8_t* ram, uint_t dir, uint_t start, uint_t loop)
      {
        Dir = dir;
        Start = start;
        Loop = loop;
        Blocks = 0;
        Loops = false;
        for (uint_t idx = 0, addr = start; idx < MAX_BLOCKS; ++idx, addr = (addr + BLOCK_SIZE) & 0xffff)
        {
          const auto header = ram[addr];
          if (header & 1)
          {
            Blocks = idx + 1;
            Loops = (header & 2) != 0;
            break;
          }
        }
        const auto loopOffset = (loop - start) & 0xffff;
        LoopBlock = loopOffset % BLOCK_SIZE == 0 && loopOffset / BLOCK_SIZE < Blocks ? loopOffset / BLOCK_SIZE : 0xffff;
      }
    };

    std::array<Sample, VOICES> Samples;
    uint_t Snapshots = 0;
  };
}  // namespace Module
