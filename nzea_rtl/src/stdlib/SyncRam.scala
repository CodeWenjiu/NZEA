package nzea_rtl.stdlib

import java.lang.foreign.Arena

import mainargs.TokensReader
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.FileList
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import scala.collection.mutable

/** The shape of a synchronous RAM's ports, in the spelling memory macros are catalogued with: `1RW` is one read-write
  * port, `1R1W` is one read port and one write port, `1R1RW` a read port plus a read-write port, `1W1RW` a write port
  * plus a read-write port, `2RW` two read-write ports.
  *
  * Only [[SyncRamPorts.OneR1W]] is implemented so far — the shape the tables need — and a parameter of any other shape
  * fails its construction. The spelling is one token across the config file, the generator's flag and the dumped
  * module's name.
  */
enum SyncRamPorts(val name: String):
    case OneRW extends SyncRamPorts("1rw")
    case OneR1W extends SyncRamPorts("1r1w")
    case OneR1RW extends SyncRamPorts("1r1rw")
    case OneW1RW extends SyncRamPorts("1w1rw")
    case TwoRW extends SyncRamPorts("2rw")

object SyncRamPorts:

    given upickle.default.ReadWriter[SyncRamPorts] =
        upickle.default
            .readwriter[String]
            .bimap(
                _.name,
                name => parseOrThrow(name)
            )

    def parse(token: String): Either[String, SyncRamPorts] =
        token.trim match
            case "1rw"   => Right(OneRW)
            case "1r1w"  => Right(OneR1W)
            case "1r1rw" => Right(OneR1RW)
            case "1w1rw" => Right(OneW1RW)
            case "2rw"   => Right(TwoRW)
            case _       => Left(s"unknown ports '$token'; known: 1rw, 1r1w, 1r1rw, 1w1rw, 2rw")

    def parseOrThrow(token: String): SyncRamPorts =
        parse(token).fold(error => throw new IllegalArgumentException(error), identity)

    given TokensReader.Simple[SyncRamPorts] with
        def shortName = "1rw|1r1w|1r1rw|1w1rw|2rw"

        def read(strs: Seq[String]): Either[String, SyncRamPorts] =
            strs.lastOption.map(parse).getOrElse(Left("ports requires a value"))

/** What a read port returns when a write port hits the same entry on the same edge. */
enum SyncRamReadUnderWrite(val name: String):
    /** The entry's old value: the read samples before the write lands. */
    case ReadFirst extends SyncRamReadUnderWrite("ReadFirst")

    /** The write's incoming data: the read bypasses. */
    case WriteFirst extends SyncRamReadUnderWrite("WriteFirst")

object SyncRamReadUnderWrite:

    given upickle.default.ReadWriter[SyncRamReadUnderWrite] =
        upickle.default
            .readwriter[String]
            .bimap(
                _.name,
                name => parseOrThrow(name)
            )

    def parse(token: String): Either[String, SyncRamReadUnderWrite] =
        token.trim match
            case "ReadFirst"  => Right(ReadFirst)
            case "WriteFirst" => Right(WriteFirst)
            case _            => Left(s"unknown readUnderWrite '$token'; known: ReadFirst, WriteFirst")

    def parseOrThrow(token: String): SyncRamReadUnderWrite =
        parse(token).fold(error => throw new IllegalArgumentException(error), identity)

    given TokensReader.Simple[SyncRamReadUnderWrite] with
        def shortName = "ReadFirst|WriteFirst"

        def read(strs: Seq[String]): Either[String, SyncRamReadUnderWrite] =
            strs.lastOption.map(parse).getOrElse(Left("readUnderWrite requires a value"))

case class SyncRamParameter(width: Int, depth: Int, ports: SyncRamPorts, readUnderWrite: SyncRamReadUnderWrite)
    extends Parameter:
    require(width >= 1, s"SyncRam width must be positive, got $width")
    require(depth >= 2, s"SyncRam depth must be at least 2, got $depth")
    require(ports == SyncRamPorts.OneR1W, s"only SyncRamPorts.OneR1W is implemented so far, got ${ports.name}")

    /** Number of bits of every port's address. */
    def addressWidth: Int = BigInt(depth - 1).bitLength

given upickle.default.ReadWriter[SyncRamParameter] = upickle.default.macroRW

