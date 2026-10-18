/**
 *
 * @file
 *
 * @brief  SNSF parser implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "formats/chiptune/emulation/supernessoundformat.h"

#include "binary/format_factories.h"
#include "binary/input_stream.h"

#include "byteorder.h"
#include "make_ptr.h"

namespace Formats::Chiptune
{
  namespace SuperNESSoundFormat
  {
    const auto DESCRIPTION = "Super NES Sound Format"sv;

    // http://snsf.caitsith2.net/snsf%20spec.txt
    /*
    Offset         Size    Description
    0x00000000     4       SNSF_Offset
    0x00000004     4       Size of Rom.
    0x00000008     XX      The Rom data itself.
    */
    void ParseRom(Binary::View data, Builder& target)
    {
      Binary::DataInputStream stream(data);
      const uint32_t offset = stream.Read<le_uint32_t>();
      const std::size_t size = stream.Read<le_uint32_t>();
      target.SetRom(offset, stream.ReadData(std::min(size, stream.GetRestSize())));
    }

    /*
    Offset         Size    Description
    0x00000000     4       Data Type (0 - SRAM, 0xffffffff - end)
    0x00000004     4       Size of Data
    0x00000008     XX      Data block.

    SRAM block:
    0x00000000     4       Offset of SRAM to load
    0x00000004     XX      SRAM data.
    */
    void ParseReserved(Binary::View data, Builder& target)
    {
      const uint32_t SRAM = 0;
      const uint32_t END = 0xffffffff;
      Binary::DataInputStream stream(data);
      // spec allows to ignore less than 9 bytes
      while (stream.GetRestSize() > 8)
      {
        const uint32_t type = stream.Read<le_uint32_t>();
        const std::size_t size = std::min<std::size_t>(stream.Read<le_uint32_t>(), stream.GetRestSize());
        if (type == END)
        {
          break;
        }
        const auto block = stream.ReadData(size);
        if (type == SRAM && size > sizeof(uint32_t))
        {
          Binary::DataInputStream sram(block);
          const uint32_t offset = sram.Read<le_uint32_t>();
          target.SetSram(offset, sram.ReadRestData());
        }
      }
    }

    const auto FORMAT =
        "'P'S'F"
        "23"
        ""sv;

    class Decoder : public Formats::Chiptune::Decoder
    {
    public:
      Decoder()
        : Format(Binary::CreateMatchOnlyFormat(FORMAT))
      {}

      StringView GetDescription() const override
      {
        return DESCRIPTION;
      }

      Binary::Format::Ptr GetFormat() const override
      {
        return Format;
      }

      bool Check(Binary::View rawData) const override
      {
        return Format->Match(rawData);
      }

      Formats::Chiptune::Container::Ptr Decode(const Binary::Container& /*rawData*/) const override
      {
        return {};  // TODO
      }

    private:
      const Binary::Format::Ptr Format;
    };
  }  // namespace SuperNESSoundFormat

  Decoder::Ptr CreateSNSFDecoder()
  {
    return MakePtr<SuperNESSoundFormat::Decoder>();
  }
}  // namespace Formats::Chiptune
