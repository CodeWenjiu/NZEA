#!/usr/bin/env nu
# FPGA build report: resource utilization, timing, warnings.
# Reads existing outputs — always runs, never skipped.

def main [build: string, top: string] {
    let stat_json   = $"($build)/($top)_stat.json"
    let report_json = $"($build)/($top)_report.json"
    let pnr_log     = $"($build)/($top)_pnr.log"
    let synth_log   = $"($build)/($top)_synth.log"

    print $"(ansi green)=== Resource Utilization ===(ansi reset)"
    if ($report_json | path exists) {
        let util = open $report_json | get utilization
        let rows = $util | transpose type data

        def sum-cat [types: list] {
            let matches = $rows | where ($it.type in $types)
            let used = $matches | each {|r| $r.data.used } | default [0] | math sum
            let avail = $rows | where ($it.type == $types.0) | first | get data.available
            {used: $used, avail: $avail}
        }

        let lut  = sum-cat ["LUT4" "MUX2_LUT5" "MUX2_LUT6" "MUX2_LUT7" "MUX2_LUT8"]
        let ff   = sum-cat ["DFF"]
        let bram = sum-cat ["BSRAM"]

        let lut_pct  = (($lut.used * 10000 / $lut.avail) | math round) / 100
        let ff_pct   = (($ff.used * 10000 / $ff.avail) | math round) / 100
        let bram_pct = (($bram.used * 10000 / $bram.avail) | math round) / 100

        [
            ["Resource", Used, Capacity, "Util%"];
            ["LUT",      $lut.used,   $lut.avail,  $"($lut_pct)%"]
            ["FF",       $ff.used,    $ff.avail,   $"($ff_pct)%"]
            ["BRAM",     $bram.used,  $bram.avail, $"($bram_pct)%"]
        ] | table | print
    } else if ($synth_log | path exists) {
        print $"(ansi yellow)No PnR report — run 'just pack' first(ansi reset)"
    } else {
        print $"(ansi yellow)No build output(ansi reset)"
    }

    print $"\n(ansi green)=== Timing ===(ansi reset)"
    if ($report_json | path exists) {
        let rpt = open $report_json
        let fmax = $rpt | get fmax | transpose clock data | first
        let freq = ($fmax.data.achieved | into float | math round --precision 2)
        print $"(ansi green)($freq) MHz(ansi reset) \(clock: ($fmax.clock)\)"
    } else if ($pnr_log | path exists) {
        print $"(ansi yellow)No PnR report JSON(ansi reset)"
    }

    print $"\n(ansi green)=== Warnings ===(ansi reset)"
    let have_synth_warn = ($synth_log | path exists) and ((open $synth_log | lines | where ($it =~ 'Warnings:') | length) > 0)
    let have_pnr_warn   = ($pnr_log | path exists) and ((open $pnr_log | lines | where ($it =~ '^\d+ warnings?') | length) > 0)

    if ($have_synth_warn) {
        let raw = open $synth_log | lines
        let sw = $raw | where ($it =~ 'Warnings:') | first | split row ' ' | get 1
        print $"Synthesis: (ansi yellow)($sw)(ansi reset)"
    }
    if ($have_pnr_warn) {
        let raw = open $pnr_log | lines
        let pw_line = $raw | where ($it =~ '^\d+ warnings?') | first
        let pw = $pw_line | split row ',' | get 0 | split row ' ' | get 0 | str trim
        let pe = $pw_line | split row ',' | get 1 | str trim | split row ' ' | get 0
        print $"Place & Route: (ansi yellow)($pw) | (ansi red)($pe)(ansi reset)"
    }
    if (not $have_synth_warn and not $have_pnr_warn) {
        print $"(ansi green)No warnings(ansi reset)"
    }
}
