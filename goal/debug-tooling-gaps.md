# Debug Tooling and FPGA Verification Gaps (open problems, deferred)

Status: **deferred — problem statement only**.

Provenance: distilled from the DDR3 bring-up (`goal/ddr3-debug-postmortem.md`)
plus a tooling review done afterwards. The two documents divide the work: the
postmortem records *what happened*; this one records *what is still
structurally missing* in the debug toolchain (`probe`, `pulse`, remu) and in
the nzea flow around them.

No decisions or recommendations are recorded, so that later work — which will
have more boards and more evidence — is not anchored by conclusions drawn from
a single bring-up. Where this document disagrees with the postmortem, the
disagreement is stated as an open question rather than a verdict.

## Verified facts this document rests on

Each claim below was checked against the repository, not recalled:

| Fact | Evidence |
|---|---|
| The fpga platform has no iverilog simulation path | `AGENTS.md`, `### 仿真（iverilog）`: "`just iv tile yosys <isa>` … **仅 yosys 平台**——fpga 平台设备在板外/平台 IP，TB 未覆盖" |
| MIG simulation RTL is already requested at project-creation time | `nzea_fpga/tools/vivado/vivado-project.py`: the generated snippet ends with `generate_target all [get_ips mig_ddr3]` (`all` includes the Simulation target) |
| The official Micron DDR3 model is already in-tree | `nzea_fpga/src/boards/lxb_artix7/ddr3_ref/sim/{ddr3_model.sv,ddr3_model_parameters.vh,wiredly.v}` |
| Four hand-written MIG behavioural stubs exist | `nzea_device/test/src/ddr3/Ddr3AdapterTest.scala`: `MigAppStub`, `MigAppStubWithCalib`, `MigAppStubSwallow`, `MigAppStubHold` |
| Debugging constitution rule 2 forbids exactly that | `AGENTS.md`, `### 调试宪法`: "**仿真模型必须官方**：禁止手写 IP 行为模型（如 MIG、MMCM、PLL）。必须使用 Vivado 生成的官方仿真文件" |
| remu's RAM region ends exactly where DDR begins | remu boot log: `ram region initialized at 0x80000000:0x88000000`; `nzea_tile/src/platform/fpga/AddressMap.scala`: `val ddr = LiteAddrRange(base = 0x88000000, size = 0x20000000)` |
| `probe load` has no read-back window control | `probe/cli/src/main.rs`: `Cmd::Load { hex, port, baud, addr }`, docstring "…then read back **briefly**" |
| ILA VCD net names are synthesis artifacts | `nzea_fpga/src/FpgaElaborate.scala` `probeMap` comment: firtool junk names (`E`, `SR`), constant folding (`<const0>`), address split into `_2`/`_1`; 13 of the previous map's entries were wrong names |
| `TRIGGER_POSITION` was hardcoded above the capture depth | fixed in `7cf2837` (was 32768 vs depth 16384; now derived from `IlaProbes.depth`) |

## G1 — the fpga platform has no simulation path at all

Anything board-specific (MIG, MMCM, DDR physical layer, ILA) can only be
validated by a hardware iteration costing roughly 10–20 minutes
(`dump` → `vivado-build` → `program` → `load`). The postmortem estimates 15+
such iterations for the DDR3 bring-up.

The postmortem frames this as "the stub was not faithful enough" (its P0
items 1–2: make the MIG stub precise, add stub variants for each observed
hardware quirk). **Open disagreement**: a stub's semantics come from our
*guess* about the real IP, so a wrong guess produces a stub that faithfully
implements the wrong protocol. Bug 1 (whether `app_wdf_data` is driven for one
beat or two) survived simulation precisely because the stub was written from
the same wrong assumption that the RTL was. More stub variants means more
guesses, not fewer.

Note that the raw material for a real simulation already exists (see the fact
table): `generate_target all` is already invoked, so MIG's simulation RTL is
produced by a project creation — a step much cheaper than a full build — and
the official Micron DDR3 model is committed. What is missing is a testbench
and a recipe, not vendor files.

**Unverified assumption, recorded as a risk and not as a plan**: whether an
open simulator can compile MIG 7-series RTL is unknown. iverilog is expected
to struggle with Xilinx IP; `xsim` (ships with Vivado, no extra licence, can
necessarily compile its own IP) is the plausible route. This has not been
tried.

Open questions:
1. Is the missing capability "a more faithful stub" or "a path to the official
   model"? The two imply different work and different failure modes.
