/**
 *
 * @file
 *
 * @brief  Standard MIDI File support implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "formats/chiptune/music/midi.h"

#include "formats/chiptune/common/container.h"

#include "binary/format_factories.h"
#include "binary/input_stream.h"
#include "formats/chiptune/decoder.h"
#include "strings/sanitize.h"

#include "byteorder.h"
#include "make_ptr.h"
#include "string_view.h"

namespace Formats::Chiptune
{
  namespace Midi
  {
    const auto DESCRIPTION = "Standard MIDI File"sv;

    const uint_t MAX_TRACKS = 256;

    class Format
    {
    public:
      explicit Format(const Binary::Container& data)
        : Stream(data)
      {}

      Container::Ptr Parse(Builder& target)
      {
        if (Stream.Read<be_uint32_t>() != HEADER_ID || Stream.Read<be_uint32_t>() < 6)
        {
          return {};
        }
        const uint_t format = Stream.Read<be_uint16_t>();
        const uint_t tracks = Stream.Read<be_uint16_t>();
        const uint_t division = Stream.Read<be_uint16_t>();
        if (format > 2 || !tracks || tracks > MAX_TRACKS || !division)
        {
          return {};
        }
        for (uint_t track = 0; track < tracks;)
        {
          if (Stream.GetRestSize() < 8)
          {
            // some rips miss trailing empty tracks
            break;
          }
          const uint_t id = Stream.Read<be_uint32_t>();
          const std::size_t size = Stream.Read<be_uint32_t>();
          // last track is sometimes truncated
          const auto data = Stream.ReadData(std::min(size, Stream.GetRestSize()));
          if (id == TRACK_ID)
          {
            if (track == 0)
            {
              ParseMeta(data, target.GetMetaBuilder());
            }
            ++track;
          }
        }
        if (const auto subData = Stream.GetReadContainer())
        {
          return CreateCalculatingCrcContainer(*subData);
        }
        return {};
      }

    private:
      // Text meta events at the start of the first track
      static void ParseMeta(Binary::View track, MetaBuilder& meta)
      {
        Binary::DataInputStream stream(track);
        while (stream.GetRestSize() >= 4)
        {
          ReadVarLen(stream);
          if (stream.ReadByte() != 0xff)
          {
            break;
          }
          const auto type = stream.ReadByte();
          const auto size = ReadVarLen(stream);
          const auto text = stream.ReadData(std::min<std::size_t>(size, stream.GetRestSize()));
          const auto str = Strings::Sanitize(StringView(text.As<char>(), text.Size()));
          if (type == 0x03 && !str.empty())
          {
            meta.SetTitle(str);
          }
          else if (type == 0x02 && !str.empty())
          {
            meta.SetComment(str);
          }
          else if (type == 0x2f)
          {
            break;
          }
        }
      }

      static uint_t ReadVarLen(Binary::DataInputStream& stream)
      {
        uint_t result = 0;
        for (uint_t idx = 0; idx < 4; ++idx)
        {
          const auto byte = stream.ReadByte();
          result = (result << 7) | (byte & 0x7f);
          if (!(byte & 0x80))
          {
            break;
          }
        }
        return result;
      }

    private:
      static const uint_t HEADER_ID = 0x4d546864;  // MThd
      static const uint_t TRACK_ID = 0x4d54726b;   // MTrk
      Binary::InputStream Stream;
    };

    Formats::Chiptune::Container::Ptr Parse(const Binary::Container& data, Builder& target)
    {
      try
      {
        return Format(data).Parse(target);
      }
      catch (const std::exception&)
      {
        return {};
      }
    }

    class StubBuilder : public Builder
    {
    public:
      MetaBuilder& GetMetaBuilder() override
      {
        return GetStubMetaBuilder();
      }
    };

    Builder& GetStubBuilder()
    {
      static StubBuilder stub;
      return stub;
    }

    const auto FORMAT =
        "'M'T'h'd"
        "00000006"
        "00 00-02"  // format
        "00-01 ?"   // tracks
        "??"        // division
        "'M'T'r'k"
        ""sv;

    class Decoder : public Formats::Chiptune::Decoder
    {
    public:
      Decoder()
        : Format(Binary::CreateFormat(FORMAT))
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

      Formats::Chiptune::Container::Ptr Decode(const Binary::Container& rawData) const override
      {
        if (Format->Match(rawData))
        {
          return Parse(rawData, GetStubBuilder());
        }
        else
        {
          return {};
        }
      }

    private:
      const Binary::Format::Ptr Format;
    };
  }  // namespace Midi

  Decoder::Ptr CreateMIDIDecoder()
  {
    return MakePtr<Midi::Decoder>();
  }
}  // namespace Formats::Chiptune
