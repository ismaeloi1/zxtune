/**
 *
 * @file
 *
 * @brief  Oscilloscope correlation trigger
 *
 * @author vitamin.caig@gmail.com
 *
 * Port of the CorrelationTrigger from corrscope (https://github.com/corrscope/corrscope)
 *
 * Copyright (c) 2018-2020+, nyanpasu64
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 **/

#pragma once

#include "types.h"

#include <complex>
#include <memory>
#include <vector>

namespace Sound
{
  //! @brief Source of waveform data for trigger
  class TriggerWave
  {
  public:
    virtual ~TriggerWave() = default;

    //! @brief Fill target with samples [begin, begin + count * stride) taking each stride'th one
    //! @note Samples out of available range should be zeroes. Values are in [-1, 1] range
    virtual void GetPadded(int64_t begin, uint_t count, uint_t stride, float* target) const = 0;
  };

  //! @brief Correlation-based trigger. Finds stable phase position for periodic waveforms
  class CorrelationTrigger
  {
  public:
    //! Default values match corrscope's template config
    struct Config
    {
      float EdgeStrength = 1.0f;
      float Responsiveness = 0.5f;
      float BufferFalloff = 0.5f;
      float BufferStrength = 1.0f;
      float MeanResponsiveness = 0.0f;
      float TriggerDiameter = 0.5f;
      float TriggerRadiusPeriods = 1.5f;
      float RecalcSemitones = 1.0f;
      float MaxFreq = 4000.0f;
      float SlopeWidth = 0.25f;
      float ResetBelow = 0.3f;
    };

    //! @param kernelSize correlation buffer size in subsamples
    //! @param stride subsampling factor
    //! @param samplerate input wave samplerate
    CorrelationTrigger(uint_t kernelSize, uint_t stride, uint_t samplerate, const Config& config);
    CorrelationTrigger(uint_t kernelSize, uint_t stride, uint_t samplerate);
    ~CorrelationTrigger();

    //! @param pos estimated position of wave to display
    //! @param samplesPerFrame distance between consequent calls
    //! @return found trigger position
    int64_t GetTrigger(const TriggerWave& wave, int64_t pos, uint_t samplesPerFrame);

    //! @brief Forget all the accumulated state (e.g. after seek)
    void Reset();

    //! @brief Estimate pitch period using the same autocorrelation as trigger
    //! @param data subsampled (by stride) wave, size should not exceed 1.5 of kernel size
    //! @return period in subsamples, 0 if unknown
    uint_t EstimatePeriod(const float* data, uint_t size);

  private:
    class FFT;
    using Buffer = std::vector<float>;

    uint_t GetPeriod(const Buffer& data);
    bool IsWindowInvalid(uint_t period) const;
    void CalcSlopeFinder(uint_t period);
    void CorrelateValid(const Buffer& data, const Buffer& kernel, Buffer& result);
    void CorrelateValid2(const Buffer& data, const Buffer& kernel1, const Buffer& kernel2, Buffer& result1,
                         Buffer& result2);
    uint_t FindPeak(Buffer& corr, const Buffer& peaks, int radius) const;
    void UpdateBuffer(Buffer& data, float resultMean, uint_t period);

  private:
    const Config Cfg;
    const uint_t A;
    const uint_t B;
    const uint_t Stride;
    const uint_t Samplerate;
    const uint_t TriggerDiameter;
    const std::unique_ptr<FFT> CorrFFT;
    const std::unique_ptr<FFT> PeriodFFT;

    // state
    Buffer CorrBuffer;
    Buffer PrevWindow;
    Buffer SlopeFinder;
    float PrevMean = 0;
    int PrevPeriod = -1;  // undefined
    uint_t CachedPeriod = 0;
    uint_t PeriodAge = 0;
    bool HasPeriod = false;
    //! period estimations per second
    static const uint_t PERIOD_RATE = 60;
    int64_t PrevTrigger = 0;
    bool HasPrevTrigger = false;

    // temporaries
    Buffer Data;
    Buffer PeriodData;
    Buffer CorrQuality;
    Buffer Corr;
    Buffer Aligned;
    std::vector<std::complex<float>> TmpA;
    std::vector<std::complex<float>> TmpB;
    std::vector<std::complex<float>> TmpC;
  };
}  // namespace Sound
