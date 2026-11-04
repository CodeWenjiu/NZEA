package nzea_fpga

import nzea_rtl.Elaborate

/** How the FPGA level is generated: the layer's front door, beside the parameter it describes. The two-step protocol
  * (`config` writes the parameter out, `design` reads it back and elaborates) is [[nzea_rtl.Elaborate]]'s.
  */
object FpgaElaborate:

    def withLinked[T](parameter: TopParameter)(lower: os.Path => T): T =
        val scratch = os.temp.dir(prefix = "nzea-dump")
        try
            Elaborate.withLinked(
                Top.moduleName(parameter),
                scratch,
                configFile => Top.main(Array("config", configFile.toString) ++ configFlags(parameter)),
                configFile => Top.main(Array("design", configFile.toString))
            )(lower)
        finally os.remove.all(scratch)

    /** The generator's `config` flags for `parameter`: the level's serialized form, one flag per knob. The config file
      * this writes is what the `design` step reads back, so every knob has to be spelled here.
      */
    private def configFlags(parameter: TopParameter): Seq[String] =
        parameter.probes.flatMap(probe => Seq("--probes", probe))
