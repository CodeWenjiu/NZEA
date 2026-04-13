# Contributor Workflow

## Environment

Enter the pinned toolchain before building or verifying:

```bash
nix develop
```

The dev shell provides `mill`, `scalafmt`, `yosys`, `ieda`, JDK, and Rust nightly, and sets `PDK_PATH`.

## Daily Commands

Use `just` targets from repo root for normal flow:

```bash
just init
just dump <args>
just dump-tile <args>
just synth <args>
just sta <args>
```

`just dump` elaborates RTL into `build/<target>/<platform>/<isa>/<sim|sta>/`.
`just synth` and `just sta` run synthesis and STA scripts under `scripts/`.

For quick compile and test checks:

```bash
mill nzea_core.compile
mill nzea_tile.compile
mill nzea_core.test
```

For waveform tooling:

```bash
cd wave_tracker
cargo run --release -- --help
cargo test
```

## Verification Expectations

For logic changes, include at least one Scala test or elaboration command in your PR notes.
For synthesis or timing changes, include command line, report path, and key metrics from `build/.../synth/` or STA outputs.

## Deprecated Command

Do not use `just run`; it is no longer defined in the current `justfile`.
