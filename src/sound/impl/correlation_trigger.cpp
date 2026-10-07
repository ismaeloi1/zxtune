/**
 *
 * @file
 *
 * @brief  Oscilloscope correlation trigger implementation
 *
 * @author vitamin.caig@gmail.com
 *
 * Port of the CorrelationTrigger from corrscope (https://github.com/corrscope/corrscope)
 * Copyright (c) 2018-2020+, nyanpasu64. BSD 2-Clause License, see correlation_trigger.h
 *
 **/

#include "sound/impl/correlation_trigger.h"

#include <algorithm>
#include <cmath>
#include <numeric>

namespace Sound
{
  namespace CorrelationTriggerDetails
  {
    const float MIN_AMPLITUDE = 0.01f;
    const float EDGE_COMPENSATION = 0.9f;
    const float MAX_AMPLIFICATION = 2.0f;
    const float PI = 3.14159265358979f;

    uint_t NextPowerOfTwo(uint_t val)
    {
      uint_t res = 1;
      while (res < val)
      {
        res <<= 1;
      }
      return res;
    }

    void FillGaussian(float* target, uint_t size, float std)
    {
      if (std == 0)
      {
        std::fill_n(target, size, 0.0f);
        return;
      }
      const float mid = (size - 1) * 0.5f;
      for (uint_t idx = 0; idx < size; ++idx)
      {
        const float n = (idx - mid) / std;
        target[idx] = std::exp(-0.5f * n * n);
      }
    }

    float AbsMax(const float* data, std::size_t size)
    {
      float res = 0;
      for (std::size_t idx = 0; idx < size; ++idx)
      {
        res = std::max(res, std::abs(data[idx]));
      }
      return res;
    }

    void NormalizeBuffer(float* data, std::size_t size)
    {
      const float peak = AbsMax(data, size);
      const float div = std::max(peak, MIN_AMPLITUDE);
      for (std::size_t idx = 0; idx < size; ++idx)
      {
        data[idx] /= div;
      }
    }

    float Mean(const float* data, std::size_t size)
    {
      return size ? std::accumulate(data, data + size, 0.0f) / size : 0.0f;
    }
  }  // namespace CorrelationTriggerDetails

  using namespace CorrelationTriggerDetails;

  // std::complex multiplication handles NaN/Inf specially (via __mulsc3 call) without -ffast-math, it's very slow
  inline std::complex<float> Mul(std::complex<float> a, std::complex<float> b)
  {
    return {a.real() * b.real() - a.imag() * b.imag(), a.real() * b.imag() + a.imag() * b.real()};
  }

  // Iterative radix-2 complex FFT
  class CorrelationTrigger::FFT
  {
  public:
    using Complex = std::complex<float>;

    explicit FFT(uint_t size)
      : Size(size)
      , Twiddles(size / 2)
      , Reverse(size)
    {
      for (uint_t idx = 0; idx < size / 2; ++idx)
      {
        const float angle = -2 * PI * idx / size;
        Twiddles[idx] = Complex(std::cos(angle), std::sin(angle));
      }
      uint_t bits = 0;
      while ((1u << bits) < size)
      {
        ++bits;
      }
      for (uint_t idx = 0; idx < size; ++idx)
      {
        uint_t rev = 0;
        for (uint_t bit = 0; bit < bits; ++bit)
        {
          rev |= ((idx >> bit) & 1) << (bits - 1 - bit);
        }
        Reverse[idx] = rev;
      }
    }

    uint_t GetSize() const
    {
      return Size;
    }

