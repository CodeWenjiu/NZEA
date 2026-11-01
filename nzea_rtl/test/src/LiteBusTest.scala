package nzea_rtl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.stdlib.*
import nzea_rtl.bus.*
import nzea_rtl.stdlib.*
import utest.*

case class BusPipeTestParameter(width: Int) extends Parameter:
    require(width >= 1, s"width must be positive, got $width")

given upickle.default.ReadWriter[BusPipeTestParameter] = upickle.default.macroRW

class BusPipeTestLayers(parameter: BusPipeTestParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class BusPipeTestIO(parameter: BusPipeTestParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val flush = Flipped(new FlushBus(parameter.width))
    val ibus = Flipped(new LiteBusRO(parameter.width, parameter.width, 4, 4))
    val in = Flipped(Decoupled(Bits(parameter.width)))
    val out = Aligned(Decoupled(Bits(parameter.width)))

class BusPipeTestProbe(parameter: BusPipeTestParameter)
    extends DVBundle[BusPipeTestParameter, BusPipeTestLayers](parameter)

/** Declares a read-only bus and a pipeline register, so both can be checked through the real flow. */
@generator
object BusPipeTestModule extends Generator[BusPipeTestParameter, BusPipeTestLayers, BusPipeTestIO, BusPipeTestProbe]:
    override def moduleName(p: BusPipeTestParameter): String = s"BusPipeTest_w${p.width}"

    def architecture(parameter: BusPipeTestParameter) =
        val io = summon[Interface[BusPipeTestIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        pipelineConnect(io.in, io.out, io.flush, "pipe", FlushHandover.Consume)

        // Loopback slave: every request field is consumed and every response field is driven, so the flattened port list is
        // checked in both directions and a dropped field cannot slip through unnoticed.
        io.ibus.req.ready := io.ibus.resp.ready
        io.ibus.resp.valid := io.ibus.req.valid
        io.ibus.resp.bits.data := io.ibus.req.bits.addr
        io.ibus.resp.bits.user := io.ibus.req.bits.user
        io.ibus.resp.bits.id := io.ibus.req.bits.id

/** The bus and its fabric, through the real flow: the flavours' flattened ports and the pipe handshake; the read-only →
  * read/write adapter; the narrow → wide `user` adapter; the crossbar's ports following the address map; and the timing
  * wrapper with its own clock, reset and struct contract.
  */
object LiteBusTest extends TestSuite:

    private val pipeModule = BusPipeTestModule.moduleName(BusPipeTestParameter(32))

    private lazy val pipeSv = TestFlow.lower(
        pipeModule,
        cfg => BusPipeTestModule.main(Array("config", cfg.toString, "--width", "32")),
        cfg => BusPipeTestModule.main(Array("design", cfg.toString))
    )

    private val roModule = LiteBusROToRW.moduleName(LiteBusROToRWParameter(32, 32, 4, 1))

    private lazy val roSv = TestFlow.lower(
        roModule,
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

    private val widenParameter =
        LiteBusUserWidenParameter(addrWidth = 32, dataWidth = 32, narrowUserWidth = 2, wideUserWidth = 5, idWidth = 1)

    private val widenModule = LiteBusUserWiden.moduleName(widenParameter)

    private lazy val widenSv = TestFlow.lower(
        widenModule,
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

    private val map = AddressMap(
        masters = Seq(MasterPort("ibus", userWidth = 20, idWidth = 1), MasterPort("dbus", userWidth = 24, idWidth = 1)),
        slaves = Seq(
            SlavePort("ram", AddrRange(BigInt("80000000", 16), BigInt("08000000", 16)), SlaveKind.Memory),
            SlavePort("uart", AddrRange(BigInt("10000000", 16), BigInt("8", 16)), SlaveKind.Mmio)
        )
    )

    private val crossbarParameter = LiteBusCrossbarParameter(map, addrWidth = 32, dataWidth = 32, outstanding = 2)
    private val crossbarModule = LiteBusCrossbar.moduleName(crossbarParameter)

    private lazy val crossbarSv = TestFlow.lower(
        crossbarModule,
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

    private val timing = DeviceTiming(AccessCost.SameDomain(4), jitterEvery = 4, jitterSpan = 4)
    private val timingParameter = LiteBusTimingParameter(32, 32, 4, 1, 25_000_000, timing)
    private val timingModule = LiteBusTiming.moduleName(timingParameter)

    private lazy val timingSv = TestFlow.lower(
        timingModule,
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
                    "--busHz",
                    "25000000",
                    "--cost",
                    "SameDomain:4",
                    "--jitterEvery",
                    "4",
                    "--jitterSpan",
                    "4"
                )
            ),
        cfg => LiteBusTiming.main(Array("design", cfg.toString))
    )

    val tests = Tests:
        test("read-only bus flattens to the expected port names"):
            assert(pipeSv.contains(s"module $pipeModule("))
            for port <- Seq(
                    "ibus_req_valid",
                    "ibus_req_ready",
                    "ibus_req_bits_addr",
                    "ibus_req_bits_user",
                    "ibus_req_bits_id",
                    "ibus_resp_valid",
                    "ibus_resp_ready",
                    "ibus_resp_bits_data",
                    "ibus_resp_bits_user",
                    "ibus_resp_bits_id"
                )
            do assert(pipeSv.contains(port))

        test("read-only bus has no write channel"):
            assert(!pipeSv.contains("ibus_req_bits_wen"))
            assert(!pipeSv.contains("ibus_req_bits_wstrb"))
            assert(!pipeSv.contains("ibus_req_bits_wdata"))

        test("pipe channels are Decoupled, and flush is the register's own port"):
            for port <- Seq(
                    "flush_valid",
                    "flush_next_pc",
                    "in_valid",
                    "in_ready",
                    "in_bits",
                    "out_valid",
                    "out_ready",
                    "out_bits"
                )
            do assert(pipeSv.contains(port))
            assert(!pipeSv.contains("in_flush"))
            assert(pipeSv.contains("always @(posedge clock)"))

        test("the read-only side drives the request and takes the response"):
            assert(roSv.contains(s"module $roModule("))
            for port <- Seq("ro_req_valid", "ro_req_ready", "ro_req_bits_addr", "ro_resp_valid", "ro_resp_ready")
            do assert(roSv.contains(port))

        test("only the read/write side carries the write payload"):
            assert(roSv.contains("rw_req_bits_wdata"))
            assert(!roSv.contains("ro_req_bits_wdata"))
            assert(!roSv.contains("ro_req_bits_wen"))
            assert(!roSv.contains("ro_req_bits_wstrb"))

        test("the two sides carry their own payload widths"):
            val ports = TestFlow.ports(widenSv, widenModule)
            assert(ports("narrow_req_bits_user") == 2)
            assert(ports("wide_req_bits_user") == 5)
            assert(ports("narrow_resp_bits_user") == 2)
            assert(ports("wide_resp_bits_user") == 5)

        test("the read/write payload and the handshakes are untouched"):
            for port <- Seq("narrow_req_bits_wdata", "wide_req_bits_wstrb", "narrow_req_ready", "wide_resp_valid")
            do assert(widenSv.contains(port))

        test("one port pair per side, in map order"):
            assert(crossbarSv.contains(s"module $crossbarModule("))
            for port <- Seq(
                    "ins_0_req_valid",
                    "ins_1_req_bits_addr",
                    "outs_0_resp_valid",
                    "outs_1_req_bits_wdata"
                )
            do assert(crossbarSv.contains(port))

        test("the fabric payload is the map's widest master"):
            val ports = TestFlow.ports(crossbarSv, crossbarModule)
            assert(ports("ins_0_req_bits_user") == 24)
            assert(ports("ins_1_req_bits_user") == 24)
            assert(ports("outs_0_req_bits_id") == 1)

        test("the wrapper carries the bus at its width on both sides, with its own clock and reset"):
            val ports = TestFlow.ports(timingSv, timingModule)
            assert(ports("clock") == 1)
            assert(ports("resetN") == 1)
            assert(ports("in_req_bits_user") == 4)
            assert(ports("out_req_bits_user") == 4)

        test("both directions keep their handshakes"):
            for port <- Seq("in_req_ready", "out_req_valid", "in_resp_valid", "out_resp_ready")
            do assert(timingSv.contains(port))

        test("a timing the model cannot build is rejected, not defaulted"):
            intercept[IllegalArgumentException](DeviceTiming(AccessCost.SameDomain(0), jitterEvery = 4, jitterSpan = 4))
            intercept[IllegalArgumentException](DeviceTiming(AccessCost.SameDomain(4), jitterEvery = 0, jitterSpan = 4))
            intercept[IllegalArgumentException](DeviceTiming(AccessCost.SameDomain(4), jitterEvery = 4, jitterSpan = 0))
            intercept[IllegalArgumentException](
                DeviceTiming(AccessCost.Periods(deviceHz = 0, deviceCycles = 1), jitterEvery = 4, jitterSpan = 4)
            )

        test("a period costs the same duration at any bus clock, a structural path does not"):
            val period =
                DeviceTiming(
                    AccessCost.Periods(deviceHz = 25_000_000, deviceCycles = 4),
                    jitterEvery = 4,
                    jitterSpan = 4
                )
            assert(period.latency(25_000_000) == 4)
            assert(period.latency(100_000_000) == 16)
            assert(BigInt(period.latency(25_000_000)) * 100_000_000 == BigInt(period.latency(100_000_000)) * 25_000_000)
            val stages = DeviceTiming(AccessCost.SameDomain(4), jitterEvery = 4, jitterSpan = 4)
            assert(stages.latency(25_000_000) == 4 && stages.latency(100_000_000) == 4)
            // One device cycle at 1 GHz against a 1 Hz bus is below the model's own two registers: clamp, not reshape.
            assert(DeviceTiming(AccessCost.Periods(deviceHz = 1_000_000_000, deviceCycles = 1), 4, 4).latency(1) == 2)
