// Direct RAM load: copies hex data from boot_buf into tile RAM via hierarchy.
// Included inside the initial block — references boot_buf, boot_bi, hex_file,
// tb.dut.tile.ram.mem_ext.Memory.

for (boot_bi = 0; boot_bi < 4096; boot_bi = boot_bi + 1) boot_buf[boot_bi] = 32'hDEADBEEF;
$readmemh(hex_file, boot_buf);
boot_bi = 0;
while (boot_bi < 4096 && boot_buf[boot_bi] != 32'hDEADBEEF) begin
    tb.dut.tile.ram.memBytes_0_ext.Memory[boot_bi] = boot_buf[boot_bi][7:0];
    tb.dut.tile.ram.memBytes_1_ext.Memory[boot_bi] = boot_buf[boot_bi][15:8];
    tb.dut.tile.ram.memBytes_2_ext.Memory[boot_bi] = boot_buf[boot_bi][23:16];
    tb.dut.tile.ram.memBytes_3_ext.Memory[boot_bi] = boot_buf[boot_bi][31:24];
    boot_bi = boot_bi + 1;
end
$display("[%0t] Boot: direct RAM load %0d words from %s", $time, boot_bi, hex_file);
