"""Reader for snestrace port logs (TSV)."""
from dataclasses import dataclass
from typing import Iterator, List


@dataclass
class PortEvent:
    kind: str      # CW (65816 writes $214x), CR (65816 reads, changes only), SW (SPC700 writes $F4-$F7)
    port: int
    value: int
    clock: int     # cumulative SPC700 clock (~1.024 MHz)
    frame: int
    cpu_cycles: int
    pc: int        # 65816 PC (bank<<16|addr) of the instruction doing the access

    def to_line(self) -> str:
        return f"{self.kind}\t{self.port}\t{self.value:02x}\t{self.clock}\t{self.frame}\t{self.cpu_cycles}\t{self.pc:06x}"


def read_events(path: str) -> Iterator[PortEvent]:
    with open(path) as f:
        for line in f:
            if not line or line[0] == "#":
                continue
            p = line.rstrip("\n").split("\t")
            yield PortEvent(p[0], int(p[1]), int(p[2], 16), int(p[3]), int(p[4]), int(p[5]), int(p[6], 16))


def write_events(path: str, events: List[PortEvent]) -> None:
    with open(path, "w") as f:
        f.write("# kind\tport\tvalue\tsmp_clock\tframe\tcpu_cycles\tpc\n")
        for e in events:
            f.write(e.to_line() + "\n")
