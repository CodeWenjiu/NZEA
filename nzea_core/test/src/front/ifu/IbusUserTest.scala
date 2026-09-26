package nzea_core.front.ifu

import utest.*

/** The user-field layout is what the fetcher and any ibus peer agree on, so it is pinned here rather than left implicit
  * in the pack/unpack expressions.
  */
object IbusUserTest extends TestSuite:

  val tests = Tests:
    test("the user field is the epoch column plus the pc column"):
      for addrWidth <- Seq(32, 64) do
        assert(IbusUser.epochField.offset == 0)
        assert(IbusUser.epochField.width == IbusUser.epochBits)
        assert(IbusUser.epochField.end == IbusUser.pcField(addrWidth).offset)
        assert(IbusUser.pcField(addrWidth).width == addrWidth)
        assert(IbusUser.userWidth(addrWidth) == IbusUser.pcField(addrWidth).end)
        assert(IbusUser.userWidth(addrWidth) == addrWidth + IbusUser.epochBits)

    test("the epoch is a generation counter, not a single bit"):
      // One bit would alias a stream switch that lands an even number of redirects after the fetch was issued.
      assert(IbusUser.epochBits > 1)
