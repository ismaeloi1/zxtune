/**
 *
 * @file
 *
 * @brief Oscilloscope data provider implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "sound/scope.h"

#include "sound/impl/correlation_trigger.h"

#include "sound/sound_parameters.h"

#include "make_ptr.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

namespace Sound
{
  namespace ScopeDetails
  {
    // ~2.7s at 48kHz, enough to cover output buffers latency
    const uint_t RING_SIZE = 1 << 17;
    const uint_t MIN_WINDOW_MS = 1;
    const uint_t MAX_WINDOW_MS = 200;
    const uint_t MAX_VOICES = 32;
    const uint_t MAX_FULL_RATE_TRIGGERS = 8;
    // searches are spread among frames, so serial processing of not so many voices fits into frame, while waking of
    // other cores costs power and delays (e.g. if they are busy or slow)
    const uint_t MAX_SERIAL_TRIGGERS = 16;
    // Limited search rate (power saving) spreads searches among frames, the rest of voices extrapolate phase. Without
    // limit each voice is searched at each frame as corrscope does: extrapolated phase drifts (period is not exact,
    // vibrato) and is visible as jitter at frames between searches
    const uint_t MAX_SEARCHES_PER_FRAME = 4;
    const uint_t MIN_TRIGGER_MS = 5;
    const uint_t MAX_TRIGGER_MS = 200;
    // ~-66dB
    const int SILENCE_LEVEL = 16;
    // pitch estimation window for voices without frequency state
    const uint_t ESTIMATOR_MS = 40;
    // keeps scope lock short while history is filled
    const uint_t MAX_ESTIMATIONS_PER_CALL = 8;
    // Keep separate voices rendering while data is requested at least this often
    const auto ACTIVITY_TIMEOUT = std::chrono::seconds(2);

    class Ring
    {
    public:
      Ring()
        : Data(RING_SIZE)
      {}

      void Push(const int16_t* samples, uint_t count, uint_t stride)
      {
        for (uint_t idx = 0; idx < count; ++idx, samples += stride)
        {
          Data[(Count + idx) & (RING_SIZE - 1)] = *samples;
        }
        Count += count;
      }

      void Push(int16_t sample)
      {
        Data[Count++ & (RING_SIZE - 1)] = sample;
      }

      uint64_t Size() const
      {
        return Count;
      }

      int16_t At(int64_t idx) const
      {
        return idx >= 0 && uint64_t(idx) < Count && uint64_t(idx) + RING_SIZE > Count ? Data[idx & (RING_SIZE - 1)] : 0;
      }

    private:
      std::vector<int16_t> Data;
      uint64_t Count = 0;
    };

    class RingWave : public TriggerWave
    {
    public:
      RingWave(const Ring& ring, int64_t offset)
        : Data(ring)
        , Offset(offset)
      {}

      void GetPadded(int64_t begin, uint_t count, uint_t stride, float* target) const override
      {
        auto pos = begin + Offset;
        for (uint_t idx = 0; idx < count; ++idx, pos += stride)
        {
          target[idx] = Data.At(pos) * (1.0f / 32768);
        }
      }

    private:
      const Ring& Data;
      const int64_t Offset;
    };

    struct Column
    {
      uint8_t Min = 255;
      uint8_t Max = 0;

      void Add(uint8_t val)
      {
        Min = std::min(Min, val);
        Max = std::max(Max, val);
      }

      void Add(const Column& rh)
      {
        Min = std::min(Min, rh.Min);
        Max = std::max(Max, rh.Max);
      }
    };

    // Slow gauges history covers ~4s by GAUGE_COLUMNS columns, it's recalculated on each request (up to display
    // refresh rate) for all voices. So complete columns are calculated once and kept by absolute index.
    template<std::size_t Count>
    struct SlowColumn
    {
      int64_t Index = -1;
      std::array<Column, Count> Values = {};
    };

    template<std::size_t Count>
    using SlowColumns = std::array<SlowColumn<Count>, 512>;

    struct VoiceRecord
    {
      int64_t Index = 0;
      Module::VoiceState State;
    };

    struct Voice
    {
      Ring Samples;
      std::vector<int16_t> Staging;
      // Samples.Size() - master samples count at last commit
      int64_t Offset = 0;
      // state snapshots for chips without registers
      std::deque<VoiceRecord> Records;
      std::vector<VoiceRecord> RecordsStaging;
      // computed from audio slow gauges columns (level, frequency) by column index
      struct CachedColumn
      {
        int64_t Index = -1;
        uint8_t Level = 0;
        uint8_t Frequency = 0;
      };
      std::array<CachedColumn, 512> AudioColumns;
      // level and frequency
      SlowColumns<2> Slow;
    };

    // ~4s of 4ms snapshots
    // ~8s of 1ms snapshots
    const std::size_t MAX_STATES = 8192;
    // gauges scale is in CPU cycles, PAL clock is precise enough for visualization
    const uint_t CPU_CLOCK = 985248;
    const uint_t SLOW_COLUMN_CYCLES = 128 * 128;

    struct StateRecord
    {
      int64_t Index = 0;
      std::array<uint8_t, Scope::STATE_RECORD_SIZE> Data = {};
    };

    struct ChipStates
    {
      std::deque<StateRecord> Records;
      std::vector<StateRecord> Staging;
      // osc1..3, volume for each sample
      std::array<Ring, 4> Fast;
      std::array<std::vector<int16_t>, 4> FastStaging;
      // envelope1..3, frequency1..3, resonance, cutoff
      SlowColumns<8> Slow;
    };

    // logarithm per each state record is too expensive for history of thousands records
    class EnvelopeLevels
    {
    public:
      EnvelopeLevels()
      {
        for (uint_t env = 0; env < 256; ++env)
        {
          // dB scale down to -48dB
          const float level = env ? 1.0f + 20.0f * std::log10(env / 255.0f) / 48.0f : 0.0f;
          Table[env] = static_cast<uint8_t>(std::clamp(level, 0.0f, 1.0f) * 255);
        }
      }

      uint8_t operator[](uint_t env) const
      {
        return Table[env & 255];
      }

    private:
      std::array<uint8_t, 256> Table = {};
    };

    uint8_t EnvelopeLevel(uint_t env)
    {
      static const EnvelopeLevels LEVELS;
      return LEVELS[env];
    }

    uint8_t LevelOfDb(float db)
    {
      // dB scale down to -48dB
      return static_cast<uint8_t>(std::clamp(1.0f + db / 48.0f, 0.0f, 1.0f) * 255);
    }

    // logarithmic scale of 7 octaves starting from A0
    const float MIN_FREQUENCY = 27.5f;

    uint8_t LevelOfFrequency(float hz)
    {
      return hz > 0 ? static_cast<uint8_t>(std::clamp(std::log2(hz / MIN_FREQUENCY) / 7, 0.0f, 1.0f) * 255) : 0;
    }

    uint8_t FrequencyLevel(uint_t freq)
    {
      // logarithmic scale of 7 octaves
      const float level = freq ? 1.0f + std::log2(freq / 65535.0f) / 7 : 0.0f;
      return static_cast<uint8_t>(std::clamp(level, 0.0f, 1.0f) * 255);
    }

    using Clock = std::chrono::steady_clock;

    // Persistent threads to process independent tasks, caller thread participates as well
    class WorkersPool
    {
    public:
      WorkersPool() = default;

      ~WorkersPool()
      {
        {
          const std::scoped_lock guard(Lock);
          Stopping = true;
        }
        Wake.notify_all();
        for (auto& thread : Threads)
        {
          thread.join();
        }
      }

      void Run(uint_t count, bool parallel, const std::function<void(uint_t)>& task)
      {
        // threads are started on demand since scope may be created for each playback but never displayed
        if (!Started && parallel && count >= 2)
        {
          Started = true;
          // leave cores for audio rendering and UI
          const auto workers = std::clamp<uint_t>(std::thread::hardware_concurrency(), 2, 5) - 2;
          for (uint_t idx = 0; idx < workers; ++idx)
          {
            Threads.emplace_back([this]() { Work(); });
          }
        }
        if (Threads.empty() || count < 2 || !parallel)
        {
          for (uint_t idx = 0; idx < count; ++idx)
          {
            task(idx);
          }
          return;
        }
        {
          const std::scoped_lock guard(Lock);
          Task = &task;
          Count = count;
          Next = 0;
          Done = 0;
          ++Generation;
        }
        Wake.notify_all();
        Process(&task, count);
        // no worker may touch the task after return
        std::unique_lock guard(Lock);
        Finished.wait(guard, [this]() { return Done == Count && Busy == 0; });
        Task = nullptr;
      }

    private:
      void Work()
      {
        uint64_t seen = 0;
        for (;;)
        {
          const std::function<void(uint_t)>* task = nullptr;
          uint_t count = 0;
          {
            std::unique_lock guard(Lock);
            Wake.wait(guard, [&]() { return Stopping || Generation != seen; });
            if (Stopping)
            {
              return;
            }
            seen = Generation;
            task = Task;
            count = Count;
            ++Busy;
          }
          Process(task, count);
          {
            const std::scoped_lock guard(Lock);
            --Busy;
          }
          Finished.notify_one();
        }
      }

      void Process(const std::function<void(uint_t)>* task, uint_t count)
      {
        if (!task)
        {
          return;
        }
        uint_t processed = 0;
        for (uint_t idx; (idx = Next.fetch_add(1)) < count; ++processed)
        {
          (*task)(idx);
        }
        if (processed)
        {
          const std::scoped_lock guard(Lock);
          Done += processed;
        }
        Finished.notify_one();
      }

    private:
      bool Started = false;
      std::vector<std::thread> Threads;
      std::mutex Lock;
      std::condition_variable Wake;
      std::condition_variable Finished;
      bool Stopping = false;
      uint64_t Generation = 0;
      const std::function<void(uint_t)>* Task = nullptr;
      uint_t Count = 0;
      std::atomic<uint_t> Next = 0;
      uint_t Done = 0;
      uint_t Busy = 0;
    };
    struct TriggerSettings
    {
      CorrelationTrigger::Config Trigger;
      uint_t WidthMs = Parameters::ZXTune::Sound::Scope::TRIGGER_WIDTH_DEFAULT;
      uint_t Subsampling = Parameters::ZXTune::Sound::Scope::SUBSAMPLING_DEFAULT;
      uint_t MaxPeriods = Parameters::ZXTune::Sound::Scope::MAX_PERIODS_DEFAULT;

      TriggerSettings()
      {
        Trigger.MaxRate = Parameters::ZXTune::Sound::Scope::SEARCH_RATE_DEFAULT;
        Trigger.PostRadius = Parameters::ZXTune::Sound::Scope::POST_RADIUS_DEFAULT;
      }

      static TriggerSettings Load(const Parameters::Accessor& params)
      {
        using namespace Parameters::ZXTune::Sound::Scope;
        const auto get = [&params](Parameters::Identifier name, Parameters::IntType def) {
          return Parameters::GetInteger(params, name, def);
        };
        const auto ratio = [&get](Parameters::Identifier name, Parameters::IntType def, float max) {
          return std::clamp(float(get(name, def)) / PERCENTS, 0.0f, max);
        };
        TriggerSettings res;
        auto& cfg = res.Trigger;
        cfg.EdgeStrength = ratio(EDGE_STRENGTH, EDGE_STRENGTH_DEFAULT, 10);
        cfg.SlopeWidth = ratio(SLOPE_WIDTH, SLOPE_WIDTH_DEFAULT, 1);
        cfg.BufferStrength = ratio(BUFFER_STRENGTH, BUFFER_STRENGTH_DEFAULT, 10);
        cfg.Responsiveness = ratio(RESPONSIVENESS, RESPONSIVENESS_DEFAULT, 1);
        cfg.BufferFalloff = ratio(BUFFER_FALLOFF, BUFFER_FALLOFF_DEFAULT, 10);
        cfg.ResetBelow = ratio(RESET_BELOW, RESET_BELOW_DEFAULT, 1);
        cfg.MeanResponsiveness = ratio(MEAN_RESPONSIVENESS, MEAN_RESPONSIVENESS_DEFAULT, 1);
        cfg.SignStrength = ratio(SIGN_STRENGTH, SIGN_STRENGTH_DEFAULT, 10);
        // too narrow range is useless and zero breaks searching
        cfg.TriggerDiameter = std::max(ratio(DIAMETER, DIAMETER_DEFAULT, 1), 0.05f);
        cfg.EdgeDirection = get(FALLING, FALLING_DEFAULT) ? -1 : 1;
        cfg.PitchTracking = get(PITCH_TRACKING, PITCH_TRACKING_DEFAULT) != 0;
        cfg.PostRadius =
            static_cast<uint_t>(std::clamp<Parameters::IntType>(get(POST_RADIUS, POST_RADIUS_DEFAULT), 0, 64));
        cfg.MaxRate =
            static_cast<uint_t>(std::clamp<Parameters::IntType>(get(SEARCH_RATE, SEARCH_RATE_DEFAULT), 0, 1000));
        res.WidthMs = static_cast<uint_t>(
            std::clamp<Parameters::IntType>(get(TRIGGER_WIDTH, TRIGGER_WIDTH_DEFAULT), MIN_TRIGGER_MS, MAX_TRIGGER_MS));
        res.Subsampling =
            static_cast<uint_t>(std::clamp<Parameters::IntType>(get(SUBSAMPLING, SUBSAMPLING_DEFAULT), 0, 8));
        res.MaxPeriods =
            static_cast<uint_t>(std::clamp<Parameters::IntType>(get(MAX_PERIODS, MAX_PERIODS_DEFAULT), 0, 64));
        return res;
      }
    };

    // Some of the views (e.g. narrow cells of many voices) need less points than samples in window, plain
    // decimation would hide or alias high frequencies (e.g. noise looks like low tone)
    void Resample(const Ring& ring, int64_t begin, int64_t count, uint_t points, int16_t* out)
    {
      if (count <= int64_t(points))
      {
        const auto last = std::max<int64_t>(count - 1, 0);
        for (uint_t idx = 0; idx < points; ++idx)
        {
          const auto pos = points > 1 ? double(idx) * last / (points - 1) : 0.0;
          const auto lo = static_cast<int64_t>(pos);
          const auto frac = pos - lo;
          const int a = ring.At(begin + lo);
          const int b = frac > 0 ? ring.At(begin + lo + 1) : a;
          out[idx] = static_cast<int16_t>(a + std::lround((b - a) * frac));
        }
        return;
      }
      const uint_t bins = points / 2;
      for (uint_t bin = 0; bin < bins; ++bin)
      {
        const auto from = begin + count * bin / bins;
        const auto to = begin + count * (bin + 1) / bins;
        int16_t lo = ring.At(from);
        int16_t hi = lo;
        int64_t loPos = from;
        int64_t hiPos = from;
        for (auto idx = from + 1; idx < to; ++idx)
        {
          const auto smp = ring.At(idx);
          if (smp < lo)
          {
            lo = smp;
            loPos = idx;
          }
          else if (smp > hi)
          {
            hi = smp;
            hiPos = idx;
          }
        }
        out[bin * 2] = loPos <= hiPos ? lo : hi;
        out[bin * 2 + 1] = loPos <= hiPos ? hi : lo;
      }
      if (points & 1)
      {
        out[points - 1] = points > 1 ? out[points - 2] : ring.At(begin);
      }
    }

    class ScopeImpl : public Scope
    {
    public:
      ScopeImpl(uint_t samplerate, Parameters::Accessor::Ptr params)
        : Samplerate(samplerate)
        , Stride(samplerate > 32000 ? 2 : 1)
        , Params(std::move(params))
      {}

      // Render thread
      void SetVoicesGroups(const std::vector<Module::VoicesGroup>& groups, bool hasRegisters) override
      {
        const std::scoped_lock guard(Lock);
        uint_t total = 0;
        LayoutDescription = groups.empty() ? "" : (hasRegisters ? "registers" : "audio");
        for (const auto& group : groups)
        {
          const auto voices = std::min<uint_t>(static_cast<uint_t>(group.Voices.size()), MAX_VOICES - total);
          if (!voices)
          {
            break;
          }
          LayoutDescription += '\n';
          LayoutDescription += group.Name;
          for (uint_t idx = 0; idx < voices; ++idx)
          {
            LayoutDescription += '\t';
            LayoutDescription += group.Voices[idx];
          }
          total += voices;
        }
        Voices.clear();
        Voices.resize(total);
        VoicesPerChip = hasRegisters && !groups.empty() ? static_cast<uint_t>(groups.front().Voices.size()) : 0;
        Chips.clear();
        Chips.resize(VoicesPerChip ? total / VoicesPerChip : 0);
        Triggers.clear();
        // unique among all the scope instances to detect changes on track switching
        static std::atomic<uint_t> LastLayoutId;
        LayoutId = ++LastLayoutId & 0x7fff;
      }

      // Render thread, no locks - collected data is flushed on Commit
      void FeedVoices(uint_t firstVoice, const Module::VoiceState* states, uint_t count) override
      {
        for (uint_t idx = 0; idx < count; ++idx)
        {
          const auto voice = firstVoice + idx;
          if (voice < Voices.size())
          {
            auto& target = Voices[voice];
            if (target.RecordsStaging.size() < MAX_STATES)
            {
              target.RecordsStaging.push_back({int64_t(target.Samples.Size() + target.Staging.size()), states[idx]});
            }
          }
        }
      }

      uint_t GetStatePeriod() const override
      {
        return std::max<uint_t>(Samplerate / 1000, 1);
      }

      // Render thread, no locks - collected data is flushed on Commit
      void FeedChip(uint_t chip, const uint8_t* data, uint_t count) override
      {
        if (chip >= Chips.size())
        {
          return;
        }
        auto& staging = Chips[chip].FastStaging;
        if (staging[0].size() >= RING_SIZE)
        {
          return;
        }
        for (uint_t idx = 0; idx < count; ++idx, data += 4)
        {
          for (uint_t val = 0; val < 4; ++val)
          {
            staging[val].push_back(data[val]);
          }
        }
      }

      // Render thread, no locks - collected data is flushed on Commit
      void FeedState(uint_t chip, const uint8_t* registers, const uint8_t* osc, const uint8_t* env) override
      {
        if (chip >= Chips.size() || Chips[chip].Staging.size() >= MAX_STATES)
        {
          return;
        }
        const auto& voice = Voices[chip * VoicesPerChip];
        StateRecord rec;
        rec.Index = int64_t(voice.Samples.Size() + voice.Staging.size());
        std::memcpy(rec.Data.data(), registers, 0x19);
        std::memcpy(rec.Data.data() + 0x19, osc, 3);
        std::memcpy(rec.Data.data() + 0x1c, env, 3);
        Chips[chip].Staging.push_back(rec);
      }

      void SetDescription(const String& description) override
      {
        const std::scoped_lock guard(Lock);
        Description = description;
      }

      // Render thread
      bool IsActive() const override
      {
        const auto lastRequest = Clock::time_point(Clock::duration(LastRequest.load()));
        return Clock::now() - lastRequest < ACTIVITY_TIMEOUT;
      }

      // Render thread, no locks - collected data is flushed on Commit
      void Feed(uint_t firstVoice, uint_t voices, const int16_t* samples, uint_t count) override
      {
        for (uint_t idx = 0; idx < voices; ++idx)
        {
          const auto voice = firstVoice + idx;
          if (voice < Voices.size())
          {
            auto& staging = Voices[voice].Staging;
            if (staging.size() >= RING_SIZE)
            {
              // e.g. seeking, no need to keep more than ring can store
              continue;
            }
            const auto* src = samples + idx;
            for (uint_t smp = 0; smp < count; ++smp, src += voices)
            {
              staging.push_back(*src);
            }
          }
        }
      }

      void Commit(const Chunk& master) override
      {
        const std::scoped_lock guard(Lock);
        for (const auto& smp : master)
        {
          Master.Push(static_cast<int16_t>((int(smp.Left()) + smp.Right()) / 2));
        }
        for (auto& voice : Voices)
        {
          voice.Samples.Push(voice.Staging.data(), static_cast<uint_t>(voice.Staging.size()), 1);
          voice.Staging.clear();
          voice.Offset = int64_t(voice.Samples.Size()) - int64_t(Master.Size());
          voice.Records.insert(voice.Records.end(), voice.RecordsStaging.begin(), voice.RecordsStaging.end());
          voice.RecordsStaging.clear();
          while (voice.Records.size() > MAX_STATES)
          {
            voice.Records.pop_front();
          }
        }
        for (auto& chip : Chips)
        {
          chip.Records.insert(chip.Records.end(), chip.Staging.begin(), chip.Staging.end());
          chip.Staging.clear();
          for (uint_t val = 0; val < 4; ++val)
          {
            auto& staging = chip.FastStaging[val];
            chip.Fast[val].Push(staging.data(), static_cast<uint_t>(staging.size()), 1);
            staging.clear();
          }
          while (chip.Records.size() > MAX_STATES)
          {
            chip.Records.pop_front();
          }
        }
      }

      void Played(uint64_t start, uint_t samples) override
      {
        const std::scoped_lock guard(Lock);
        PlayedStart = start;
        PlayedSize = samples;
        PlayedTime = Clock::now();
        HasPlayed = true;
      }

      void Reset() override
      {
        const std::scoped_lock guard(Lock);
        ResetTriggers();
      }

      void SetLatency(uint_t ms) override
      {
        LatencySamples = int64_t(Samplerate) * ms / 1000;
      }

      Layout Get(uint_t maxChannels, uint_t points, int64_t playing, uint_t windowMs, int16_t* target) override
      {
        const auto request = Clock::now();
        LastRequest = request.time_since_epoch().count();
        const std::scoped_lock guard(Lock);
        // waiting for render thread or gauges request is not a cost of triggering, but delays frame anyway
        const auto start = Clock::now();
        WaitUsMax = std::max<int64_t>(WaitUsMax,
                                      std::chrono::duration_cast<std::chrono::microseconds>(start - request).count());
        if (!HasPlayed || !points || !maxChannels)
        {
          return {};
        }
        UpdateSettings();
        LastPlayingHint = playing;
        // precise position from output device is preferred
        const auto pos = playing >= 0 ? playing : GetPlaybackPosition();
        uint_t samplesPerFrame = Samplerate / 60;
        if (HasLastPos)
        {
          if (pos < LastPos || pos - LastPos > int64_t(Samplerate / 2))
          {
            ResetTriggers();
          }
          else
          {
            samplesPerFrame = static_cast<uint_t>(std::clamp<int64_t>(pos - LastPos, 1, Samplerate / 20));
          }
        }
        LastPos = pos;
        HasLastPos = true;

        const uint_t channels = std::min<uint_t>(Voices.empty() ? 1 : static_cast<uint_t>(Voices.size()), maxChannels);
        // many voices (e.g. OPL3) require cheaper triggering to fit into high refresh rate frames
        const auto stride = Settings.Subsampling ? Settings.Subsampling
                                                 : (channels > MAX_FULL_RATE_TRIGGERS ? Stride * 2 : Stride);
        if (stride != TriggersStride)
        {
          Triggers.clear();
          TriggersStride = stride;
        }
        while (Triggers.size() < channels)
        {
          const auto kernelSize = Samplerate * Settings.WidthMs / 1000 / stride;
          Triggers.emplace_back(new CorrelationTrigger(kernelSize, stride, Samplerate, Settings.Trigger));
        }
        const int64_t renderSamples = Samplerate * std::clamp(windowMs, MIN_WINDOW_MS, MAX_WINDOW_MS) / 1000;
        const int64_t triggerSamples = Samplerate * Settings.WidthMs / 1000;
        // Full searches are spread among frames to keep cost of each frame flat (e.g. 3 voices searched at 60 per
        // second on 120Hz display are 2+1 searches per frame instead of 3+0)
        Silent.resize(channels);
        Windows.resize(channels);
        CanSearch.resize(channels);
        const auto range = std::max(renderSamples, triggerSamples);
        for (uint_t chan = 0; chan < channels; ++chan)
        {
          const auto& ring = Voices.empty() ? Master : Voices[chan].Samples;
          const auto offset = Voices.empty() ? 0 : Voices[chan].Offset;
          // silent voices (e.g. unused chip channels) are not triggered, stable flat line is displayed
          Silent[chan] = IsSilent(ring, pos + offset - range, pos + offset + range);
        }
        const auto rate = Settings.Trigger.MaxRate;
        auto budget =
            rate ? std::min(MAX_SEARCHES_PER_FRAME,
                            std::max<uint_t>(1, (channels * rate * samplesPerFrame + Samplerate - 1) / Samplerate))
                 : channels;
        // cursor is moved inside the loop, so voices are enumerated from its initial value to not skip some of them
        const auto firstChan = SearchCursor;
        for (uint_t idx = 0; idx < channels; ++idx)
        {
          const auto chan = (firstChan + idx) % channels;
          const bool can = budget && !Silent[chan] && Triggers[chan]->IsSearchDue(samplesPerFrame);
          CanSearch[chan] = can;
          budget -= can;
          if (can)
          {
            SearchCursor = chan + 1;
          }
        }
        std::atomic<uint_t> triggered = 0;
        // channels are independent, so triggered in parallel
        // Waking of other cores costs more power than serial processing of few voices.
        // Many voices are processed in parallel to fit into high refresh rate frame
        Pool.Run(channels, channels > MAX_SERIAL_TRIGGERS, [&](uint_t chan) {
          const auto& ring = Voices.empty() ? Master : Voices[chan].Samples;
          const auto offset = Voices.empty() ? 0 : Voices[chan].Offset;
          const RingWave wave(ring, offset);
          const bool silent = Silent[chan] != 0;
          const auto trigger = silent ? pos
                                      : Triggers[chan]->GetTrigger(wave, pos, samplesPerFrame, CanSearch[chan] != 0);
          triggered += !silent;
          const auto window = GetRenderWindow(chan, renderSamples, silent ? 0 : Triggers[chan]->GetPeriodSamples());
          const auto begin = trigger - window / 2 + offset;
          auto* out = target + chan * points;
          Resample(ring, begin, window, points, out);
          // Some chips' voices are unipolar (e.g. AY/YM channels are DAC levels of 0..max) or have DC offset (e.g. idle
          // YM2612 DAC), so traces are centered by displayed range as Furnace's default DC offset correction does
          RemoveOffset(out, points);
        });
        const int64_t computeUs = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - start).count();
        ComputeUsSum += computeUs;
        ComputeUsMax = std::max(ComputeUsMax, computeUs);
        ++ComputeCalls;
        LastChannels = channels;
        LastTriggered = triggered;
        Layout result;
        result.Channels = channels;
        result.Id = LayoutId;
        return result;
      }

      // High notes in long window are tens of periods squeezed into few pixels, an unreadable band (corrscope and
      // Furnace show it so). Window is shrunk to MaxPeriods of detected pitch. Window changes are smoothed and small
      // ones ignored, so pitch estimation jitter (e.g. vibrato, octave errors on single frames) doesn't zoom picture.
      int64_t GetRenderWindow(uint_t chan, int64_t renderSamples, uint_t period)
      {
        auto& current = Windows[chan];
        if (!Settings.MaxPeriods)
        {
          return renderSamples;
        }
        const auto minSamples = int64_t(Samplerate) * MIN_WINDOW_MS / 1000;
        if (current <= 0 || current > float(renderSamples))
        {
          current = float(renderSamples);
        }
        // unknown pitch (silence, noise, note change) keeps last zoom instead of flashing full window
        if (!period)
        {
          return std::max<int64_t>(int64_t(current), minSamples);
        }
        const auto target =
            float(std::clamp<int64_t>(int64_t(period) * Settings.MaxPeriods, minSamples, renderSamples));
        if (std::abs(target - current) > current * 0.15f)
        {
          current += (target - current) * 0.25f;
        }
        return std::max<int64_t>(int64_t(current), minSamples);
      }

      uint_t GetGauges(uint_t maxChips, int64_t playing, uint_t waveWindowMs, uint8_t* target) override
      {
        // gauges may be displayed without waveforms
        LastRequest = Clock::now().time_since_epoch().count();
        const std::scoped_lock guard(Lock);
        if (!HasPlayed || Chips.empty() || VoicesPerChip == 0)
        {
          return 0;
        }
        const auto start = Clock::now();
        const auto pos = playing >= 0 ? playing : GetPlaybackPosition();
        const auto chips = std::min<uint_t>(static_cast<uint_t>(Chips.size()), maxChips);
        for (uint_t chip = 0; chip < chips; ++chip)
        {
          const auto& voice = Voices[chip * VoicesPerChip];
          FillGauges(Chips[chip], pos + voice.Offset, int64_t(voice.Samples.Size()),
                     std::clamp(waveWindowMs, MIN_WINDOW_MS, MAX_WINDOW_MS), target + std::size_t(chip) * GAUGES_SIZE);
        }
        TrackGauges(start);
        return chips;
      }

      String GetLayout() const override
      {
        const std::scoped_lock guard(Lock);
        return LayoutDescription;
      }

      uint_t GetVoiceGauges(uint_t maxVoices, int64_t playing, uint_t waveWindowMs, uint8_t* target) override
      {
        // gauges may be displayed without waveforms
        LastRequest = Clock::now().time_since_epoch().count();
        const std::scoped_lock guard(Lock);
        if (!HasPlayed || Voices.empty() || VoicesPerChip != 0)
        {
          return 0;
        }
        const auto start = Clock::now();
        const auto pos = playing >= 0 ? playing : GetPlaybackPosition();
        const auto voices = std::min<uint_t>(static_cast<uint_t>(Voices.size()), maxVoices);
        EstimatorBudget = MAX_ESTIMATIONS_PER_CALL;
        for (uint_t idx = 0; idx < voices; ++idx)
        {
          auto& voice = Voices[idx];
          FillVoiceGauges(voice, pos + voice.Offset, std::clamp(waveWindowMs, MIN_WINDOW_MS, MAX_WINDOW_MS),
                          target + std::size_t(idx) * VOICE_GAUGES_SIZE);
        }
        TrackGauges(start);
        return voices;
      }

      String GetStatus() const override
      {
        const std::scoped_lock guard(Lock);
        String result = Description;
        result += result.empty() ? "" : "\n";
        result += "scope: " + std::to_string(LastTriggered) + "/" + std::to_string(LastChannels) + " ch, trigger "
                  + std::to_string(ComputeCalls ? ComputeUsSum / ComputeCalls : 0) + "us (max "
                  + std::to_string(ComputeUsMax) + ", wait " + std::to_string(WaitUsMax) + "), sync "
                  + (LastPlayingHint >= 0 ? "device" : "estimated");
        if (GaugesCalls)
        {
          result +=
              ", gauges " + std::to_string(GaugesUsSum / GaugesCalls) + "us (max " + std::to_string(GaugesUsMax) + ")";
        }
        // searches are made not at each call, so average since last status is more informative
        ComputeUsSum = ComputeUsMax = WaitUsMax = 0;
        ComputeCalls = 0;
        GaugesUsSum = GaugesUsMax = 0;
        GaugesCalls = 0;
        return result;
      }

    private:
      // end is index of currently heard sample in chip's samples
      void FillGauges(ChipStates& chip, int64_t end, int64_t committed, uint_t waveWindowMs, uint8_t* target) const
      {
        auto store = [target](uint_t gauge, uint_t col, const Column& c) {
          auto* out = target + (gauge * GAUGE_COLUMNS + col) * 2;
          out[0] = c.Min > c.Max ? c.Max : c.Min;
          out[1] = c.Max;
        };
        // fast gauges: wave1..3 (0..2) and volume (9)
        const double fastSamples = double(Samplerate) * waveWindowMs / 1000 / GAUGE_COLUMNS;
        uint8_t prev[4] = {};
        for (uint_t col = 0; col < GAUGE_COLUMNS; ++col)
        {
          // each column contains at least one sample
          const auto from = end - int64_t((GAUGE_COLUMNS - col) * fastSamples);
          const auto to = std::max(from + 1, end - int64_t((GAUGE_COLUMNS - col - 1) * fastSamples));
          for (uint_t val = 0; val < 4; ++val)
          {
            Column c;
            // connect with previous column for continuous line
            if (col)
            {
              c.Add(prev[val]);
            }
            for (auto idx = from; idx < to; ++idx)
            {
              const auto raw = static_cast<uint8_t>(chip.Fast[val].At(idx));
              prev[val] = val == 3 ? static_cast<uint8_t>(raw * 17) : raw;
              c.Add(prev[val]);
            }
            store(val == 3 ? 9 : val, col, c);
          }
        }
        // slow gauges from state snapshots, columns are aligned to absolute position to cache them
        const auto colSamples = std::max<int64_t>(int64_t(SLOW_COLUMN_CYCLES) * Samplerate / CPU_CLOCK, 1);
        const auto current = end / colSamples;
        const auto& recs = chip.Records;
        const StateRecord* last = nullptr;
        for (uint_t col = 0; col < GAUGE_COLUMNS; ++col)
        {
          const auto colIdx = current - GAUGE_COLUMNS + 1 + col;
          const auto& values =
              GetSlowColumn(chip.Slow, colIdx, colSamples, end, committed, recs,
                            [](const StateRecord& rec, std::array<Column, 8>& cols) {
                              const auto& d = rec.Data;
                              for (uint_t voice = 0; voice < 3; ++voice)
                              {
                                cols[voice].Add(EnvelopeLevel(d[0x1c + voice]));
                                cols[3 + voice].Add(FrequencyLevel(d[voice * 7] | (d[voice * 7 + 1] << 8)));
                              }
                              cols[6].Add(static_cast<uint8_t>((d[0x17] >> 4) * 17));
                              cols[7].Add(static_cast<uint8_t>(((d[0x15] & 7) | (d[0x16] << 3)) >> 3));
                            });
          for (uint_t idx = 0; idx < 3; ++idx)
          {
            store(3 + idx, col, values[idx]);
            store(6 + idx, col, values[3 + idx]);
          }
          store(10, col, values[6]);
          store(11, col, values[7]);
        }
        // registers of currently heard moment
        auto it = std::lower_bound(recs.begin(), recs.end(), end,
                                   [](const StateRecord& rec, int64_t p) { return rec.Index < p; });
        if (it != recs.begin())
        {
          last = &*(it - 1);
        }
        auto* regs = target + GAUGES_COUNT * GAUGE_COLUMNS * 2;
        std::memset(regs, 0, STATE_RECORD_SIZE);
        if (last)
        {
          std::memcpy(regs, last->Data.data(), STATE_RECORD_SIZE);
        }
        regs[STATE_RECORD_SIZE - 1] = ColumnPhase(end, colSamples);
      }

      static uint8_t ColumnPhase(int64_t end, int64_t colSamples)
      {
        return static_cast<uint8_t>((end % colSamples) * 256 / colSamples);
      }

      // Min/max of values in column of records history (records state lasts until the next one).
      // @param committed samples count with all the records already stored, so later columns may be changed yet
      template<std::size_t Count, class Records, class Add>
      static const std::array<Column, Count>& GetSlowColumn(SlowColumns<Count>& cache, int64_t colIdx,
                                                            int64_t colSamples, int64_t end, int64_t committed,
                                                            const Records& recs, Add add)
      {
        auto& cached = cache[colIdx & (cache.size() - 1)];
        if (cached.Index == colIdx)
        {
          return cached.Values;
        }
        const auto from = colIdx * colSamples;
        const auto to = std::min(from + colSamples, end);
        cached.Values = {};
        auto it =
            std::lower_bound(recs.begin(), recs.end(), from, [](const auto& rec, int64_t p) { return rec.Index < p; });
        if (it != recs.begin())
        {
          add(*(it - 1), cached.Values);
        }
        for (; it != recs.end() && it->Index < to; ++it)
        {
          add(*it, cached.Values);
        }
        const bool complete = from + colSamples <= std::min(end, committed);
        cached.Index = complete ? colIdx : -1;
        return cached.Values;
      }

      // constant level (e.g. AY/YM channel with tone and noise disabled) is silent too
      static bool IsSilent(const Ring& ring, int64_t from, int64_t to)
      {
        int lo = ring.At(from);
        int hi = lo;
        for (auto idx = from; idx < to; ++idx)
        {
          const int smp = ring.At(idx);
          lo = std::min(lo, smp);
          hi = std::max(hi, smp);
          if (hi - lo > 2 * SILENCE_LEVEL)
          {
            return false;
          }
        }
        return true;
      }

      static void RemoveOffset(int16_t* data, uint_t count)
      {
        if (!count)
        {
          return;
        }
        const auto [lo, hi] = std::minmax_element(data, data + count);
        const auto offset = (int(*lo) + *hi) / 2;
        if (offset)
        {
          std::transform(data, data + count, data,
                         [offset](int16_t smp) { return static_cast<int16_t>(smp - offset); });
        }
      }

      // end is index of currently heard sample in voice's samples
      void FillVoiceGauges(Voice& voice, int64_t end, uint_t waveWindowMs, uint8_t* target)
      {
        auto store = [target](uint_t gauge, uint_t col, const Column& c) {
          auto* out = target + (gauge * GAUGE_COLUMNS + col) * 2;
          out[0] = c.Min > c.Max ? c.Max : c.Min;
          out[1] = c.Max;
        };
        // fast wave gauge
        {
          const double fastSamples = double(Samplerate) * waveWindowMs / 1000 / GAUGE_COLUMNS;
          // centered as oscilloscope traces are, see RemoveOffset
          int lo = 32767;
          int hi = -32768;
          for (auto idx = end - int64_t(GAUGE_COLUMNS * fastSamples); idx < end; ++idx)
          {
            const int smp = voice.Samples.At(idx);
            lo = std::min(lo, smp);
            hi = std::max(hi, smp);
          }
          const auto offset = lo <= hi ? (lo + hi) / 2 : 0;
          auto toByte = [offset](int16_t smp) {
            return static_cast<uint8_t>(std::clamp(int(smp) - offset + 32768, 0, 65535) >> 8);
          };
          uint8_t prev = 128;
          for (uint_t col = 0; col < GAUGE_COLUMNS; ++col)
          {
            const auto from = end - int64_t((GAUGE_COLUMNS - col) * fastSamples);
            const auto to = std::max(from + 1, end - int64_t((GAUGE_COLUMNS - col - 1) * fastSamples));
            Column c;
            if (col)
            {
              c.Add(prev);
            }
            for (auto idx = from; idx < to; ++idx)
            {
              prev = toByte(voice.Samples.At(idx));
              c.Add(prev);
            }
            store(0, col, c);
          }
        }
        // slow gauges, columns are aligned to absolute position to allow caching
        const auto colSamples = std::max<int64_t>(int64_t(SLOW_COLUMN_CYCLES) * Samplerate / CPU_CLOCK, 1);
        const auto current = end / colSamples;
        const auto committed = int64_t(voice.Samples.Size());
        const auto& recs = voice.Records;
        for (uint_t col = 0; col < GAUGE_COLUMNS; ++col)
        {
          const auto colIdx = current - GAUGE_COLUMNS + 1 + col;
          // empty columns are filled from audio analysis
          auto values = GetSlowColumn(voice.Slow, colIdx, colSamples, end, committed, recs,
                                      [](const VoiceRecord& rec, std::array<Column, 2>& cols) {
                                        const auto& st = rec.State;
                                        if (st.Flags & Module::VoiceState::HAS_LEVEL)
                                        {
                                          cols[0].Add(LevelOfDb(st.Level));
                                        }
                                        if (st.Flags & Module::VoiceState::HAS_FREQUENCY)
                                        {
                                          // inaudible voice has no visible pitch
                                          const bool audible = !(st.Flags & Module::VoiceState::HAS_LEVEL)
                                                               || st.Level > -48.0f;
                                          cols[1].Add(audible ? LevelOfFrequency(st.Frequency) : 0);
                                        }
                                      });
          auto& level = values[0];
          auto& freq = values[1];
          const bool noLevel = level.Min > level.Max;
          const bool noFreq = freq.Min > freq.Max;
          if (noLevel || noFreq)
          {
            const auto& cached = AnalyzeColumn(voice, colIdx, colSamples, committed);
            if (noLevel)
            {
              level.Add(cached.Level);
            }
            if (noFreq)
            {
              freq.Add(cached.Frequency);
            }
          }
          store(1, col, level);
          store(2, col, freq);
        }
        auto* state = target + VOICE_GAUGES_COUNT * GAUGE_COLUMNS * 2;
        std::memset(state, 0, VOICE_STATE_SIZE);
        // state of currently heard moment
        const auto it = std::lower_bound(recs.begin(), recs.end(), end,
                                         [](const VoiceRecord& rec, int64_t p) { return rec.Index < p; });
        if (it != recs.begin())
        {
          const auto& st = (it - 1)->State;
          std::memcpy(state, &st.Frequency, sizeof(float));
          std::memcpy(state + 4, &st.Level, sizeof(float));
          state[8] = st.Flags;
          state[9] = st.Kind;
          std::copy(st.Fields.begin(), st.Fields.end(), state + 10);
          std::copy(st.Text.begin(), st.Text.end(), state + 10 + Module::VoiceState::FIELDS);
        }
        state[VOICE_STATE_SIZE - 1] = ColumnPhase(end, colSamples);
      }

      // level and pitch of voice audio in column, for voices without state
      // @param committed samples count, later ones are not available yet
      const Voice::CachedColumn& AnalyzeColumn(Voice& voice, int64_t colIdx, int64_t colSamples, int64_t committed)
      {
        auto& cached = voice.AudioColumns[colIdx & (voice.AudioColumns.size() - 1)];
        if (cached.Index == colIdx)
        {
          return cached;
        }
        const auto from = colIdx * colSamples;
        const auto to = from + colSamples;
        int peak = 0;
        for (auto idx = from; idx < to; ++idx)
        {
          peak = std::max(peak, std::abs(int(voice.Samples.At(idx))));
        }
        bool complete = to <= committed;
        cached.Level = peak ? LevelOfDb(20 * std::log10(peak / 32768.0f)) : 0;
        cached.Frequency = 0;
        if (peak && EstimatorBudget == 0)
        {
          // will be done on next calls
          complete = false;
        }
        else if (peak)
        {
          --EstimatorBudget;
          // pitch below ~4kHz is enough to show, so input is subsampled to make FFT cheaper
          const auto stride = std::max<uint_t>(Samplerate / 12000, 1);
          if (!Estimator)
          {
            Estimator =
                std::make_unique<CorrelationTrigger>(Samplerate * ESTIMATOR_MS / 1000 / stride, stride, Samplerate);
          }
          // analyze window ending at column end, without DC
          const auto size = Samplerate * ESTIMATOR_MS / 1000 / stride;
          EstimatorData.resize(size);
          float mean = 0;
          for (uint_t idx = 0; idx < size; ++idx)
          {
            EstimatorData[idx] = voice.Samples.At(to - int64_t(size - idx) * stride) * (1.0f / 32768);
            mean += EstimatorData[idx];
          }
          mean /= size;
          for (auto& val : EstimatorData)
          {
            val -= mean;
          }
          if (const auto period = Estimator->EstimatePeriod(EstimatorData.data(), size))
          {
            cached.Frequency = LevelOfFrequency(float(Samplerate) / stride / period);
          }
        }
        cached.Index = complete ? colIdx : -1;
        return cached;
      }

      // Estimated currently playing sample in master samples index
      int64_t GetPlaybackPosition() const
      {
        // While current buffer is being rendered and written, the previous one is playing
        const auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - PlayedTime).count();
        const auto prevStart = int64_t(PlayedStart) - int64_t(PlayedSize);
        const auto pos = prevStart + elapsed * int64_t(Samplerate) / 1000000;
        const auto played = std::clamp<int64_t>(pos, std::max<int64_t>(prevStart, 0), int64_t(PlayedStart));
        return std::max<int64_t>(played - LatencySamples, 0);
      }

      void TrackGauges(Clock::time_point start)
      {
        const int64_t us = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - start).count();
        GaugesUsSum += us;
        GaugesUsMax = std::max(GaugesUsMax, us);
        ++GaugesCalls;
      }

      // new configuration is applied by triggers recreation, it's rare and done on user's action
      void UpdateSettings()
      {
        if (!Params)
        {
          return;
        }
        const auto version = Params->Version();
        if (version != ParamsVersion)
        {
          ParamsVersion = version;
          Settings = TriggerSettings::Load(*Params);
          Triggers.clear();
        }
      }

      void ResetTriggers()
      {
        for (auto& trigger : Triggers)
        {
          trigger->Reset();
        }
        HasLastPos = false;
      }

    private:
      const uint_t Samplerate;
      const uint_t Stride;
      mutable std::mutex Lock;
      Ring Master;
      std::vector<Voice> Voices;
      uint_t VoicesPerChip = 0;
      std::vector<std::unique_ptr<CorrelationTrigger>> Triggers;
      uint64_t PlayedStart = 0;
      uint_t PlayedSize = 0;
      Clock::time_point PlayedTime;
      bool HasPlayed = false;
      int64_t LastPos = 0;
      bool HasLastPos = false;
      std::atomic<Clock::rep> LastRequest = 0;
      std::vector<ChipStates> Chips;
      String Description;
      // since last status request
      mutable int64_t ComputeUsSum = 0;
      mutable int64_t ComputeUsMax = 0;
      mutable int64_t ComputeCalls = 0;
      uint_t LastChannels = 0;
      uint_t LastTriggered = 0;
      uint_t TriggersStride = 0;
      // per channel flags of current request, read by workers
      std::vector<uint8_t> Silent;
      // smoothed render window of each voice, see GetRenderWindow
      std::vector<float> Windows;
      std::vector<uint8_t> CanSearch;
      uint_t SearchCursor = 0;
      int64_t LastPlayingHint = -1;
      String LayoutDescription;
      WorkersPool Pool;
      uint_t LayoutId = 0;
      std::unique_ptr<CorrelationTrigger> Estimator;
      std::vector<float> EstimatorData;
      uint_t EstimatorBudget = 0;
      const Parameters::Accessor::Ptr Params;
      uint_t ParamsVersion = ~uint_t(0);
      TriggerSettings Settings;
      mutable int64_t WaitUsMax = 0;
      mutable int64_t GaugesUsSum = 0;
      mutable int64_t GaugesUsMax = 0;
      mutable int64_t GaugesCalls = 0;
      std::atomic<int64_t> LatencySamples = 0;
    };

  }  // namespace ScopeDetails

  Scope::Ptr Scope::Create(uint_t samplerate)
  {
    return MakePtr<ScopeDetails::ScopeImpl>(samplerate, Parameters::Accessor::Ptr());
  }

  Scope::Ptr Scope::Create(uint_t samplerate, Parameters::Accessor::Ptr params)
  {
    return MakePtr<ScopeDetails::ScopeImpl>(samplerate, std::move(params));
  }
}  // namespace Sound
