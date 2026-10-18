/**
 *
 * @file
 *
 * @brief  SNSF parser interface
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "binary/view.h"
#include "formats/chiptune/decoder.h"

namespace Formats::Chiptune
{
  namespace SuperNESSoundFormat
  {
    const uint_t VERSION_ID = 0x23;

    class Builder
    {
    public:
      virtual ~Builder() = default;

      virtual void SetRom(uint32_t offset, Binary::View content) = 0;
      virtual void SetSram(uint32_t offset, Binary::View content) = 0;
    };

    // unpacked program section
    void ParseRom(Binary::View data, Builder& target);
    // reserved section
    void ParseReserved(Binary::View data, Builder& target);
  }  // namespace SuperNESSoundFormat

  Decoder::Ptr CreateSNSFDecoder();
}  // namespace Formats::Chiptune
