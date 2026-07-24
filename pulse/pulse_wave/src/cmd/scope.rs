use wellen::ItemRef;

use crate::WaveError;

impl crate::Pulse {
    /// List scopes (modules) in the hierarchy.
    pub(crate) fn scope(&self, filter: Option<&str>) -> Result<(), WaveError> {
        let h = self.wav.hierarchy();

        if self.json {
            let scopes: Vec<serde_json::Value> = match filter {
                Some(f) => collect_filtered_json(h, h.items(), String::new(), f),
                None => collect_tree_json(h, h.items(), 0),
            };
            let output = serde_json::json!({ "scopes": scopes });
            println!("{}", serde_json::to_string(&output).unwrap_or_default());
        } else {
            match filter {
                Some(f) => walk_filtered(h, h.items(), String::new(), f),
                None => walk_tree(h, h.items(), 0),
            }
        }

        Ok(())
    }
}

fn walk_tree(h: &wellen::Hierarchy, items: impl Iterator<Item = ItemRef>, depth: u32) {
    for r in items {
        if let ItemRef::Scope(sr) = r {
            let indent = "  ".repeat(depth as usize);
            println!("{indent}{}", r.name(h));
            let children = h[sr].items(h);
            walk_tree(h, children, depth + 1);
        }
    }
}

fn walk_filtered(
    h: &wellen::Hierarchy,
    items: impl Iterator<Item = ItemRef>,
    path: String,
    filter: &str,
) {
    for r in items {
        let name = r.name(h).to_string();
        let full_path = if path.is_empty() {
            name.clone()
        } else {
            format!("{path}.{name}")
        };
        if let ItemRef::Scope(sr) = r {
            if name.contains(filter) {
                println!("{full_path}");
            }
            let children = h[sr].items(h);
            walk_filtered(h, children, full_path, filter);
        }
    }
}

fn collect_tree_json(
    h: &wellen::Hierarchy,
    items: impl Iterator<Item = ItemRef>,
    depth: u32,
) -> Vec<serde_json::Value> {
    let mut out = Vec::new();
    for r in items {
        if let ItemRef::Scope(sr) = r {
            out.push(serde_json::json!({
                "name": r.name(h),
                "depth": depth,
            }));
            let children = h[sr].items(h);
            out.extend(collect_tree_json(h, children, depth + 1));
        }
    }
    out
}

fn collect_filtered_json(
    h: &wellen::Hierarchy,
    items: impl Iterator<Item = ItemRef>,
    path: String,
    filter: &str,
) -> Vec<serde_json::Value> {
    let mut out = Vec::new();
    for r in items {
        let name = r.name(h).to_string();
        let full_path = if path.is_empty() {
            name.clone()
        } else {
            format!("{path}.{name}")
        };
        if let ItemRef::Scope(sr) = r {
            if name.contains(filter) {
                out.push(serde_json::json!({ "path": full_path }));
            }
            let children = h[sr].items(h);
            out.extend(collect_filtered_json(h, children, full_path, filter));
        }
    }
    out
}
