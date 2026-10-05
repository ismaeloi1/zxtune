/**
 *
 * @file
 *
 * @brief  MIDI files support plugin via MT-32 emulation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "core/plugins/player_plugins_registrator.h"
#include "core/plugins/players/plugin.h"
#include "module/players/external/mt32.h"

#include "core/plugin_attrs.h"
#include "formats/chiptune/decoders.h"

#include "make_ptr.h"
#include "string_view.h"

namespace ZXTune
{
  void RegisterMIDIPlugin(PlayerPluginsRegistrator& registrator)
  {
    const auto ID = "MIDI"_id;
    // LA synthesis is a mix of PCM samples and digital synthesis
    const uint_t CAPS = Capabilities::Module::Type::TRACK | Capabilities::Module::Device::DAC;

    auto decoder = Formats::Chiptune::CreateMIDIDecoder();
    auto factory = Module::MT32::CreateFactory();
    auto plugin = CreatePlayerPlugin(ID, CAPS, std::move(decoder), std::move(factory));
    registrator.RegisterPlugin(std::move(plugin));
  }
}  // namespace ZXTune
