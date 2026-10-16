package nzea_fpga.device.ddr3

import nzea_fpga.XdcRegistry

/** The DDR3 bus's constraints: the pads' pin map, their I/O standards and termination, and the timing exceptions the
  * pads' serializer stages need.
  *
  * The pins are the MIG reference's — the vendor's own example for this board, whose `mig_ddr3.xdc` carries the same
  * map (kept in the legacy tree, `legacy/nzea_fpga/src/boards/lxb_artix7/ddr3_ref/`). The *names* are this design's:
  * the level's own ports ([[nzea_fpga.TopIO]]), which the pads' modules drive ([[Ddr3Pads]]).
  */
object Ddr3Xdc:

    /** Register the bus's constraints with the dump — said by the device that builds the bus, where it builds it
      * ([[Ddr3Side.instantiate]]): a level that instantiates no DDR3 writes none of these.
      */
    def register(): Unit = XdcRegistry.onEmit("ddr3")(text)

    /** The file's text, as the dump writes it. */
    val text: String =
        """|# The DDR3 bus (the MIG reference's pin map, on the top's own ports).
           |#
           |# The device is a single MT41K256M16TW-107 (x16), run at 1.5 V: the data pins are terminated at the fabric's own
           |# split termination (VREF-referenced — hence the bank's INTERNAL_VREF), the strobes and the clock are differential,
           |# and the command/address side is plain SSTL15. The chip select is tied low on the board (the reference's pin map has
           |# no CS# pin), which the controller's NOPs are written for.
           |#
           |# The bus is not in one bank: the lower byte's data, the CK and the whole command/address side are bank 35's, and the
           |# upper byte's data and strobe are bank 34's — the reference's own pad-function comments say so pin by pin
           |# (`ddr3_dq[8]` is `IO_L1N_T0_34`, and its strobe is a `_34` `DQS` site).
           |
           |# The MMCM and the pads' clock buffers are left to the placer: the pads' clocks go through the wizard's own global
           |# buffers, whose placement has no bearing on the pads' timing, so nothing here needs pinning.
           |set_property SLEW FAST [get_ports {DDR3_DQ[*]}]
           |set_property SLEW FAST [get_ports {DDR3_DQS_P[*]}]
           |set_property SLEW FAST [get_ports {DDR3_DQS_N[*]}]
           |set_property SLEW FAST [get_ports {DDR3_DM[*]}]
           |set_property SLEW FAST [get_ports {DDR3_ADDR[*]}]
           |set_property SLEW FAST [get_ports {DDR3_BA[*]}]
           |set_property SLEW FAST [get_ports {DDR3_RAS_N}]
           |set_property SLEW FAST [get_ports {DDR3_CAS_N}]
           |set_property SLEW FAST [get_ports {DDR3_WE_N}]
           |set_property SLEW FAST [get_ports {DDR3_CKE}]
           |set_property SLEW FAST [get_ports {DDR3_ODT}]
           |set_property SLEW FAST [get_ports {DDR3_CK_P}]
           |set_property SLEW FAST [get_ports {DDR3_CK_N}]
           |
           |set_property IOSTANDARD SSTL15 [get_ports {DDR3_ADDR[*]}]
           |set_property IOSTANDARD SSTL15 [get_ports {DDR3_BA[*]}]
           |set_property IOSTANDARD SSTL15 [get_ports {DDR3_RAS_N}]
           |set_property IOSTANDARD SSTL15 [get_ports {DDR3_CAS_N}]
           |set_property IOSTANDARD SSTL15 [get_ports {DDR3_WE_N}]
           |set_property IOSTANDARD SSTL15 [get_ports {DDR3_CKE}]
           |set_property IOSTANDARD SSTL15 [get_ports {DDR3_ODT}]
           |set_property IOSTANDARD SSTL15 [get_ports {DDR3_DM[*]}]
           |set_property IOSTANDARD SSTL15 [get_ports {DDR3_DQ[*]}]
           |set_property IOSTANDARD DIFF_SSTL15 [get_ports {DDR3_DQS_P[*]}]
           |set_property IOSTANDARD DIFF_SSTL15 [get_ports {DDR3_DQS_N[*]}]
           |set_property IOSTANDARD DIFF_SSTL15 [get_ports {DDR3_CK_P}]
           |set_property IOSTANDARD DIFF_SSTL15 [get_ports {DDR3_CK_N}]
           |set_property IOSTANDARD LVCMOS15 [get_ports {DDR3_RESET_N}]
           |
           |# The data side's input termination, and the reference the split termination needs. The reference terminates every
           |# data pin this way and states the reference voltage for bank 35 — the bank the lower byte and the command side sit
           |# in; the line below is the reference's own, unchanged, like the rest of this file. The strobes carry no input
           |# termination: the design drives them and never reads them (Ddr3Pads's header says why).
           |#
           |# Spelled out one pin per line: an XDC has no control structures (a `for` loop is rejected and its constraints are
           |# dropped), which the one-pin-per-line style below already follows.
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[0]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[1]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[2]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[3]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[4]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[5]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[6]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[7]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[8]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[9]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[10]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[11]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[12]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[13]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[14]}]
           |set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[15]}]
           |set_property INTERNAL_VREF 0.750 [get_iobanks 35]
           |
           |# The pins, one per line, as the reference maps them.
           |set_property PACKAGE_PIN P5 [get_ports DDR3_CK_P]
           |set_property PACKAGE_PIN P4 [get_ports DDR3_CK_N]
           |set_property PACKAGE_PIN P1 [get_ports {DDR3_ADDR[0]}]
           |set_property PACKAGE_PIN M6 [get_ports {DDR3_ADDR[1]}]
           |set_property PACKAGE_PIN K3 [get_ports {DDR3_ADDR[2]}]
           |set_property PACKAGE_PIN K4 [get_ports {DDR3_ADDR[3]}]
           |set_property PACKAGE_PIN M5 [get_ports {DDR3_ADDR[4]}]
           |set_property PACKAGE_PIN J6 [get_ports {DDR3_ADDR[5]}]
           |set_property PACKAGE_PIN N2 [get_ports {DDR3_ADDR[6]}]
           |set_property PACKAGE_PIN K6 [get_ports {DDR3_ADDR[7]}]
           |set_property PACKAGE_PIN P2 [get_ports {DDR3_ADDR[8]}]
           |set_property PACKAGE_PIN L1 [get_ports {DDR3_ADDR[9]}]
           |set_property PACKAGE_PIN M2 [get_ports {DDR3_ADDR[10]}]
           |set_property PACKAGE_PIN P6 [get_ports {DDR3_ADDR[11]}]
           |set_property PACKAGE_PIN L4 [get_ports {DDR3_ADDR[12]}]
           |set_property PACKAGE_PIN L5 [get_ports {DDR3_ADDR[13]}]
           |set_property PACKAGE_PIN N5 [get_ports {DDR3_ADDR[14]}]
           |set_property PACKAGE_PIN J4 [get_ports {DDR3_BA[0]}]
           |set_property PACKAGE_PIN R1 [get_ports {DDR3_BA[1]}]
           |set_property PACKAGE_PIN M1 [get_ports {DDR3_BA[2]}]
           |set_property PACKAGE_PIN M3 [get_ports DDR3_RAS_N]
           |set_property PACKAGE_PIN N3 [get_ports DDR3_CAS_N]
           |set_property PACKAGE_PIN L6 [get_ports DDR3_WE_N]
           |set_property PACKAGE_PIN N4 [get_ports DDR3_CKE]
           |set_property PACKAGE_PIN L3 [get_ports DDR3_ODT]
           |set_property PACKAGE_PIN F4 [get_ports DDR3_RESET_N]
           |set_property PACKAGE_PIN E2 [get_ports {DDR3_DM[0]}]
           |set_property PACKAGE_PIN H3 [get_ports {DDR3_DM[1]}]
           |set_property PACKAGE_PIN E1 [get_ports {DDR3_DQS_P[0]}]
           |set_property PACKAGE_PIN D1 [get_ports {DDR3_DQS_N[0]}]
           |set_property PACKAGE_PIN K2 [get_ports {DDR3_DQS_P[1]}]
           |set_property PACKAGE_PIN J2 [get_ports {DDR3_DQS_N[1]}]
           |set_property PACKAGE_PIN B2 [get_ports {DDR3_DQ[0]}]
           |set_property PACKAGE_PIN F1 [get_ports {DDR3_DQ[1]}]
           |set_property PACKAGE_PIN B1 [get_ports {DDR3_DQ[2]}]
           |set_property PACKAGE_PIN D2 [get_ports {DDR3_DQ[3]}]
           |set_property PACKAGE_PIN C2 [get_ports {DDR3_DQ[4]}]
           |set_property PACKAGE_PIN F3 [get_ports {DDR3_DQ[5]}]
           |set_property PACKAGE_PIN A1 [get_ports {DDR3_DQ[6]}]
           |set_property PACKAGE_PIN G1 [get_ports {DDR3_DQ[7]}]
           |set_property PACKAGE_PIN J5 [get_ports {DDR3_DQ[8]}]
           |set_property PACKAGE_PIN G2 [get_ports {DDR3_DQ[9]}]
           |set_property PACKAGE_PIN K1 [get_ports {DDR3_DQ[10]}]
           |set_property PACKAGE_PIN G3 [get_ports {DDR3_DQ[11]}]
           |set_property PACKAGE_PIN H2 [get_ports {DDR3_DQ[12]}]
           |set_property PACKAGE_PIN H5 [get_ports {DDR3_DQ[13]}]
           |set_property PACKAGE_PIN J1 [get_ports {DDR3_DQ[14]}]
           |set_property PACKAGE_PIN H4 [get_ports {DDR3_DQ[15]}]
           |
           |# ── Timing exceptions ──
           |#
           |# Three facts about the pads that the tools cannot read off the netlist. Each one is stated here rather than
           |# worked around in the RTL, and none of them changes the design's behavior — the simulator is what holds the design
           |# to that.
           |#
           |# 1. The pads' enables are fabric-cycle levels: the design stages a word per cycle and holds `DDR3_DQ_OE` and
           |#    `DDR3_DQS_OE` for a whole write — several cycles of it — so a serializer's tristate inputs are captured well
           |#    after the tools' per-edge model asks for them, while they are still held. (The words themselves stay
           |#    single-cycle: they change every cycle and must be captured by that cycle's load edge.)
           |set_multicycle_path 4 -setup -to [get_pins -hierarchical {*T1 *T2 *T3 *T4}]
           |set_multicycle_path 3 -hold  -to [get_pins -hierarchical {*T1 *T2 *T3 *T4}]
           |# The housekeeping pins' own levels (CKE, ODT, RESET_N) are the same kind of signal — a level that changes once per
           |# cycle at most — loaded by a data-grid `ODDR`. One pattern for all three: the level pads are one module
           |# instantiated three times (Ddr3LevelPads), so what names them is the register they share. The pattern's own
           |# form is load-bearing: hierarchical search matches the pattern against the full path, so a leading `*/` names
           |# nothing at all (measured: with that form the paths stayed unrelaxed — 1.875 ns of requirement against a 1.5 ns
           |# route — and five endpoints missed setup by up to 0.76 ns).
           |set_multicycle_path 2 -setup -to [get_pins -hierarchical {*levelDrive/D1 *levelDrive/D2}]
           |set_multicycle_path 1 -hold  -to [get_pins -hierarchical {*levelDrive/D1 *levelDrive/D2}]
           |#
           |# 2. The forwarded clock. The device takes the CK as the reference its commands and its capture are defined against,
           |#    so the pad's own delay is not a requirement — and the CK's *relationship* to the strobe and the data is the
           |#    shared grid the design launches all of them from, not this delay.
           |set_false_path -to [get_ports {DDR3_CK_P DDR3_CK_N}]
           |#
           |# 3. The serializers' resets come from the controller's domain and are held for the whole bring-up — microseconds
           |#    against a 5 ns cycle. What has to hold is that the shift paths are cleared while the registers whose words they
           |#    load are, which the shared reset gives them; beating the load clock's edges is not a requirement a reset has.
           |#    One pattern for every serializer: each is a pads module's instance named `<bus>Drive<bit>` ([[Ddr3Pads]]), so
           |#    the index-bearing pattern is what reaches them (a leading `*/` would not, as the level pins' note above says).
           |set_false_path -to [get_pins -hierarchical {*Drive*/RST}]
           |""".stripMargin
