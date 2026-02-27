# Module Roles

All modules are Scala modules defined in the root `build.mill`.

## nzea_config

- **Purpose:** Define the single, shared configuration type used across the project.
- **Contents:** `NzeaConfig` case class with fields such as `width`, `debug`, `outDir`, annotated with mainargs `@arg` for CLI parsing.
- **Dependencies:** Only `mainargs` (for the annotations). No Chisel, no nzea_core.
- **Used by:** `nzea_core` (receives config), `nzea_cli` (parses args into this type).

## nzea_core

- **Purpose:** Chisel-based RTL design and elaboration (emit SystemVerilog).
- **Contents:** Hardware modules (e.g. GCD placeholder), `Elaborate` object with `elaborate(implicit config: NzeaConfig)` that drives ChiselStage and writes output to `config.outDir`.
- **Dependencies:** `nzea_config`, Chisel (and CIRCT via Chisel). No mainargs.
- **Tests:** `mill nzea_core.test` runs ScalaTest tests under `nzea_core/test/`.

## nzea_cli

- **Purpose:** Command-line entry point: parse argv into `NzeaConfig` and invoke the core pipeline.
- **Contents:** `Main` with `main(args)`: uses mainargs to build `NzeaConfig`, then calls `Elaborate.elaborate` (relying on implicit config).
- **Dependencies:** `nzea_core` (and transitively `nzea_config`), `mainargs`.
- **Run:** `mill nzea_cli.run` or `just run` (with optional args after `run`).

## Dependency Graph

```
nzea_config  (standalone; mainargs only)
     ↑
nzea_core    (nzea_config + Chisel)
     ↑
nzea_cli     (nzea_core + mainargs)
```
