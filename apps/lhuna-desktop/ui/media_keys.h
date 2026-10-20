/**
 *
 * @file
 *
 * @brief System media controls integration: MPRIS on Linux, global media keys on Windows
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "apps/lhuna-desktop/engine/engine.h"

#include <QtCore/QString>

#include <functional>
#include <memory>

class QWidget;

namespace Lhuna
{
  struct MediaCommands
  {
    std::function<void()> PlayPause;
    std::function<void()> Play;
    std::function<void()> Pause;
    std::function<void()> Stop;
    std::function<void()> Next;
    std::function<void()> Previous;
    std::function<void()> Raise;
    std::function<void()> Quit;
    std::function<void(uint32_t)> SeekTo;
    std::function<uint32_t()> Position;
  };

  struct MediaStatus
  {
    PlaybackState State = PlaybackState::STOPPED;
    QString Title;
    QString Artist;
    QString Album;
    uint32_t DurationMs = 0;
    //! changes on each new track, so desktop shell can tell tracks apart
    int TrackId = 0;
    bool HasNext = false;
    bool HasPrevious = false;
  };

  class MediaKeys
  {
  public:
    virtual ~MediaKeys() = default;

    virtual void Update(const MediaStatus& status) = 0;
    //! Position jumped (seek), not the regular playback progress
    virtual void Seeked(uint32_t positionMs) = 0;

    //! @return platform implementation, stub if not supported or not available (e.g. no session bus)
    static std::unique_ptr<MediaKeys> Create(QWidget* window, MediaCommands commands);
  };
}  // namespace Lhuna
