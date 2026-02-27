// Synthesis stub: undriven resp_bits -> setundef -anyconst preserves full Core
module IbusSynthBridge(
  input         clock,
                reset,
                io_bus_resp_ready,
  output        io_bus_resp_valid,
  output [31:0] io_bus_resp_bits,
  input         io_bus_req_valid,
  input  [31:0] io_bus_req_bits
);
  assign io_bus_resp_valid = io_bus_req_valid;
  /* io_bus_resp_bits intentionally undriven for Yosys setundef -anyconst */
endmodule
