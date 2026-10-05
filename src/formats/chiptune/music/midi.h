/**
 *
 * @file
 *
 * @brief  Standard MIDI File support interface
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "formats/chiptune/common/builder_meta.h"

#include "formats/chiptune/container.h"

namespace Formats::Chiptune::Midi
{
  // Structure and metainformation only, events are interpreted by player
  class Builder
  {
  public:
    virtual ~Builder() = default;

    virtual MetaBuilder& GetMetaBuilder() = 0;
  };

  Formats::Chiptune::Container::Ptr Parse(const Binary::Container& data, Builder& target);
  Builder& GetStubBuilder();
}  // namespace Formats::Chiptune::Midi
