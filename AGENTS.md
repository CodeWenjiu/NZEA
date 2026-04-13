# Repository Guidelines

## Project Structure & Module Organization
The active Scala/Chisel code is split by role: `nzea_core/src` contains the core pipeline, `nzea_tile/src` provides the tile-level wrapper, `nzea_rtl/src` holds shared RTL utilities, `nzea_config/src` defines elaboration and ISA configuration, and `nzea_cli/src` is the CLI entrypoint that dispatches to `CoreElaborate` or `TileElaborate`. Scala tests live in `nzea_core/test/src`. `wave_tracker/` is a separate Rust CLI for FST/VCD analysis. Flow scripts live in `scripts/`, generated RTL and reports land under `build/`, and `ssrc/` contains older reference sources and notes rather than the main edit path.

## Working Baseline
Use `nix develop` before build or verification work. The flake pins `mill`, `scalafmt`, `yosys`, `ieda`, JDK, and Rust nightly, and exports `PDK_PATH` for synthesis and STA flows.

## Build, Test, and Development Commands
Prefer the repo `justfile` over ad hoc commands. Common commands:

- `just init`: install BSP metadata for editors.
- `just dump <args>`: elaborate RTL into `build/<target>/<platform>/<isa>/<sim|sta>/`.
- `just dump-tile <args>`: elaborate the tile target.
- `just synth <args>`: generate synth-ready RTL, then run synthesis.
- `just sta <args>`: run synthesis plus STA; requires `PDK_PATH` from the Nix shell.
- `mill nzea_core.compile` or `mill nzea_tile.compile`: compile a single Scala module when you only need a fast sanity check.
- `mill nzea_core.test`: run ScalaTest suites in `nzea_core/test/src`.
- `cd wave_tracker && cargo run --release -- --help`: inspect waveform tool options.
- `cd wave_tracker && cargo test`: run Rust tests when adding or changing the tool.

## Coding Style & Naming Conventions
Follow existing file-local style instead of reformatting unrelated code. Scala uses `PascalCase` for classes, objects, and modules, `camelCase` for vals and methods, and test files ending in `*Test.scala`. Rust follows the standard split of `snake_case` for modules and functions and `CamelCase` for types. Keep comments and docstrings in English only. Prefer small modules and comments that explain intent or hazards, not line-by-line mechanics. Use `scalafmt` for Scala and `cargo fmt` for Rust when you touch those areas.

## Repository-Specific Rules
- Do not extract JARs or archives into the repository tree. Use read-only inspection such as `jar tf`, or extract into `/tmp`.
- Treat `docs/history/vibe/` as archived project knowledge, not as the source of truth for active commands.
- When changing synthesis or STA scripts, keep command examples aligned with the current `justfile`; do not reintroduce removed commands such as `just run`.

## Testing Guidelines
Add Scala regression tests in `nzea_core/test/src` for functional or elaboration changes. Keep test names descriptive, for example `VectorBackendTest.scala` or `DbusMemBridgeTest.scala`. For `wave_tracker`, add focused unit tests near the affected Rust module and run `cargo test`. Changes to synthesis or STA flows should include the exact command used and the generated report path in the PR.

## Commit & Pull Request Guidelines
Recent history uses short conventional subjects such as `feat: nnu`, `fix: DIV pre path`, and `chore: rtl split`. Prefer `type: concise summary` with imperative wording. PRs should state the affected area, list verification commands, link related issues, and attach report snippets or screenshots when the change affects generated RTL, timing, or debug tooling output.

## Design Notes
The stable architecture rule is separation of concerns: `nzea_config` owns shared configuration, `nzea_cli` only parses arguments and selects the elaboration target, and hardware generation stays in `nzea_core` or `nzea_tile`. If you need deeper background, use `docs/architecture/` and `docs/history/`, while keeping this file as the short contributor entrypoint.
