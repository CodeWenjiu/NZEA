package nzea_rtl

/** The firtool flags a flow's lowering uses: split output, the randomization and debug stripping every dump is read
  * with, and the style options the tool downstream of the dump needs.
  *
  * Yosys refuses `always_comb` and local variables inside it, so a synthesis-shaped dump is lowered without either.
  * That shape is also what an external Verilog-2001 flow accepts: a toolchain without `-g2012` (the course labs'
  * iverilog) reads the same output.
  */
object Lowering:

    private val shared = Seq(
        "--split-verilog",
        "-disable-all-randomization",
        "-strip-debug-info"
    )

    private val yosys =
        shared :+ "--lowering-options=locationInfoStyle=wrapInAtSquareBracket,disallowLocalVariables,disallowPackedArrays,noAlwaysComb"

    def of(flow: RtlFlow): Seq[String] = flow match
        case RtlFlow.Verilator => shared
        case RtlFlow.Yosys     => yosys
