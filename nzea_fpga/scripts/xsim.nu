#!/usr/bin/env nu

# Simulate a scenario under xsim (Vivado's own simulator) against the IP's official models.
#
# A scenario is a self-checking testbench, `nzea_fpga/sim/<name>.sv`: it `$fatal`s on failure, prints `<name>: PASS`
# on success, and the flow fails unless that line is in the log. Three lists are compiled, each owned by whoever
# declares it:
#   - the design: the dump's `filelist.f` (the same list the board build compiles);
#   - the IP's simulation sources: the dump's `simfiles.f` (see `nzea_fpga/src/ip/`), whose entries are paths under
#     the generated-IP root — the flow generates its own copy of the IP project, so simulating never touches the
#     board's build tree and needs no build: dump, then sim;
#   - the scenario itself.
# The waveform is opt-in (`--vcd` -> the scenario's `dump` plusarg); it writes `<name>.vcd` beside its log.
#
# Usage:
#   nu nzea_fpga/scripts/xsim.nu [--name TopSmoke] [--board lxb_artix7] [--isa riscv32i]
#                                [--vcd] [--args "<extra xsim args>"] [--timeout 300] [--list]

const chips_script = (path self . | path join "chips.nu")
const batch_nix = (path self . | path join "vivado-batch.nix")
source $chips_script

# Run a bash snippet inside the batch Vivado FHS environment (stdin, same pattern as ila-capture.nu).
def run-in-vivado [script: string] {
    $script | ^nix-shell $batch_nix
}

# The xvlog project line for one source: the language by extension, plus any flags the declaring list carried.
def xvlog-line [p: string, flags: string] {
    let lang = if ($p | str ends-with ".sv") { "sv" } else { "verilog" }
    if $flags == "" { $"($lang) work ($p)" } else { $"($lang) work ($p) ($flags)" }
}