class SyncRamLayers(parameter: SyncRamParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The emitted module's ports: a clock, and the shape's ports.
  *
  * The field names are the SystemVerilog port names — the body [[SyncRam.verilog]] writes, and the instantiation
  * firtool emits, connect by them — so they are part of the contract, not an implementation detail.
  */
class SyncRamIO(parameter: SyncRamParameter) extends HWRecord(parameter):
    Flipped("clock", Clock())
    Flipped("r_en", Bool())
    Flipped("r_addr", UInt(parameter.addressWidth))
    Aligned("r_data", UInt(parameter.width))
    Flipped("w_en", Bool())
    Flipped("w_addr", UInt(parameter.addressWidth))
    Flipped("w_data", UInt(parameter.width))

/** Typed access to the shape's fields; a `Record`'s fields are reached by name, and these are the names. */
extension (io: Interface[SyncRamIO])
    /** The one clock every port samples on. */
    def clock: Ref[Clock] = io.field[Clock]("clock")

    /** Read port: enable, address, and the data the address held a cycle ago. */
    def rEn: Ref[Bool] = io.field[Bool]("r_en")
    def rAddr: Ref[UInt] = io.field[UInt]("r_addr")
    def rData: Ref[UInt] = io.field[UInt]("r_data")

    /** Write port: enable, address, and the data to store. */
    def wEn: Ref[Bool] = io.field[Bool]("w_en")
    def wAddr: Ref[UInt] = io.field[UInt]("w_addr")
    def wData: Ref[UInt] = io.field[UInt]("w_data")

class SyncRamProbe(parameter: SyncRamParameter) extends DVBundle[SyncRamParameter, SyncRamLayers](parameter)

case class SyncRamVerilogParameter() extends VerilogParameter

@generator
object SyncRamModule
    extends VerilogWrapper[SyncRamParameter, SyncRamLayers, SyncRamIO, SyncRamProbe, SyncRamVerilogParameter]:

    /** The module is named by its whole interface — width, depth, port shape, read policy — so one name is one body,
      * and the file the body is emitted into carries the name too.
      */
    override def moduleName(parameter: SyncRamParameter): String =
        s"SyncRam_dataWidth${parameter.width}_depth${parameter.depth}" +
            s"_ports${parameter.ports.name}_readUnderWrite${parameter.readUnderWrite.name}"

    override def verilogModuleName(parameter: SyncRamParameter): String = moduleName(parameter)

    /** No Verilog parameters: the interface is baked into the body, not passed to it. */
    def verilogParameter(parameter: SyncRamParameter): SyncRamVerilogParameter = SyncRamVerilogParameter()

/** A synchronous memory: a black box whose body is emitted beside the dump, one `.sv` per instantiated parameter set.
  *
  * The body is the classic synchronous-read pattern (`mem[addr]` sampled on the edge, no reset — a memory entry has no
  * reset value) that synthesis tools infer as a RAM macro, so a dump carries the body in its file list and every tool
  * compiles it like any other module. Instantiate through [[SyncRam.instantiate]], not the wrapper's own, so the body
  * is recorded for the dump to write.
  */
object SyncRam:

    /** The module every instance shares, and the body the dump has to emit, keyed by module name, first use first. */
    private val instantiated = mutable.LinkedHashMap.empty[String, SyncRamParameter]

    /** Forget what the previous elaboration instantiated: one elaboration reports its own modules. */
    def reset(): Unit = instantiated.clear()

    /** SystemVerilog body of one module. A pure function, so tests can pin the text. */
    def verilog(moduleName: String, parameter: SyncRamParameter): String =
        val readData = parameter.readUnderWrite match
            case SyncRamReadUnderWrite.ReadFirst  => "mem[r_addr]"
            case SyncRamReadUnderWrite.WriteFirst => "(w_en && (w_addr == r_addr)) ? w_data : mem[r_addr]"
        s"""module $moduleName(
       |  input clock,
       |  input r_en,
       |  input [${parameter.addressWidth - 1}:0] r_addr,
       |  output [${parameter.width - 1}:0] r_data,
       |  input w_en,
       |  input [${parameter.addressWidth - 1}:0] w_addr,
       |  input [${parameter.width - 1}:0] w_data
       |);
       |  reg [${parameter.width - 1}:0] mem [0:${parameter.depth - 1}];
       |  reg [${parameter.width - 1}:0] r_data_reg;
       |  assign r_data = r_data_reg;
       |  always @(posedge clock) begin
       |    if (w_en) mem[w_addr] <= w_data;
       |    if (r_en) r_data_reg <= $readData;
       |  end
       |endmodule
       |""".stripMargin

    /** Write one file per instantiated module into `dir` and add them to its file list. Returns the file names written.
      */
    def emitModules(dir: os.Path): Seq[String] =
        if instantiated.isEmpty then Seq.empty
        else
            os.makeDir.all(dir)
            val names = instantiated.map { (moduleName, parameter) =>
                os.write.over(dir / s"$moduleName.sv", verilog(moduleName, parameter))
                s"$moduleName.sv"
            }.toSeq
            FileList.append(dir, names)
            names

    /** Instantiate the module, recording it so the dump emits its body. */
    def instantiate(parameter: SyncRamParameter)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Instance[SyncRamIO, SyncRamProbe] =
        instantiated.put(SyncRamModule.moduleName(parameter), parameter)
        SyncRamModule.instantiate(parameter)
