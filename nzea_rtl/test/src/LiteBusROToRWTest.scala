package nzea_rtl

import nzea_rtl.bus.*
import utest.*

/** The read-only → read/write adapter: the read-only side carries no write payload, the read/write side does. */
object LiteBusROToRWTest extends TestSuite:

    private val module = LiteBusROToRW.moduleName(LiteBusROToRWParameter(32, 32, 4, 1))

    private lazy val sv = TestFlow.lower(
        module,
        cfg =>
            LiteBusROToRW.main(
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
        cfg => LiteBusROToRW.main(Array("design", cfg.toString))
    )

    val tests = Tests:
        test("the read-only side drives the request and takes the response"):
            assert(sv.contains(s"module $module("))
            for port <- Seq("ro_req_valid", "ro_req_ready", "ro_req_bits_addr", "ro_resp_valid", "ro_resp_ready")
            do assert(sv.contains(port))

        test("only the read/write side carries the write payload"):
            assert(sv.contains("rw_req_bits_wdata"))
            assert(!sv.contains("ro_req_bits_wdata"))
            assert(!sv.contains("ro_req_bits_wen"))
            assert(!sv.contains("ro_req_bits_wstrb"))
