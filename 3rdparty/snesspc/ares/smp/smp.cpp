#include "smp.hpp"

namespace ares::SuperFamicom {

#include "memory.cpp"
#include "io.cpp"
#include "timing.cpp"

auto SMP::main() -> void {
  if(r.wait) return instructionWait();
  if(r.stop) return instructionStop();

  instruction();
}

auto SMP::power() -> void {
  SPC700::power();

  r.pc.byte.l = iplrom[62];
  r.pc.byte.h = iplrom[63];

  io = {};
  timer0 = {};
  timer1 = {};
  timer2 = {};
}

}
