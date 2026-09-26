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
#   nu nzea_fpga/scripts/vivado-sim.nu [--board lxb_artix7] [--isa riscv32im]
#                                     [--dump] [--tb <NAME>] [--tb-list]
#                                     [--tb-args "..."]
#
# --tb <NAME> selects the cosim testbench wrapper: it must be the module name
# of a file <NAME>.v in nzea_fpga/src/boards/<board>/sim/. The vendor
# sim_tb_top.v instantiates that module in place of example_top, so every
# wrapper must keep example_top's parameter/port list (clocks, resets, delays
# and the memory-model instantiation stay vendor code). --tb-list enumerates
# the available wrappers. Default: nzea_ddr3_sim_top.
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
    --tb: string = "nzea_ddr3_sim_top"
    --tb-list
    # Extra xsim command-line args, passed verbatim - most useful for TB
    # plusargs, e.g. --tb-args "-testplusarg GAP=0 -testplusarg PHASE=3000".
    --tb-args: string = ""
] {
    # Wrapper inventory first: --tb-list works without a dumped build tree.
    let board_sim = $"nzea_fpga/src/boards/($board)/sim" | path expand
    let wrappers = (
        glob ($board_sim | path join "*.v")
        | each {|f| $f | path parse | get stem }
        | sort
    )
    if $tb_list {
        print "Cosim testbench wrappers (--tb <name>):"
        for w in $wrappers { print $"  ($w)" }
        exit 0
    }
    if ($wrappers | length) == 0 {
        print $"ERROR: no wrapper .v files in ($board_sim)"
        exit 1
    }
    let wrapper = $board_sim | path join $"($tb).v"
    if not ($wrapper | path exists) {
        print $"ERROR: wrapper '($tb)' not found - available: ($wrappers | str join ', ')"
        exit 1
    }

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
    # propagation delays trustworthy. (tb_text, not tb: the --tb parameter is a
    # variable named $tb and a let in the same scope would shadow it.)
    let tb_text = (
        open --raw ($sim_dir | path join "sim_tb_top.v")
        | str replace "example_top #" $"($tb) #"
    )
    $tb_text | save --force ($work | path join "sim_tb_top.v")
    if not ($tb_text | str contains $"($tb) #") {
        print $"ERROR: failed to patch example_top out of sim_tb_top.v (selected tb: ($tb))"
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
    #
    # Why not read the vendor's xsim_files.prj as the authoritative list? It
    # also lists the example-design traffic generator and example_top files,
    # which we deliberately replace with our own wrapper (the vendor TB with
    # only the instantiated module name changed). Glob over user_design/rtl is
    # the subset we actually need; nushell glob descends symlinks, and the
    # build is verified by cosim compiling all 67 files.
    let mig_files = (
        glob $"($user_rtl)/**/*.v"
        | where {|f| not ($f | str ends-with "mig_ddr3_mig.v") }
        | sort
    )

    # C9 guard: the cosim relies on Ddr3Subsystem being PURE WIRING around the
    # MIG blackbox (firtool removes its clock/reset ports — see the wrapper
    # comment "do not connect any"). If it ever grows internal state (always
    # blocks / registers), that assumption breaks and the cosim silently
    # produces wrong results. Fail loudly instead: check the generated SV has
    # no sequential logic before compiling it.
    let ddr3_sub = $hw | path join "Ddr3Subsystem.sv"
    let sub_has_seq = (
        open --raw $ddr3_sub
        | lines
        | any {|l| $l =~ "always" }
    )
    if $sub_has_seq {
        print $"ERROR: ($ddr3_sub) contains sequential logic (always block) — the cosim "
        print "assumes Ddr3Subsystem is pure wiring around the MIG blackbox. If it now"
        print "has internal state, the wrapper's 'do not connect any' assumption is broken;"
        print "the cosim would silently simulate wrong behavior. Fix the wrapper first."
        exit 1
    }

    let prj_lines = ([
        # Xilinx global reset/initialisation; env var is expanded by xvlog.
        'verilog work $XILINX_VIVADO/data/verilog/src/glbl.v'
        $"sv work ($sim_dir | path join 'ddr3_model.sv') ($model_defines)"
        $"verilog work ($sim_dir | path join 'wiredly.v')"
        $"verilog work ($wrapper)"
        $"sv work ($hw | path join 'Ddr3Subsystem.sv')"
        $"sv work ($hw | path join 'Ddr3Adapter.sv')"
        $"verilog work ($work | path join 'sim_tb_top.v')"
    ] ++ ($mig_files | each {|f| $"verilog work ($f)"}))

    $prj_lines | str join "\n" | save --force ($work | path join "files.prj")

    let plusargs = (if $dump { "-testplusarg dump" } else { "" })

    print $"Simulating DDR3 against the real MIG under xsim in ($work)"
    print $"  wrapper: ($tb)"
    print $"  MIG RTL files: ($mig_files | length)"
    print $"  memory model defines: ($model_defines)"
    if $dump { print "  waveform: ddr3_cosim.vcd (adapter internals only)" }

    # firtool emits no timescale, so xelab needs one for the modules that lack it.
    # The vendor TB is 1ps/100fs; matching it keeps every delay in one unit.
    # `timeout 600` guards the xsim run: a hung sim (no $finish, watchdog missed)
    # would otherwise block the recipe forever.
    run-in-vivado $"set -e
cd ($work)
xvlog -prj files.prj
xelab work.sim_tb_top work.glbl -prj files.prj -L unisims_ver -L secureip -s ddr3_cosim --timescale 1ps/100fs -debug typical
echo 'run all
quit' > run.tcl
timeout 600 xsim ddr3_cosim -tclbatch run.tcl ($plusargs) ($tb_args)
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
