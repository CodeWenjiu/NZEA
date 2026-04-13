# Architecture Overview

## Module Boundaries

- `nzea_config`: shared configuration model. `NzeaConfig` holds global elaboration options (`target`, `sim`, output path, platform), while `CoreConfig` holds core-only micro-architecture and ISA options.
- `nzea_core`: core RTL implementation and elaboration path for core target.
- `nzea_tile`: tile-level wrapper and elaboration path for tile target.
- `nzea_rtl`: shared utility RTL components.
- `nzea_cli`: CLI entrypoint that parses args and dispatches to `CoreElaborate` or `TileElaborate`.
- `wave_tracker`: standalone Rust waveform analysis CLI.

## Dependency Shape

Primary Scala dependency direction:

`nzea_config -> nzea_rtl -> nzea_core -> nzea_tile`

`nzea_cli` depends on `nzea_core` and `nzea_tile` and is only responsible for argument parsing and target routing.

## Design Principles

1. Keep configuration centralized in `nzea_config` to avoid duplicated CLI parsing logic.
2. Keep scope explicit: pass `config.core` into core/tile hardware modules, keep non-core flow options in top-level `NzeaConfig`.
3. Keep CLI concerns separate from hardware generation so elaboration remains reusable by tools and tests.
4. Prefer small focused modules with clear ownership over large multi-purpose files.
