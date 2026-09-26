package nzea_rtl

/** Pure SystemVerilog text for a [[StatsRegs]] black box: the VPI contract's source of truth.
  *
  * The registers carry a `verilator public_flat_rd` metacomment so every register is readable by its leaf name from the
  * simulator (contract: `stat_*` leaf names are globally unique across the whole design). Other tools (iverilog,
  * Vivado) treat the metacomment as an ordinary comment.
  *
  * A counter cannot exist in the RTL without being part of the emitted [[StatSchema]]: both come from the same
  * [[StatCounter]] declarations.
  *
  * State lives inside the black box — all update logic stays on the RTL side through per-register `en`/`data` write
  * ports plus a `value` read-back port, so the box is opaque to constant folding. This module holds the text only; the
  * zaozi wrapper that instantiates it, and the emit step that writes this text next to the generated RTL, follow.
  */
object StatsRegs:

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
       |  input reset,
       |${portsDecl.mkString(",\n")}
       |);
       |${regsDecl.mkString("\n")}
       |  always @(posedge clock) begin
       |    if (reset) begin
       |${resets.mkString("\n")}
       |    end else begin
       |${writes.mkString("\n")}
       |    end
       |  end
       |endmodule
       |""".stripMargin
