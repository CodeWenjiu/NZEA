package nzea_core.front.ifu

import nzea_core.back.BpUpdate
import utest.*

/** The user-field layout is what the fetcher and any ibus peer agree on, so it is pinned here rather than left implicit
  * in the pack/unpack expressions.
  */
object IbusUserTest extends TestSuite:

    val tests = Tests:
        test("the user field is the epoch column plus the fall-through, next-pc, pc and counter columns"):
            for addrWidth <- Seq(32, 64) do
                val user = IbusUser.layout(addrWidth)
                assert(user.offset("epoch") == 0)
                assert(user.fieldWidth("epoch") == IbusUser.epochBits)
                assert(user.offset("next_pc") == IbusUser.epochBits)
                assert(user.fieldWidth("next_pc") == addrWidth)
                assert(user.offset("fall_through") == IbusUser.epochBits + addrWidth)
                assert(user.fieldWidth("fall_through") == addrWidth)
                assert(user.offset("pc") == IbusUser.epochBits + 2 * addrWidth)
                assert(user.fieldWidth("pc") == addrWidth)
                assert(user.offset("pht_count") == IbusUser.epochBits + 3 * addrWidth)
                assert(user.fieldWidth("pht_count") == BpUpdate.phtCountBits)
                assert(user.width == 3 * addrWidth + IbusUser.epochBits + BpUpdate.phtCountBits)
                assert(IbusUser.userWidth(addrWidth) == user.width)

        test("the epoch is a generation counter, not a single bit"):
            // One bit would alias a stream switch that lands an even number of redirects after the fetch was issued.
            assert(IbusUser.epochBits > 1)
