/**
 *
 * @file
 *
 * @brief MPRIS media controls implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/media_keys_mpris.h"

#include <QtCore/QCoreApplication>
#include <QtDBus/QDBusConnection>
#include <QtDBus/QDBusMessage>
#include <QtGui/QGuiApplication>

#include <algorithm>
#include <memory>

namespace Lhuna
{
  const auto OBJECT_PATH = "/org/mpris/MediaPlayer2";
  const auto PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player";
  const auto SERVICE_PREFIX = "org.mpris.MediaPlayer2.lhuna";
  const qlonglong US_PER_MS = 1000;

  MprisRoot::MprisRoot(QObject* parent, const MediaCommands& commands)
    : QDBusAbstractAdaptor(parent)
    , Commands(commands)
  {}

  QString MprisRoot::Identity() const
  {
    return QCoreApplication::applicationName();
  }

  QString MprisRoot::DesktopEntry() const
  {
    return QGuiApplication::desktopFileName();
  }

  QStringList MprisRoot::SupportedUriSchemes() const
  {
    return {"file"};
  }

  QStringList MprisRoot::SupportedMimeTypes() const
  {
    return {};
  }

  void MprisRoot::Raise()
  {
    Commands.Raise();
  }

  void MprisRoot::Quit()
  {
    Commands.Quit();
  }

  MprisPlayer::MprisPlayer(QObject* parent, const MediaCommands& commands)
    : QDBusAbstractAdaptor(parent)
    , Commands(commands)
  {}

  QString MprisPlayer::PlaybackStatus() const
  {
    switch (Status.State)
    {
    case PlaybackState::PLAYING:
      return "Playing";
    case PlaybackState::PAUSED:
      return "Paused";
    default:
      return "Stopped";
    }
  }

  QDBusObjectPath MprisPlayer::TrackPath() const
  {
    return QDBusObjectPath(QString("/app/zxtune/Lhuna/track/%1").arg(Status.TrackId));
  }

  QVariantMap MprisPlayer::Metadata() const
  {
    QVariantMap result;
    if (Status.State == PlaybackState::STOPPED && Status.Title.isEmpty())
    {
      // "/org/mpris/MediaPlayer2/TrackList/NoTrack" is reserved for no track
      result["mpris:trackid"] = QVariant::fromValue(QDBusObjectPath("/org/mpris/MediaPlayer2/TrackList/NoTrack"));
      return result;
    }
    result["mpris:trackid"] = QVariant::fromValue(TrackPath());
    result["mpris:length"] = qlonglong(Status.DurationMs) * US_PER_MS;
    result["xesam:title"] = Status.Title;
    if (!Status.Artist.isEmpty())
    {
      result["xesam:artist"] = QStringList{Status.Artist};
    }
    if (!Status.Album.isEmpty())
    {
      result["xesam:album"] = Status.Album;
    }
    return result;
  }

  qlonglong MprisPlayer::Position() const
  {
    return qlonglong(Commands.Position()) * US_PER_MS;
  }

  QVariantMap MprisPlayer::SetStatus(const MediaStatus& status)
  {
    QVariantMap changed;
    const auto prev = Status;
    Status = status;
    if (prev.State != status.State)
    {
      changed["PlaybackStatus"] = PlaybackStatus();
    }
    if (prev.TrackId != status.TrackId || prev.Title != status.Title || prev.Artist != status.Artist
        || prev.DurationMs != status.DurationMs || prev.Album != status.Album)
    {
      changed["Metadata"] = Metadata();
      changed["CanSeek"] = CanSeek();
    }
    if (prev.HasNext != status.HasNext)
    {
      changed["CanGoNext"] = CanGoNext();
    }
    if (prev.HasPrevious != status.HasPrevious)
    {
      changed["CanGoPrevious"] = CanGoPrevious();
    }
    return changed;
  }

  void MprisPlayer::Next()
  {
    Commands.Next();
  }

  void MprisPlayer::Previous()
  {
    Commands.Previous();
  }

  void MprisPlayer::Pause()
  {
    Commands.Pause();
  }

  void MprisPlayer::PlayPause()
  {
    Commands.PlayPause();
  }

  void MprisPlayer::Stop()
  {
    Commands.Stop();
  }

  void MprisPlayer::Play()
  {
    Commands.Play();
  }

  void MprisPlayer::Seek(qlonglong offset)
  {
    const auto target = std::clamp<qlonglong>(Position() + offset, 0, qlonglong(Status.DurationMs) * US_PER_MS);
    Commands.SeekTo(static_cast<uint32_t>(target / US_PER_MS));
  }

  void MprisPlayer::SetPosition(const QDBusObjectPath& trackId, qlonglong position)
  {
    // stale requests for previous track are ignored as specification requires
    if (trackId == TrackPath() && position >= 0 && position <= qlonglong(Status.DurationMs) * US_PER_MS)
    {
      Commands.SeekTo(static_cast<uint32_t>(position / US_PER_MS));
    }
  }

  void MprisPlayer::OpenUri(const QString& /*uri*/) {}

  class MprisMediaKeys : public MediaKeys
  {
  public:
    explicit MprisMediaKeys(MediaCommands commands)
      : Commands(std::move(commands))
      , Holder(std::make_unique<QObject>())
      , Player(new MprisPlayer(Holder.get(), Commands))
    {
      new MprisRoot(Holder.get(), Commands);
      auto bus = QDBusConnection::sessionBus();
      if (!bus.isConnected())
      {
        return;
      }
      bus.registerObject(OBJECT_PATH, Holder.get());
      // the second instance gets unique name, as specification suggests
      Registered =
          bus.registerService(SERVICE_PREFIX)
          || bus.registerService(QString("%1.instance%2").arg(SERVICE_PREFIX).arg(QCoreApplication::applicationPid()));
    }

    ~MprisMediaKeys() override
    {
      if (Registered)
      {
        auto bus = QDBusConnection::sessionBus();
        bus.unregisterObject(OBJECT_PATH);
      }
    }

    void Update(const MediaStatus& status) override
    {
      const auto changed = Player->SetStatus(status);
      if (Registered && !changed.isEmpty())
      {
        auto msg = QDBusMessage::createSignal(OBJECT_PATH, "org.freedesktop.DBus.Properties", "PropertiesChanged");
        msg << QString(PLAYER_INTERFACE) << changed << QStringList();
        QDBusConnection::sessionBus().send(msg);
      }
    }

    void Seeked(uint32_t positionMs) override
    {
      if (Registered)
      {
        emit Player->Seeked(qlonglong(positionMs) * US_PER_MS);
      }
    }

  private:
    const MediaCommands Commands;
    const std::unique_ptr<QObject> Holder;
    MprisPlayer* const Player;
    bool Registered = false;
  };

  std::unique_ptr<MediaKeys> MediaKeys::Create(QWidget* /*window*/, MediaCommands commands)
  {
    return std::make_unique<MprisMediaKeys>(std::move(commands));
  }
}  // namespace Lhuna
