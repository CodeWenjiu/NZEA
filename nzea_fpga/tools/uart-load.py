#!/usr/bin/env python3
"""Send a hex file to NzeaTile via UART boot protocol.
Usage: python3 fpga/scripts/uart-load.py <hex_file> <serial_port> [--baud 100000]
Protocol:
  1. 4-byte magic: 0xB007B007 (big-endian)
  2. 4-byte word address (big-endian)
  3. 4-byte word count (big-endian)
  4. count*4 bytes of data (big-endian words, MSB first)
"""

import sys, struct, serial, time, argparse

MAGIC = 0xB007B007

def read_hex(path: str) -> list[int]:
    """Read a Verilog hex file (one 32-bit word per line, no @address headers)."""
    words = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("//") or line.startswith("#"):
                continue
            # Handle lines with multiple words separated by spaces
            for token in line.split():
                try:
                    words.append(int(token, 16))
                except ValueError:
                    continue
    return words

def send_boot(ser: serial.Serial, words: list[int], base_addr: int = 0):
    """Send magic + addr + count + data over serial."""
    count = len(words)
    print(f"Sending {count} words to addr 0x{base_addr:08X}…")
    sys.stdout.flush()

    # Magic → addr → count → data
    # BootFsm shift reg: Cat(rx_byte, shift[31:8]) → first byte → MSB
    # Header: send LSB first so magic reassembles correctly
    header = struct.pack("<I", MAGIC) + struct.pack("<I", base_addr & 0x7FFF) + struct.pack("<I", count)
    print("  writing header…", end=" ", flush=True)
    ser.write(header)
    print("ok")

    data = b"".join(struct.pack(">I", w) for w in words)
    print("  writing data…", end=" ", flush=True)
    ser.write(data)
    print("ok")

    # Wait for data to transmit (no flush – may hang on some UART hardware)
    bits = (12 + count * 4) * 10  # 8N1 = 10 bits/byte
    wait_s = bits / ser.baudrate + 0.01
    time.sleep(wait_s)
    print("Done.")

def main():
    parser = argparse.ArgumentParser(description="UART bootloader for NzeaTile")
    parser.add_argument("hex_file", help="Verilog hex file path")
    parser.add_argument("port", help="Serial port (e.g. COM3 or /dev/ttyUSB0)")
    parser.add_argument("--baud", type=int, default=100000, help="Baud rate (default: 100000)")
    parser.add_argument("--addr", type=lambda x: int(x, 0), default=0, help="Word address in tile RAM (default: 0)")
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