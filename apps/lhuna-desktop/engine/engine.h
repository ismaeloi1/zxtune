/**
 *
 * @file
 *
 * @brief Playback engine of desktop player: modules detection, sound output and visualization data
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "parameters/container.h"
#include "sound/scope.h"

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace Lhuna
{
  //! Single playable module (file may contain several ones, e.g. archives or multitrack formats)
  struct TrackInfo
  {
    std::string Path;
    std::string Subpath;
    std::string Title;
    std::string Author;
    std::string Type;
    std::string Program;
    std::string Computer;
    std::string Comment;
    uint32_t DurationMs = 0;

    //! Title if any, else filename with subpath
    std::string DisplayName() const;
  };

  enum class PlaybackState
  {
    STOPPED,
    PLAYING,
    PAUSED
  };

  /**
   * Qt independent player facade over zxtune core. Options container is shared with sound backends and modules
   * renderers, so emulation and trigger options are applied on the fly (as in Android app).
   */
  class Engine
  {
  public:
    Engine();
    ~Engine();

    //! Synchronized options container, changes are applied to playing tune
    Parameters::Container& Options();

    //! @return all the modules in file, empty on error
    std::vector<TrackInfo> Detect(const std::string& path, std::string* error = nullptr) const;

    //! Opens and starts track
    bool Play(const TrackInfo& track, std::string* error = nullptr);
    void Pause();
    void Resume();
    void Stop();
    void Seek(uint32_t ms);

    PlaybackState State() const;
    uint32_t PositionMs() const;
    const TrackInfo& Current() const;
    //! true once after the playing track is finished
    bool TakeFinished();

    //! Output backend id ("alsa", "win32" etc), empty if not playing
    std::string BackendId() const;
    //! Delay of output device buffers, compensated by oscilloscope
    void SetLatencyCompensation(uint32_t ms);

    //! Oscilloscope data of playing track, null if not playing
    Sound::Scope::Ptr Scope() const;

  private:
    class Impl;
    const std::unique_ptr<Impl> Delegate;
  };
}  // namespace Lhuna
