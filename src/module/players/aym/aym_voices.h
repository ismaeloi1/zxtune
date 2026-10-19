/**
 *
 * @file
 *
 * @brief  AY/YM separate channels output for visualization
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "devices/aym/chip.h"

#include "module/voices_scope.h"
#include "parameters/accessor.h"
#include "time/duration.h"

#include <vector>

namespace Module::AYM
{
  //! @brief Feeds separate channels of AY/YM chips (taken right from chip emulation, as Furnace does) to scope
  class VoicesFeed
  {
  public:
    //! @param frameDuration player interrupt period to display
    VoicesFeed(Parameters::Accessor::Ptr params, uint_t chips, Time::Microseconds frameDuration);

    //! @param scope receiver or nullptr to disable
    void SetScope(VoicesScope::Ptr scope, Devices::AYM::ChannelsSource& chip);

    //! @brief Called before chip rendering, channels are output only while consumed to save resources
    void Prepare(Devices::AYM::ChannelsSource& chip);

    //! @brief Called after chip rendering
    void Rendered(const Devices::AYM::ChannelsSource& chip);

  private:
    const Parameters::Accessor::Ptr Params;
    const uint_t Chips;
    const Time::Microseconds FrameDuration;
    VoicesScope::Ptr Scope;
    bool Active = false;
    std::vector<Devices::AYM::ChannelState> Channels;
    std::vector<VoiceState> States;
  };
}  // namespace Module::AYM
