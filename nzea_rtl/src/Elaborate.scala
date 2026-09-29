package nzea_rtl

/** The design flow, shared by the dump recipe and the tests: a generator's `config` and `design` steps write one
  * `.mlirbc` per module into the working directory, `firld` joins those into a single circuit, and `firtool` lowers
  * that circuit to Verilog.
  *
  * Generators write where the process is, so the files a run produced are found by diffing the working directory rather
  * than by listing it: whatever was already there is left alone, and the intermediates are removed once `lower` is done
  * with the linked circuit (which lives in `scratch`).
  */
object Elaborate:

  /** Elaborate and link `moduleName`, hand the linked circuit to `lower`, then remove the intermediates.
    *
    * The removal covers every `.mlirbc` the run produced, including one left behind by an elaboration that failed
    * partway: that file would be counted as pre-existing by the next run, and its module would be dropped from the
    * linked design — a dump that instantiates a module it never defines.
    */
  def withLinked[T](moduleName: String, scratch: os.Path, writeConfig: os.Path => Unit, design: os.Path => Unit)(
      lower: os.Path => T
  ): T =
    val config = scratch / "config.json"
    val before = intermediates
    // The stats a lowering declares are read back after it: start from an empty set so each run reports its own.
    StatRegistry.reset()
    try
      writeConfig(config)
      design(config)
      val produced = intermediates -- before
      require(
        produced.exists(_.baseName == moduleName),
        s"no $moduleName.mlirbc among ${produced.map(_.baseName).toSeq.sorted.mkString(", ")}"
      )
      val circuit =
        // A design of one module is its own circuit; firdl would only copy it.
        if produced.sizeIs == 1 then produced.head
        else
          val linked = scratch / "design.mlir"
          os.proc(
            "firld",
            produced.toSeq.sorted.map(_.toString),
            "--base-circuit",
            moduleName,
            "--no-mangle",
            "-o",
            linked.toString
          ).call()
          linked
      lower(circuit)
    finally (intermediates -- before).foreach(os.remove(_))

  /** Lower `mlir` to Verilog on standard output. */
  def verilog(mlir: os.Path, params: Seq[String]): String = os.proc("firtool", mlir.toString, params).call().out.text()

  /** Lower `mlir` into `outDir`, one file per module plus the file list, as the simulators expect to find it. */
  def verilogInto(mlir: os.Path, outDir: os.Path, params: Seq[String]): Unit =
    os.makeDir.all(outDir)
    os.proc("firtool", mlir.toString, params, "-o", outDir.toString).call()

  private def intermediates: Set[os.Path] = os.list(os.pwd).filter(_.ext == "mlirbc").toSet
