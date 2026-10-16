# `logic/` — this level's own logic

The modules that are the board top's own *function*, as opposed to the pieces it borrows. A new module has a home by
what it is:

| If it is | it lives in |
|---|---|
| the board boundary — the pads, the clocking the design asks for (`Clocks`), the reset chain and the wiring between them | `src/Top.scala`, one readable file (`nzea_fpga.Top`) |
| this level's own logic — a state machine, a datapath, behaviour that is the *board's* rather than a chip's | here |
| reusable design IP — board-agnostic, instantiable by any design (a blinker, a UART, a timer) | `nzea_device/src/devices/` |
| one of the board's chips | `src/device/<chip>/` (see `../device/README.md`) |
| vendor IP | `src/ip/` |
| a device primitive with a text body | `src/primitives/` |
| a board fact — pin maps, the oscillator's rate, reference constraints | `src/boards/<board>/` (`BoardXdc`, `BoardClock`) |
| the flow and the dump's machinery | `src/FpgaElaborate.scala`, `src/XdcRegistry.scala` |

The test for *this level's own logic*: it is bound to this board, but it is not a chip and not the boundary — a bring-up
sequencer, an LED pattern, an address decode over the board's devices. If it turns out to speak a chip's protocol, it
moves to `device/`; if it turns out to be useful to any board, it moves to `nzea_device`.

A module here is instantiated from the boundary (`Top`), which owns the clock and the reset it gets — the same rule the
level above follows: clocks and resets are distributed by the top, never picked up inside a module. A chip under
`device/` is the one exception, and it is not a pick-up either: the device *states* which grid each of its own ports runs
on (`Clocks.bind`, right where it names the instance) and which reasons hold its own domain
(`nzea_rtl.stdlib.Resets`), while the boundary still builds the one wizard those asks come from and wires the network
once they are all stated.

`Blinkers` is the first module here: the two LEDs, one blinker per clock domain, with the probe bindings its instances
need — the level's own composition of a reusable module ([[nzea_device]]'s `Blink`), whose diagnostic story (a board
where only the first LED blinks points at the clocking wizard) is the reason the boundary hands it two clock domains
instead of one.

`Ddr3Soak` is the second: the test the board's DDR3 bus ([[nzea_fpga.device.ddr3.Ddr3Side]]) is put through — the
64 (pattern, address) pairs, a block per pair, and the account a board reading is made of (`rounds`/`misses`, the two
masks, and where the last miss stood). The transport is the device's (`Ddr3` stages what it is handed and answers one
bit per round); what a round *carries*, and what the rounds *account to*, is this level's — so the whole campaign is
one file that can be dropped without touching the bus, and the bus without it is idle in its own reset. It is also the
one module here that takes its clock and its reset from a *device's* handle rather than from the boundary's own
distribution (`Ddr3Bus.clock`/`resetN`): a round's verdict belongs to the bus's domain, and the campaign exists to read
it there ([[nzea_fpga.Top]] wires the three connections).
