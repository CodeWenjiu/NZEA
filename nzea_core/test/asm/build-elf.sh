#!/usr/bin/env bash
# Assemble a core-level difftest program (`nzea_core/test/asm/<name>.S`) for one ISA into an ELF under
# `build/asm/`. Invoked by the `asm-elf` recipe; the run belongs to remu's own shell (the program's header
# carries the command).
set -euo pipefail
name="$1"
target="${2:-riscv32im}"
src="nzea_core/test/asm/$name.S"
dir="build/asm"
mkdir -p "$dir"
out="$dir/$name-$target"
case "$target" in
riscv32*) triple=riscv32-unknown-none-elf; march=rv32im; abi=ilp32 ;;
riscv64*) triple=riscv64-unknown-none-elf; march=rv64im; abi=lp64 ;;
*) echo "asm-elf: unknown target '$target' (want riscv32* or riscv64*)" >&2; exit 1 ;;
esac
clang -target "$triple" -march="$march" -mabi="$abi" -mno-relax -c "$src" -o "$out.o"
printf 'OUTPUT_ARCH(riscv)\nENTRY(_start)\nSECTIONS {\n  . = 0x80000000;\n  .text : { *(.text*) }\n  .rodata : { *(.rodata*) }\n}\n' > "$out.ld"
rust-lld -flavor gnu -T "$out.ld" -o "$out.elf" "$out.o"
echo "built $out.elf"
