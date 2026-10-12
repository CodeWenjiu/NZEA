#!/usr/bin/env python3
"""Generate the Vivado project for a board with edalize.

Edalize owns the vendor-neutral scaffolding (create_project / read sources / read constraints / the build chain); the
board-specific Xilinx IP is not modelled here at all — the dump already wrote one tcl snippet per instantiated IP
(`ip_*.tcl`, listed in `ipfiles.f`, generated beside the RTL by the modules that instantiate them), and this script
only attaches them as `tclSource` snippets for edalize to `source` into the project script.

Alongside the project it writes two paste-able one-shots into the work root:

  build_all.tcl  project -> synthesis -> implementation -> bitstream
  program.tcl    JTAG programming (finds the part, attaches the impl's probes file when there is one)

When the dump wrote an ILA debug script beside the RTL (`ila_debug.tcl`), `build_all.tcl` attaches it to the
implementation run as `STEPS.OPT_DESIGN.TCL.PRE`: the debug core is created on the synthesized netlist, which only
the implementation run has open, and the presence of the file is what says the design is watched.

Both `cd` to the work root first, because edalize's own scripts assume it is the working directory (its run script
copies the bitstream to `[pwd]/nzea_fpga.bit`).

Usage:
    uv run vivado-project.py --rtl-dir <dir> --part <part> --top <module> --xdc <path> [--out <work_root>]
"""

import argparse
import glob
import os
import shutil
import sys

from edalize.flows.vivado import Vivado as VivadoFlow


def rtl_sources(rtl_dir):
    """The design's SystemVerilog sources: the dump's own file list when it has one, else every `.sv` beside it.

    The file list is what the elaborator wrote, so it names the current dump's modules and nothing else — a directory
    swept by glob would pick up a module a previous dump left behind and synthesize it into the design.
    """
    filelist = os.path.join(rtl_dir, "filelist.f")
    if os.path.exists(filelist):
        with open(filelist) as f:
            listed = [line.strip() for line in f if line.strip().endswith(".sv")]
        if listed:
            return [os.path.join(rtl_dir, name) for name in listed]
    return sorted(glob.glob(os.path.join(rtl_dir, "*.sv")))


def ip_tcl_sources(rtl_dir):
    """The vendor-IP snippets the dump emitted, from its IP list (`ipfiles.f`).

    Same reasoning as the RTL list: the elaborator knows which IPs the design instantiates and how they are configured
    (their ports are the RTL's interface), so the snippets are written beside the RTL and listed, never guessed here.
    """
    listed = os.path.join(rtl_dir, "ipfiles.f")
    if not os.path.exists(listed):
        return []
    with open(listed) as f:
        names = [line.strip() for line in f if line.strip().endswith(".tcl")]
    return [os.path.join(rtl_dir, name) for name in names]


def build_edam(rtl_dir, part, xdc_paths, top):
    files = []
    for sv in rtl_sources(rtl_dir):
        files.append({"name": os.path.abspath(sv), "file_type": "systemVerilogSource"})
    for xdc in xdc_paths:
        files.append({"name": os.path.abspath(xdc), "file_type": "xdc"})
    for ip_tcl in ip_tcl_sources(rtl_dir):
        files.append({"name": os.path.abspath(ip_tcl), "file_type": "tclSource"})

    return {
        "name": "nzea_fpga",
        "files": files,
        "toplevel": top,
        # `part` must live under `flow_options`: the edalize flow API reads tool options from there and writes them
        # into the project tcl. Under `tool_options` it is silently dropped, and Vivado then picks an arbitrary
        # default part whose banks may not match the XDC.
        "flow_options": {"part": part},
    }


