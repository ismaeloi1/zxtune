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

#include <cmath>
#include <iostream>
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

  void TestPhaseLock(double startFreq, double endFreq)
  {
    VectorWave wave;
    const auto phases = MakePulse(wave, startFreq, endFreq);
    CorrelationTrigger trigger(KERNEL, STRIDE, SAMPLERATE);
    double maxDeviation = 0;
    for (uint_t frame = 30; frame < 300; ++frame)
    {
      const int64_t pos = int64_t(frame) * FRAME;
      const auto result = trigger.GetTrigger(wave, pos, FRAME);
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
    std::cout << "Freq " << startFreq << "->" << endFreq << "Hz: max phase deviation " << maxDeviation << std::endl;
    if (maxDeviation > 0.05)
    {
      throw std::runtime_error("Unstable trigger");
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
}  // namespace Sound

int main()
{
  try
  {
    Sound::TestPhaseLock(110, 120);
    Sound::TestPhaseLock(220, 233);
    Sound::TestPhaseLock(880, 900);
    Sound::TestSilence();
    std::cout << "All tests passed" << std::endl;
    return 0;
  }
  catch (const std::exception& e)
  {
    std::cout << "Failed: " << e.what() << std::endl;
    return 1;
  }
}
