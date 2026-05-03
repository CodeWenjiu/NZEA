# Repository Guidelines

## Project Structure & Module Organization
The active Scala/Chisel code is split by role:

| Module | Purpose |
|--------|---------|
| `nzea_rtl/src` | Shared RTL utilities (FabricBus, LiteBus, crossbar, arbiter, Pipe, MuxTree) |
| `nzea_core/src` | Core pipeline: frontend (IFU/IDU/ISU/RAT/PRF/CSR/BP), backend (integer/V/NNU/LSU), retire (ROB/Commit/WBU) |
| `nzea_config/src` | Shared configuration model: `NzeaConfig` (global elaboration options) and `CoreConfig` (micro-arch/ISA) |
| `nzea_tile/src` | Tile-level SoC wrapper (NzeaTile + FabricBus crossbar + platform devices) |
| `nzea_cli/src` | CLI entrypoint; parses args and dispatches to `CoreElaborate` or `TileElaborate` |
| `wave_tracker/` | Standalone Rust CLI for FST/VCD waveform analysis and RTL-level debugging |

### Dependency Direction
```
nzea_config -> nzea_rtl -> nzea_core -> nzea_tile
```
`nzea_cli` depends on `nzea_core` and `nzea_tile` for argument parsing and target routing only.
`nzea_config` depends on `nzea_core` solely for `CoreConfig`.

### Design Principles
1. Keep configuration centralized in `nzea_config` to avoid duplicated CLI parsing logic.
2. Keep scope explicit: pass `config.core` into core/tile hardware modules; keep non-core flow options in top-level `NzeaConfig`.
3. Keep CLI concerns separate from hardware generation so elaboration remains reusable by tests and tools.
4. Prefer small focused modules with clear ownership over large multi-purpose files.
5. Version constants (`scalaV`, `chiselV`, etc.) are defined once in `build.mill` and reused across modules.

## Working Baseline
Use `nix develop` before build or verification work. The flake pins `mill`, `scalafmt`, `yosys`, `ieda`, JDK, and Rust nightly, and exports `PDK_PATH` for synthesis and STA flows.

## Build, Test, and Development Commands
Prefer the repo `justfile` over ad hoc commands. Common commands:

- `just init`: install BSP metadata for editors.
- `just dump <args>`: elaborate RTL into `build/<target>/<platform>/<isa>/<sim|sta>/`.
- `just dump-tile <args>`: convenience alias for `just dump --target tile <args>`.
- `just synth <args>`: generate synth-ready RTL, then run synthesis.
- `just sta <args>`: run synthesis plus STA; requires `PDK_PATH` from the Nix shell.
- `just clean-all`: clean `build/` and Mill cache.

### Quick Compile Checks
- `mill nzea_core.compile` or `mill nzea_tile.compile`: compile a single Scala module.

### Running Tests
- `just test <module>`: run all tests in a module (default `nzea_rtl`; also supports `nzea_core`).
- `just test-suites <module> <suites...>`: run specific ScalaTest suites, e.g. `just test-suites nzea_rtl FabricBusCrossbarTest FabricBusAdapterTest`.
- `just test-match <module> <pattern>`: run suites matching a regex on `*Test.scala` filenames, e.g. `just test-match nzea_core "Vector.*Test"`.
- `just tb <pattern>`: convenience alias for `just test-match nzea_rtl <pattern>`.
- `mill nzea_core.test` or `mill nzea_rtl.test`: run all tests via Mill directly.

### Wave Tracker
- `cd wave_tracker && cargo run --release -- --help`: inspect waveform tool options.
- `cd wave_tracker && cargo test`: run Rust tests.
- `cd wave_tracker && cargo clippy`: run Rust lints.
- `cd wave_tracker && cargo fmt --check`: check Rust formatting.

### 4-State Simulation (iverilog)
- `just iv target=<target> platform=<platform> isa=<isa>`: generate RTL, compile, and run 4-state simulation.
  - Example: `just iv target=tile platform=hellofpga isa=riscv32i`
  - Output: `build/<target>/<platform>/<isa>/hw/iverilog/tb.{vvp,fst}`
- `just iv-build target=<target> platform=<platform> isa=<isa>`: compile only.
- `just iv-run target=<target> platform=<platform> isa=<isa>`: run compiled simulation.

Testbench sources live in `iverilog_tb/` (bus models, test programs). The `--sim false` RTL is used since it exposes bus IO without DPI bridges, and the behavioral bus models in the testbench replace DPI with pure Verilog memory models loaded via `$readmemh`.

## Coding Style & Naming Conventions
Follow existing file-local style instead of reformatting unrelated code. Scala uses `PascalCase` for classes, objects, and modules, `camelCase` for vals and methods, and test files ending in `*Test.scala`. Rust follows the standard split of `snake_case` for modules and functions and `CamelCase` for types. Keep comments and docstrings in English only. Prefer small modules and comments that explain intent or hazards, not line-by-line mechanics.

### Formatting
- Scala: use `scalafmt` (configured via `.scalafmt.conf` at repo root).
- Rust: use `cargo fmt` and `cargo clippy`.

## Repository-Specific Rules
- Do not extract JARs or archives into the repository tree. Use read-only inspection such as `jar tf`, or extract into `/tmp`.
- Keep comments and docstrings in English only.
- Do not reintroduce removed commands such as `just run`.
- When changing synthesis or STA scripts, keep command examples aligned with the current `justfile`.

### Decode & Chisel Notes
- `DecodeTable.decode(inst)` may log Espresso failures and then fall back to QMC; this is expected unless you install Espresso. The generated RTL is valid.
- If decode warnings appear for casting non-literal `UInt` to `ChiselEnum`, define the decode field as `UInt(enum.getWidth.W)` and convert with `EnumType.safe(...)` at use sites.

## Testing Guidelines
- Scala regression tests live in `nzea_core/test/src` and `nzea_rtl/test/src`.
- Keep test names descriptive, for example `VectorBackendTest.scala` or `DbusMemBridgeTest.scala`.
- For `wave_tracker`, add focused unit tests near the affected Rust module and run `cargo test`.
- Changes to synthesis or STA flows should include the exact command used and the generated report path.

## Commit & Pull Request Guidelines
Recent history uses short conventional subjects such as `feat: nnu`, `fix: DIV pre path`, and `chore: rtl split`. Prefer `type: concise summary` with imperative wording. PRs should state the affected area, list verification commands, link related issues, and attach report snippets or screenshots when the change affects generated RTL, timing, or debug tooling output.
