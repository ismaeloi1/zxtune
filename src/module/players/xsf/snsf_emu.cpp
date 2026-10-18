/**
 *
 * @file
 *
 * @brief  SNSF related stuff. SNES system emulation implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "module/players/xsf/snsf_emu.h"

#include "contract.h"

#include <algorithm>

namespace Module::SNSF
{
  // Timings are in master clocks, see https://problemkaputt.de/fullsnes.htm
  const uint_t LINE_CLOCKS = 1364;
  // WRAM refresh stalls CPU at the same horizontal position each line
  const uint_t REFRESH_POS = 538;
  const uint_t REFRESH_CLOCKS = 40;
  const uint_t NTSC_MASTER_CLOCK = 21477272;
  const uint_t PAL_MASTER_CLOCK = 21281370;
  const uint_t SPC_CLOCK = Emulator::SAMPLE_RATE * ::SNES_SPC::clocks_per_sample;

  // S-SMP boot ROM, the same as in all the SPC players (required to upload sound driver from the main CPU)
  const uint8_t IPL_ROM[::SNES_SPC::rom_size] = {
      0xcd, 0xef, 0xbd, 0xe8, 0x00, 0xc6, 0x1d, 0xd0, 0xfc, 0x8f, 0xaa, 0xf4, 0x8f, 0xbb, 0xf5, 0x78,
      0xcc, 0xf4, 0xd0, 0xfb, 0x2f, 0x19, 0xeb, 0xf4, 0xd0, 0xfc, 0x7e, 0xf4, 0xd0, 0x0b, 0xe4, 0xf5,
      0xcb, 0xf4, 0xd7, 0x00, 0xfc, 0xd0, 0xf3, 0xab, 0x01, 0x10, 0xef, 0x7e, 0xf4, 0x10, 0xeb, 0xba,
      0xf6, 0xda, 0x00, 0xba, 0xf4, 0xc4, 0xf4, 0xdd, 0x5d, 0xd0, 0xdb, 0x1f, 0x00, 0x00, 0xc0, 0xff};

  // Addressing modes
  enum
  {
    DPX_IND,
    SR,
    DP,
    DPL_IND,
    IMM,
    ABS,
    LONG,
    DP_IND_Y,
    DP_IND,
    SR_IND_Y,
    DPX,
    DPL_IND_Y,
    ABSY,
    ABSX,
    LONGX,
    DPY,
    NONE
  };

  // Mode for opcodes of ORA/AND/EOR/ADC/STA/LDA/CMP/SBC group by low 5 bits
  const uint8_t ALU_MODES[32] = {NONE, DPX_IND,  NONE,   SR,       NONE, DP,   NONE, DPL_IND,    // 00..07
                                 NONE, IMM,      NONE,   NONE,     NONE, ABS,  NONE, LONG,       // 08..0f
                                 NONE, DP_IND_Y, DP_IND, SR_IND_Y, NONE, DPX,  NONE, DPL_IND_Y,  // 10..17
                                 NONE, ABSY,     NONE,   NONE,     NONE, ABSX, NONE, LONGX};     // 18..1f

  uint_t Mirror(uint_t addr, uint_t size)
  {
    // the same way as address lines of non power-of-2 sized ROMs are wired
    uint_t base = 0;
    uint_t mask = 1 << 23;
    while (addr >= size)
    {
      while (!(addr & mask))
      {
        mask >>= 1;
      }
      addr -= mask;
      if (size > mask)
      {
        size -= mask;
        base += mask;
      }
      mask >>= 1;
    }
    return base + addr;
  }

  Emulator::Emulator(const Cartridge& cart)
    : Cart(cart)
  {
    Require(!Cart.Rom.empty() && (Cart.Rom.size() % (1 << PAGE_BITS)) == 0);
    DetectMapping();
    Require(!Spc.init());
    Spc.init_rom(IPL_ROM);
    BuildMemoryMap();
    Reset();
  }

  void Emulator::DetectMapping()
  {
    const auto& rom = Cart.Rom;
    const auto score = [&rom](uint_t base, uint_t expectedMode) -> int {
      if (rom.size() < base + 0x40)
      {
        return -100;
      }
      const auto* hdr = rom.data() + base;
      int res = 0;
      const uint_t checksum = hdr[0x1e] | (hdr[0x1f] << 8);
      const uint_t complement = hdr[0x1c] | (hdr[0x1d] << 8);
      if ((checksum ^ complement) == 0xffff)
      {
        res += 2;
      }
      const uint_t mode = hdr[0x15] & 0xef;
      if ((mode & 0xe0) == 0x20 && (mode & 0x0f) == expectedMode)
      {
        res += 3;
      }
      const uint_t reset = hdr[0x3c] | (hdr[0x3d] << 8);
      res += reset >= 0x8000 ? 2 : -4;
      if (std::all_of(hdr, hdr + 21, [](uint8_t c) { return c >= 0x20 && c < 0x7f; }))
      {
        ++res;
      }
      return res;
    };
    const auto lo = score(0x7fc0, 0);
    const auto hi = score(0xffc0, 1);
    const auto exhi = rom.size() > 0x400000 ? score(0x40ffc0, 5) : -100;
    uint_t base = 0x7fc0;
    if (Cart.Memory != Cartridge::MemoryMode::AUTO)
    {
      Map = Cart.Memory == Cartridge::MemoryMode::HIROM ? Mapping::HiRom : Mapping::LoRom;
      base = Map == Mapping::HiRom ? 0xffc0 : 0x7fc0;
    }
    else if (exhi > lo && exhi >= hi)
    {
      Map = Mapping::ExHiRom;
      base = 0x40ffc0;
    }
    else if (hi > lo)
    {
      Map = Mapping::HiRom;
      base = 0xffc0;
    }
    else
    {
      Map = Mapping::LoRom;
    }
    uint_t sramSize = 0;
    uint_t country = 0;
    if (rom.size() >= base + 0x40)
    {
      const auto sramCode = rom[base + 0x18];
      sramSize = sramCode && sramCode <= 8 ? 1024 << sramCode : 0;
      country = rom[base + 0x19];
    }
    // as in snes9x
    Pal = Cart.Video == Cartridge::VideoMode::AUTO ? country >= 2 && country <= 12
                                                   : Cart.Video == Cartridge::VideoMode::PAL;
    for (const auto& block : Cart.Sram)
    {
      sramSize = std::max<uint_t>(sramSize, block.Offset + block.Data.size());
    }
    if (sramSize)
    {
      // emulate at least one memory map page, smaller chips mirroring is not reproduced
      uint_t size = 1 << PAGE_BITS;
      while (size < sramSize)
      {
        size <<= 1;
      }
      Sram.resize(size);
      SramMask = size - 1;
    }
  }

  void Emulator::BuildMemoryMap()
  {
    const auto romSize = static_cast<uint_t>(Cart.Rom.size());
    const auto romAt = [&](uint_t offset) { return Cart.Rom.data() + Mirror(offset, romSize); };
    for (uint_t page = 0; page < PAGES; ++page)
    {
      const auto addr = page << PAGE_BITS;
      const auto bank = addr >> 16;
      const auto offset = addr & 0xffff;
      const uint8_t* read = nullptr;
      uint8_t* write = nullptr;
      uint_t romOffset = 0;
      switch (Map)
      {
      case Mapping::LoRom:
        romOffset = ((bank & 0x7f) << 15) | (offset & 0x7fff);
        break;
      case Mapping::HiRom:
        romOffset = ((bank & 0x3f) << 16) | offset;
        break;
      case Mapping::ExHiRom:
        romOffset = (bank & 0x80 ? 0 : 0x400000) | ((bank & 0x3f) << 16) | offset;
        break;
      }
      if ((bank & 0xfe) == 0x7e)
      {
        write = Wram.data() + (addr & 0x1ffff);
        read = write;
      }
      else if (!(bank & 0x40))
      {
        if (offset < 0x2000)
        {
          write = Wram.data() + offset;
          read = write;
        }
        else if (offset >= 0x8000)
        {
          read = romAt(romOffset);
        }
        else if (offset >= 0x6000 && Map != Mapping::LoRom && (bank & 0x20) && !Sram.empty())
        {
          write = Sram.data() + ((((bank & 0x1f) << 13) | (offset - 0x6000)) & SramMask);
          read = write;
        }
      }
      else if (Map == Mapping::LoRom && (bank & 0x7f) >= 0x70 && offset < 0x8000 && !Sram.empty())
      {
        write = Sram.data() + ((((bank & 0x0f) << 15) | offset) & SramMask);
        read = write;
      }
      else
      {
        read = romAt(romOffset);
      }
      ReadMap[page] = read;
      WriteMap[page] = write;
    }
  }

  void Emulator::Reset()
  {
    // as in snes9x, real memory content is random
    Wram.fill(0x55);
    std::fill(Sram.begin(), Sram.end(), Cart.SramFill);
    for (const auto& block : Cart.Sram)
    {
      std::copy_n(block.Data.begin(), std::min<std::size_t>(block.Data.size(), Sram.size() - block.Offset),
                  Sram.begin() + block.Offset);
    }

    Spc.reset();
    MasterClockRate = Pal ? PAL_MASTER_CLOCK : NTSC_MASTER_CLOCK;
    SpcFrameStart = 0;
    SpcFraction = 0;

    NmiTimEn = 0;
    MulA = 0xff;
    DivA = 0xffff;
    RdDiv = RdMpy = 0;
    HTime = VTime = 0x1ff;
    RomSpeed = 8;
    NmiFlag = NmiPending = IrqFlag = false;
    for (auto& regs : DmaRegs)
    {
      regs.fill(0xff);
    }
    WramAddr = 0;
    M7A = M7B = 0;
    M7Latch = 0;
    M7Result = 0;
    LatchedH = LatchedV = 0;
    CounterLatched = OphctFlip = OpvctFlip = false;

    Clock = 0;
    Line = 0;
    LineStart = 0;
    RefreshDone = IrqDone = false;
    LinesPerFrame = Pal ? 312 : 262;
    OddField = false;
    VBlankLine = 225;
    UpdateNextEvent();

    A = X = Y = 0;
    D = 0;
    DB = PB = 0;
    S = 0x01ff;
    E = true;
    P = 0x34;
    Waiting = Stopped = false;
    Mdr = 0;
    PC = Read(0xfffc);
    PC |= Read(0xfffd) << 8;
  }

  uint_t Emulator::RunFrame(::SNES_SPC::sample_t* target, uint_t maxSamples)
  {
    Spc.set_output(target, static_cast<int>(maxSamples * 2));
    // NTSC non-interlaced odd frames have one dot shorter line 240. Unlike snes9x, PAL frames are not affected (as in
    // bsnes), so PAL tempo differs by ~5ppm.
    ShortLine = OddField && !Pal;
    const auto frameClocks = LinesPerFrame * LINE_CLOCKS - (ShortLine ? 4 : 0);
    while (Clock < frameClocks)
    {
      if (Waiting && (NmiPending || IrqFlag))
      {
        Waiting = false;
      }
      if (Waiting || Stopped)
      {
        // fast forward till the next event
        Clock = std::min(std::max(Clock, NextEvent), frameClocks);
      }
      else
      {
        Step();
      }
      ProcessEvents();
    }
    const auto total = uint64_t(Clock - SpcFrameStart) * SPC_CLOCK + SpcFraction;
    const auto spcClocks = total / MasterClockRate;
    Spc.end_frame(static_cast<int>(spcClocks));
    SpcFraction = total - spcClocks * MasterClockRate;
    // SPC frame ends exactly at current clock, so the next one starts here
    Clock -= frameClocks;
    SpcFrameStart = Clock;
    Line = 0;
    LineStart = 0;
    RefreshDone = IrqDone = false;
    NmiFlag = false;
    OddField = !OddField;
    ShortLine = false;
    UpdateNextEvent();
    ProcessEvents();
    return static_cast<uint_t>(Spc.sample_count() / 2);
  }

  int Emulator::SpcTime() const
  {
    return static_cast<int>((uint64_t(Clock - SpcFrameStart) * SPC_CLOCK + SpcFraction) / MasterClockRate);
  }

  void Emulator::UpdateNextEvent()
  {
    uint_t next = ~uint_t(0);
    if (Line + 1 < LinesPerFrame)
    {
      next = LineStart + GetLineClocks();
    }
    if (!RefreshDone)
    {
      next = std::min(next, LineStart + REFRESH_POS);
    }
    if (!IrqDone)
    {
      const auto mode = (NmiTimEn >> 4) & 3;
      if (mode == 2 && Line == VTime)
      {
        next = std::min(next, LineStart + 10);
      }
      else if ((mode == 1 || (mode == 3 && Line == VTime)) && HTime < 340)
      {
        next = std::min(next, LineStart + HTime * 4 + 14);
      }
    }
    NextEvent = next;
  }

  void Emulator::ProcessEvents()
  {
    while (Clock >= NextEvent)
    {
      if (NextEvent == LineStart + GetLineClocks())
      {
        StartLine();
      }
      else if (!RefreshDone && NextEvent == LineStart + REFRESH_POS)
      {
        RefreshDone = true;
        Tick(REFRESH_CLOCKS);
      }
      else
      {
        IrqDone = true;
        IrqFlag = true;
      }
      UpdateNextEvent();
    }
  }

  void Emulator::StartLine()
  {
    LineStart += GetLineClocks();
    ++Line;
    RefreshDone = IrqDone = false;
    if (Line == VBlankLine)
    {
      NmiFlag = true;
      if (NmiTimEn & 0x80)
      {
        NmiPending = true;
      }
    }
  }

  uint_t Emulator::Speed(uint_t addr) const
  {
    if (addr & 0x408000)
    {
      return addr & 0x800000 ? RomSpeed : 8;
    }
    if ((addr + 0x6000) & 0x4000)
    {
      return 8;
    }
    if ((addr - 0x4000) & 0x7e00)
    {
      return 6;
    }
    return 12;
  }

  uint8_t Emulator::Read(uint_t addr)
  {
    Tick(Speed(addr));
    if (const auto* page = ReadMap[addr >> PAGE_BITS])
    {
      return Mdr = page[addr & ((1 << PAGE_BITS) - 1)];
    }
    return Mdr = ReadSpecial(addr);
  }

  void Emulator::Write(uint_t addr, uint8_t data)
  {
    Tick(Speed(addr));
    Mdr = data;
    if (auto* page = WriteMap[addr >> PAGE_BITS])
    {
      page[addr & ((1 << PAGE_BITS) - 1)] = data;
    }
    else if (!ReadMap[addr >> PAGE_BITS])
    {
      WriteSpecial(addr, data);
    }
  }

  uint8_t Emulator::ReadSpecial(uint_t addr)
  {
    const auto offset = addr & 0xffff;
    if ((offset & 0xff00) == 0x2100)
    {
      return ReadBBus(offset & 0xff);
    }
    else if (offset == 0x4016 || offset == 0x4017)
    {
      // no controllers connected
      return (Mdr & 0xe0) | (offset == 0x4017 ? 0x1c : 0);
    }
    else if (offset >= 0x4200 && offset < 0x4400)
    {
      return ReadCpuReg(offset);
    }
    return Mdr;
  }

  void Emulator::WriteSpecial(uint_t addr, uint8_t data)
  {
    const auto offset = addr & 0xffff;
    if ((offset & 0xff00) == 0x2100)
    {
      WriteBBus(offset & 0xff, data);
    }
    else if (offset >= 0x4200 && offset < 0x4400)
    {
      WriteCpuReg(offset, data);
    }
  }

  uint8_t Emulator::ReadBBus(uint_t reg)
  {
    switch (reg)
    {
    case 0x34:
    case 0x35:
    case 0x36:
      return static_cast<uint8_t>(M7Result >> (8 * (reg - 0x34)));
    case 0x37:
      ProcessEvents();
      LatchedH = static_cast<uint16_t>((Clock - LineStart) / 4);
      LatchedV = static_cast<uint16_t>(Line);
      CounterLatched = true;
      return Mdr;
    case 0x3c:
      OphctFlip = !OphctFlip;
      return OphctFlip ? static_cast<uint8_t>(LatchedH) : static_cast<uint8_t>((LatchedH >> 8) | (Mdr & 0xfe));
    case 0x3d:
      OpvctFlip = !OpvctFlip;
      return OpvctFlip ? static_cast<uint8_t>(LatchedV) : static_cast<uint8_t>((LatchedV >> 8) | (Mdr & 0xfe));
    case 0x3e:
      return (Mdr & 0x10) | 0x01;
    case 0x3f:
    {
      const uint8_t res = (CounterLatched ? 0x40 : 0) | (Pal ? 0x10 : 0) | 0x03 | (Mdr & 0x20);
      CounterLatched = OphctFlip = OpvctFlip = false;
      return res;
    }
    case 0x80:
    {
      const auto res = Wram[WramAddr];
      WramAddr = (WramAddr + 1) & 0x1ffff;
      return res;
    }
    default:
      if (reg >= 0x40 && reg < 0x80)
      {
        return static_cast<uint8_t>(Spc.read_port(SpcTime(), reg & 3));
      }
      return Mdr;
    }
  }

  void Emulator::WriteBBus(uint_t reg, uint8_t data)
  {
    switch (reg)
    {
    case 0x1b:
    case 0x1c:
    {
      // mode7 matrix registers are used as signed multiplier by some code
      const uint16_t val = (data << 8) | M7Latch;
      M7Latch = data;
      (reg == 0x1b ? M7A : M7B) = val;
      M7Result = static_cast<uint32_t>(int32_t(int16_t(M7A)) * int8_t(M7B >> 8)) & 0xffffff;
      break;
    }
    case 0x1d:
    case 0x1e:
    case 0x1f:
    case 0x20:
      M7Latch = data;
      break;
    case 0x80:
      Wram[WramAddr] = data;
      WramAddr = (WramAddr + 1) & 0x1ffff;
      break;
    case 0x81:
      WramAddr = (WramAddr & 0x1ff00) | data;
      break;
    case 0x82:
      WramAddr = (WramAddr & 0x100ff) | (data << 8);
      break;
    case 0x83:
      WramAddr = (WramAddr & 0x0ffff) | ((data & 1) << 16);
      break;
    default:
      if (reg >= 0x40 && reg < 0x80)
      {
        Spc.write_port(SpcTime(), reg & 3, data);
      }
      break;
    }
  }

  uint8_t Emulator::ReadCpuReg(uint_t reg)
  {
    switch (reg)
    {
    case 0x4210:
    {
      ProcessEvents();
      const uint8_t res = (NmiFlag ? 0x80 : 0) | (Mdr & 0x70) | 0x02;
      NmiFlag = false;
      return res;
    }
    case 0x4211:
    {
      ProcessEvents();
      const uint8_t res = (IrqFlag ? 0x80 : 0) | (Mdr & 0x7f);
      IrqFlag = false;
      return res;
    }
    case 0x4212:
    {
      ProcessEvents();
      const auto h = Clock - LineStart;
      const bool vblank = Line >= VBlankLine;
      const bool hblank = h <= 2 || h >= 1096;
      const bool joypad = (NmiTimEn & 1) && Line >= VBlankLine && Line < VBlankLine + 3;
      return (vblank ? 0x80 : 0) | (hblank ? 0x40 : 0) | (joypad ? 0x01 : 0) | (Mdr & 0x3e);
    }
    case 0x4213:
      return 0xff;
    case 0x4214:
      return static_cast<uint8_t>(RdDiv);
    case 0x4215:
      return static_cast<uint8_t>(RdDiv >> 8);
    case 0x4216:
      return static_cast<uint8_t>(RdMpy);
    case 0x4217:
      return static_cast<uint8_t>(RdMpy >> 8);
    default:
      if (reg >= 0x4218 && reg < 0x4220)
      {
        return 0;
      }
      if (reg >= 0x4300 && reg < 0x4380)
      {
        const auto idx = reg & 0x0f;
        if (idx < 0x0c || idx == 0x0f)
        {
          return DmaRegs[(reg >> 4) & 7][idx == 0x0f ? 0x0b : idx];
        }
      }
      return Mdr;
    }
  }

  void Emulator::WriteCpuReg(uint_t reg, uint8_t data)
  {
    switch (reg)
    {
    case 0x4200:
      if ((data & 0x80) && !(NmiTimEn & 0x80) && NmiFlag)
      {
        NmiPending = true;
      }
      NmiTimEn = data;
      if (!(data & 0x30))
      {
        IrqFlag = false;
      }
      UpdateNextEvent();
      break;
    case 0x4202:
      MulA = data;
      break;
    case 0x4203:
      // real hardware provides result in 8 cycles, nobody relies on reading it earlier
      RdMpy = MulA * data;
      RdDiv = data;
      break;
    case 0x4204:
      DivA = (DivA & 0xff00) | data;
      break;
    case 0x4205:
      DivA = (DivA & 0x00ff) | (data << 8);
      break;
    case 0x4206:
      if (data)
      {
        RdDiv = DivA / data;
        RdMpy = DivA % data;
      }
      else
      {
        RdDiv = 0xffff;
        RdMpy = DivA;
      }
      break;
    case 0x4207:
      HTime = (HTime & 0x100) | data;
      UpdateNextEvent();
      break;
    case 0x4208:
      HTime = (HTime & 0xff) | ((data & 1) << 8);
      UpdateNextEvent();
      break;
    case 0x4209:
      VTime = (VTime & 0x100) | data;
      UpdateNextEvent();
      break;
    case 0x420a:
      VTime = (VTime & 0xff) | ((data & 1) << 8);
      UpdateNextEvent();
      break;
    case 0x420b:
      RunDma(data);
      break;
    case 0x420d:
      RomSpeed = data & 1 ? 6 : 8;
      break;
    default:
      if (reg >= 0x4300 && reg < 0x4380)
      {
        const auto idx = reg & 0x0f;
        if (idx < 0x0c || idx == 0x0f)
        {
          DmaRegs[(reg >> 4) & 7][idx == 0x0f ? 0x0b : idx] = data;
        }
      }
      break;
    }
  }

  bool Emulator::IsDmaAccessible(uint_t addr) const
  {
    // A-bus cannot access B-bus and on-die registers
    if (addr & 0x400000)
    {
      return true;
    }
    const auto offset = addr & 0xffff;
    return (offset & 0xff00) != 0x2100 && (offset < 0x4000 || offset >= 0x4400);
  }

  // HDMA is not emulated: it only feeds PPU registers, stealing a few cycles per scanline
  void Emulator::RunDma(uint8_t channels)
  {
    static const uint8_t OFFSETS[8][4] = {{0, 0, 0, 0}, {0, 1, 0, 1}, {0, 0, 0, 0}, {0, 0, 1, 1},
                                          {0, 1, 2, 3}, {0, 1, 0, 1}, {0, 0, 0, 0}, {0, 0, 1, 1}};
    if (!channels)
    {
      return;
    }
    Tick(12);
    for (uint_t chan = 0; chan < 8; ++chan)
    {
      if (!(channels & (1 << chan)))
      {
        continue;
      }
      auto& regs = DmaRegs[chan];
      Tick(8);
      const auto params = regs[0];
      const auto* offsets = OFFSETS[params & 7];
      uint_t aaddr = regs[2] | (regs[3] << 8);
      const uint_t abank = regs[4] << 16;
      uint_t count = regs[5] | (regs[6] << 8);
      for (uint_t idx = 0;; ++idx)
      {
        Tick(8);
        const auto areg = abank | aaddr;
        const auto breg = (regs[1] + offsets[idx & 3]) & 0xff;
        if (params & 0x80)
        {
          const auto data = ReadBBus(breg);
          if (IsDmaAccessible(areg))
          {
            if (auto* page = WriteMap[areg >> PAGE_BITS])
            {
              page[areg & ((1 << PAGE_BITS) - 1)] = data;
            }
          }
        }
        else
        {
          uint8_t data = Mdr;
          if (IsDmaAccessible(areg))
          {
            if (const auto* page = ReadMap[areg >> PAGE_BITS])
            {
              data = page[areg & ((1 << PAGE_BITS) - 1)];
            }
          }
          WriteBBus(breg, data);
        }
        if (!(params & 0x08))
        {
          aaddr = (aaddr + (params & 0x10 ? 0xffff : 1)) & 0xffff;
        }
        count = (count - 1) & 0xffff;
        if (!count)
        {
          break;
        }
      }
      regs[2] = static_cast<uint8_t>(aaddr);
      regs[3] = static_cast<uint8_t>(aaddr >> 8);
      regs[5] = regs[6] = 0;
    }
  }

  uint8_t Emulator::Fetch()
  {
    const auto res = Read((PB << 16) | PC);
    PC = PC + 1;
    return res;
  }

  uint16_t Emulator::Fetch16()
  {
    const uint16_t lo = Fetch();
    return lo | (Fetch() << 8);
  }

  void Emulator::Push(uint8_t val)
  {
    Write(S, val);
    S = E ? 0x100 | ((S - 1) & 0xff) : S - 1;
  }

  uint8_t Emulator::Pull()
  {
    S = E ? 0x100 | ((S + 1) & 0xff) : S + 1;
    return Read(S);
  }

  void Emulator::Push16(uint16_t val)
  {
    Push(val >> 8);
    Push(val & 0xff);
  }

  uint16_t Emulator::Pull16()
  {
    const uint16_t lo = Pull();
    return lo | (Pull() << 8);
  }

  void Emulator::SetP(uint8_t val)
  {
    P = E ? val | 0x30 : val;
    if (P & 0x10)
    {
      X &= 0xff;
      Y &= 0xff;
    }
  }

  void Emulator::SetNZ(uint16_t val, bool wide)
  {
    P &= ~0x82;
    if (wide)
    {
      P |= (val ? 0 : 0x02) | ((val >> 8) & 0x80);
    }
    else
    {
      P |= ((val & 0xff) ? 0 : 0x02) | (val & 0x80);
    }
  }

  void Emulator::Interrupt(uint16_t nativeVector, uint16_t emulationVector)
  {
    Io();
    Io();
    if (!E)
    {
      Push(PB);
    }
    Push16(PC);
    Push(E ? P & ~0x10 : P);
    P = (P | 0x04) & ~0x08;
    PB = 0;
    const auto vec = E ? emulationVector : nativeVector;
    PC = Read(vec);
    PC |= Read(vec + 1) << 8;
  }

  Emulator::Address Emulator::Effective(uint_t mode, bool write)
  {
    const auto indexed = [this, write](uint_t base, uint_t idx) -> uint_t {
      const auto res = (base + idx) & 0xffffff;
      if (write || WideIdx() || ((base ^ res) & 0xff00))
      {
        Io();
      }
      return res;
    };
    const auto pointer = [this](uint_t addr) -> uint_t {
      const uint_t lo = Read(addr);
      return lo | (Read((addr + 1) & 0xffff) << 8);
    };
    switch (mode)
    {
    case DP:
    {
      const auto dp = Fetch();
      IoDirect();
      return {(D + dp) & 0xffffu, true};
    }
    case DPX:
    case DPY:
    {
      const auto dp = Fetch();
      IoDirect();
      Io();
      return {(D + dp + (mode == DPX ? X : Y)) & 0xffffu, true};
    }
    case DPX_IND:
    {
      const auto dp = Fetch();
      IoDirect();
      Io();
      return {(uint_t(DB) << 16) | pointer((D + dp + X) & 0xffff), false};
    }
    case DP_IND:
    {
      const auto dp = Fetch();
      IoDirect();
      return {(uint_t(DB) << 16) | pointer((D + dp) & 0xffff), false};
    }
    case DP_IND_Y:
    {
      const auto dp = Fetch();
      IoDirect();
      return {indexed((uint_t(DB) << 16) | pointer((D + dp) & 0xffff), Y), false};
    }
    case DPL_IND:
    case DPL_IND_Y:
    {
      const auto dp = Fetch();
      IoDirect();
      const auto ptr = (D + dp) & 0xffff;
      auto addr = pointer(ptr);
      addr |= Read((ptr + 2) & 0xffff) << 16;
      return {mode == DPL_IND ? addr : ((addr + Y) & 0xffffff), false};
    }
    case ABS:
      return {(uint_t(DB) << 16) | Fetch16(), false};
    case ABSX:
    case ABSY:
      return {indexed((uint_t(DB) << 16) | Fetch16(), mode == ABSX ? X : Y), false};
    case LONG:
    case LONGX:
    {
      uint_t addr = Fetch16();
      addr |= Fetch() << 16;
      return {mode == LONG ? addr : ((addr + X) & 0xffffff), false};
    }
    case SR:
    {
      const auto off = Fetch();
      Io();
      return {(S + off) & 0xffffu, true};
    }
    case SR_IND_Y:
    {
      const auto off = Fetch();
      Io();
      const auto ptr = pointer((S + off) & 0xffff);
      Io();
      return {((uint_t(DB) << 16) + ptr + Y) & 0xffffff, false};
    }
    default:
      Require(false);
      return {0, false};
    }
  }

  uint16_t Emulator::ReadData(Address addr, bool wide)
  {
    const uint16_t lo = Read(addr.Value);
    if (!wide)
    {
      return lo;
    }
    const auto next = (addr.Value + 1) & (addr.Bank0 ? 0xffff : 0xffffff);
    return lo | (Read(next) << 8);
  }

  void Emulator::WriteData(Address addr, uint16_t val, bool wide)
  {
    Write(addr.Value, val & 0xff);
    if (wide)
    {
      Write((addr.Value + 1) & (addr.Bank0 ? 0xffff : 0xffffff), val >> 8);
    }
  }

  template<>
  void Emulator::Adc<false>(uint16_t data)
  {
    const uint_t a = A & 0xff;
    const uint_t c = P & 1;
    int res = 0;
    if (!(P & 0x08))
    {
      res = a + data + c;
    }
    else
    {
      res = (a & 0x0f) + (data & 0x0f) + c;
      if (res > 0x09)
      {
        res += 0x06;
      }
      const uint_t carry = res > 0x0f;
      res = (a & 0xf0) + (data & 0xf0) + (carry << 4) + (res & 0x0f);
    }
    P &= ~0x41;
    if (~(a ^ data) & (a ^ res) & 0x80)
    {
      P |= 0x40;
    }
    if ((P & 0x08) && res > 0x9f)
    {
      res += 0x60;
    }
    if (res > 0xff)
    {
      P |= 1;
    }
    A = (A & 0xff00) | (res & 0xff);
    SetNZ(A, false);
  }

  template<>
  void Emulator::Adc<true>(uint16_t data)
  {
    const uint_t a = A;
    uint_t c = P & 1;
    int res = 0;
    if (!(P & 0x08))
    {
      res = a + data + c;
    }
    else
    {
      res = (a & 0x000f) + (data & 0x000f) + c;
      if (res > 0x0009)
      {
        res += 0x0006;
      }
      c = res > 0x000f;
      res = (a & 0x00f0) + (data & 0x00f0) + (c << 4) + (res & 0x000f);
      if (res > 0x009f)
      {
        res += 0x0060;
      }
      c = res > 0x00ff;
      res = (a & 0x0f00) + (data & 0x0f00) + (c << 8) + (res & 0x00ff);
      if (res > 0x09ff)
      {
        res += 0x0600;
      }
      c = res > 0x0fff;
      res = (a & 0xf000) + (data & 0xf000) + (c << 12) + (res & 0x0fff);
    }
    P &= ~0x41;
    if (~(a ^ data) & (a ^ res) & 0x8000)
    {
      P |= 0x40;
    }
    if ((P & 0x08) && res > 0x9fff)
    {
      res += 0x6000;
    }
    if (res > 0xffff)
    {
      P |= 1;
    }
    A = static_cast<uint16_t>(res);
    SetNZ(A, true);
  }

  template<>
  void Emulator::Sbc<false>(uint16_t val)
  {
    const uint_t a = A & 0xff;
    const uint_t data = ~val & 0xff;
    const uint_t c = P & 1;
    int res = 0;
    if (!(P & 0x08))
    {
      res = a + data + c;
    }
    else
    {
      res = (a & 0x0f) + (data & 0x0f) + c;
      if (res <= 0x0f)
      {
        res -= 0x06;
      }
      const uint_t carry = res > 0x0f;
      res = (a & 0xf0) + (data & 0xf0) + (carry << 4) + (res & 0x0f);
    }
    P &= ~0x41;
    if (~(a ^ data) & (a ^ res) & 0x80)
    {
      P |= 0x40;
    }
    if ((P & 0x08) && res <= 0xff)
    {
      res -= 0x60;
    }
    if (res > 0xff)
    {
      P |= 1;
    }
    A = (A & 0xff00) | (res & 0xff);
    SetNZ(A, false);
  }

  template<>
  void Emulator::Sbc<true>(uint16_t val)
  {
    const uint_t a = A;
    const uint_t data = ~val & 0xffff;
    uint_t c = P & 1;
    int res = 0;
    if (!(P & 0x08))
    {
      res = a + data + c;
    }
    else
    {
      res = (a & 0x000f) + (data & 0x000f) + c;
      if (res <= 0x000f)
      {
        res -= 0x0006;
      }
      c = res > 0x000f;
      res = (a & 0x00f0) + (data & 0x00f0) + (c << 4) + (res & 0x000f);
      if (res <= 0x00ff)
      {
        res -= 0x0060;
      }
      c = res > 0x00ff;
      res = (a & 0x0f00) + (data & 0x0f00) + (c << 8) + (res & 0x00ff);
      if (res <= 0x0fff)
      {
        res -= 0x0600;
      }
      c = res > 0x0fff;
      res = (a & 0xf000) + (data & 0xf000) + (c << 12) + (res & 0x0fff);
    }
    P &= ~0x41;
    if (~(a ^ data) & (a ^ res) & 0x8000)
    {
      P |= 0x40;
    }
    if ((P & 0x08) && res <= 0xffff)
    {
      res -= 0x6000;
    }
    if (res > 0xffff)
    {
      P |= 1;
    }
    A = static_cast<uint16_t>(res);
    SetNZ(A, true);
  }

  void Emulator::Compare(uint16_t reg, uint16_t val, bool wide)
  {
    const uint_t mask = wide ? 0xffff : 0xff;
    const uint_t r = reg & mask;
    const uint_t v = val & mask;
    P = (P & ~1) | (r >= v ? 1 : 0);
    SetNZ(static_cast<uint16_t>((r - v) & mask), wide);
  }

  void Emulator::Alu(uint_t op, uint16_t val)
  {
    const auto wide = Wide();
    switch (op >> 5)
    {
    case 0:
      A = wide ? A | val : A | (val & 0xff);
      SetNZ(A, wide);
      break;
    case 1:
      A = wide ? A & val : A & (val | 0xff00);
      SetNZ(A, wide);
      break;
    case 2:
      A = wide ? A ^ val : A ^ (val & 0xff);
      SetNZ(A, wide);
      break;
    case 3:
      wide ? Adc<true>(val) : Adc<false>(val);
      break;
    case 5:
      A = wide ? val : (A & 0xff00) | (val & 0xff);
      SetNZ(A, wide);
      break;
    case 6:
      Compare(A, val, wide);
      break;
    case 7:
      wide ? Sbc<true>(val) : Sbc<false>(val);
      break;
    }
  }

  // op is opcode with addressing bits masked out
  uint16_t Emulator::Modify(uint_t op, uint16_t val, bool wide)
  {
    const uint_t mask = wide ? 0xffff : 0xff;
    const uint_t sign = wide ? 0x8000 : 0x80;
    const uint_t carry = P & 1;
    uint_t res = val;
    switch (op)
    {
    case 0x00:  // ASL
      P = (P & ~1) | ((val & sign) ? 1 : 0);
      res = val << 1;
      break;
    case 0x20:  // ROL
      P = (P & ~1) | ((val & sign) ? 1 : 0);
      res = (val << 1) | carry;
      break;
    case 0x40:  // LSR
      P = (P & ~1) | (val & 1);
      res = val >> 1;
      break;
    case 0x60:  // ROR
      P = (P & ~1) | (val & 1);
      res = (val >> 1) | (carry ? sign : 0);
      break;
    case 0xc0:  // DEC
      res = val - 1;
      break;
    case 0xe0:  // INC
      res = val + 1;
      break;
    }
    res &= mask;
    SetNZ(static_cast<uint16_t>(res), wide);
    return static_cast<uint16_t>(res);
  }

  void Emulator::ModifyMemory(uint_t op, Address addr)
  {
    const auto wide = Wide();
    const auto val = ReadData(addr, wide);
    Io();
    uint16_t res = 0;
    if (op == 0x04 || op == 0x14)
    {
      // TSB/TRB
      P = (P & ~0x02) | ((val & A & (wide ? 0xffff : 0xff)) ? 0 : 0x02);
      res = op == 0x04 ? val | A : val & ~A;
    }
    else
    {
      res = Modify(op & 0xe0, val, wide);
    }
    // high byte is written first
    if (wide)
    {
      Write((addr.Value + 1) & (addr.Bank0 ? 0xffff : 0xffffff), res >> 8);
    }
    Write(addr.Value, res & 0xff);
  }

  void Emulator::Branch(bool cond)
  {
    const auto off = static_cast<int8_t>(Fetch());
    if (cond)
    {
      Io();
      const uint16_t dst = PC + off;
      if (E && ((dst ^ PC) & 0xff00))
      {
        Io();
      }
      PC = dst;
    }
  }

  void Emulator::Step()
  {
    if (NmiPending)
    {
      NmiPending = false;
      Interrupt(0xffea, 0xfffa);
      return;
    }
    if (IrqFlag && (NmiTimEn & 0x30) && !(P & 0x04))
    {
      Interrupt(0xffee, 0xfffe);
      return;
    }
    const uint_t op = Fetch();
    const auto wide = Wide();
    const auto wideIdx = WideIdx();
    if (const auto mode = ALU_MODES[op & 0x1f]; mode != NONE && op != 0x89)
    {
      if ((op >> 5) == 4)
      {
        // STA
        WriteData(Effective(mode, true), A, wide);
      }
      else if (mode == IMM)
      {
        Alu(op, wide ? Fetch16() : Fetch());
      }
      else
      {
        Alu(op, ReadData(Effective(mode, false), wide));
      }
      return;
    }
    const auto readIdx = [&](uint_t mode) -> uint16_t {
      return mode == IMM ? (wideIdx ? Fetch16() : Fetch()) : ReadData(Effective(mode, false), wideIdx);
    };
    const auto load = [&](uint16_t& reg, uint_t mode) {
      reg = readIdx(mode);
      SetNZ(reg, wideIdx);
    };
    const auto transfer = [&](uint16_t& dst, uint16_t src, bool w) {
      Io();
      dst = w ? src : (dst & 0xff00) | (src & 0xff);
      SetNZ(dst, w);
    };
    const auto bit = [&](uint16_t val, bool imm) {
      const uint_t mask = wide ? 0xffff : 0xff;
      P = (P & ~0x02) | ((A & val & mask) ? 0 : 0x02);
      if (!imm)
      {
        const uint_t top = wide ? val >> 8 : val;
        P = (P & ~0xc0) | (top & 0xc0);
      }
    };
    switch (op)
    {
    // RMW
    case 0x06:
    case 0x26:
    case 0x46:
    case 0x66:
    case 0xc6:
    case 0xe6:
    case 0x04:
    case 0x14:
      ModifyMemory(op & 0xe0 ? op & 0xe0 : op, Effective(DP, true));
      break;
    case 0x0e:
    case 0x2e:
    case 0x4e:
    case 0x6e:
    case 0xce:
    case 0xee:
      ModifyMemory(op & 0xe0, Effective(ABS, true));
      break;
    case 0x0c:
    case 0x1c:
      ModifyMemory(op == 0x0c ? 0x04 : 0x14, Effective(ABS, true));
      break;
    case 0x16:
    case 0x36:
    case 0x56:
    case 0x76:
    case 0xd6:
    case 0xf6:
      ModifyMemory(op & 0xe0, Effective(DPX, true));
      break;
    case 0x1e:
    case 0x3e:
    case 0x5e:
    case 0x7e:
    case 0xde:
    case 0xfe:
      ModifyMemory(op & 0xe0, Effective(ABSX, true));
      break;
    case 0x0a:
    case 0x2a:
    case 0x4a:
    case 0x6a:
    {
      Io();
      const auto res = Modify(op & 0xe0, wide ? A : A & 0xff, wide);
      A = wide ? res : (A & 0xff00) | res;
      break;
    }
    case 0x1a:
    case 0x3a:
    {
      Io();
      const auto res = Modify(op == 0x1a ? 0xe0 : 0xc0, wide ? A : A & 0xff, wide);
      A = wide ? res : (A & 0xff00) | res;
      break;
    }
    // index registers
    case 0xa2:
      load(X, IMM);
      break;
    case 0xa6:
      load(X, DP);
      break;
    case 0xae:
      load(X, ABS);
      break;
    case 0xb6:
      load(X, DPY);
      break;
    case 0xbe:
      load(X, ABSY);
      break;
    case 0xa0:
      load(Y, IMM);
      break;
    case 0xa4:
      load(Y, DP);
      break;
    case 0xac:
      load(Y, ABS);
      break;
    case 0xb4:
      load(Y, DPX);
      break;
    case 0xbc:
      load(Y, ABSX);
      break;
    case 0x86:
      WriteData(Effective(DP, true), X, wideIdx);
      break;
    case 0x8e:
      WriteData(Effective(ABS, true), X, wideIdx);
      break;
    case 0x96:
      WriteData(Effective(DPY, true), X, wideIdx);
      break;
    case 0x84:
      WriteData(Effective(DP, true), Y, wideIdx);
      break;
    case 0x8c:
      WriteData(Effective(ABS, true), Y, wideIdx);
      break;
    case 0x94:
      WriteData(Effective(DPX, true), Y, wideIdx);
      break;
    case 0x64:
      WriteData(Effective(DP, true), 0, wide);
      break;
    case 0x74:
      WriteData(Effective(DPX, true), 0, wide);
      break;
    case 0x9c:
      WriteData(Effective(ABS, true), 0, wide);
      break;
    case 0x9e:
      WriteData(Effective(ABSX, true), 0, wide);
      break;
    case 0xe0:
      Compare(X, readIdx(IMM), wideIdx);
      break;
    case 0xe4:
      Compare(X, readIdx(DP), wideIdx);
      break;
    case 0xec:
      Compare(X, readIdx(ABS), wideIdx);
      break;
    case 0xc0:
      Compare(Y, readIdx(IMM), wideIdx);
      break;
    case 0xc4:
      Compare(Y, readIdx(DP), wideIdx);
      break;
    case 0xcc:
      Compare(Y, readIdx(ABS), wideIdx);
      break;
    // BIT
    case 0x89:
      bit(wide ? Fetch16() : Fetch(), true);
      break;
    case 0x24:
      bit(ReadData(Effective(DP, false), wide), false);
      break;
    case 0x2c:
      bit(ReadData(Effective(ABS, false), wide), false);
      break;
    case 0x34:
      bit(ReadData(Effective(DPX, false), wide), false);
      break;
    case 0x3c:
      bit(ReadData(Effective(ABSX, false), wide), false);
      break;
    // branches
    case 0x10:
      Branch(!(P & 0x80));
      break;
    case 0x30:
      Branch(P & 0x80);
      break;
    case 0x50:
      Branch(!(P & 0x40));
      break;
    case 0x70:
      Branch(P & 0x40);
      break;
    case 0x90:
      Branch(!(P & 0x01));
      break;
    case 0xb0:
      Branch(P & 0x01);
      break;
    case 0xd0:
      Branch(!(P & 0x02));
      break;
    case 0xf0:
      Branch(P & 0x02);
      break;
    case 0x80:
      Branch(true);
      break;
    case 0x82:
    {
      const auto off = Fetch16();
      Io();
      PC += off;
      break;
    }
    // flags
    case 0x18:
    case 0x38:
    case 0x58:
    case 0x78:
    case 0xb8:
    case 0xd8:
    case 0xf8:
    {
      static const uint8_t FLAGS[8] = {0x01, 0x01, 0x04, 0x04, 0, 0x40, 0x08, 0x08};
      Io();
      const auto flag = FLAGS[op >> 5];
      P = op == 0xb8 || !(op & 0x20) ? P & ~flag : P | flag;
      break;
    }
    case 0xc2:
    case 0xe2:
    {
      const auto val = Fetch();
      Io();
      SetP(op == 0xc2 ? P & ~val : P | val);
      break;
    }
    case 0xfb:
    {
      Io();
      const bool carry = P & 1;
      P = (P & ~1) | (E ? 1 : 0);
      E = carry;
      if (E)
      {
        SetP(P | 0x30);
        S = 0x100 | (S & 0xff);
      }
      break;
    }
    // transfers
    case 0xaa:
      transfer(X, A, wideIdx);
      break;
    case 0xa8:
      transfer(Y, A, wideIdx);
      break;
    case 0x8a:
      transfer(A, X, wide);
      break;
    case 0x98:
      transfer(A, Y, wide);
      break;
    case 0xba:
      transfer(X, S, wideIdx);
      break;
    case 0x9b:
      transfer(Y, X, wideIdx);
      break;
    case 0xbb:
      transfer(X, Y, wideIdx);
      break;
    case 0x7b:
      transfer(A, D, true);
      break;
    case 0x3b:
      transfer(A, S, true);
      break;
    case 0x5b:
      transfer(D, A, true);
      break;
    case 0x9a:
      Io();
      S = E ? 0x100 | (X & 0xff) : X;
      break;
    case 0x1b:
      Io();
      S = E ? 0x100 | (A & 0xff) : A;
      break;
    case 0xeb:
      Io();
      Io();
      A = static_cast<uint16_t>((A >> 8) | (A << 8));
      SetNZ(A, false);
      break;
    // increments
    case 0xe8:
    case 0xca:
    case 0xc8:
    case 0x88:
    {
      Io();
      auto& reg = op == 0xe8 || op == 0xca ? X : Y;
      reg = (reg + (op == 0xe8 || op == 0xc8 ? 1 : -1)) & (wideIdx ? 0xffff : 0xff);
      SetNZ(reg, wideIdx);
      break;
    }
    // stack
    case 0x48:
      Io();
      wide ? Push16(A) : Push(A & 0xff);
      break;
    case 0xda:
      Io();
      wideIdx ? Push16(X) : Push(X & 0xff);
      break;
    case 0x5a:
      Io();
      wideIdx ? Push16(Y) : Push(Y & 0xff);
      break;
    case 0x68:
      Io();
      Io();
      A = wide ? Pull16() : (A & 0xff00) | Pull();
      SetNZ(A, wide);
      break;
    case 0xfa:
      Io();
      Io();
      X = wideIdx ? Pull16() : Pull();
      SetNZ(X, wideIdx);
      break;
    case 0x7a:
      Io();
      Io();
      Y = wideIdx ? Pull16() : Pull();
      SetNZ(Y, wideIdx);
      break;
    case 0x08:
      Io();
      Push(P);
      break;
    case 0x28:
      Io();
      Io();
      SetP(Pull());
      break;
    case 0x8b:
      Io();
      Push(DB);
      break;
    case 0xab:
      Io();
      Io();
      DB = Pull();
      SetNZ(DB, false);
      break;
    case 0x0b:
      Io();
      Push16(D);
      break;
    case 0x2b:
      Io();
      Io();
      D = Pull16();
      SetNZ(D, true);
      break;
    case 0x4b:
      Io();
      Push(PB);
      break;
    case 0xf4:
      Push16(Fetch16());
      break;
    case 0xd4:
    {
      const auto dp = Fetch();
      IoDirect();
      const auto ptr = (D + dp) & 0xffff;
      const uint16_t lo = Read(ptr);
      Push16(lo | (Read((ptr + 1) & 0xffff) << 8));
      break;
    }
    case 0x62:
    {
      const auto off = Fetch16();
      Io();
      Push16(PC + off);
      break;
    }
    // jumps
    case 0x4c:
      PC = Fetch16();
      break;
    case 0x5c:
    {
      const auto addr = Fetch16();
      PB = Fetch();
      PC = addr;
      break;
    }
    case 0x6c:
    case 0xdc:
    {
      const auto addr = Fetch16();
      const uint16_t lo = Read(addr);
      PC = lo | (Read((addr + 1) & 0xffff) << 8);
      if (op == 0xdc)
      {
        PB = Read((addr + 2) & 0xffff);
      }
      break;
    }
    case 0x7c:
    case 0xfc:
    {
      const auto addr = Fetch16();
      if (op == 0xfc)
      {
        Push16(PC - 1);
      }
      Io();
      const uint_t bank = uint_t(PB) << 16;
      const uint16_t lo = Read(bank | ((addr + X) & 0xffff));
      PC = lo | (Read(bank | ((addr + X + 1) & 0xffff)) << 8);
      break;
    }
    case 0x20:
    {
      const auto addr = Fetch16();
      Io();
      Push16(PC - 1);
      PC = addr;
      break;
    }
    case 0x22:
    {
      const auto addr = Fetch16();
      Push(PB);
      Io();
      const auto bank = Fetch();
      Push16(PC - 1);
      PB = bank;
      PC = addr;
      break;
    }
    case 0x60:
      Io();
      Io();
      PC = Pull16() + 1;
      Io();
      break;
    case 0x6b:
      Io();
      Io();
      PC = Pull16() + 1;
      PB = Pull();
      break;
    case 0x40:
      Io();
      Io();
      SetP(Pull());
      PC = Pull16();
      if (!E)
      {
        PB = Pull();
      }
      break;
    // misc
    case 0x00:
    case 0x02:
    {
      Fetch();
      if (!E)
      {
        Push(PB);
      }
      Push16(PC);
      Push(E ? P | 0x10 : P);
      P = (P | 0x04) & ~0x08;
      PB = 0;
      const uint16_t vec = op == 0x00 ? (E ? 0xfffe : 0xffe6) : (E ? 0xfff4 : 0xffe4);
      PC = Read(vec);
      PC |= Read(vec + 1) << 8;
      break;
    }
    case 0x42:
      Fetch();
      break;
    case 0xea:
      Io();
      break;
    case 0xcb:
      Io();
      Io();
      Waiting = true;
      break;
    case 0xdb:
      Io();
      Io();
      Stopped = true;
      break;
    case 0x44:
    case 0x54:
    {
      DB = Fetch();
      const auto src = Fetch();
      const auto data = Read((uint_t(src) << 16) | X);
      Write((uint_t(DB) << 16) | Y, data);
      Io();
      Io();
      const uint16_t delta = op == 0x54 ? 1 : 0xffff;
      const uint16_t mask = wideIdx ? 0xffff : 0xff;
      X = (X + delta) & mask;
      Y = (Y + delta) & mask;
      if (A-- != 0)
      {
        // repeat instruction, interrupts are served between iterations
        PC -= 3;
      }
      break;
    }
    default:
      Require(false);
      break;
    }
  }
}  // namespace Module::SNSF
