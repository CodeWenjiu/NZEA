# Debug scaffolding: what can come out after bring-up

An inventory, not a decision. The DDR3 bring-up is done and board-validated, so the parts below — the pieces that
exist to *observe* and to *exercise* the design — could go. Each entry says what it is, what depends on it, and what has
to be updated with it. Nothing here is removed yet.

## Before removing anything

1. **None of this is committed yet.** The DDR3 tree (`src/device/ddr3/`), the simulation scenarios (`sim/Ddr3*.sv`), the vendor
   models (`sim/vendor/`), `XdcRegistry`, `BoardXdc`/`Ddr3Xdc` and the probe test are untracked or uncommitted as of
   this writing. Deleting before a commit is unrecoverable — commit first, then prune.
2. **The ILA is the board's only observation channel.** Every board-side number in `src/device/ddr3/README.md` (the 1.8e9
   rounds / 0 misses soak, the locked tap) came through it, and simulation cannot answer the same questions: xsim has no
   sampling eye, so it cannot say *which* delay tap is centred (see `README_zh.md`, "the board is the authority"). If the
   memory is used later (the tile's port), the first step of any DDR3 diagnosis would be re-adding the ILA.
3. Prefer a **build-level off switch** over source deletion where it gets the same result (see A, last row).

## A. The observation channel (probes, ILA, capture flow)

Self-contained: no functional logic depends on it.

| What | Where | Notes |
|---|---|---|
| The debug core | `src/ip/Ila.scala`; opened in `src/Top.scala` (`Ila("u_ila_0", 1024)`, `ila.clock(clk50)`) | the three artifacts: `ila_debug.tcl`, `ila_capture.tcl`, `ila_probes.map` |
| The probe statements | `nzea_rtl/src/stdlib/Resets.scala` — the reset network's 6 (`Probes.mark`: 3 reasons, 3 domains); `src/device/ddr3/Ddr3.scala` — 22 `Probes.mark` (the device's); `src/logic/Ddr3Soak.scala` — 8 `Probes.mark` (the soak's); `src/Top.scala` — 2 `Probes.mark` (its own LEDs); `src/logic/Blinkers.scala` — 2 `Probes.watch` (the panel's — `Blink` is the one shared body, so it declares and gets bound per instance) | a statement keeps a wire named `probe_<name>` and marks it `mark_debug`; `mark` = declare + bind in one step, for a module instantiated once |
| The mechanism | `nzea_rtl/src/probe/` (`Probes`, `ProbePlace`, `keepNet`) | `Elaborate`'s `layers-*.sv` handling is generic — shared with any layer user (e.g. `nzea_rtl`'s `ProbeLayerTest`); check its consumers before touching it |
| The capture flow | `scripts/ila-capture.nu`, `just fpga capture`, the `STEPS.OPT_DESIGN.TCL.PRE` hook in `tools/vivado/vivado-project.py` | the hook is keyed on `ila_debug.tcl` existing, so removing the ILA removes the hook by itself; `probe`'s `vcd-rename` is used by the capture script (see the fpga-flow skill) |
| Tests and docs to update | `test/src/LxbArtix7ElaborateTest.scala` (the `watched`/`patterns`/`netKeys` tables, the `mark_debug` count, the two ILA tests), `nzea_rtl`'s `ProbeLayerTest`, `src/device/ddr3/README*.md`, `.agents/skills/fpga-flow/SKILL.md` (the ILA chapter, the artifacts table) | the skill also documents the trigger values (`calibrating=0`) that only exist for the probes |
| **The cheap alternative**: keep every line of source and just do not create the debug core | generate the project without the debug script, i.e. no `STEPS.OPT_DESIGN.TCL.PRE` hook | costs nothing to undo; drops the core's impl time and its timing pressure. The dump still writes `ila_debug.tcl` (the `Ila` is registered at elaboration), so this needs a switch in `vivado-project.py` or `build_all.tcl` |

## B. The bring-up self-test (`src/logic/Ddr3Soak.scala`, and what stays in the device)

