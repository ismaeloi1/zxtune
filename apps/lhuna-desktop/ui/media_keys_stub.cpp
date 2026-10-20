/**
 *
 * @file
 *
 * @brief System media controls stub for platforms without integration
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/media_keys.h"

namespace Lhuna
{
  class MediaKeysStub : public MediaKeys
  {
  public:
    void Update(const MediaStatus& /*status*/) override {}
    void Seeked(uint32_t /*positionMs*/) override {}
  };

  std::unique_ptr<MediaKeys> MediaKeys::Create(QWidget* /*window*/, MediaCommands /*commands*/)
  {
    return std::make_unique<MediaKeysStub>();
  }
}  // namespace Lhuna
