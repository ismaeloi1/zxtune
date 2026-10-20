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
#include "sound/analyzer.h"
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

  //! Sound output subsystem (core sound backend)
  struct OutputBackend
  {
    std::string Id;
    std::string Description;
    //! false if system libraries are missing or failed to initialize
    bool Available = false;
    std::string Status;
  };

  //! Output device of backend, empty id is system default
  struct OutputDevice
  {
    std::string Id;
    std::string Name;
  };

  //! Core plugin (format or container support)
  struct PluginInfo
  {
    std::string Id;
    std::string Description;
    bool Container = false;
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
    //! Output buffering delay estimated by current (or preferred if stopped) backend options, 0 if unknown (e.g.
    //! PulseAudio simple API does not report its buffers)
    uint32_t EstimatedLatencyMs() const;

    //! Playback backends of the platform (stubs and file writers are not listed)
    std::vector<OutputBackend> Backends() const;
    //! @return devices supported by backend, empty if device can't be selected
    static std::vector<OutputDevice> Devices(const std::string& backend);
    //! Applied on next Play call
    //! @param backend preferred backend id, empty for automatic choice
    //! @param device device id of preferred backend, empty for default
    void SetOutput(const std::string& backend, const std::string& device);

    //! Oscilloscope data of playing track, null if not playing
    Sound::Scope::Ptr Scope() const;
    //! Spectrum of playing track, null if not playing
    Sound::Analyzer::Ptr Analyzer() const;

    static std::vector<PluginInfo> Plugins();
    //! @return format description by module type (plugin id), e.g. "ProTracker v3.x"
    static std::string FormatName(const std::string& type);

  private:
    class Impl;
    const std::unique_ptr<Impl> Delegate;
  };
}  // namespace Lhuna
