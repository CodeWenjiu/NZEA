// UART TX tasks and byte_reverse utility for boot protocol.
// Included inside module tb — references uart_tx, clk, BAUD.

task uart_send_byte; input [7:0] d; reg [9:0] f; integer i; begin
    f={1'b1, d[7:0], 1'b0};
    for(i=0;i<10;i=i+1) begin uart_tx<=f[0]; f={1'b1,f[9:1]}; repeat(BAUD) @(posedge clk); end
end endtask

task uart_send_word; input [31:0] w; begin
    uart_send_byte(w[31:24]); uart_send_byte(w[23:16]);
    uart_send_byte(w[15:8]);  uart_send_byte(w[7:0]);
end endtask

function [31:0] byte_reverse; input [31:0] w; begin
    byte_reverse = {w[7:0], w[15:8], w[23:16], w[31:24]};
end endfunction
