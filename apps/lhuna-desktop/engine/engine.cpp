/**
 *
 * @file
 *
 * @brief Playback engine of desktop player implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/engine/engine.h"

#include "module/properties/path.h"
#include "sound/backends/alsa.h"
#include "sound/backends/dsound.h"
#include "sound/backends/win32.h"

#include "core/additional_files_resolve.h"
#include "core/data_location.h"
#include "core/module_detect.h"
#include "core/plugin.h"
#include "core/plugin_attrs.h"
#include "core/service.h"
#include "io/api.h"
#include "module/attributes.h"
#include "parameters/merged_container.h"
#include "sound/backend.h"
#include "sound/backend_attrs.h"
#include "sound/backends_parameters.h"
#include "sound/service.h"
#include "sound/sound_parameters.h"
#include "tools/progress_callback.h"

#include "error.h"
#include "make_ptr.h"

#include <atomic>
#include <map>
#include <mutex>

namespace Lhuna
{
  // Options are read by playback thread while UI changes them
  class SynchronizedContainer : public Parameters::Container
  {
  public:
    uint_t Version() const override
    {
      const std::scoped_lock guard(Lock);
      return Delegate->Version();
    }

    std::optional<Parameters::IntType> FindInteger(Parameters::Identifier name) const override
    {
      const std::scoped_lock guard(Lock);
      return Delegate->FindInteger(name);
    }

    std::optional<Parameters::StringType> FindString(Parameters::Identifier name) const override
    {
      const std::scoped_lock guard(Lock);
      return Delegate->FindString(name);
    }

    Binary::Data::Ptr FindData(Parameters::Identifier name) const override
    {
      const std::scoped_lock guard(Lock);
      return Delegate->FindData(name);
    }

    void Process(Parameters::Visitor& visitor) const override
    {
      // visitor is called without lock to allow it to access this container
      const auto snapshot = [this]() {
        const std::scoped_lock guard(Lock);
        return Container::Clone(*Delegate);
      }();
      snapshot->Process(visitor);
    }

    void SetValue(Parameters::Identifier name, Parameters::IntType val) override
    {
      const std::scoped_lock guard(Lock);
      Delegate->SetValue(name, val);
    }

    void SetValue(Parameters::Identifier name, StringView val) override
    {
      const std::scoped_lock guard(Lock);
      Delegate->SetValue(name, val);
    }

    void SetValue(Parameters::Identifier name, Binary::View val) override
    {
      const std::scoped_lock guard(Lock);
      Delegate->SetValue(name, val);
    }

    void RemoveValue(Parameters::Identifier name) override
    {
      const std::scoped_lock guard(Lock);
      Delegate->RemoveValue(name);
    }

  private:
    mutable std::mutex Lock;
    const Parameters::Container::Ptr Delegate = Parameters::Container::Create();
  };

  std::string TrackInfo::DisplayName() const
  {
    if (!Title.empty())
    {
      return Author.empty() ? Title : Author + " - " + Title;
    }
    const auto slash = Path.find_last_of("/\\");
    auto name = slash == std::string::npos ? Path : Path.substr(slash + 1);
    return Subpath.empty() ? name : name + " > " + Subpath;
  }

  std::string GetString(const Parameters::Accessor& props, StringView name)
  {
    return props.FindString(name).value_or(String());
  }

  TrackInfo MakeTrackInfo(const std::string& path, StringView subpath, const Module::Holder& holder)
  {
    const auto props = holder.GetModuleProperties();
    TrackInfo info;
    info.Path = path;
    info.Subpath = subpath;
    info.Title = GetString(*props, Module::ATTR_TITLE);
    info.Author = GetString(*props, Module::ATTR_AUTHOR);
    info.Type = GetString(*props, Module::ATTR_TYPE);
    info.Program = GetString(*props, Module::ATTR_PROGRAM);
    info.Computer = GetString(*props, Module::ATTR_COMPUTER);
    info.Comment = GetString(*props, Module::ATTR_COMMENT);
    info.DurationMs = holder.GetModuleInformation().Duration.Get();
    return info;
  }

  // Additional files (e.g. samples of some formats) are looked for near the module
  class FolderFilesSource : public Module::AdditionalFilesSource
  {
  public:
    FolderFilesSource(const Parameters::Accessor& params, std::string dir)
      : Params(params)
      , Dir(std::move(dir))
    {}

    Binary::Container::Ptr Get(StringView name) const override
    {
      return IO::OpenData(Dir + String(name), Params, Log::ProgressCallback::Stub());
    }

  private:
    const Parameters::Accessor& Params;
    const std::string Dir;
  };

  class CollectingCallback : public Module::DetectCallback
  {
  public:
    CollectingCallback(const Parameters::Accessor& params, std::string path)
      : Params(params)
      , Path(std::move(path))
    {}

    Parameters::Container::Ptr CreateInitialProperties(StringView subpath) const override
    {
      auto id = IO::ResolveUri(Path)->WithSubpath(subpath);
      return Parameters::CreateMergedContainer(Module::CreatePathProperties(std::move(id)),
                                               Parameters::Container::Create());
    }

    void ProcessModule(const ZXTune::DataLocation& location, const ZXTune::Plugin& /*decoder*/,
                       Module::Holder::Ptr holder) override
    {
      const auto subpath = location.GetPath()->AsString();
      if (subpath.empty())
      {
        if (const auto* const files = dynamic_cast<const Module::AdditionalFiles*>(holder.get()))
        {
          const auto slash = Path.find_last_of("/\\");
          const FolderFilesSource source(Params,
                                         slash == std::string::npos ? std::string() : Path.substr(0, slash + 1));
          Module::ResolveAdditionalFiles(source, *files);
        }
      }
      Tracks.push_back(MakeTrackInfo(Path, subpath, *holder));
      Holders.push_back(std::move(holder));
    }

    Log::ProgressCallback* GetProgress() const override
    {
      return nullptr;
    }

    std::vector<TrackInfo> Tracks;
    std::vector<Module::Holder::Ptr> Holders;

  private:
    const Parameters::Accessor& Params;
    const std::string Path;
  };

  // ALSA backend has explicit latency (PulseAudio simple API buffers up to 2s by default), so oscilloscope is
  // synchronized with output exactly. Other systems' backends follow.
  const std::string DEFAULT_BACKENDS_ORDER = "alsa;win32;dsound;sdl;paudio;openal;oss";
  // Most of the players render by 20ms frames (50Hz), backends with buffers count queue such a chunks
  const uint32_t TYPICAL_CHUNK_MS = 20;

  uint32_t QueuedChunksLatency(const Parameters::Accessor& params, Parameters::Identifier name, Parameters::IntType def)
  {
    // the chunk being written is the one following played one (see Sound::Scope::Played), others are in queue
    const auto buffers = Parameters::GetInteger(params, name, def);
    return buffers > 1 ? static_cast<uint32_t>((buffers - 1) * TYPICAL_CHUNK_MS) : 0;
  }

  class Engine::Impl : public Sound::BackendCallback
  {
  public:
    Impl()
      : Params(MakePtr<SynchronizedContainer>())
      , Service(ZXTune::Service::Create(Params))
    {
      Params->SetValue(Parameters::ZXTune::Sound::Backends::ORDER, StringView(DEFAULT_BACKENDS_ORDER));
      Params->SetValue(Parameters::ZXTune::Sound::LOOPED, Parameters::IntType(0));
      Sounds = Sound::CreateSystemService(Params);
    }

    ~Impl() override
    {
      Stop();
    }

    Parameters::Container& Options()
    {
      return *Params;
    }

    std::vector<TrackInfo> Detect(const std::string& path, std::string* error) const
    {
      CollectingCallback callback(*Params, path);
      try
      {
        const auto id = IO::ResolveUri(path);
        auto data = IO::OpenData(id->Path(), *Params, Log::ProgressCallback::Stub());
        if (id->Subpath().empty())
        {
          Service->DetectModules(std::move(data), callback);
        }
        else
        {
          Service->OpenModule(std::move(data), id->Subpath(), callback);
        }
      }
      catch (const Error& e)
      {
        if (error)
        {
          *error = e.ToString();
        }
      }
      return std::move(callback.Tracks);
    }

    bool Play(const TrackInfo& track, std::string* error)
    {
      Stop();
      try
      {
        auto data = IO::OpenData(track.Path, *Params, Log::ProgressCallback::Stub());
        CollectingCallback callback(*Params, track.Path);
        Service->OpenModule(std::move(data), track.Subpath, callback);
        if (callback.Holders.empty())
        {
          throw Error(THIS_LINE, "No module found");
        }
        const auto holder = callback.Holders.front();
        std::string errors;
        for (const auto& id : Sounds->GetAvailableBackends())
        {
          try
          {
            Backend = Sounds->CreateBackend(id, holder, Self);
            BackendName = String(id);
            break;
          }
          catch (const Error& e)
          {
            errors += e.ToString();
          }
        }
        if (!Backend)
        {
          throw Error(THIS_LINE, "No sound output available: " + errors);
        }
        CurrentTrack = callback.Tracks.front();
        CurrentTrack.Path = track.Path;
        CurrentTrack.Subpath = track.Subpath;
        Finished = false;
        Backend->GetScope()->SetLatency(LatencyMs);
        Backend->GetPlaybackControl()->Play();
        return true;
      }
      catch (const Error& e)
      {
        Backend.reset();
        if (error)
        {
          *error = e.ToString();
        }
        return false;
      }
    }

    void Pause()
    {
      if (Backend)
      {
        Backend->GetPlaybackControl()->Pause();
      }
    }

    void Resume()
    {
      if (Backend)
      {
        Backend->GetPlaybackControl()->Play();
      }
    }

    void Stop()
    {
      if (Backend)
      {
        try
        {
          Backend->GetPlaybackControl()->Stop();
        }
        catch (const Error&)
        {}
        Backend.reset();
        BackendName.clear();
      }
    }

    void Seek(uint32_t ms)
    {
      if (Backend)
      {
        Backend->GetPlaybackControl()->SetPosition(Time::AtMillisecond(ms));
      }
    }

    PlaybackState State() const
    {
      if (!Backend)
      {
        return PlaybackState::STOPPED;
      }
      switch (Backend->GetPlaybackControl()->GetPlaybackState())
      {
      case Sound::PlaybackControl::STARTED:
        return PlaybackState::PLAYING;
      case Sound::PlaybackControl::PAUSED:
        return PlaybackState::PAUSED;
      default:
        return PlaybackState::STOPPED;
      }
    }

    uint32_t PositionMs() const
    {
      return Backend ? Backend->GetPlaybackControl()->GetModuleState().At.Get() : 0;
    }

    const TrackInfo& Current() const
    {
      return CurrentTrack;
    }

    bool TakeFinished()
    {
      return Finished.exchange(false);
    }

    std::string GetBackendId() const
    {
      return BackendName;
    }

    void SetLatencyCompensation(uint32_t ms)
    {
      LatencyMs = ms;
      if (Backend)
      {
        Backend->GetScope()->SetLatency(ms);
      }
    }

    Sound::Scope::Ptr GetScope() const
    {
      return Backend ? Backend->GetScope() : Sound::Scope::Ptr();
    }

    Sound::Analyzer::Ptr GetAnalyzer() const
    {
      return Backend ? Backend->GetAnalyzer() : Sound::Analyzer::Ptr();
    }

    uint32_t EstimatedLatencyMs() const
    {
      using namespace Parameters::ZXTune::Sound::Backends;
      auto id = BackendName.empty() ? PreferredBackend : BackendName;
      if (id.empty())
      {
        const auto available = Sounds->GetAvailableBackends();
        if (!available.empty())
        {
          id = String(available.front());
        }
      }
      // device buffer is filled by blocking writes, so the heard sample is the whole buffer behind
      if (id == Sound::Alsa::BACKEND_ID)
      {
        return static_cast<uint32_t>(Parameters::GetInteger(*Params, Alsa::LATENCY, Alsa::LATENCY_DEFAULT));
      }
      else if (id == Sound::DirectSound::BACKEND_ID)
      {
        return static_cast<uint32_t>(
            Parameters::GetInteger(*Params, DirectSound::LATENCY, DirectSound::LATENCY_DEFAULT));
      }
      else if (id == Sound::Win32::BACKEND_ID)
      {
        return QueuedChunksLatency(*Params, Win32::BUFFERS, Win32::BUFFERS_DEFAULT);
      }
      else if (id == "sdl")
      {
        return QueuedChunksLatency(*Params, Sdl::BUFFERS, Sdl::BUFFERS_DEFAULT);
      }
      else if (id == "openal")
      {
        return QueuedChunksLatency(*Params, OpenAl::BUFFERS, OpenAl::BUFFERS_DEFAULT);
      }
      return 0;
    }

    std::vector<OutputBackend> Backends() const
    {
      std::vector<OutputBackend> result;
      for (const auto& info : Sounds->EnumerateBackends())
      {
        if ((info->Capabilities() & Sound::CAP_TYPE_MASK) != Sound::CAP_TYPE_SYSTEM)
        {
          continue;
        }
        OutputBackend backend;
        backend.Id = String(info->Id());
        backend.Description = info->Description();
        if (const auto status = info->Status())
        {
          backend.Status = status.GetText();
        }
        else
        {
          backend.Available = true;
        }
        result.push_back(std::move(backend));
      }
      return result;
    }

    void SetOutput(const std::string& backend, const std::string& device)
    {
      using namespace Parameters::ZXTune::Sound::Backends;
      PreferredBackend = backend;
      const auto order = backend.empty() ? DEFAULT_BACKENDS_ORDER : backend + ";" + DEFAULT_BACKENDS_ORDER;
      Params->SetValue(ORDER, StringView(order));
      // device of other backends is reset, so automatic fallback uses defaults
      Params->RemoveValue(Alsa::DEVICE);
      Params->RemoveValue(DirectSound::DEVICE);
      Params->RemoveValue(Win32::DEVICE);
      if (device.empty())
      {
        return;
      }
      if (backend == Sound::Alsa::BACKEND_ID)
      {
        Params->SetValue(Alsa::DEVICE, StringView(device));
      }
      else if (backend == Sound::DirectSound::BACKEND_ID)
      {
        Params->SetValue(DirectSound::DEVICE, StringView(device));
      }
      else if (backend == Sound::Win32::BACKEND_ID)
      {
        try
        {
          Params->SetValue(Win32::DEVICE, Parameters::IntType(std::stoi(device)));
        }
        catch (const std::exception&)
        {}
      }
    }

    // Sound::BackendCallback, called from playback thread
    void OnStart() override {}
    void OnFrame(const Module::State& /*state*/) override {}
    void OnStop() override {}
    void OnPause() override {}
    void OnResume() override {}

    void OnFinish() override
    {
      Finished = true;
    }

    // backend keeps callback by shared pointer, while engine owns backend
    Sound::BackendCallback::Ptr Self;

  private:
    const Parameters::Container::Ptr Params;
    const ZXTune::Service::Ptr Service;
    Sound::Service::Ptr Sounds;
    Sound::Backend::Ptr Backend;
    std::string BackendName;
    std::string PreferredBackend;
    TrackInfo CurrentTrack;
    std::atomic<bool> Finished = false;
    uint32_t LatencyMs = 0;
  };

  Engine::Engine()
    : Delegate(std::make_unique<Impl>())
  {
    // non-owning pointer, see Impl::Self
    Delegate->Self = Sound::BackendCallback::Ptr(Delegate.get(), [](auto*) {});
  }

  Engine::~Engine() = default;

  Parameters::Container& Engine::Options()
  {
    return Delegate->Options();
  }

  std::vector<TrackInfo> Engine::Detect(const std::string& path, std::string* error) const
  {
    return Delegate->Detect(path, error);
  }

  bool Engine::Play(const TrackInfo& track, std::string* error)
  {
    return Delegate->Play(track, error);
  }

  void Engine::Pause()
  {
    Delegate->Pause();
  }

  void Engine::Resume()
  {
    Delegate->Resume();
  }

  void Engine::Stop()
  {
    Delegate->Stop();
  }

  void Engine::Seek(uint32_t ms)
  {
    Delegate->Seek(ms);
  }

  PlaybackState Engine::State() const
  {
    return Delegate->State();
  }

  uint32_t Engine::PositionMs() const
  {
    return Delegate->PositionMs();
  }

  const TrackInfo& Engine::Current() const
  {
    return Delegate->Current();
  }

  bool Engine::TakeFinished()
  {
    return Delegate->TakeFinished();
  }

  std::string Engine::BackendId() const
  {
    return Delegate->GetBackendId();
  }

  void Engine::SetLatencyCompensation(uint32_t ms)
  {
    Delegate->SetLatencyCompensation(ms);
  }

  Sound::Scope::Ptr Engine::Scope() const
  {
    return Delegate->GetScope();
  }

  Sound::Analyzer::Ptr Engine::Analyzer() const
  {
    return Delegate->GetAnalyzer();
  }

  uint32_t Engine::EstimatedLatencyMs() const
  {
    return Delegate->EstimatedLatencyMs();
  }

  std::vector<OutputBackend> Engine::Backends() const
  {
    return Delegate->Backends();
  }

  std::vector<OutputDevice> Engine::Devices(const std::string& backend)
  {
    std::vector<OutputDevice> result;
    try
    {
      if (backend == Sound::Alsa::BACKEND_ID)
      {
        for (auto it = Sound::Alsa::EnumerateDevices(); it->IsValid(); it->Next())
        {
          const auto dev = it->Get();
          result.push_back({dev->Id(), dev->CardName() + " - " + dev->Name()});
        }
      }
      else if (backend == Sound::DirectSound::BACKEND_ID)
      {
        for (auto it = Sound::DirectSound::EnumerateDevices(); it->IsValid(); it->Next())
        {
          const auto dev = it->Get();
          result.push_back({dev->Id(), dev->Name()});
        }
      }
      else if (backend == Sound::Win32::BACKEND_ID)
      {
        for (auto it = Sound::Win32::EnumerateDevices(); it->IsValid(); it->Next())
        {
          const auto dev = it->Get();
          result.push_back({std::to_string(dev->Id()), dev->Name()});
        }
      }
    }
    catch (const Error&)
    {
      // e.g. no system library, the default device only
    }
    return result;
  }

  void Engine::SetOutput(const std::string& backend, const std::string& device)
  {
    Delegate->SetOutput(backend, device);
  }

  std::vector<PluginInfo> Engine::Plugins()
  {
    class Collector : public ZXTune::PluginVisitor
    {
    public:
      void Visit(const ZXTune::Plugin& plugin) override
      {
        const auto category = plugin.Capabilities() & ZXTune::Capabilities::Category::MASK;
        Result.push_back(
            {String(plugin.Id()), String(plugin.Description()), category == ZXTune::Capabilities::Category::CONTAINER});
      }

      std::vector<PluginInfo> Result;
    };
    static const auto INSTANCE = [] {
      Collector collector;
      ZXTune::EnumeratePlugins(collector);
      return std::move(collector.Result);
    }();
    return INSTANCE;
  }

  std::string Engine::FormatName(const std::string& type)
  {
    static const auto INSTANCE = [] {
      std::map<std::string, std::string> result;
      for (const auto& plugin : Plugins())
      {
        if (!plugin.Container)
        {
          result.emplace(plugin.Id, plugin.Description);
        }
      }
      return result;
    }();
    const auto it = INSTANCE.find(type);
    return it != INSTANCE.end() ? it->second : std::string();
  }
}  // namespace Lhuna