    void Transform(Complex* data, bool inverse) const
    {
      for (uint_t idx = 0; idx < Size; ++idx)
      {
        if (idx < Reverse[idx])
        {
          std::swap(data[idx], data[Reverse[idx]]);
        }
      }
      for (uint_t len = 2; len <= Size; len <<= 1)
      {
        const uint_t half = len / 2;
        const uint_t step = Size / len;
        for (uint_t start = 0; start < Size; start += len)
        {
          for (uint_t idx = 0; idx < half; ++idx)
          {
            const auto& tw = Twiddles[idx * step];
            const Complex w = inverse ? std::conj(tw) : tw;
            const Complex u = data[start + idx];
            const Complex v = Mul(data[start + idx + half], w);
            data[start + idx] = u + v;
            data[start + idx + half] = u - v;
          }
        }
      }
      if (inverse)
      {
        const float scale = 1.0f / Size;
        for (uint_t idx = 0; idx < Size; ++idx)
        {
          data[idx] *= scale;
        }
      }
    }

  private:
    const uint_t Size;
    std::vector<Complex> Twiddles;
    std::vector<uint_t> Reverse;
  };

  CorrelationTrigger::CorrelationTrigger(uint_t kernelSize, uint_t stride, uint_t samplerate)
    : CorrelationTrigger(kernelSize, stride, samplerate, Config())
  {}

  CorrelationTrigger::CorrelationTrigger(uint_t kernelSize, uint_t stride, uint_t samplerate, const Config& config)
    : Cfg(config)
    , A(kernelSize / 2)
    , B(kernelSize / 2)
    , Stride(std::max<uint_t>(stride, 1))
    , Samplerate(samplerate)
    , TriggerDiameter(static_cast<uint_t>((A + B) * Cfg.TriggerDiameter))
    , CorrFFT(new FFT(NextPowerOfTwo(A + TriggerDiameter + B)))
    , PeriodFFT(new FFT(NextPowerOfTwo(2 * (A + TriggerDiameter + B))))
  {
    Reset();
  }

  CorrelationTrigger::~CorrelationTrigger() = default;

  void CorrelationTrigger::Reset()
  {
    const auto kernelSize = A + B;
    CorrBuffer.assign(kernelSize, 0.0f);
    PrevWindow.assign(kernelSize, 0.0f);
    SlopeFinder.assign(kernelSize, 0.0f);
    PrevMean = 0;
    PrevPeriod = -1;
    HasPeriod = false;
    PeriodAge = 0;
    PrevTrigger = 0;
    HasPrevTrigger = false;
  }

