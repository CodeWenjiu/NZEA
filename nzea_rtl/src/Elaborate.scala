package nzea_rtl

import nzea_rtl.DumpRegistry
import nzea_rtl.stat.StatRegistry
import nzea_rtl.stdlib.SyncRam

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
        // The hand-written bodies a lowering has to emit (stat boxes, sync rams, vendor-IP snippets) are read back after
        // it: start from an empty set so each run reports its own.
        DumpRegistry.reset()
        StatRegistry.reset()
        SyncRam.reset()
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
    def verilog(mlir: os.Path, params: Seq[String]): String =
        os.proc("firtool", mlir.toString, params).call().out.text()

    /** Lower `mlir` into `outDir`, one file per module plus the file list, as the simulators expect to find it, and
      * the layer fragments firtool writes beside them ([[layerRoots]]).
      *
      * The fragments of an earlier lowering are removed first: they are firtool's own output and are rewritten by this
      * one, so a stale fragment — from a design that had layers where this one does not — would otherwise be found by
      * [[layerRoots]] and compiled into the project.
      */
    def verilogInto(mlir: os.Path, outDir: os.Path, params: Seq[String]): Unit =
        os.makeDir.all(outDir)
        os.list(outDir)
            .filter(path => (path.last.startsWith("layers-") || path.last.startsWith("ref_")) && path.ext == "sv")
            .foreach(os.remove(_))
        os.proc("firtool", mlir.toString, params, "-o", outDir.toString).call()
        val roots = layerRoots(outDir)
        if roots.nonEmpty then FileList.append(outDir, roots)

    /** The root layer fragments of a split lowering: each `layers-*.sv` firtool writes holds the `bind` statements of
      * one module's layer blocks, and the roots include their children’s, so compiling the roots compiles the tree once.
      * firtool leaves them out of `filelist.f` — it writes the list of the modules, and a fragment is not one — but they
      * are part of the design: without them the layer modules (a bound probe consumer, an ILA) are never instantiated.
      *
      * A fragment that only guards its macro carries nothing — a module captured *through* by a layer block gets an empty
      * one — and those are left out of the list.
      */
    private def layerRoots(outDir: os.Path): Seq[String] =
        val include = "`include \"([^\"]+)\"".r
        val fragments = os
            .list(outDir)
            .filter(path => path.last.startsWith("layers-") && path.ext == "sv")
            .map(path => (path, os.read(path)))
        val machinery = fragments.filter((_, text) => text.contains("bind ") || include.findFirstIn(text).isDefined)
        val included = machinery.flatMap((_, text) => include.findAllMatchIn(text).map(_.group(1))).toSet
        machinery.filterNot((path, _) => included.contains(path.last)).map(_._1.last).sorted

    private def intermediates: Set[os.Path] = os.list(os.pwd).filter(_.ext == "mlirbc").toSet
