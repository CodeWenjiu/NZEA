// UART boot protocol: reads hex file, sends via BootFsm protocol over UART.
// Included inside module tb — references uart_send_word, byte_reverse.
// Array size set at compile time via +define+HEX_BUF_WORDS; defaults to 256.

`ifndef HEX_BUF_WORDS
`define HEX_BUF_WORDS 256
`endif

task boot_from_hex; input [1023:0] filename;
    reg [31:0] words [0:`HEX_BUF_WORDS-1];
    integer i, count;
begin
    for (i = 0; i < `HEX_BUF_WORDS; i = i + 1) words[i] = 32'hDEADBEEF;
    $readmemh(filename, words);
    count = 0;
    while (count < `HEX_BUF_WORDS && words[count] != 32'hDEADBEEF)
        count = count + 1;
    if (count == 0) begin
        $display("ERROR: empty or all-zero hex file '%s'", filename);
        $finish;
    end
    $display("[%0t] Boot: loading %0d words from %s", $time, count, filename);
    uart_send_word(byte_reverse(32'hB007B007));
    uart_send_word(byte_reverse(32'h00000000));
    uart_send_word(byte_reverse(count[31:0]));
    for (i = 0; i < count; i = i + 1)
        uart_send_word(byte_reverse(words[i]));
    $display("[%0t] Boot: %0d words sent", $time, count);
end endtask