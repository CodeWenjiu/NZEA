# stdlib/

DSL-level utilities that the zaozi frontend does not ship yet: `mux1H` / `muxCase` / `muxLookup` / `fill` / `log2Ceil`,
the `PipeIO` / `pipelineConnect` pipeline register, the `Opcode` opcode-table helper, and the DPI-C call layer
(`dpiUnclockedCall` / `dpiClockedCall`).

Everything here is written to be upstreamable: it uses only zaozi's public API, carries no nzea-specific policy, and is
covered by tests that do not depend on nzea code. Once a file lands in zaozi's own `stdlib`, delete it here and import
it from `me.jiuyang.stdlib` instead.

Code that is *not* upstreamable — the stat schema contract, the `LiteBus` protocol, the core itself — stays in the
parent package.
