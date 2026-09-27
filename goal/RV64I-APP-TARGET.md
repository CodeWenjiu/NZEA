# nzea RV64I: the app target remu is missing (handoff)

Status: **the nzea side is ready** — a `riscv64i` core dump exists and the nzea backend already lists
RV64I. The only thing blocking the RV64I acceptance run is that remu cannot build an app image for an
M-less RV64. Companion document: `goal/RV64-ABI.md` (the DPI/width boundary, implemented on both
sides).

---

## 1. Repro

```
cd ~/project/chip-dev/remu
just build-app hello_world riscv64i
```

```
error: failed to run `rustc` to learn about target-specific information
  ...  --target riscv64i-unknown-none-elf ...
  error: error loading target specification: could not find specification for target "riscv64i-unknown-none-elf"
  = help: run `rustc --print target-list` for a list of built-in targets
  = help: did you mean `riscv64im-unknown-none-elf`?
```

The same error blocks the end-to-end run:

```
just run-app hello_world riscv64i --platform remu -- --platform nzea --sim-opt target=core \
  --sim-opt watchdog=3x30 --batch --startup '{' continue '}' --difftest remu
```

## 2. Why

A target shorthand is expanded to `format!("{base_prefix}-unknown-none-elf")` in
`remu_app/remu_hal/xtask/src/target.rs` (`CargoTarget::try_from_parsed`) and the result is used as a
**built-in** rustc target (`needs_json_target_spec: false`). On the pinned toolchain
(`rustc 1.100.0-nightly`) the RISC-V bare-metal RV64 targets are:

```
riscv64gc-unknown-none-elf   riscv64im-unknown-none-elf   riscv64imac-unknown-none-elf
```

Every one of them carries M. There is no built-in `riscv64i-unknown-none-elf`, and no built-in
M-free RV64 target at all — an M-free RV64 image has to be produced from an explicit target spec.

## 3. What remu needs

`riscv64i` must yield an ELF a **RV64I-only** core can run: no M instruction anywhere in the image,
`core`/`compiler_builtins` included. Two ways to get there — the second is the one I would pick.

**A. Custom target spec** (precedent: `remu_app/rcore/rcore64.json`). Add e.g.
`remu_app/riscv_only/riscv64i-unknown-none-elf.json` — `rcore64.json` with `"features": ""` instead
of `"+m,+forced-atomics"` — and resolve the `riscv64i` shorthand to it
(`needs_json_target_spec: true`). The `.json` path already builds std from source
(`-Z build-std=core,alloc -Z json-target-spec`, see `print.rs`), which is what makes the image M-free.

**B. Built-in triple + rebuilt `core`.** Keep `riscv64im-unknown-none-elf` and add
`-Z build-std=core,alloc` together with `-C target-feature=-m` (the shorthand table already has the
rustflags-env mechanism this fits: `CARGO_TARGET_RUSTFLAGS_RV32I/IM_ENV`). Same effect as a JSON
spec, no extra file, and `core` is still rebuilt from source.

**What does not work on its own**: `-C target-feature=-m` *without* `-Z build-std`. The precompiled
`core`/`compiler_builtins` for `riscv64im-unknown-none-elf` were built with M enabled (LLVM uses
`mul` for constant division and address scaling), so an M-free app can still link M instructions out
of the prebuilt rlib.

Also needed along the way:

- the ISA string that reaches remu_cli (and through it the nzea backend) must stay `riscv64i`, so
  the `riscv64i` model is dumped/loaded — `NzeaIsaKind::Rv64I` is already accepted
  (`remu_simulator/simulators/nzea/src/supported_isa.rs`);
- nothing else: `app_caps.rs` / `validate_app_target` already fail open for `.json` targets.

## 4. What nzea provides today (no change on our side)

- `just dump --target core --isa riscv64i --flow verilator` elaborates cleanly (17-bit shared op
  window, 81-term PLA before minimization), and `IsaConfig.parse` accepts `riscv64i`;
- the DUT's decode maps an unknown instruction to a no-op, so an M instruction reaching this core is
  a **difftest mismatch, not a trap** — the acceptance run below doubles as the M-free check;
- verified against the current RTL: `microbench riscv64im --app-args test` → 10/10 PASS, `ipc 0.2346`;
  `hello_world riscv64im` → `Hello World` / `Answer: 42`, exit 0.

## 5. Acceptance

```
just build-app hello_world riscv64i
just run-app hello_world riscv64i --platform remu -- --platform nzea --sim-opt target=core \
  --sim-opt watchdog=3x30 --batch --startup '{' continue '}' --difftest remu
```

Expected: `Hello World` / `Answer: 42`, difftest clean, exit 0.

Optional — the same 10-kernel difftest the RV64IM run passes (a soft-mul/div build has a different
instruction mix, so the per-kernel ms are not comparable; the difftest is the acceptance):

```
just run-app microbench riscv64i --platform remu --app-args test -- --platform nzea \
  --sim-opt target=core --sim-opt watchdog=3x30 --batch \
  --startup '{' continue '}' and '{' stat ipc '}' --difftest remu
```

Expected: 10/10 PASS, `ipc` in the same ballpark as the rv64im run (0.2346).

## 6. Out of scope

RV64IM and RV32* already run. D/F/V, other ISA variants and anything above the core (tile) are
separate work items.
