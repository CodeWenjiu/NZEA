package nzea_fpga

import nzea_rtl.Elaborate

/** How the FPGA level is generated: the layer's front door, beside the parameter it describes.
  *
  * The two-step protocol (`config` writes the parameter out, `design` reads it back and elaborates, `firld` joins the
  * modules) is [[nzea_rtl.Elaborate]]'s; the top's parameter carries no flags yet, so `config` is the file alone.
  */
object FpgaElaborate:

    /** Serialize `parameter`, elaborate the board top, and hand the linked circuit to `lower`. */
    def withLinked[T](parameter: TopParameter)(lower: os.Path => T): T =
        val scratch = os.temp.dir(prefix = "nzea-dump")
        try
            Elaborate.withLinked(
                Top.moduleName(parameter),
                scratch,
                configFile => Top.main(Array("config", configFile.toString)),
                configFile => Top.main(Array("design", configFile.toString))
            )(lower)
        finally os.remove.all(scratch)