The round loop used to be two things at once — a test, and the only memory traffic the design has. They are two files now
(`README_zh.md` §12 records the split): the transport is the device's (`src/device/ddr3/Ddr3.scala` — a LiteBus slave a
master reads and writes the memory through), the test is `src/logic/Ddr3Soak.scala` (the LiteBus master that exercises
it), wired in `Top` as one bus between the two instances (`Ddr3Bus.bus :<>= soak.io.bus`).

**Removable — the whole soak file:** `Ddr3Soak.scala` (its 8 probes included: `phase`, `step`, `issued`, `errors`,
`done`, `firstBad*`), plus the `soak` instance in `src/Top.scala` (its four lines). The address and pattern tables, the
LFSR and the mask's own test all live in it — deleting it (or simply not instantiating it) leaves the bus idle in its own
reset.

**Removable — the device's own forensics** (in `Ddr3.scala`): the probe declarations `captured`, `checked`, `timer`,
`dqIn`, `dmOut`, `recent` (the sweep's per-round bit map), `failTaps` and `respValid`/`respData` are observations the
board's readings were taken from; the registers themselves (`recent`, `failTaps`) are cheap, the *declarations* are what
the ILA spends probes on. The compare itself (`matched`, `aligned`, `pass`, the alignment slice) is the verdict the
device's own bring-up rounds carry — it is scaffolding only in the sense that a design whose memory is driven by a real
master has no use for the rounds' *pattern* check (the slices are load-bearing for the bus's reads: `alignLocked`).

**Keep — load-bearing, not scaffolding:** the read-capture calibration (the sweep, the planes, the lock, and the one
*post-lock round* at the locked taps whose own whole-burst verdict is what `calibrated` means:
`calibPlane`, `planeMiss*`, `planeFirst/Last*`, `onWins`, `locked*`, `finalRound`/`finalPass`, the `delayTap` mux). The
taps are *found* by it — delete it and the interface needs a hardcoded tap, which is a board fact, not a design constant
— and the alignment the bus's reads slice at has to be measured at the tap the bus runs at. Also keep the
init/refresh/command state machine and the MR settings, and the transport itself (the staging, the window, the check).

**The catch:** with the soak gone, the bus has nothing to read or write, and the memory's correctness has no on-board
witness at all. Either keep the soak (it is one file, and it *is* the witness), or re-point the device's slave end at the
tile's bus when the tile lands (the soak then becomes a bring-up mode).

## C. Simulation scaffolding

`sim/Ddr3Bringup.sv`, `sim/Ddr3Smoke.sv` (+ their `.f`), `sim/TopSmoke.sv`, `scripts/xsim.nu`, `just fpga sim`.

Keep `sim/vendor/` — the models any future xsim run compiles (and `vendor/micron/guides/`, which is documentation).
Removing the scenarios costs the one way to check a mechanism change before spending a 20-minute build, which is what
the debug rules ask for ("simulation first").

## D. Not scaffolding — do not remove while cleaning

- The two blinkers (LED1 off the oscillator's 50 MHz, LED2 off the wizard's 100 MHz): the only "is it alive" indication
  without the ILA, and the reason one of the wizard's six outputs is pure diagnostics.
- `locked` in the reset chains (the 100 MHz domain and the DDR3 one).
- The `*Probe` / `*Layers` / `*VerilogParameter` type parameters of every `@generator` object — the DSL's module shape,
  required by the macro; they are not observations in themselves.
- `test/src/RingAsModuleProbeTest.scala` — the record of "a text-body module as a module of the circuit" (its doc comment
  carries the three findings the pads' modules are built on). Delete only if text-body modules are dropped for good.
- The constraint machinery (`XdcRegistry`, `BoardXdc`, `Ddr3Xdc`, `xcdfiles.f`): board constraints, not debug.

## Suggested order

1. Commit (nothing here is in git yet).
2. Try the build-level off switch (A, last row) — it needs no source change; measure the build time and the timing
   reports before and after.
3. If the sources should go, in this order: the level's bindings → the per-module declarations → the ILA → the flow
   scripts → the tests and docs. After each step: `just dump --target fpga --isa riscv32i` and `mill --no-server
   nzea_fpga.test`; the dump's file set is the checkable part (`ila_debug.tcl` present or not, `mark_debug` count).
4. Update this file (or delete it) when the cleanup is done.
