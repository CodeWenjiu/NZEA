/// Packaged agent skill markdown, embedded at compile time.
///
/// Printed by `pulse skill` so the document travels with the binary and can be
/// installed verbatim into an agent skill directory (e.g.
/// `.agents/skills/pulse/SKILL.md`).
pub const SKILL_MARKDOWN: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../docs/skills/pulse.md"
));