2. Which simulator, and does the answer differ per IP (MIG vs MMCM vs ILA)?
3. Should such a cosim live under `just iv` (extending the platform matrix) or
   as its own recipe, given that its simulator is Vivado-provided rather than
   iverilog?
4. What happens to `Ddr3AdapterTest`'s four hand-written stubs if an official
   model becomes available — deleted, or kept as fast unit-level checks with
   the cosim as the authority?

## G2 — debugging constitution rule 2 currently cannot be complied with

`AGENTS.md` forbids hand-written IP behavioural models and requires
Vivado-generated official simulation files. Four hand-written MIG stubs exist
in `nzea_device/test/src/ddr3/Ddr3AdapterTest.scala`, and no flow in the
repository can consume an official MIG model.

This is recorded as a **rule/tooling mismatch**, not as a violation to be
punished: with no path to the official model, the rule was unenforceable from
the moment it was written, and the DDR3 work had no alternative.

Open questions:
1. Should the rule be relaxed (state when a stub is acceptable) or the tooling
   built (make the rule executable)? A rule that cannot be followed trains
   readers to ignore the whole constitution.
2. If stubs stay, what marks them as "guess, not authority" so that a green
   stub run does not produce false confidence? The postmortem calls this out:
   a passing but wrong stub is *more* dangerous than no stub.
3. Does the same gap apply to MMCM (`Mmcm50to200`) and to the Gowin path?

## G3 — the ILA chain is three tools glued together, keyed on synthesized net names

`just ila-capture` is a nushell script (`nzea_fpga/scripts/ila-capture.nu`)
driving a Vivado batch TCL (`ila_capture.tcl`), whose VCD output is then
renamed by `probe vcd-rename` using `ila_probes.map`. `probe` owns only the
last step.

Two defects surfaced during this bring-up:

- `TRIGGER_POSITION` was hardcoded to 32768 against a depth of 16384, so every
  capture errored until edited by hand. Fixed in `7cf2837` by deriving it from
  `IlaProbes.depth`.
- 13 entries of `ila_probes.map` carried wrong names, so the exported VCD had
  junk signal names. Fixed in `7cf2837` by rewriting the map against an actual
  capture.

The second fix is a manual repair, not a mechanism: **the names being mapped
are synthesis artifacts** (firtool junk names, constant folding, split
vectors), so the map can silently stop matching on the next build. The
mitigation currently on record is a comment telling the reader to re-derive
names with `pulse signal`.

Separately (postmortem item 3): probe wiring is hardcoded in
`LxbArtix7Core` (`ila.probeN := …`), so adding a probe costs an RTL change
plus a full rebuild. That is an independent problem from name fragility — one
is "changing probes is expensive", the other is "probe names are unreliable".

Open questions:
1. Can semantic names be carried from RTL to the exported VCD instead of being
   reconstructed afterwards (probe port names on `IlaProbes`, names from the
   `.ltx`, or ILA IP probe-name properties)? **Requires reading PG172 before
   assuming any of these works.**
2. Should `probe` own the whole capture flow (arm, trigger, upload, rename)
   rather than only the rename, so the chain has one owner?
3. Is VCD the right export format at all? ILA samples are cycle-indexed while
   VCD is time-stamped and only written on change, which the postmortem notes
   makes static windows look a few ns long. Vivado can also export CSV, whose
   one-row-per-sample shape matches the cycle semantics `pulse --cycles`
   assumes. (Related: `pulse` gained a clockless fallback in `08a2ca9`
   precisely because ILA exports have no usable clock.)

## G4 — remu cannot see DDR, so DDR tests run without difftest

remu initialises `ram` as `0x80000000:0x88000000`; `AddressMap.ddr` starts at
`0x88000000` — the DDR window lies entirely outside remu's address space.
Consequence: DDR test programs can only be run as bare hex via `just
uart-load`, with no reference model, so correctness rests on the program
comparing its own writes against its own reads.

Open questions:
1. Is extending remu's RAM region (or adding a second region) the right
   response, or should DDR stay outside difftest because a real MIG has
   timing-dependent behaviour a flat reference model would hide?
2. **Cross-repository boundary**: remu is a separate repository with its own
   conventions (`AGENTS.md` red line 1 forbids inferring consistency across
   repositories). Any change there needs an explicit decision, not a
   well-intentioned "fix" from this side.

## G5 — probe: no read-back window on load, and serial port drift

