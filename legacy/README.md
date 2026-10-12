# legacy/ — frozen pre-migration core (reference only)

The Chisel/Scala 2.13 implementation of the whole core, moved here when the
rewrite onto Scala 3 / [zaozi](https://github.com/xinpian-tech/zaozi) started.

**It is not part of the build.** No module in `build.mill` points at these
sources, so they are never compiled and never appear as BSP targets —
editors show no diagnostics and no language server work is spent on them. The
files are kept as reference material and are deleted once the rewrite lands.

## Layout

| Directory | Content |
|-----------|---------|
| `legacy/nzea_{rtl,config,core,cache,device,tile,cli}/` | Chisel sources + ScalaTest suites |
| `legacy/nzea_fpga/` | FPGA board wrappers, constraints/MIG assets, nu scripts |
| `legacy/nzea_sim/` | Tile/fpga simulation elaboration, Verilog TB, hex programs, nu scripts |

Assets that the rewrite still needs (the iverilog testbench under
`legacy/nzea_sim/sim/`, board constraints under `legacy/nzea_fpga/src/boards/`)
are recoverable from here with a plain `git mv` when they are picked up again.

## Running the old flow

The complete, buildable pre-migration repository is frozen at tag
`legacy-baseline`. To run it, materialize it in a side worktree:

```sh
git worktree add .oracle legacy-baseline
cd .oracle && nix develop     # its own flake pins JDK + mill + CIRCT
just iv tile yosys riscv32i   # the legacy commands work there unchanged
cd .. && git worktree remove --force .oracle   # back at the root, delete it again
```

Use it to re-measure a baseline (IPC, WNS, cell counts), to generate reference
RTL for comparison, or to run the FPGA flow while the rewrite is in flight.
To compare one file against its frozen predecessor while porting it:
`git diff --no-index legacy/<path> <path>`.

## Rules

- Do not edit files under `legacy/`: fixes go into the rewrite. If a legacy bug
  must be fixed for oracle purposes, fix it in `.oracle/` and re-freeze with a
  new tag.
- Do not reference `legacy/` paths from the new tree's sources or build files.
- Delete `legacy/` (and the `legacy-baseline` tag stays as history) when the
  rewrite passes the full gate.
