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
