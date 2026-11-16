#include "ares_spc.h"

#include "smp/smp.hpp"

#include <cstring>
#include <vector>

namespace
{
  using namespace ares;
  using namespace ares::SuperFamicom;

  // S-SMP boot ROM
  const u8 IPL_ROM[64] = {0xcd, 0xef, 0xbd, 0xe8, 0x00, 0xc6, 0x1d, 0xd0, 0xfc, 0x8f, 0xaa, 0xf4, 0x8f, 0xbb, 0xf5, 0x78,
                          0xcc, 0xf4, 0xd0, 0xfb, 0x2f, 0x19, 0xeb, 0xf4, 0xd0, 0xfc, 0x7e, 0xf4, 0xd0, 0x0b, 0xe4, 0xf5,
                          0xcb, 0xf4, 0xd7, 0x00, 0xfc, 0xd0, 0xf3, 0xab, 0x01, 0x10, 0xef, 0x7e, 0xf4, 0x10, 0xeb, 0xba,
                          0xf6, 0xda, 0x00, 0xba, 0xf4, 0xc4, 0xf4, 0xdd, 0x5d, 0xd0, 0xdb, 0x1f, 0x00, 0x00, 0xc0, 0xff};

  enum
  {
    FILE_REGISTERS = 0x25,
    FILE_RAM = 0x100,
    FILE_DSP = 0x10100,
    RAM_SIZE = 0x10000,
    DSP_REGISTERS = 128,
  };
}  // namespace

struct AresSpc::Machine
{
  DSP Dsp;
  SMP Smp;
  // samples rendered but not consumed yet: instruction is not interrupted at the end of requested range
  std::vector<int16_t> Pending;
  voices_func_t VoicesFunc = nullptr;
  void* VoicesData = nullptr;

  Machine()
  {
    Smp.dsp = &Dsp;
    Dsp.sampleFunc = &OnSample;
    Dsp.sampleParam = this;
    for (u32 idx = 0; idx < 64; ++idx)
    {
      Smp.iplrom[idx] = IPL_ROM[idx];
    }
    Power();
  }

  void Power()
  {
    Dsp.power();
    Smp.power();
    Pending.clear();
  }

  static void OnSample(void* param, s32 left, s32 right, const s32* voices)
  {
    auto* const self = static_cast<Machine*>(param);
    self->Pending.push_back(static_cast<int16_t>(left));
    self->Pending.push_back(static_cast<int16_t>(right));
    if (self->VoicesFunc)
    {
      self->VoicesFunc(self->VoicesData, voices);
    }
  }

  void CopyStateFrom(const Machine& rh)
  {
    const auto muteMask = Dsp.muteMask;
    Dsp = rh.Dsp;
    Smp = rh.Smp;
    Pending = rh.Pending;
    Smp.dsp = &Dsp;
    Dsp.sampleParam = this;
    Dsp.muteMask = muteMask;
  }
};

AresSpc::AresSpc()
  : M(new Machine())
{}

AresSpc::~AresSpc() = default;

