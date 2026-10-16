# `device/` — the board's own devices

One directory per device *the board* carries: a chip the FPGA talks to, with everything about it that is this board's —
its controller (the design side), its pads and the primitives in their I/O cells (how it is wired), **the grids its ports
run on** (the device binds its own clocking where its instances are named — `nzea_fpga.Clocks`), **the reasons its reset
waits for** (it declares its own and states its own domain, on its own grid — `nzea_rtl.stdlib.Resets`), its constraints,
and its bring-up record. `ddr3/` is the first of them, and `Ddr3Side.scala` is its front door: a level hands it the pins it
declares, and that is the whole of what the level says to it.

A device is the *transport*, not its test: what traffic a device is put through is the level's own logic
(`../logic/` — `Ddr3Soak` is the first such campaign), wired to the device's ports where both instances are named. The
one thing the device hands over for it is the domain the traffic belongs to (`Ddr3Bus.clock`/`resetN`), so the two see
one timeline without the level knowing which grid that is.

Not to be confused with `nzea_device`: that module is *fabric* IP — devices a design instantiates, core-relative and
board-agnostic (UART, timers, RAM). What belongs here is bound to this board's silicon and its wiring; an Ethernet PHY
or a SPI flash would land beside `ddr3/` the same way.
