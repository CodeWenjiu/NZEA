// Synthesis stub: undriven resp_bits -> setundef -anyconst preserves full Core
module DbusSynthBridge(
  input         clock,
                reset,
                io_bus_resp_ready,
  output        io_bus_resp_valid,
  output [31:0] io_bus_resp_bits,
  output        io_bus_req_ready,
  input         io_bus_req_valid,
  input  [31:0] io_bus_req_bits_addr,
                io_bus_req_bits_wdata,
  input         io_bus_req_bits_wen,
  input  [3:0]  io_bus_req_bits_wstrb
);
  assign io_bus_req_ready  = 1'b1;
  assign io_bus_resp_valid = io_bus_req_valid & ~io_bus_req_bits_wen;
  /* io_bus_resp_bits intentionally undriven for Yosys setundef -anyconst */
endmodule
