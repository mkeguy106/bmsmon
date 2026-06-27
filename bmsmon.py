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

# Safe read-only commands (see CLAUDE.md for full safety classification)
QUERY_STATUS = bytes([0x00, 0x00, 0x04, 0x01, 0x13, 0x55, 0xAA, 0x17])
QUERY_SERIAL = bytes([0x00, 0x00, 0x04, 0x01, 0x10, 0x55, 0xAA, 0x14])
QUERY_FW_VER = bytes([0x00, 0x00, 0x04, 0x01, 0x16, 0x55, 0xAA, 0x1A])

# Known battery name prefixes (Beken BLE module, same protocol)
KNOWN_PREFIXES = ("R-12", "R-24", "RO-12", "RO-24",  # Redodo
                  "L-12", "L-24", "L-51", "LT-",      # LiTime
                  "P-12", "P-24", "PQ-12", "PQ-24",   # PowerQueen
                  "S-", "SS-")                          # Starry Sea

# Friendly aliases for known batteries, keyed by MAC address (uppercase).
# Pass an alias anywhere a MAC is accepted (e.g. -a 2016-BATTERY-A).
BATTERY_ALIASES = {
    "C8:47:80:15:DB:13": "2016-BATTERY-A",  # R-12100BNNA70-A03902
    "C8:47:80:15:25:9A": "2016-BATTERY-B",  # R-12100BNNA70-A03727
}


def resolve_address(token: str) -> str:
    """Resolve a MAC address or a friendly alias (case-insensitive) to a MAC."""
    for mac, alias in BATTERY_ALIASES.items():
        if token.lower() == alias.lower():
            return mac
    return token


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

DEFAULT_WATCH_INTERVAL = 1.0  # seconds


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


async def find_device(address: str, scan_timeout: int = 15):
    """Find a BLE device by address with retries."""
    for attempt in range(3):
        device = await BleakScanner.find_device_by_address(address, timeout=scan_timeout)
        if device:
            return device
    return None


async def send_command(client: BleakClient, command: bytes, min_bytes: int = 10) -> bytes | None:
    """Send a command and collect the response fragments."""
    buf = bytearray()
    done = asyncio.Event()

    def on_notify(_sender, data):
        buf.extend(data)
        if len(buf) >= min_bytes:
            done.set()

    await client.start_notify(FFE1_UUID, on_notify)
    await client.write_gatt_char(FFE2_UUID, command, response=False)

    try:
        await asyncio.wait_for(done.wait(), timeout=5)
    except asyncio.TimeoutError:
        pass

    await client.stop_notify(FFE1_UUID)
    return bytes(buf) if buf else None


def parse_serial(data: bytes) -> str | None:
    """Parse serial number response (cmd 0x10). ASCII from offset 8 to end-2."""
    if len(data) < 11:
        return None
    raw = data[8:-2]
    return "".join(chr(b) for b in raw if 32 <= b <= 126) or None


def parse_firmware_version(data: bytes) -> dict | None:
    """Parse firmware version response (cmd 0x16).

    Payload after the 8-byte header (offsets relative to payload p):
      p[0:6]   structured version triplet (uint16 LE x3), e.g. 1.4.0
      p[6:10]  build date: uint16 LE year, then month and day bytes
      p[10:]   two NUL-terminated ASCII strings "MODEL-Vx.y", the first
               the hardware revision, the second the firmware revision,
               e.g. "T12100-V1.2" (HW) then "T12100-V1.4" (FW)
    """
    if len(data) < 18:
        return None
    # Payload starts after the 8-byte header
    p = data[8:]
    if len(p) < 10:
        return None

    version = (
        f"{struct.unpack_from('<H', p, 0)[0]}."
        f"{struct.unpack_from('<H', p, 2)[0]}."
        f"{struct.unpack_from('<H', p, 4)[0]}"
    )
    year = struct.unpack_from("<H", p, 6)[0]
    month = p[8]
    day = p[9]
    build_date = f"{year:04d}-{month:02d}-{day:02d}"

    # Extract the NUL-separated ASCII strings from the tail.
    strings = [s.decode("ascii", "ignore") for s in p[10:].split(b"\x00") if len(s) >= 3]
    model = strings[0].split("-", 1)[0] if strings else None

    def rev(s: str | None) -> str | None:
        return s.split("-", 1)[1] if s and "-" in s else s

    hw_version = rev(strings[0]) if len(strings) >= 1 else None
    fw_version = rev(strings[1]) if len(strings) >= 2 else None

    return {
        "version": version,
        "build_date": build_date,
        "model": model,
        "hw_version": hw_version,
        "fw_version": fw_version,
    }


async def query_static_info(client: BleakClient) -> dict:
    """Query serial number and firmware version (once per connection)."""
    info = {}

    raw = await send_command(client, QUERY_SERIAL, min_bytes=15)
    if raw:
        serial = parse_serial(raw)
        if serial:
            info["serial"] = serial

    raw = await send_command(client, QUERY_FW_VER, min_bytes=18)
    if raw:
        fw = parse_firmware_version(raw)
        if fw:
            info["firmware"] = fw

    return info


