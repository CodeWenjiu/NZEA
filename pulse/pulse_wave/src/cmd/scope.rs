use serde::Serialize;
use wellen::ItemRef;

use crate::WaveError;

struct ScopeOut {
    flat: bool,
    tree: ScopeTree,
}

impl serde::Serialize for ScopeOut {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        self.tree.serialize(s)
    }
}

type ScopeTree = crate::tree::Tree<ScopeItem>;

#[derive(Serialize)]
struct ScopeItem {
    name: String,
    path: String,
    #[serde(skip)]
    truncated: bool,
}

impl std::fmt::Display for ScopeOut {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        if self.flat {
            for item in self.tree.preorder() {
                writeln!(f, "{}", item.path)?;
            }
        } else {
            write_node(f, &self.tree, 0)?;
        }
        Ok(())
    }
}

fn write_node(f: &mut std::fmt::Formatter<'_>, node: &ScopeTree, depth: u32) -> std::fmt::Result {
    let indent = "  ".repeat(depth as usize);
    let mark = if node.value.truncated { " +" } else { "" };
    writeln!(f, "{indent}{}{mark}", node.value.name)?;
    for child in &node.children {
        write_node(f, child, depth + 1)?;
    }
    Ok(())
}

impl crate::Pulse {
    pub(crate) fn scope(
        &self,
        max_depth: u32,
        filter: Option<&str>,
        flat: bool,
        root_path: Option<&str>,
    ) -> Result<(), WaveError> {
        let h = self.wav.hierarchy();

        let root_path = match root_path {
            Some(p) => p.to_string(),
            None => crate::top_scope(h)?,
        };

        let root = match crate::find_scope(h, &root_path) {
            Some(sr) => sr,
            None => {
                return Err(WaveError::Parse(format!("scope '{root_path}' not found")));
            }
        };

        let filter = filter.unwrap_or("");
        let root_name = root_path
            .rsplit('.')
            .next()
            .unwrap_or(&root_path)
            .to_string();
        let children = collect_children(h, h[root].items(h), &root_path, 1, max_depth, filter);

        let tree = crate::tree::Tree {
            value: ScopeItem {
                name: root_name,
                path: root_path.to_string(),
                truncated: false,
            },
            children,
        };

        self.emit(&ScopeOut { flat, tree });
        Ok(())
    }
}

fn collect_children(
    h: &wellen::Hierarchy,
    items: impl Iterator<Item = ItemRef>,
    parent_path: &str,
    depth: u32,
    max_depth: u32,
    filter: &str,
) -> Vec<ScopeTree> {
    let mut out = Vec::new();
    for r in items {
        if let ItemRef::Scope(sr) = r {
            let name = r.name(h).to_string();
            let path = format!("{parent_path}.{name}");

            let has_children = h[sr].items(h).any(|c| matches!(c, ItemRef::Scope(_)));
            let at_limit = depth >= max_depth;

            let children = if at_limit {
                Vec::new()
            } else {
                collect_children(h, h[sr].items(h), &path, depth + 1, max_depth, filter)
            };

            let truncated = has_children && at_limit;

            if name.contains(filter) || !children.is_empty() {
                out.push(crate::tree::Tree {
                    value: ScopeItem {
                        name,
                        path,
                        truncated,
                    },
                    children,
                });
            }
        }
    }
    out
}
