// The Micron DDR3 model, configured for the board's part — MT41K256M16TW-107 (4Gb, x16, DDR3L-1866 bin).
//
// The defines are exactly the ones MIG's own wrapper generator sets (`ddr3_model_wrapper.ttcl`, shipped beside the
// model): the density picks the parameter table `ddr3.v` includes, the speed grade selects the timing set, the
// width selects the x16 geometry, and MAX_MEM enables the memory array the scenarios write through.
//
// Provenance and the update path are in README.md beside this file. Do not edit `ddr3.v` or the parameter table:
// they are the vendor's files verbatim, and the warranty header inside `ddr3.v` must stay with them.
`define den4096Mb
`define sg107
`define x16
`define MAX_MEM
`include "ddr3.v"
