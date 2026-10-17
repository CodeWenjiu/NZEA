# `logic/` — this level's own logic

The modules that are the board top's own *function*, as opposed to the pieces it borrows. A new module has a home by
what it is:

| If it is | it lives in |
|---|---|
| the board boundary — the pins the design declares, the clocking the design asks for (`Clocks`), the reset chain and the wiring between them | `src/Top.scala`, one readable file (`nzea_fpga.Top`) |
| what a logic asks the boundary for — the pins, the grids and the reset domain it states (`LogicNeeds`) | `src/Pins.scala`, `src/Logic.scala` |
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

A module here is instantiated from the boundary (`Top`), and what it needs from that boundary it *states* rather than is
handed: the pins it drives and reads, the grids its instances run on, and the reset domain it runs in (`LogicNeeds`, the
same rule the level above follows — clocks and resets are distributed by the top, never picked up inside a module). A chip
under `device/` is the one exception in how it is built, and not in this rule either: a logic that owns a chip
instantiates the chip inside itself and states the chip's needs as its own, so the bus's own grids and its reset domain
reach it through the boundary's distribution — one wizard for the design's clocks (`nzea_fpga.Clocks`), one network for
its resets (`nzea_rtl.stdlib.Resets`), both wired where the boundary names the logic's instance (`Logics.connect`). A
logic that stated its clocks but was handed nothing would have to have its own wizard, and two wizards put an unknown
phase between grids that must keep one.

`Blinkers` is the first module here: the two LEDs, one blinker per clock domain, the level's own composition of a
reusable module ([[nzea_device]]'s `Blink`), whose diagnostic story (a board where only the first LED blinks points at the
clocking wizard) is the reason the boundary hands it two clock domains instead of one.

`BootSram` and `CoreMemory` are the board's program loader, split by what each owns: the loader turns the serial line's
frames into writes and states the hold as a reason ([[nzea_fpga.logic.BootSram]] — the protocol is the host tool's), and
the memory those writes land in is this level's, because the tile's RAM is an *external window* here ([[nzea_fpga.Top]]):
`CoreMemory` serves the tile's port and the loader's on one macro and drains a held core's answers. Both run on the boot
grid the loader states, so a load's writes and the core's first fetch are ordered by the one clock.

`Ddr3Soak` is the test the board's DDR3 bus ([[nzea_fpga.device.ddr3.Ddr3Side]]) is put through — the walk's
sixteen addresses, the pattern window, the LFSR run and the write-mask steps, with every read-back judged against what
the writes themselves left behind (`errors`, and the first failure's address/expectation/answer). It owns the bus's board
side: the device is instantiated *inside* it, on the pins, grids and domain it states ([[LogicNeeds]]), so the boundary
knows neither the transport nor a pad of it. Its rounds and the device's answer belong to the bus's own domain, and the
campaign exists to read that verdict there. It is not instantiated in the top today — the loader's target is SRAM — and
comes back with the migration to DDR3.