  int64_t CorrelationTrigger::GetTrigger(const TriggerWave& wave, int64_t pos, uint_t samplesPerFrame)
  {
    const auto kernelSize = A + B;
    const auto dataSize = A + TriggerDiameter + B;

    const auto triggerBegin = std::max<int64_t>(pos - samplesPerFrame, pos - int64_t(Stride * TriggerDiameter / 2));
    const auto dataBegin = triggerBegin - int64_t(Stride * A);

    Data.resize(dataSize);
    wave.GetPadded(dataBegin, dataSize, Stride, Data.data());

    const float mean = Mean(Data.data(), dataSize);
    PeriodData.resize(dataSize);
    std::transform(Data.begin(), Data.end(), PeriodData.begin(), [mean](float v) { return v - mean; });

    if (Cfg.MeanResponsiveness != 0)
    {
      PrevMean += Cfg.MeanResponsiveness * (mean - PrevMean);
      if (Cfg.MeanResponsiveness != 1)
      {
        for (auto& v : Data)
        {
          v -= PrevMean;
        }
      }
      else
      {
        Data = PeriodData;
      }
    }

    // pitch changes slowly, so it's estimated not more often than corrscope does at 60fps
    PeriodAge += samplesPerFrame;
    if (PeriodAge >= Samplerate / PERIOD_RATE || !HasPeriod)
    {
      CachedPeriod = GetPeriod(PeriodData);
      HasPeriod = true;
      PeriodAge = 0;
    }
    const auto period = CachedPeriod;
    if (IsWindowInvalid(period))
    {
      CalcSlopeFinder(period);
      PrevPeriod = static_cast<int>(period);
    }

    bool corrEnabled = Cfg.BufferStrength != 0 && Cfg.Responsiveness != 0;
    const auto corrSize = TriggerDiameter + 1;

    if (corrEnabled)
    {
      // corr(data, slope + strength * buffer) == corr(data, slope) + strength * corr(data, buffer)
      CorrelateValid2(Data, CorrBuffer, SlopeFinder, CorrQuality, Corr);
      if (Cfg.ResetBelow > 0)
      {
        const auto peakIdx = std::max_element(CorrQuality.begin(), CorrQuality.end()) - CorrQuality.begin();
        const float peakQuality = CorrQuality[peakIdx];
        const float* dataSlice = Data.data() + peakIdx;
        Aligned.resize(kernelSize);
        std::transform(dataSlice, dataSlice + kernelSize, Aligned.begin(), [mean](float v) { return v - mean; });
        NormalizeBuffer(Aligned.data(), kernelSize);
        float selfQuality = 0;
        for (uint_t idx = 0; idx < kernelSize; ++idx)
        {
          selfQuality += dataSlice[idx] * Aligned[idx] * PrevWindow[idx];
        }
        const float relativeQuality = peakQuality / (selfQuality + 0.001f);
        if (relativeQuality < Cfg.ResetBelow)
        {
          std::fill(CorrQuality.begin(), CorrQuality.end(), 0.0f);
          std::fill(CorrBuffer.begin(), CorrBuffer.end(), 0.0f);
          corrEnabled = false;
        }
      }
    }
    else
    {
      CorrQuality.assign(corrSize, 0.0f);
    }

    if (corrEnabled)
    {
      for (uint_t idx = 0; idx < corrSize; ++idx)
      {
        Corr[idx] += CorrQuality[idx] * Cfg.BufferStrength;
      }
    }
    else
    {
      CorrelateValid(Data, SlopeFinder, Corr);
    }

    // peaks = corr_quality * buffer_strength - edge_strength * cumsum(data[A - 1 : len - B])
    auto& peaks = CorrQuality;
    float edgeScore = 0;
    for (uint_t idx = 0; idx < corrSize; ++idx)
    {
      peaks[idx] *= Cfg.BufferStrength;
      if (Cfg.EdgeStrength != 0)
      {
        edgeScore += Data[A - 1 + idx];
        peaks[idx] -= Cfg.EdgeStrength * edgeScore;
      }
    }

    const int radius = Cfg.TriggerRadiusPeriods != 0 && period != 0
                           ? static_cast<int>(std::lround(period * Cfg.TriggerRadiusPeriods))
                           : -1;
    const auto peakOffset = FindPeak(Corr, peaks, radius);
    auto trigger = triggerBegin + int64_t(Stride * peakOffset);
    if (HasPrevTrigger)
    {
      trigger = std::max(trigger, PrevTrigger);
    }
    PrevTrigger = trigger;
    HasPrevTrigger = true;

    Aligned.resize(kernelSize);
    wave.GetPadded(trigger - int64_t((kernelSize / 2) * Stride), kernelSize, Stride, Aligned.data());
    const float resultMean = Mean(Aligned.data(), kernelSize);
    UpdateBuffer(Aligned, resultMean, period);
    return trigger;
  }

  uint_t CorrelationTrigger::EstimatePeriod(const float* data, uint_t size)
  {
    const auto limit = std::min<uint_t>(size, PeriodFFT->GetSize() / 2);
    PeriodData.assign(data + (size - limit), data + size);
    return GetPeriod(PeriodData);
  }

