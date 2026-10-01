package nzea_rtl

import mainargs.TokensReader

/** Which flow the generated RTL is for: the CLI flag that selects it, the directory segment a dump lands in, and what
  * that flow needs from the design.
  *
  * Consumers ask for the property they need — [[bridges]], [[counters]] — instead of naming a flow, so a flow that
  * shares the shape (iverilog reads the same one Yosys does) is not forced to borrow a name, and one that diverges
  * later adds a variant plus the predicate that says so.
  */
enum RtlFlow(val segment: String):
    /** remu's cycle model: the top wires the interfaces to the DPI bridges, and the design builds the counters the
      * simulator reads back through VPI.
      */
    case Verilator extends RtlFlow("verilator")

    /** Synthesis-shaped RTL: the interfaces are top-level ports and no counters are built. Yosys today; iverilog, the
      * fpga flow and a PDK one share the shape.
      */
    case Yosys extends RtlFlow("yosys")

    /** Whether the top attaches the DPI bridges instead of exposing the interfaces as ports. */
    def bridges: Boolean = this == Verilator

    /** Whether the design builds the debug counters a simulator reads back. */
    def counters: Boolean = this == Verilator

object RtlFlow:

    /** Serialized as the segment, the same token the flag takes: one spelling for a flow across the CLI, the config
      * file and the build tree.
      */
    given upickle.default.ReadWriter[RtlFlow] = upickle.default
        .readwriter[String]
        .bimap[RtlFlow](
            _.segment,
            segment => fromString(segment).fold(error => throw new IllegalArgumentException(error), identity)
        )

    given TokensReader.Simple[RtlFlow] with
        def shortName = values.map(_.segment).mkString("|")

        def read(strs: Seq[String]): Either[String, RtlFlow] =
            strs.lastOption.map(fromString).getOrElse(Left("flow requires a value"))

    def fromString(s: String): Either[String, RtlFlow] =
        values.find(_.segment == s.toLowerCase) match
            case Some(flow) => Right(flow)
            case None       => Left(s"invalid flow '$s' (expected ${values.map(_.segment).mkString(", ")})")
