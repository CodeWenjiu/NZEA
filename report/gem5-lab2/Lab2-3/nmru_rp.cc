#include "mem/cache/replacement_policies/nmru_rp.hh"

#include <cassert>
#include <memory>
#include <vector>

#include "params/NMRURP.hh"
#include "sim/cur_tick.hh"

namespace gem5
{

namespace replacement_policy
{

NMRU::NMRU(const Params &p)
  : Base(p)
{
}

void
NMRU::invalidate(const std::shared_ptr<ReplacementData>& replacement_data)
{
    auto data = std::static_pointer_cast<NMRUReplData>(replacement_data);
    data->valid = false;
    data->lastTouchTick = Tick(0);
}

void
NMRU::touch(const std::shared_ptr<ReplacementData>& replacement_data) const
{
    auto data = std::static_pointer_cast<NMRUReplData>(replacement_data);
    data->valid = true;
    data->lastTouchTick = curTick();
}

void
NMRU::reset(const std::shared_ptr<ReplacementData>& replacement_data) const
{
    auto data = std::static_pointer_cast<NMRUReplData>(replacement_data);
    data->valid = true;
    data->lastTouchTick = curTick();
}

ReplaceableEntry*
NMRU::getVictim(const ReplacementCandidates& candidates) const
{
    assert(candidates.size() > 0);

    // An invalid entry is always the preferred victim.
    for (const auto& candidate : candidates) {
        if (!std::static_pointer_cast<NMRUReplData>(
                candidate->replacementData)->valid) {
            return candidate;
        }
    }

    // Protect the most recently used entry.
    ReplaceableEntry* mru = candidates[0];
    Tick max_tick = std::static_pointer_cast<NMRUReplData>(
        mru->replacementData)->lastTouchTick;
    for (const auto& candidate : candidates) {
        Tick tick = std::static_pointer_cast<NMRUReplData>(
            candidate->replacementData)->lastTouchTick;
        if (tick > max_tick) {
            max_tick = tick;
            mru = candidate;
        }
    }

    // Choose uniformly at random among the remaining candidates.
    std::vector<ReplaceableEntry*> rest;
    rest.reserve(candidates.size() - 1);
    for (const auto& candidate : candidates) {
        if (candidate != mru) {
            rest.push_back(candidate);
        }
    }

    return rest[rng->random<unsigned>(0, rest.size() - 1)];
}

std::shared_ptr<ReplacementData>
NMRU::instantiateEntry()
{
    return std::shared_ptr<ReplacementData>(new NMRUReplData());
}

} // namespace replacement_policy
} // namespace gem5
