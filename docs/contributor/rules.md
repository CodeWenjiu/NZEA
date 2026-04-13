# Contributor Rules

## Language and Comments

Use English for code comments, docstrings, and repository documentation.
When editing existing non-English comments, convert them to English in the same change when practical.

## Workspace Safety

Do not extract JARs or archives into the repository tree.
Use read-only inspection (`jar tf`) or extract into a temporary path such as `/tmp`.

## Decode and Chisel Notes

`DecodeTable.decode(inst)` may log Espresso failures and then fall back to QMC; this is expected unless you install Espresso.

If decode warnings appear for casting non-literal `UInt` to `ChiselEnum`, define the decode field as `UInt(enum.getWidth.W)` and convert with `EnumType.safe(...)` at use sites.

## Documentation Priority

Use `AGENTS.md` and `docs/contributor/*` for current workflow and rules.
Treat files in `docs/history/vibe/` as historical references only.
