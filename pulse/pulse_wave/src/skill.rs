/// Packaged agent skill markdown, embedded at compile time so the document
/// travels with the binary and can be installed verbatim into an agent skill
/// directory (`.agents/skills/pulse/SKILL.md`).
pub const SKILL_MARKDOWN: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../docs/skills/pulse.md"
));
