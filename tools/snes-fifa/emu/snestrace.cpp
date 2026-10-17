// snestrace: headless SNES runner built on a (patched) snes9x libretro core.
//
// Logs every 65816 <-> SPC700 port access ($2140-$2143 / $F4-$F7) with SPC700
// clock timestamps, captures audio, dumps ARAM / .spc snapshots / WRAM at
// given frames, can feed scripted joypad input, poke WRAM, save/load states
// and produce a 65816 execution trace.
//
// Built by build.sh (links the snes9x sources directly, no dlopen).

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <set>
#include <string>
#include <vector>

#include "libretro.h"
#include "snes9x.h"
#include "memmap.h"
#include "apu/apu.h"
#include "apu/bapu/snes/snes.hpp"
#include "snesfifa_hooks.h"

namespace {

struct FrameAction {
  std::string kind;  // spc, aram, wram, state, poke, statesave
  std::string arg;
};

FILE* g_log = nullptr;
int g_reads_mode = 1;  // 0 none, 1 changes, 2 all
int g_last_read[4] = {-1, -1, -1, -1};
uint32_t g_frame = 0;
uint32_t g_last_pc = 0;
std::vector<int16_t> g_audio;
std::map<uint32_t, uint32_t> g_input;  // frame -> joypad mask (held from frame until next entry)
uint32_t g_cur_input = 0;
FILE* g_exec = nullptr;
uint32_t g_exec_from = 0, g_exec_to = 0;
std::map<uint32_t, uint64_t> g_coverage;
bool g_coverage_on = false;
bool g_quiet_smp = false;

void LogEvent(const char* kind, int port, int value) {
  if (!g_log) return;
  fprintf(g_log, "%s\t%d\t%02x\t%llu\t%u\t%d\t%06x\n", kind, port, value,
          (unsigned long long)snesfifa_smp_clock, g_frame, CPU.Cycles, g_last_pc);
}

void OnCpuWrite(int port, uint8 v) { LogEvent("CW", port, v); }

void OnCpuRead(int port, uint8 v) {
  if (g_reads_mode == 0) return;
  if (g_reads_mode == 1 && g_last_read[port] == v) return;
  g_last_read[port] = v;
  LogEvent("CR", port, v);
}

void OnSmpWrite(int port, uint8 v) {
  if (!g_quiet_smp) LogEvent("SW", port, v);
}

void OnExec(uint32 pbpc) {
  g_last_pc = pbpc;
  if (g_coverage_on) g_coverage[pbpc | (uint32_t)(Registers.P.B.l & 0x30) << 20]++;
  if (g_exec && g_frame >= g_exec_from && g_frame <= g_exec_to) {
    fprintf(g_exec, "%u %06x A=%04x X=%04x Y=%04x S=%04x D=%04x DB=%02x P=%02x E=%d\n", g_frame, pbpc,
            Registers.A.W, Registers.X.W, Registers.Y.W, Registers.S.W, Registers.D.W, Registers.DB,
            Registers.P.B.l, (int)CheckEmulation() ? 1 : 0);
  }
}

bool Environment(unsigned cmd, void* data) {
  switch (cmd) {
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
      return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
      *(const char**)data = ".";
      return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE:
      return false;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
      *(bool*)data = true;
      return true;
    default:
      return false;
  }
}
void Video(const void*, unsigned, unsigned, size_t) {}
void AudioSample(int16_t l, int16_t r) {
  g_audio.push_back(l);
  g_audio.push_back(r);
}
size_t AudioBatch(const int16_t* d, size_t frames) {
  g_audio.insert(g_audio.end(), d, d + frames * 2);
  return frames;
}
void InputPoll() {}
int16_t InputState(unsigned port, unsigned device, unsigned, unsigned id) {
  if (port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
  if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)g_cur_input;
  return (g_cur_input >> id) & 1;
}

uint32_t ParseButtons(const std::string& s) {
  static const char* names[] = {"B", "Y", "SELECT", "START", "UP", "DOWN", "LEFT", "RIGHT", "A", "X", "L", "R"};
  uint32_t mask = 0;
  size_t i = 0;
  while (i <= s.size()) {
    size_t j = s.find('+', i);
    if (j == std::string::npos) j = s.size();
    std::string tok = s.substr(i, j - i);
    for (int k = 0; k < 12; ++k)
      if (tok == names[k]) mask |= 1u << k;
    i = j + 1;
  }
  return mask;
}

void WriteFile(const std::string& path, const void* data, size_t size) {
  FILE* f = fopen(path.c_str(), "wb");
  if (!f) {
    fprintf(stderr, "cannot write %s\n", path.c_str());
    exit(1);
  }
  fwrite(data, 1, size, f);
  fclose(f);
}

std::vector<uint8_t> ReadFile(const std::string& path) {
  std::vector<uint8_t> out;
  FILE* f = fopen(path.c_str(), "rb");
  if (!f) {
    fprintf(stderr, "cannot read %s\n", path.c_str());
    exit(1);
  }
  uint8_t buf[65536];
  size_t n;
  while ((n = fread(buf, 1, sizeof buf, f)) > 0) out.insert(out.end(), buf, buf + n);
  fclose(f);
  return out;
}

void WriteWav(const std::string& path, const std::vector<int16_t>& s, unsigned rate) {
  FILE* f = fopen(path.c_str(), "wb");
  if (!f) exit(1);
  uint32_t data = s.size() * 2;
  auto w32 = [&](uint32_t v) { fwrite(&v, 4, 1, f); };
  auto w16 = [&](uint16_t v) { fwrite(&v, 2, 1, f); };
  fwrite("RIFF", 1, 4, f); w32(36 + data); fwrite("WAVEfmt ", 1, 8, f);
  w32(16); w16(1); w16(2); w32(rate); w32(rate * 4); w16(4); w16(16);
  fwrite("data", 1, 4, f); w32(data);
  fwrite(s.data(), 2, s.size(), f);
  fclose(f);
}

void Usage() {
  fprintf(stderr,
          "usage: snestrace --rom FILE [--frames N] [--log FILE] [--reads none|changes|all]\n"
          "  [--no-smp-log] [--wav FILE] [--input F:BTN+BTN,...] [--spc F:FILE] [--aram F:FILE]\n"
          "  [--wram F:FILE] [--save-state F:FILE] [--load-state FILE] [--poke F:ADDR=VAL]\n"
          "  [--exec-trace FILE --exec-range F1-F2] [--coverage FILE] [--pal|--ntsc]\n"
          "Frame actions run after frame F has been emulated (F counts from 1).\n");
  exit(2);
}

}  // namespace

