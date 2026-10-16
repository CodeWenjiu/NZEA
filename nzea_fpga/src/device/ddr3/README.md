# The DDR3 bus's numbers, and where they come from

The controller in this directory drives the board's single MT41K256M16TW-107 (x16, 4 Gb DDR3L run at 1.5 V) at 400 MHz
(tCK = 2500 ps, DDR3-800). Nothing here is a knob: the device, the board's routing and the rate are board facts, and the
constants the design runs on are the device's own requirements. This file keeps those requirements, their source, and how
this design meets them — so the next reader does not have to re-derive them from a 300-page PDF.

Sources, both beside this tree: the device's datasheet (`nzea_fpga/sim/vendor/micron/guides/4gb_ddr3l.pdf`, Rev. R) and
the Xilinx memory interface solution (`nzea_fpga/sim/vendor/micron/guides/ug586_7Series_MIS.pdf`, the reference whose
calibrated PHY is known good on this exact board).

## What the device requires

| Requirement | Value at tCK = 2500 ps | Source |
|---|---|---|
| Write DQS vs CK (`tDQSS`) | ±0.25 tCK = ±625 ps | datasheet, AC timings |
| Write preamble (`tWPRE`) | ≥ 0.9 tCK = 2250 ps | datasheet, `tWPRE Timing` |
| Write postamble (`tWPST`) | ≥ 0.3 tCK = 750 ps | datasheet, `tWPST Timing` |
| DQS falling edge vs CK (`tDSS`, `tDSH`) | 0.2 tCK = 500 ps | datasheet, AC timings |
| Read DQS vs CK (`tDQSCK`) | ±225–400 ps (grade- and voltage-dependent) | datasheet, AC timings |
| Read DQS-to-DQ skew (`tDQSQ`) | ≤ 150 ps for this grade | datasheet, AC timings |
| Read DQ hold from DQS (`tQH`) | 0.38 tCK | datasheet, AC timings |
| ODT turn-on/off after the pin (`ODTLon`, `ODTLoff`) | `CWL + AL − 2` = 3 tCK | datasheet, `Synchronous ODT Mode` |
| ODT minimum high time after a BL8 write (`ODTH8`) | 6 tCK | datasheet, `Synchronous ODT Mode` |
| ODT off before a READ | ≥ 0.5 tCK before the preamble | datasheet, `ODT Off During READs` |
| `RTT,nom` usable during writes | RZQ/2, RZQ/4, RZQ/6 only | datasheet, `Nominal ODT` |
| Write DQS vs its own DQ | **90°** (centred in the data eye) | UG586: `OCLK_DELAYED` clocks the DQS ODDR "to achieve the required 90° phase offset between the write DQS and its associated DQ bits"; the vendor device model samples DQ on the DQS edges |
| Write leveling | per byte lane: the x16's prime DQs are DQ0 and DQ8, "two prime DQ on a x16 enable each byte lane to be leveled independently" | datasheet, `Write Leveling` |
| Read centring | per byte group | UG586, `Read Leveling` |

## What this design does with them

- **Mode registers** (`Ddr3.scala`): MR0 = 0x0520 (BL8, sequential, CL 6, DLL reset, WR 6 — the 400 MHz JEDEC
  settings), MR1 = 0x0044 (output drive RZQ/6, `RTT,nom` = RZQ/6 — one of the values the datasheet allows during
  writes — DLL enabled, output buffers on, write leveling off after bring-up), MR2 = 0 (CWL 5, dynamic ODT off, so
  writes terminate through `RTT,nom`), MR3 = 0.
- **ODT**: the pin is high for the whole six-cycle write state, which is 12 tCK — so the device's own `ODTLon` of
  3 tCK lands 2 tCK before the burst's first beat and its turn-off lands long after the last one, and the pin is low
  everywhere else (reads and idles), which is what `ODT Off During READs` asks.
- **The write strobe train** (`Ddr3.scala`'s `dqsFlat`): two beats of low preamble before the burst's first edge
  (1.0 tCK ≥ 0.9) and three and a half beats of low postamble after its last (≥ 0.3), with the toggling word's
  transitions every beat in between.
- **The phase grids** (`Clocks`, `Ddr3Pads`): the device sees CK and the write data launched from one
  grid and the strobes from the grid a quarter period from it — the 90° offset UG586 requires of the write strobe
  against its own DQ, and what the reference gets from clocking the DQS ODDR with its delayed clock. Both lanes launch
  that way, and there is no per-lane alternative to reach for: the banks this board routes the bus into are high-range
  ones, which have no `ODELAYE2`, and this part has no PHASER — the MIG reference generated for it instantiates neither
  (the vendor demo's `.../phy/` tree: zero `ODELAYE2`, zero PHASER, and a `..._dqs_found_cal_hr` module for the HR
  banks), so it levels nothing on the write side either. The bus is not one bank, though: the pin map puts the lower
  byte, CK and the command side in bank 35 and the upper byte in bank 34 (its strobe is `IO_L3P_T0_DQS_34`) — a per-lane
  write phase is not a thing this device can hold. The board's own round rate is what says the launch is right; it is
  measured, not derived (`Ddr3Bringup.sv` states the fitted offset and why it is a fit).
- **The read capture**: a fixed clock with an `IDELAYE2` per DQ lane, tapped in 78 ps steps, plus the sweep that
  measures where each byte lane's window is and locks its tap (`Ddr3.scala`'s three planes). The two byte lanes take
  their own tap (`Ddr3PadNames.tapLanes`): what a tap cancels is a byte lane's own delay against the fabric's capture
  clock, which UG586 centres per byte group — and this board's two groups sit in different banks.

## What this design does not yet do

- **No write leveling**: the strobes' launch phase is the *fixed* quarter-period offset the reference uses, not a
  per-part, per-temperature measurement — and on this device it cannot be anything else: there is no `ODELAYE2` and no
  PHASER to level with, and MIG's own write-leveling logic drives blocks this part does not have, so the reference
  levels nothing here either. A board whose strobe-to-data skew exceeded the device's `tDSS`/`tDSH` window would need
  hardware this part lacks.
- **No DQS-based read capture and no per-bit deskew**: the capture is a fixed clock against delayed data, so it cannot
  track `tDQSCK` or centre each bit; UG586 does both with the read strobe and per-bit `IDELAY`s. What this design does
  have is a *measured, per-byte* tap: the sweep weighs three hypotheses about the two lanes' difference and locks the
  one the board's own rounds pass at (`Ddr3.scala`).
- **The strobes carry no input termination**: the MIG reference's own constraints set `IN_TERM UNTUNED_SPLIT_50` on the
  strobes; this design's strobe path is output-only (`OBUFTDS`), so the device's read strobe is not terminated at the
  FPGA. That is a difference from the reference on the read path, and it is not yet measured.
