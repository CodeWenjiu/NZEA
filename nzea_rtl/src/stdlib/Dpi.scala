package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.dialect.firrtl.operation.{ConnectApi, given}
import org.llvm.mlir.scalalib.capi.ir.{
    Attribute,
    Block,
    Context,
    Location,
    LocationApi,
    NamedAttribute,
    NamedAttributeApi,
    Operation,
    OperationApi,
    Value,
    given
}

/** DPI-C calls, expressed as the FIRRTL `int.dpi.call` intrinsic.
  *
  * The DSL does not ship this op, so it is built through the generic operation API; the ABI is CIRCT's DPI intrinsic
  * ABI: `inputNames`/`outputName` become the arguments of the generated `import "DPI-C"` declaration. A call without a
  * clock is combinational (its result tracks its inputs); a call with one is a state transfer next to the clock, which
  * is what makes a replayed call impossible.
  *
  * The result is returned as a `Wire`: a FIRRTL value produced by the intrinsic has to be named anyway, and the DSL
  * gives no public way to build a raw `Node` outside its own package.
  */

/** Build and append a `firrtl.int.dpi.call`; returns the operation when it has a result. */
private def dpiCall(
    functionName: String,
    inputNames: Option[Seq[String]],
    outputName: Option[String],
    clock: Option[Referable[Clock]],
    enable: Option[Referable[Bool]],
    inputs: Seq[Referable[?]],
    result: Option[Data]
)(using
    Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
): Option[Value] =
    require(
        inputNames.forall(_.size == inputs.size),
        s"$functionName: ${inputNames.map(_.size).getOrElse(0)} input names for ${inputs.size} operands"
    )

    val namedAttributeApi = summon[NamedAttributeApi]
    def attribute(name: String, value: Attribute): NamedAttribute =
        namedAttributeApi.namedAttributeGet(name.identifierGet, value)

    val attributes =
        Seq(attribute("functionName", functionName.stringAttrGet)) ++
            inputNames.map(names => attribute("inputNames", names.map(_.stringAttrGet).arrayAttrGet)) ++
            outputName.map(name => attribute("outputName", name.stringAttrGet)) ++
            // AttrSizedOperandSegments: clock?, enable?, inputs*
            Seq(attribute("operandSegmentSizes", Seq(clock.size, enable.size, inputs.size).denseI32ArrayGet))

    val operation = summon[OperationApi].operationCreate(
        name = "firrtl.int.dpi.call",
        location = summon[LocationApi].locationFileLineColGet(
            summon[sourcecode.File].value,
            summon[sourcecode.Line].value,
            0
        ),
        namedAttributes = attributes,
        operands = clock.map(_.refer).toSeq ++ enable.map(_.refer).toSeq ++ inputs.map(_.refer),
        resultsTypes = result.map(r => Seq(r.toMlirType))
    )
    operation.appendToBlock()
    if result.isDefined then Some(operation.getResult(0)) else None

/** Call a DPI function combinationally: the returned wire follows `inputs` whenever `enable` is set (and is undefined
  * otherwise). Use this for side-effect-free functions such as memory reads.
  */
def dpiUnclockedCall[T <: Data](
    functionName: String,
    result: T,
    inputNames: Option[Seq[String]] = None,
    outputName: Option[String] = None
)(enable: Referable[Bool], inputs: Referable[?]*)(using
    Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Wire[T] =
    val value = dpiCall(functionName, inputNames, outputName, None, Some(enable), inputs, Some(result)).get
    val wire = Wire(result)
    val connect = summon[ConnectApi].op(
        value,
        wire.refer,
        summon[LocationApi].locationFileLineColGet(summon[sourcecode.File].value, summon[sourcecode.Line].value, 0)
    )
    connect.operation.appendToBlock()
    wire

/** Call a DPI function on the clock edge, guarded by `enable`. Use this for functions with side effects, such as a
  * write or a trace push: the call is issued once per enabled clock edge rather than continuously.
  */
def dpiClockedCall(
    functionName: String,
    inputNames: Option[Seq[String]] = None
)(clock: Referable[Clock], enable: Referable[Bool], inputs: Referable[?]*)(using
    Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
): Unit =
    dpiCall(functionName, inputNames, None, Some(clock), Some(enable), inputs, None)
    ()
