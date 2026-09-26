package nzea_rtl

/** Pure SystemVerilog text for a counter black box, plus the emit step that puts it next to the design's Verilog.
  *
  * The registers carry a `verilator public_flat_rd` metacomment so every register is readable by its leaf name from the
  * simulator (contract: `stat_*` leaf names are globally unique across the whole design). Other tools (iverilog,
  * Vivado) treat the metacomment as an ordinary comment.
  *
  * A counter cannot exist in the RTL without being part of the emitted [[StatSchema]]: both come from the same
  * [[StatCounter]] declarations, and the zaozi side (`nzea_core.stats`) instantiates this text through a wrapper whose
  * ports are the declarations' names.
  *
  * State lives inside the black box — all update logic stays on the RTL side through per-register `en`/`data` write
  * ports plus a `value` read-back port, so the box is opaque to constant folding.
  */
object StatsRegs:

  /** Width of every counter and of the wrapper's ports: the schema's maximum, so one port bundle serves all of them. */
  val Width: Int = 64

  /** Generate the SystemVerilog body for a black box named `moduleName` holding `regs`. Pure function for tests. */
  def verilog(moduleName: String, regs: Seq[StatCounter]): String =
    val portsDecl = regs.map { c =>
      s"  input ${c.name}_en,\n  input [${c.width - 1}:0] ${c.name}_data,\n  output [${c.width - 1}:0] ${c.name}_value"
    }
    val regsDecl = regs.map { c =>
      s"  reg [${c.width - 1}:0] ${c.name} /*verilator public_flat_rd*/;\n  assign ${c.name}_value = ${c.name};"
    }
    val resets = regs.map { c => s"      ${c.name} <= '0;" }
    val writes = regs.map { c => s"      if (${c.name}_en) ${c.name} <= ${c.name}_data;" }
    s"""module $moduleName(
       |  input clock,
       |  input resetN,
       |${portsDecl.mkString(",\n")}
       |);
       |${regsDecl.mkString("\n")}
       |  always @(posedge clock) begin
       |    if (!resetN) begin
       |${resets.mkString("\n")}
       |    end else begin
       |${writes.mkString("\n")}
       |    end
       |  end
       |endmodule
       |""".stripMargin

  /** Write the counter modules of `modules` into `dir` and add them to its file list, which firtool's own list cannot
    * carry: a black box has no body to lower, so its implementation is a file the design flow supplies. Returns the
    * file names added.
    */
  def emit(dir: os.Path, modules: Seq[(String, Seq[StatCounter])]): Seq[String] =
    os.makeDir.all(dir)
    val names = modules.map { (moduleName, regs) =>
      val file = s"$moduleName.sv"
      os.write.over(dir / file, verilog(moduleName, regs))
      file
    }
    val filelist = dir / "filelist.f"
    val listed = if os.exists(filelist) then os.read(filelist) else ""
    val terminated = if listed.isEmpty || listed.endsWith("\n") then listed else s"$listed\n"
    os.write.over(filelist, terminated ++ names.map(_ + "\n").mkString)
    names
