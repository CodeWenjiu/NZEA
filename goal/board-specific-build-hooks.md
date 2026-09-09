# Board-Specific Build Hooks (open problem, deferred)

Status: **deferred — problem statement only**.

Raised during the DDR3 bring-up: `vivado-project.nu` grew lxb_artix7-specific
logic inline. This document deliberately records **problems and the option
space, not a decision**. No recommendation is written down on purpose, so that
whoever picks this up later (likely with a second board in hand) is not
anchored by conclusions drawn from a sample size of one.

## Background: current layering

The Vivado project generation pipeline spans four layers, each holding some
board knowledge:

| Layer | File | Board knowledge today |
|-------|------|----------------------|
| Chip database | `nzea_fpga/scripts/chips.nu` | Pure data: vendor, board segment, top module, base XDC (`cst`), vivado part |
| Root build script | `nzea_fpga/scripts/vivado-project.nu` | **Inline special-casing**: DDR3 handling (lines 47–75), keyed on `<board>/ddr3_ref/` file existence, with five hardcoded XDC port-rename substitutions; plus a hardcoded `ip_clk_wiz.tcl` path |
| Edalize wrapper | `nzea_fpga/tools/vivado/vivado-project.py` | Generic flags only: `--xdc` (append), `--ip-tcl` (append), `--mig-prj` (optional single) |
| Scala elaboration | `nzea_fpga/src/FpgaElaborate.scala` | `match` on the `FpgaBoard` enum; Gowin-only `renameAluForGowin` post-processing; writes `ila_config.json` / `ila_capture.tcl` / `ila_probes.map` when `debugIla` |

## Observed problems

### P1 — DDR3 is the only specialization that needs a *transform*

The other two board additions are pure presence-driven conventions:

- clk_wiz: `ip_clk_wiz.tcl` exists in the board directory → passed through.
- ILA: `IlaProbes` is instantiated in RTL → firtool emits `IlaProbes.sv` and
  Scala writes `ila_config.json` → the file's presence is the switch, so the
  IP always matches the RTL with no flag to keep in sync.

DDR3 alone requires *deriving* a build input at script time (a rewritten XDC).
That asymmetry is why it needed 29 lines while the others need 3.

Open: should the goal be to make DDR3 look like the other two, or is a
transform an unavoidable class of board specialization that deserves its own
mechanism?

### P2 — RTL bit-width knowledge leaks into the build script

Four of the five substitutions strip a `[0]` index
(`{DDR3_ck_p[0]}` → `{DDR3_ck_p}`, likewise `ck_n`/`cke`/`odt`). Verified
against a generated build: `DDR3_dq[0]` and `DDR3_addr[14]` keep their indices
because they are real vectors, while exactly those four pins collapse to
scalars — **because they are declared `UInt(1.W)` in `Ddr3CtrlIo` and firtool
emits width-1 `UInt` as a scalar port**.

So the rule set is a function of an RTL declaration detail. Changing `ck_p` to
`Bool()`, or widening it, silently invalidates the rules; nothing in the build
reports it.

Open: which side should move — RTL port shapes adapted to vendor constraint
templates (MIG's example design declares `ddr3_ck_p` as `output [0:0]`, so a
1-element vector would be the vendor-faithful shape and would reduce the whole
transform to a single uniform prefix rename), or templates adapted to RTL?
Cost of each over the life of the project is unknown; re-verification on the
board is expensive either way.

### P3 — the transform is a blunt instrument and does not track renames

The template contains 167 `ddr3_` occurrences, three of them inside commented
out `ddr3_cs_n[0]` lines; the blanket `str replace -a "ddr3_" "DDR3_"` rewrites
those too. More importantly it does not follow field renames: renaming `ck_p`
to `clk_p` in `Ddr3CtrlIo` leaves the XDC constraining `DDR3_ck_p`, which
Vivado reports only as a warning about an unmatched port.

Open: is a build-time transform able to track the RTL at all, or is tracking
only achievable by deriving the rules (or the whole XDC) from the bundle
definition in Scala, the way `ila_config.json` is derived from
`IlaProbes.widths`?

### P4 — the enable switch is implicit and one-directional

DDR3 is on iff `ddr3_ref/srcs/.../mig_b.prj` and `ddr3_ref/mig_ddr3.xdc` both
exist. Nothing in `chips.nu` reveals that lxb_artix7 has DDR3, and the only way
to disable it is to delete or move reference files.

Open: is presence-driven enabling a feature (no flag to keep in sync, same
philosophy as the ILA) or a liability (undiscoverable, cannot be turned off)?
Note the two existing precedents disagree with each other in spirit: the ILA is
presence-driven *and* has an explicit `debugIla` switch upstream.

### P5 — constraint/RTL disagreement surfaces very late

