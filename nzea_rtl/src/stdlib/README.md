# stdlib/

DSL-level utilities that the zaozi frontend does not ship yet: `mux1H` / `muxCase` / `muxLookup` / `fill` / `log2Ceil`,
the `pipelineConnect` register between `Decoupled` stages, the `Opcode` opcode-table helper, the DPI-C call layer
(`dpiUnclockedCall` / `dpiClockedCall`), and the decode pair `Qmc` + `PlaPlan`.

`Qmc` replaces `me.jiuyang.decoder.espresso` with an in-process Quine–McCluskey minimizer (zaozi has a `TODO` for it and
otherwise shells out to the Berkeley binary), and `PlaPlan` turns a minimized PLA into logic without going through
zaozi's `PLADecoder`, whose `DecoderParameter` runs `espresso` unconditionally. Both consume and produce zaozi's own
`TruthTable` / `PLA` types, so they are drop-in pieces of the same pipeline:

| | espresso path | this tree |
|---|---|---|
| minimize | `espresso(tables)`, external binary | `Qmc(tables)`, pure Scala |
| emit | `PLADecoder` submodule | `PlaPlan.decode` inlined |

Everything here is written to be upstreamable: it uses only zaozi's public API, carries no nzea-specific policy, and is
covered by tests that do not depend on nzea code (`QmcTest` compiles nothing but the minimizer). Once a file lands in
zaozi's own `stdlib`, delete it here and import it from `me.jiuyang.stdlib` instead.

Code that is *not* upstreamable — the stat schema contract, the `LiteBus` protocol, the core itself — stays in the
parent package.
