package nzea_fpga

import _root_.circt.stage.ChiselStage
import nzea_config.FpgaBoard
import nzea_config.core.CoreConfig
import nzea_fpga.boards.lxb_artix7.{IlaProbes, LxbArtix7Config, LxbArtix7Top}
import nzea_fpga.boards.tangnano20k.TangNano20kTop

import java.io.File
import java.nio.file.{Files, StandardCopyOption}
import scala.sys.process._

object FpgaElaborate {

  /** Gowin synthesis has a built-in ALU primitive; rename our ALU module to avoid conflict. */
  private def renameAluForGowin(outDir: String): Unit = {
    val dir = new File(outDir)
    val aluFile = new File(dir, "ALU.sv")
    if (!aluFile.exists()) return

    val newFile = new File(dir, "ALU_nzea.sv")
    Files.move(aluFile.toPath, newFile.toPath, StandardCopyOption.REPLACE_EXISTING)

    val modPattern = raw"\bmodule ALU\b"
    val instPattern = raw"\bALU alu\b"

    for (f <- dir.listFiles((_, n) => n.endsWith(".sv"))) {
      val content = new String(Files.readAllBytes(f.toPath))
      val updated = content
        .replaceAll(modPattern, "module ALU_nzea")
        .replaceAll(instPattern, "ALU_nzea alu")
      if (updated != content)
        Files.write(f.toPath, updated.getBytes)
    }
  }

  /** Generate the Vivado project via the `vivado-project.nu` entry point.
    *
    * All board-specific parameters (part, top module, XDC, IP snippets) are resolved from `chips.nu` inside the script;
    * this call only passes the board segment and ISA.
    *
    * When `debugIla` is set, `ila_config.json` is written into the RTL directory so the ILA IP TCL snippet can be
    * generated with the exact probe widths from [[IlaProbes.widths]] (the single source of truth). A stale
    * `ila_config.json` from a previous ILA-enabled build is removed otherwise. When enabled, the ILA capture script and
    * probe name map are also written into the Vivado work root (the dump-time pair of the runtime `just ila-capture`
    * flow).
    */
  private def generateVivadoProject(board: FpgaBoard, isa: String, outDir: String, debugIla: Boolean): Unit = {
    val ilaCfg = new File(outDir, "ila_config.json")
    if (debugIla) {
      val json =
        s"""{"depth":${IlaProbes.depth},"widths":[${IlaProbes.widths.mkString(",")}]}"""
      Files.write(ilaCfg.toPath, json.getBytes)
    } else {
      ilaCfg.delete()
    }

    val script = new File("nzea_fpga/scripts/vivado-project.nu").getAbsolutePath
    val cmd = Seq("nu", script, "--board", board.segment, "--isa", isa)
    val proc = Process(cmd, new File("."))
    proc.!< match {
      case 0 => ()
      case code =>
        Console.err.println(s"[FpgaElaborate] vivado-project generation failed (exit $code)")
    }

    if (debugIla) {
      val vivadoDir = new File(outDir, "vivado")
      vivadoDir.mkdirs()
      Files.write(new File(vivadoDir, "ila_capture.tcl").toPath, IlaCapture.tcl.getBytes)
      Files.write(new File(vivadoDir, "ila_probes.map").toPath, IlaCapture.probeMap.getBytes)
    }
  }

  /** Static runtime content for the ILA capture flow (paired with `just ila-capture`). */
  private object IlaCapture {

    // Dual-key rename map (literal string replacement, longest key first — see
    // probe/cli/src/vcd.rs): bare `probeN` keys catch nets that lost their name
    // in synthesis (the ILA VCD then exports the port name, possibly as
    // `core/ila/probeN`, which the bare key still renames as a substring);
    // `core/ila/<net>` path keys catch nets whose firtool-generated name
    // survived into the Vivado export (board-level ILA, so no `tile/` segment).
    // Net names are synthesis artifacts of one verified capture and may drift
    // between builds — re-derive them from `pulse signal` on a fresh capture if
    // a rename stops matching. Semantic names mirror the probe wiring in
    // LxbArtix7Core / IlaProbes.widths (11 probes: board IO + MIG physical layer).
    val probeMap: String =
      """probe0 cpu_reset
        |probe1 uart_txd
        |probe2 uart_rxd
        |probe3 finisher_finished
        |probe4 ddr_ui_rst
        |probe5 ddr_calib_done
        |probe6 ddr_app_addr
        |probe7 ddr_app_wdf_rdy
        |probe8 ddr_app_rdy
        |probe9 ddr_rd_valid
        |probe10 ddr_rd_data
        |core/ila/_tile_io_debug_cpuReset cpu_reset
        |core/ila/io_uart_tx uart_txd
        |core/ila/uart_rxd uart_rxd
        |core/ila/finisher_finished finisher_finished
        |core/ila/SR ddr_ui_rst
        |core/ila/init_calib_complete ddr_calib_done
        |core/ila/_bridge_io_mig_app_addr ddr_app_addr
        |core/ila/app_wdf_rdy ddr_app_wdf_rdy
        |core/ila/app_rdy ddr_app_rdy
        |core/ila/app_rd_data_valid ddr_rd_valid
        |core/ila/app_rd_data ddr_rd_data
        |""".stripMargin

