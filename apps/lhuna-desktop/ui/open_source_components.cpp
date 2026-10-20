/**
 *
 * @file
 *
 * @brief Free and open source components implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/open_source_components.h"

#include <algorithm>

namespace Lhuna::OpenSourceComponents
{
  Component Playback(std::string name, std::string authors, std::string license, std::string url, std::string dir = {})
  {
    return {std::move(name), std::move(authors), std::move(license),
            std::move(url),  Category::PLAYBACK, std::move(dir)};
  }

  Component Codec(std::string name, std::string authors, std::string license, std::string url, std::string dir = {})
  {
    return {std::move(name), std::move(authors), std::move(license), std::move(url), Category::CODECS, std::move(dir)};
  }

  Component System(std::string name, std::string authors, std::string license, std::string url, std::string dir = {})
  {
    return {std::move(name), std::move(authors), std::move(license), std::move(url), Category::SYSTEM, std::move(dir)};
  }

  Component Desktop(std::string name, std::string authors, std::string license, std::string url)
  {
    return {std::move(name), std::move(authors), std::move(license), std::move(url), Category::DESKTOP, {}};
  }

  Component Reference(std::string name, std::string authors, std::string license, std::string url)
  {
    return {std::move(name), std::move(authors), std::move(license), std::move(url), Category::REFERENCE, {}};
  }

  const std::vector<Component>& All()
  {
    // Android only components (adrenotools, liblinkernsbypass, AndroidX etc) are not used here
    static const std::vector<Component> INSTANCE = {
        Playback("ZXTune", "Vitamin/CAIG", "LGPL-3.0", "https://github.com/vitamin-caig/zxtune"),
        Playback("libsidplayfp, reSID, reSIDfp", "Leandro Nini, Antti Lankila, Dag Lem et al.", "GPL-2.0+",
                 "https://github.com/libsidplayfp/libsidplayfp", "sidplayfp"),
        Playback("SIDId and sidid.cfg signatures", "Cadaver, HVSC crew et al.", "BSD-3-Clause",
                 "https://github.com/cadaver/sidid", "sidid"),
        Playback("libvgm", "Valley Bell et al.", "GPL-2.0, BSD, MAME (per core)",
                 "https://github.com/ValleyBell/libvgm", "vgm"),
        Playback("Game_Music_Emu", "Shay Green (blargg), Chris Moeller et al.", "LGPL-2.1+",
                 "https://github.com/libgme/game-music-emu", "gme"),
        Playback("snes_spc", "Shay Green (blargg)", "LGPL-2.1+", "https://www.slack.net/~ant/libs/audio.html",
                 "snesspc"),
        Playback("Munt (mt32emu)", "Munt team", "LGPL-2.1+", "https://github.com/munt/munt", "mt32emu"),
        Playback("libopenmpt", "OpenMPT developers", "BSD-3-Clause", "https://lib.openmpt.org", "openmpt"),
        Playback("libxmp", "Claudio Matsuoka, Hipolito Carraro Jr et al.", "MIT", "https://github.com/libxmp/libxmp",
                 "xmp"),
        Playback("ASAP", "Piotr Fusik", "GPL-2.0+", "https://asap.sourceforge.net", "asap"),
        Playback("vgmstream", "vgmstream team", "ISC", "https://github.com/vgmstream/vgmstream", "vgmstream"),
        Playback("Highly Experimental", "Neill Corlett", "GPL-3.0", "https://github.com/kode54/Highly_Experimental",
                 "he"),
        Playback("Highly Theoretical", "Neill Corlett, Christopher Snowhill", "GPL-3.0",
                 "https://github.com/kode54/Highly_Theoretical", "ht"),
        Playback("lazyusf2", "Christopher Snowhill", "GPL-2.0 (Mupen64Plus)", "https://github.com/kode54/lazyusf2",
                 "lazyusf2"),
        Playback("mGBA", "Jeffrey Pfau", "MPL-2.0", "https://mgba.io", "mgba"),
        Playback("vio2sf", "Christopher Snowhill", "GPL-2.0 (DeSmuME)", "https://github.com/kode54/vio2sf", "vio2sf"),
        Playback("sseqplayer", "Naram Qashat, Christopher Snowhill", "WTFPL", "https://github.com/kode54/sseqplayer",
                 "sseqplayer"),
        Playback("HivelyTracker replayer", "Pete Gordon", "BSD-3-Clause",
                 "https://github.com/pete-gordon/hivelytracker", "hvl"),
        Playback("v2m-player", "farbrausch, Joakim L. Gilje", "Artistic-2.0", "https://github.com/jgilje/v2m-player",
                 "v2m"),
        Playback("z80ex", "Boo-boo", "GPL-2.0", "https://sourceforge.net/projects/z80ex", "z80ex"),
        Codec("FLAC", "Xiph.Org Foundation", "BSD-3-Clause", "https://xiph.org/flac", "FLAC"),
        Codec("Ogg", "Xiph.Org Foundation", "BSD-3-Clause", "https://xiph.org/ogg", "ogg"),
        Codec("Vorbis", "Xiph.Org Foundation", "BSD-3-Clause", "https://xiph.org/vorbis", "vorbis"),
        Codec("Opus", "Xiph.Org, Skype, Octasic et al.", "BSD-3-Clause", "https://opus-codec.org", "opus"),
        Codec("mpg123", "Michael Hipp, Thomas Orgis et al.", "LGPL-2.1", "https://mpg123.org", "mpg123"),
        Codec("FFmpeg", "FFmpeg developers", "LGPL-2.1+", "https://ffmpeg.org", "ffmpeg"),
        Codec("LibAtrac9", "Alex Barney", "MIT", "https://github.com/Thealexbarney/LibAtrac9", "atrac9"),
        System("zlib", "Jean-loup Gailly, Mark Adler", "Zlib", "https://zlib.net", "zlib"),
        System("LZMA SDK", "Igor Pavlov", "Public domain", "https://www.7-zip.org/sdk.html", "lzma"),
        System("lhasa", "Simon Howard", "ISC", "https://github.com/fragglet/lhasa", "lhasa"),
        System("UnRAR", "Alexander Roshal", "UnRAR license", "https://www.rarlab.com", "unrar"),
        System("{fmt}", "Victor Zverovich", "MIT", "https://fmt.dev", "fmt"),
        Desktop("Qt 6", "The Qt Company and contributors", "LGPL-3.0", "https://www.qt.io"),
        Desktop("Boost", "Boost contributors", "BSL-1.0", "https://www.boost.org"),
        Reference("corrscope (trigger algorithm)", "jimbo1qaz", "BSD-2-Clause",
                  "https://github.com/corrscope/corrscope"),
        Reference("Furnace (per-chip voices)", "tildearrow et al.", "GPL-2.0+",
                  "https://github.com/tildearrow/furnace"),
        Reference("JSIDPlay2 (SID filter presets)", "Ken Händel et al.", "GPL",
                  "https://sourceforge.net/projects/jsidplay2"),
        Reference("AYEmul", "S.Bulba", "Freeware", "https://bulba.untergrund.net/emulator_e.htm"),
    };
    return INSTANCE;
  }

  std::string Title(Category kind)
  {
    switch (kind)
    {
    case Category::PLAYBACK:
      return "Playback engines";
    case Category::CODECS:
      return "Codecs";
    case Category::SYSTEM:
      return "Archives and system";
    case Category::DESKTOP:
      return "Desktop libraries";
    default:
      return "Algorithms and data references";
    }
  }

  std::string Escape(const std::string& text)
  {
    std::string result;
    for (const auto c : text)
    {
      switch (c)
      {
      case '&':
        result += "&amp;";
        break;
      case '<':
        result += "&lt;";
        break;
      case '>':
        result += "&gt;";
        break;
      case '"':
        result += "&quot;";
        break;
      default:
        result += c;
        break;
      }
    }
    return result;
  }

  std::string ToHtml(const std::vector<Component>& components)
  {
    std::string result;
    // components are grouped by category in order of appearance
    std::vector<Category> order;
    for (const auto& c : components)
    {
      if (std::find(order.begin(), order.end(), c.Kind) == order.end())
      {
        order.push_back(c.Kind);
      }
    }
    for (const auto kind : order)
    {
      if (!result.empty())
      {
        result += "<br/>";
      }
      result += "<big><b>" + Title(kind) + "</b></big><br/>";
      for (const auto& c : components)
      {
        if (c.Kind != kind)
        {
          continue;
        }
        const auto shortUrl = c.Url.starts_with("https://") ? c.Url.substr(8) : c.Url;
        result += "<b>" + Escape(c.Name) + "</b> &#8212; " + Escape(c.Authors);
        result += "<br/>&#160;&#160;" + Escape(c.License) + " &#183; ";
        result += "<a href=\"" + c.Url + "\">" + Escape(shortUrl) + "</a><br/>";
      }
    }
    return result;
  }
}  // namespace Lhuna::OpenSourceComponents