bool AresSpc::load_spc(const void* data, std::size_t size)
{
  if (size < min_file_size)
  {
    return false;
  }
  const auto* const file = static_cast<const u8*>(data);
  auto& smp = M->Smp;
  auto& dsp = M->Dsp;
  M->Power();

  const auto* const ram = file + FILE_RAM;
  for (u32 addr = 0; addr < RAM_SIZE; ++addr)
  {
    dsp.apuram[addr] = ram[addr];
  }

  // CPU
  const auto* const regs = file + FILE_REGISTERS;
  smp.r.pc.w = regs[0] | (regs[1] << 8);
  smp.r.ya.byte.l = regs[2];
  smp.r.x = regs[3];
  smp.r.ya.byte.h = regs[4];
  smp.r.p = n8(regs[5]);
  smp.r.s = regs[6];

  // IO registers are restored from their RAM image; TEST is not readable, so its power-on value is kept
  const auto control = ram[0xf1];
  smp.timer0.enable = (control & 1) != 0;
  smp.timer1.enable = (control & 2) != 0;
  smp.timer2.enable = (control & 4) != 0;
  smp.io.iplromEnable = (control & 0x80) != 0;
  smp.io.dspAddress = ram[0xf2];
  // values from main CPU
  smp.io.apu0 = ram[0xf4];
  smp.io.apu1 = ram[0xf5];
  smp.io.apu2 = ram[0xf6];
  smp.io.apu3 = ram[0xf7];
  smp.io.aux4 = ram[0xf8];
  smp.io.aux5 = ram[0xf9];
  smp.timer0.target = ram[0xfa];
  smp.timer1.target = ram[0xfb];
  smp.timer2.target = ram[0xfc];
  smp.timer0.stage3 = ram[0xfd] & 15;
  smp.timer1.stage3 = ram[0xfe] & 15;
  smp.timer2.stage3 = ram[0xff] & 15;

  // DSP registers via regular writes to set up internal state; voices of KON are started as in other SPC players
  const auto* const dspRegs = file + FILE_DSP;
  for (u32 reg = 0; reg < DSP_REGISTERS; ++reg)
  {
    dsp.write(reg, dspRegs[reg]);
  }
  // read-only ones and those cleared by write
  for (u32 voice = 0; voice < voice_count; ++voice)
  {
    dsp.registers[voice * 0x10 + 0x08] = dspRegs[voice * 0x10 + 0x08];
    dsp.registers[voice * 0x10 + 0x09] = dspRegs[voice * 0x10 + 0x09];
    dsp.voice[voice]._end = (dspRegs[0x7c] >> voice) & 1;
  }
  dsp.registers[0x7c] = dspRegs[0x7c];
  // latches updated once per sample
  dsp.brr._bank = dsp.brr.bank;
  dsp.echo._page = dsp.echo.page;
  dsp.echo._readonly = dsp.echo.readonly;
  for (auto& v : dsp.voice)
  {
    v._modulate = v.modulate;
    v._noise = v.noise;
    v._echo = v.echo;
  }
  return true;
}

void AresSpc::clear_echo()
{
  auto& dsp = M->Dsp;
  if (!dsp.echo.readonly)
  {
    const u32 start = u32(dsp.echo.page) << 8;
    const u32 delay = dsp.echo.delay;
    const u32 size = delay ? delay << 11 : 4;
    const u32 end = start + size < RAM_SIZE ? start + size : u32(RAM_SIZE);
    for (u32 addr = start; addr < end; ++addr)
    {
      dsp.apuram[addr] = 0xff;
    }
  }
}

void AresSpc::mute_voices(int mask)
{
  M->Dsp.muteMask = static_cast<u32>(mask);
}

void AresSpc::set_voices_func(voices_func_t func, void* data)
{
  M->VoicesFunc = func;
  M->VoicesData = data;
}

void AresSpc::play(std::size_t frames, int16_t* out)
{
  auto& pending = M->Pending;
  const auto required = frames * 2;
  while (pending.size() < required)
  {
    M->Smp.main();
  }
  if (out)
  {
    std::memcpy(out, pending.data(), required * sizeof(*out));
  }
  pending.erase(pending.begin(), pending.begin() + static_cast<std::ptrdiff_t>(required));
}

void AresSpc::copy_state_from(const AresSpc& rh)
{
  M->CopyStateFrom(*rh.M);
}

void AresSpc::get_voice_state(int voice, voice_state_t* out) const
{
  const auto& dsp = M->Dsp;
  const auto& v = dsp.voice[voice & 7];
  out->env = v.envelope;
  out->released = v.envelopeMode == 0;
  out->pitch = v.pitch;
  out->vol_l = v.volume[0];
  out->vol_r = v.volume[1];
  out->srcn = v.source;
  out->noise = v.noise;
  out->noise_rate = dsp.noise.frequency;
  out->env_mode = v.envelopeMode;
  out->adsr0 = v.adsr0;
  out->adsr1 = v.adsr1;
  out->gain = v.gain;
  out->echo = v.echo;
  out->pmod = v.modulate;
  out->brr_addr = v.brrAddress;
}

int AresSpc::read(int reg) const
{
  return M->Dsp.registers[reg & 0x7f];
}

const uint8_t* AresSpc::ram() const
{
  return &M->Dsp.apuram[0].data;
}