async def poll_once(client: BleakClient) -> bytes | None:
    """Send a status query and collect the response on an open connection."""
    return await send_command(client, QUERY_STATUS, min_bytes=80)


async def query_battery(address: str, scan_timeout: int = 15) -> dict | None:
    """Connect to a battery, read static info + one telemetry sample, and disconnect."""
    device = await find_device(address, scan_timeout)
    if not device:
        print(f"  Device {address} not found after scanning.", file=sys.stderr)
        return None

    async with BleakClient(device, timeout=30) as client:
        static = await query_static_info(client)
        raw = await poll_once(client)

    if not raw:
        return None
    t = parse_telemetry(raw, device.name)
    if t:
        t.update(static)
        t["address"] = device.address
    return t


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
    """Pretty-print a telemetry dict with optional static info header."""
    name = t.get("name", "Unknown")
    alias = BATTERY_ALIASES.get(str(t.get("address", "")).upper())
    print(f"\n{'─' * 50}")
    if alias:
        print(f"  {name}  [{alias}]")
    else:
        print(f"  {name}")
    if "serial" in t or "firmware" in t:
        parts = []
        if "serial" in t:
            parts.append(f"S/N: {t['serial']}")
        if "firmware" in t:
            fw = t["firmware"]
            if fw.get("model"):
                parts.append(f"Model: {fw['model']}")
            if fw.get("fw_version"):
                parts.append(f"FW: {fw['fw_version']}")
            if fw.get("hw_version"):
                parts.append(f"HW: {fw['hw_version']}")
            if fw.get("build_date"):
                parts.append(f"Built: {fw['build_date']}")
        print(f"  {' │ '.join(parts)}")
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


async def watch_batteries(addresses: list[str], interval: float, scan_timeout: int,
                          output_json: bool):
    """Persistent-connection polling loop. Reconnects automatically on drop."""
    import json as json_mod

    clients: dict[str, BleakClient] = {}

    async def watch_one(address: str):
        """Maintain a persistent connection to one battery and poll it."""
        while True:
            device = await find_device(address, scan_timeout)
            if not device:
                await asyncio.sleep(interval)
                continue

            client = BleakClient(device, timeout=30)
            try:
                await client.connect()
                clients[address] = client
                name = device.name

                # Query static info once per connection
                static = await query_static_info(client)
                static_info[address] = static

                while client.is_connected:
                    raw = await poll_once(client)
                    if raw:
                        t = parse_telemetry(raw, name)
                        if t:
                            t.update(static_info.get(address, {}))
                            t["address"] = device.address
                            results[address] = t
                            updated.set()
                    await asyncio.sleep(interval)
            except Exception as e:
                print(f"  {address}: connection lost ({e}), reconnecting...", file=sys.stderr)
            finally:
                clients.pop(address, None)
                try:
                    await client.disconnect()
                except Exception:
                    pass
            await asyncio.sleep(1)

    results: dict[str, dict] = {}
    static_info: dict[str, dict] = {}
    updated = asyncio.Event()

    # Start a persistent connection task per battery
    tasks = [asyncio.create_task(watch_one(addr)) for addr in addresses]

    # Display loop — waits for data, then refreshes on interval
    try:
        print("Connecting...")
        while True:
            await updated.wait()
            updated.clear()
            print("\033[2J\033[H", end="")  # clear screen
            for addr in addresses:
                t = results.get(addr)
                if t:
                    if output_json:
                        print(json_mod.dumps(t, indent=2))
                    else:
                        print_telemetry(t)
    except KeyboardInterrupt:
        print("\nDisconnecting...")
        for task in tasks:
            task.cancel()
        for client in clients.values():
            try:
                await client.disconnect()
            except Exception:
                pass
        print("Stopped.")


async def main():
    parser = argparse.ArgumentParser(description="BLE Battery Monitor for Redodo/LiTime/PowerQueen LiFePO4")
    parser.add_argument("--scan", action="store_true", help="Scan for compatible batteries")
    parser.add_argument("--address", "-a", type=str, help="MAC address of battery to query")
    parser.add_argument("--all", action="store_true", help="Query all batteries found in scan")
    parser.add_argument("--watch", "-w", type=float, metavar="SECS", default=None,
                        help=f"Continuously poll at interval (default: {DEFAULT_WATCH_INTERVAL}s)")
    parser.add_argument("--timeout", "-t", type=int, default=15, help="BLE scan timeout (default: 15s)")
    parser.add_argument("--json", action="store_true", help="Output as JSON")
    args = parser.parse_args()

    if args.scan:
        await scan_batteries(args.timeout)
        return

    if args.address:
        addresses = [resolve_address(args.address)]
    elif args.all:
        batteries = await scan_batteries(args.timeout)
        addresses = [b.address for b in batteries]
        if not addresses:
            return
    else:
        parser.print_help()
        return

    if args.watch is not None:
        interval = args.watch if args.watch > 0 else DEFAULT_WATCH_INTERVAL
        await watch_batteries(addresses, interval, args.timeout, args.json)
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
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
