# wave_tracker

Parse and inspect FST/VCD waveform files for nzea RTL debugging. Uses [wellen](https://crates.io/crates/wellen) for **direct FST parsing** (no conversion to VCD).

## Build

```bash
cd wave_tracker
cargo build --release
```

## Usage

```bash
# List all signals (default: ../remu/target/trace.fst)
cargo run -- -l
cargo run --release -- -l

# Filter signals by name
cargo run -- -l -g "flush"
cargo run -- -l -g "commit"

# Get signal values at a specific time (timescale units)
cargo run -- -t 100 -g "freeList"

# Scan time range, print when matching signals change (optionally filter by value)
cargo run -- --scan 0 --scan-end 100000 -g "next_pc" --filter-value "8000611"

# Custom file path
cargo run -- -f /path/to/trace.fst -l
```

## Options

| Option | Description |
|--------|--------------|
| `-f, --file PATH` | Waveform file (default: ../remu/target/trace.fst) |
| `-l, --list-signals` | List all signal names |
| `-g, --grep STR` | Filter signals by substring |
| `-t, --time N` | Print values at time N |
| `--scan N` | Scan time range, print when matching signals change |
| `--scan-end N` | End time for scan (default: start+500) |
| `--filter-value STR` | With --scan: only print when any signal value contains STR (hex substring for binary values) |
| `--filter-rd-index BIN` | With --scan: only print when commit_bits_rd_index matches 5-bit binary (e.g. "00010" for sp) |
| `--bug-scan N` | Find cycles where PR is both in FreeList buf AND RMT(sp)=table_1 (overlap = bug) |
| `--bug-scan-end N` | With --bug-scan: end time (default: start+500) |
| `--bug-pr BIN` | With --bug-scan: PR value in 6-bit binary (default "000010" for PR2) |
| `--timeline N` | Timeline trace: dump commits and enqs for t=108 bug analysis |
| `--timeline-end N` | With --timeline: end time (default: start+150) |
| `-s, --start N` | Start time (reserved) |
| `-e, --end N` | End time (reserved) |

## Dependencies

- **wellen**: Direct FST/VCD parsing
- **clap**: CLI argument parsing

Add with `cargo add`:
```bash
cargo add clap --features derive
```
