package nzea_fpga.device.ddr3

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.{DumpRegistry, FileList}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import scala.collection.mutable

/** The DDR3 bus's pads, as modules of the circuit: the I/O cells' primitives (`OSERDESE2`, `ISERDESE2`, `IDELAYE2`,
  * `IDELAYCTRL`, `ODDR`, `IOBUF`, `OBUFTDS`, `OBUF`, `OBUFDS`) are text-bodied modules here, and the top calls them and
  * owns the pins they drive ([[nzea_fpga.TopIO]]). Each one is a `VerilogWrapper`: the dump writes the body beside the
  * RTL and lists it in `filelist.f`, exactly as every other text-body module ([[nzea_fpga.primitives.Bufg]]).
  *
  * What the pads *are*, in one place. A fabric cycle is a whole memory clock (two data beats: the pads are double data
  * rate), and the fabric runs at the memory clock's *bit* rate — 400 MHz for DDR3-800 — which no fabric-to-pad route
  * can meet. So every pin's data is loaded once per fabric cycle into the IOB's own serializer, and the serializer is
  * what meets the pin's edge rate:
  *
  *   - `clk400` and `clk400Dqs` are the wizard's two 400 MHz grids, `clk400Dqs` a quarter period behind `clk400`, and
  *     their difference *is* the device-facing timing: the data leaves on `clk400Dqs` and the strobes on `clk400`, so a
  *     strobe's edges land in its data's eye (`UG586`'s `OCLK`/`OCLK_DELAYED` relationship), and the device's CK — an
  *     `ODDR` with a fixed 1/0 pattern on `clk400Dqs` — has its edges a quarter period into the command's window;
  *   - DQ is an `OSERDESE2` (4:1) on `clk400Dqs`, whose four slots are the cycle's four beats, and the command pins are
  *     `OSERDESE2`s on `clk400`: the command side duplicates each of its two levels into a whole beat (the device reads
  *     commands once per clock, not twice);
  *   - the write strobe is an `OSERDESE2` on `clk400` behind a tristatable differential buffer, because the device
  *     drives the strobe at other times. Both lanes launch there: a per-lane write phase is not something this device
  *     offers — it has no `ODELAYE2` and no PHASER, so the MIG reference generated for it instantiates neither and
  *     levels nothing on the write side — and the two lanes' capture windows, which is where this board's lanes really
  *     differed, are dealt with where they belong: the read taps are per byte lane ([[Ddr3PadNames.tapLanes]]);
  *   - the read capture is an `ISERDESE2` (4:1, memory mode) per DQ lane clocked on `clk400`, behind the `IDELAYE2`
  *     whose tap the design sweeps (`DDR3_DELAY_TAP`): the round trip's phase is a board fact, and the sweep is how the
  *     design measures it.
  *
  * The strobe is write-only: the design sends it and never samples it back. With a deserializer placed at the strobe
  * pair, the pair's serializer-`CLK` and that deserializer's `OCLK` cannot be routed at all — not from a global clock
  * and not from any `BUFIO` site of the region (measured: those two pins are left unroutable in every configuration
  * tried, while every other pin of the same nets routes). So the watcher is not built, and what says whether the write
  * landed is the read-back the controller checks every round.
  *
  * The grids arrive through the wizard's global buffers, and the pads take them as they are. That is deliberate: a
  * serializer loads its word from the fabric's clock and shifts it out on the grid, so the two insertions have to
  * match, and only the wizard's own buffers keep the fabric clocks and the grids in one family — taking the pad side
  * through the I/O column's own buffers instead (a `BUFIO`, as MIG's read path does) skews the load against the shift
  * by about a nanosecond and the fabric's last register stage before each pad misses setup by it (measured: up to 3 ns
  * off).
  *
  * The two 400 MHz grids become the pads' clocks here and the 200 MHz grids clock the fabric side. The taps load on the
  * controller's own 200 MHz grid (the `IDELAYE2.C` below): loading a tap is the fabric's own business and the value
  * comes from the controller's domain, so the load rides the same net the value does — which is how MIG's byte groups
  * clock their input delays.
  *
  * The serializers' `RST` is the controller's own domain reset (`ringReset`): a serializer's first word is then loaded
  * from a known state, on the same reset that clears the registers whose word it is.
  *
  * Every tri-state pad takes its `T` from its own serializer's `TQ`, with the fabric's `OE` entering that serializer as
  * its `T1..T4` word — the four-slot tri-state word MIG's own byte groups use (`TRISTATE_WIDTH(4)` on a DDR `TQ`;
  * measured: `TRISTATE_WIDTH(1)` leaves `TQ` stuck at 1 in the vendor simulator and the placer refuses a `T` fed
  * straight from the fabric, `[Place 30-608]`). The level therefore travels the same serializer the data does.
  *
  * The lanes' taps are one group with one calibrator: every `IDELAYE2` carries the same `IODELAY_GROUP`, and the bus's
  * single `IDELAYCTRL` is what reports when the taps are usable (`Ddr3Side` holds the controller in reset until it is).
  * The board's pins are *not* one bank — the lower byte's data, the CK and the whole command side are bank 35's, and
  * the upper byte's whole group (its data, its DM and its strobe) is bank 34's, as the reference's own pad-function
  * comments say pin by pin ([[Ddr3Xdc]] holds the pin map).
  */
