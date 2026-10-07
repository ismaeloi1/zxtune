/**
 *
 * @file
 *
 * @brief Oscilloscope data provider implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/zxtune-android/zxtune/src/main/jni/scope.h"

#include "sound/impl/correlation_trigger.h"

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

namespace Player
{
  namespace ScopeDetails
  {
    // ~2.7s at 48kHz, enough to cover output buffers latency
    const uint_t RING_SIZE = 1 << 17;
    const uint_t TRIGGER_MS = 40;
    const uint_t MIN_WINDOW_MS = 1;
    const uint_t MAX_WINDOW_MS = 200;
    const uint_t MAX_VOICES = 32;
    const uint_t MAX_FULL_RATE_TRIGGERS = 8;
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

    class RingWave : public Sound::TriggerWave
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
    };

    uint8_t EnvelopeLevel(uint_t env)
    {
      // dB scale down to -48dB
      const float level = env ? 1.0f + 20.0f * std::log10(env / 255.0f) / 48.0f : 0.0f;
      return static_cast<uint8_t>(std::clamp(level, 0.0f, 1.0f) * 255);
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

    struct Column
    {
      uint8_t Min = 255;
      uint8_t Max = 0;

      void Add(uint8_t val)
      {
        Min = std::min(Min, val);
        Max = std::max(Max, val);
      }
    };

    using Clock = std::chrono::steady_clock;

    // Persistent threads to process independent tasks, caller thread participates as well
    class WorkersPool
    {
    public:
      WorkersPool()
      {
        // leave cores for audio rendering and UI
        const auto workers = std::clamp<uint_t>(std::thread::hardware_concurrency(), 2, 5) - 2;
        for (uint_t idx = 0; idx < workers; ++idx)
        {
          Threads.emplace_back([this]() { Work(); });
        }
      }

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

      void Run(uint_t count, const std::function<void(uint_t)>& task)
      {
        if (Threads.empty() || count < 2)
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
  }  // namespace ScopeDetails

  using namespace ScopeDetails;

  class ScopeImpl : public Scope
  {
  public:
    explicit ScopeImpl(uint_t samplerate)
      : Samplerate(samplerate)
      , Stride(samplerate > 32000 ? 2 : 1)
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

    void Commit(const Sound::Chunk& master) override
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

    Layout Get(uint_t maxChannels, uint_t points, int64_t playing, uint_t windowMs, int16_t* target) override
    {
      const auto start = Clock::now();
      LastRequest = start.time_since_epoch().count();
      const std::scoped_lock guard(Lock);
      if (!HasPlayed || !points || !maxChannels)
      {
        return {};
      }
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
      const auto stride = channels > MAX_FULL_RATE_TRIGGERS ? Stride * 2 : Stride;
      if (stride != TriggersStride)
      {
        Triggers.clear();
        TriggersStride = stride;
      }
      while (Triggers.size() < channels)
      {
        const auto kernelSize = Samplerate * TRIGGER_MS / 1000 / stride;
        Triggers.emplace_back(new Sound::CorrelationTrigger(kernelSize, stride, Samplerate));
      }
      const int64_t renderSamples = Samplerate * std::clamp(windowMs, MIN_WINDOW_MS, MAX_WINDOW_MS) / 1000;
      const int64_t triggerSamples = Samplerate * TRIGGER_MS / 1000;
      std::atomic<uint_t> triggered = 0;
      // channels are independent, so triggered in parallel
      Pool.Run(channels, [&](uint_t chan) {
        const auto& ring = Voices.empty() ? Master : Voices[chan].Samples;
        const auto offset = Voices.empty() ? 0 : Voices[chan].Offset;
        const RingWave wave(ring, offset);
        // silent voices (e.g. unused chip channels) are not triggered, stable flat line is displayed
        const auto range = std::max(renderSamples, triggerSamples);
        const auto silent = IsSilent(ring, pos + offset - range, pos + offset + range);
        const auto trigger = silent ? pos : Triggers[chan]->GetTrigger(wave, pos, samplesPerFrame);
        triggered += !silent;
        const auto begin = trigger - renderSamples / 2 + offset;
        auto* out = target + chan * points;
        for (uint_t idx = 0; idx < points; ++idx)
        {
          out[idx] = ring.At(begin + int64_t(idx) * renderSamples / points);
        }
      });
      LastComputeUs = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - start).count();
      LastChannels = channels;
      LastTriggered = triggered;
      Layout result;
      result.Channels = channels;
      result.Id = LayoutId;
      return result;
    }

    uint_t GetGauges(uint_t maxChips, int64_t playing, uint_t waveWindowMs, uint8_t* target) override
    {
      const std::scoped_lock guard(Lock);
      if (!HasPlayed || Chips.empty() || VoicesPerChip == 0)
      {
        return 0;
      }
      const auto pos = playing >= 0 ? playing : GetPlaybackPosition();
      const auto chips = std::min<uint_t>(static_cast<uint_t>(Chips.size()), maxChips);
      for (uint_t chip = 0; chip < chips; ++chip)
      {
        FillGauges(Chips[chip], pos + Voices[chip * VoicesPerChip].Offset,
                   std::clamp(waveWindowMs, MIN_WINDOW_MS, MAX_WINDOW_MS), target + std::size_t(chip) * GAUGES_SIZE);
      }
      return chips;
    }

    String GetLayout() const override
    {
      const std::scoped_lock guard(Lock);
      return LayoutDescription;
    }

    uint_t GetVoiceGauges(uint_t maxVoices, int64_t playing, uint_t waveWindowMs, uint8_t* target) override
    {
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
      LastGaugesUs = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - start).count();
      return voices;
    }

    String GetStatus() const override
    {
      const std::scoped_lock guard(Lock);
      String result = Description;
      result += result.empty() ? "" : "\n";
      result += "scope: " + std::to_string(LastTriggered) + "/" + std::to_string(LastChannels) + " ch, trigger "
                + std::to_string(LastComputeUs) + "us, sync " + (LastPlayingHint >= 0 ? "device" : "estimated");
      if (LastGaugesUs)
      {
        result += ", gauges " + std::to_string(LastGaugesUs) + "us";
      }
      return result;
    }

  private:
    // end is index of currently heard sample in chip's samples
    void FillGauges(const ChipStates& chip, int64_t end, uint_t waveWindowMs, uint8_t* target) const
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
      // slow gauges from state snapshots
      const auto& recs = chip.Records;
      const double slowSamples = double(SLOW_COLUMN_CYCLES) * Samplerate / CPU_CLOCK;
      const auto windowStart = end - int64_t(GAUGE_COLUMNS * slowSamples);
      auto it = std::lower_bound(recs.begin(), recs.end(), windowStart,
                                 [](const StateRecord& rec, int64_t p) { return rec.Index < p; });
      const StateRecord* last = it != recs.begin() ? &*(it - 1) : nullptr;
      for (uint_t col = 0; col < GAUGE_COLUMNS; ++col)
      {
        const auto to = end - int64_t((GAUGE_COLUMNS - col - 1) * slowSamples);
        Column cols[8];
        auto addRecord = [&cols](const StateRecord& rec) {
          const auto& d = rec.Data;
          for (uint_t voice = 0; voice < 3; ++voice)
          {
            cols[voice].Add(EnvelopeLevel(d[0x1c + voice]));
            cols[3 + voice].Add(FrequencyLevel(d[voice * 7] | (d[voice * 7 + 1] << 8)));
          }
          cols[6].Add(static_cast<uint8_t>((d[0x17] >> 4) * 17));
          cols[7].Add(static_cast<uint8_t>(((d[0x15] & 7) | (d[0x16] << 3)) >> 3));
        };
        if (last)
        {
          addRecord(*last);
        }
        for (; it != recs.end() && it->Index < to; ++it)
        {
          addRecord(*it);
          last = &*it;
        }
        for (uint_t idx = 0; idx < 3; ++idx)
        {
          store(3 + idx, col, cols[idx]);
          store(6 + idx, col, cols[3 + idx]);
        }
        store(10, col, cols[6]);
        store(11, col, cols[7]);
      }
      auto* regs = target + GAUGES_COUNT * GAUGE_COLUMNS * 2;
      std::memset(regs, 0, STATE_RECORD_SIZE);
      if (last)
      {
        std::memcpy(regs, last->Data.data(), STATE_RECORD_SIZE);
      }
    }

    static bool IsSilent(const Ring& ring, int64_t from, int64_t to)
    {
      for (auto idx = from; idx < to; ++idx)
      {
        if (std::abs(int(ring.At(idx))) > SILENCE_LEVEL)
        {
          return false;
        }
      }
      return true;
    }

    // end is index of currently heard sample in voice's samples
    void FillVoiceGauges(Voice& voice, int64_t end, uint_t waveWindowMs, uint8_t* target)
    {
      auto store = [target](uint_t gauge, uint_t col, const Column& c) {
        auto* out = target + (gauge * GAUGE_COLUMNS + col) * 2;
        out[0] = c.Min > c.Max ? c.Max : c.Min;
        out[1] = c.Max;
      };
      auto toByte = [](int16_t smp) { return static_cast<uint8_t>((int(smp) + 32768) >> 8); };
      // fast wave gauge
      {
        const double fastSamples = double(Samplerate) * waveWindowMs / 1000 / GAUGE_COLUMNS;
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
      // slow gauges, columns are aligned to absolute position to allow caching of audio analysis
      const auto colSamples = std::max<int64_t>(int64_t(SLOW_COLUMN_CYCLES) * Samplerate / CPU_CLOCK, 1);
      // only complete columns are displayed
      const auto lastCol = end / colSamples - 1;
      const auto& recs = voice.Records;
      auto it = std::lower_bound(recs.begin(), recs.end(), (lastCol - GAUGE_COLUMNS + 1) * colSamples,
                                 [](const VoiceRecord& rec, int64_t p) { return rec.Index < p; });
      const VoiceRecord* last = it != recs.begin() ? &*(it - 1) : nullptr;
      for (uint_t col = 0; col < GAUGE_COLUMNS; ++col)
      {
        const auto colIdx = lastCol - GAUGE_COLUMNS + 1 + col;
        const auto to = (colIdx + 1) * colSamples;
        Column level;
        Column freq;
        bool levelFromState = false;
        bool freqFromState = false;
        auto addRecord = [&](const VoiceRecord& rec) {
          const auto& st = rec.State;
          if (st.Flags & Module::VoiceState::HAS_LEVEL)
          {
            levelFromState = true;
            level.Add(LevelOfDb(st.Level));
          }
          if (st.Flags & Module::VoiceState::HAS_FREQUENCY)
          {
            freqFromState = true;
            // inaudible voice has no visible pitch
            const bool audible = !(st.Flags & Module::VoiceState::HAS_LEVEL) || st.Level > -48.0f;
            freq.Add(audible ? LevelOfFrequency(st.Frequency) : 0);
          }
        };
        if (last)
        {
          addRecord(*last);
        }
        for (; it != recs.end() && it->Index < to; ++it)
        {
          addRecord(*it);
          last = &*it;
        }
        if (!levelFromState || !freqFromState)
        {
          const auto& cached = AnalyzeColumn(voice, colIdx, colSamples);
          if (!levelFromState)
          {
            level.Add(cached.Level);
          }
          if (!freqFromState)
          {
            freq.Add(cached.Frequency);
          }
        }
        store(1, col, level);
        store(2, col, freq);
      }
      auto* state = target + VOICE_GAUGES_COUNT * GAUGE_COLUMNS * 2;
      std::memset(state, 0, VOICE_STATE_SIZE);
      if (last)
      {
        const auto& st = last->State;
        std::memcpy(state, &st.Frequency, sizeof(float));
        std::memcpy(state + 4, &st.Level, sizeof(float));
        state[8] = st.Flags;
        state[9] = st.Kind;
        std::copy(st.Fields.begin(), st.Fields.end(), state + 10);
        std::copy(st.Text.begin(), st.Text.end(), state + 10 + Module::VoiceState::FIELDS);
      }
    }

    // level and pitch of voice audio in column, for voices without state
    const Voice::CachedColumn& AnalyzeColumn(Voice& voice, int64_t colIdx, int64_t colSamples)
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
      bool complete = true;
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
          Estimator = std::make_unique<Sound::CorrelationTrigger>(Samplerate * ESTIMATOR_MS / 1000 / stride, stride,
                                                                  Samplerate);
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
      return std::clamp<int64_t>(pos, std::max<int64_t>(prevStart, 0), int64_t(PlayedStart));
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
    std::vector<std::unique_ptr<Sound::CorrelationTrigger>> Triggers;
    uint64_t PlayedStart = 0;
    uint_t PlayedSize = 0;
    Clock::time_point PlayedTime;
    bool HasPlayed = false;
    int64_t LastPos = 0;
    bool HasLastPos = false;
    std::atomic<Clock::rep> LastRequest = 0;
    std::vector<ChipStates> Chips;
    String Description;
    int64_t LastComputeUs = 0;
    uint_t LastChannels = 0;
    uint_t LastTriggered = 0;
    uint_t TriggersStride = 0;
    int64_t LastPlayingHint = -1;
    String LayoutDescription;
    WorkersPool Pool;
    uint_t LayoutId = 0;
    std::unique_ptr<Sound::CorrelationTrigger> Estimator;
    std::vector<float> EstimatorData;
    int64_t LastGaugesUs = 0;
    uint_t EstimatorBudget = 0;
  };

  Scope::Ptr Scope::Create(uint_t samplerate)
  {
    return MakePtr<ScopeImpl>(samplerate);
  }
}  // namespace Player
