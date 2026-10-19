/**
 *
 * @file
 *
 * @brief Live texts of SID dashboard rows implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "visualization/sid_texts.h"

#include "visualization/format.h"
#include "visualization/notes.h"

#include <cmath>

namespace Visualization
{
  namespace SidCells
  {
    using namespace SidDescription;

    // order of cells is order of display; what is heard (waveform, note, loudness) is kept the longest,
    // envelope timings are static settings and dropped first
    const std::vector<CellSpec>& Voice()
    {
      static const std::vector<CellSpec> INSTANCE = {
          {std::string(Words::WAVE), {"Pulse+Saw", std::string(Words::TRIANGLE), std::string(Words::RESET)}, false, 0},
          {std::string(Words::DUTY), {"100%"}, true, 6},
          {std::string(Words::NOTE), {"C#5"}, false, 1},
          {std::string(Words::PITCH), {"8888 Hz"}, true, 5},
          {std::string(Words::MOD), {"Sync V3", "Ring V3", std::string(Words::SYNC_RING)}, false, 7},
          {std::string(Words::FILTER), {"Low+Band", "Band+High", std::string(Words::MUTED)}, false, 4},
          {std::string(Words::LEVEL), {"100%"}, true, 2},
          {std::string(Words::STAGE),
           {std::string(Words::ATTACK), std::string(Words::DECAY), std::string(Words::SUSTAIN),
            std::string(Words::RELEASE), std::string(Words::SILENT)},
           false,
           3},
          {std::string(Words::ATTACK), {"250 ms", "8 s"}, true, 10},
          {std::string(Words::DECAY), {"750 ms", "24 s"}, true, 11},
          {std::string(Words::SUSTAIN), {"100%"}, true, 8},
          {std::string(Words::RELEASE), {"750 ms", "24 s"}, true, 9},
      };
      return INSTANCE;
    }

    const std::vector<CellSpec>& Global()
    {
      static const std::vector<CellSpec> INSTANCE = {
          {std::string(Words::VOLUME), {"100%"}, true, 0},
          {std::string(Words::FILTER), {"Low+Band", "Band+High"}, false, 1},
          {std::string(Words::CUTOFF), {"~8.8 kHz", "~18 kHz"}, true, 2},
          {std::string(Words::RESONANCE), {"100%"}, true, 3},
          {std::string(Words::ROUTED), {"V1+V2+V3"}, false, 4},
      };
      return INSTANCE;
    }
  }  // namespace SidCells

  std::string Hex4(int value)
  {
    return Format("%04X", value);
  }

  SidVoiceText::SidVoiceText()
    : SidRowText(SidCells::Voice().size())
    , Note(Notes::NONE)
  {}

  void SidVoiceText::Update(const ChipState& state, int voice, int trend, const SidDescription::Chip& chip,
                            int chipLabel)
  {
    using namespace SidDescription;
    const auto ctrl = state.Control(voice);
    const auto routing = state.Routing();
    const auto mode = state.Mode();
    const bool filtered = routing & (1 << voice);
    const auto ad = state.AttackDecay(voice);
    const auto sr = state.SustainRelease(voice);
    const auto hz = FrequencyHz(state.Frequency(voice), chip);
    Note = hz < 1.f ? Notes::NONE : Notes::Quantize(Notes::Semitones(hz), Note);
    const auto pulse = PulsePercent(state.PulseWidth(voice));
    const auto level = EnvelopePercent(state, voice);
    const auto phase = GetPhase(state, voice, trend);
    const auto roundedHz = static_cast<int>(std::lround(hz));
    const auto noteName = Note == Notes::NONE ? std::string() : Notes::Name(Note);

    // titles: raw registers
    const auto wave = (ctrl & 0xfe) | (filtered ? 1 : 0);
    if (Titles.Changed(Title(0), (chipLabel << 8) | wave))
    {
      const auto prefix = chipLabel != 0 ? "S" + std::to_string(chipLabel) : std::string();
      Titles.Set(Title(0), prefix + "V" + std::to_string(voice + 1) + " " + WaveTitle(wave, filtered));
    }
    if (Titles.Changed(Title(1), (ad << 8) | sr))
    {
      Titles.Set(Title(1), "Env " + Hex4((ad << 8) | sr));
    }
    if (Titles.Changed(Title(2), state.Frequency(voice)))
    {
      Titles.Set(Title(2), "Freq " + Hex4(state.Frequency(voice)));
    }
    // titles: values
    if (Titles.Changed(Main(0), (ctrl & 0xfe) | (pulse << 8)))
    {
      Titles.Set(Main(0), WaveName(ctrl, pulse) + WaveFlags(ctrl));
    }
    if (Titles.Changed(Tail(0), ctrl & 1))
    {
      Titles.Set(Tail(0), IsGate(ctrl) ? std::string(GATE_MARK) : std::string());
    }
    if (Titles.Changed(Main(1), level))
    {
      Titles.Set(Main(1), Percent(level));
    }
    if (Titles.Changed(Tail(1), static_cast<int>(phase)))
    {
      Titles.Set(Tail(1), std::string(PhaseShort(phase)));
    }
    if (Titles.Changed(Main(2), Note))
    {
      Titles.Set(Main(2), noteName);
    }
    if (Titles.Changed(Tail(2), roundedHz))
    {
      Titles.Set(Tail(2), std::to_string(roundedHz) + "Hz");
    }

    // cells
    if (Cells.Changed(SidCells::WAVE, ctrl & 0xf8))
    {
      Cells.Set(SidCells::WAVE, WaveWord(ctrl));
    }
    const auto duty = HasPulse(ctrl) ? pulse : Notes::NONE;
    if (Cells.Changed(SidCells::DUTY, duty))
    {
      Cells.Set(SidCells::DUTY, duty == Notes::NONE ? std::string() : Percent(duty));
    }
    if (Cells.Changed(SidCells::NOTE, Note))
    {
      Cells.Set(SidCells::NOTE, noteName.empty() ? std::string(Words::DASH) : noteName);
    }
    if (Cells.Changed(SidCells::PITCH, roundedHz))
    {
      Cells.Set(SidCells::PITCH, roundedHz == 0 ? std::string(Words::DASH) : HzWord(roundedHz));
    }
    if (Cells.Changed(SidCells::MOD, ctrl & 0x16))
    {
      Cells.Set(SidCells::MOD, ModulationWord(ctrl, voice));
    }
    const auto muted = IsMuted(voice, mode, routing);
    if (Cells.Changed(SidCells::FILTER, (filtered ? 1 : 0) | (muted ? 2 : 0) | ((mode & 7) << 2)))
    {
      Cells.Set(SidCells::FILTER, FilterWord(filtered, mode, muted));
    }
    if (Cells.Changed(SidCells::LEVEL, level))
    {
      Cells.Set(SidCells::LEVEL, Percent(level));
    }
    if (Cells.Changed(SidCells::STAGE, static_cast<int>(phase)))
    {
      Cells.Set(SidCells::STAGE, std::string(PhaseWord(phase)));
    }
    if (Cells.Changed(SidCells::ATTACK, ad >> 4))
    {
      Cells.Set(SidCells::ATTACK, DurationWord(AttackMs(ad >> 4)));
    }
    if (Cells.Changed(SidCells::DECAY, ad & 15))
    {
      Cells.Set(SidCells::DECAY, DurationWord(DecayMs(ad)));
    }
    if (Cells.Changed(SidCells::SUSTAIN, sr >> 4))
    {
      Cells.Set(SidCells::SUSTAIN, Percent(SustainPercent(sr >> 4)));
    }
    // release uses the same table as decay
    if (Cells.Changed(SidCells::RELEASE, sr & 15))
    {
      Cells.Set(SidCells::RELEASE, DurationWord(DecayMs(sr)));
    }
  }

  SidGlobalText::SidGlobalText()
    : SidRowText(SidCells::Global().size())
  {}

  void SidGlobalText::Update(const ChipState& state, const SidDescription::Chip& chip)
  {
    using namespace SidDescription;
    const auto mode = state.Mode();
    const auto cutoff = RoundCutoff(CutoffHz(state.Cutoff(), chip));
    if (Titles.Changed(Title(0), state.Volume()))
    {
      Titles.Set(Title(0), "Vol " + std::to_string(state.Volume()));
      Titles.Set(Tail(0), PercentOf15(state.Volume()));
      Cells.Set(SidCells::VOLUME, Titles[Tail(0)]);
    }
    if (Titles.Changed(Title(1), state.Resonance()))
    {
      Titles.Set(Title(1), "Res " + std::to_string(state.Resonance()));
      Titles.Set(Tail(1), PercentOf15(state.Resonance()));
      Cells.Set(SidCells::RESONANCE, Titles[Tail(1)]);
    }
    if (Titles.Changed(Title(2), ((mode & 7) << 11) | state.Cutoff()))
    {
      Titles.Set(Title(2), FilterTitle(mode, state.Cutoff()));
    }
    if (Titles.Changed(Main(2), mode & 7))
    {
      Titles.Set(Main(2), std::string(ModeShort(mode)));
      Cells.Set(SidCells::MODE, std::string(ModeWord(mode)));
    }
    if (Titles.Changed(Tail(2), cutoff))
    {
      auto word = CutoffWord(cutoff);
      Cells.Set(SidCells::CUTOFF, word);
      std::erase(word, ' ');
      Titles.Set(Tail(2), std::move(word));
    }
    if (Cells.Changed(SidCells::ROUTED, state.Routing() & 15))
    {
      Cells.Set(SidCells::ROUTED, RoutingWord(state.Routing()));
    }
  }
}  // namespace Visualization