object Ddr3Pads:

    /** The group the lanes' taps are calibrated in: the board routes the whole bus into bank 35 (the reference's pin
      * map), so one `IDELAYCTRL` — the bank's own — serves every lane's tap.
      */
    private val dqBank = 35

    private[ddr3] val dqGroup = s"DDR3_DQ_$dqBank"

    /** The bodies this elaboration instantiated, in first-use order: a dump writes the pads it used and no others. */
    private val bodies = mutable.LinkedHashMap.empty[String, () => String]

    DumpRegistry.onReset(() => bodies.clear())

    /** Record that a pads module is part of this dump. Registering *is* the statement: a level that instantiates no
      * DDR3 pads writes no DDR3 pad module.
      */
    private[ddr3] def register(name: String)(text: => String): Unit =
        bodies.getOrElseUpdate(name, () => text)
        DumpRegistry.onEmit("ddr3Pads")(emitModules)

    /** Write the used pads' bodies into `dir` and add them to its file list. */
    def emitModules(dir: os.Path): Unit =
        if bodies.nonEmpty then
            os.makeDir.all(dir)
            for (name, text) <- bodies do os.write.over(dir / s"$name.sv", text())
            FileList.append(dir, bodies.keysIterator.map(_ + ".sv").toSeq)

// ── DQ: the data lanes ──────────────────────────────────────────────────────────────────────────────────────────────

/** No configuration: the bus's shape is the board's ([[Ddr3PadNames]]). */
case class Ddr3DqPadsParameter() extends Parameter

given upickle.default.ReadWriter[Ddr3DqPadsParameter] = upickle.default.macroRW

