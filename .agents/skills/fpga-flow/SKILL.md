---
name: fpga-flow
description: Run the lxb_artix7 FPGA pipeline headlessly — dump the board's RTL, generate its Vivado project (edalize), build the bitstream, program the board over JTAG, and capture an ILA window for pulse. Use when building or programming the board, when Vivado is not found or fails to start, or when an on-board capture is needed; it holds the FHS nix-shell environment and the batch invocation quirks.
---

# fpga-flow

Run the FPGA pipeline for the lxb_artix7 board headlessly: generate RTL → build a bitstream (Vivado synth +
impl) → program the board over JTAG → capture an ILA window for pulse. This skill captures the environment
knowledge (Vivado is not on PATH, runs in an FHS nix shell) and the exact commands.

**Current state (2026-10-12)**: the board top is the bring-up design — `nzea_fpga/src/Top.scala` (`Top`, module
`Top`) = the clocking wizard + two blinkers, watched by an ILA debug core the netlist flow creates — so the pipeline below
ends at "the LEDs blink and an ILA window is readable". The tile (and with it the
UART boot loader, `just probe load`/`reset`/`monitor`, and the reset-triggered ILA mode) is not on the
board yet; those recipes come back when it is.

## The pipeline (one command per stage)

```sh
# 1. Dump the board's RTL — the main dump, `--target fpga` (board and isa default to lxb_artix7 / riscv32i).
#    The ILA is part of the board design, not a flag.
just dump --target fpga

# 2. Build the bitstream (synth + impl) — headless, ~5 min on this machine.
just fpga build

# 3. Program the FPGA over JTAG — board must be connected via the Digilent JTAG cable.
just fpga program

# 4. Capture an ILA window (board programmed with an ila build) and analyze it.
just fpga capture                                 # trigger immediately (default)
just fpga capture lxb_artix7 riscv32i 'led1=0'    # or arm on one probe's compare value
pulse --wave build/fpga/lxb_artix7/riscv32i/vivado/capture.named.vcd info

# No board needed: a scenario under xsim, against the IP's official models.
just fpga sim
```

`just fpga build` regenerates the Vivado project from the dumped RTL first — the project's file list follows the
dump — so the only RTL step to remember is `just dump --target fpga`. To regenerate the project alone, the
underlying `nzea_fpga/scripts/vivado-project.nu` can still be run directly.

