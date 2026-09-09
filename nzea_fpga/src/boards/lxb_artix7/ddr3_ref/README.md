# ddr3_ref — MicroPhase A7-Lite DDR3 reference (21_ddr3_test)

Reference only. Copied from the MicroPhase A7-Lite `21_ddr3_test` Vivado
example (`Copyright (c) 2022 by MicroPhase Technologies Inc.`); kept here as
the authoritative source for DDR3 integration on the lxb_artix7 board.

Contents:

- `mig_ddr3.xdc` — DDR3 pin/I/O constraints (generated from `mig_b.prj`).
- `srcs/sources_1/ip/mig_ddr3/` — MIG 7-series IP source; actual configuration
  lives in `mig_b.prj` (MT41K256M16XX-107, 16-bit DQ, 400 MHz memclk, 4:1
  PHY → 128-bit native app interface at 100 MHz ui_clk, NATIVE, 15-row/10-col/3-bank).
- `srcs/sources_1/ip/clock/` — clk_wiz (50 MHz → 200 MHz sys_clk for MIG).
- `srcs/sources_1/ip/ila_top/` — example ILA IP (probes on the app interface).
- `srcs/sources_1/new/` — example design RTL: `ddr3_ctrl` wraps MIG and
  exposes a wr/rd-separated flow-control interface; `ddr3_loop_test` ties it
  to a data generator + ILA. Interface timing reference only — the nzea
  bridge (`DdrBridge`) target the raw MIG `app_*` interface instead.
- `srcs/sim_1/new/tb_ddr3_loop_test.v` — example testbench (Vivado sim).
- `sim/` — Micron DDR3 PHY-level model (`ddr3_model.sv` + parameters +
  `wiredly.v`), the official simulation target for MIG (`example_design/sim`).

Integration plan (see `goal/ddr3-bus-device.md`): DDR becomes an additional
LiteBus fabric slave via `DdrBridge` (32-bit LiteBus ⇄ 128-bit MIG app),
keeping the BRAM boot path untouched.