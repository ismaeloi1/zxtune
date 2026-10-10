/**
 *
 * @file
 *
 * @brief  Emulation state snapshots for seeking implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "module/players/seek_snapshots.h"

#include "core/core_parameters.h"
#include "parameters/accessor.h"

namespace Module
{
  uint64_t GetSeekSnapshotsMemoryLimit(const Parameters::Accessor& params)
  {
    using namespace Parameters::ZXTune::Core;
    const auto value = Parameters::GetInteger(params, SEEK_SNAPSHOTS_MEMORY, SEEK_SNAPSHOTS_MEMORY_DEFAULT);
    return static_cast<uint64_t>(std::max<Parameters::IntType>(value, 0));
  }
}  // namespace Module
