package nzea_rtl

/** The file list a dump hands the downstream tools (Verilator, iverilog, yosys): the lowering writes its own list of
  * the modules it emitted, and every hand-written body appended here — stat boxes, sync rams — is part of the design
  * those tools compile.
  */
object FileList:

  /** Append `names` to `dir`'s file list, one per line, continuing whatever list is already there. */
  def append(dir: os.Path, names: Seq[String]): Unit =
    val file = dir / "filelist.f"
    val listed = if os.exists(file) then os.read(file) else ""
    val terminated = if listed.isEmpty || listed.endsWith("\n") then listed else s"$listed\n"
    os.write.over(file, terminated ++ names.map(_ + "\n").mkString)
