// spcreplay: replay a snestrace port log into blargg's snes_spc (3rdparty/snesspc) and
// render a WAV.
//
// The replay starts from an .spc snapshot taken by snestrace at SPC clock C (--start-clock)
// and applies every 65816 write (CW) logged after C.  Handshakes are honoured: when the
// 65816 had read a value from the SPC (CR) before a write, the replayer first runs the
// SPC until that value appears on the port, and shifts the rest of the timeline if the
// SPC turns out to be slower than in the original run ("ack-sync").
//
// Log format: kind port value smp_clock frame cpu_cycles pc  (TSV, '#' comments)

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "snes_spc/SNES_SPC.h"

namespace {

struct Ev {
  char kind;  // 'W' cpu write, 'R' cpu read, 'S' smp write
  int port;
  int value;
  long long clock;
};

std::vector<unsigned char> ReadFile(const char* path) {
  std::vector<unsigned char> d;
  FILE* f = fopen(path, "rb");
  if (!f) {
    fprintf(stderr, "cannot open %s\n", path);
    exit(1);
  }
  unsigned char buf[65536];
  size_t n;
  while ((n = fread(buf, 1, sizeof buf, f)) > 0) d.insert(d.end(), buf, buf + n);
  fclose(f);
  return d;
}

void WriteWav(const char* path, const std::vector<short>& s, unsigned rate) {
  FILE* f = fopen(path, "wb");
  if (!f) exit(1);
  unsigned data = s.size() * 2;
  auto w32 = [&](unsigned v) { fwrite(&v, 4, 1, f); };
  auto w16 = [&](unsigned short v) { fwrite(&v, 2, 1, f); };
  fwrite("RIFF", 1, 4, f); w32(36 + data); fwrite("WAVEfmt ", 1, 8, f);
  w32(16); w16(1); w16(2); w32(rate); w32(rate * 4); w16(4); w16(16);
  fwrite("data", 1, 4, f); w32(data);
  fwrite(s.data(), 2, s.size(), f);
  fclose(f);
}

void Usage() {
  fprintf(stderr,
          "usage: spcreplay --spc SNAP.spc --log PORTS.tsv --start-clock C --out OUT.wav\n"
          "       [--seconds N] [--no-sync] [--rate HZ] [--end-clock E]\n");
  exit(2);
}

}  // namespace

int main(int argc, char** argv) {
  const char *spc_path = nullptr, *log_path = nullptr, *out_path = nullptr;
  long long start_clock = 0, end_clock = -1;
  double seconds = 0;
  bool sync = true;
  unsigned rate = 32040;
  for (int i = 1; i < argc; ++i) {
    std::string a = argv[i];
    auto next = [&]() { if (i + 1 >= argc) Usage(); return argv[++i]; };
    if (a == "--spc") spc_path = next();
    else if (a == "--log") log_path = next();
    else if (a == "--out") out_path = next();
    else if (a == "--start-clock") start_clock = atoll(next());
    else if (a == "--end-clock") end_clock = atoll(next());
    else if (a == "--seconds") seconds = atof(next());
    else if (a == "--no-sync") sync = false;
    else if (a == "--rate") rate = atoi(next());
    else Usage();
  }
  if (!spc_path || !log_path || !out_path) Usage();

  std::vector<Ev> evs;
  int ports_at_start[4] = {0, 0, 0, 0};
  {
    FILE* f = fopen(log_path, "r");
    if (!f) { fprintf(stderr, "cannot open %s\n", log_path); return 1; }
    char line[256];
    while (fgets(line, sizeof line, f)) {
      if (line[0] == '#') continue;
      char kind[4];
      int port, value;
      long long clock;
      if (sscanf(line, "%3s %d %x %lld", kind, &port, &value, &clock) != 4) continue;
      Ev e{kind[1] == 'W' && kind[0] == 'C' ? 'W' : kind[0] == 'C' ? 'R' : 'S', port, value, clock};
      if (clock <= start_clock) {
        if (e.kind == 'W') ports_at_start[port] = value;
        continue;
      }
      if (end_clock >= 0 && clock > end_clock) break;
      evs.push_back(e);
    }
    fclose(f);
  }

  std::vector<unsigned char> spc = ReadFile(spc_path);
  SNES_SPC emu;
  if (emu.init()) return 1;
  if (const char* err = emu.load_spc(spc.data(), spc.size())) {
    fprintf(stderr, "load_spc: %s\n", err);
    return 1;
  }

  const int kChunk = 32 * 2048;  // clocks per emulation frame
  std::vector<short> out;
  std::vector<short> buf(kChunk / 32 * 2 + 64);
  emu.set_output(buf.data(), buf.size());
  long long frame_base = 0;  // absolute replay clock of current frame start
  auto flush_to = [&](long long t) {
    while (t - frame_base >= kChunk) {
      emu.end_frame(kChunk);
      frame_base += kChunk;
      out.insert(out.end(), buf.begin(), buf.begin() + emu.sample_count());
      emu.set_output(buf.data(), buf.size());
    }
  };
  for (int p = 0; p < 4; ++p) emu.write_port(0, p, ports_at_start[p]);

  long long now = 0, offset = 0;
  int pending_port = -1, pending_value = 0;
  long long pending_clock = 0;
  long sync_waits = 0, sync_fail = 0;
  long long max_shift = 0;
  for (const Ev& e : evs) {
    if (e.kind == 'R') {
      pending_port = e.port;
      pending_value = e.value;
      pending_clock = e.clock;
      continue;
    }
    if (e.kind != 'W') continue;
    long long target = e.clock - start_clock + offset;
    if (sync && pending_port >= 0) {
      // The 65816 saw `pending_value` at log time pending_clock; find when our SPC shows it.
      long long rel = pending_clock - start_clock;
      long long t = rel < now ? now : rel;
      long long limit = t + 200000;
      bool ok = false;
      while (t < limit) {
        flush_to(t);
        if (emu.read_port((int)(t - frame_base), pending_port) == pending_value) { ok = true; break; }
        t += 4;
      }
      ++sync_waits;
      if (!ok) ++sync_fail;
      if (ok) {
        offset = t - rel;  // current lag of the replay behind the original timeline
        if (offset > max_shift) max_shift = offset;
        target = e.clock - start_clock + offset;
      }
      if (t > now) now = t;
      pending_port = -1;
    }
    if (target < now) target = now;
    flush_to(target);
    emu.write_port((int)(target - frame_base), e.port, e.value);
    now = target;
  }
  long long end = now + 32 * 32;
  if (seconds > 0) end = (long long)(seconds * rate * 32);
  flush_to(end + kChunk);
  if (seconds > 0 && (long long)out.size() > (long long)(seconds * rate) * 2) out.resize((size_t)(seconds * rate) * 2);
  WriteWav(out_path, out, rate);
  fprintf(stderr, "spcreplay: %zu events, %ld sync waits (%ld failed), total shift %lld clocks (max %lld), %zu frames\n",
          evs.size(), sync_waits, sync_fail, offset, max_shift, out.size() / 2);
  return sync_fail ? 3 : 0;
}
