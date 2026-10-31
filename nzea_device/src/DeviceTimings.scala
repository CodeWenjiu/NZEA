package nzea_device

import nzea_rtl.bus.{AddrRange, DeviceTiming, FrameTiming, FramedRange}

/** The bus devices' timing in the simulator: one entry per device, applied by the bridge answer. Only the bridge flow
  * reads it — a synthesis flow builds the device's own hardware, whose timing is the macro's or the board's, not a
  * model. The numbers are in tile cycles: the RAM's stand in for an off-chip memory (a fixed access latency, not a
  * clock ratio), and the MMIO devices' for their slower register accesses.
  */
object DeviceTimings:

    /** The platform RAM, as an off-chip memory: a long fixed access latency with row-conflict-style jitter. */
    val ram = DeviceTiming(latency = 20, jitterEvery = 8, jitterSpan = 8)

    /** The UART's register file: slow, and rarely touched. */
    val uart = DeviceTiming(latency = 8, jitterEvery = 4, jitterSpan = 4)

    /** The test finisher: a write ends the run; a short residency is enough. */
    val finisher = DeviceTiming(latency = 4, jitterEvery = 4, jitterSpan = 4)

    val clint = DeviceTiming(latency = 8, jitterEvery = 4, jitterSpan = 4)

    /** The peripheral bridge: one window in front of a secondary bus; the forward's residency is the bridge's own. */
    val peripheral = DeviceTiming(latency = 4, jitterEvery = 4, jitterSpan = 4)

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
