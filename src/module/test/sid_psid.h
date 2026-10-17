/**
 *
 * @file
 *
 * @brief  Synthetic PSID tunes for SID emulation host checks
 *
 **/

#pragma once

#include "types.h"

#include <array>
#include <cstring>
#include <vector>

namespace SidTest
{
  // Full image of SID registers $D400..$D418
  using Registers = std::array<uint8_t, 25>;

  // Static sound tests need no player routine, so registers are just written once per subtune (#1..#N)
  inline std::vector<uint8_t> MakePsid(const std::vector<Registers>& songs)
  {
    const uint_t load = 0x1000;
    // init: A - song index
    //   asl a; tax; lda ptrs,x; sta $fb; lda ptrs+1,x; sta $fc
    //   ldy #24; loop: lda ($fb),y; sta $d400,y; dey; bpl loop; rts
    // Registers are written from the end, so envelope is set before gate bit and volume first
    const uint_t ptrs = load + 24;
    std::vector<uint8_t> code = {0x0a, 0xaa, 0xbd, uint8_t(ptrs & 0xff), uint8_t(ptrs >> 8), 0x85, 0xfb, 0xbd,
                                 uint8_t((ptrs + 1) & 0xff), uint8_t((ptrs + 1) >> 8), 0x85, 0xfc, 0xa0, 0x18, 0xb1,
                                 0xfb, 0x99, 0x00, 0xd4, 0x88, 0x10, 0xf8, 0x60,
                                 // play
                                 0x60};
    const uint_t images = ptrs + 2 * songs.size();
    for (std::size_t idx = 0; idx < songs.size(); ++idx)
    {
      const auto addr = images + idx * sizeof(Registers);
      code.push_back(uint8_t(addr & 0xff));
      code.push_back(uint8_t(addr >> 8));
    }
    for (const auto& regs : songs)
    {
      code.insert(code.end(), regs.begin(), regs.end());
    }
    std::vector<uint8_t> result(0x7c);
    std::memcpy(result.data(), "PSID", 4);
    const auto put16 = [&result](std::size_t offset, uint_t val) {
      result[offset] = uint8_t(val >> 8);
      result[offset + 1] = uint8_t(val & 0xff);
    };
    put16(0x04, 2);
    put16(0x06, 0x7c);
    put16(0x08, load);
    put16(0x0a, load);
    put16(0x0c, load + 23);
    put16(0x0e, songs.size());
    put16(0x10, 1);
    std::memcpy(&result[0x16], "Synthetic", 9);
    // PAL clock, unknown model to use default one
    put16(0x76, 0x04);
    result.insert(result.end(), code.begin(), code.end());
    return result;
  }

  // Sustained voice 1 without filter routing
  inline Registers MakeVoice(uint_t freq, uint8_t control, uint_t pulseWidth = 0x800)
  {
    Registers regs = {};
    regs[0] = uint8_t(freq & 0xff);
    regs[1] = uint8_t(freq >> 8);
    regs[2] = uint8_t(pulseWidth & 0xff);
    regs[3] = uint8_t(pulseWidth >> 8);
    regs[4] = control;
    // zero attack and decay, max sustain
    regs[5] = 0x00;
    regs[6] = 0xf0;
    regs[0x18] = 0x0f;
    return regs;
  }

  // Register value for given tone frequency at PAL clock
  inline uint_t ToneRegister(double hz)
  {
    return uint_t(hz * 16777216.0 / 985248.0 + 0.5);
  }
}  // namespace SidTest
