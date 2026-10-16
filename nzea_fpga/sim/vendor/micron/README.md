# Vendor models (simulation only)

Simulation assets that belong to scenarios, not to any IP emitter (so `simfiles.f` does not carry them; a scenario
lists them in its own `<name>.f`).

## micron/ — the DDR3 device

- `ddr3.v` — Micron's DDR3 SDRAM behavioral model, **version 1.71**, taken verbatim from the copy Vivado 2023.2
  ships in its bundled Micron IP repo:
  `$XILINX_VIVADO/data/rsb/non_default_iprepos/micron_ddr3_v2_0/hdl/ddr3.v`.
- `4096Mb_ddr3_parameters.vh` — the 4Gb parameter table the model includes (same source directory); the model
  selects it via `` `define den4096Mb ``. It must sit beside `ddr3.v` (the include resolves per-file).
- `ddr3_model.sv` — this repo's configuration wrapper: the defines MIG's own wrapper generator sets, plus the
  include. A scenario instantiates the module `ddr3` directly.

**Configured part**: MT41K256M16TW-107 (the board's D9SHG marking) — 4Gb, x16, DDR3L-1866 speed bin (`sg107`).
The scenario picks its own clock rate (tCK); `sg107` keeps the part's own timing limits.

**Updating**: keep `ddr3.v` and the parameter table byte-identical to the vendor's copy (or a newer Micron
release); the warranty header inside `ddr3.v` travels with it. Only the wrapper is ours.
