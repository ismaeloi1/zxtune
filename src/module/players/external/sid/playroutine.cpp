/**
 *
 * @file
 *
 * @brief  SID playroutine identification implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "module/players/external/sid/playroutine.h"

#include "types.h"

#include <algorithm>
#include <optional>

namespace Module::Sid::SidId
{
  struct Signature
  {
    uint16_t Player;
    uint32_t Offset;
  };

#include "module/players/external/sid/sidid_db.inc"

  // Port of identifybytes from 3rdparty/sidid/sidid.c, keep the same semantics to get the same results as reference
  // tool (and HVSC STIL data based on it)
  bool Matches(const uint16_t* bytes, const uint8_t* buffer, std::size_t length)
  {
    std::size_t c = 0;
    std::size_t d = 0;
    std::size_t rc = 0;
    std::size_t rd = 0;
    while (c < length)
    {
      if (d == rd)
      {
        if (buffer[c] == bytes[d])
        {
          rc = c + 1;
          ++d;
        }
        ++c;
      }
      else
      {
        if (bytes[d] == END)
        {
          return true;
        }
        if (bytes[d] == AND)
        {
          ++d;
          while (c < length)
          {
            if (buffer[c] == bytes[d])
            {
              rc = c + 1;
              rd = d;
              break;
            }
            ++c;
          }
          if (c >= length)
          {
            return false;
          }
        }
        if (bytes[d] != ANY && buffer[c] != bytes[d])
        {
          c = rc;
          d = rd;
        }
        else
        {
          ++c;
          ++d;
        }
      }
    }
    return bytes[d] == END;
  }

}  // namespace Module::Sid::SidId

namespace Module::Sid
{
  String DetectPlayroutine(Binary::View data)
  {
    using namespace SidId;
    const auto* buffer = data.As<uint8_t>();
    const auto size = data.Size();
    if (!buffer || !size)
    {
      return {};
    }
    // signatures are ordered by priority (e.g. DigiOrganizer is the last one), so first match wins as in reference tool
    for (const auto& sig : SIGNATURES)
    {
      if (Matches(TOKENS + sig.Offset, buffer, size))
      {
        return PLAYERS[sig.Player];
      }
    }
    return {};
  }

  // Matches "ld? #imm; st? $dc0X" with the same register
  std::optional<uint8_t> GetImmediateStore(const uint8_t* code, uint8_t reg)
  {
    // lda/sta, ldx/stx, ldy/sty
    static const uint8_t LOADS[] = {0xa9, 0xa2, 0xa0};
    static const uint8_t STORES[] = {0x8d, 0x8e, 0x8c};
    for (std::size_t idx = 0; idx < 3; ++idx)
    {
      if (code[0] == LOADS[idx] && code[2] == STORES[idx] && code[3] == reg && code[4] == 0xdc)
      {
        return code[1];
      }
    }
    return std::nullopt;
  }

  uint_t FindCiaTimer(Binary::View data)
  {
    const auto* code = data.As<uint8_t>();
    const auto size = data.Size();
    // both bytes are usually set close to each other
    const std::size_t MAX_DISTANCE = 16;
    const std::size_t STORE_SIZE = 5;
    for (std::size_t lo = 0; lo + STORE_SIZE <= size; ++lo)
    {
      const auto low = GetImmediateStore(code + lo, 0x04);
      if (!low)
      {
        continue;
      }
      const auto from = lo > MAX_DISTANCE ? lo - MAX_DISTANCE : 0;
      const auto to = std::min(size - STORE_SIZE + 1, lo + MAX_DISTANCE);
      for (auto hi = from; hi < to; ++hi)
      {
        if (const auto high = GetImmediateStore(code + hi, 0x05))
        {
          return *low | (uint_t(*high) << 8);
        }
      }
    }
    return 0;
  }
}  // namespace Module::Sid