def main [
    --name: string = "TopSmoke"
    --board: string = "lxb_artix7"
    --isa: string = "riscv32i"
    --vcd                # let the scenario dump its waveform (the `dump` plusarg)
    --args: string = ""  # extra xsim arguments, passed verbatim (e.g. more -testplusarg flags)
    --timeout: int = 300 # wall-clock seconds for the xsim run; a hung scenario must fail, not hang the recipe
    --list               # enumerate the scenarios and exit
] {
    let sim_dir = ("nzea_fpga/sim" | path expand)
    let scenarios = (glob ($sim_dir | path join "*.sv") | each {|f| $f | path parse | get stem } | sort)
    if $list {
        print "xsim scenarios (--name <name>):"
        for s in $scenarios { print $"  ($s)" }
        exit 0
    }
    if not ($scenarios | any {|s| $s == $name }) {
        print $"ERROR: scenario '($name)' not found — available: ($scenarios | str join ', ')"
        exit 1
    }

    let info = board_info $board
    let rtl_dir = fpga_rtl_dir $board $isa
    if not (($rtl_dir | path join "filelist.f") | path exists) {
        print $"ERROR: no design file list at ($rtl_dir)/filelist.f — run: just dump --target fpga"
        exit 1
    }

    # ---- the IP's simulation sources ---------------------------------------------------------

    let ip_root = $rtl_dir | path join "xsim/ipgen/nzea_ipgen.gen/sources_1/ip"
    let sim_entries = if (($rtl_dir | path join "simfiles.f") | path exists) {
        open ($rtl_dir | path join "simfiles.f") | lines | each {|l| $l | str trim } | where {|l| $l != "" }
    } else { [] }
    let ip_tcls = if (($rtl_dir | path join "ipfiles.f") | path exists) {
        open ($rtl_dir | path join "ipfiles.f") | lines | each {|l| $l | str trim } | where {|l| $l != "" }
    } else { [] }

    if ($sim_entries | length) > 0 {
        if ($ip_tcls | length) == 0 {
            print $"ERROR: ($rtl_dir)/simfiles.f lists simulation sources but ipfiles.f is empty — re-dump"
            exit 1
        }
        # The generated copy is keyed by the snippets' content: the same snippets are the same IPs, and a dump that
        # changed one regenerates — a stale generated IP would otherwise be simulated silently.
        let fingerprint = ($ip_tcls | each {|t| open --raw ($rtl_dir | path join $t) } | str join "\n")
        let ipgen_dir = $rtl_dir | path join "xsim/ipgen"
        let stamp = $ipgen_dir | path join ".ipfiles"
        let declared = ($sim_entries | each {|e| $e | split row -r '\s+' | first })
        let absent = ($declared | where {|p| not (($ip_root | path join $p) | path exists) })
        let fresh = if (($stamp | path exists) and ($absent | length) == 0) {
            (open --raw $stamp) == $fingerprint
        } else {
            false
        }
        if not $fresh {
            print "Generating the IP's simulation sources (the flow's own project; the board's is left alone)…"
            rm -rf $ipgen_dir
            mkdir $ipgen_dir
            let sources = ($ip_tcls | each {|t| $rtl_dir | path join $t })
            # `generate_target all` writes the sources; `synth_ip` runs the IP's out-of-context synthesis, which is
            # what produces the simulation netlist (`*_sim_netlist.v`) this flow compiles.
            let tcl_lines = ([$"create_project -force -part ($info.part) nzea_ipgen"]
                ++ ($sources | each {|s| $"source ($s)" })
                ++ ["generate_target all [get_ips]", "synth_ip [get_ips]", "close_project", "exit"])
            $tcl_lines | str join "\n" | save --force ($ipgen_dir | path join "ipgen.tcl")
            run-in-vivado $"set -e
cd ($ipgen_dir)
vivado -mode batch -source ipgen.tcl -nolog -nojournal
exit
"
            $fingerprint | save --force $stamp
        }
        # Every declared entry must exist — a declaration the flow cannot honour is an error, not a warning.
        let missing = ($declared | where {|p| not (($ip_root | path join $p) | path exists) })
        if ($missing | length) > 0 {
            print $"ERROR: declared simulation sources missing under ($ip_root): ($missing | str join ', ')"
            exit 1
        }
    }

    # ---- the compile list --------------------------------------------------------------------

    let work = $rtl_dir | path join "xsim" $name
    rm -rf $work
    mkdir $work

    let design_lines = (open ($rtl_dir | path join "filelist.f")
        | lines | each {|l| $l | str trim } | where {|l| $l != "" }
        | each {|rel| xvlog-line ($rtl_dir | path join $rel | into string) "" })
    let sim_lines = ($sim_entries | each {|e|
        let bits = ($e | split row -r '\s+')
        let flags = if ($bits | length) > 1 { $bits | skip 1 | str join ' ' } else { "" }
        xvlog-line ($ip_root | path join ($bits | first) | into string) $flags
    })
    let tb_line = xvlog-line ($sim_dir | path join $"($name).sv" | into string) ""
    let glbl_line = 'verilog work $XILINX_VIVADO/data/verilog/src/glbl.v'

    # A scenario's own compile list (`<name>.f`): extra sources beside the scenario, one entry per line as
    # "<path> [xvlog flags]" with the path relative to the repo root, `#` starting a comment. Vendor models live
    # this way — they belong to the scenario, not to an IP emitter.
    let extra_lines = if (($sim_dir | path join $"($name).f") | path exists) {
        open ($sim_dir | path join $"($name).f")
            | lines
            | each {|l| $l | str trim }
            | where {|l| $l != "" and not ($l | str starts-with "#") }
            | each {|e|
                let bits = ($e | split row -r '\s+')
                let flags = if ($bits | length) > 1 { $bits | skip 1 | str join ' ' } else { "" }
                xvlog-line ($bits | first | path expand | into string) $flags
            }
    } else {
        []
    }

    ($design_lines ++ $sim_lines ++ $extra_lines ++ [$glbl_line $tb_line])
    | str join "\n"
    | save --force ($work | path join "files.prj")
    "run all\nquit\n" | save --force ($work | path join "run.tcl")

    let plusargs = if $vcd { "-testplusarg dump" } else { "" }

    print $"Simulating ($name) under xsim in ($work)"
    print $"  design: ($design_lines | length) sources from the dump; IP sources: ($sim_lines | length); scenario sources: ($extra_lines | length)"

    run-in-vivado $"set -eo pipefail
cd ($work)
xvlog -prj files.prj
xelab work.($name) work.glbl -prj files.prj -L unisims_ver -L secureip --timescale 1ns/1ps -debug typical -s ($name)
timeout ($timeout) xsim ($name) -tclbatch run.tcl ($plusargs) ($args) 2>&1 | tee xsim.log
exit
"

    # The verdict: the exit code is the first gate (an error in xvlog/xelab fails here), but a run that finished
    # without the scenario's PASS line is a failure too.
    let log = $work | path join "xsim.log"
    if not (($log | path exists) and ((open --raw $log) | str contains $"($name): PASS")) {
        print $"ERROR: ($name) did not pass — see ($log)"
        if ($log | path exists) { open $log | lines | last 8 | each {|l| print $"  ($l)" } }
        exit 1
    }
    print $"($name): PASS"

    if $vcd {
        let vcd_path = $work | path join $"($name).vcd"
        if ($vcd_path | path exists) {
            print $"Waveform ready for pulse: ($vcd_path)"
        } else {
            print $"WARNING: --vcd was passed but the scenario wrote no ($name).vcd"
        }
    }
}
