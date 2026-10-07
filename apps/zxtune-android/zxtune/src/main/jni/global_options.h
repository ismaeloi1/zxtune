/**
 *
 * @file
 *
 * @brief Global parameters interface
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "parameters/container.h"

namespace Parameters
{
  Container& GlobalOptions();

  // For options modified from UI thread while being polled by rendering thread
  Container::Ptr CreateSynchronizedContainer();
}  // namespace Parameters
