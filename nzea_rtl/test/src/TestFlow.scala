package nzea_rtl

/** Test helper: elaborate a generator through the real flow and lower it to Verilog.
  *
  * `config`/`design` write `<moduleName>.mlirbc` into the working directory (the DSL's default output location), so the
  * caller only has to describe how to invoke the generated `main`.
  */
object TestFlow:

  /** Run `writeConfig` then `design`, link every module the design produced, and lower the top to Verilog.
    *
    * A generator instantiating another writes one `.mlirbc` per module, and only `firld` knows how to join them into
    * one circuit — the same two steps the `just zaozi-*` recipes run.
    */
  def lower(moduleName: String, writeConfig: os.Path => Unit, design: os.Path => Unit): String =
    val dir = os.temp.dir(prefix = "nzea-elab")
    val cfg = dir / "config.json"
    try
      writeConfig(cfg)
      design(cfg)
      val produced = os.list(os.pwd).filter(_.ext == "mlirbc")
      try
        assert(produced.exists(_.baseName == moduleName), s"no $moduleName.mlirbc among $produced")
        val input =
          if produced.lengthCompare(1) == 0 then produced.head.toString
          else
            val linked = dir / "design.mlir"
            os.proc(
              "firld",
              produced.map(_.toString),
              "--base-circuit",
              moduleName,
              "--no-mangle",
              "-o",
              linked.toString
            ).call()
            linked.toString
        os.proc("firtool", input, "--lowering-options=disallowPackedArrays,noAlwaysComb").call().out.text()
      finally produced.foreach(file => if os.exists(file) then os.remove(file))
    finally if os.exists(cfg) then os.remove(cfg)

  /** Port widths of `module`, parsed from its port table.
    *
    * Parsed rather than pattern-searched, because firtool shares one declaration across the ports of equal width, so a
    * port name and its width need not be on the same line. A port without a range is one bit wide.
    */
  def ports(sv: String, module: String): Map[String, Int] =
    val declaration = raw"""^\s*(?:input|output)\s+(?:\[(\d+):0\]\s+)?(.+)$$""".r
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
