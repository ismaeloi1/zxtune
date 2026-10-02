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
#include <chrono>
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
    void SetVoicesCount(uint_t count) override
    {
      const std::scoped_lock guard(Lock);
      Voices.resize(std::min(count, MAX_VOICES));
      Triggers.clear();
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

    uint_t Get(uint_t maxChannels, uint_t points, int16_t* target) override
    {
      const std::scoped_lock guard(Lock);
      if (!HasPlayed || !points || !maxChannels)
      {
        return 0;
      }
      const auto pos = GetPlaybackPosition();
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
      return channels;
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
    std::mutex Lock;
    Ring Master;
    std::vector<Voice> Voices;
    std::vector<std::unique_ptr<Sound::CorrelationTrigger>> Triggers;
    uint64_t PlayedStart = 0;
    uint_t PlayedSize = 0;
    Clock::time_point PlayedTime;
    bool HasPlayed = false;
    int64_t LastPos = 0;
    bool HasLastPos = false;
  };

  Scope::Ptr Scope::Create(uint_t samplerate)
  {
    return MakePtr<ScopeImpl>(samplerate);
  }
}  // namespace Player
