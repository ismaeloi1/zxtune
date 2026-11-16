// Minimal replacement of nall types used by ares SPC700/SMP/DSP sources.
// Only the subset required by imported files, with the same semantics (see nall/primitives).
#pragma once

#include <cmath>
#include <cstdint>
#include <optional>
#include <type_traits>

namespace ares
{
  using u8 = uint8_t;
  using u16 = uint16_t;
  using u32 = uint32_t;
  using u64 = uint64_t;
  using s8 = int8_t;
  using s16 = int16_t;
  using s32 = int32_t;
  using s64 = int64_t;
  using f64 = double;

  namespace Math
  {
    constexpr f64 Pi = 3.14159265358979323846;
  }

  template<typename T>
  using maybe = std::optional<T>;
  constexpr auto nothing = std::nullopt;

  template<u32 Bits>
  constexpr s64 sclamp(s64 x)
  {
    constexpr s64 b = s64(1) << (Bits - 1);
    constexpr s64 m = b - 1;
    return x > m ? m : x < -b ? -b : x;
  }

  struct range
  {
    struct iterator
    {
      u32 value;
      u32 operator*() const
      {
        return value;
      }
      bool operator!=(const iterator& rh) const
      {
        return value != rh.value;
      }
      iterator& operator++()
      {
        ++value;
        return *this;
      }
    };

    constexpr explicit range(u32 size)
      : limit(size)
    {}

    iterator begin() const
    {
      return {0};
    }

    iterator end() const
    {
      return {limit};
    }

    u32 limit;
  };

  // reference to bits range of storage
  template<typename U>
  struct BitRange
  {
    U& target;
    u32 shift;
    U mask;

    operator U() const
    {
      return static_cast<U>((target & mask) >> shift);
    }

    template<typename T>
    BitRange& operator=(const T& value)
    {
      target = static_cast<U>((target & ~mask) | ((static_cast<U>(value) << shift) & mask));
      return *this;
    }

    BitRange& operator=(const BitRange& value)
    {
      return *this = static_cast<U>(value);
    }

    template<typename T>
    BitRange& operator^=(const T& value)
    {
      return *this = static_cast<U>(static_cast<U>(*this) ^ static_cast<U>(value));
    }

    template<typename T>
    BitRange& operator|=(const T& value)
    {
      return *this = static_cast<U>(static_cast<U>(*this) | static_cast<U>(value));
    }

    template<typename T>
    BitRange& operator&=(const T& value)
    {
      return *this = static_cast<U>(static_cast<U>(*this) & static_cast<U>(value));
    }
  };

  template<u32 Bits>
  struct Integer;

  template<u32 Bits>
  struct Natural
  {
    using utype = std::conditional_t<Bits <= 8, u8, std::conditional_t<Bits <= 16, u16, u32>>;
    static constexpr u32 Mask = static_cast<u32>(~u64(0) >> (64 - Bits));

    constexpr Natural() = default;

    // any type convertible to integer, as in nall
    template<typename T, std::enable_if_t<std::is_convertible_v<T, u32>, int> = 0>
    constexpr Natural(const T& value)
      : data(static_cast<utype>(static_cast<u32>(value) & Mask))
    {}

    template<u32 Other>
    constexpr Natural(Natural<Other> value)
      : Natural(static_cast<u32>(value))
    {}

    template<u32 Other>
    constexpr Natural(Integer<Other> value)
      : Natural(static_cast<s32>(value))
    {}

    template<typename U>
    constexpr Natural(const BitRange<U>& value)
      : Natural(static_cast<U>(value))
    {}

    constexpr operator utype() const
    {
      return data;
    }

    Natural operator++(int)
    {
      const auto prev = *this;
      *this = data + 1;
      return prev;
    }

    Natural operator--(int)
    {
      const auto prev = *this;
      *this = data - 1;
      return prev;
    }

    Natural& operator++()
    {
      return *this = data + 1;
    }

