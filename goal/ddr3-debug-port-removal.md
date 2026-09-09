# Ddr3AdapterDebug Port Removal (cleanup plan)

Status: **planned** — deferred until board ILA debugging converges.

## Background

`Ddr3Adapter` exposes a wide observation port:

```scala
// nzea_device/src/ddr3/Ddr3Adapter.scala:104
val debug = Output(new Ddr3AdapterDebug)
```

`Ddr3AdapterDebug` carries ~20 fields (`reqPending`, `respValid`, `reqFire`,
`flush`, `uiState`, `uiReqGoSeen`, `uiDoneReq`, `uiAppEn`, `uiWen`, `reqWen`,
`wdfWren`, plus synchronizer-chain and captured-address probes). It was added
incrementally while chasing the DDR3 bring-up bugs (write-drain underflow,
row-shift address mapping, wdf mask inversion, ui-domain reset coverage).

The port is **not** part of the adapter's functional contract:

- Plain consumers (e.g. `Ddr3TestCore`) leave it unconnected.
- The only real reader is the board ILA instrumentation in
  `LxbArtix7Core` (probe4–probe11, probe14, probe16, probe17).
- Every field is a direct tap of an internal register/wire, so it costs
  nothing functionally but widens the module interface permanently.

## Why it cannot be removed yet

The current 22-probe ILA capture (`IlaProbes` + `ila_capture.tcl` +
`ila_probes.map`) is the primary tool for diagnosing residual DDR3
intermittency on the board (e.g. the delayed read-back staleness that
motivated `wrDrainCycles`). Eleven of the 22 probes come straight from
`bridge.io.debug.*`. Removing the port now would blind the ILA flow.

## Removal plan (when ILA debugging converges)

Trigger: DDR3 memtest passes long-run on the board with margin, and no open
bug requires adapter-internal visibility.

1. Delete `val debug = Output(new Ddr3AdapterDebug)` from `Ddr3Adapter.io`
   and the `Ddr3AdapterDebug` bundle class.
2. Delete the `debug` taps in the adapter body (they are pure `:=` reads of
   internals, no logic depends on them).
3. In `LxbArtix7Core`: remove probe4–11, probe14, probe16, probe17 wiring;
   renumber or shrink `IlaProbes` accordingly (probe count is a single
   source of truth in `IlaProbes.widths`).
4. Update `FpgaElaborate.IlaCapture.probeMap` and the widths comment in
   `IlaProbes.scala` to the new probe set.
5. Update `Ddr3AdapterTest` tops (`nzea_device/test/src/ddr3/`) which also
   instantiate `Ddr3AdapterDebug` in their observation IOs.
6. `Ddr3SubsystemDebug` (ui_clk_sync_rst / ui_rst / calib_done) is smaller
   and may stay if board-level MIG status observation is still wanted;
   decide at removal time.

## Notes

- The debug port intentionally lives in the device layer (not the tile) so
  the tile stays ILA-agnostic (abstraction-leak rule: board concerns must
  not propagate into `nzea_tile`).
- When removed, the ILA can still observe tile-boundary signals
  (`extRamBus(1)` req/resp) plus MIG-side pins via `ddr.io.mig.*`, which is
  enough for bus-protocol-level debugging.
