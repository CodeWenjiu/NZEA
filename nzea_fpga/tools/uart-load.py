#!/usr/bin/env python3
"""Send a hex file via UART BootFsm protocol, then read back response.

Usage: uart-load.py <hex_file> <port> [--baud 115200] [--addr 0]
"""

import argparse
import struct
import sys
import time

import serial

MAGIC = 0xB007B007


def read_hex(path: str) -> list[int]:
    words = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("//") or line.startswith("#"):
                continue
            for token in line.split():
                try:
                    words.append(int(token, 16))
                except ValueError:
                    continue
    return words


def send_boot(ser: serial.Serial, words: list[int], base_addr: int = 0):
    """Send magic + addr + count + data, then read back response."""
    count = len(words)
    print(f"Sending {count} words to addr 0x{base_addr:08X}…")
    sys.stdout.flush()

    header = (
        struct.pack("<I", MAGIC)
        + struct.pack("<I", base_addr & 0x7FFF)
        + struct.pack("<I", count)
    )
    print("  writing header…", end=" ", flush=True)
    ser.write(header)
    print("ok")

    data = b"".join(struct.pack("<I", w) for w in words)
    print("  writing data…", end=" ", flush=True)
    ser.write(data)
    print("ok")

    # Wait for transmission + FPGA processing
    bits = (12 + count * 4) * 10
    wait_s = bits / ser.baudrate + 0.5
    time.sleep(wait_s)

    # Read back initial FPGA output (time-bounded to avoid hanging
    # on programs that loop forever printing to UART).
    print("Response:", flush=True)
    ser.timeout = 0.1
    deadline = time.monotonic() + 1.0
    buf = b""
    while time.monotonic() < deadline:
        b = ser.read(1)
        if b:
            buf += b
    if not buf:
        print("  (no data)")
        return
    # Try to display as ASCII, fall back to hex words
    printable = all(0x20 <= c < 0x7F or c in (0x0A, 0x0D) for c in buf)
    if printable:
        text = buf.decode("ascii", errors="replace").rstrip()
        print(f"  {text}")
    else:
        for i in range(0, len(buf) - 3, 4):
            w = struct.unpack(">I", buf[i : i + 4])[0]
            print(f"  word[{i // 4:2d}] = 0x{w:08X}")


def main():
    parser = argparse.ArgumentParser(description="UART bootloader for NzeaTile")
    parser.add_argument("hex_file", help="Verilog hex file path")
    parser.add_argument("port", help="Serial port (e.g. COM3, /dev/ttyUSB0)")
    parser.add_argument("--baud", type=int, default=115200, help="Baud rate")
    parser.add_argument("--addr", type=lambda x: int(x, 0), default=0)
    args = parser.parse_args()

    words = read_hex(args.hex_file)
    if not words:
        print("Error: no words in hex file")
        sys.exit(1)

    ser = serial.Serial(args.port, args.baud, timeout=1, write_timeout=None)
    time.sleep(0.05)
    send_boot(ser, words, args.addr)
    ser.close()


if __name__ == "__main__":
    main()
