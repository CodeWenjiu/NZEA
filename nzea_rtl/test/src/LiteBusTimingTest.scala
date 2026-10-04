package nzea_rtl

import nzea_rtl.bus.*
import utest.*

/** The timing wrapper: the bus at its width on both sides, its own clock and reset, and the handshakes preserved. The
  * timing struct's contract is checked too — a shape the model cannot build is rejected, not defaulted.
  */
object LiteBusTimingTest extends TestSuite:

    private val timing = DeviceTiming(latency = 4, jitterEvery = 4, jitterSpan = 4)
    private val parameter = LiteBusTimingParameter(32, 32, 4, 1, timing)
    private val module = LiteBusTiming.moduleName(parameter)

    private lazy val sv = TestFlow.lower(
        module,
        cfg =>
            LiteBusTiming.main(
                Array(
                    "config",
                    cfg.toString,
                    "--addrWidth",
                    "32",
                    "--dataWidth",
                    "32",
                    "--userWidth",
                    "4",
                    "--idWidth",
                    "1",
                    "--latency",
                    "4",
                    "--jitterEvery",
                    "4",
                    "--jitterSpan",
                    "4"
                )
            ),
        cfg => LiteBusTiming.main(Array("design", cfg.toString))
    )

    val tests = Tests:
        test("the wrapper carries the bus at its width on both sides, with its own clock and reset"):
            val ports = TestFlow.ports(sv, module)
            assert(ports("clock") == 1)
            assert(ports("resetN") == 1)
            assert(ports("in_req_bits_user") == 4)
            assert(ports("out_req_bits_user") == 4)

        test("both directions keep their handshakes"):
            for port <- Seq("in_req_ready", "out_req_valid", "in_resp_valid", "out_resp_ready")
            do assert(sv.contains(port))

        test("a timing the model cannot build is rejected, not defaulted"):
            intercept[IllegalArgumentException](DeviceTiming(latency = 1, jitterEvery = 4, jitterSpan = 4))
            intercept[IllegalArgumentException](DeviceTiming(latency = 4, jitterEvery = 0, jitterSpan = 4))
            intercept[IllegalArgumentException](DeviceTiming(latency = 4, jitterEvery = 4, jitterSpan = 0))
