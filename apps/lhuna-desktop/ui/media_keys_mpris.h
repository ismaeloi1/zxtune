/**
 *
 * @file
 *
 * @brief MPRIS D-Bus interfaces of media controls on Linux
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "apps/lhuna-desktop/ui/media_keys.h"

#include <QtCore/QStringList>
#include <QtCore/QVariantMap>
#include <QtDBus/QDBusAbstractAdaptor>
#include <QtDBus/QDBusObjectPath>

namespace Lhuna
{
  //! org.mpris.MediaPlayer2, see https://specifications.freedesktop.org/mpris-spec/latest/
  class MprisRoot : public QDBusAbstractAdaptor
  {
    Q_OBJECT
    Q_CLASSINFO("D-Bus Interface", "org.mpris.MediaPlayer2")
    Q_PROPERTY(bool CanQuit READ CanQuit)
    Q_PROPERTY(bool CanRaise READ CanRaise)
    Q_PROPERTY(bool HasTrackList READ HasTrackList)
    Q_PROPERTY(QString Identity READ Identity)
    Q_PROPERTY(QString DesktopEntry READ DesktopEntry)
    Q_PROPERTY(QStringList SupportedUriSchemes READ SupportedUriSchemes)
    Q_PROPERTY(QStringList SupportedMimeTypes READ SupportedMimeTypes)
  public:
    MprisRoot(QObject* parent, const MediaCommands& commands);

    bool CanQuit() const
    {
      return true;
    }

    bool CanRaise() const
    {
      return true;
    }

    bool HasTrackList() const
    {
      return false;
    }

    QString Identity() const;
    QString DesktopEntry() const;
    QStringList SupportedUriSchemes() const;
    QStringList SupportedMimeTypes() const;

  public slots:
    void Raise();
    void Quit();

  private:
    const MediaCommands& Commands;
  };

  //! org.mpris.MediaPlayer2.Player
  class MprisPlayer : public QDBusAbstractAdaptor
  {
    Q_OBJECT
    Q_CLASSINFO("D-Bus Interface", "org.mpris.MediaPlayer2.Player")
    Q_PROPERTY(QString PlaybackStatus READ PlaybackStatus)
    Q_PROPERTY(double Rate READ Rate WRITE SetRate)
    Q_PROPERTY(double MinimumRate READ Rate)
    Q_PROPERTY(double MaximumRate READ Rate)
    Q_PROPERTY(QVariantMap Metadata READ Metadata)
    Q_PROPERTY(double Volume READ Volume WRITE SetVolume)
    Q_PROPERTY(qlonglong Position READ Position)
    Q_PROPERTY(bool CanGoNext READ CanGoNext)
    Q_PROPERTY(bool CanGoPrevious READ CanGoPrevious)
    Q_PROPERTY(bool CanPlay READ CanPlay)
    Q_PROPERTY(bool CanPause READ CanPause)
    Q_PROPERTY(bool CanSeek READ CanSeek)
    Q_PROPERTY(bool CanControl READ CanControl)
  public:
    MprisPlayer(QObject* parent, const MediaCommands& commands);

    QString PlaybackStatus() const;

    double Rate() const
    {
      return 1.0;
    }

    void SetRate(double /*rate*/) {}

    QVariantMap Metadata() const;

    double Volume() const
    {
      return 1.0;
    }

    void SetVolume(double /*volume*/) {}

    qlonglong Position() const;

    bool CanGoNext() const
    {
      return Status.HasNext;
    }

    bool CanGoPrevious() const
    {
      return Status.HasPrevious;
    }

    bool CanPlay() const
    {
      return true;
    }

    bool CanPause() const
    {
      return true;
    }

    bool CanSeek() const
    {
      return Status.DurationMs != 0;
    }

    bool CanControl() const
    {
      return true;
    }

    //! @return changed properties
    QVariantMap SetStatus(const MediaStatus& status);

  public slots:
    void Next();
    void Previous();
    void Pause();
    void PlayPause();
    void Stop();
    void Play();
    void Seek(qlonglong offset);
    void SetPosition(const QDBusObjectPath& trackId, qlonglong position);
    void OpenUri(const QString& uri);

  signals:
    void Seeked(qlonglong position);

  private:
    QDBusObjectPath TrackPath() const;

  private:
    const MediaCommands& Commands;
    MediaStatus Status;
  };
}  // namespace Lhuna
