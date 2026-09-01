#!/usr/bin/env python3
"""Generate a Vivado project for the lxb_artix7 board using edalize.

Replaces the hand-written create_project.tcl. Edalize owns the vendor-neutral
flow scaffolding (create_project / read sources / read constraints / build
chain); board-specific Xilinx IP (clk_wiz MMCM, ILA) is attached as `tclSource`
snippets that edalize `source`s into the project script.

Path handling: when running inside WSL, all absolute paths passed to Vivado
(the Windows binary) must be converted from /mnt/c/... to C:\\... via
`wslpath -m`. This avoids UNC (\\\\wsl$\\...) synthesis failures. On native
Linux the paths are passed through unchanged.

Usage:
    uv run vivado-project.py --rtl-dir <dir> --part <part> --xdc <path> \
        [--ip-tcl <path> ...] [--out <work_root>]
"""

import argparse
import glob
import json
import os
import shutil
import subprocess
import sys

from edalize.flows.vivado import Vivado as VivadoFlow


def is_wsl() -> bool:
    """Detect WSL via /proc/sys/fs/binfmt_misc/WSLInterop or /proc/version."""
    if os.path.exists("/proc/sys/fs/binfmt_misc/WSLInterop"):
        return True
    try:
        with open("/proc/version") as f:
            version = f.read().lower()
            return "microsoft" in version or "wsl" in version
    except OSError:
        return False


def to_windows_path(path: str) -> str:
    """Convert a Linux path to a Windows path (for WSL), passthrough otherwise."""
    if not is_wsl():
        return path
    try:
        return subprocess.run(
            ["wslpath", "-m", path], capture_output=True, text=True, check=True
        ).stdout.strip()
    except (subprocess.SubprocessError, FileNotFoundError):
        # Fall back to the original path if wslpath is unavailable.
        return path


def build_edam(rtl_dir, part, xdc_path, ip_tcl_paths, top):
    files = []
    for sv in sorted(glob.glob(os.path.join(rtl_dir, "*.sv"))):
        files.append({"name": to_windows_path(os.path.abspath(sv)), "file_type": "systemVerilogSource"})
    files.append({"name": to_windows_path(os.path.abspath(xdc_path)), "file_type": "xdc"})
    for ip_tcl in ip_tcl_paths:
        files.append({"name": to_windows_path(os.path.abspath(ip_tcl)), "file_type": "tclSource"})

    # If the RTL instantiates the ILA wrapper, FpgaElaborate wrote ila_config.json
    # with the probe widths (single source of truth, see IlaProbes.scala). Generate
    # the ILA IP snippet from it so the Vivado IP matches the RTL interface.
    ila_cfg = os.path.join(rtl_dir, "ila_config.json")
    if os.path.exists(ila_cfg):
        with open(ila_cfg) as f:
            cfg = json.load(f)
        width_lines = "".join(
            f"  CONFIG.C_PROBE{i}_WIDTH {w} \\\n"
            for i, w in enumerate(cfg.get("widths", []))
        )
        depth = cfg.get("depth", 4096)
        nprobes = len(cfg.get("widths", []))
        ila_tcl = f"""# Auto-generated ILA IP snippet (from ila_config.json)
create_ip -name ila -vendor xilinx.com -library ip -module_name u_ila_0
set_property -dict [list \\
  CONFIG.C_DATA_DEPTH {depth} \\
  CONFIG.C_NUM_OF_PROBES {nprobes} \\
{width_lines}] [get_ips u_ila_0]
generate_target all [get_ips u_ila_0]
"""
        ila_tcl_path = os.path.join(rtl_dir, "ip_ila_generated.tcl")
        with open(ila_tcl_path, "w") as f:
            f.write(ila_tcl)
        files.append({"name": to_windows_path(os.path.abspath(ila_tcl_path)), "file_type": "tclSource"})

    return {
        "name": "nzea_fpga",
        "files": files,
        "toplevel": top,
        # ``part`` must live under ``flow_options``: the edalize flow API reads
        # tool options from edam["flow_options"] (see Edaflow.extract_tool_options)
        # and writes them into the Vivado project tcl via ``set_property part``.
        # Putting it under tool_options silently drops it, and Vivado then picks
        # an arbitrary default part whose banks may not match our XDC.
        "flow_options": {"part": part},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rtl-dir", required=True, help="Directory containing generated .sv RTL")
    parser.add_argument("--part", required=True, help="Vivado part number, e.g. xc7a200tsbg484-1")
    parser.add_argument("--xdc", required=True, help="Path to the XDC constraint file")
    parser.add_argument("--ip-tcl", action="append", default=[],
                        help="TCL snippet creating Xilinx IP (clk_wiz/ILA); repeatable")
    parser.add_argument("--out", default=None, help="Output work_root (default: <rtl-dir>/vivado)")
    parser.add_argument("--top", default="LxbArtix7Top", help="Top-level module name")
    args = parser.parse_args()

    work_root = args.out or os.path.join(args.rtl_dir, "vivado")
    if os.path.isdir(work_root):
        shutil.rmtree(work_root)
    os.makedirs(work_root)

    edam = build_edam(args.rtl_dir, args.part, args.xdc, args.ip_tcl, args.top)
    backend = VivadoFlow(edam=edam, work_root=work_root)
    backend.configure()

    # Generate a one-shot flow script that mirrors the old `create_project.tcl`
    # experience: source this single file in the Vivado Tcl Console to create the
    # project and run the full build (project -> synthesis -> implementation -> bitstream).
    # All internal paths are converted to Windows form under WSL (Vivado is a Windows
    # binary and cannot read /mnt/c/... paths).
    project_tcl = to_windows_path(os.path.join(work_root, "nzea_fpga.tcl"))
    synth_tcl = to_windows_path(os.path.join(work_root, "nzea_fpga_synth.tcl"))
    run_tcl = to_windows_path(os.path.join(work_root, "nzea_fpga_run.tcl"))
    flow_tcl = os.path.join(work_root, "build_all.tcl")
    with open(flow_tcl, "w") as f:
        f.write(f"# One-shot full flow: source {os.path.basename(project_tcl)} then synth + impl\n")
        # Close any open project first (like the old create_project.tcl did) so
        # repeated sourcing from the Tcl Console does not fail with a project
        # already open.
        f.write("close_project -quiet\n")
        f.write(f"source {{{project_tcl}}}\n")
        f.write(f"source {{{synth_tcl}}}\n")
        f.write(f"source {{{run_tcl}}}\n")

    # The paste-able command for the Vivado Tcl Console (Windows path under WSL).
    console_cmd = f"source {to_windows_path(flow_tcl)}"

    print(f"Vivado project generated at: {work_root}")
    print("Open in Vivado GUI with: vivado <work_root>/nzea_fpga.xpr")
    print("or run the build chain with: make -C <work_root>")
    print(f"\nPaste into the Vivado Tcl Console to run the full flow:")
    print(f"    {console_cmd}")


if __name__ == "__main__":
    sys.exit(main())
