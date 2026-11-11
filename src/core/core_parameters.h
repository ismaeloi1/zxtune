/**
 *
 * @file
 *
 * @brief  Core parameters names
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "zxtune.h"

#include "parameters/types.h"

namespace Parameters::ZXTune::Core
{
  //! @brief Parameters#ZXTune#Core namespace prefix
  const auto PREFIX = ZXTune::PREFIX + "core"_id;

  //@{
  //! @name Masking for each of ATTR_CHANNELS_NAMES channels
  const auto CHANNELS_MASK = PREFIX + "channels_mask"_id;
  const IntType CHANNELS_MASK_DEFAULT = 0;
  //@}

  //@{
  //! @name Memory limit in bytes for emulation state snapshots used to speed up backward seeking in single tune
  //! 0 disables snapshots
  const auto SEEK_SNAPSHOTS_MEMORY = PREFIX + "seek_snapshots_memory"_id;
  const IntType SEEK_SNAPSHOTS_MEMORY_DEFAULT = 8 * 1024 * 1024;
  //@}

  //! @brief AYM-chip related parameters namespace
  namespace AYM
  {
    //! @brief Parameters#ZXTune#Core#AYM namespace prefix
    const auto PREFIX = Core::PREFIX + "aym"_id;

    //@{
    //! @name PSG clockrate in Hz

    //! Default value- 1.75MHz
    const IntType CLOCKRATE_DEFAULT = 1750000;
    const IntType CLOCKRATE_MIN = 100000;
    const IntType CLOCKRATE_MAX = UINT64_C(10000000);
    //! Parameter name
    const auto CLOCKRATE = PREFIX + "clockrate"_id;
    //@}

    //@{
    //! @name Chip type
    const IntType TYPE_AY = 0;
    const IntType TYPE_YM = 1;
    //! Default is AY
    const IntType TYPE_DEFAULT = TYPE_AY;
    //! Parameter name
    const auto TYPE = PREFIX + "type"_id;
    //@}

    //@{
    //! @name Interpolation mode
    const IntType INTERPOLATION_NONE = 0;
    const IntType INTERPOLATION_LQ = 1;
    const IntType INTERPOLATION_HQ = 2;
    //! Default is HQ
    const IntType INTERPOLATION_DEFAULT = INTERPOLATION_HQ;
    //! Parameter name
    const auto INTERPOLATION = PREFIX + "interpolation"_id;
    //@}

    //! @brief Frequency table for ay-based plugins
    //! @details String- table name or dump @see freq_tables.h
    const auto TABLE = PREFIX + "table"_id;

    //@{
    //! @name Duty cycle in percents
    const IntType DUTY_CYCLE_MIN = 1;
    const IntType DUTY_CYCLE_MAX = 99;
    // Default is 50%
    const IntType DUTY_CYCLE_DEFAULT = 50;
    //! Parameter name
    const auto DUTY_CYCLE = PREFIX + "duty_cycle"_id;
    //@}

    //@{
    //! @name Duty cycle applied channels masks
    //! @details @see core/devices/aym.h
    const IntType DUTY_CYCLE_MASK_DEFAULT = 0;
    //! Parameter name
    const auto DUTY_CYCLE_MASK = PREFIX + "duty_cycle_mask"_id;
    //@}

    //@{
    //! @name Channels layout parameter
    //! @details @see core/devices/aym/chip.h
    const IntType LAYOUT_ABC = 0;
    const IntType LAYOUT_ACB = 1;
    const IntType LAYOUT_BAC = 2;
    const IntType LAYOUT_BCA = 3;
    const IntType LAYOUT_CAB = 4;
    const IntType LAYOUT_CBA = 5;
    const IntType LAYOUT_MONO = 6;
    //! Default is ABC
    const IntType LAYOUT_DEFAULT = LAYOUT_ABC;
    //! Parameter name
    const auto LAYOUT = PREFIX + "layout"_id;
    //@}
  }  // namespace AYM

  //! @brief DAC-related parameters namespace
  namespace DAC
  {
    //! @brief Parameters#ZXTune#Core#DAC namespace prefix
    const auto PREFIX = Core::PREFIX + "dac"_id;

    //@{
    //! @name Interpolation mode
    const IntType INTERPOLATION_NO = 0;
    const IntType INTERPOLATION_YES = 1;
    //! Default is LQ
    const IntType INTERPOLATION_DEFAULT = INTERPOLATION_NO;
    //! Parameter name
    const auto INTERPOLATION = PREFIX + "interpolation"_id;
    //@}

    const IntType SAMPLES_FREQUENCY_MIN = 800;
    const IntType SAMPLES_FREQUENCY_MAX = 16000;
    //! @brief Base samples frequency for C-1 (32.7Hz)
    const auto SAMPLES_FREQUENCY = PREFIX + "samples_frequency"_id;
  }  // namespace DAC

  //! @brief Z80-related parameters namespace
  namespace Z80
  {
    //! @brief Parameters#ZXTune#Core#Z80 namespace prefix
    const auto PREFIX = Core::PREFIX + "z80"_id;

    //@{
    //! @name CPU int duration in ticks

    //! Default value
    const IntType INT_TICKS_DEFAULT = 24;
    //! Parameter name
    const auto INT_TICKS = PREFIX + "int_ticks"_id;
    //@}

    //@{
    //! @name CPU clockrate in Hz

    //! Default value- 3.5MHz
    const IntType CLOCKRATE_DEFAULT = UINT64_C(3500000);
    const IntType CLOCKRATE_MIN = 1000000;
    const IntType CLOCKRATE_MAX = UINT64_C(10000000);
    //! Parameter name
    const auto CLOCKRATE = PREFIX + "clockrate"_id;
    //@}
  }  // namespace Z80

  //! @brief FM-related parameters namespace
  namespace FM
  {
    //! @brief Parameters#ZXTune#Core#FM namespace prefix
    const auto PREFIX = Core::PREFIX + "fm"_id;

    //@{
    //! @name FM clockrate in Hz

    //! Default value- 3.5MHz
    const IntType CLOCKRATE_DEFAULT = UINT64_C(3500000);
    //! Parameter name
    const auto CLOCKRATE = PREFIX + "clockrate"_id;
    //@}

    //! Yamaha FM chips of external players (VGM/S98 via libvgm), grouped by family. Applied while playing:
    //! emulation changes restart the chips at the same position, output stage changes are applied in place.
    //! Platform presets set the chip variant and the analog output stage of a real board, see
    //! src/module/players/external/fm_output.cpp for references. Custom values are used with PRESET_CUSTOM only.
    //! Output stage of the first family found in a tune (in the order below) is applied to the whole tune's output,
    //! as the chips of a board share it (e.g. YM2612 and PSG of Mega Drive).

    //@{
    //! @name Output stage parameters common for all the families
    //! Platform preset to use custom values
    const IntType PRESET_CUSTOM = 0;
    //! Low-pass filter cutoff in Hz, 0 to disable
    const IntType LOWPASS_MIN = 0;
    const IntType LOWPASS_MAX = 24000;
    //! Low-pass filter order: 1 (6dB/oct, RC) or 2 (12dB/oct, Butterworth)
    const IntType LOWPASS_ORDER_MIN = 1;
    const IntType LOWPASS_ORDER_MAX = 2;
    //! High-pass (output coupling capacitor) cutoff in Hz, 0 to keep DC bias of chip output
    const IntType HIGHPASS_MIN = 0;
    const IntType HIGHPASS_MAX = 200;
    //! Stereo separation in percents: 0 - mono, 100 - as emulated
    const IntType STEREO_MIN = 0;
    const IntType STEREO_MAX = 100;
    //@}

    //! @brief YM2612/YM3438/YMF276 (OPN2): Mega Drive/Genesis, FM Towns
    namespace OPN2
    {
      const auto PREFIX = FM::PREFIX + "opn2"_id;

      //@{
      //! @name Emulation core
      const IntType CORE_NUKED = 0;
      const IntType CORE_YMFM = 1;
      const IntType CORE_GPGX = 2;
      const IntType CORE_DEFAULT = CORE_NUKED;
      const auto CORE = PREFIX + "core"_id;
      //@}

      //@{
      //! @name Platform preset
      const IntType PRESET_MEGADRIVE1 = 1;
      const IntType PRESET_MEGADRIVE1_VA7 = 2;
      const IntType PRESET_MEGADRIVE2 = 3;
      const IntType PRESET_GENESIS3 = 4;
      const IntType PRESET_YM3438 = 5;
      const IntType PRESET_YMF276 = 6;
      const IntType PRESET_DEFAULT = PRESET_MEGADRIVE1;
      const auto PRESET = PREFIX + "preset"_id;
      //@}

      //@{
      //! @name Custom preset
      //! Chip variant, YM3438 is used anyway if tune requires it. Nuked and ymfm cores only
      const IntType CHIP_YM2612 = 0;
      const IntType CHIP_YM3438 = 1;
      const IntType CHIP_YMF276 = 2;
      const IntType CHIP_DEFAULT = CHIP_YM2612;
      const auto CHIP = PREFIX + "chip"_id;
      //! YM2612 DAC crossover distortion (ladder effect), 0/1. Nuked and ymfm cores only
      const IntType LADDER_DEFAULT = 1;
      const auto LADDER = PREFIX + "ladder"_id;
      const IntType LOWPASS_DEFAULT = 3390;
      const auto LOWPASS = PREFIX + "lowpass"_id;
      const IntType LOWPASS_ORDER_DEFAULT = 1;
      const auto LOWPASS_ORDER = PREFIX + "lowpass_order"_id;
      const IntType HIGHPASS_DEFAULT = 5;
      const auto HIGHPASS = PREFIX + "highpass"_id;
      const IntType STEREO_DEFAULT = 100;
      const auto STEREO = PREFIX + "stereo"_id;
      //@}
    }  // namespace OPN2

    //! @brief YM2151 (OPM): arcade boards, Sharp X68000/X1
    namespace OPM
    {
      const auto PREFIX = FM::PREFIX + "opm"_id;

      //@{
      //! @name Emulation core
      const IntType CORE_NUKED = 0;
      const IntType CORE_YMFM = 1;
      const IntType CORE_MAME = 2;
      const IntType CORE_DEFAULT = CORE_YMFM;
      const auto CORE = PREFIX + "core"_id;
      //@}

      //@{
      //! @name Platform preset
      const IntType PRESET_YM3012 = 1;
      const IntType PRESET_YM3014 = 2;
      const IntType PRESET_DEFAULT = PRESET_YM3012;
      const auto PRESET = PREFIX + "preset"_id;
      //@}

      //@{
      //! @name Custom preset
      const IntType LOWPASS_DEFAULT = 0;
      const auto LOWPASS = PREFIX + "lowpass"_id;
      const IntType LOWPASS_ORDER_DEFAULT = 2;
      const auto LOWPASS_ORDER = PREFIX + "lowpass_order"_id;
      const IntType HIGHPASS_DEFAULT = 0;
      const auto HIGHPASS = PREFIX + "highpass"_id;
      const IntType STEREO_DEFAULT = 100;
      const auto STEREO = PREFIX + "stereo"_id;
      //@}
    }  // namespace OPM

    //! @brief YM2203/YM2608/YM2610 (OPN/OPNA/OPNB): NEC PC-88/PC-98, Neo Geo, arcade boards
    namespace OPN
    {
      const auto PREFIX = FM::PREFIX + "opn"_id;

      //@{
      //! @name Emulation core
      const IntType CORE_YMFM = 0;
      const IntType CORE_MAME = 1;
      const IntType CORE_DEFAULT = CORE_YMFM;
      const auto CORE = PREFIX + "core"_id;
      //@}

      //@{
      //! @name Platform preset
      const IntType PRESET_PC88_98 = 1;
      const IntType PRESET_DEFAULT = PRESET_PC88_98;
      const auto PRESET = PREFIX + "preset"_id;
      //@}

      //@{
      //! @name Custom preset
      const IntType LOWPASS_DEFAULT = 0;
      const auto LOWPASS = PREFIX + "lowpass"_id;
      const IntType LOWPASS_ORDER_DEFAULT = 2;
      const auto LOWPASS_ORDER = PREFIX + "lowpass_order"_id;
      const IntType HIGHPASS_DEFAULT = 0;
      const auto HIGHPASS = PREFIX + "highpass"_id;
      const IntType STEREO_DEFAULT = 100;
      const auto STEREO = PREFIX + "stereo"_id;
      //@}
    }  // namespace OPN

    //! @brief YM3526/Y8950/YM3812/YMF262 (OPL/OPL2/OPL3): AdLib and Sound Blaster cards, arcade boards, MSX
    namespace OPL
    {
      const auto PREFIX = FM::PREFIX + "opl"_id;

      //@{
      //! @name Emulation core of YM3812 and YMF262 (also FM part of YMF278B), others are emulated by MAME only
      const IntType CORE_NUKED = 0;
      const IntType CORE_ADLIBEMU = 1;
      const IntType CORE_MAME = 2;
      const IntType CORE_DEFAULT = CORE_NUKED;
      const auto CORE = PREFIX + "core"_id;
      //@}

      //@{
      //! @name Card preset
      const IntType PRESET_ADLIB = 1;
      const IntType PRESET_SB2 = 2;
      const IntType PRESET_SBPRO2 = 3;
      const IntType PRESET_SB16 = 4;
      const IntType PRESET_DEFAULT = PRESET_SB16;
      const auto PRESET = PREFIX + "preset"_id;
      //@}

      //@{
      //! @name Custom preset
      const IntType LOWPASS_DEFAULT = 0;
      const auto LOWPASS = PREFIX + "lowpass"_id;
      const IntType LOWPASS_ORDER_DEFAULT = 1;
      const auto LOWPASS_ORDER = PREFIX + "lowpass_order"_id;
      const IntType HIGHPASS_DEFAULT = 0;
      const auto HIGHPASS = PREFIX + "highpass"_id;
      const IntType STEREO_DEFAULT = 100;
      const auto STEREO = PREFIX + "stereo"_id;
      //@}
    }  // namespace OPL

    //! @brief YM2413 (OPLL), VRC7: Sega Master System, MSX, Famicom
    namespace OPLL
    {
      const auto PREFIX = FM::PREFIX + "opll"_id;

      //@{
      //! @name Emulation core
      const IntType CORE_NUKED = 0;
      const IntType CORE_EMU2413 = 1;
      const IntType CORE_MAME = 2;
      const IntType CORE_DEFAULT = CORE_NUKED;
      const auto CORE = PREFIX + "core"_id;
      //@}

      //@{
      //! @name Output stage, mono chip so no stereo setting
      const IntType LOWPASS_DEFAULT = 0;
      const auto LOWPASS = PREFIX + "lowpass"_id;
      const IntType LOWPASS_ORDER_DEFAULT = 2;
      const auto LOWPASS_ORDER = PREFIX + "lowpass_order"_id;
      const IntType HIGHPASS_DEFAULT = 0;
      const auto HIGHPASS = PREFIX + "highpass"_id;
      //@}
    }  // namespace OPLL
  }    // namespace FM

  //! @brief SAA-related parameters namespace
  namespace SAA
  {
    //! @brief Parameters#ZXTune#Core#SAA namespace prefix
    const auto PREFIX = Core::PREFIX + "saa"_id;

    //@{
    //! @name SAA clockrate in Hz

    //! Default value- 8MHz
    const IntType CLOCKRATE_DEFAULT = UINT64_C(8000000);
    const IntType CLOCKRATE_MIN = UINT64_C(4000000);
    const IntType CLOCKRATE_MAX = UINT64_C(16000000);
    //! Parameter name
    const auto CLOCKRATE = PREFIX + "clockrate"_id;
    //@}

    //@{
    //! @name Interpolation mode
    const IntType INTERPOLATION_NONE = 0;
    const IntType INTERPOLATION_LQ = 1;
    const IntType INTERPOLATION_HQ = 2;
    //! Default is LQ
    const IntType INTERPOLATION_DEFAULT = INTERPOLATION_LQ;
    //! Parameter name
    const auto INTERPOLATION = PREFIX + "interpolation"_id;
    //@}
  }  // namespace SAA

  //! @brief SID-related parameters namespace
  namespace SID
  {
    //! @brief Parameters#ZXTune#Core#SID namespace prefix
    const auto PREFIX = Core::PREFIX + "sid"_id;

    //@{
    //! @name SID filter emulation
    const IntType FILTER_DISABLED = 0;
    const IntType FILTER_ENABLED = 1;
    //! Default is enabled
    const IntType FILTER_DEFAULT = FILTER_ENABLED;
    //! Parameter name
    const auto FILTER = PREFIX + "filter"_id;
    //@}

    //@{
    //! @name Interpolation mode
    const IntType INTERPOLATION_NONE = 0;
    const IntType INTERPOLATION_LQ = 1;
    const IntType INTERPOLATION_HQ = 2;
    //! Default is LQ
    const IntType INTERPOLATION_DEFAULT = INTERPOLATION_NONE;
    //! Parameter name
    const auto INTERPOLATION = PREFIX + "interpolation"_id;
    //@}

    //@{
    //! @name Emulation engine
    const IntType ENGINE_RESID = 0;
    const IntType ENGINE_RESIDFP = 1;
    //! Default is reSIDfp
    const IntType ENGINE_DEFAULT = ENGINE_RESIDFP;
    //! Parameter name
    const auto ENGINE = PREFIX + "engine"_id;
    //@}

    //@{
    //! @name Chip model. Used for tunes with unknown model, for all tunes if forced
    const IntType MODEL_6581 = 0;
    const IntType MODEL_8580 = 1;
    //! Default is 6581
    const IntType MODEL_DEFAULT = MODEL_6581;
    //! Parameter name
    const auto MODEL = PREFIX + "model"_id;
    //! Force model (0/1), default is not
    const IntType MODEL_FORCE_DEFAULT = 0;
    const auto MODEL_FORCE = PREFIX + "model_force"_id;
    //@}

    //@{
    //! @name C64 clock model. Used for tunes with unknown clock, for all tunes if forced
    const IntType CLOCK_PAL = 0;
    const IntType CLOCK_NTSC = 1;
    const IntType CLOCK_OLD_NTSC = 2;
    const IntType CLOCK_DREAN = 3;
    const IntType CLOCK_PAL_M = 4;
    //! Default is PAL
    const IntType CLOCK_DEFAULT = CLOCK_PAL;
    //! Parameter name
    const auto CLOCK = PREFIX + "clock"_id;
    //! Force clock (0/1), default is not
    const IntType CLOCK_FORCE_DEFAULT = 0;
    const auto CLOCK_FORCE = PREFIX + "clock_force"_id;
    //@}

    //@{
    //! @name 8580 digi boost (0/1)
    const IntType DIGIBOOST_DEFAULT = 0;
    const auto DIGIBOOST = PREFIX + "digiboost"_id;
    //@}

    //@{
    //! @name reSID 6581 filter DAC bias in millivolts, -1000..1000
    const IntType FILTER_BIAS_MIN = -1000;
    const IntType FILTER_BIAS_MAX = 1000;
    const IntType FILTER_BIAS_DEFAULT = 0;
    const auto FILTER_BIAS = PREFIX + "filter_bias"_id;
    //@}

    //@{
    //! @name reSIDfp filters tuning in percents, 0..100
    //! 6581 filter curve: 0 - light, 100 - dark
    const IntType FILTER_6581_CURVE_DEFAULT = 50;
    const auto FILTER_6581_CURVE = PREFIX + "filter_6581_curve"_id;
    //! 6581 filter range: 0 - dark, 100 - light
    const IntType FILTER_6581_RANGE_DEFAULT = 50;
    const auto FILTER_6581_RANGE = PREFIX + "filter_6581_range"_id;
    //! 8580 filter curve: 0 - light, 100 - dark
    const IntType FILTER_8580_CURVE_DEFAULT = 50;
    const auto FILTER_8580_CURVE = PREFIX + "filter_8580_curve"_id;
    //@}

    //@{
    //! @name Filters presets: measured or tuned cutoff curves of JSIDPlay2 and VICE, applied to both engines.
    //! See module/players/external/sid/filters.cpp for values. Custom one uses FILTER_BIAS/FILTER_*_CURVE/RANGE
    const IntType FILTER_PRESET_CUSTOM = 0;
    const IntType FILTER_6581_PRESET_DEFAULT = FILTER_PRESET_CUSTOM;
    const auto FILTER_6581_PRESET = PREFIX + "filter_6581_preset"_id;
    const IntType FILTER_8580_PRESET_DEFAULT = FILTER_PRESET_CUSTOM;
    const auto FILTER_8580_PRESET = PREFIX + "filter_8580_preset"_id;
    //@}

    //@{
    //! @name reSIDfp combined waveforms strength
    const IntType COMBINED_WAVEFORMS_AVERAGE = 0;
    const IntType COMBINED_WAVEFORMS_WEAK = 1;
    const IntType COMBINED_WAVEFORMS_STRONG = 2;
    const IntType COMBINED_WAVEFORMS_DEFAULT = COMBINED_WAVEFORMS_AVERAGE;
    const auto COMBINED_WAVEFORMS = PREFIX + "combined_waveforms"_id;
    //! reSIDfp 6581 pulse+saw: 0 - accumulator MSB is pulled down by the combined output as in most of the measured
    //! chips (silent with pulse width over 50%), 1 - kept as in some chips (and reSID sampled tables), louder sound
    const IntType STRONG_PULSE_SAW_DEFAULT = 0;
    const auto STRONG_PULSE_SAW = PREFIX + "strong_pulse_saw"_id;
    //@}
  }  // namespace SID

  //! @brief MT-32 (Munt) related parameters namespace
  namespace MT32
  {
    //! @brief Parameters#ZXTune#Core#MT32 namespace prefix
    const auto PREFIX = Core::PREFIX + "mt32"_id;

    //@{
    //! @name Directory with control and PCM ROM images (full or partial dumps, detected by contents)
    const auto ROMS_PATH = PREFIX + "roms_path"_id;
    //@}

    //@{
    //! @name Preferred model if several ROM sets are available
    const IntType MODEL_AUTO = 0;
    const IntType MODEL_MT32 = 1;
    const IntType MODEL_CM32L = 2;
    const IntType MODEL_DEFAULT = MODEL_AUTO;
    const auto MODEL = PREFIX + "model"_id;
    //@}

    //@{
    //! @name Separate voices visualization: 9 parts or 32 partials of LA synthesis
    const IntType VOICES_PARTS = 0;
    const IntType VOICES_PARTIALS = 1;
    const IntType VOICES_DEFAULT = VOICES_PARTS;
    const auto VOICES = PREFIX + "voices"_id;
    //@}
  }  // namespace MT32
}  // namespace Parameters::ZXTune::Core
