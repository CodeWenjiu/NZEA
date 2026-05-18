// Direct RAM load: copies hex data from boot_buf into tile RAM via hierarchy.
// Included inside the initial block — references boot_buf, boot_bi, hex_file,
// tb.dut.tile.ram.mem_ext.Memory.

if ($value$plusargs("HEX_SIZE=%d", hex_size)) begin
    $readmemh(hex_file, boot_buf);
    $display("[%0t] Boot: direct RAM load %0d words from %s", $time, hex_size, hex_file);
end else begin
    for (boot_bi = 0; boot_bi < 32768; boot_bi = boot_bi + 1) boot_buf[boot_bi] = 32'hDEADBEEF;
    $readmemh(hex_file, boot_buf);
    hex_size = 0;
    while (hex_size < 32768 && boot_buf[hex_size] != 32'hDEADBEEF) hex_size = hex_size + 1;
    $display("[%0t] Boot: direct RAM load %0d words from %s", $time, hex_size, hex_file);
end
for (boot_bi = 0; boot_bi < hex_size; boot_bi = boot_bi + 1) begin
    tb.dut.tile.ram.memBytes_0_ext.Memory[boot_bi] = boot_buf[boot_bi][7:0];
    tb.dut.tile.ram.memBytes_1_ext.Memory[boot_bi] = boot_buf[boot_bi][15:8];
    tb.dut.tile.ram.memBytes_2_ext.Memory[boot_bi] = boot_buf[boot_bi][23:16];
    tb.dut.tile.ram.memBytes_3_ext.Memory[boot_bi] = boot_buf[boot_bi][31:24];
end
