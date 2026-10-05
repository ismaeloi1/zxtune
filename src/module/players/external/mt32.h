/**
 *
 * @file
 *
 * @brief  MIDI files support via MT-32 emulation (Munt)
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "module/players/factory.h"

namespace Module::MT32
{
  Factory::Ptr CreateFactory();
}  // namespace Module::MT32