  // Tweaked autocorrelation to estimate the period (pitch) of a signal, 0 if unknown
  uint_t CorrelationTrigger::GetPeriod(const Buffer& data)
  {
    const auto size = static_cast<uint_t>(data.size());
    if (AbsMax(data.data(), size) < MIN_AMPLITUDE)
    {
      return 0;
    }
    const auto fftSize = PeriodFFT->GetSize();
    TmpA.assign(fftSize, FFT::Complex());
    std::copy(data.begin(), data.end(), TmpA.begin());
    PeriodFFT->Transform(TmpA.data(), false);
    for (auto& v : TmpA)
    {
      v = std::norm(v);
    }
    PeriodFFT->Transform(TmpA.data(), true);

    Corr.resize(size);
    for (uint_t idx = 0; idx < size; ++idx)
    {
      Corr[idx] = TmpA[idx].real();
    }

    const auto minPeriod = static_cast<uint_t>(std::lround(float(Samplerate) / Stride / Cfg.MaxFreq));
    const auto zeroCrossing = std::find_if(Corr.begin(), Corr.end(), [](float v) { return v < 0; }) - Corr.begin();
    if (zeroCrossing == 0 || zeroCrossing == static_cast<std::ptrdiff_t>(size))
    {
      return 0;
    }
    const auto minX = std::min<uint_t>(std::max<uint_t>(minPeriod, zeroCrossing), size - 1);
    const auto calcPeak = [&]() {
      return static_cast<uint_t>(std::max_element(Corr.begin() + minX, Corr.end()) - Corr.begin());
    };
    auto peakX = calcPeak();
    if (peakX > 0.1f * size)
    {
      for (uint_t idx = 0; idx < size; ++idx)
      {
        const float divisor = 1.0f - EDGE_COMPENSATION * idx / size;
        Corr[idx] /= std::max(divisor, 1 / MAX_AMPLIFICATION);
      }
      peakX = calcPeak();
    }
    return peakX;
  }

  bool CorrelationTrigger::IsWindowInvalid(uint_t period) const
  {
    if (PrevPeriod < 0)
    {
      return true;
    }
    else if (period == 0)
    {
      return false;
    }
    else if (PrevPeriod == 0)
    {
      return true;
    }
    const float semitones = std::log2(float(period) / PrevPeriod) * -12;
    return std::abs(semitones) > Cfg.RecalcSemitones;
  }

  void CorrelationTrigger::CalcSlopeFinder(uint_t period)
  {
    const auto kernelSize = A + B;
    const float slopeWidth = std::clamp(Cfg.SlopeWidth * period, 1.0f, std::max(1.0f, A / 3.0f));
    const float slopeStrength = Cfg.EdgeStrength * 2;
    SlopeFinder.resize(kernelSize);
    FillGaussian(SlopeFinder.data(), kernelSize, slopeWidth);
    for (uint_t idx = 0; idx < kernelSize; ++idx)
    {
      SlopeFinder[idx] *= idx < A ? -slopeStrength / 2 : slopeStrength / 2;
    }
  }

  // result[k] = sum(data[k + n] * kernel[n]), k in [0, len(data) - len(kernel)]
  void CorrelationTrigger::CorrelateValid(const Buffer& data, const Buffer& kernel, Buffer& result)
  {
    const auto fftSize = CorrFFT->GetSize();
    // pack both real signals into single complex transform
    TmpA.assign(fftSize, FFT::Complex());
    for (std::size_t idx = 0; idx < data.size(); ++idx)
    {
      TmpA[idx].real(data[idx]);
    }
    for (std::size_t idx = 0; idx < kernel.size(); ++idx)
    {
      TmpA[idx].imag(kernel[idx]);
    }
    CorrFFT->Transform(TmpA.data(), false);
    TmpB.resize(fftSize);
    for (uint_t idx = 0; idx < fftSize; ++idx)
    {
      const auto x = TmpA[idx];
      const auto y = std::conj(TmpA[(fftSize - idx) % fftSize]);
      const auto spectrumData = (x + y) * 0.5f;
      // (x - y) * -0.5i
      const auto diff = x - y;
      const FFT::Complex spectrumKernel(diff.imag() * 0.5f, -diff.real() * 0.5f);
      TmpB[idx] = Mul(spectrumData, std::conj(spectrumKernel));
    }
    CorrFFT->Transform(TmpB.data(), true);
    const auto size = data.size() - kernel.size() + 1;
    result.resize(size);
    for (std::size_t idx = 0; idx < size; ++idx)
    {
      result[idx] = TmpB[idx].real();
    }
  }

