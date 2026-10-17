package nzea_tile

import nzea_rtl.Elaborate

/** How the tile level is generated: this module's front door, beside the parameter it describes.
  *
  * The two-step protocol (`config` writes the parameter out, `design` reads it back and elaborates, `firld` joins the
  * modules) is [[nzea_top.NzeaCoreElaborate]]'s; a level top spells its own parameter's flags and nothing else. What a
  * flow can build is the platform's device list, stated beside the wiring it feeds ([[NzeaTile]]'s `devices` extension
  * on [[nzea_rtl.RtlFlow]]).
  */
object NzeaTileElaborate:

    def withLinked[T](parameter: NzeaTileParameter)(lower: os.Path => T): T =
        val scratch = os.temp.dir(prefix = "nzea-dump")
        try
            Elaborate.withLinked(
                NzeaTile.moduleName(parameter),
                scratch,
                configFile => NzeaTile.main(Array("config", configFile.toString) ++ configFlags(parameter)),
                configFile => NzeaTile.main(Array("design", configFile.toString))
            )(lower)
        finally os.remove.all(scratch)

    /** The tile's `config` flags, one per knob the parameter carries; a knob whose value is absent (no predictor) is
      * spelled by the flag's absence.
      */
    private def configFlags(parameter: NzeaTileParameter): Seq[String] =
        Seq(
            "--isa",
            parameter.config.isa.name,
            "--defaultPc",
            parameter.config.defaultPc.toString,
            "--flow",
            parameter.flow.segment,
            "--bypass",
            parameter.bypass.name,
            "--stats",
            parameter.stats.name
        ) ++ parameter.bpu.toSeq.flatMap(bpu => Seq("--bpu", bpu.name))
            ++ Seq("--lsqDepth", parameter.lsqDepth.toString)
            ++ parameter.icache.toSeq.flatMap(icache => Seq("--icache", icache.name))
            ++ parameter.dcache.toSeq.flatMap(dcache => Seq("--dcache", dcache.name))
            ++ parameter.devices.flatMap(device => Seq("--devices", upickle.default.write(device)))
            ++ parameter.externals.flatMap(external => Seq("--externals", upickle.default.write(external)))
