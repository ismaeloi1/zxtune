/**
 *
 * @file
 *
 * @brief Playlists import and export implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/playlist_io.h"

#include <QtCore/QDir>
#include <QtCore/QFile>
#include <QtCore/QFileInfo>
#include <QtCore/QSaveFile>
#include <QtCore/QTextStream>
#include <QtCore/QUrl>
#include <QtCore/QXmlStreamReader>
#include <QtCore/QXmlStreamWriter>

#include <algorithm>

namespace Lhuna::PlaylistIo
{
  // see apps/zxtune-android/.../playlist/xspf/Schema.kt
  const auto XMLNS = "http://xspf.org/ns/0/";
  const auto APPLICATION = "http://zxtune.googlecode.com";
  // V2 does not escape string values
  const auto VERSION = "2";
  const auto PLAYLIST_CREATOR = "zxtune.app.playlist.creator";
  const auto PLAYLIST_NAME = "zxtune.app.playlist.name";
  const auto PLAYLIST_VERSION = "zxtune.app.playlist.version";
  const auto PLAYLIST_ITEMS = "zxtune.app.playlist.items";
  // see apps/zxtune-android/.../rescue/RescueFormats.kt
  const auto REL_PATH = "http://zxtune.googlecode.com/rescue/path";
  const auto REL_RELATIVE = "http://zxtune.googlecode.com/rescue/relative";
  const auto M3U_HEADER = "#EXTM3U";
  const auto M3U_INFO = "#EXTINF:";
  const auto M3U_PLAYLIST = "#PLAYLIST:";
  const auto M3U_ID = "#ZXTUNE-ID:";
  const auto M3U_TITLE = "#ZXTUNE-TITLE:";
  const auto M3U_AUTHOR = "#ZXTUNE-AUTHOR:";
  const auto M3U_DURATION = "#ZXTUNE-DURATION-MS:";
  const auto M3U_RELATIVE = "#ZXTUNE-RELATIVE:";
  // per track properties of zxtune-qt playlists
  const auto PROP_TYPE = "Type";
  const auto PROP_PROGRAM = "Program";
  const auto PROP_COMPUTER = "Computer";
  const auto PROP_COMMENT = "Comment";
  const QChar BOM(0xfeff);

  QString Q(const std::string& str)
  {
    return QString::fromStdString(str);
  }

  QString ToLocation(const TrackInfo& track)
  {
    auto url = QUrl::fromLocalFile(QDir::fromNativeSeparators(Q(track.Path)));
    if (!track.Subpath.empty())
    {
      // Android app encodes fragment completely (Uri.encode), so does it here: '#' of subtunes and '/' of archives
      url.setFragment(QString::fromLatin1(QUrl::toPercentEncoding(Q(track.Subpath))), QUrl::StrictMode);
    }
    return url.toString(QUrl::FullyEncoded);
  }

  bool FromLocation(const QString& location, TrackInfo& track)
  {
    const QUrl url(location, QUrl::TolerantMode);
    if (!url.isValid() || !url.isLocalFile())
    {
      return false;
    }
    track.Path = QDir::toNativeSeparators(url.toLocalFile()).toStdString();
    track.Subpath = url.fragment(QUrl::FullyDecoded).toStdString();
    return !track.Path.empty();
  }

  void WriteProperty(QXmlStreamWriter& xml, const QString& name, const QString& value)
  {
    xml.writeStartElement("property");
    xml.writeAttribute("name", name);
    xml.writeCharacters(value);
    xml.writeEndElement();
  }

  void WriteTag(QXmlStreamWriter& xml, const QString& name, const QString& value)
  {
    if (!value.isEmpty())
    {
      xml.writeTextElement(name, value);
    }
  }

  void WriteMeta(QXmlStreamWriter& xml, const QString& rel, const QString& value)
  {
    if (!value.isEmpty())
    {
      xml.writeStartElement("meta");
      xml.writeAttribute("rel", rel);
      xml.writeCharacters(value);
      xml.writeEndElement();
    }
  }

  bool Commit(QSaveFile& out, QString* error)
  {
    if (!out.commit())
    {
      if (error)
      {
        *error = out.errorString();
      }
      return false;
    }
    return true;
  }

  bool Open(QSaveFile& out, QString* error)
  {
    if (!out.open(QIODevice::WriteOnly))
    {
      if (error)
      {
        *error = out.errorString();
      }
      return false;
    }
    return true;
  }

  QString RelativePath(const QString& playlist, const TrackInfo& track)
  {
    return QDir(QFileInfo(playlist).absolutePath()).relativeFilePath(Q(track.Path));
  }

  bool SaveXspf(const QString& file, const QString& name, const std::vector<TrackInfo>& tracks, QString* error)
  {
    QSaveFile out(file);
    if (!Open(out, error))
    {
      return false;
    }
    QXmlStreamWriter xml(&out);
    xml.setAutoFormatting(true);
    xml.setAutoFormattingIndent(2);
    xml.writeStartDocument("1.0", true);
    xml.writeStartElement("playlist");
    xml.writeAttribute("version", "1");
    xml.writeDefaultNamespace(XMLNS);
    xml.writeStartElement("extension");
    xml.writeAttribute("application", APPLICATION);
    WriteProperty(xml, PLAYLIST_VERSION, VERSION);
    WriteProperty(xml, PLAYLIST_NAME, name);
    WriteProperty(xml, PLAYLIST_ITEMS, QString::number(tracks.size()));
    xml.writeEndElement();
    if (!tracks.empty())
    {
      xml.writeStartElement("trackList");
      for (const auto& track : tracks)
      {
        xml.writeStartElement("track");
        WriteTag(xml, "location", ToLocation(track));
        WriteTag(xml, "creator", Q(track.Author));
        WriteTag(xml, "title", Q(track.Title));
        WriteTag(xml, "duration", QString::number(track.DurationMs));
        // plain paths are for manual recovery and other players, as Android's rescue export does
        WriteMeta(xml, REL_PATH, QDir::fromNativeSeparators(Q(track.Path)));
        WriteMeta(xml, REL_RELATIVE, RelativePath(file, track));
        const std::pair<const char*, const std::string*> props[] = {{PROP_TYPE, &track.Type},
                                                                    {PROP_PROGRAM, &track.Program},
                                                                    {PROP_COMPUTER, &track.Computer},
                                                                    {PROP_COMMENT, &track.Comment}};
        // properties allow to display playlist without modules detection
        if (std::any_of(std::begin(props), std::end(props), [](const auto& prop) { return !prop.second->empty(); }))
        {
          xml.writeStartElement("extension");
          xml.writeAttribute("application", APPLICATION);
          for (const auto& [prop, value] : props)
          {
            if (!value->empty())
            {
              WriteProperty(xml, prop, Q(*value));
            }
          }
          xml.writeEndElement();
        }
        xml.writeEndElement();
      }
      xml.writeEndElement();
    }
    xml.writeEndElement();
    xml.writeEndDocument();
    return Commit(out, error);
  }

  QString OneLine(QString str)
  {
    return str.replace('\r', ' ').replace('\n', ' ');
  }

  bool SaveM3u(const QString& file, const QString& name, const std::vector<TrackInfo>& tracks, QString* error)
  {
    QSaveFile out(file);
    if (!Open(out, error))
    {
      return false;
    }
    QTextStream stream(&out);
    stream.setEncoding(QStringConverter::Utf8);
    stream << M3U_HEADER << '\n' << M3U_PLAYLIST << OneLine(name) << '\n';
    for (const auto& track : tracks)
    {
      const auto seconds = track.DurationMs > 0 ? static_cast<int>((track.DurationMs + 500) / 1000) : -1;
      QStringList display;
      for (const auto* field : {&track.Author, &track.Title})
      {
        if (!Q(*field).trimmed().isEmpty())
        {
          display << Q(*field);
        }
      }
      stream << '\n' << M3U_INFO << seconds << ',' << OneLine(display.join(" - ")) << '\n';
      stream << M3U_ID << ToLocation(track) << '\n';
      if (!track.Title.empty())
      {
        stream << M3U_TITLE << OneLine(Q(track.Title)) << '\n';
      }
      if (!track.Author.empty())
      {
        stream << M3U_AUTHOR << OneLine(Q(track.Author)) << '\n';
      }
      stream << M3U_DURATION << track.DurationMs << '\n';
      stream << M3U_RELATIVE << OneLine(RelativePath(file, track)) << '\n';
      // subpaths are meaningful for this app only, other players get the file itself
      stream << OneLine(Q(track.Path)) << '\n';
    }
    stream.flush();
    return Commit(out, error);
  }

  QString Resolve(const QString& base, const QString& path)
  {
    const auto native = QDir::fromNativeSeparators(path);
    return QDir::toNativeSeparators(QFileInfo(native).isAbsolute() ? native
                                                                   : QDir::cleanPath(QDir(base).filePath(native)));
  }

  // Entry is taken from the first resolvable source: app location, then plain absolute or relative path
  bool ResolveEntry(const QString& base, const QString& location, const QString& path, const QString& relative,
                    TrackInfo& track)
  {
    if (!location.isEmpty() && FromLocation(location, track))
    {
      if (QFileInfo::exists(Q(track.Path)) || (path.isEmpty() && relative.isEmpty()))
      {
        return true;
      }
    }
    for (const auto* candidate : {&path, &relative})
    {
      if (!candidate->isEmpty())
      {
        const auto resolved = Resolve(base, *candidate);
        if (QFileInfo::exists(resolved))
        {
          track.Path = resolved.toStdString();
          return true;
        }
      }
    }
    return !track.Path.empty();
  }

  QString Decode(const QString& str, bool escaped)
  {
    return escaped ? QUrl::fromPercentEncoding(str.toUtf8()) : str;
  }

  bool LoadXspf(const QString& file, const QByteArray& data, LoadResult& result, QString* error)
  {
    const auto base = QFileInfo(file).absolutePath();
    QXmlStreamReader xml(data);
    bool escapedTexts = true;
    bool desktopPaths = false;
    bool inTrack = false;
    QString location;
    QString path;
    QString relative;
    TrackInfo track;
    while (!xml.atEnd())
    {
      const auto token = xml.readNext();
      if (token == QXmlStreamReader::StartElement)
      {
        const auto tag = xml.name();
        if (tag == u"track")
        {
          inTrack = true;
          location.clear();
          path.clear();
          relative.clear();
          track = TrackInfo();
        }
        else if (tag == u"property")
        {
          const auto name = xml.attributes().value("name").toString();
          const auto text = xml.readElementText().trimmed();
          if (!inTrack)
          {
            if (name == PLAYLIST_VERSION)
            {
              escapedTexts = text.toInt() < 2;
            }
            else if (name == PLAYLIST_CREATOR)
            {
              desktopPaths = Decode(text, true).startsWith("zxtune-qt");
            }
            else if (name == PLAYLIST_NAME)
            {
              result.Name = Decode(text, escapedTexts);
            }
          }
          else
          {
            const auto value = Decode(text, escapedTexts).toStdString();
            if (name == PROP_TYPE)
            {
              track.Type = value;
            }
            else if (name == PROP_PROGRAM)
            {
              track.Program = value;
            }
            else if (name == PROP_COMPUTER)
            {
              track.Computer = value;
            }
            else if (name == PROP_COMMENT)
            {
              track.Comment = value;
            }
          }
        }
        else if (inTrack && tag == u"location")
        {
          location = xml.readElementText().trimmed();
        }
        else if (inTrack && tag == u"title")
        {
          track.Title = Decode(xml.readElementText(), escapedTexts).toStdString();
        }
        else if (inTrack && tag == u"creator")
        {
          track.Author = Decode(xml.readElementText(), escapedTexts).toStdString();
        }
        else if (inTrack && tag == u"duration")
        {
          track.DurationMs = xml.readElementText().trimmed().toUInt();
        }
        else if (inTrack && tag == u"meta")
        {
          const auto rel = xml.attributes().value("rel").toString();
          const auto text = xml.readElementText().trimmed();
          if (rel == REL_PATH)
          {
            path = text;
          }
          else if (rel == REL_RELATIVE)
          {
            relative = text;
          }
        }
      }
      else if (token == QXmlStreamReader::EndElement && xml.name() == u"track")
      {
        inTrack = false;
        if (desktopPaths)
        {
          // zxtune-qt stores plain (maybe relative) paths with '?' before subpath
          const auto subpos = location.indexOf('?');
          track.Path = Resolve(base, subpos == -1 ? location : location.left(subpos)).toStdString();
          track.Subpath = subpos == -1 ? std::string() : location.mid(subpos + 1).toStdString();
          result.Tracks.push_back(track);
        }
        else if (ResolveEntry(base, location, path, relative, track))
        {
          result.Tracks.push_back(track);
        }
        else
        {
          ++result.Skipped;
        }
      }
    }
    if (xml.hasError())
    {
      if (error)
      {
        *error = xml.errorString();
      }
      return !result.Tracks.empty();
    }
    return true;
  }

  bool LoadM3u(const QString& file, const QByteArray& data, LoadResult& result)
  {
    const auto base = QFileInfo(file).absolutePath();
    QString id;
    QString relative;
    TrackInfo track;
    for (auto line : QString::fromUtf8(data).split('\n'))
    {
      line = line.trimmed();
      if (line.startsWith(BOM))
      {
        line.remove(0, 1);
      }
      if (line.isEmpty())
      {
        continue;
      }
      else if (line.startsWith(M3U_ID))
      {
        id = line.mid(QString(M3U_ID).size());
      }
      else if (line.startsWith(M3U_TITLE))
      {
        track.Title = line.mid(QString(M3U_TITLE).size()).toStdString();
      }
      else if (line.startsWith(M3U_AUTHOR))
      {
        track.Author = line.mid(QString(M3U_AUTHOR).size()).toStdString();
      }
      else if (line.startsWith(M3U_DURATION))
      {
        track.DurationMs = line.mid(QString(M3U_DURATION).size()).toUInt();
      }
      else if (line.startsWith(M3U_RELATIVE))
      {
        relative = line.mid(QString(M3U_RELATIVE).size());
      }
      else if (line.startsWith(M3U_INFO))
      {
        const auto info = line.mid(QString(M3U_INFO).size());
        const auto comma = info.indexOf(',');
        if (const auto seconds = info.left(comma).trimmed().toInt(); seconds > 0)
        {
          track.DurationMs = seconds * 1000;
        }
        // display string of other players is "artist - title", the title only is unknown
        if (track.Title.empty() && comma != -1)
        {
          track.Title = info.mid(comma + 1).toStdString();
        }
      }
      else if (line.startsWith(M3U_PLAYLIST))
      {
        result.Name = line.mid(QString(M3U_PLAYLIST).size());
      }
      else if (line.startsWith('#'))
      {
        continue;
      }
      else
      {
        const QUrl url(line);
        // remote streams of other players are not supported, one letter scheme is a drive on Windows
        const auto isRemote = url.isValid() && url.scheme().size() > 1 && !url.isLocalFile();
        const auto target = isRemote ? QString() : (url.isLocalFile() ? url.toLocalFile() : line);
        if (ResolveEntry(base, id, target, relative, track))
        {
          result.Tracks.push_back(track);
        }
        else
        {
          ++result.Skipped;
        }
        id.clear();
        relative.clear();
        track = TrackInfo();
      }
    }
    return true;
  }

  bool Load(const QString& file, LoadResult& result, QString* error)
  {
    QFile in(file);
    if (!in.open(QIODevice::ReadOnly))
    {
      if (error)
      {
        *error = in.errorString();
      }
      return false;
    }
    const auto data = in.readAll();
    auto head = QString::fromUtf8(data.left(512));
    while (!head.isEmpty() && (head[0] == BOM || head[0].isSpace()))
    {
      head.remove(0, 1);
    }
    if (head.startsWith('<') || file.endsWith(".xspf", Qt::CaseInsensitive))
    {
      return LoadXspf(file, data, result, error);
    }
    return LoadM3u(file, data, result);
  }

  bool IsPlaylist(const QString& file)
  {
    for (const auto* ext : {".xspf", ".m3u", ".m3u8"})
    {
      if (file.endsWith(ext, Qt::CaseInsensitive))
      {
        return true;
      }
    }
    return false;
  }
}  // namespace Lhuna::PlaylistIo
