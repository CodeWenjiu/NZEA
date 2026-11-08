#!/bin/sh
set -eu

GEM5=${GEM5:-/workspace/gem5-lab/build/X86/gem5.opt}
LAB=${LAB:-/workspace/gem5-lab/lab2}
OUT=${OUT:-$LAB/results22}

run_one() {
    b=$1
    c=$2
    case $b in
        compute) bin=$LAB/bench_compute; args=500000 ;;
        seq)     bin=$LAB/bench_seq;     args=1048576 ;;
        rand)    bin=$LAB/bench_rand;    args=524288 ;;
        *) echo "unknown benchmark: $b" >&2; exit 1 ;;
    esac
    case $c in
        w8-1g-nol2)      opts="--width 8 --freq 1GHz --l2-size none" ;;
        w8-4g-nol2)      opts="--width 8 --freq 4GHz --l2-size none" ;;
        w2-1g-nol2)      opts="--width 2 --freq 1GHz --l2-size none" ;;
        w8-1g-l2-256k-8) opts="--width 8 --freq 1GHz --l2-size 256KiB --l2-assoc 8" ;;
        w8-1g-l2-256k-4) opts="--width 8 --freq 1GHz --l2-size 256KiB --l2-assoc 4" ;;
        w8-1g-l2-2m-8)   opts="--width 8 --freq 1GHz --l2-size 2MiB --l2-assoc 8" ;;
        w8-1g-l2-16m-8)  opts="--width 8 --freq 1GHz --l2-size 16MiB --l2-assoc 8" ;;
        *) echo "unknown config: $c" >&2; exit 1 ;;
    esac

    dir=$OUT/$b/$c
    mkdir -p "$dir"
    echo "== $b $c"
    "$GEM5" -d "$dir" "$LAB/lab22.py" $opts --binary "$bin" --args "$args"
}

for b in ${BENCHES:-compute seq rand}; do
    for c in ${CONFIGS:-w8-1g-nol2 w8-4g-nol2 w2-1g-nol2 w8-1g-l2-256k-8 w8-1g-l2-256k-4 w8-1g-l2-2m-8 w8-1g-l2-16m-8}; do
        run_one "$b" "$c"
    done
done
