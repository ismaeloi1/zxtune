/**
 *
 * @file
 *
 * @brief  Correlation trigger test
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "sound/impl/correlation_trigger.h"

#include "sound/scope.h"

#include <chrono>
#include <cmath>
#include <iostream>
#include <numeric>
#include <stdexcept>
#include <vector>

namespace Sound
{
  class VectorWave : public TriggerWave
  {
  public:
    std::vector<float> Data;

    void GetPadded(int64_t begin, uint_t count, uint_t stride, float* target) const override
    {
      for (uint_t idx = 0; idx < count; ++idx)
      {
        const auto pos = begin + int64_t(idx) * stride;
        target[idx] = pos >= 0 && pos < int64_t(Data.size()) ? Data[pos] : 0.0f;
      }
    }
  };

  const uint_t SAMPLERATE = 48000;
  const uint_t STRIDE = 2;
  const uint_t KERNEL = SAMPLERATE * 40 / 1000 / STRIDE;
  const uint_t FRAME = SAMPLERATE / 120;

  // Pulse wave with slow pitch glide, returns phase for each sample
  std::vector<double> MakePulse(VectorWave& wave, double startFreq, double endFreq)
  {
    const std::size_t size = SAMPLERATE * 3;
    wave.Data.resize(size);
    std::vector<double> phases(size);
    double phase = 0;
    for (std::size_t idx = 0; idx < size; ++idx)
    {
      phases[idx] = phase - std::floor(phase);
      wave.Data[idx] = (phases[idx] < 0.3 ? 0.6f : -0.6f) + 0.2f * std::sin(2 * 3.14159265 * 3 * phase);
      phase += (startFreq + (endFreq - startFreq) * idx / size) / SAMPLERATE;
    }
    return phases;
  }

  CorrelationTrigger::Config LimitedRate(uint_t rate)
  {
    CorrelationTrigger::Config cfg;
    cfg.MaxRate = rate;
    return cfg;
  }

  void TestPhaseLock(double startFreq, double endFreq, const CorrelationTrigger::Config& cfg = {})
  {
    VectorWave wave;
    const auto phases = MakePulse(wave, startFreq, endFreq);
    CorrelationTrigger trigger(KERNEL, STRIDE, SAMPLERATE, cfg);
    uint_t searches = 0;
    const auto start = std::chrono::steady_clock::now();
    double maxDeviation = 0;
    for (uint_t frame = 30; frame < 300; ++frame)
    {
      const int64_t pos = int64_t(frame) * FRAME;
      const auto result = trigger.GetTrigger(wave, pos, FRAME);
      searches += trigger.IsSearched();
      if (result < 0 || result >= int64_t(phases.size()))
      {
        throw std::runtime_error("Trigger out of range");
      }
      if (std::abs(result - pos) > int64_t(SAMPLERATE / 20))
      {
        throw std::runtime_error("Trigger too far from requested position");
      }
      // distance to rising edge at phase 0
      const auto phase = phases[result];
      maxDeviation = std::max(maxDeviation, std::min(phase, 1 - phase));
    }
    const auto us = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - start).count();
    std::cout << "Freq " << startFreq << "->" << endFreq << "Hz, max rate " << cfg.MaxRate << ": max phase deviation "
              << maxDeviation << ", searches " << searches << "/270, " << us / 270 << "us/frame" << std::endl;
    if (maxDeviation > 0.05)
    {
      throw std::runtime_error("Unstable trigger");
    }
  }

  // Post trigger slides to exact rising edge at full rate, so subsampled search error is removed
  void TestPostTrigger()
  {
    VectorWave wave;
    const auto phases = MakePulse(wave, 220, 220);
    CorrelationTrigger::Config cfg;
    cfg.PostRadius = 4;
    CorrelationTrigger trigger(KERNEL, STRIDE, SAMPLERATE, cfg);
    const double samplesPerPeriod = double(SAMPLERATE) / 220;
    double maxDeviation = 0;
    for (uint_t frame = 30; frame < 300; ++frame)
    {
      const auto result = trigger.GetTrigger(wave, int64_t(frame) * FRAME, FRAME);
      // the first sample of high level, i.e. phase is less than one sample after edge
      const auto phase = phases[result];
      maxDeviation = std::max(maxDeviation, std::min(phase, 1 - phase) * samplesPerPeriod);
    }
    std::cout << "Post trigger: max deviation " << maxDeviation << " samples" << std::endl;
    if (maxDeviation > 1)
    {
      throw std::runtime_error("Imprecise post trigger");
    }
  }

  // Falling edge of pulse is at phase 0.3 of MakePulse
  void TestFallingEdge()
  {
    VectorWave wave;
    const auto phases = MakePulse(wave, 220, 220);
    CorrelationTrigger::Config cfg;
    cfg.EdgeDirection = -1;
    cfg.PostRadius = 4;
    CorrelationTrigger trigger(KERNEL, STRIDE, SAMPLERATE, cfg);
    double maxDeviation = 0;
    for (uint_t frame = 30; frame < 300; ++frame)
    {
      const auto result = trigger.GetTrigger(wave, int64_t(frame) * FRAME, FRAME);
      maxDeviation = std::max(maxDeviation, std::abs(phases[result] - 0.3));
    }
    std::cout << "Falling edge: max phase deviation " << maxDeviation << std::endl;
    if (maxDeviation > 0.02)
    {
      throw std::runtime_error("Falling edge is not tracked");
    }
  }

  // Note changes by fifth up and down each 0.5s, trigger should lock to the new note quickly
  void TestNoteChanges(bool pitchTracking)
  {
    VectorWave wave;
    const std::size_t size = SAMPLERATE * 4;
    wave.Data.resize(size);
    std::vector<double> phases(size);
    double phase = 0;
    for (std::size_t idx = 0; idx < size; ++idx)
    {
      phases[idx] = phase - std::floor(phase);
      wave.Data[idx] = (phases[idx] < 0.3 ? 0.6f : -0.6f) + 0.2f * std::sin(2 * 3.14159265 * 3 * phase);
      phase += ((idx / (SAMPLERATE / 2)) & 1 ? 330.0 : 220.0) / SAMPLERATE;
    }
    CorrelationTrigger::Config cfg;
    cfg.PitchTracking = pitchTracking;
    CorrelationTrigger trigger(KERNEL, STRIDE, SAMPLERATE, cfg);
    uint_t unlocked = 0;
    uint_t frames = 0;
    for (uint_t frame = 30; frame * FRAME < size - SAMPLERATE / 10; ++frame, ++frames)
    {
      const auto result = trigger.GetTrigger(wave, int64_t(frame) * FRAME, FRAME);
      const auto ph = phases[result];
      unlocked += std::min(ph, 1 - ph) > 0.05;
    }
    std::cout << "Note changes (pitch tracking " << pitchTracking << "): unlocked frames " << unlocked << "/" << frames
              << std::endl;
    // each note change may take a few frames
    if (unlocked > 8 * 3)
    {
      throw std::runtime_error("Note changes are not tracked");
    }
  }

  // Postponed searches keep phase by extrapolation
  void TestPostponedSearches()
  {
    VectorWave wave;
    const auto phases = MakePulse(wave, 220, 233);
    CorrelationTrigger trigger(KERNEL, STRIDE, SAMPLERATE, LimitedRate(60));
    double maxDeviation = 0;
    uint_t searches = 0;
    for (uint_t frame = 30; frame < 300; ++frame)
    {
      // only every third frame is allowed to search
      const auto result = trigger.GetTrigger(wave, int64_t(frame) * FRAME, FRAME, frame % 3 == 0);
      searches += trigger.IsSearched();
      const auto phase = phases[result];
      maxDeviation = std::max(maxDeviation, std::min(phase, 1 - phase));
    }
    std::cout << "Postponed searches: max phase deviation " << maxDeviation << ", searches " << searches << "/270"
              << std::endl;
    if (maxDeviation > 0.05 || searches > 100)
    {
      throw std::runtime_error("Postponed searches failed");
    }
  }

  void TestSilence()
  {
    VectorWave wave;
    wave.Data.assign(SAMPLERATE, 0.0f);
    CorrelationTrigger trigger(KERNEL, STRIDE, SAMPLERATE);
    for (uint_t frame = 0; frame < 100; ++frame)
    {
      const int64_t pos = int64_t(frame) * FRAME;
      const auto result = trigger.GetTrigger(wave, pos, FRAME);
      if (std::abs(result - pos) > int64_t(SAMPLERATE / 20))
      {
        throw std::runtime_error("Trigger lost on silence");
      }
    }
    std::cout << "Silence: ok" << std::endl;
  }

  // Each voice of chip is triggered: frame to frame displacement of traces of voices with vibrato (extrapolated phase
  // drifts) is the same as for the most stable one
  void TestScopeVoices()
  {
    const uint_t voices = 3;
    const auto scope = Scope::Create(SAMPLERATE);
    scope->SetVoicesGroups({{"Chip", {"1", "2", "3"}}}, false);
    const uint_t points = SAMPLERATE * 40 / 1000;
    std::vector<int16_t> traces(voices * points);
    std::vector<int16_t> prev;
    // activate
    scope->Get(voices, points, 0, 40, traces.data());
    const uint_t chunk = SAMPLERATE / 50;
    const double freqs[voices] = {261.6, 329.6, 392.0};
    std::vector<int16_t> samples(chunk * voices);
    Chunk master(chunk);
    double phases[voices] = {};
    uint64_t played = 0;
    std::vector<double> shifts(voices);
    uint_t pairs = 0;
    const auto latency = SAMPLERATE / 5;
    const double step = double(SAMPLERATE) / 120;
    double frameTime = 0;
    while (played < 5 * SAMPLERATE)
    {
      for (uint_t idx = 0; idx < chunk; ++idx)
      {
        const double t = double(played + idx) / SAMPLERATE;
        for (uint_t voice = 0; voice < voices; ++voice)
        {
          // 6Hz vibrato of +-40 cents
          const double freq = freqs[voice] * std::pow(2.0, 0.4 / 12 * std::sin(2 * 3.14159265 * 6 * t + voice));
          phases[voice] += freq / SAMPLERATE;
          // pulse with sharp edges, as chip voices
          samples[idx * voices + voice] = std::fmod(phases[voice], 1.0) < 0.3 ? 12000 : -12000;
        }
      }
      scope->Feed(0, voices, samples.data(), chunk);
      scope->Commit(master);
      scope->Played(played, chunk);
      played += chunk;
      while (frameTime + latency <= double(played))
      {
        const auto layout = scope->Get(voices, points, int64_t(frameTime), 40, traces.data());
        frameTime += step;
        if (layout.Channels != voices)
        {
          throw std::runtime_error("Invalid channels count");
        }
        if (prev.size() == traces.size() && frameTime > SAMPLERATE)
        {
          for (uint_t voice = 0; voice < voices; ++voice)
          {
            const auto* a = prev.data() + voice * points;
            const auto* b = traces.data() + voice * points;
            // best alignment of central parts within a quarter of period
            int best = 0;
            double bestErr = -1;
            for (int lag = -40; lag <= 40; ++lag)
            {
              double err = 0;
              for (uint_t idx = points / 4; idx < points * 3 / 4; ++idx)
              {
                const double d = double(b[idx]) - a[idx + lag];
                err += d * d;
              }
              if (bestErr < 0 || err < bestErr)
              {
                bestErr = err;
                best = lag;
              }
            }
            shifts[voice] += std::abs(best);
          }
          ++pairs;
        }
        prev = traces;
      }
    }
    for (uint_t voice = 0; voice < voices; ++voice)
    {
      const auto mean = shifts[voice] / pairs;
      std::cout << "Scope voice " << voice << ": mean shift " << mean << " samples" << std::endl;
      if (mean > 1.0)
      {
        throw std::runtime_error("Unstable scope voice");
      }
    }
  }
}  // namespace Sound

int main()
{
  try
  {
    Sound::TestPhaseLock(110, 120);
    Sound::TestPhaseLock(220, 233);
    Sound::TestPhaseLock(880, 900);
    // 120fps display with searches at 60 per second (30 is already visibly unstable for gliding pitch)
    Sound::TestPhaseLock(110, 120, Sound::LimitedRate(60));
    Sound::TestPhaseLock(220, 233, Sound::LimitedRate(60));
    Sound::TestPhaseLock(880, 900, Sound::LimitedRate(60));
    Sound::TestSilence();
    Sound::TestPostTrigger();
    Sound::TestFallingEdge();
    Sound::TestNoteChanges(false);
    Sound::TestNoteChanges(true);
    Sound::TestPostponedSearches();
    Sound::TestScopeVoices();
    std::cout << "All tests passed" << std::endl;
    return 0;
  }
  catch (const std::exception& e)
  {
    std::cout << "Failed: " << e.what() << std::endl;
    return 1;
  }
}
