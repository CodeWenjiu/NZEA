package nzea_device

import nzea_rtl.bus.{AccessCost, AddrRange, BusTiming, Occupancy, TimingEntry}

/** Every bus timing the platform states, in one place and one shape: a [[BusTiming]] is a port's table, so a device on
  * a port of its own is one catch-all row and a window in front of several devices is a row per device.
  *
  * Only the bridge flow reads these — a synthesis flow builds the device's own hardware, whose timing is the macro's or
  * the board's, not a model. A row's [[AccessCost]] says which clock the delay belongs to (`Periods` for a device's
  * own, `SameDomain` for a device the bus clock runs, `Sum` for parts of one access with different origins), and its
  * [[Occupancy]] says whether the port is held for it. The rule, the arithmetic and the measured evidence are
  * `goal/latency-calibration.md`.
  */
object DeviceTimings:

    /** The platform RAM, as the board's memory: measured at the core's port (C1, 2026-11-04) — 23 cycles of the near
      * clock's 100 MHz (230 ns) typical, 54 (540 ns) at worst (row conflict or refresh). The path is the whole one a
      * master sees: the near-side arbitration, the crossing, the whole-burst adapter, the MIG and the DDR3, so this is
      * the port's cost, not the DRAM's column latency alone.
      *
      * Evidence: `goal/measurements/c1-ddr-latency-*.vcd`, read through the `ddrLatLast`/`ddrLatMax`/`ddrGapLast`
      * probes defined in `nzea_fpga/src/logic/DdrMemory.scala`; the arithmetic is in `goal/latency-calibration.md` §2.
      */
    val ram: BusTiming = BusTiming(
        Seq(
            TimingEntry(
                range = None,
                cost = AccessCost.Periods(deviceHz = 100_000_000, deviceCycles = 23),
                occupancy = Occupancy.Pipelined
            )
        ),
        jitterEvery = 8,
        jitterSpan = 8
    )

    /** The UART's register file: the hardware answers three cycles after the request (`Uart.scala`'s `busCycle` reaches
      * 3), which is what the bridge flow must reproduce.
      */
    val uart: BusTiming = BusTiming(
        Seq(TimingEntry(None, AccessCost.SameDomain(3), Occupancy.Pipelined)),
        jitterEvery = 4,
        jitterSpan = 4
    )

    /** The arm block's register file, reached over a 400 kbit/s wire. A frame's bits are what the transfer occupies the
      * wire for — start, the 7-bit address with its read/write bit, the data byte, both acknowledges and the stop — not
      * the data bits alone. The range matches remu's `configs/devices/arm.conf` base: two statements, one address, and
      * this one is the model's.
      */
    val arm: AddrRange = AddrRange(base = BigInt("8A000000", 16), size = BigInt("68", 16))

    /** The peripheral window: one port in front of the secondary bus, so its table has a row per device. The arm holds
      * the port for its frame; every other address takes the bus's own pace ([[PlatformDevices.PeripheralDeviceHz]]),
      * which is the same two numbers the hardware `PeripheralBus` divides its clock by.
      */
    val peripheral: BusTiming = BusTiming(
        Seq(
            TimingEntry(
                range = Some(arm),
                cost = AccessCost.bitFrame(bitHz = 400_000, bits = 20),
                occupancy = Occupancy.Held
            ),
            TimingEntry(
                range = None,
                cost = AccessCost.Periods(
                    deviceHz = PlatformDevices.PeripheralDeviceHz,
                    deviceCycles = PlatformDevices.PeripheralDeviceCycles
                ),
                occupancy = Occupancy.Pipelined
            )
        ),
        jitterEvery = 4,
        jitterSpan = 4
    )
