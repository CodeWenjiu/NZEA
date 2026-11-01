package nzea_device

import nzea_rtl.bus.{AccessCost, AddrRange, DeviceTiming, FrameTiming, FramedRange}

/** The bus devices' timing in the simulator: one entry per device, applied by the bridge answer. Only the bridge flow
  * reads it — a synthesis flow builds the device's own hardware, whose timing is the macro's or the board's, not a
  * model.
  *
  * Every entry declares an [[AccessCost]]: `SameDomain` for a device the bus clock itself runs, whose access is a
  * number of bus cycles at any clock; `Periods` for a device in its own clock domain, whose access keeps its duration
  * and whose cycle count therefore scales with the bus clock. These devices all sit on the tile clock, so they are
  * `SameDomain`, and the same 4 cycles is 160 ns in the 25 MHz simulation and 40 ns on the 100 MHz board. A cost that
  * is a device's own period belongs in `Periods`; a cost that is a wire's frame belongs in `FrameTiming`. The rule,
  * with the arithmetic and the measured evidence, is `goal/latency-calibration.md`.
  *
  * The register files are calibrated to the hardware they stand in for: the bridge flow reproduces the answer distance
  * the synthesis flow's own RTL shows, so a master sees the same bus on both. The RAM is the exception — its board
  * counterpart is the DDR3 behind the MIG, whose controller latency has no source yet. The per-entry notes say which is
  * which, and `goal/latency-calibration.md` §2 keeps the table with the evidence.
  */
object DeviceTimings:

    /** The platform RAM, as an off-chip memory: a long fixed access latency with row-conflict-style jitter.
      *
      * Not calibrated. The window it serves on the board is the 512 MiB DDR3 (the tile's own 128 KiB RAM device is
      * shadowed by that external window), and the parts are known — DDR3-800, tCK 2.5 ns, CL 6 / CWL 5 / WR 6 / BL8
      * (`nzea_fpga/src/device/ddr3/README_zh.md:17`) — but the MIG controller's own latency has no source in the repo.
      * For scale: the DRAM alone is 15 ns (CL 6 x 2.5 ns), and the stress round the README describes (ACT, WR, tWR,
      * PRE, tRP, ACT, RD) takes about 230 ns; 20 cycles at the 25 MHz simulation clock is 800 ns, 3.5x that worst case.
      * The board measurement (goal/research-plan.md C1) is what settles it.
      */
    val ram = DeviceTiming(AccessCost.SameDomain(20), jitterEvery = 8, jitterSpan = 8)

    /** The UART's register file: the hardware answers three cycles after the request (`Uart.scala`'s `busCycle` reaches
      * 3), which is what the bridge flow must reproduce.
      */
    val uart = DeviceTiming(AccessCost.SameDomain(3), jitterEvery = 4, jitterSpan = 4)

    /** The test finisher: same three-cycle answer as the other register files (`SifiveTestFinisher.scala`). */
    val finisher = DeviceTiming(AccessCost.SameDomain(3), jitterEvery = 4, jitterSpan = 4)

    /** The CLINT: the hardware answers one cycle after the request (`Clint.scala`'s `resp.valid := busy`), one cycle
      * faster than the model can express — the forward always owns an answer register and an output register — so two
      * is the floor, and this is that floor rather than eight.
      */
    val clint = DeviceTiming(AccessCost.SameDomain(2), jitterEvery = 4, jitterSpan = 4)

    /** The peripheral bridge: one window in front of a secondary bus. No hardware counterpart exists yet (a synthesis
      * flow hands the window to `Todo`), so four is still a stand-in; a same-domain forward is one to three cycles.
      */
    val peripheral = DeviceTiming(AccessCost.SameDomain(4), jitterEvery = 4, jitterSpan = 4)

/** The frame residencies of the bridge flow: which ranges pay a wire's own time, and the wire's facts. The same role as
  * [[DeviceTimings]] — numbers only the simulator's bridge reads — but a residence instead of a line: a hit range's
  * access is one frame long ([[nzea_rtl.bus.LiteBusResidency]]).
  */
object DeviceFrames:

    /** The arm block's register file, reached over a 400 kbit/s wire. A frame's bits are what the transfer occupies the
      * wire for — start, the 7-bit address with its read/write bit, the data byte, both acknowledges and the stop — not
      * the data bits alone. The range matches remu's `configs/devices/arm.conf` base: two statements, one address, and
      * this one is the model's.
      */
    val frames: Seq[FramedRange] = Seq(
        FramedRange(
            range = AddrRange(base = BigInt("8A000000", 16), size = BigInt("68", 16)),
            timing = FrameTiming(bitsPerFrame = 20, bitHz = 400_000)
        )
    )
