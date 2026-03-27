#!/usr/bin/env python3
"""bmsmon - BLE Battery Monitor for Redodo/LiTime/PowerQueen LiFePO4 batteries."""

import argparse
import asyncio
import struct
import sys

from bleak import BleakClient, BleakScanner

# BLE characteristics
SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
FFE1_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"  # RX (notify)
FFE2_UUID = "0000ffe2-0000-1000-8000-00805f9b34fb"  # TX (write)

# Protocol constants
QUERY_STATUS = bytes([0x00, 0x00, 0x04, 0x01, 0x13, 0x55, 0xAA, 0x17])
QUERY_SERIAL = bytes([0x00, 0x00, 0x04, 0x01, 0x10, 0x55, 0xAA, 0x14])
QUERY_FW_VER = bytes([0x00, 0x00, 0x04, 0x01, 0x16, 0x55, 0xAA, 0x1A])

# Known battery name prefixes (Beken BLE module, same protocol)
KNOWN_PREFIXES = ("R-12", "R-24", "RO-12", "RO-24",  # Redodo
                  "L-12", "L-24", "L-51", "LT-",      # LiTime
                  "P-12", "P-24", "PQ-12", "PQ-24",   # PowerQueen
                  "S-", "SS-")                          # Starry Sea

BATTERY_STATES = {0x0000: "Idle", 0x0001: "Charging", 0x0002: "Discharging", 0x0004: "Disabled"}

PROTECTION_FLAGS = {
    0x00000004: "Over Charge",
    0x00000020: "Over-discharge",
    0x00000040: "Charge Over Current",
    0x00000080: "Discharge Over Current",
    0x00000100: "High Temp (charge)",
    0x00000200: "High Temp (discharge)",
    0x00000400: "Low Temp (charge)",
    0x00000800: "Low Temp (discharge)",
    0x00004000: "Short Circuit",
}


def is_compatible(name: str | None) -> bool:
    if not name:
        return False
    return any(name.startswith(p) for p in KNOWN_PREFIXES)


async def scan_batteries(timeout: int = 15) -> list:
    """Scan for compatible BLE batteries."""
    print(f"Scanning for batteries ({timeout}s)...")
    devices = await BleakScanner.discover(timeout=timeout)
    batteries = []
    for d in devices:
        if is_compatible(d.name):
            batteries.append(d)
            print(f"  Found: {d.name} ({d.address})")
    if not batteries:
        print("  No compatible batteries found.")
    return batteries


async def query_battery(address: str, scan_timeout: int = 15) -> dict | None:
    """Connect to a battery and read telemetry."""
    device = None
    for attempt in range(3):
        device = await BleakScanner.find_device_by_address(address, timeout=scan_timeout)
        if device:
            break

    if not device:
        print(f"  Device {address} not found after scanning.", file=sys.stderr)
        return None

    async with BleakClient(device, timeout=30) as client:
        all_data = bytearray()
        done = asyncio.Event()

        def on_notify(_sender, data):
            all_data.extend(data)
            if len(all_data) >= 80:
                done.set()

        await client.start_notify(FFE1_UUID, on_notify)
        await client.write_gatt_char(FFE2_UUID, QUERY_STATUS, response=False)

        try:
            await asyncio.wait_for(done.wait(), timeout=10)
        except asyncio.TimeoutError:
            pass

        await client.stop_notify(FFE1_UUID)

    return parse_telemetry(bytes(all_data), device.name)