During bring-up, a bank-type conflict (`[DRC BIVB-1]`: `UART_RX`'s LVCMOS33 in
a bank the MIG template constrained as High Performance / SSTL15) appeared only
at Vivado DRC time, after synthesis had already run. A cross-file constraint
conflict like that is not detectable by reading any single file.

Open: where should XDC↔RTL agreement (port names, and ideally bank/IOSTANDARD
compatibility) be verified, and by whom? What is the cheapest check that would
have caught the P2/P3 class of drift at dump time rather than at DRC time? Is
such a check generic across boards and constraint files, or DDR-specific?

### P6 — the asymmetry is entirely in the nushell layer

`vivado-project.py` already accepts repeatable `--xdc` / `--ip-tcl` and an
optional `--mig-prj`. Edalize's own file-set/tool-option model may already
cover part of this. Whatever mechanism is chosen, the Python layer probably
needs no change beyond `--mig-prj` becoming appendable (if a board ever needs
two memory IP instances).

## Specialization types observed so far (descriptive inventory)

Enumerated so that "what counts as a board specialization" has a registry.
This is a record of what exists, not a claim that the list is the right
abstraction.

1. **Constraint transformation** — adapt a vendor/reference XDC to generated
   top-level port names (DDR3). Shape: substitutions, or a rename map.
2. **IP generation from vendor config** — MIG 7-series from an XML `.prj`.
3. **IP generation from a board TCL snippet** — clk_wiz.
4. **IP generation driven by RTL instantiation** — ILA via `ila_config.json`.
5. **Additional constraint files** — `ddr3_pins.xdc` beyond the board base.
6. **Post-RTL processing** — edits to firtool output before synthesis (Gowin
   ALU module rename).
7. **Device / part / clock resolution** — already data-driven (`chips.nu`);
   clock structure (MMCM) lives in board Top RTL.

## Mechanism space (enumerated, deliberately not ranked)

- **A. Convention-based nushell hooks** — optional `<board>/build-hooks.nu`
  exporting a fixed contract (e.g. `prepare [rtl_dir] -> record`); the root
  script sources it when present. Nushell has no traits, but command existence
  after `source` emulates "has implementation → call it". Gives imperative
  freedom; the contract is stringly typed and each board's hook is bespoke
  code that no compiler checks.
- **B. Data-driven descriptors** — `chips.nu` board records gain structured
  fields (`extra_xdcs`, `mig_prj`, an `ip: [...]` list with a closed set of
  `kind` values); the root script interprets enumerated kinds and hard-errors
  on unknown ones. Declarative and diffable; a genuinely new specialization
  type still needs an interpreter branch.
- **C. Scala-side board traits** — `FpgaBoard` gains methods returning a build
  descriptor, written as JSON next to the RTL; scripts become dumb executors.
  Type-checked and colocated with part names / probe widths / top module
  names; widens the Scala→script data interface and risks diverging from
  `chips.nu` as a second board database.
- **D. Eliminate the transform** — run the rewrite once offline and commit the
  result as a board constraint file next to `A7_lite.xdc`, leaving only
  `mig_b.prj` as a build-time vendor input. Removes the logic entirely; drift
  between the committed XDC and RTL then becomes the open risk (see P5).

## Open questions to resolve before implementing

1. Which side owns the port-name contract: RTL bundle, vendor template, or a
   committed board XDC? (P2, P3, D)
2. Is a board hook allowed to be code, or only files plus data entries? If
   code is allowed, what guards against unbounded growth? (A vs B)
3. Where does the specialization-type registry live and who may extend it?
   (This document is the initial record.)
4. Should `chips.nu` remain the single board database, or should per-board
   records be sourced lazily so it stays small? (B vs C)
5. Does edalize's file-set/tool-option abstraction already subsume part of
   type 2–4, such that `vivado-project.py` should consume descriptors
   directly instead of growing flags? (P6)
6. What verification, if any, should run at dump time to catch
   constraint↔RTL disagreement before Vivado does? (P5)
7. Should `renameAluForGowin` (type 6) fold into the same mechanism, or stay
   a Scala-side special case because it operates on firtool output during
   `dump` rather than on project generation?

## Revisit trigger

A second board needs any of the types above, **or** a change to `Ddr3CtrlIo`
pin names/widths forces the transform to be touched anyway. Until then the
inline block works and is verified on hardware; refactoring it for its own sake
buys nothing and risks a board re-verification cycle.

## Related

- `goal/ddr3-debug-port-removal.md` — same posture (deferred cleanup, recorded
  so the constraint is not forgotten), also gated on board debugging
  converging.
- `goal/ddr3-debug-postmortem.md` — the adapter/timing bring-up history
  (address mapping, write drain, ui-domain reset). It does **not** cover the
  constraint-file problems recorded here; P2/P5 come from the Vivado project
  generation side, which has no postmortem of its own yet.
