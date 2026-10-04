package nzea_rtl

import nzea_rtl.bus.*
import utest.*

/** The read-only skid buffer: it carries the bus plus its own clock and reset, at the bus's width on both sides. */
object LiteBusROSkidBufferTest extends TestSuite:

    private val parameter = LiteBusROSkidBufferParameter(32, 32, 4, 1)
    private val module = LiteBusROSkidBuffer.moduleName(parameter)

    private lazy val sv = TestFlow.lower(
        module,
        cfg =>
            LiteBusROSkidBuffer.main(
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
                    "1"
                )
            ),
        cfg => LiteBusROSkidBuffer.main(Array("design", cfg.toString))
    )

    val tests = Tests:
        test("the buffer has its own clock and reset beside the two bus ports"):
            val ports = TestFlow.ports(sv, module)
            assert(ports("clock") == 1)
            assert(ports("resetN") == 1)
            assert(ports("in_req_bits_user") == 4)
            assert(ports("out_req_bits_user") == 4)

        test("both directions keep their handshakes"):
            for port <- Seq("in_req_ready", "out_req_valid", "in_resp_valid", "out_resp_ready")
            do assert(sv.contains(port))