Success signals: `just fpga build` ends with `Bitstream generation completed` and writes
`build/fpga/lxb_artix7/<isa>/vivado/nzea_fpga.bit`; `just fpga program` prints
`INFO: FPGA xc7a200tfbg484-2 programmed.`; on the board, LED1 blinks (50 MHz domain, ~0.7 Hz) and LED2 blinks
(the wizard's 100 MHz domain, ~3 Hz) — **if only LED1 blinks, the wizard's IP or its clock path is at fault**, which is why
there are two. A capture tells the same story without eyes: `reset` 0, `locked` 1, and the two counters counting —
`blink50_count` +1 per sample (50 MHz exactly), `blink100_count` +2 per sample (its 100 MHz domain seen through the
50 MHz trigger clock) — so the domain whose counter is not counting is the domain whose clocking is at fault.

## Environment knowledge (critical, non-obvious)

Vivado 2023.2 is **not on PATH** and will not run from a plain shell:

- Install root: `/mnt/data/tools/Xilinx/Vivado/2023.2`
- It is a Linux binary with a `/bin/bash` shebang, but NixOS has no `/bin/bash`, so it must run inside an FHS
  environment: `nix-shell nzea_fpga/scripts/vivado-batch.nix`
- `vivado-batch.nix` is a copy of the Xilinx `shell.nix` (full library set) with the **blocking GUI launch
  stripped** from the profile. The original `shell.nix` ends with a bare `vivado` that starts the GUI and blocks
  forever — never use it for batch work.

### How to drive Vivado headlessly

`nix-shell --run` / `--command` are **unreliable** under this FHS env (the `runScript` is an interactive bash).
The working pattern is to **pipe commands via stdin**, which is what `vivado-flow.nu` / `ila-capture.nu` do:

```bash
nix-shell nzea_fpga/scripts/vivado-batch.nix <<NIXEOF
cd "$VIVADO_DIR"
vivado -mode batch -source build_all.tcl -nolog -nojournal
NIXEOF
```

Always use `-mode batch -nolog -nojournal`; batch exits after the script runs.

## Generated artifacts

A board's build tree follows the one layout rule every level's dump uses — `build/<target>/<platform>/<isa>` — with the
board as the platform: `build/fpga/lxb_artix7/<isa>/`. Under it:

| Path | Written by | Purpose |
|------|-----------|---------|
| `*.sv`, `filelist.f` | `just dump --target fpga` (firtool) | the design's RTL and its file list |
| `layers-*.sv` | same, only when the design has layer blocks | the `bind` fragments; the dump adds the roots to `filelist.f` (see the probe section) |
| `Bufg.sv` | `just dump --target fpga` (`Bufg.emitModules`) | the global clock buffer's body, listed in `filelist.f` |
| `ila_debug.tcl`, `ila_capture.tcl`, `ila_probes.map` | same (`Ila.emit`) | the debug script (what the impl run sources), the capture script and the VCD rename map |
| `ip_*.tcl` | same (`ClockingWizard.emit`) | one snippet per instantiated vendor IP, listed in `ipfiles.f` |
| `simfiles.f` | same (`ClockingWizard.emit`) | the IP's simulation sources, paths under the generated-IP root (see the xsim section) |
| `vivado/build_all.tcl` | `just fpga build` (its first step, edalize) | one-shot: project → synthesis → impl → bitstream |
| `vivado/program.tcl` | same | JTAG programming, attaching the impl's `.ltx` probes file |
| `vivado/nzea_fpga.bit` | `build_all.tcl` | the bitstream |
| `vivado/nzea_fpga.runs/impl_1/*.ltx` | impl | ILA probes file (without it the hw manager cannot see the ILA core) |
| `vivado/capture.vcd`, `capture.named.vcd` | `just fpga capture` | the raw and probe-renamed capture |
| `xsim/ipgen/` | `just fpga sim` (when needed) | the flow's own copy of the IP project, keyed by the snippets — simulating never touches the board's tree |
| `xsim/<name>/` | `just fpga sim` | a scenario's work dir: `files.prj`, `xsim.log`, `<name>.vcd` |

Both `build_all.tcl` and `program.tcl` `cd` to the work root first (edalize's scripts assume cwd = work root,
e.g. run.tcl copies the bitstream to `[pwd]/nzea_fpga.bit`), so they work wherever Vivado is launched.

**Pitfall: `just fpga build` does NOT regenerate RTL.** It regenerates the Vivado project and synthesizes the
`.sv` files already under `build/fpga/.../`. After any Scala/zaozi change you must re-run `just dump --target fpga`
first, or you build a bitstream from stale RTL.

## IP: how it is created, and where the facts live

The project is generated by `nzea_fpga/tools/vivado/vivado-project.py` (**edalize**, run through `uv`), which:

- reads the RTL through the dump's `filelist.f` (not a directory glob — a glob would pick up modules a previous
  dump left behind);
- `source`s the vendor-IP snippets the dump listed in `ipfiles.f`;
- writes `build_all.tcl` and `program.tcl`.

