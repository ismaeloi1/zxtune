/**
 *
 * @file
 *
 * @brief  SID playroutine identification interface
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "binary/view.h"

#include "string_type.h"
#include "types.h"

namespace Module::Sid
{
  //! @return player routine name using SIDId signatures database, empty if not identified
  String DetectPlayroutine(Binary::View data);

  //! @return CIA1 timer A value set up by immediate stores in tune code (typical for multispeed tunes), 0 if not found
  uint_t FindCiaTimer(Binary::View data);
}  // namespace Module::Sid