int main(int argc, char** argv) {
  std::string rom, log_path, wav_path, exec_path, cov_path, load_state;
  uint32_t frames = 600;
  std::multimap<uint32_t, FrameAction> actions;
  for (int i = 1; i < argc; ++i) {
    std::string a = argv[i];
    auto next = [&]() -> std::string {
      if (i + 1 >= argc) Usage();
      return argv[++i];
    };
    auto frame_action = [&](const char* kind) {
      std::string v = next();
      size_t c = v.find(':');
      if (c == std::string::npos) Usage();
      actions.insert({(uint32_t)strtoul(v.substr(0, c).c_str(), nullptr, 0), {kind, v.substr(c + 1)}});
    };
    if (a == "--rom") rom = next();
    else if (a == "--frames") frames = strtoul(next().c_str(), nullptr, 0);
    else if (a == "--log") log_path = next();
    else if (a == "--reads") {
      std::string m = next();
      g_reads_mode = m == "none" ? 0 : m == "all" ? 2 : 1;
    } else if (a == "--no-smp-log") g_quiet_smp = true;
    else if (a == "--wav") wav_path = next();
    else if (a == "--spc") frame_action("spc");
    else if (a == "--aram") frame_action("aram");
    else if (a == "--wram") frame_action("wram");
    else if (a == "--save-state") frame_action("state");
    else if (a == "--poke") frame_action("poke");
    else if (a == "--load-state") load_state = next();
    else if (a == "--exec-trace") exec_path = next();
    else if (a == "--exec-range") {
      std::string v = next();
      size_t d = v.find('-');
      g_exec_from = strtoul(v.substr(0, d).c_str(), nullptr, 0);
      g_exec_to = d == std::string::npos ? g_exec_from : strtoul(v.substr(d + 1).c_str(), nullptr, 0);
    } else if (a == "--coverage") cov_path = next();
    else if (a == "--input") {
      std::string v = next();
      size_t i0 = 0;
      while (i0 < v.size()) {
        size_t j = v.find(',', i0);
        if (j == std::string::npos) j = v.size();
        std::string item = v.substr(i0, j - i0);
        size_t c = item.find(':');
        if (c != std::string::npos)
          g_input[strtoul(item.substr(0, c).c_str(), nullptr, 0)] = ParseButtons(item.substr(c + 1));
        i0 = j + 1;
      }
    } else Usage();
  }
  if (rom.empty()) Usage();

  retro_set_environment(Environment);
  retro_set_video_refresh(Video);
  retro_set_audio_sample(AudioSample);
  retro_set_audio_sample_batch(AudioBatch);
  retro_set_input_poll(InputPoll);
  retro_set_input_state(InputState);
  retro_init();

  std::vector<uint8_t> romdata = ReadFile(rom);
  retro_game_info gi = {};
  gi.path = rom.c_str();
  gi.data = romdata.data();
  gi.size = romdata.size();
  if (!retro_load_game(&gi)) {
    fprintf(stderr, "failed to load %s\n", rom.c_str());
    return 1;
  }
  retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
  if (!load_state.empty()) {
    std::vector<uint8_t> st = ReadFile(load_state);
    if (!retro_unserialize(st.data(), st.size())) {
      fprintf(stderr, "failed to load state\n");
      return 1;
    }
  }
  fprintf(stderr, "snestrace: rom=%s mapping=%s region=%s\n", rom.c_str(), Memory.HiROM ? "HiROM" : "LoROM",
          Settings.PAL ? "PAL" : "NTSC");

  if (!log_path.empty()) {
    g_log = fopen(log_path.c_str(), "w");
    fprintf(g_log, "# kind\tport\tvalue\tsmp_clock\tframe\tcpu_cycles\tpc\n");
  }
  if (!exec_path.empty()) g_exec = fopen(exec_path.c_str(), "w");
  g_coverage_on = !cov_path.empty();
  snesfifa_cpu_port_write_hook = OnCpuWrite;
  snesfifa_cpu_port_read_hook = OnCpuRead;
  snesfifa_smp_port_write_hook = OnSmpWrite;
  snesfifa_cpu_exec_hook = OnExec;

  for (g_frame = 1; g_frame <= frames; ++g_frame) {
    auto it = g_input.upper_bound(g_frame);
    if (it != g_input.begin()) g_cur_input = std::prev(it)->second;
    retro_run();
    auto range = actions.equal_range(g_frame);
    for (auto a = range.first; a != range.second; ++a) {
      const FrameAction& fa = a->second;
      if (fa.kind == "spc") {
        S9xSPCDump(fa.arg.c_str());
      } else if (fa.kind == "aram") {
        WriteFile(fa.arg, SNES::smp.apuram, 0x10000);
      } else if (fa.kind == "wram") {
        WriteFile(fa.arg, Memory.RAM, 0x20000);
      } else if (fa.kind == "state") {
        std::vector<uint8_t> st(retro_serialize_size());
        retro_serialize(st.data(), st.size());
        WriteFile(fa.arg, st.data(), st.size());
      } else if (fa.kind == "poke") {
        size_t e = fa.arg.find('=');
        uint32_t addr = strtoul(fa.arg.substr(0, e).c_str(), nullptr, 16) & 0x1ffff;
        Memory.RAM[addr] = strtoul(fa.arg.substr(e + 1).c_str(), nullptr, 16);
      }
      if (g_log) fprintf(g_log, "# frame %u action %s %s\n", g_frame, fa.kind.c_str(), fa.arg.c_str());
    }
  }
  if (g_log) fclose(g_log);
  if (g_exec) fclose(g_exec);
  if (!wav_path.empty()) WriteWav(wav_path, g_audio, 32040);
  if (!cov_path.empty()) {
    FILE* f = fopen(cov_path.c_str(), "w");
    for (auto& kv : g_coverage) fprintf(f, "%06x %d %d %llu\n", kv.first & 0xffffff, (kv.first >> 25) & 1, (kv.first >> 24) & 1, (unsigned long long)kv.second);
    fclose(f);
  }
  fprintf(stderr, "snestrace: %u frames, %zu audio frames, smp_clock=%llu\n", frames, g_audio.size() / 2,
          (unsigned long long)snesfifa_smp_clock);
  retro_unload_game();
  retro_deinit();
  return 0;
}
