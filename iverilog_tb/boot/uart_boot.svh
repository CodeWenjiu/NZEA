// UART boot protocol: reads hex file, sends via BootFsm protocol over UART.
// Included inside module tb — references uart_send_word, byte_reverse.

task boot_from_hex; input [1023:0] filename;
    reg [31:0] words [0:4095];
    integer i, count;
begin
    for (i = 0; i < 4096; i = i + 1) words[i] = 32'h00000000;
    $readmemh(filename, words);
    count = 4096;
    while (count > 0 && words[count - 1] == 32'h00000000)
        count = count - 1;
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
