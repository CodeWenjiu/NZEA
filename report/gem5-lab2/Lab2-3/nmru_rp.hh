/**
 * NMRU (Not Most Recently Used) replacement policy.
 *
 * The victim is chosen at random among all candidates except the single most
 * recently used line. Invalid entries are evicted first.
 */

#ifndef __MEM_CACHE_REPLACEMENT_POLICIES_NMRU_RP_HH__
#define __MEM_CACHE_REPLACEMENT_POLICIES_NMRU_RP_HH__

#include "base/random.hh"
#include "mem/cache/replacement_policies/base.hh"

namespace gem5
{

struct NMRURPParams;

namespace replacement_policy
{

class NMRU : public Base
{
  protected:
    mutable gem5::Random::RandomPtr rng = gem5::Random::genRandom();

    struct NMRUReplData : ReplacementData
    {
        /** Tick on which the entry was last touched. */
        Tick lastTouchTick;

        /** Invalid entries are prioritized for eviction. */
        bool valid;

        NMRUReplData() : lastTouchTick(0), valid(false) {}
    };

  public:
    typedef NMRURPParams Params;
    NMRU(const Params &p);
    ~NMRU() = default;

    void invalidate(const std::shared_ptr<ReplacementData>& replacement_data)
                                                                    override;

    void touch(const std::shared_ptr<ReplacementData>& replacement_data) const
                                                                     override;

    void reset(const std::shared_ptr<ReplacementData>& replacement_data) const
                                                                     override;

    ReplaceableEntry* getVictim(const ReplacementCandidates& candidates) const
                                                                     override;

    std::shared_ptr<ReplacementData> instantiateEntry() override;
};

} // namespace replacement_policy
} // namespace gem5

#endif // __MEM_CACHE_REPLACEMENT_POLICIES_NMRU_RP_HH__
