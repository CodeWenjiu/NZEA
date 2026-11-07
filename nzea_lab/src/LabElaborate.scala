package nzea_lab

import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

import me.jiuyang.zaozi.{Generator, Parameter}
import nzea_rtl.{Elaborate, Lowering, RtlFlow}

/** The one elaboration path a lab generator takes: write its parameter as the config, let the generator's `design`
  * write the module, then lower the linked circuit into `out` in the shape an external Verilog toolchain reads.
  */
object LabElaborate:

    /** `Elaborate.withLinked` finds its intermediates by diffing the process's working directory, so two elaborations
      * would see each other's `.mlirbc` files — including two test forks, which share the directory. A lock file beside
      * the process (the system temp dir, so every fork sees one) serializes them; the CLI runs one dump per process
      * anyway.
      */
    private val lockFile = os.Path(System.getProperty("java.io.tmpdir")) / "nzea-lab-elaborate.lock"

    private def withLock[T](body: => T): T =
        val channel = FileChannel.open(lockFile.toNIO, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = channel.lock()
        try body
        finally
            lock.release()
            channel.close()

    def dump[P <: Parameter](
        gen: Generator[P, ?, ?, ?],
        parameter: P,
        out: os.Path,
        preserveNames: Boolean
    )(using upickle.default.ReadWriter[P]): Unit = withLock {
        val scratch = os.temp.dir(prefix = "nzea-lab")
        try
            Elaborate.withLinked(
                gen.moduleName(parameter),
                scratch,
                config => os.write(config, upickle.default.write(parameter)),
                config => gen.main(Array("design", config.toString))
            ): circuit =>
                val flags =
                    if preserveNames then Lowering.of(RtlFlow.Yosys) :+ "--preserve-values=named"
                    else Lowering.of(RtlFlow.Yosys)
                Elaborate.verilogInto(circuit, out, flags)
        finally os.remove.all(scratch)
    }