- `Cmd::Load` exposes only `hex`, `port`, `baud`, `addr`; the read-back window
  is fixed and short. Observed symptom during bring-up: `(no data)` for a
  program that was in fact running, which reads exactly like a hang.
  `Cmd::Monitor` already has `--reset` (attach, then reset), so the correct
  observation posture exists but is not reachable from the load path.
- Port names drift (`ttyUSB0` ↔ `ttyUSB1`). `ila-capture` defaults to a
  `/dev/serial/by-id/...` path, but `just uart-load` still requires the port
  to be passed explicitly, so every board session starts by re-discovering it.

Postmortem items 6 and 8; both still open. Item 7 (a `probe` subcommand to read
an arbitrary DDR address, to distinguish "stale residue" from "write failed")
is also open.

## G6 — no rule for which changes require board verification

The `Ddr3CtrlIo` bundle refactor (`7cf2837`) changed the *declaration order*
of generated top-level ports while keeping every name identical. What could be
verified cheaply was "names unchanged, so the pin XDC still matches"; what
could not be verified without a ~15-minute build plus a board cycle was
"behaviour unchanged". The commit records that gap explicitly rather than
claiming more than was tested.

Postmortem item 4 discusses build cost (incremental reuse from dcp
checkpoints); this is the adjacent missing piece — a **verification tier
rule**, e.g. comment/naming-only → `dump` suffices; port names or constraints →
`dump` plus an XDC↔RTL cross-check; protocol or timing → board. The
cross-check half is the same infrastructure recorded as P5 in
`goal/board-specific-build-hooks.md`.

## G7 — `goal/` has no index or lifecycle rule

Four documents currently live in `goal/`, in different states (two deferred,
one implemented, one postmortem), and at least four have been deleted over the
project's history (`config-aggregation`, `config-single-source`,
`data-path-info-units`, `pulse-evolution`). `AGENTS.md` defines a lifecycle
principle for `.pulse` event files ("用到再说") but says nothing about `goal/`.

Concrete symptom: while writing `goal/board-specific-build-hooks.md`, a deleted
document (`config-aggregation.md`) was cited as precedent and had to be
retracted after checking. Nothing in the repository made that discoverable
except `ls`.

Open questions: what states does a goal document have, who moves it between
them, and is an index (`goal/README.md`) or a status line in each document the
cheaper way to keep them honest?

## G8 — stale references in `ddr3_ref/README.md` (resolved)

Committed in `7cf2837` without being checked. Three references were wrong:

| Reference in README | Actual state |
|---|---|
| `goal/ddr3-bus-device.md` | did not exist |
| `srcs/sim_1/new/tb_ddr3_loop_test.v` | claimed as included; **not** in the copied tree |
| `DdrBridge` | renamed to `Ddr3Adapter`; the README was the only place still using the old name |

**Resolved** in the cosim work: the README was rewritten to describe the actual
contents, to drop the non-existent goal document, and to stop vendoring the
Micron model (`sim/`), which Vivado regenerates anyway. The vendor TB the
missing-file entry referred to turned out to be unnecessary for G1 — the cosim
reuses the regenerated `sim_tb_top.v` directly instead.

The underlying problem is unchanged and is the same class as G7: documentation
that no check keeps honest.

## Already landed — do not re-propose

| Postmortem item | Landed in |
|---|---|
| 5 — parameterise `ila_capture.tcl` (depth / TRIGGER_POSITION) | `7cf2837` |
| 13 — board test-program management | `e2fc571` (`probe/asm2hex` + built-in programs) |
| pulse width awareness, change builtin, clockless fallback | `08a2ca9`, `b61976c` |
| ILA VCD → pulse pipeline | `ff2ffa6` (`probe vcd-rename`) |

Still open from the postmortem: items 1–2 (see G1 for the reframing), 3 (G3),
4 (G6), 6–8 (G5), 9 (CDC handshake as a reusable `nzea_rtl` component), 10
(reset-domain matrix), 11–12 (reference-design coverage blind spots and an
archive of MIG facts).

## Related

- `goal/ddr3-debug-postmortem.md` — the bring-up narrative this document
  abstracts from (Chinese, by request).
- `goal/ddr3-debug-port-removal.md` — the wide `Ddr3AdapterDebug` port exists
  only because G1/G3 left the ILA as the sole window into the adapter.
- `goal/board-specific-build-hooks.md` — P5 there (dump-time XDC↔RTL port
  cross-check) is the infrastructure G6 needs.
- `goal/pulse-widths-and-change-stats.md` — implemented; the ILA-export
  problems it solved are the same family as G3's format question.
