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

/** Structural checks: the bus flavour and the pipe handshake elaborate and flatten as the flow expects. */
object LiteBusTest extends TestSuite:

    private val module = BusPipeTestModule.moduleName(BusPipeTestParameter(32))

    private lazy val sv = TestFlow.lower(
        module,
        cfg => BusPipeTestModule.main(Array("config", cfg.toString, "--width", "32")),
        cfg => BusPipeTestModule.main(Array("design", cfg.toString))
    )

    val tests = Tests:
        test("read-only bus flattens to the expected port names"):
            assert(sv.contains(s"module $module("))
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
            do assert(sv.contains(port))

        test("read-only bus has no write channel"):
            assert(!sv.contains("ibus_req_bits_wen"))
            assert(!sv.contains("ibus_req_bits_wstrb"))
            assert(!sv.contains("ibus_req_bits_wdata"))

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
            do assert(sv.contains(port))
            assert(!sv.contains("in_flush"))
            assert(sv.contains("always @(posedge clock)"))
