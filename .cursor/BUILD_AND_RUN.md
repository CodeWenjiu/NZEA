# Build and Run

## Build System

- **Mill** is the primary build tool. All Scala modules are defined in the root `build.mill`.
- **just** wraps common operations (run, clean, init) so you don’t have to remember Mill invocations.

## Prerequisites

- **Mill** (e.g. via Nix: `nix develop` then `mill` is available)
- **JDK** (e.g. 17 or 21) for Scala/Mill
- **just** for the `justfile` targets

If using Nix, from the repo root run `nix develop` (or `direnv allow` when using direnv) so `mill` and `just` are on `PATH`.

## Building with Mill

- Compile everything:  
  `mill compile`
- Compile a single module:  
  `mill nzea_core.compile` or `mill nzea_config.compile` or `mill nzea_cli.compile`
- Run tests:  
  `mill nzea_core.test`

## Running with just

- **Run the CLI (default config):**  
  `just run`
- **Run with arguments:**  
  `just run -w 64 -d -o out/verilog`  
  Arguments after `run` are passed through to `mill nzea_cli.run -- <ARGS>`.

## Verifying a Successful Build

1. Run:  
   `just run`
2. If the build succeeds, you’ll see Mill compile output and then something like:  
   `Generating NzeaCore (width: 32, Debug: false)`  
   and the run will complete without errors.
3. Generated RTL (e.g. SystemVerilog) will appear under the output directory (default `build` unless overridden with `-o`).

So: **a successful `just run` means the project compiles and the elaboration path works.**

## Other just Targets

- `just init` — install Mill BSP config (for editor/IDE integration).
- `just clean` — remove local `build` directory.
- `just clean-all` — run `mill mill clean` then clean (removes Mill’s `out/` and local `build`).
