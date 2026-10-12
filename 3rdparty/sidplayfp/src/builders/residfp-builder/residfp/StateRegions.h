/*
 * This file is part of libsidplayfp, a SID player engine.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

#ifndef RESIDFP_STATEREGIONS_H
#define RESIDFP_STATEREGIONS_H

#include <cstddef>
#include <utility>
#include <vector>

namespace reSIDfp
{

/**
 * zxtune: memory occupied by emulation objects.
 *
 * Byte images of these regions make a snapshot of emulation state
 * which can be restored to the very same objects only, while their
 * configuration (and so owned objects and pointed tables) is unchanged.
 */
typedef std::vector<std::pair<void*, std::size_t>> StateRegions;

template<class T>
inline void addStateRegion(StateRegions &regions, T* object)
{
    regions.emplace_back(object, sizeof(T));
}

} // namespace reSIDfp

#endif
