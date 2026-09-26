package nzea_rtl

/** Test helper: elaborate a generator through the real flow and lower it to Verilog.
  *
  * `config`/`design` write `<moduleName>.mlirbc` into the working directory (the DSL's default output location), so the
  * caller only has to describe how to invoke the generated `main`.
  */
object TestFlow:

  /** Run `writeConfig` then `design`, then lower the produced bytecode with firtool. */
  def lower(moduleName: String, writeConfig: os.Path => Unit, design: os.Path => Unit): String =
    val dir = os.temp.dir(prefix = "nzea-elab")
    val cfg = dir / "config.json"
    val mlirbc = os.pwd / s"$moduleName.mlirbc"
    try
      writeConfig(cfg)
      design(cfg)
      assert(os.exists(mlirbc))
      os.proc("firtool", mlirbc.toString, "--lowering-options=disallowPackedArrays,noAlwaysComb").call().out.text()
    finally if os.exists(mlirbc) then os.remove(mlirbc)

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
        val (declared, names) = line match
          case declaration(hi, rest) => (Option(hi).fold(1)(_.toInt + 1), rest)
          // A continuation line carries names only and inherits the declaration's width; the `module X(` line and the
          // closing line carry no port name at all.
          case continued => (width, continued)
        val parsed = names.split(",").map(_.trim).filter(_.matches("[A-Za-z_][A-Za-z0-9_]*"))
        (declared, ports ++ parsed.map(_ -> declared))
      ._2
