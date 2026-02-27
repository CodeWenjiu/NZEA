# Design Philosophy

Documentation and design notes are kept in English. Comments in code follow the principle: **fewer, higher-quality comments** — only add them where they clarify non-obvious behavior or intent.

## Principles

1. **Shared config, single definition**  
   `NzeaConfig` lives in its own module (`nzea_config`) and is the only place that knows about CLI/mainargs. Other modules depend on this module and take `NzeaConfig` (or an implicit one) so config is passed as a single value, not scattered arguments.

2. **Implicit config where it simplifies call sites**  
   `Elaborate.elaborate` takes an `implicit config: NzeaConfig`. The CLI parses once, marks the value `implicit`, and calls `Elaborate.elaborate` with no arguments. Config “threads through” without being passed explicitly at every layer.

3. **CLI and core are separate**  
   `nzea_cli` handles argument parsing and entry point only. `nzea_core` contains the actual Chisel design and elaboration logic and has no dependency on mainargs. This keeps the core reusable from other entry points (tests, scripts, other tools).

4. **Version constants in one place**  
   Dependency versions (e.g. `mainargsVersion`, `chiselVersion`) are defined once in `build.mill` and reused so upgrades don’t require edits in multiple modules.

5. **Comments: quality over quantity**  
   Prefer clear naming and small functions over long comments. Add comments only when they explain *why* or non-obvious constraints, not to restate what the code already says.