    Natural& operator--()
    {
      return *this = data - 1;
    }

#define ARES_SHIM_ASSIGN(OP)                                                                                           \
  template<typename T>                                                                                                 \
  Natural& operator OP##=(const T& value)                                                                              \
  {                                                                                                                    \
    return *this = static_cast<s64>(data) OP static_cast<s64>(value);                                                  \
  }
    ARES_SHIM_ASSIGN(+)
    ARES_SHIM_ASSIGN(-)
    ARES_SHIM_ASSIGN(*)
    ARES_SHIM_ASSIGN(&)
    ARES_SHIM_ASSIGN(|)
    ARES_SHIM_ASSIGN(^)
    ARES_SHIM_ASSIGN(<<)
    ARES_SHIM_ASSIGN(>>)
#undef ARES_SHIM_ASSIGN

    BitRange<utype> bit(u32 index)
    {
      return {data, index, static_cast<utype>(utype(1) << index)};
    }

    constexpr utype bit(u32 index) const
    {
      return (data >> index) & 1;
    }

    BitRange<utype> bit(u32 lo, u32 hi)
    {
      return {data, lo, static_cast<utype>(((u32(1) << (hi - lo + 1)) - 1) << lo)};
    }

    constexpr utype bit(u32 lo, u32 hi) const
    {
      return (data >> lo) & ((u32(1) << (hi - lo + 1)) - 1);
    }

    BitRange<utype> byte(u32 index)
    {
      return bit(index * 8, index * 8 + 7);
    }

    constexpr utype byte(u32 index) const
    {
      return bit(index * 8, index * 8 + 7);
    }

    utype data = 0;
  };

  template<u32 Bits>
  struct Integer
  {
    using stype = std::conditional_t<Bits <= 8, s8, std::conditional_t<Bits <= 16, s16, s32>>;
    static constexpr u32 Mask = static_cast<u32>(~u64(0) >> (64 - Bits));
    static constexpr u32 Sign = u32(1) << (Bits - 1);

    constexpr Integer() = default;

    template<typename T, std::enable_if_t<std::is_arithmetic_v<T> || std::is_enum_v<T>, int> = 0>
    constexpr Integer(T value)
      : data(Extend(static_cast<s64>(value)))
    {}

    template<u32 Other>
    constexpr Integer(Natural<Other> value)
      : Integer(static_cast<u32>(value))
    {}

    template<u32 Other>
    constexpr Integer(Integer<Other> value)
      : Integer(static_cast<s32>(value))
    {}

    constexpr operator stype() const
    {
      return data;
    }

#define ARES_SHIM_ASSIGN(OP)                                                                                           \
  template<typename T>                                                                                                 \
  Integer& operator OP##=(const T& value)                                                                              \
  {                                                                                                                    \
    data = Extend(static_cast<s64>(data) OP static_cast<s64>(value));                                                  \
    return *this;                                                                                                      \
  }
    ARES_SHIM_ASSIGN(+)
    ARES_SHIM_ASSIGN(-)
    ARES_SHIM_ASSIGN(&)
    ARES_SHIM_ASSIGN(|)
#undef ARES_SHIM_ASSIGN

    constexpr u8 byte(u32 index) const
    {
      return static_cast<u8>(static_cast<u32>(data) >> (index * 8));
    }

    static constexpr stype Extend(s64 value)
    {
      const auto masked = static_cast<u32>(static_cast<u64>(value) & Mask);
      return static_cast<stype>(static_cast<s32>((masked ^ Sign) - Sign));
    }

    stype data = 0;
  };

  struct Boolean
  {
    constexpr Boolean() = default;
    constexpr Boolean(bool value)
      : data(value)
    {}

    constexpr operator bool() const
    {
      return data;
    }

    bool raise(bool value)
    {
      const bool result = !data && value;
      data = value;
      return result;
    }

    bool lower(bool value)
    {
      const bool result = data && !value;
      data = value;
      return result;
    }

    bool data = false;
  };

  using b1 = Boolean;
  using n1 = Natural<1>;
  using n2 = Natural<2>;
  using n3 = Natural<3>;
  using n4 = Natural<4>;
  using n5 = Natural<5>;
  using n7 = Natural<7>;
  using n8 = Natural<8>;
  using n11 = Natural<11>;
  using n14 = Natural<14>;
  using n15 = Natural<15>;
  using n16 = Natural<16>;
  using i8 = Integer<8>;
  using i16 = Integer<16>;
  using i17 = Integer<17>;
  using i32 = Integer<32>;

  static_assert(sizeof(n8) == 1, "n8 should be byte");
}  // namespace ares
