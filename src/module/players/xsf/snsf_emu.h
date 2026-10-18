/**
 *
 * @file
 *
 * @brief  SNSF related stuff. SNES system emulation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "types.h"

#include "3rdparty/snesspc/snes_spc/SNES_SPC.h"

#include <array>
#include <vector>

namespace Module::SNSF
{
  struct Cartridge
  {
    struct SramBlock
    {
      uint32_t Offset = 0;
      std::vector<uint8_t> Data;
    };

    enum class VideoMode
    {
      AUTO,
      NTSC,
      PAL
    };

    enum class MemoryMode
    {
      AUTO,
      LOROM,
      HIROM
    };

    std::vector<uint8_t> Rom;
    std::vector<SramBlock> Sram;
    // SNSF spec default
    uint8_t SramFill = 0xff;
    VideoMode Video = VideoMode::AUTO;
    MemoryMode Memory = MemoryMode::AUTO;
  };

  // Console without video: 65816 CPU with its on-die registers (DMA, timers, math), memory map, WRAM and the
  // timing-relevant parts of PPU, and S-SMP/S-DSP via snes_spc. Sound engines that run their sequencer on
  // the main CPU (feeding the SPC700 with notes every frame) require this rather than a plain SPC snapshot.
  // Coprocessors (SA-1, SuperFX, S-DD1, DSP-n) are not emulated.
  class Emulator
  {
  public:
    explicit Emulator(const Cartridge& cart);

    void Reset();

    // Runs single video frame, appends produced stereo samples into target, returns samples count
    uint_t RunFrame(::SNES_SPC::sample_t* target, uint_t maxSamples);

    ::SNES_SPC& GetSpc()
    {
      return Spc;
    }

    bool IsPal() const
    {
      return Pal;
    }

    // S-SMP clock is derived from its own ceramic resonator, nominal 24.576MHz/24 but ~32040Hz sample rate
    // measured on real consoles (and used by snes9x), so the output stream has this rate in emulated time
    static constexpr uint_t SAMPLE_RATE = 32040;
    // samples count produced by single frame does not exceed this value
    static constexpr uint_t MAX_FRAME_SAMPLES = 1024;

  private:
    enum class Mapping
    {
      LoRom,
      HiRom,
      ExHiRom
    };
    void DetectMapping();
    void BuildMemoryMap();

    // bus
    uint_t Speed(uint_t addr) const;
    uint8_t Read(uint_t addr);
    void Write(uint_t addr, uint8_t data);
    uint8_t ReadSpecial(uint_t addr);
    void WriteSpecial(uint_t addr, uint8_t data);
    uint8_t ReadBBus(uint_t reg);
    void WriteBBus(uint_t reg, uint8_t data);
    uint8_t ReadCpuReg(uint_t reg);
    void WriteCpuReg(uint_t reg, uint8_t data);
    bool IsDmaAccessible(uint_t addr) const;
    void RunDma(uint8_t channels);

    // timing
    void Tick(uint_t clocks)
    {
      Clock += clocks;
    }
    void ProcessEvents();
    void StartLine();
    void UpdateNextEvent();
    uint_t GetLineClocks() const
    {
      return ShortLine && Line == 240 ? 1360 : 1364;
    }
    int SpcTime() const;

    // cpu
    void Step();
    void Interrupt(uint16_t nativeVector, uint16_t emulationVector);
    void Io()
    {
      Tick(6);
    }
    void IoDirect()
    {
      if (D & 0xff)
      {
        Io();
      }
    }
    uint8_t Fetch();
    uint16_t Fetch16();
    void Push(uint8_t val);
    uint8_t Pull();
    void Push16(uint16_t val);
    uint16_t Pull16();
    void SetP(uint8_t val);
    bool Wide() const
    {
      return !(P & 0x20);
    }
    bool WideIdx() const
    {
      return !(P & 0x10);
    }

    struct Address
    {
      uint_t Value;
      // direct page and stack relative operands wrap in bank 0
      bool Bank0;
    };
    Address Effective(uint_t mode, bool write);
    uint16_t ReadData(Address addr, bool wide);
    void WriteData(Address addr, uint16_t val, bool wide);
    void SetNZ(uint16_t val, bool wide);

    void Alu(uint_t op, uint16_t val);
    template<bool Wide>
    void Adc(uint16_t val);
    template<bool Wide>
    void Sbc(uint16_t val);
    void Compare(uint16_t reg, uint16_t val, bool wide);
    uint16_t Modify(uint_t op, uint16_t val, bool wide);
    void ModifyMemory(uint_t op, Address addr);
    void Branch(bool cond);

  private:
    const Cartridge& Cart;
    Mapping Map = Mapping::LoRom;
    bool Pal = false;
    uint_t SramMask = 0;
    std::vector<uint8_t> Sram;
    std::array<uint8_t, 0x20000> Wram{};

    // 8kb pages, nullptr for special handlers
    static constexpr uint_t PAGE_BITS = 13;
    static constexpr uint_t PAGES = 1 << (24 - PAGE_BITS);
    std::array<const uint8_t*, PAGES> ReadMap{};
    std::array<uint8_t*, PAGES> WriteMap{};

    // cpu registers
    uint16_t A = 0;
    uint16_t X = 0;
    uint16_t Y = 0;
    uint16_t S = 0;
    uint16_t D = 0;
    uint16_t PC = 0;
    uint8_t DB = 0;
    uint8_t PB = 0;
    uint8_t P = 0;
    bool E = true;
    bool Waiting = false;
    bool Stopped = false;
    uint8_t Mdr = 0;

    // on-die registers
    uint8_t NmiTimEn = 0;
    uint8_t MulA = 0xff;
    uint16_t DivA = 0xffff;
    uint16_t RdDiv = 0;
    uint16_t RdMpy = 0;
    uint16_t HTime = 0x1ff;
    uint16_t VTime = 0x1ff;
    uint_t RomSpeed = 8;
    bool NmiFlag = false;
    bool NmiPending = false;
    bool IrqFlag = false;
    std::array<std::array<uint8_t, 16>, 8> DmaRegs{};

    // ppu bits
    uint32_t WramAddr = 0;
    uint16_t M7A = 0;
    uint16_t M7B = 0;
    uint8_t M7Latch = 0;
    uint32_t M7Result = 0;
    uint16_t LatchedH = 0;
    uint16_t LatchedV = 0;
    bool CounterLatched = false;
    bool OphctFlip = false;
    bool OpvctFlip = false;

    // master clocks since frame start
    uint_t Clock = 0;
    uint_t Line = 0;
    uint_t LineStart = 0;
    uint_t NextEvent = 0;
    bool RefreshDone = false;
    bool IrqDone = false;
    uint_t LinesPerFrame = 262;
    bool OddField = false;
    bool ShortLine = false;
    uint_t VBlankLine = 225;

    // apu
    ::SNES_SPC Spc;
    uint_t MasterClockRate = 0;
    // master clock that matches zero time of current SPC frame and remainder of conversion
    uint_t SpcFrameStart = 0;
    uint64_t SpcFraction = 0;
  };
}  // namespace Module::SNSF
