# Nzea Project Overview

**Nzea** is a Scala/Chisel-based hardware project that generates RTL (e.g. SystemVerilog) for a configurable core. The codebase is organized into small, single-purpose modules and uses **Mill** for building and **just** for common tasks.

## What This Project Is

- **Language / stack:** Scala 2.13, [Chisel](https://www.chipsalliance.org/chisel/) (with CIRCT for emission)
- **Output:** SystemVerilog via ChiselStage (CIRCT/firtool)
- **Entry point:** The `nzea_cli` module provides a CLI that parses options into a config struct and invokes the elaboration pipeline in `nzea_core`.

## Quick Start

- **Build & run (recommended):** `just run` — builds and runs the CLI with default config. Append CLI flags as needed, e.g. `just run -w 64 -o out/verilog`.
- **Verify build:** After any change, run `just run`; a successful run (and generated output) indicates the project compiles and the pipeline runs.
- **Raw Mill:** `mill nzea_cli.run` or `mill nzea_cli.run -- -w 64 -o out/verilog` to pass arguments through to the program.

See [BUILD_AND_RUN.md](BUILD_AND_RUN.md) for build details and [MODULES.md](MODULES.md) for each module’s role.
