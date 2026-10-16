package nzea_fpga.device.ddr3

/** The DDR3 bus's shape and names, in one place: the design's own pins ([[nzea_fpga.TopIO]]), the pads' modules
  * ([[Ddr3Pads]]) and the controller's ports ([[nzea_fpga.device.ddr3.Ddr3]]) all read this table, so a rename is one
  * edit and the compiler checks every place the bus is named.
  *
  * The command/address names are also the *pin* names (the XDC's), because the design hands the pads exactly those
  * levels: `DDR3_ADDR` is the address a beat presents and the pin group it leaves on.
  *
  * A fabric cycle is a whole memory clock — a quarter-rate controller would be a different design — so the widths below
  * count a cycle's *beats*: four of them per lane (two per half-cycle: the pads are double data rate), and the
  * single-rate command/address side carries two levels per pin, which the pads widen into the device's windows.
  */
object Ddr3PadNames:

    /** The bus's shape, as the board routes it: 15 address bits, 3 bank bits, two byte lanes. */
    val addrWidth = 15
    val baWidth = 3
    val dmWidth = 2
    val dqWidth = 16
    val dqsWidth = 2

    /** The beats one fabric cycle carries per lane: the pads are double data rate, the command side is single. */
    val beats = 4

    /** The data word: four beats per lane per cycle — bit `4i + k` is lane `i`'s beat `k`. */
    val dqWordWidth = beats * dqWidth

    /** The strobe word: one level per lane per beat — the same four slots as the data. */
    val dqsWordWidth = beats * dqsWidth

    /** The input delay's tap: a five-bit value (32 steps) per byte lane, low lane's in the low half of `delayTap`, high
      * lane's in the high half, loaded into that byte's capture paths. The lanes take their own taps because what the
      * tap cancels is the *board's* own skew between a byte's data pins and the fabric's capture clock, and that skew
      * is a lane's fact, not the interface's (`UG586` read leveling: centred per byte group).
      */
    val tapWidth = 5
    val tapLanes = 2

    // The write side, as the controller presents it and the pads take it.
    val dqOut = "DDR3_DQ_OUT"
    val dqOe = "DDR3_DQ_OE"
    val dqsOut = "DDR3_DQS_OUT"
    val dqsOe = "DDR3_DQS_OE"

    // The device's pins (the XDC's names) and the levels the controller presents per pin — the same name on both sides:
    // bit `2i` is a pin's first beat of the cycle and `2i + 1` its second.
    val addr = "DDR3_ADDR"
    val ba = "DDR3_BA"
    val dm = "DDR3_DM"
    val rasN = "DDR3_RAS_N"
    val casN = "DDR3_CAS_N"
    val weN = "DDR3_WE_N"
    val cke = "DDR3_CKE"
    val odt = "DDR3_ODT"
    val resetN = "DDR3_RESET_N"

    // The pads themselves: the bidirectional and differential pins (the design's own `inout` ports), and the clock the
    // device takes as its reference.
    val dq = "DDR3_DQ"
    val dqsP = "DDR3_DQS_P"
    val dqsN = "DDR3_DQS_N"
    val ckP = "DDR3_CK_P"
    val ckN = "DDR3_CK_N"

    val dqIn = "DDR3_DQ_IN"
    val delayTap = "DDR3_DELAY_TAP"
