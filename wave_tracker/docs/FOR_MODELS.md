# Wave Tracker — Guide for AI Models

Rust-based FST/VCD waveform parser for nzea RTL debugging. Uses **wellen** for direct FST parsing (no fst2vcd conversion).

## Waveform Location

```
{chip-dev}/remu/target/trace.fst
```

## Build & Run

```bash
cd nzea/wave_tracker
cargo build --release
./target/release/wave_tracker [options]
# Or: cargo run --release -- [options]
```

## CLI Options

| Option | Description |
|--------|--------------|
| `-f, --file PATH` | Waveform file (default: ../remu/target/trace.fst) |
| `-l, --list-signals` | List all signal names |
| `-g, --grep STR` | Filter signals by substring |
| `-t, --time N` | Print values at time N (timescale units) |
| `--scan N` | Scan time range, print when matching signals change |
| `--scan-end N` | End time for scan (default: start+500) |
| `--filter-value STR` | With --scan: only print when any signal value contains STR (hex substring for binary values) |
| `-s, -e` | Start/end time (reserved) |

## Important Signals for RMT/FreeList Debugging

- `freeList.io_commit_valid`, `freeList.io_commit_bits`
- `freeList.io_flush`
- `rmt.io_commit_*`, `rmt.io_flush`
- `rob.io_commit_*`, `rob.do_flush`
- `slots_flush_*`, `slots_might_flush_*`

Use `-g "freeList"` or `-g "flush"` to filter.

## Dependencies

- **wellen** (0.20.2): FST/VCD parsing
- **clap** (4.x): CLI
