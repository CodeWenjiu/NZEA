package nzea_rtl

import nzea_rtl.bus.*
import utest.*

/** The narrow → wide `user` adapter: each side keeps its own payload width, and the read/write payload is untouched. */
object LiteBusUserWidenTest extends TestSuite:

    private val parameter =
        LiteBusUserWidenParameter(addrWidth = 32, dataWidth = 32, narrowUserWidth = 2, wideUserWidth = 5, idWidth = 1)

    private val module = LiteBusUserWiden.moduleName(parameter)

    private lazy val sv = TestFlow.lower(
        module,
        cfg =>
            LiteBusUserWiden.main(
                Array(
                    "config",
                    cfg.toString,
                    "--addrWidth",
                    "32",
                    "--dataWidth",
                    "32",
                    "--narrowUserWidth",
                    "2",
                    "--wideUserWidth",
                    "5",
                    "--idWidth",
                    "1"
                )
            ),
        cfg => LiteBusUserWiden.main(Array("design", cfg.toString))
    )

    val tests = Tests:
        test("the two sides carry their own payload widths"):
            val ports = TestFlow.ports(sv, module)
            assert(ports("narrow_req_bits_user") == 2)
            assert(ports("wide_req_bits_user") == 5)
            assert(ports("narrow_resp_bits_user") == 2)
            assert(ports("wide_resp_bits_user") == 5)

        test("the read/write payload and the handshakes are untouched"):
            for port <- Seq("narrow_req_bits_wdata", "wide_req_bits_wstrb", "narrow_req_ready", "wide_resp_valid")
            do assert(sv.contains(port))
