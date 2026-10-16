package nzea_rtl

/** Test helper: elaborate a generator through the real flow and lower it to Verilog, then read the result structurally.
  *
  * The flow itself is [[nzea_rtl.Elaborate]]'s — the same one the dump recipe runs — so a test that elaborates is
  * exercising the build path, not a copy of it.
  */
object TestFlow:

    /** firtool flags the tests lower with: packed arrays and `always_comb` are what the synthesis flows refuse. */
    val loweringOptions: Seq[String] = Seq("--lowering-options=disallowPackedArrays,noAlwaysComb")

    /** Run `writeConfig` then `design`, link every module the design produced, and lower the top to Verilog. */
    def lower(moduleName: String, writeConfig: os.Path => Unit, design: os.Path => Unit): String =
        val scratch = os.temp.dir(prefix = "nzea-elab")
        try Elaborate.withLinked(moduleName, scratch, writeConfig, design)(Elaborate.verilog(_, loweringOptions))
        finally os.remove.all(scratch)

    /** Port widths of `module`, parsed from its port table.
      *
      * Parsed rather than pattern-searched, because firtool shares one declaration across the ports of equal width, so
      * a port name and its width need not be on the same line. A port without a range is one bit wide.
      */
    def ports(sv: String, module: String): Map[String, Int] =
        val declaration = raw"""^\s*(?:input|output|inout)\s+(?:\[(\d+):0\]\s+)?(.+)$$""".r
        val start = sv.indexOf(s"module $module(")
        val header = sv.substring(start, sv.indexOf(");", start))
        header.linesIterator
            .foldLeft((1, Map.empty[String, Int])): (state, line) =>
                val (width, ports) = state
                // firtool appends a location comment per port; on the port that closes the list it would glue to the name.
                val (declared, names) = line.split("//", 2).head match
                    case declaration(hi, rest) => (Option(hi).fold(1)(_.toInt + 1), rest)
                    // A continuation line carries names only and inherits the declaration's width; the `module X(` line and the
                    // closing line carry no port name at all.
                    case continued => (width, continued)
                val parsed = names.split(",").map(_.trim).filter(_.matches("[A-Za-z_][A-Za-z0-9_]*"))
                (declared, ports ++ parsed.map(_ -> declared))
            ._2