class Ddr3DqPadsLayers(parameter: Ddr3DqPadsParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The DQ pads: the bus itself is the analog pin, and the fabric side is the word the serializers take. */
class Ddr3DqPadsIO(parameter: Ddr3DqPadsParameter) extends HWBundle(parameter):
    val DQ = Aligned(Analog(Ddr3PadNames.dqWidth))
    val dqOut = Flipped(UInt(Ddr3PadNames.dqWordWidth))
    val oe = Flipped(Bool())
    val tap = Flipped(UInt(Ddr3PadNames.tapWidth * Ddr3PadNames.tapLanes))
    val clk400 = Flipped(Clock())
    val clk200Inv = Flipped(Clock())
    val clk400Dqs = Flipped(Clock())
    val clk200Dqs = Flipped(Clock())
    val ringReset = Flipped(Bool())
    val dqIn = Aligned(UInt(Ddr3PadNames.dqWordWidth))

class Ddr3DqPadsProbe(parameter: Ddr3DqPadsParameter) extends DVBundle[Ddr3DqPadsParameter, Ddr3DqPadsLayers](parameter)

case class Ddr3DqPadsVerilogParameter() extends VerilogParameter

/** The wrapper the RTL names: one module, whose body is the lanes below. */
@generator
object Ddr3DqPadsModule
    extends VerilogWrapper[
        Ddr3DqPadsParameter,
        Ddr3DqPadsLayers,
        Ddr3DqPadsIO,
        Ddr3DqPadsProbe,
        Ddr3DqPadsVerilogParameter
    ]:

    override def moduleName(parameter: Ddr3DqPadsParameter): String = "Ddr3DqPads"

    override def verilogModuleName(parameter: Ddr3DqPadsParameter): String = "Ddr3DqPads"

    def verilogParameter(parameter: Ddr3DqPadsParameter): Ddr3DqPadsVerilogParameter = Ddr3DqPadsVerilogParameter()

/** The data lanes' module: one lane per DQ pin, and the tap load they share. */
object Ddr3DqPads:

    /** One DQ lane: output serializer, pad, input delay, capture deserializer.
      *
      * The capture is wired the way MIG's own byte group wires it, because the primitive's memory mode is particular:
      * `MEMORY_DDR3` (the plain `MEMORY` type does not bring the output register up), the divided clock on `CLKDIVP` —
      * which is what clocks that register out in this mode, so `CLKDIV` stays low — the inverted pair clock on `CLKB`
      * (by attribute, not by an inverter in the fabric), and both clock enables on. The `OCLK` is not used by a memory
      * capture, but the primitive's rules want it driven — and the placer's own rule wants it driven by the *same* net
      * as the paired serializer's `CLK` (`[PDRC-25]`: the tile's output clock track is shared, so the obvious wiring —
      * the capture grid here — leaves every lane unroutable), which is why it takes the strobe grid.
      *
      * Driving `CLKDIV` too — the primitive's documentation pairs it with `CLKDIVP` — is *not* placeable here: the I/O
      * tile takes two clocks and the lane already hands it the sampling clock and `CLKDIVP`, so a third leaves sixteen
      * pins unroutable (`route_design` reports the capture grid as not completely routed). What the word's framing is
      * aligned to on the board is therefore still open; what is *measured* is that the capture's stream is not a clean
      * one: at good taps seven of a burst's eight transfers are the pattern and the newest one comes back as its
      * predecessor repeated ([[Ddr3.scala]]'s window probes read it), so no eight transfers are ever the pattern —
      * which is what the sweep reports at every tap. A functional model cannot see it.
      */
    private def lane(lane: Int): String =
        val tapSlice =
            if lane < Ddr3PadNames.dqWidth / 2 then s"tap[${Ddr3PadNames.tapWidth - 1}:0]"
            else s"tap[${2 * Ddr3PadNames.tapWidth - 1}:${Ddr3PadNames.tapWidth}]"
        s"""  (* IODELAY_GROUP = "${Ddr3Pads.dqGroup}" *)
           |  IDELAYE2 #(
           |    .DELAY_SRC("IDATAIN"),
           |    .IDELAY_TYPE("VAR_LOAD"),
           |    .IDELAY_VALUE(0),
           |    .HIGH_PERFORMANCE_MODE("TRUE"),
           |    .SIGNAL_PATTERN("DATA")
           |  ) dqDelay$lane (
           |    .IDATAIN(dqPad[$lane]),
           |    .DATAOUT(dqDelayed[$lane]),
           |    .DATAIN(1'b0),
           |    .C(clk200Dqs),
           |    .CE(1'b0),
           |    .INC(1'b0),
           |    .LD(delayLoad),
           |    .LDPIPEEN(1'b0),
           |    .CNTVALUEIN($tapSlice),
           |    .CNTVALUEOUT(),
           |    .REGRST(1'b0),
           |    .CINVCTRL(1'b0)
           |  );
           |  ISERDESE2 #(
           |    .DATA_RATE("DDR"),
           |    .DATA_WIDTH(4),
           |    .INTERFACE_TYPE("MEMORY_DDR3"),
           |    .IOBDELAY("IFD"),
           |    .NUM_CE(2),
           |    .SERDES_MODE("MASTER"),
           |    .DYN_CLKDIV_INV_EN("FALSE"),
           |    .DYN_CLK_INV_EN("FALSE"),
           |    .IS_CLKB_INVERTED("1")
           |  ) dqCapture$lane (
           |    .Q1(dqIn[${4 * lane}]),
           |    .Q2(dqIn[${4 * lane + 1}]),
           |    .Q3(dqIn[${4 * lane + 2}]),
           |    .Q4(dqIn[${4 * lane + 3}]),
           |    .SHIFTIN1(1'b0),
           |    .SHIFTIN2(1'b0),
           |    .D(1'b0),
           |    .DDLY(dqDelayed[$lane]),
           |    .CLK(clk400),
           |    .CLKB(clk400),
           |    .CLKDIV(1'b0),
           |    .CLKDIVP(clk200Inv),
           |    .OCLK(clk400Dqs),
           |    .OCLKB(1'b0),
           |    .BITSLIP(1'b0),
           |    .CE1(1'b1),
           |    .CE2(1'b1),
           |    .RST(1'b0),
           |    .DYNCLKDIVSEL(1'b0),
           |    .DYNCLKSEL(1'b0),
           |    .OFB(1'b0)
           |  );
           |  OSERDESE2 #(
           |    .DATA_RATE_OQ("DDR"),
           |    .DATA_RATE_TQ("DDR"),
           |    .DATA_WIDTH(4),
           |    .TRISTATE_WIDTH(4),
           |    .SERDES_MODE("MASTER")
           |  ) dqDrive$lane (
           |    .OQ(dqDrivePad[$lane]),
           |    .TQ(dqTristate[$lane]),
           |    .CLK(clk400Dqs),
           |    .CLKDIV(clk200Dqs),
           |    .D1(dqOut[${4 * lane}]),
           |    .D2(dqOut[${4 * lane + 1}]),
           |    .D3(dqOut[${4 * lane + 2}]),
           |    .D4(dqOut[${4 * lane + 3}]),
           |    .D5(1'b0),
           |    .D6(1'b0),
           |    .D7(1'b0),
           |    .D8(1'b0),
           |    .OCE(1'b1),
           |    .RST(ringReset),
           |    .SHIFTIN1(1'b0),
           |    .SHIFTIN2(1'b0),
           |    .T1(~oe),
           |    .T2(~oe),
           |    .T3(~oe),
           |    .T4(~oe),
           |    .TBYTEIN(1'b0),
           |    .TCE(1'b1)
           |  );
           |  IOBUF #(
           |    .DRIVE(8),
           |    .IBUF_LOW_PWR("FALSE"),
           |    .SLEW("FAST")
           |  ) dqBuf$lane (
           |    .IO(DQ[$lane]),
           |    .I(dqDrivePad[$lane]),
           |    .O(dqPad[$lane]),
           |    .T(dqTristate[$lane])
           |  );""".stripMargin

    /** The body: the lanes, and the tap load they share.
      *
      * The tap load is the sweep's own hand on the delays: the sweep moves the tap a step at a time, and each step is
      * loaded into every lane. It runs on the controller's own 200 MHz grid — the clock the tap value itself belongs to
      * — and that same clock is what the lanes' `IDELAYE2`s load on, so a step is in place before the controller's next
      * round looks at it.
      */
    def verilog(parameter: Ddr3DqPadsParameter): String =
        val tapWidth = Ddr3PadNames.tapWidth * Ddr3PadNames.tapLanes
        s"""module ${Ddr3DqPadsModule.moduleName(parameter)}(
           |  inout [${Ddr3PadNames.dqWidth - 1}:0] DQ,
           |  input [${Ddr3PadNames.dqWordWidth - 1}:0] dqOut,
           |  input oe,
           |  input [${tapWidth - 1}:0] tap,
           |  input clk400,
           |  input clk200Inv,
           |  input clk400Dqs,
           |  input clk200Dqs,
           |  input ringReset,
           |  output [${Ddr3PadNames.dqWordWidth - 1}:0] dqIn
           |);
           |
           |  wire [${Ddr3PadNames.dqWidth - 1}:0] dqPad;
           |  wire [${Ddr3PadNames.dqWidth - 1}:0] dqDelayed;
           |  wire [${Ddr3PadNames.dqWidth - 1}:0] dqDrivePad;
           |  wire [${Ddr3PadNames.dqWidth - 1}:0] dqTristate;
           |
           |  reg [${tapWidth - 1}:0] delayTapReg;
           |  reg delayLoadReg;
           |  always @(posedge clk200Dqs) begin
           |    delayTapReg <= tap;
           |    delayLoadReg <= (delayTapReg != tap);
           |  end
           |  wire delayLoad = delayLoadReg;
           |
           |${(0 until Ddr3PadNames.dqWidth).map(lane).mkString("\n\n")}
           |
           |endmodule
           |""".stripMargin

    /** Instantiate the lanes, recording that the dump has to write their body. */
    def instantiate(parameter: Ddr3DqPadsParameter)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Instance[Ddr3DqPadsIO, Ddr3DqPadsProbe] =
        Ddr3Pads.register(Ddr3DqPadsModule.moduleName(parameter))(verilog(parameter))
        Ddr3DqPadsModule.instantiate(parameter)

// ── DQS: the strobe lanes ───────────────────────────────────────────────────────────────────────────────────────────

/** No configuration: the bus's shape is the board's ([[Ddr3PadNames]]). */
case class Ddr3DqsPadsParameter() extends Parameter

given upickle.default.ReadWriter[Ddr3DqsPadsParameter] = upickle.default.macroRW

class Ddr3DqsPadsLayers(parameter: Ddr3DqsPadsParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The strobe pads: the differential pair is the analog pin, and only the write side is wired. */
class Ddr3DqsPadsIO(parameter: Ddr3DqsPadsParameter) extends HWBundle(parameter):
    val DQS_P = Aligned(Analog(Ddr3PadNames.dqsWidth))
    val DQS_N = Aligned(Analog(Ddr3PadNames.dqsWidth))
    val dqsOut = Flipped(UInt(Ddr3PadNames.dqsWordWidth))
    val oe = Flipped(Bool())
    val clk400 = Flipped(Clock())
    val clk200 = Flipped(Clock())
    val ringReset = Flipped(Bool())

class Ddr3DqsPadsProbe(parameter: Ddr3DqsPadsParameter)
    extends DVBundle[Ddr3DqsPadsParameter, Ddr3DqsPadsLayers](parameter)

case class Ddr3DqsPadsVerilogParameter() extends VerilogParameter

@generator
object Ddr3DqsPadsModule
    extends VerilogWrapper[
        Ddr3DqsPadsParameter,
        Ddr3DqsPadsLayers,
        Ddr3DqsPadsIO,
        Ddr3DqsPadsProbe,
        Ddr3DqsPadsVerilogParameter
    ]:

    override def moduleName(parameter: Ddr3DqsPadsParameter): String = "Ddr3DqsPads"

    override def verilogModuleName(parameter: Ddr3DqsPadsParameter): String = "Ddr3DqsPads"

    def verilogParameter(parameter: Ddr3DqsPadsParameter): Ddr3DqsPadsVerilogParameter = Ddr3DqsPadsVerilogParameter()

/** The strobe lanes' module: one lane per strobe pin, each a serializer behind a tristatable differential buffer. */
object Ddr3DqsPads:

    /** One strobe lane. Both lanes launch from the command grid, and the data from the strobe grid a quarter period
      * later — see the file header for what that relationship is and why it is not per lane.
      */
    private def lane(lane: Int): String =
        s"""  OSERDESE2 #(
           |    .DATA_RATE_OQ("DDR"),
           |    .DATA_RATE_TQ("DDR"),
           |    .DATA_WIDTH(4),
           |    .TRISTATE_WIDTH(4),
           |    .SERDES_MODE("MASTER")
           |  ) dqsDrive$lane (
           |    .OQ(dqsDrivePad[$lane]),
           |    .TQ(dqsTristate[$lane]),
           |    .CLK(clk400),
           |    .CLKDIV(clk200),
           |    .D1(dqsOut[${4 * lane}]),
           |    .D2(dqsOut[${4 * lane + 1}]),
           |    .D3(dqsOut[${4 * lane + 2}]),
           |    .D4(dqsOut[${4 * lane + 3}]),
           |    .D5(1'b0),
           |    .D6(1'b0),
           |    .D7(1'b0),
           |    .D8(1'b0),
           |    .OCE(1'b1),
           |    .RST(ringReset),
           |    .SHIFTIN1(1'b0),
           |    .SHIFTIN2(1'b0),
           |    .T1(~oe),
           |    .T2(~oe),
           |    .T3(~oe),
           |    .T4(~oe),
           |    .TBYTEIN(1'b0),
           |    .TCE(1'b1)
           |  );
           |  OBUFTDS #(
           |    .SLEW("FAST")
           |  ) dqsBuf$lane (
           |    .I(dqsDrivePad[$lane]),
           |    .O(DQS_P[$lane]),
           |    .OB(DQS_N[$lane]),
           |    .T(dqsTristate[$lane])
           |  );""".stripMargin

    /** The body: the two lanes. */
    def verilog(parameter: Ddr3DqsPadsParameter): String =
        s"""module ${Ddr3DqsPadsModule.moduleName(parameter)}(
           |  inout [${Ddr3PadNames.dqsWidth - 1}:0] DQS_P,
           |  inout [${Ddr3PadNames.dqsWidth - 1}:0] DQS_N,
           |  input [${Ddr3PadNames.dqsWordWidth - 1}:0] dqsOut,
           |  input oe,
           |  input clk400,
           |  input clk200,
           |  input ringReset
           |);
           |
           |  wire [${Ddr3PadNames.dqsWidth - 1}:0] dqsDrivePad;
           |  wire [${Ddr3PadNames.dqsWidth - 1}:0] dqsTristate;
           |
           |${(0 until Ddr3PadNames.dqsWidth).map(lane).mkString("\n\n")}
           |
           |endmodule
           |""".stripMargin

    /** Instantiate the strobes, recording that the dump has to write their body. */
    def instantiate(parameter: Ddr3DqsPadsParameter)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Instance[Ddr3DqsPadsIO, Ddr3DqsPadsProbe] =
        Ddr3Pads.register(Ddr3DqsPadsModule.moduleName(parameter))(verilog(parameter))
        Ddr3DqsPadsModule.instantiate(parameter)

// ── DM: the data mask pins ─────────────────────────────────────────────────────────────────────────────────────────

/** No configuration: the bus's shape is the board's ([[Ddr3PadNames]]). */
case class Ddr3DmPadsParameter() extends Parameter

given upickle.default.ReadWriter[Ddr3DmPadsParameter] = upickle.default.macroRW

class Ddr3DmLayers(parameter: Ddr3DmPadsParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The mask pins: the pins it drives, and the four levels per pin the controller presents — the *data's* own rate, one
  * level per transfer. A byte strobe is a per-transfer fact (a bus byte `i` is transfer `i / 2`'s lane `i % 2`), so a
  * pair of levels per cycle — the shape the command side has — could not express it: two transfers sharing a level are
  * four bytes apart, and a master masks bytes, not pairs.
  */
class Ddr3DmPadsIO(parameter: Ddr3DmPadsParameter) extends HWBundle(parameter):
    val pad = Aligned(UInt(Ddr3PadNames.dmWidth))
    val levels = Flipped(UInt(4 * Ddr3PadNames.dmWidth))
    val clk400Dqs = Flipped(Clock())
    val clk200Dqs = Flipped(Clock())
    val ringReset = Flipped(Bool())

class Ddr3DmPadsProbe(parameter: Ddr3DmPadsParameter) extends DVBundle[Ddr3DmPadsParameter, Ddr3DmLayers](parameter)

case class Ddr3DmPadsVerilogParameter() extends VerilogParameter

@generator
object Ddr3DmPadsModule
    extends VerilogWrapper[
        Ddr3DmPadsParameter,
        Ddr3DmLayers,
        Ddr3DmPadsIO,
        Ddr3DmPadsProbe,
        Ddr3DmPadsVerilogParameter
    ]:

    override def moduleName(parameter: Ddr3DmPadsParameter): String = "Ddr3DmPads"

    override def verilogModuleName(parameter: Ddr3DmPadsParameter): String = "Ddr3DmPads"

    def verilogParameter(parameter: Ddr3DmPadsParameter): Ddr3DmPadsVerilogParameter = Ddr3DmPadsVerilogParameter()

/** The mask pins' module: one serializer and one output buffer per pin, on the data's own grids — the mask has to keep
  * step with the data it masks, so it launches where the data does ([[Ddr3DqPads]] holds that wiring's reasons).
  */
object Ddr3DmPads:

    /** One pin: the serializer with its four levels, and the buffer. The mask is always driven: the serializer's
      * tristate side is disabled (`TCE` low), exactly as the single-rate pins disable theirs.
      */
    private def pin(bit: Int): String =
        s"""  OSERDESE2 #(
           |    .DATA_RATE_OQ("DDR"),
           |    .DATA_RATE_TQ("DDR"),
           |    .DATA_WIDTH(4),
           |    .TRISTATE_WIDTH(1),
           |    .SERDES_MODE("MASTER")
           |  ) dmDrive$bit (
           |    .OQ(dmDrivePad[$bit]),
           |    .CLK(clk400Dqs),
           |    .CLKDIV(clk200Dqs),
           |    .D1(levels[${4 * bit}]),
           |    .D2(levels[${4 * bit + 1}]),
           |    .D3(levels[${4 * bit + 2}]),
           |    .D4(levels[${4 * bit + 3}]),
           |    .D5(1'b0),
           |    .D6(1'b0),
           |    .D7(1'b0),
           |    .D8(1'b0),
           |    .OCE(1'b1),
           |    .RST(ringReset),
           |    .SHIFTIN1(1'b0),
           |    .SHIFTIN2(1'b0),
           |    .T1(1'b0),
           |    .T2(1'b0),
           |    .T3(1'b0),
           |    .T4(1'b0),
           |    .TBYTEIN(1'b0),
           |    .TCE(1'b0)
           |  );
           |  OBUF #(
           |    .SLEW("FAST")
           |  ) dmBuf$bit (
           |    .I(dmDrivePad[$bit]),
           |    .O(pad[$bit])
           |  );""".stripMargin

    /** The body: the pins. */
    def verilog(parameter: Ddr3DmPadsParameter): String =
        s"""module ${Ddr3DmPadsModule.moduleName(parameter)}(
           |  output [${Ddr3PadNames.dmWidth - 1}:0] pad,
           |  input [${4 * Ddr3PadNames.dmWidth - 1}:0] levels,
           |  input clk400Dqs,
           |  input clk200Dqs,
           |  input ringReset
           |);
           |
           |  wire [${Ddr3PadNames.dmWidth - 1}:0] dmDrivePad;
           |
           |${(0 until Ddr3PadNames.dmWidth).map(pin).mkString("\n\n")}
           |
           |endmodule
           |""".stripMargin

    /** Instantiate the mask pins, recording that the dump has to write their body. */
    def instantiate(parameter: Ddr3DmPadsParameter)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Instance[Ddr3DmPadsIO, Ddr3DmPadsProbe] =
        Ddr3Pads.register(Ddr3DmPadsModule.moduleName(parameter))(verilog(parameter))
        Ddr3DmPadsModule.instantiate(parameter)

// ── The command side: single-rate pins ──────────────────────────────────────────────────────────────────────────────

/** The group's width: one module per pin count, so the address, bank, DM and control pins are the same module at four
  * widths. A generator dumps one module per parameter, which is what makes them four.
  */
case class Ddr3SdrPadsParameter(width: Int) extends Parameter

given upickle.default.ReadWriter[Ddr3SdrPadsParameter] = upickle.default.macroRW

class Ddr3SdrPadsLayers(parameter: Ddr3SdrPadsParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The single-rate pins: the pins it drives, and the two levels per pin the controller presents. */
class Ddr3SdrPadsIO(parameter: Ddr3SdrPadsParameter) extends HWBundle(parameter):
    val pad = Aligned(UInt(parameter.width))
    val levels = Flipped(UInt(2 * parameter.width))
    val clk400 = Flipped(Clock())
    val clk200 = Flipped(Clock())
    val ringReset = Flipped(Bool())

class Ddr3SdrPadsProbe(parameter: Ddr3SdrPadsParameter)
    extends DVBundle[Ddr3SdrPadsParameter, Ddr3SdrPadsLayers](parameter)

case class Ddr3SdrPadsVerilogParameter() extends VerilogParameter

@generator
object Ddr3SdrPadsModule
    extends VerilogWrapper[
        Ddr3SdrPadsParameter,
        Ddr3SdrPadsLayers,
        Ddr3SdrPadsIO,
        Ddr3SdrPadsProbe,
        Ddr3SdrPadsVerilogParameter
    ]:

    override def moduleName(parameter: Ddr3SdrPadsParameter): String = s"Ddr3SdrPads${parameter.width}"

    override def verilogModuleName(parameter: Ddr3SdrPadsParameter): String = moduleName(parameter)

    def verilogParameter(parameter: Ddr3SdrPadsParameter): Ddr3SdrPadsVerilogParameter = Ddr3SdrPadsVerilogParameter()

/** The command/address pins' module: one serializer and one output buffer per pin.
  *
  * The design presents two levels per pin per cycle (a pair), and the serializer duplicates each into a whole beat —
  * the device reads one command per clock, not two.
  */
object Ddr3SdrPads:

    /** One pin: the serializer, and the buffer that reaches the pin. */
    private def pin(bit: Int): String =
        s"""  OSERDESE2 #(
           |    .DATA_RATE_OQ("DDR"),
           |    .DATA_RATE_TQ("DDR"),
           |    .DATA_WIDTH(4),
           |    .TRISTATE_WIDTH(1),
           |    .SERDES_MODE("MASTER")
           |  ) padDrive$bit (
           |    .OQ(padOq_$bit),
           |    .CLK(clk400),
           |    .CLKDIV(clk200),
           |    .D1(levels[${2 * bit}]),
           |    .D2(levels[${2 * bit}]),
           |    .D3(levels[${2 * bit + 1}]),
           |    .D4(levels[${2 * bit + 1}]),
           |    .D5(1'b0),
           |    .D6(1'b0),
           |    .D7(1'b0),
           |    .D8(1'b0),
           |    .OCE(1'b1),
           |    .RST(ringReset),
           |    .SHIFTIN1(1'b0),
           |    .SHIFTIN2(1'b0),
           |    .T1(1'b0),
           |    .T2(1'b0),
           |    .T3(1'b0),
           |    .T4(1'b0),
           |    .TBYTEIN(1'b0),
           |    .TCE(1'b0)
           |  );
           |  OBUF #(
           |    .SLEW("FAST")
           |  ) padBuf$bit (
           |    .I(padOq_$bit),
           |    .O(pad[$bit])
           |  );""".stripMargin

    /** The body: the pins. */
    def verilog(parameter: Ddr3SdrPadsParameter): String =
        s"""module ${Ddr3SdrPadsModule.moduleName(parameter)}(
           |  output [${parameter.width - 1}:0] pad,
           |  input [${2 * parameter.width - 1}:0] levels,
           |  input clk400,
           |  input clk200,
           |  input ringReset
           |);
           |
           |${(0 until parameter.width).map(bit => s"  wire padOq_$bit;").mkString("\n")}
           |
           |${(0 until parameter.width).map(pin).mkString("\n\n")}
           |
           |endmodule
           |""".stripMargin

    /** Instantiate a pin group, recording that the dump has to write its body. */
    def instantiate(parameter: Ddr3SdrPadsParameter)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Instance[Ddr3SdrPadsIO, Ddr3SdrPadsProbe] =
        Ddr3Pads.register(Ddr3SdrPadsModule.moduleName(parameter))(verilog(parameter))
        Ddr3SdrPadsModule.instantiate(parameter)

// ── The housekeeping pins ───────────────────────────────────────────────────────────────────────────────────────────

/** No configuration: a level pin is one pin. */
case class Ddr3LevelPadsParameter() extends Parameter

given upickle.default.ReadWriter[Ddr3LevelPadsParameter] = upickle.default.macroRW

class Ddr3LevelPadsLayers(parameter: Ddr3LevelPadsParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** A pin whose value changes once per command at most (CKE, ODT, RESET_N): one level in, one pin out. */
class Ddr3LevelPadsIO(parameter: Ddr3LevelPadsParameter) extends HWBundle(parameter):
    val pad = Aligned(Bool())
    val level = Flipped(Bool())
    val clk400 = Flipped(Clock())

class Ddr3LevelPadsProbe(parameter: Ddr3LevelPadsParameter)
    extends DVBundle[Ddr3LevelPadsParameter, Ddr3LevelPadsLayers](parameter)

case class Ddr3LevelPadsVerilogParameter() extends VerilogParameter

@generator
object Ddr3LevelPadsModule
    extends VerilogWrapper[
        Ddr3LevelPadsParameter,
        Ddr3LevelPadsLayers,
        Ddr3LevelPadsIO,
        Ddr3LevelPadsProbe,
        Ddr3LevelPadsVerilogParameter
    ]:

    override def moduleName(parameter: Ddr3LevelPadsParameter): String = "Ddr3LevelPads"

    override def verilogModuleName(parameter: Ddr3LevelPadsParameter): String = "Ddr3LevelPads"

    def verilogParameter(parameter: Ddr3LevelPadsParameter): Ddr3LevelPadsVerilogParameter =
        Ddr3LevelPadsVerilogParameter()

/** The housekeeping pins' module: a plain output register on the data grid, because the input is a slow level and never
  * a per-beat word.
  */
object Ddr3LevelPads:

    /** The body: one registered level, out through a buffer. */
    def verilog(parameter: Ddr3LevelPadsParameter): String =
        s"""module ${Ddr3LevelPadsModule.moduleName(parameter)}(
           |  output pad,
           |  input level,
           |  input clk400
           |);
           |
           |  wire padDrive;
           |  ODDR #(
           |    .DDR_CLK_EDGE("SAME_EDGE"),
           |    .INIT(1'b0),
           |    .SRTYPE("SYNC")
           |  ) levelDrive (
           |    .Q(padDrive),
           |    .C(clk400),
           |    .CE(1'b1),
           |    .D1(level),
           |    .D2(level),
           |    .R(1'b0),
           |    .S(1'b0)
           |  );
           |  OBUF #(
           |    .SLEW("FAST")
           |  ) levelBuf (
           |    .I(padDrive),
           |    .O(pad)
           |  );
           |endmodule
           |""".stripMargin

    /** Instantiate a level pin, recording that the dump has to write its body. */
    def instantiate(parameter: Ddr3LevelPadsParameter)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Instance[Ddr3LevelPadsIO, Ddr3LevelPadsProbe] =
        Ddr3Pads.register(Ddr3LevelPadsModule.moduleName(parameter))(verilog(parameter))
        Ddr3LevelPadsModule.instantiate(parameter)

// ── The device's clock ──────────────────────────────────────────────────────────────────────────────────────────────

/** No configuration: the clock's pattern is the interface's. */
case class Ddr3CkPadsParameter() extends Parameter

given upickle.default.ReadWriter[Ddr3CkPadsParameter] = upickle.default.macroRW

class Ddr3CkPadsLayers(parameter: Ddr3CkPadsParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The device's clock: a differential pair, launched from the strobe grid. */
class Ddr3CkPadsIO(parameter: Ddr3CkPadsParameter) extends HWBundle(parameter):
    val CK_P = Aligned(Bool())
    val CK_N = Aligned(Bool())
    val clk400Dqs = Flipped(Clock())

class Ddr3CkPadsProbe(parameter: Ddr3CkPadsParameter) extends DVBundle[Ddr3CkPadsParameter, Ddr3CkPadsLayers](parameter)

case class Ddr3CkPadsVerilogParameter() extends VerilogParameter

@generator
object Ddr3CkPadsModule
    extends VerilogWrapper[
        Ddr3CkPadsParameter,
        Ddr3CkPadsLayers,
        Ddr3CkPadsIO,
        Ddr3CkPadsProbe,
        Ddr3CkPadsVerilogParameter
    ]:

    override def moduleName(parameter: Ddr3CkPadsParameter): String = "Ddr3CkPads"

    override def verilogModuleName(parameter: Ddr3CkPadsParameter): String = "Ddr3CkPads"

    def verilogParameter(parameter: Ddr3CkPadsParameter): Ddr3CkPadsVerilogParameter = Ddr3CkPadsVerilogParameter()

/** The device's clock's module: the output register, and the differential buffer. */
object Ddr3CkPads:

    /** The body: half the fabric's rate, from a fixed 1/0 pattern. */
    def verilog(parameter: Ddr3CkPadsParameter): String =
        s"""module ${Ddr3CkPadsModule.moduleName(parameter)}(
           |  output CK_P,
           |  output CK_N,
           |  input clk400Dqs
           |);
           |
           |  wire ckPad;
           |  ODDR #(
           |    .DDR_CLK_EDGE("SAME_EDGE"),
           |    .INIT(1'b0),
           |    .SRTYPE("SYNC")
           |  ) ckDrive (
           |    .Q(ckPad),
           |    .C(clk400Dqs),
           |    .CE(1'b1),
           |    .D1(1'b1),
           |    .D2(1'b0),
           |    .R(1'b0),
           |    .S(1'b0)
           |  );
           |  OBUFDS #(
           |    .SLEW("FAST")
           |  ) ckBuf (
           |    .I(ckPad),
           |    .O(CK_P),
           |    .OB(CK_N)
           |  );
           |endmodule
           |""".stripMargin

    /** Instantiate the clock, recording that the dump has to write its body. */
    def instantiate(parameter: Ddr3CkPadsParameter)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Instance[Ddr3CkPadsIO, Ddr3CkPadsProbe] =
        Ddr3Pads.register(Ddr3CkPadsModule.moduleName(parameter))(verilog(parameter))
        Ddr3CkPadsModule.instantiate(parameter)

// ── The input delays' calibration ───────────────────────────────────────────────────────────────────────────────────

/** No configuration: the calibration group is the board's. */
case class Ddr3IdelayCtrlParameter() extends Parameter

given upickle.default.ReadWriter[Ddr3IdelayCtrlParameter] = upickle.default.macroRW

class Ddr3IdelayCtrlLayers(parameter: Ddr3IdelayCtrlParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The bank's tap calibration: its reference clock, its reset, and the ready it reports. */
class Ddr3IdelayCtrlIO(parameter: Ddr3IdelayCtrlParameter) extends HWBundle(parameter):
    val READY = Aligned(Bool())
    val refClk = Flipped(Clock())
    val reset = Flipped(Bool())

class Ddr3IdelayCtrlProbe(parameter: Ddr3IdelayCtrlParameter)
    extends DVBundle[Ddr3IdelayCtrlParameter, Ddr3IdelayCtrlLayers](parameter)

case class Ddr3IdelayCtrlVerilogParameter() extends VerilogParameter

@generator
object Ddr3IdelayCtrlModule
    extends VerilogWrapper[
        Ddr3IdelayCtrlParameter,
        Ddr3IdelayCtrlLayers,
        Ddr3IdelayCtrlIO,
        Ddr3IdelayCtrlProbe,
        Ddr3IdelayCtrlVerilogParameter
    ]:

    override def moduleName(parameter: Ddr3IdelayCtrlParameter): String = "Ddr3IdelayCtrl"

    override def verilogModuleName(parameter: Ddr3IdelayCtrlParameter): String = "Ddr3IdelayCtrl"

    def verilogParameter(parameter: Ddr3IdelayCtrlParameter): Ddr3IdelayCtrlVerilogParameter =
        Ddr3IdelayCtrlVerilogParameter()

/** The tap calibration's module: the bank's own calibrator, which every lane's `IDELAYE2` delays against. */
object Ddr3IdelayCtrl:

    /** The body: the primitive, under the group the lanes' delays state. */
    def verilog(parameter: Ddr3IdelayCtrlParameter): String =
        s"""module ${Ddr3IdelayCtrlModule.moduleName(parameter)}(
           |  output READY,
           |  input refClk,
           |  input reset
           |);
           |
           |  (* IODELAY_GROUP = "${Ddr3Pads.dqGroup}" *)
           |  IDELAYCTRL ctrl (
           |    .RDY(READY),
           |    .REFCLK(refClk),
           |    .RST(reset)
           |  );
           |endmodule
           |""".stripMargin

    /** Instantiate the calibrator, recording that the dump has to write its body. */
    def instantiate(parameter: Ddr3IdelayCtrlParameter)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Instance[Ddr3IdelayCtrlIO, Ddr3IdelayCtrlProbe] =
        Ddr3Pads.register(Ddr3IdelayCtrlModule.moduleName(parameter))(verilog(parameter))
        Ddr3IdelayCtrlModule.instantiate(parameter)
