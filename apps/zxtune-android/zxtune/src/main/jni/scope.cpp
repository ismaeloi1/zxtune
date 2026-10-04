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
#include <cstring>
#include <deque>
#include <mutex>
#include <vector>

namespace Player
{
  namespace ScopeDetails
  {
    // ~2.7s at 48kHz, enough to cover output buffers latency
    const uint_t RING_SIZE = 1 << 17;
    const uint_t TRIGGER_MS = 40;
    const uint_t RENDER_MS = 40;
    const uint_t MAX_VOICES = 32;
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

    struct Voice
    {
      Ring Samples;
      std::vector<int16_t> Staging;
      // Samples.Size() - master samples count at last commit
      int64_t Offset = 0;
    };

    // ~4s of 4ms snapshots
    const std::size_t MAX_STATES = 1024;

    struct StateRecord
    {
      int64_t Index = 0;
      std::array<uint8_t, Scope::STATE_RECORD_SIZE> Data = {};
    };

    struct ChipStates
    {
      std::deque<StateRecord> Records;
      std::vector<StateRecord> Staging;
    };

    using Clock = std::chrono::steady_clock;
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
    void SetVoicesCount(uint_t count, uint_t perChip) override
    {
      const std::scoped_lock guard(Lock);
      Voices.resize(std::min(count, MAX_VOICES));
      VoicesPerChip = perChip;
      Chips.resize(perChip ? Voices.size() / perChip : 0);
      Triggers.clear();
    }

    uint_t GetStatePeriod() const override
    {
      return std::max<uint_t>(Samplerate / 250, 1);
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
      }
      for (auto& chip : Chips)
      {
        chip.Records.insert(chip.Records.end(), chip.Staging.begin(), chip.Staging.end());
        chip.Staging.clear();
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

    Layout Get(uint_t maxChannels, uint_t points, int64_t playing, int16_t* target) override
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
      while (Triggers.size() < channels)
      {
        const auto kernelSize = Samplerate * TRIGGER_MS / 1000 / Stride;
        Triggers.emplace_back(new Sound::CorrelationTrigger(kernelSize, Stride, Samplerate));
      }
      const int64_t renderSamples = Samplerate * RENDER_MS / 1000;
      for (uint_t chan = 0; chan < channels; ++chan)
      {
        const auto& ring = Voices.empty() ? Master : Voices[chan].Samples;
        const auto offset = Voices.empty() ? 0 : Voices[chan].Offset;
        const RingWave wave(ring, offset);
        const auto trigger = Triggers[chan]->GetTrigger(wave, pos, samplesPerFrame);
        const auto begin = trigger - renderSamples / 2 + offset;
        auto* out = target + chan * points;
        for (uint_t idx = 0; idx < points; ++idx)
        {
          out[idx] = ring.At(begin + int64_t(idx) * renderSamples / points);
        }
      }
      LastComputeUs = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - start).count();
      LastChannels = channels;
      Layout result;
      result.Channels = channels;
      result.PerChip = Voices.empty() ? 0 : VoicesPerChip;
      return result;
    }

    uint_t GetStates(uint_t maxChips, uint_t records, int64_t playing, uint8_t* target) override
    {
      const std::scoped_lock guard(Lock);
      if (!HasPlayed || !records || Chips.empty() || VoicesPerChip == 0)
      {
        return 0;
      }
      const auto pos = playing >= 0 ? playing : GetPlaybackPosition();
      const auto chips = std::min<uint_t>(static_cast<uint_t>(Chips.size()), maxChips);
      std::memset(target, 0, std::size_t(chips) * records * STATE_RECORD_SIZE);
      for (uint_t chip = 0; chip < chips; ++chip)
      {
        const auto& recs = Chips[chip].Records;
        const auto voicePos = pos + Voices[chip * VoicesPerChip].Offset;
        // last record not newer than currently heard sample
        auto end = std::upper_bound(recs.begin(), recs.end(), voicePos,
                                    [](int64_t p, const StateRecord& rec) { return p < rec.Index; });
        const auto avail = static_cast<uint_t>(std::min<std::ptrdiff_t>(end - recs.begin(), records));
        auto* out = target + (std::size_t(chip) * records + (records - avail)) * STATE_RECORD_SIZE;
        for (auto it = end - avail; it != end; ++it, out += STATE_RECORD_SIZE)
        {
          std::memcpy(out, it->Data.data(), STATE_RECORD_SIZE);
        }
      }
      return chips;
    }

    String GetStatus() const override
    {
      const std::scoped_lock guard(Lock);
      String result = Description;
      result += result.empty() ? "" : "\n";
      result += "scope: " + std::to_string(LastChannels) + " ch, trigger " + std::to_string(LastComputeUs) + "us, sync "
                + (LastPlayingHint >= 0 ? "device" : "estimated");
      return result;
    }

  private:
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
    int64_t LastPlayingHint = -1;
  };

  Scope::Ptr Scope::Create(uint_t samplerate)
  {
    return MakePtr<ScopeImpl>(samplerate);
  }
}  // namespace Player
