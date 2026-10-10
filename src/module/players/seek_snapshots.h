/**
 *
 * @file
 *
 * @brief  Emulation state snapshots for seeking
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "types.h"

#include <algorithm>
#include <limits>
#include <memory>
#include <vector>

namespace Parameters
{
  class Accessor;
}

namespace Module
{
  //! @return memory limit for snapshots of single renderer, 0 if disabled
  uint64_t GetSeekSnapshotsMemoryLimit(const Parameters::Accessor& params);

  // Backward seeking requires emulation from the very beginning, so states of engine are taken periodically while
  // playing to start from the closest one.
  // States are identified by amount of emulated samples since reset. Engine should produce the same state for the
  // same amount regardless of how emulation was split into render/skip calls, so restored state followed by skip is
  // identical to reset followed by skip.
  template<class StateType>
  class SeekSnapshots
  {
  public:
    using StatePtr = std::unique_ptr<StateType>;

    struct Entry
    {
      uint64_t Position = 0;
      StatePtr State;
    };

    //! @param minPeriod minimal distance between snapshots
    //! @param limit position to take snapshots up to, usually track duration
    SeekSnapshots(uint64_t minPeriod, uint64_t limit, std::size_t stateSize, uint64_t memoryLimit)
      : MaxCount(
          static_cast<std::size_t>(std::min<uint64_t>(memoryLimit / std::max<std::size_t>(stateSize, 1), MAX_COUNT)))
      , Period(std::max<uint64_t>(std::max<uint64_t>(minPeriod, 1), MaxCount ? limit / MaxCount + 1 : 0))
      , Limit(limit)
    {}

    //! @brief Engine is reset with current settings, so snapshots are consistent with it
    void Restart()
    {
      Active = MaxCount >= MIN_COUNT;
    }

    //! @brief Settings affecting emulation are changed, so neither stored snapshots nor current engine state
    //!        are reproducible by reset followed by skip
    void Invalidate()
    {
      Entries.clear();
      Active = false;
    }

    //! @return position to split skipping at to take snapshot, max value if not required
    uint64_t NextAt(uint64_t pos) const
    {
      if (!Active)
      {
        return std::numeric_limits<uint64_t>::max();
      }
      const auto* prev = Find(0, pos);
      const auto next = (prev ? prev->Position : 0) + Period;
      return next <= Limit ? next : std::numeric_limits<uint64_t>::max();
    }

    bool IsDue(uint64_t pos) const
    {
      return pos >= NextAt(pos);
    }

    void Add(uint64_t pos, StatePtr state)
    {
      const auto it = std::upper_bound(Entries.begin(), Entries.end(), pos,
                                       [](uint64_t p, const Entry& e) { return p < e.Position; });
      Entries.insert(it, Entry{pos, std::move(state)});
      if (Entries.size() > MaxCount)
      {
        Thin();
      }
    }

    //! @return closest snapshot in range (after, upTo]
    const Entry* Find(uint64_t after, uint64_t upTo) const
    {
      const auto it = std::upper_bound(Entries.begin(), Entries.end(), upTo,
                                       [](uint64_t p, const Entry& e) { return p < e.Position; });
      if (it == Entries.begin())
      {
        return nullptr;
      }
      const auto& res = *std::prev(it);
      return res.Position > after ? &res : nullptr;
    }

  private:
    // Keep uniform coverage of the whole played range in bounded memory if limit is not reliable
    void Thin()
    {
      std::size_t out = 0;
      for (std::size_t in = 1; in < Entries.size(); in += 2)
      {
        Entries[out++] = std::move(Entries[in]);
      }
      Entries.resize(out);
      Period *= 2;
    }

  private:
    // less snapshots are not worth it
    static const std::size_t MIN_COUNT = 2;
    static const std::size_t MAX_COUNT = 1024;
    const std::size_t MaxCount;
    uint64_t Period;
    const uint64_t Limit;
    bool Active = false;
    std::vector<Entry> Entries;
  };

  // Emulated samples counter since engine reset with snapshots taken on the way and applied on seeking.
  // Engine should provide:
  // - Snapshot type
  // - void Skip(uint_t samples)
  // - std::unique_ptr<Snapshot> SaveState() const
  // - void LoadState(const Snapshot&) to make engine state exactly as after skip from saved state
  template<class EngineType>
  class EngineTimeline
  {
  public:
    using Snapshot = typename EngineType::Snapshot;

    EngineTimeline(uint64_t minPeriod, uint64_t limit, std::size_t stateSize, uint64_t memoryLimit)
      : Snapshots(minPeriod, limit, stateSize, memoryLimit)
    {
      Snapshots.Restart();
    }

    //! @brief Engine is reset with settings it's going to be reset with on backward seeking
    void Reset()
    {
      Samples = 0;
      Snapshots.Restart();
    }

    //! @brief Engine settings affecting emulation are changed, snapshots are not taken till the next Reset()
    void Invalidate()
    {
      Snapshots.Invalidate();
    }

    void Rendered(const EngineType& engine, uint64_t samples)
    {
      Samples += samples;
      TakeSnapshot(engine);
    }

    //! @param backward seeking requires engine reset
    //! @param toSkip samples count to skip after reset (if backward) or from current position
    //! @param reset engine reset callback, should call Reset()
    //! @return true if engine state was restored from snapshot
    template<class ResetFunc>
    bool Seek(EngineType& engine, bool backward, uint64_t toSkip, ResetFunc&& reset)
    {
      const auto start = backward ? 0 : Samples;
      const auto target = start + toSkip;
      const auto* snapshot = Snapshots.Find(start, target);
      if (snapshot)
      {
        engine.LoadState(*snapshot->State);
        Samples = snapshot->Position;
      }
      else if (backward)
      {
        reset();
      }
      Skip(engine, target - Samples);
      return snapshot != nullptr;
    }

  private:
    void Skip(EngineType& engine, uint64_t samples)
    {
      // split to take snapshots on the way
      for (const auto target = Samples + samples; Samples < target;)
      {
        const auto part = std::min(target, Snapshots.NextAt(Samples)) - Samples;
        engine.Skip(static_cast<uint_t>(part));
        Samples += part;
        TakeSnapshot(engine);
      }
    }

    void TakeSnapshot(const EngineType& engine)
    {
      if (Snapshots.IsDue(Samples))
      {
        Snapshots.Add(Samples, engine.SaveState());
      }
    }

  private:
    uint64_t Samples = 0;
    SeekSnapshots<Snapshot> Snapshots;
  };
}  // namespace Module
