#!/bin/sh
set -eu

GEM5=${GEM5:-/workspace/gem5-lab/build/X86/gem5.opt}
LAB=${LAB:-/workspace/gem5-lab/lab2}
OUT=${OUT:-$LAB/results}

ORIG=202608013282144
REV=441282310806202
BIN=$LAB/factor

run() {
    name=$1
    shift
    mkdir -p "$OUT/$name"
    "$GEM5" -d "$OUT/$name" "$LAB/lab21.py" "$@"
}

run base \
    --cpu TimingSimpleCPU --freq 1GHz --mem DDR3_1600_8x8 \
    --binary "$BIN" --args "$ORIG"
run base-rev \
    --cpu TimingSimpleCPU --freq 1GHz --mem DDR3_1600_8x8 \
    --binary "$BIN" --args "$REV"
run minor \
    --cpu MinorCPU --freq 1GHz --mem DDR3_1600_8x8 \
    --binary "$BIN" --args "$REV"
run 2ghz \
    --cpu TimingSimpleCPU --freq 2GHz --mem DDR3_1600_8x8 \
    --binary "$BIN" --args "$REV"
run ddr32133 \
    --cpu TimingSimpleCPU --freq 1GHz --mem DDR3_2133_8x8 \
    --binary "$BIN" --args "$REV"