  // Two correlations of the same data in one pass: results are real, so combined as real and imaginary parts
  void CorrelationTrigger::CorrelateValid2(const Buffer& data, const Buffer& kernel1, const Buffer& kernel2,
                                           Buffer& result1, Buffer& result2)
  {
    const auto fftSize = CorrFFT->GetSize();
    TmpA.assign(fftSize, FFT::Complex());
    for (std::size_t idx = 0; idx < data.size(); ++idx)
    {
      TmpA[idx].real(data[idx]);
    }
    for (std::size_t idx = 0; idx < kernel1.size(); ++idx)
    {
      TmpA[idx].imag(kernel1[idx]);
    }
    CorrFFT->Transform(TmpA.data(), false);
    TmpC.assign(fftSize, FFT::Complex());
    for (std::size_t idx = 0; idx < kernel2.size(); ++idx)
    {
      TmpC[idx].real(kernel2[idx]);
    }
    CorrFFT->Transform(TmpC.data(), false);
    TmpB.resize(fftSize);
    for (uint_t idx = 0; idx < fftSize; ++idx)
    {
      const auto x = TmpA[idx];
      const auto y = std::conj(TmpA[(fftSize - idx) % fftSize]);
      const auto spectrumData = (x + y) * 0.5f;
      // (x - y) * -0.5i
      const auto diff = x - y;
      const FFT::Complex spectrumKernel1(diff.imag() * 0.5f, -diff.real() * 0.5f);
      const auto corr1 = Mul(spectrumData, std::conj(spectrumKernel1));
      const auto corr2 = Mul(spectrumData, std::conj(TmpC[idx]));
      // corr1 + i * corr2
      TmpB[idx] = FFT::Complex(corr1.real() - corr2.imag(), corr1.imag() + corr2.real());
    }
    CorrFFT->Transform(TmpB.data(), true);
    const auto size = data.size() - kernel1.size() + 1;
    result1.resize(size);
    result2.resize(size);
    for (std::size_t idx = 0; idx < size; ++idx)
    {
      result1[idx] = TmpB[idx].real();
      result2[idx] = TmpB[idx].imag();
    }
  }

  // Find the best correlation among local maximums of peaks within radius from center
  uint_t CorrelationTrigger::FindPeak(Buffer& corr, const Buffer& peaks, int radius) const
  {
    const int size = static_cast<int>(corr.size());
    const int mid = size / 2;
    int left = 0;
    int right = size;
    if (radius >= 0)
    {
      left = std::max(mid - radius, 0);
      right = std::min(mid + radius + 1, size);
    }
    if (right - left < 3)
    {
      return mid;
    }
    const float minCorr = *std::min_element(corr.begin() + left, corr.begin() + right);
    float best = minCorr;
    int bestIdx = -1;
    for (int idx = left + 1; idx < right - 1; ++idx)
    {
      if (peaks[idx] < peaks[idx + 1] || peaks[idx] < peaks[idx - 1])
      {
        continue;
      }
      if (corr[idx] > best)
      {
        best = corr[idx];
        bestIdx = idx;
      }
    }
    return bestIdx < 0 ? mid : bestIdx;
  }

  void CorrelationTrigger::UpdateBuffer(Buffer& data, float resultMean, uint_t period)
  {
    if (Cfg.BufferStrength == 0 || Cfg.Responsiveness == 0)
    {
      return;
    }
    const auto size = data.size();
    for (auto& v : data)
    {
      v -= resultMean;
    }
    NormalizeBuffer(data.data(), size);
    FillGaussian(PrevWindow.data(), size, period * Cfg.BufferFalloff);
    NormalizeBuffer(CorrBuffer.data(), size);
    for (std::size_t idx = 0; idx < size; ++idx)
    {
      CorrBuffer[idx] = CorrBuffer[idx] * (1 - Cfg.Responsiveness) + data[idx] * PrevWindow[idx] * Cfg.Responsiveness;
    }
  }
}  // namespace Sound
