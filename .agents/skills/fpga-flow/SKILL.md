---
name: fpga-flow
description: Use this skill when you need to build a bitstream and program the lxb_artix7 FPGA board (Vivado 2023.2), or load and run a program on it via UART. Covers the headless build/program/load pipeline and the environment quirks needed to drive Vivado from the CLI.
---

# fpga-flow

Run the full FPGA pipeline for the lxb_artix7 board headlessly: generate RTL →
build bitstream (Vivado synth + impl) → program the board over JTAG → load a
program over UART. This skill captures the environment knowledge (Vivado is not
on PATH, runs in an FHS nix shell) and the exact commands, all verified end-to-end.

## The full pipeline (one command per stage)

```sh
# 1. Generate RTL + Vivado project (also emits build_all.tcl and program.tcl)
just dump --target fpga --fpgaBoard lxb_artix7 --isa riscv32im

# 2. Build bitstream (synth + impl) — headless, ~5 min on this machine
just vivado-build

# 3. Program the FPGA over JTAG — board must be connected via Digilent JTAG
just vivado-program

# 4. Load and run a program over UART
just uart-load nzea_sim/sim/tile/hello_loop.hex /dev/serial/by-id/usb-1a86_USB_Serial-if00-port0 115200
```

Or combine 2+3 with one command: `just vivado-flow` (build then program).

## Environment knowledge (critical, non-obvious)

Vivado 2023.2 is **not on PATH** and will not run from a plain shell:

- Install root: `/mnt/data/tools/Xilinx/Vivado/2023.2`
- It is a Linux binary with a `/bin/bash` shebang, but NixOS has no `/bin/bash`,
  so it must run inside an FHS environment: `nix-shell nzea_fpga/scripts/vivado-batch.nix`
- `vivado-batch.nix` is a copy of the Xilinx `shell.nix` (full library set) with
  the **blocking GUI launch stripped** from the profile. The original `shell.nix`
  ends with a bare `vivado` that starts the GUI and blocks forever — never use it
  for batch work.

### How to drive Vivado headlessly

`nix-shell --run` / `--command` are **unreliable** under this FHS env (the
`runScript` is an interactive bash). The working pattern is to **pipe commands
via stdin** (a heredoc), as `vivado-flow.sh` does:

```bash
nix-shell nzea_fpga/scripts/vivado-batch.nix <<NIXEOF
cd "$VIVADO_DIR"
vivado -mode batch -source build_all.tcl -nolog -nojournal
NIXEOF
```

Always use `-mode batch -nolog -nojournal`; batch exits after the script runs.

## The generated scripts (from vivado-project.py / edalize)

Under `build/fpga/lxb_artix7/<isa>/hw/vivado/`:

| File | Purpose |
|------|---------|
| `build_all.tcl` | One-shot: create project + synth + impl + write bitstream |
| `program.tcl` | JTAG programming (delegates to edalize `nzea_fpga_pgm.tcl`) |
| `nzea_fpga_pgm.tcl` | edalize's program snippet: open hw server, find part, program |
| `nzea_fpga.bit` | Final bitstream (copied there by run.tcl from impl_1) |

Both `build_all.tcl` and `program.tcl` **`cd` to the work root first** — required
because edalize's scripts assume cwd = work root (e.g. run.tcl copies the
bitstream to `[pwd]/nzea_fpga.bit`). This makes them work regardless of where
Vivado was launched.

`program.tcl` guards on the bitstream existing and sets `$argv` explicitly
(`set argv [list <part> {<bitstream>}]`) before sourcing the pgm snippet —
robust across Vivado versions vs relying on `source`'s trailing args.

## Invocation reference

### `just vivado-build`
Run `build_all.tcl` headlessly (synth + impl + bitstream). Produces/refreshes
`nzea_fpga.bit`. **Every run fully rebuilds** because the project is recreated
with `create_project -force` — expect ~5 min, longer on first run (FHS env build).

### `just vivado-program`
Run `program.tcl` headlessly. Searches the hardware server for a JTAG target
containing part `xc7a200tsbg484-1`, programs it. Requires the board connected
via the Digilent USB-JTAG cable. Success looks like:
`INFO: SUCCESS! FPGA xc7a200tsbg484-1 successfully programmed` and
`End of startup status: HIGH`.

### `just vivado-flow`
Build then program, in sequence.

All three accept `board='lxb_artix7'` and `isa='riscv32im'` (defaults).

## Loading a program over UART

`just uart-load <hex> <port> <baud>` sends a hex file to the tile through the
UART bootloader baked into the bitstream, then the program runs on the core.

```sh
just uart-load nzea_sim/sim/tile/hello_loop.hex /dev/serial/by-id/usb-1a86_USB_Serial-if00-port0 115200
```

`hello_loop.hex` prints `Hello World` in a loop — repeated output is the success
signal. (`nzea_sim/sim/tile/hello.hex` runs once and exits.)

### Finding the correct serial port

**Prefer the stable `by-id` alias over `/dev/ttyUSB*`.** The board's UART is a
CH340 (QinHeng, USB vendor `1a86`, driver `ch341`), and udev exposes a stable
symlink that does not change with enumeration:

```sh
ls -l /dev/serial/by-id/
# → usb-1a86_USB_Serial-if00-port0 -> ../../ttyUSB1
```

Use that path directly:

```sh
just uart-load ... /dev/serial/by-id/usb-1a86_USB_Serial-if00-port0 115200
```

To identify the port (e.g. when several USB serials are plugged in), inspect the
USB vendor ID — the CH340 reports `1a86`:

```sh
udevadm info --query=property --name=/dev/ttyUSB0 | grep -E 'ID_VENDOR_ID|ID_MODEL_ID|ID_USB_DRIVER'
# → ID_VENDOR_ID=1a86  (CH340)
#    ID_USB_DRIVER=ch341
# → ID_VENDOR_ID=0403  (would be an FTDI, a different device)
```

### Pitfall: serial device name changes
The USB-UART enumerates as `/dev/ttyUSB*`, and the number **is not stable** — it
can be `ttyUSB0` one session and `ttyUSB1` the next (or after replug). The
`/dev/serial/by-id/` symlink is immune to this, which is why it is preferred. If
you must use a raw `ttyUSB*` path, check `ls /dev/ttyUSB*` first and use the one
that actually exists.

## Troubleshooting

- **`vivado` not found / bad interpreter** — you're outside the FHS env. Run
  through `vivado-flow.sh` / `nix-shell vivado-batch.nix`.
- **nix-shell hangs** — you're using the original `/mnt/data/tools/Xilinx/shell.nix`
  (GUI profile blocks). Use `vivado-batch.nix`.
- **`create_project -force` wipes runs** — expected; each build is a full rebuild.
- **Program finds no target** — board not connected / JTAG cable unplugged /
  hardware server not running. Check `open_hw_manager` connectivity manually in
  a Vivado Tcl shell.
- **Vivado residue files** (`vivado.jou`, `vivado_pid*.str`, `*.backup.log`) are
  gitignored; delete freely.
