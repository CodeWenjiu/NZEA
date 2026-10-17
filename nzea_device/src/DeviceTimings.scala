package nzea_device

import nzea_rtl.bus.DeviceTiming

/** The bus devices' timing in the simulator: one entry per device, applied by the bridge answer. Only the bridge flow
  * reads it — a synthesis flow builds the device's own hardware, whose timing is the macro's or the board's, not a
  * model. The numbers are in tile cycles: the RAM's stand in for an off-chip memory (a fixed access latency, not a clock
  * ratio), and the MMIO devices' for their slower register accesses.
  */
object DeviceTimings:

    /** The platform RAM, as an off-chip memory: a long fixed access latency with row-conflict-style jitter. */
    val ram = DeviceTiming(latency = 20, jitterEvery = 8, jitterSpan = 8)

    /** The UART's register file: slow, and rarely touched. */
    val uart = DeviceTiming(latency = 8, jitterEvery = 4, jitterSpan = 4)

    /** The test finisher: a write ends the run; a short residency is enough. */
    val finisher = DeviceTiming(latency = 4, jitterEvery = 4, jitterSpan = 4)

    val clint = DeviceTiming(latency = 8, jitterEvery = 4, jitterSpan = 4)
