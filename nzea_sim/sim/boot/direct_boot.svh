// Direct RAM load: copies hex data from boot_buf into tile RAM via hierarchy.
// Included inside the initial block — references boot_buf, boot_bi, hex_file,
// tb.tile.ram.memBytes_*_ext.Memory.

if ($value$plusargs("HEX_SIZE=%d", hex_size)) begin
    $readmemh(hex_file, boot_buf);
    $display("[%0t] Boot: direct RAM load %0d words from %s", $time, hex_size, hex_file);
end else begin
    for (boot_bi = 0; boot_bi < `HEX_BUF_WORDS; boot_bi = boot_bi + 1) boot_buf[boot_bi] = 32'hDEADBEEF;
    $readmemh(hex_file, boot_buf);
    hex_size = 0;
    while (hex_size < `HEX_BUF_WORDS && boot_buf[hex_size] != 32'hDEADBEEF) hex_size = hex_size + 1;
    $display("[%0t] Boot: direct RAM load %0d words from %s", $time, hex_size, hex_file);
end
for (boot_bi = 0; boot_bi < hex_size; boot_bi = boot_bi + 1) begin
    tb.ram.memBytes_0_ext.Memory[boot_bi] = boot_buf[boot_bi][7:0];
    tb.ram.memBytes_1_ext.Memory[boot_bi] = boot_buf[boot_bi][15:8];
    tb.ram.memBytes_2_ext.Memory[boot_bi] = boot_buf[boot_bi][23:16];
    tb.ram.memBytes_3_ext.Memory[boot_bi] = boot_buf[boot_bi][31:24];
end