package nzea_rtl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.stdlib.*
import nzea_rtl.bus.*
import nzea_rtl.stdlib.*
import utest.*

/** The crossbar's ports follow the address map: two masters and two slaves flatten to `ins_0..1` / `outs_0..1`, at the
  * fabric's payload width, and the whole thing elaborates through the real flow.
  */
object LiteBusCrossbarTest extends TestSuite:

    private val map = AddressMap(
        masters = Seq(MasterPort("ibus", userWidth = 20, idWidth = 1), MasterPort("dbus", userWidth = 24, idWidth = 1)),
        slaves = Seq(
            SlavePort("ram", AddrRange(BigInt("80000000", 16), BigInt("08000000", 16)), SlaveKind.Memory),
            SlavePort("uart", AddrRange(BigInt("10000000", 16), BigInt("8", 16)), SlaveKind.Mmio)
        )
    )

    private val parameter = LiteBusCrossbarParameter(map, addrWidth = 32, dataWidth = 32, outstanding = 2)
    private val module = LiteBusCrossbar.moduleName(parameter)

    private lazy val sv = TestFlow.lower(
        module,
        cfg =>
            LiteBusCrossbar.main(
                Array(
                    "config",
                    cfg.toString,
                    "--map",
                    upickle.default.write(map),
                    "--addrWidth",
                    "32",
                    "--dataWidth",
                    "32",
                    "--outstanding",
                    "2"
                )
            ),
        cfg => LiteBusCrossbar.main(Array("design", cfg.toString))
    )

    val tests = Tests:
        test("one port pair per side, in map order"):
            assert(sv.contains(s"module $module("))
            for port <- Seq(
                    "ins_0_req_valid",
                    "ins_1_req_bits_addr",
                    "outs_0_resp_valid",
                    "outs_1_req_bits_wdata"
                )
            do assert(sv.contains(port))

        test("the fabric payload is the map's widest master"):
            val ports = TestFlow.ports(sv, module)
            assert(ports("ins_0_req_bits_user") == 24)
            assert(ports("ins_1_req_bits_user") == 24)
            assert(ports("outs_0_req_bits_id") == 1)
