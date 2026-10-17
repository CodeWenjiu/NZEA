# `device/` — the board's own devices

One directory per device *the board* carries: a chip the FPGA talks to, with everything about it that is this board's —
its controller (the design side), its pads and the primitives in their I/O cells (how it is wired), **the pins it drives,
the grids its ports run on and the reset domain it runs in** (one statement, `Ddr3Side.needs` — `nzea_fpga.Logics`),
its constraints, and its bring-up record. `ddr3/` is the first of them, and `Ddr3Side.scala` is its front door: a level
declares the ports that statement asks for and wires them, and that is the whole of what the level says to it.

The same directory also holds a device's *board-side half* when the device itself is fabric IP: `uart/` is one file
(`UartXdc.scala`) — the serial line's two pins and their constraints — because the 16550 is board-agnostic
(`nzea_device`'s `Uart`) while the pins are this board's. What is here is the part that cannot leave the board; a device
whose hardware exists only on this board keeps both halves here.

A device is the *transport*, not its test: what traffic a device is put through is the level's own logic
(`../logic/` — `Ddr3Soak` is the first such campaign), and the logic that owns a device instantiates it itself, so the
two see one timeline without the level knowing which grid that is.

Not to be confused with `nzea_device`: that module is *fabric* IP — devices a design instantiates, core-relative and
board-agnostic (UART, timers, RAM). What belongs here is bound to this board's silicon and its wiring; an Ethernet PHY
or a SPI flash would land beside `ddr3/` the same way.
