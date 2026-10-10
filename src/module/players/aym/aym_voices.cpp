/**
 *
 * @file
 *
 * @brief  AY/YM separate channels output for visualization implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "module/players/aym/aym_voices.h"

#include "core/core_parameters.h"

#include "string_type.h"

#include <algorithm>
#include <cmath>

namespace Module::AYM
{
  VoicesFeed::VoicesFeed(Parameters::Accessor::Ptr params, uint_t chips)
    : Params(std::move(params))
    , Chips(chips)
    , Channels(chips * Devices::AYM::SOUND_CHANNELS)
    , States(chips * Devices::AYM::SOUND_CHANNELS)
  {}

  void VoicesFeed::SetScope(VoicesScope::Ptr scope, Devices::AYM::ChannelsSource& chip)
  {
    Scope = std::move(scope);
    Active = false;
    chip.SetChannelsOutput(false);
    if (!Scope)
    {
      return;
    }
    using namespace Parameters::ZXTune::Core::AYM;
    const auto type = Parameters::GetInteger(*Params, TYPE, TYPE_DEFAULT);
    const auto clock = Parameters::GetInteger(*Params, CLOCKRATE, CLOCKRATE_DEFAULT);
    const String name = type == TYPE_YM ? "YM2149F" : "AY-3-8910";
    std::vector<VoicesGroup> groups(Chips);
    for (auto& group : groups)
    {
      group.Name = name;
      group.Voices = {"A", "B", "C"};
    }
    Scope->SetVoicesGroups(groups, false);
    const auto tenthsKHz = (clock + 5000) / 10000;
    Scope->SetDescription((Chips > 1 ? std::to_string(Chips) + "x" : String()) + name + ' '
                          + std::to_string(tenthsKHz / 100) + '.' + std::to_string(tenthsKHz / 10 % 10)
                          + std::to_string(tenthsKHz % 10) + "MHz");
  }

  void VoicesFeed::Prepare(Devices::AYM::ChannelsSource& chip)
  {
    const bool active = Scope && Scope->IsActive();
    if (active != Active)
    {
      Active = active;
      chip.SetChannelsOutput(active);
    }
  }

  void VoicesFeed::Rendered(const Devices::AYM::ChannelsSource& chip)
  {
    if (!Active)
    {
      return;
    }
    const auto& samples = chip.GetChannelsOutput();
    const auto voices = static_cast<uint_t>(Channels.size());
    const auto count = static_cast<uint_t>(samples.size() / voices);
    if (!count)
    {
      return;
    }
    // registers are changed per frame (rendered block), so state is the same for the whole block
    chip.GetChannelsState(Channels.data());
    for (uint_t idx = 0; idx < voices; ++idx)
    {
      const auto& in = Channels[idx];
      auto& out = States[idx];
      out.Frequency = in.Frequency;
      out.Level = in.Level > 0 ? std::max(20 * std::log10(in.Level), -96.0f) : -96.0f;
      const bool audible = in.Level > 0 && (in.Tone || in.Noise || in.Envelope);
      out.Flags = VoiceState::HAS_LEVEL | (in.Frequency > 0 ? VoiceState::HAS_FREQUENCY : 0)
                  | (audible ? VoiceState::KEY_ON : 0) | (in.Noise ? VoiceState::NOISE : 0);
    }
    const auto period = std::max<uint_t>(Scope->GetStatePeriod(), 1);
    for (uint_t done = 0; done < count;)
    {
      const auto part = std::min(period, count - done);
      Scope->Feed(0, voices, samples.data() + std::size_t(done) * voices, part);
      done += part;
      Scope->FeedVoices(0, States.data(), voices);
    }
  }
}  // namespace Module::AYM
