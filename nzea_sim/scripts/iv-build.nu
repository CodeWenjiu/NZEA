#!/usr/bin/env nu
# Compile testbench + RTL with iverilog for a given target.
# Usage: nu nzea_sim/scripts/iv-build.nu <target> <platform> <isa> [hex]

def main [target: string, platform: string, isa: string, hex?: string] {
    let rtl = $"build/sim/($target)/($platform)/($isa)/hw"

    # Always regenerate RTL (sim is fast enough)
    print $"Generating RTL for ($target)/($platform)/($isa)..."
    if ($hex | is-not-empty) and ($hex | path exists) {
        ^mill --no-server nzea_sim.run $target $platform $isa $hex
    } else {
        ^mill --no-server nzea_sim.run $target $platform $isa
    }

    let iv_dir = $rtl | path join "iverilog"
    mkdir $iv_dir

    # Copy hex file: use provided hex or default hello.hex
    mut hex_buf = 256
    let hex_src = if ($hex | is-not-empty) and ($hex | path exists) {
        $hex
    } else if $target == "tile" {
        "nzea_sim/sim/tile/hello.hex"
    }
    if ($hex_src | is-not-empty) and ($hex_src | path exists) {
        cp $hex_src $iv_dir
        let hsize = (try { open $hex_src | lines | length } catch { 0 })
        if $hsize > 0 { $hex_buf = $hsize }
    }

    # Collect Verilog files
    let sim_tb = if $target == "tile" {
        (try { glob nzea_sim/sim/tile/*.sv } catch { [] })
    } else { [] };
    let rtl_sv = (try { glob $"($rtl)/*.sv" } catch { [] })

    print "Compiling with iverilog..."
    ^iverilog -g2012 -Wno-timescale -Wno-sorry -DHEX_BUF_WORDS=($hex_buf) $"-DPLATFORM=\"($platform)\"" $"-DISA=\"($isa)\"" -o ($iv_dir | path join "tb.vvp") ...$sim_tb ...$rtl_sv
}
