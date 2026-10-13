package nzea_rtl

import scala.collection.mutable

/** The file lists a dump hands the downstream tools: `filelist.f` for the SystemVerilog the tools compile (the lowering
  * writes its own list of the modules it emitted, and every hand-written body appended here — stat boxes, sync rams —
  * is part of the design too), `ipfiles.f` for the vendor-IP tcl snippets a project generator has to `source`, and
  * `simfiles.f` for the simulation sources an IP's generated project provides (the entries are paths under the
  * generated IP root — the xsim flow keeps a generated copy of its own to compile them from).
  *
  * The three differ in who starts them. The lowering rewrites `filelist.f` at every dump, so an append only extends it
  * and carries no state; nothing writes `ipfiles.f` or `simfiles.f` but the snippets themselves, so the dump's first
  * append starts a list over — a dump that instantiates fewer IPs than the last one must not leave the old ones listed.
  */
object FileList:

    /** The dumps' SystemVerilog list, the name every simulator and synthesizer reads. */
    private val VerilogList = "filelist.f"

    /** The vendor-IP snippet list. */
    val IpList = "ipfiles.f"

    /** The IP simulation-source list. */
    val SimList = "simfiles.f"

    /** The lists this dump has already written; cleared with the rest of a dump's state. */
    private val started = mutable.Set.empty[os.Path]

    DumpRegistry.onReset(() => started.clear())

    /** Append `names` to `dir`'s SystemVerilog list, one per line, behind what the lowering wrote there. */
    def append(dir: os.Path, names: Seq[String]): Unit =
        val list = dir / VerilogList
        write(list, names, if os.exists(list) then os.read(list) else "")

    /** Append `names` to `dir`'s vendor-IP list, one per line; the dump's first append starts the list over. */
    def appendIp(dir: os.Path, names: Seq[String]): Unit = appendStarted(dir / IpList, names)

    /** Append `names` to `dir`'s IP simulation-source list, one per line; the dump's first append starts it over. */
    def appendSim(dir: os.Path, names: Seq[String]): Unit = appendStarted(dir / SimList, names)

    private def appendStarted(list: os.Path, names: Seq[String]): Unit =
        // `add` is false when this dump already wrote the list: then this append extends it, otherwise it starts it.
        val extending = !started.add(list)
        write(list, names, if extending && os.exists(list) then os.read(list) else "")

    private def write(list: os.Path, names: Seq[String], listed: String): Unit =
        val terminated = if listed.isEmpty || listed.endsWith("\n") then listed else s"$listed\n"
        os.write.over(list, terminated ++ names.map(_ + "\n").mkString)