    val tcl: String =
      s"""# ILA capture for NzeaTile (lxb_artix7).
         |#
         |# Modes (via -tclargs):
         |#   reset <uart_port> <probe_bin>   (default) arm on the cpu_reset assert
         |#                                   edge (TRIGGER_COMPARE_VALUE eq1'b1),
         |#                                   then send the uart reset command.
         |#   snapshot                          trigger and capture immediately.
         |#
         |# Trigger syntax notes (Vivado 2023.2): CONTROL.TRIGGER_CONDITION only takes
         |# AND/OR aggregation; per-probe compare values live on the hw_probe object
         |# (TRIGGER_COMPARE_VALUE, e.g. eq1'b1). Edge "er1'bF" is accepted by the
         |# property but does not fire in this ILA config, so we trigger on the assert
         |# level and use the full capture window (depth ${IlaProbes.depth}, ~${IlaProbes.depth / 100} us
         |# at 100 MHz) plus TRIGGER_POSITION ${IlaProbes.depth / 4} (25%) to cover the reset
         |# pulse and the program start. Both values track IlaProbes.depth — the
         |# TRIGGER_POSITION must stay below the depth or Vivado rejects the property.
         |set mode reset
         |set uart_port /dev/serial/by-id/usb-1a86_USB_Serial-if00-port0
         |set probe_bin probe
         |if {$$argc >= 1} {
         |  set mode [lindex $$argv 0]
         |  if {$$mode eq "reset"} {
         |    if {$$argc >= 2} { set uart_port [lindex $$argv 1] }
         |    if {$$argc >= 3} { set probe_bin [lindex $$argv 2] }
         |  }
         |}
         |
         |open_hw_manager
         |connect_hw_server
         |open_hw_target
         |
         |set hw_dev_found 0
         |foreach hw_dev [get_hw_devices] {
         |  if {[string first [get_property PART $$hw_dev] "xc7a200tfbg484-2"] == 0} {
         |    current_hw_device $$hw_dev
         |    set hw_dev_found 1
         |    break
         |  }
         |}
         |if {!$$hw_dev_found} {
         |  error "no hw device of part xc7a200tfbg484-2 found — program the board first"
         |}
         |set ltx [glob -nocomplain [file join [file dirname [info script]] nzea_fpga.runs impl_1 *.ltx]]
         |if {[llength $$ltx] > 0} {
         |  set_property PROBES.FILE [lindex $$ltx 0] [current_hw_device]
         |}
         |refresh_hw_device [current_hw_device]
         |if {[llength [get_hw_ilas]] == 0} {
         |  error "no ILA core in the programmed device — rebuild with --ila and reprogram"
         |}
         |set hw_ila [lindex [get_hw_ilas] 0]
         |set_property CONTROL.TRIGGER_CONDITION AND $$hw_ila
         |set_property CONTROL.TRIGGER_POSITION ${IlaProbes.depth / 4} $$hw_ila
         |set p0 [get_hw_probes -filter {PROBE_PORT == 0} $$hw_ila]
         |set_property CAPTURE_COMPARE_VALUE eq1'bX $$p0
         |set_property TRIGGER_COMPARE_VALUE eq1'b1 $$p0
         |if {$$mode eq "snapshot"} {
         |  run_hw_ila -trigger_now $$hw_ila
         |} else {
         |  run_hw_ila $$hw_ila
         |  exec $$probe_bin reset $$uart_port
         |}
         |wait_on_hw_ila $$hw_ila
         |write_hw_ila_data -force -vcd_file capture.vcd [upload_hw_ila_data $$hw_ila]
         |
         |close_hw_target
         |close_hw_manager
         |puts "ILA capture written to [pwd]/capture.vcd"
         |""".stripMargin

  }

  /** Board top module name (mirrors the elaborate() match). */
  private def topNameFor(board: FpgaBoard): String = board match {
    case FpgaBoard.LxbArtix7   => "LxbArtix7Top"
    case FpgaBoard.TangNano20k => "TangNano20kTop"
  }

  def elaborate(
      board: FpgaBoard,
      outDir: String,
      clockHz: Int,
      debugIla: Boolean,
      firtoolOpts: Array[String]
  )(implicit config: CoreConfig): Unit = {
    val topName = topNameFor(board)
    println(s"Generating FPGA top ($topName) for board: ${board.segment}")
    println(s"Output: $outDir")

    board match {
      case FpgaBoard.LxbArtix7 =>
        ChiselStage.emitSystemVerilogFile(
          new LxbArtix7Top(clockHz, debugIla),
          args = Array("--target-dir", outDir),
          firtoolOpts = firtoolOpts
        )
        // Generate the Vivado project (edalize-based) right after the RTL so the
        // user gets the paste-able full-flow command from a single dump invocation.
        generateVivadoProject(board, config.isa, outDir, debugIla)
      case FpgaBoard.TangNano20k =>
        ChiselStage.emitSystemVerilogFile(
          new TangNano20kTop(clockHz),
          args = Array("--target-dir", outDir),
          firtoolOpts = firtoolOpts
        )
        // Gowin-specific: rename ALU module to avoid name collision with built-in primitive
        renameAluForGowin(outDir)
    }
  }

}
