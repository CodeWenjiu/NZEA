#!/usr/bin/env nu

# Simulate the DDR3 adapter against the REAL generated MIG IP, using Vivado's
# own simulator (xsim).
#
# Why this exists: the fpga platform has no iverilog coverage, so anything that
# touches a Xilinx IP could only be checked on the board, at 10-20 minutes per
# iteration. This is the alternative for protocol questions. It is NOT a daily
# test - it needs Vivado, and it runs in minutes rather than seconds, so the
# fast Chisel unit tests in nzea_device stay as they are.
#
# What is simulated: production Ddr3Subsystem + Ddr3Adapter, driven by a small
# write/read/compare FSM, against the MIG RTL that Vivado generates from
# ddr3_ref/mig_b.prj and the official Micron ddr3_model. The vendor's own
# sim_tb_top.v is reused verbatim except for the name of the module it
# instantiates, so clocking, reset polarity, PCB propagation delays and the
# memory-model instantiation all remain vendor code (no hand-written IP model).
#
# Usage:
#   nu nzea_fpga/scripts/vivado-sim.nu [--board lxb_artix7] [--isa riscv32im] [--dump]
#
# --dump writes ddr3_cosim.vcd for pulse; it is off by default because the
# adapter's internals are only interesting when something failed.

const batch_nix = (path self . | path join "vivado-batch.nix")

# Run a bash snippet inside the batch Vivado FHS environment.
# Same invocation pattern as ila-capture.nu: nix-shell's runScript is an
# interactive bash, so the commands are piped in on stdin.
def run-in-vivado [script: string] {
    $script | ^nix-shell $batch_nix
}

def main [
    --board: string = "lxb_artix7"
    --isa: string = "riscv32im"
    --dump
] {
    let hw = $"build/fpga/($board)/($isa)/hw" | path expand
    if not ($hw | path exists) {
        print $"ERROR: no FPGA RTL at ($hw)"
        print $"Run: just dump --target fpga --fpgaBoard ($board) --isa ($isa)"
        exit 1
    }

    let vivado_dir = $hw | path join "vivado"
    let mig = $vivado_dir | path join "nzea_fpga.gen/sources_1/ip/mig_ddr3/mig_ddr3"
    let sim_dir = $mig | path join "example_design/sim"
    let user_rtl = $mig | path join "user_design/rtl"
    let board_sim = $"nzea_fpga/src/boards/($board)/sim" | path expand
    let wrapper = $board_sim | path join "nzea_ddr3_sim_top.v"

    if not ($wrapper | path exists) {
        print $"ERROR: simulation wrapper not found at ($wrapper)"
        exit 1
    }

    # The MIG IP is generated when the Vivado project is created, which is a
    # one-off step far cheaper than a build. Do it here if a previous dump has
    # not already produced it.
    if not ($user_rtl | path exists) {
        if not ($vivado_dir | path join "nzea_fpga.tcl" | path exists) {
            print $"ERROR: no Vivado project script in ($vivado_dir) - re-run just dump"
            exit 1
        }
        print "MIG IP not generated yet - creating the Vivado project (one-off)..."
        run-in-vivado $"set -e
cd ($vivado_dir)
vivado -mode batch -source nzea_fpga.tcl -nolog -nojournal
exit
"
        if not ($user_rtl | path exists) {
            print "ERROR: project creation did not produce the MIG user_design RTL"
            exit 1
        }
    }

    # ---------------------------------------------------------- work dir
    let work = $hw | path join "xsim"
    rm -rf $work
    mkdir $work

    # The vendor TB, with only the instantiated module name changed. Keeping the
    # rest of the file untouched is what makes the memory-model wiring and the
    # propagation delays trustworthy.
    let tb = (
        open --raw ($sim_dir | path join "sim_tb_top.v")
        | str replace "example_top #" "nzea_ddr3_sim_top #"
    )
    $tb | save --force ($work | path join "sim_tb_top.v")
    if not ($tb | str contains "nzea_ddr3_sim_top #") {
        print "ERROR: failed to patch example_top out of sim_tb_top.v"
        exit 1
    }

    # Memory-model geometry defines come from the vendor's own xsim project file
    # rather than being hardcoded, so they follow the MIG configuration.
    let vendor_prj = $sim_dir | path join "xsim_files.prj"
    let model_defines = (
        open --raw $vendor_prj
        | lines
        | where {|l| $l =~ "ddr3_model.sv" }
        | first
        | parse -r 'ddr3_model\.sv(?<defs>.*)$'
        | get defs.0
        | str trim
    )

    # MIG RTL: everything under user_design/rtl except mig_ddr3_mig.v, which
    # defines the same module name as mig_ddr3_mig_sim.v (the simulation variant
    # with the fast calibration bypass). The vendor project file compiles only
    # the _sim variant, so do the same.
    let mig_files = (
        glob $"($user_rtl)/**/*.v"
        | where {|f| not ($f | str ends-with "mig_ddr3_mig.v") }
        | sort
    )

    let prj_lines = ([
        # Xilinx global reset/initialisation; env var is expanded by xvlog.
        'verilog work $XILINX_VIVADO/data/verilog/src/glbl.v'
        $"sv work ($sim_dir | path join 'ddr3_model.sv') ($model_defines)"
        $"verilog work ($sim_dir | path join 'wiredly.v')"
        $"verilog work ($wrapper)"
        $"sv work ($hw | path join 'Ddr3Subsystem.sv')"
        $"sv work ($hw | path join 'Ddr3Adapter.sv')"
        $"verilog work ($work | path join 'sim_tb_top.v')"
    ] ++ ($mig_files | each {|f| $"verilog work ($f)" }))

    $prj_lines | str join "\n" | save --force ($work | path join "files.prj")

    let plusargs = (if $dump { "-testplusarg dump" } else { "" })

    print $"Simulating DDR3 against the real MIG under xsim in ($work)"
    print $"  MIG RTL files: ($mig_files | length)"
    print $"  memory model defines: ($model_defines)"
    if $dump { print "  waveform: ddr3_cosim.vcd (adapter internals only)" }

    # firtool emits no timescale, so xelab needs one for the modules that lack it.
    # The vendor TB is 1ps/100fs; matching it keeps every delay in one unit.
    run-in-vivado $"set -e
cd ($work)
xvlog -prj files.prj
xelab work.sim_tb_top work.glbl -prj files.prj -L unisims_ver -L secureip -s ddr3_cosim --timescale 1ps/100fs -debug typical
echo 'run all
quit' > run.tcl
xsim ddr3_cosim -tclbatch run.tcl ($plusargs)
exit
"

    if $dump {
        let vcd = $work | path join "ddr3_cosim.vcd"
        if ($vcd | path exists) {
            print $"Waveform ready for pulse: ($vcd)"
        } else {
            print "WARNING: --dump was set but no ddr3_cosim.vcd was produced"
        }
    }
}
