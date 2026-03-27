# bmsmon

BLE battery monitor for Redodo, LiTime, PowerQueen, and Starry Sea LiFePO4 batteries.

Reads real-time telemetry over Bluetooth Low Energy using the reverse-engineered Beken BMS protocol: voltage, current, SOC, SOH, cell voltages, temperature, cycle count, and protection status.

## Supported Batteries

Any LiFePO4 battery using the Beken BK-BLE-1.0 BLE module with the `0xFFE0` service:

- **Redodo** (`R-12*`, `R-24*`, `RO-*`)
- **LiTime** (`L-12*`, `L-24*`, `L-51*`, `LT-*`)
- **PowerQueen** (`P-12*`, `P-24*`, `PQ-*`)
- **Starry Sea** (`S-*`, `SS-*`)

## Requirements

- Linux with BlueZ
- Python 3.10+
- [bleak](https://github.com/hbldh/bleak) (`python-bleak` on Arch/CachyOS)

```bash
# Arch/CachyOS
sudo pacman -S python-bleak

# pip
pip install bleak
```

## Usage

```bash
# Scan for batteries
python3 bmsmon.py --scan

# Query a specific battery
python3 bmsmon.py -a C8:47:80:15:25:01

# Query all batteries in range
python3 bmsmon.py --all

# Live monitoring (poll every 5 seconds)
python3 bmsmon.py -a C8:47:80:15:25:01 --watch 5

# JSON output
python3 bmsmon.py -a C8:47:80:15:25:01 --json
```

### Example Output

```
──────────────────────────────────────────────────
  R-12100BNNA70-A02402
──────────────────────────────────────────────────
  Total Voltage:      13.308 V
  Cell Sum Voltage:   13.164 V
    Cell 1:           3.328 V
    Cell 2:           3.328 V
    Cell 3:           3.326 V
    Cell 4:           3.326 V
    Spread:           2 mV
  Current:            0.000 A
  Cell Temp:          22 °C
  MOSFET Temp:        22 °C
  Remaining:          97.18 Ah
  Full Charge Cap:    103.24 Ah
  SOC:                94%
  SOH:                100%
  State:              Idle
  Cycles:             0
```

## Protocol

See [CLAUDE.md](CLAUDE.md) for full protocol documentation including command format, response parsing offsets, and protection flag definitions.

## License

MIT
