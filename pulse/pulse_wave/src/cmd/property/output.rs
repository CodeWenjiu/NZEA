use serde::Serialize;
use tabled::builder::Builder;

use crate::SerdeRange;

/// Output of a `property` query, shaped by `--count` and the number of
/// `--eval` columns. `untagged` forwards serialization to the inner value,
/// so the JSON shape matches the single-variant outputs exactly.
#[derive(Serialize)]
#[serde(untagged)]
pub(super) enum PropertyOutput {
    Ranges(PropertyOut),
    Count(PropertyCountOut),
    Columns(PropertyColumnsOut),
    Counts(PropertyCountsOut),
}

impl std::fmt::Display for PropertyOutput {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PropertyOutput::Ranges(o) => write!(f, "{o}"),
            PropertyOutput::Count(o) => write!(f, "{o}"),
            PropertyOutput::Columns(o) => write!(f, "{o}"),
            PropertyOutput::Counts(o) => write!(f, "{o}"),
        }
    }
}

/// Build the output shape for a set of evaluated columns.
pub(super) fn render(
    columns: &[(String, Vec<SerdeRange<u64>>)],
    count: bool,
    cycles: SerdeRange<usize>,
    total_cycles: usize,
    max: Option<usize>,
) -> PropertyOutput {
    match (count, columns.len()) {
        (true, 1) => PropertyOutput::Count(PropertyCountOut {
            cycles,
            total_cycles,
            count: columns[0].1.len(),
        }),
        (true, _) => PropertyOutput::Counts(PropertyCountsOut {
            cycles,
            total_cycles,
            columns: columns
                .iter()
                .map(|(name, m)| CountOut {
                    name: name.clone(),
                    count: m.len(),
                })
                .collect(),
        }),
        (false, 1) => PropertyOutput::Ranges(PropertyOut {
            cycles,
            total_cycles,
            max,
            matches: columns[0].1.clone(),
        }),
        (false, _) => PropertyOutput::Columns(PropertyColumnsOut {
            cycles,
            total_cycles,
            max,
            columns: columns
                .iter()
                .map(|(name, m)| ColumnOut {
                    name: name.clone(),
                    matches: m.clone(),
                })
                .collect(),
        }),
    }
}

/// Single-expression output (unchanged legacy format).
#[derive(Serialize)]
pub(super) struct PropertyOut {
    #[serde(flatten)]
    pub(super) cycles: SerdeRange<usize>,
    pub(super) total_cycles: usize,
    /// Requested match limit; present when `--max` truncated the output.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(super) max: Option<usize>,
    pub(super) matches: Vec<SerdeRange<u64>>,
}

impl std::fmt::Display for PropertyOut {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        for ts in &self.matches {
            writeln!(f, "{ts}")?;
        }
        Ok(())
    }
}

/// Single-expression `--count` output.
#[derive(Serialize)]
pub(super) struct PropertyCountOut {
    #[serde(flatten)]
    pub(super) cycles: SerdeRange<usize>,
    pub(super) total_cycles: usize,
    pub(super) count: usize,
}

impl std::fmt::Display for PropertyCountOut {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.count)
    }
}

/// Multi-expression output: one column per `--eval`.
#[derive(Serialize)]
pub(super) struct PropertyColumnsOut {
    #[serde(flatten)]
    pub(super) cycles: SerdeRange<usize>,
    pub(super) total_cycles: usize,
    /// Requested match limit; present when `--max` truncated the output.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(super) max: Option<usize>,
    pub(super) columns: Vec<ColumnOut>,
}

#[derive(Serialize)]
pub(super) struct ColumnOut {
    pub(super) name: String,
    pub(super) matches: Vec<SerdeRange<u64>>,
}

impl std::fmt::Display for PropertyColumnsOut {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Union table: rows are time segments partitioned by every match
        // endpoint, cells mark whether the column's expression fired there.
        let mut builder = Builder::default();
        let mut header: Vec<String> = vec!["time".into()];
        header.extend(self.columns.iter().map(|c| c.name.clone()));
        builder.push_record(header);
        for (ts, cells) in union_segments(&self.columns) {
            let mut row: Vec<String> = vec![ts.to_string()];
            row.extend(cells.iter().map(|&b| if b { "1".into() } else { "0".into() }));
            builder.push_record(row);
        }
        writeln!(f, "{}", builder.build())
    }
}

/// Multi-expression `--count` output.
#[derive(Serialize)]
pub(super) struct PropertyCountsOut {
    #[serde(flatten)]
    pub(super) cycles: SerdeRange<usize>,
    pub(super) total_cycles: usize,
    pub(super) columns: Vec<CountOut>,
}

#[derive(Serialize)]
pub(super) struct CountOut {
    pub(super) name: String,
    pub(super) count: usize,
}

impl std::fmt::Display for PropertyCountsOut {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let mut builder = Builder::default();
        builder.push_record(["expr", "count"]);
        for c in &self.columns {
            builder.push_record([c.name.as_str(), &c.count.to_string()]);
        }
        writeln!(f, "{}", builder.build())
    }
}

/// Partition the union of all matches into disjoint `[from, to]` segments,
/// each tagged with which columns cover it. A point match `[p, p]` yields a
/// degenerate segment `[p, p]`; a range covers a segment iff it contains it.
fn union_segments(columns: &[ColumnOut]) -> Vec<(SerdeRange<u64>, Vec<bool>)> {
    // Endpoints of every range, plus degenerate single-point segments.
    let mut pts: Vec<u64> = Vec::new();
    let mut points: Vec<u64> = Vec::new();
    for c in columns {
        for r in &c.matches {
            let (a, b) = (*r.0.start(), *r.0.end());
            pts.push(a);
            pts.push(b);
            if a == b {
                points.push(a);
            }
        }
    }
    pts.sort_unstable();
    pts.dedup();
    let mut segs: Vec<(u64, u64)> = pts.windows(2).map(|w| (w[0], w[1])).collect();
    segs.extend(points.into_iter().map(|p| (p, p)));
    segs.sort_unstable();
    segs.dedup();

    segs.into_iter()
        .map(|(s, e)| {
            let cells: Vec<bool> = columns
                .iter()
                .map(|c| {
                    c.matches
                        .iter()
                        .any(|r| *r.0.start() <= s && e <= *r.0.end())
                })
                .collect();
            (SerdeRange(s..=e), cells)
        })
        .collect()
}