The IPs themselves are generated by the modules that instantiate them (`nzea_fpga/src/ip/`: `ClockingWizard.scala`):
`instantiate` records the parameter in a registry, `emit` writes `ip_<name>.tcl` at dump time and adds
it to `ipfiles.f`, so the IP's ports and its configuration cannot disagree with the RTL. `ClockPlan`
(`inMHz:outMHz[,…]`) is the clocking fact, and the **primitive is derived from it**, never stated: the PLL when a PLL
configuration expresses the plan exactly (a small integer search against the PLL's own ranges), the MMCM — the superset —
otherwise; the board's 50 → 100/200 plan is an exact integer ratio, so its IP is a PLL. The module is named after the IP
(`clk_wiz_0`), because a `VerilogWrapper` only merges with the generated IP when `moduleName` and `verilogModuleName` are
equal.

**The clock path is the design's, and it matters** (`Top.scala`): the oscillator feeds the wizard's input *as it is* —
a clock generator's input wants the IO buffer's own path, which is what its compensation is about — and the design's own
logic (both blinkers, the reset synchronizer, the ILA's clock) runs off a **global buffer** of it (`nzea_fpga/src/
primitives/Bufg.scala`, a hand-written `BUFG` body the dump emits like `SyncRam`'s). Letting the tool insert that buffer
instead puts it on the generator's input too, and a **PLL then fails the IO-clock DRC** (`REQP-1712: ... with
COMPENSATION mode ZHOLD must be driven by a clock capable IO`) — the pin itself is fine (J19 is an MRCC); the buffer on
the input path is not. The MMCM's rule tolerates it, which is how the design got away with it until the plan derived a
PLL.

The ILA is **not** an IP here: it is a debug core the implementation run creates on the synthesized netlist (see
below), so nothing about it goes through the IP machinery.

## ILA: probes, capture, and pulse

- **The ILA is a debug core the netlist flow creates, not RTL.** The RTL only states what to watch: a signal is
  *declared* where it lives — `Probes.declare("<name>")(<value>)` (`nzea_rtl.probe`, one line in the owning module,
  e.g. `Blink`'s counter in `nzea_device`) — and *bound* where the instance is named,
  `Probes.watch(<instance>, "<name>")`, which makes one probe of that instance, shown as `<instance>_<name>`.
  Each declaration keeps a wire named `probe_<name>` carrying `(* mark_debug = "true" *)` (what Vivado preserves a net
  by) and takes the value's **own width**;
  `Probes.mark("<name>")(<value>)` is the one-line form of both, for a signal whose instance is known where it lives
  (the top's own). Current probes: `reset, locked, led1, led2` (top-level marks) and `blink50_count, blink100_count`
  (the blinkers' counters, declared in `Blink`, bound per instance), depth 1024, sampled on the design's buffered
  50 MHz clock (kept as the wire `ila_clock`).
- **Declarations are unconditional; the consumer is the level's.** They are collected in a dump-level table
  (`nzea_rtl.probe.Probes`, cleared by `DumpRegistry` like the rest of a dump's state), and the fpga level's consumer
  is `nzea_fpga.ip.Ila` — `Ila("<core>", <depth>)`, `Ila.clock(<net>)` for the anchor, `Ila.emit(<dir>)` for the
  three artifacts. A level with no consumer simply carries the wires; a future consumer (a simulator, another vendor's
  flow) reads the same `Probes.table` its own way.
- **Why two steps**: a module body is shared by every instance, so it cannot say *which* instance a probe means — the
  binding is stated where the instance is named. Declaration names are one namespace and must be unique in the design.
- **A declaration can sit beside its signal at any depth — no port threading — which is the point of the mechanism.** The
  layer/probe mechanism (`nzea_rtl/test/src/probe/ProbeLayerTest.scala` pins it) is *not* usable for a board ILA: it
  lowers to SystemVerilog `bind`, and **Vivado synthesis silently drops `bind`** — the bound module never reaches the
  netlist (verified with a minimal `dont_touch`-marked tap that came out of `synth_design` as nothing, and by a board
  build whose only clue was `CRITICAL WARNING: Could not find module 'u_ila_0'`). The fpga test asserts the board dump
  contains no `bind` and no RTL ILA instance.
- **The debug script is the dump's `ila_debug.tcl`, and `build_all.tcl` attaches it to the implementation run** as
  `STEPS.OPT_DESIGN.TCL.PRE` on `impl_1` (presence-driven: no file, no hook). The impl run sources it with the
  *synthesized* netlist open, where `create_debug_core` exists; the core is implemented by the run itself (opt_design
  implements debug cores automatically — do **not** call `implement_debug_core` there, it demands a saved design).
- **The script finds every net by name, in the mode the binding made**: a marked signal by leaf through the hierarchy
  (`get_nets -hier` — which is what lets a declaration sit at any depth), a bound one at the instance's own level by
  path (`get_nets` *without* `-hier`, e.g. `*blink50/probe_count*`; `-hier` matches leaf names only, so a path is the
  only way to tell a shared body's instances apart). A declared bus survives synthesis bit by bit
  (`probe_count[0]` … `[25]`) and is connected in index order; the clock is kept as its own wire (`ila_clock`, the
  sampled domain's net) and resolved like a mark. A search that does not come back with the number of nets the width
  declares fails the build loudly (`ILA: '…' matched N nets`), never silently.
- `just fpga capture` drives the dump-generated `ila_capture.tcl` in the same batch FHS env: it finds the part,
  attaches the impl `.ltx` (required — without it `get_hw_ilas` is empty), sets `TRIGGER_POSITION` to `depth/4`,
  and triggers — immediately by default, or armed on one probe's compare value
  (`just fpga capture lxb_artix7 riscv32i '<probe>[=<value>]'`; the probe is the semantic name, resolved through
  `ila_probes.map` to the manager's net name, the value decimal or `0x…`, default 1) — uploads the window and
  writes `capture.vcd`. Then `probe vcd-rename` applies `ila_probes.map` → `capture.named.vcd`.
- **The rename map keys on the declared nets, not on `probeN`.** Vivado's export names the probes after the *nets* the
  core samples (`reset` for `probe_reset`; a bound bus as `blink50/probe_count[25:0]`), so the map's net keys do the
  renaming; the bare `probeN` keys beside them catch an export that names the signal after the core's port. Every key
  is generated from the declarations, so a rename cannot make the map and the RTL drift apart.
- **pulse semantics for this export**: the VCD's timescale is `1 ps` and timestamps are per-sample (10 ns step
  at 50 MHz); there is no clock signal in the dump, so analyze with `value`/`property` at sample indices rather
  than the default posedge-clock `--on`. `_TRIGGER`/`_WINDOW`/`_GAP` marker signals are included.
- **A build that lost its ILA** now fails loudly in the impl run (the script's name search errors on 0 matches); if
  the capture says `no ILA core in the programmed device`, check the impl run's log for the `ILA:` lines and the
  `.ltx` (`nzea_fpga.runs/impl_1/*.ltx`) before blaming the capture script.
- **`REQP-1712` (IO clock DRC) points at the clock path, not at the pin.** A PLL whose `CLKIN` ends up behind *any*
  buffer fails it; the design must feed the generator's input from the IO buffer's own net (see the clock path note
  above) and buffer the fabric itself.
- Vivado 2023.2 trigger quirks (verified on-board): per-probe compare values live on the `hw_probe` object
  (`TRIGGER_COMPARE_VALUE`, e.g. `eq1'b1`); `CONTROL.TRIGGER_CONDITION` only accepts AND/OR, and edge values
  like `eq1'bF` are accepted but never fire.
- A timed-out capture can leave orphan `cs_server`/`hw_server` processes spinning at 100% CPU — kill them with
  `pkill -9 -f cs_server; pkill -9 -f hw_server` before the next build.

## xsim scenarios (IP simulation)

Board-only debug of anything touching a vendor IP is the slowest loop there is; a scenario simulates the dumped RTL
against the IP's official models under xsim. It works from the dump alone — no bitstream, no board:

```sh
just fpga sim                                    # run the default scenario (TopSmoke)
just fpga sim TopSmoke lxb_artix7 riscv32i 1     # ...and let it dump a waveform
nu nzea_fpga/scripts/xsim.nu --list              # enumerate the scenarios
```

What a scenario is (`nzea_fpga/sim/<name>.sv`, module name = file stem):

- **self-checking**: `$fatal` on failure, and print `<name>: PASS` on success — the flow fails a run whose log lacks
  that line, and the recipe fails with the flow;
- a **watchdog** (`#<n>; $fatal`) so a stuck design fails instead of hanging the recipe;
- `$test$plusargs("dump")` gating `$dumpfile("<name>.vcd")` — the waveform is opt-in (`--vcd`);
- it never hand-writes an IP model: the simulation must run the vendor's own files (that is xsim's whole point). A
  vendor TB that ships with an IP is reused verbatim, with only the name of the instantiated module changed.

The flow compiles three lists in `build/fpga/<board>/<isa>/xsim/<name>/`:

- the design: the dump's `filelist.f` (the same list the board build compiles);
- the IP's simulation sources: the dump's `simfiles.f` — one path per line, under the generated-IP root — elaborated
  with `-L unisims_ver -L secureip`. The flow generates its own copy of the IP project under `xsim/ipgen/`, keyed by
  the snippets' content (a changed snippet regenerates; nothing else does);
- the scenario itself, plus `glbl.v`.

Reading the waveform: the VCD carries the real timescale and, unlike an ILA export, every module's `clock` port —
so pulse's default clock (`posedge clock`) may pick the wrong one. Give it the scenario's own when querying:
`pulse value --wave <name>.vcd --on 'posedge CLK_50M' ...`. (An ILA export has no clock signal at all; there pulse
falls back to one cycle per sample.)

## Serial / UART (not on the board yet)

The board's UART is a CH340 (`1a86:xxxx`, driver `ch341`); prefer the stable `/dev/serial/by-id/` alias over
`/dev/ttyUSB*`, whose number changes between sessions:

```sh
ls -l /dev/serial/by-id/
# → usb-1a86_USB_Serial-if00-port0 -> ../../ttyUSB1
```

The `probe` Rust workspace (`probe/cli`, binary `probe/target/release/probe`) already implements
`load`/`reset`/`monitor`/`memread`/`vcd-rename`, but the tile's boot loader is not on the board yet, so only
`vcd-rename` is in use today (by `ila-capture.nu`). The `just probe` subcommands become useful when the tile lands.
Note: `probe` is a separate Rust workspace; `linkedProjects` in `.zed/settings.json` disables rust-analyzer
auto-discovery, so **every new Rust workspace in this repo must be listed there** or rust-analyzer will
silently not load it.

## Troubleshooting

- **`vivado` not found / bad interpreter** — you're outside the FHS env. Run through `vivado-flow.nu` /
  `ila-capture.nu`, which pipe into `nix-shell vivado-batch.nix`.
- **nix-shell hangs** — you're using the original `/mnt/data/tools/Xilinx/shell.nix` (GUI profile blocks). Use
  `vivado-batch.nix`.
- **`create_project -force` wipes runs** — expected; each build is a full rebuild.
- **Program finds no target** — board not connected / JTAG cable unplugged / hw_server not running. Check with
  `lsusb` (the Digilent cable shows as `0403:…`, the CH340 as `1a86:…`).
- **A constraint on a missing port** — an XDC line for a port the top does not declare is an error in Vivado.
  `nzea_fpga/src/boards/lxb_artix7/pins.xdc` carries only the ports the design has (the board's full pin
  map, including DDR3/UART/HDMI, is the vendor's `A7_lite.xdc` beside it, kept as reference); add lines there
  as the top grows.
- **Vivado residue files** (`vivado.jou`, `vivado_pid*.str`, `*.backup.log`) are gitignored; delete freely.