def write_flow_scripts(work_root, part, rtl_dir):
    """The two one-shots, as absolute-path Tcl that works from any working directory.

    `build_all.tcl` also attaches the dump's ILA debug script to the implementation run when there is one: the debug
    core is created on the synthesized netlist (that is the only place `create_debug_core` exists), so the script is
    the pre-opt_design hook, and its presence beside the RTL is what says the design is watched.
    """
    project_tcl = os.path.join(work_root, "nzea_fpga.tcl")
    synth_tcl = os.path.join(work_root, "nzea_fpga_synth.tcl")
    run_tcl = os.path.join(work_root, "nzea_fpga_run.tcl")

    debug_tcl = os.path.join(rtl_dir, "ila_debug.tcl")
    build_all = os.path.join(work_root, "build_all.tcl")
    with open(build_all, "w") as f:
        f.write("# One-shot full flow: the project, then synthesis and implementation.\n")
        f.write(f"cd {{{work_root}}}\n")
        f.write("close_project -quiet\n")
        f.write(f"source {{{project_tcl}}}\n")
        if os.path.exists(debug_tcl):
            f.write(f"set_property STEPS.OPT_DESIGN.TCL.PRE {{{debug_tcl}}} [get_runs impl_1]\n")
        f.write(f"source {{{synth_tcl}}}\n")
        f.write(f"source {{{run_tcl}}}\n")

    bitstream = os.path.join(work_root, "nzea_fpga.bit")
    program = os.path.join(work_root, "program.tcl")
    with open(program, "w") as f:
        f.write(
            f"""# Program the FPGA over JTAG (run after build_all.tcl produced the bitstream).
cd {{{work_root}}}
if {{![file exists {bitstream}]}} {{
  puts "ERROR: bitstream not found: {bitstream}"
  puts "Run build_all.tcl first to produce it"
  return
}}
open_hw_manager
connect_hw_server
set done 0
foreach hw_target [get_hw_targets] {{
  current_hw_target $hw_target
  if {{[catch {{open_hw_target}} err]}} {{ continue }}
  foreach hw_dev [get_hw_devices] {{
    if {{[string first [get_property PART $hw_dev] {part}] == 0}} {{
      current_hw_device $hw_dev
      set_property PROGRAM.FILE {{{bitstream}}} [current_hw_device]
      # The probes file is generated by implementation beside the bitstream; attaching it is what makes the ILA core
      # visible to the hardware manager.
      set ltx [glob -nocomplain [pwd]/nzea_fpga.runs/impl_1/*.ltx]
      if {{[llength $ltx] > 0}} {{
        set_property PROBES.FILE [lindex $ltx 0] [current_hw_device]
      }}
      program_hw_devices [current_hw_device]
      set done 1
      break
    }}
  }}
  close_hw_target
  if {{$done}} {{ break }}
}}
if {{!$done}} {{ error "no hw device of part {part} found — check cables and the hardware server" }}
close_hw_manager
puts "INFO: FPGA {part} programmed."
"""
        )
    return build_all, program


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rtl-dir", required=True, help="Directory containing the dumped RTL and its IP snippets")
    parser.add_argument("--part", required=True, help="Vivado part number, e.g. xc7a200tfbg484-2")
    parser.add_argument("--top", required=True, help="Top-level module name")
    parser.add_argument("--xdc", action="append", required=True, help="Constraint file; repeatable")
    parser.add_argument("--out", default=None, help="Work root (default: <rtl-dir>/vivado)")
    args = parser.parse_args()

    work_root = args.out or os.path.join(args.rtl_dir, "vivado")
    if os.path.isdir(work_root):
        shutil.rmtree(work_root)
    os.makedirs(work_root)

    edam = build_edam(args.rtl_dir, args.part, args.xdc, args.top)
    VivadoFlow(edam=edam, work_root=work_root).configure()

    build_all, program = write_flow_scripts(work_root, args.part, args.rtl_dir)
    print(f"Vivado project generated at: {work_root}")
    print(f"  build:   vivado -mode batch -source {build_all} -nolog -nojournal")
    print(f"  program: vivado -mode batch -source {program} -nolog -nojournal")


if __name__ == "__main__":
    sys.exit(main())
