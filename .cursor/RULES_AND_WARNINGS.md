# Rules and Warnings (Agent must read)

This document records **rules to follow** and **past mistakes** when working in this repo, so they are not repeated.

---

## Do not extract JARs inside the workspace

**Wrong:** Running any command that extracts a JAR into the current directory while that directory is the project root or any path under the repo, e.g.:

```bash
jar xf /path/to/some.jar ...
# or
unzip some.jar -d .
```

**Why it’s bad:** The full JAR layout (e.g. `chisel3/`, `META-INF/`, `org/`) is written into the repo, creating many unrelated files that are hard to remove and pollute version control.

**Right way:**

- To inspect JAR contents, use **read-only** tools only, e.g. `jar tf path/to.jar`, or extract into a **temporary directory** (e.g. `/tmp/jar-extract-$$`) and delete it afterward. **Never** run `jar xf` or `unzip` with the repo root (or any repo path) as the current working directory.
- **Never** run `jar xf` / `unzip` (or similar) in this repo’s directories so that files are written into the workspace.

**What happened:** While looking up the ChiselEnum API, the Chisel JAR was accidentally extracted in the project root with `jar xf`, which created a `chisel3/` tree there. Do not extract JARs in the workspace.

---

## DecodeTable: Espresso vs QMC (not a bug)

When using `chisel3.util.experimental.decode.DecodeTable` and `decodeTable.decode(inst)`, Chisel **by default** tries the **Espresso** logic minimizer first (for smaller decode logic). If the `espresso` binary is not installed or fails (e.g. “Cannot run program \"espresso\" … error=20, Not a directory”), Chisel **falls back to QMC** and the design still works. You did not “switch” to Espresso; the library tries it and then uses QMC when it fails.

- The “espresso failed to run” message is **informational**. The generated RTL is produced by QMC in that case.
- To avoid the message you would need to either install Espresso, or stop using `decodeTable.decode()` and instead build the `TruthTable` yourself and call `decoder(QMCMinimizer, inst, table)` (and then cast the result to your bundle type). The current code uses `DecodeTable` for maintainability and accepts the log line.

---

## W001: Casting non-literal UInt to ChiselEnum

If you see a warning like “Casting non-literal UInt to … ImmType. You can use … .safe to cast”, it comes from Chisel’s `DecoderBundle`: when a decode field’s `chiselType` is a ChiselEnum (e.g. `ImmType()`), the library does an internal `asTypeOf` from the decoder’s UInt to that enum and triggers the warning.

**Fix:** For that field, use `chiselType = UInt(YourEnum.getWidth.W)` instead of `chiselType = YourEnum()`, and when reading the decoded bundle do `YourEnum.safe(decodedBundle(YourField))` (and use the first element of the tuple). The decode table then never casts to the enum type and the warning goes away.

---

*Add new rules or recurring mistakes to this document as they come up.*