def parse_telemetry(data: bytes, name: str | None = None) -> dict | None:
    """Parse a telemetry response into a dict."""
    if len(data) < 50:
        print(f"  Insufficient data ({len(data)} bytes)", file=sys.stderr)
        return None

    def u16(o):
        return struct.unpack_from("<H", data, o)[0]

    def i16(o):
        return struct.unpack_from("<h", data, o)[0]

    def u32(o):
        return struct.unpack_from("<I", data, o)[0]

    def i32(o):
        return struct.unpack_from("<i", data, o)[0]

    result = {
        "name": name,
        "cell_sum_voltage": u32(8) / 1000.0,
        "total_voltage": u16(12) / 1000.0,
        "cells": [],
        "current": i32(48) / 1000.0,
        "cell_temp": i16(52),
        "mosfet_temp": i16(54),
    }

    # Parse cell voltages (offset 16, 2 bytes each)
    for i in range(16):
        offset = 16 + i * 2
        if offset + 2 > len(data):
            break
        cv = u16(offset) / 1000.0
        if 0.5 < cv < 5.0:
            result["cells"].append(cv)

    if len(data) > 66:
        result["remaining_ah"] = u16(62) / 100.0
        result["full_charge_ah"] = u32(64) / 100.0

    if len(data) > 92:
        result["state"] = BATTERY_STATES.get(u16(88), f"Unknown(0x{u16(88):04x})")
        result["soc"] = u16(90)
        result["soh"] = u32(92)

    if len(data) > 100:
        result["cycles"] = u32(96)

    # Protection flags
    if len(data) > 84:
        prot = struct.unpack_from("<Q", data, 76)[0]
        active = [name for flag, name in PROTECTION_FLAGS.items() if prot & flag]
        result["protections"] = active

    return result


def print_telemetry(t: dict):
    """Pretty-print a telemetry dict."""
    name = t.get("name", "Unknown")
    print(f"\n{'─' * 50}")
    print(f"  {name}")
    print(f"{'─' * 50}")
    print(f"  Total Voltage:      {t['total_voltage']:.3f} V")
    print(f"  Cell Sum Voltage:   {t['cell_sum_voltage']:.3f} V")
    for i, cv in enumerate(t["cells"], 1):
        print(f"    Cell {i}:           {cv:.3f} V")
    if len(t["cells"]) > 1:
        spread = (max(t["cells"]) - min(t["cells"])) * 1000
        print(f"    Spread:           {spread:.0f} mV")
    print(f"  Current:            {t['current']:.3f} A")
    print(f"  Cell Temp:          {t['cell_temp']} °C")
    print(f"  MOSFET Temp:        {t['mosfet_temp']} °C")
    if "remaining_ah" in t:
        print(f"  Remaining:          {t['remaining_ah']:.2f} Ah")
    if "full_charge_ah" in t:
        print(f"  Full Charge Cap:    {t['full_charge_ah']:.2f} Ah")
    if "soc" in t:
        print(f"  SOC:                {t['soc']}%")
    if "soh" in t:
        print(f"  SOH:                {t['soh']}%")
    if "state" in t:
        print(f"  State:              {t['state']}")
    if "cycles" in t:
        print(f"  Cycles:             {t['cycles']}")
    if t.get("protections"):
        print(f"  ⚠ Protections:      {', '.join(t['protections'])}")


async def main():
    parser = argparse.ArgumentParser(description="BLE Battery Monitor for Redodo/LiTime/PowerQueen LiFePO4")
    parser.add_argument("--scan", action="store_true", help="Scan for compatible batteries")
    parser.add_argument("--address", "-a", type=str, help="MAC address of battery to query")
    parser.add_argument("--all", action="store_true", help="Query all batteries found in scan")
    parser.add_argument("--watch", "-w", type=float, metavar="SECS",
                        help="Continuously poll at interval (seconds)")
    parser.add_argument("--timeout", "-t", type=int, default=15, help="BLE scan timeout (default: 15s)")
    parser.add_argument("--json", action="store_true", help="Output as JSON")
    args = parser.parse_args()

    if args.scan:
        await scan_batteries(args.timeout)
        return

    if args.address:
        addresses = [args.address]
    elif args.all:
        batteries = await scan_batteries(args.timeout)
        addresses = [b.address for b in batteries]
        if not addresses:
            return
    else:
        parser.print_help()
        return

    if args.watch:
        try:
            while True:
                print(f"\033[2J\033[H", end="")  # clear screen
                for addr in addresses:
                    t = await query_battery(addr, scan_timeout=args.timeout)
                    if t:
                        if args.json:
                            import json
                            print(json.dumps(t, indent=2))
                        else:
                            print_telemetry(t)
                await asyncio.sleep(args.watch)
        except KeyboardInterrupt:
            print("\nStopped.")
    else:
        results = []
        for addr in addresses:
            t = await query_battery(addr, scan_timeout=args.timeout)
            if t:
                results.append(t)
                if args.json:
                    import json
                    print(json.dumps(t, indent=2))
                else:
                    print_telemetry(t)

        if not results:
            print("No batteries responded.", file=sys.stderr)
            sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
