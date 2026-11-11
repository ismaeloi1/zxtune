/**
 *
 * @file
 *
 * @brief  Module properties helper interface
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "parameters/modifier.h"
#include "strings/array.h"
#include "time/duration.h"

#include "string_view.h"

namespace Formats::Chiptune
{
  class Container;
}

namespace Module
{
  class PropertiesHelper
  {
  public:
    explicit PropertiesHelper(Parameters::Modifier& delegate)
      : Delegate(delegate)
    {}

    // Generic
    void SetNonEmptyProperty(StringView name, StringView value);
    void SetBinaryProperty(StringView name, Binary::View value);

    // Common
    void SetType(StringView type);
    void SetContainer(StringView container);
    void SetSource(const Formats::Chiptune::Container& source);

    // Meta
    void SetAuthor(StringView author);
    void SetTitle(StringView title);
    void SetComment(StringView comment);
    void SetProgram(StringView program);
    void SetComputer(StringView computer);
    void SetStrings(const Strings::Array& strings);
    void SetVersion(uint_t major, uint_t minor);
    void SetVersion(StringView version);
    void SetDate(StringView date);
    void SetPlatform(StringView platform);
    // Use "${name[*}}${idx + 1}" format
    void SetChannels(const Strings::Array& names, uint_t count = 1);
    // Use "${prefix} ${idx + 1}" format
    void SetChannels(StringView prefix, uint_t count);

    // Sampled stream, zero values are not stored
    void SetStreamFormat(StringView codec, uint_t samplerate, uint_t channels, uint_t bits = 0);
    // Average over the whole stream, so container overhead is included
    void SetBitrate(uint64_t bytes, Time::Milliseconds duration);

    // Sound
    void SetFadein(Time::Milliseconds fadein);
    void SetFadeout(Time::Milliseconds fadeout);
    void SetGain(float gain);

  protected:
    Parameters::Modifier& Delegate;
  };
}  // namespace Module
