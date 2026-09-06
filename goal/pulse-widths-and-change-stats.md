# Pulse: Signal Widths and Change Statistics (feature plan)

Status: **planned** — recorded after the ILA `vcd-rename` debugging session
(2026-09-06), where pulse lacked the primitives to classify exported ILA VCD
vars, forcing a fallback to a throwaway Python parser.

## Background

While fixing the ILA capture → `probe vcd-rename` mapping, two questions had to
be answered before a rename map could be written:

1. **Metadata**: what is the exact `$var` ref (including width like `[16:0]`)
   of every exported signal? `pulse signal` returns a plain name list without
   widths, which is not enough to match Vivado's folded net names precisely.
2. **Correlation**: are two 1-bit vars the same net (identical change sets)?
   Is one signal's change set a subset of another's? How many times does a
   signal toggle in a window? The resolution (probe4 folded to a constant,
   `addr_1`/`addr` being high address bits locking step with `addr != 0`)
   came from whole-trace change-set diffing — pulse has no `change` command
   and `property` only reports event times, so this analysis fell back to a
   Python one-off.

## Goals

### 1. Width-aware output

- `signal` includes each signal's bit width in `--json` (and optionally in
  text output), e.g. `{"name":"ram_req_addr","width":17,"vector":true}`.
- `value` samples render vectors already; keep that, but make the width
  visible on `signal` so the metadata question is answerable with pulse alone.

### 2. Change statistics

Add a way to count / locate changes of a signal over a window, e.g.:

- `property --eval "change ram_req_addr"` (or a dedicated `stat`/`changes`
  command) returns the change-count plus the change timestamps.
- Accepts `--cycles FROM-TO`, `--max`, `--json` like other commands.

## Target use case (acceptance)

The next time an ILA export needs a rename map, the whole classification must
be doable with pulse only:

1. `pulse signal --scope dut --json` → widths + refs to build the map keys.
2. `pulse property --eval "change a" / "change b"` per candidate signal →
   counts; equality of change sets (identical net) checked by comparing
   timestamps or by a `a == b` steady-state query (`!(a ^ b)` true for all
   samples = same net).
3. Constant-folding detection: a signal with 1 change (initial value only) is
   a folded constant → classify as junk/`probeN` fallback.

## Non-goals

- No waveform-diff or full-set algebra UI; the two primitives above (widths +
  change stats) are the minimal set that removes the Python fallback.