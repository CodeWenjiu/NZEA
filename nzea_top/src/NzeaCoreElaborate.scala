package nzea_top

import nzea_rtl.Elaborate

/** How the core level is generated: this module's front door.
  *
  * A zaozi top is generated through a two-step pipeline — `NzeaCore.main("config", …)` serializes a
  * [[NzeaCoreParameter]] to a file, `NzeaCore.main("design", …)` reads it back and elaborates, and `firld` joins what
  * the elaboration produced. That protocol, the config file's flag spelling and the scratch directory are facts about
  * *this* level, so they live here beside the parameter they describe; a consumer (the CLI today, a test or a higher
  * level next) hands over a parameter and a lowering function and never spells a sub-command.
  */
object NzeaCoreElaborate:

    /** Serialize `parameter`, elaborate the top, and hand the linked circuit to `lower`.
      *
      * The scratch directory holds the config file and the linked circuit and is removed once `lower` returns, so the
      * circuit is only valid inside `lower`.
      */
    def withLinked[T](parameter: NzeaCoreParameter)(lower: os.Path => T): T =
        val scratch = os.temp.dir(prefix = "nzea-dump")
        try
            Elaborate.withLinked(
                NzeaCore.moduleName(parameter),
                scratch,
                configFile => NzeaCore.main(Array("config", configFile.toString) ++ configFlags(parameter)),
                configFile => NzeaCore.main(Array("design", configFile.toString))
            )(lower)
        finally os.remove.all(scratch)

    /** The generator's `config` flags for `parameter`: the level's serialized form, one flag per knob. The config file
      * this writes is what the `design` step reads back, so every knob has to be spelled here — and a knob whose value
      * is absent (no predictor) is spelled by the flag's absence, not by a token.
      */
    private def configFlags(parameter: NzeaCoreParameter): Seq[String] =
        Seq(
            "--isa",
            parameter.config.core.isa.name,
            "--defaultPc",
            parameter.config.core.defaultPc.toString,
            "--flow",
            parameter.flow.segment,
            "--bypass",
            parameter.bypass.name,
            "--stats",
            parameter.stats.name
        ) ++ parameter.bpu.toSeq.flatMap(bpu => Seq("--bpu", bpu.name))
            ++ Seq("--lsqDepth", parameter.lsqDepth.toString)
            ++ parameter.mmioRanges.flatMap(window => Seq("--mmioRanges", window))
