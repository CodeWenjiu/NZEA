#!/usr/bin/env python3
"""BootFSM FPGA validation: send test data via UART boot protocol, verify readback.

Usage:
  python bootfsm_test.py [--port /dev/ttyUSB1] [--baud 115200] [--words 16]

Protocol:
  1. Connect to FPGA serial port
  2. Send boot sequence: magic + addr + size + data words
  3. Wait for FPGA to read back and send the same number of words
  4. Compare and report

LED indicators on FPGA:
  [0] RX activity   [1] FSM busy    [2] Init done
  [3] Boot done     [4] Sending     [5] All done
"""

import argparse
import struct
import sys

try:
    import serial
except ImportError:
    print("pyserial required: pip install pyserial")
    sys.exit(1)


MAGIC = 0xB007B007


def make_test_data(n: int) -> list[int]:
    return [0xDEAD0000 + i for i in range(n)]


def send_boot(ser: serial.Serial, addr: int, words: list[int]) -> None:
    n = len(words)
    print(f"Sending boot: addr=0x{addr:08X} size={n} words")
    ser.write(struct.pack("<I", MAGIC))
    ser.write(struct.pack("<I", addr))
    ser.write(struct.pack("<I", n))
    for w in words:
        ser.write(struct.pack("<I", w))
    print(f"  sent {12 + n * 4} bytes total")


def recv_readback(ser, n_words, timeout=5.0):
    n_bytes = n_words * 4
    print(f"Waiting for {n_bytes} bytes from FPGA (timeout={timeout}s)...")
    ser.timeout = timeout
    data = ser.read(n_bytes)
    if len(data) < n_bytes:
        print(f"  TIMEOUT: received only {len(data)}/{n_bytes} bytes")
    words = list(struct.iter_unpack("<I", data))
    print(f"  received {len(data)} bytes = {len(words)} words")
    return [w[0] for w in words]


def verify(sent, recv):
    all_ok = True
    n = min(len(sent), len(recv))
    for i in range(n):
        ok = sent[i] == recv[i]
        if not ok:
            all_ok = False
        mark = "✅" if ok else "❌"
        print(f"  [{i:3d}] sent=0x{sent[i]:08X}  recv=0x{recv[i]:08X}  {mark}")
    if len(recv) < len(sent):
        print(f"  (missing {len(sent) - len(recv)} words)")
        all_ok = False
    elif len(recv) > len(sent):
        print(f"  (extra {len(recv) - len(sent)} words)")
        all_ok = False
    return all_ok


def main():
    parser = argparse.ArgumentParser(description="BootFSM FPGA validation")
    parser.add_argument("--port", default="/dev/ttyUSB1", help="Serial port")
    parser.add_argument("--baud", type=int, default=115200, help="Baud rate")
    parser.add_argument("--words", type=int, default=16, help="Number of test words")
    parser.add_argument("--addr", type=int, default=0, help="Boot target address")
    parser.add_argument(
        "--timeout", type=float, default=5.0, help="Readback timeout (s)"
    )
    parser.add_argument(
        "--list", action="store_true", help="List serial ports and exit"
    )
    args = parser.parse_args()

    if args.list:
        from serial.tools.list_ports import comports

        for p in comports():
            print(f"  {p.device}  {p.description}")
        return

    test_data = make_test_data(args.words)

    print(f"Opening {args.port} @ {args.baud} baud ...")
    ser = serial.Serial(args.port, args.baud, timeout=1)

    # ── Send boot sequence ──
    send_boot(ser, args.addr, test_data)

    # ── Wait for readback ──
    recv_data = recv_readback(ser, args.words, timeout=args.timeout)
    ser.close()

    # ── Verify ──
    print()
    ok = verify(test_data, recv_data)
    print()
    if ok:
        print("RESULT: PASS ✅ — all words match")
    else:
        print("RESULT: FAIL ❌ — mismatches detected")
        sys.exit(1)


if __name__ == "__main__":
    main()
