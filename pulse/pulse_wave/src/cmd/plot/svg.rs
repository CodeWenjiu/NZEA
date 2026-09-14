//! SVG rendering for `pulse plot` — pure functions, no I/O.
//!
//! Layout constants are fixed; the caller supplies sampled values, cycle
//! bounds and mark ranges.
//!
//! ```text
//! +---------------+-----------------------------+
//! | mark labels   |  |  vertical mark lines     |
//! | signal name   |  waveform (per cycle cell)  |
//! | signal name   |  high low high  (X band:▒▒▒) |
//! |   ...         |        0x1f  value labels   |
//! +---------------+-----------------------------+
//! | cycle axis    | 0  10  20  30               |
//! | tick axis     | 1000 2000 3000 ...          |
//! +---------------+-----------------------------+
//! ```

use crate::SerdeRange;

/// A sampled signal value at one cycle.
#[derive(Clone, Copy, PartialEq)]
pub(crate) enum Val {
    Low,
    High,
    /// Multi-bit known value (rendered as high level + hex label).
    Vector(u64),
    /// X/Z — rendered as a translucent band, no edge.
    Unknown,
}

/// Everything the SVG renderer needs; produced by `plot/mod.rs`.
pub(crate) struct PlotData {
    /// Title text (the scope path).
    pub title: String,
    /// Signal names, one row each (top to bottom).
    pub signals: Vec<String>,
    /// Cycles in the window: (time-table index, tick). x = cycle index.
    pub cycles: Vec<(usize, u64)>,
    /// samples[s][c] = signal s at cycle c.
    pub samples: Vec<Vec<Val>>,
    /// marks[i] = match ranges (cycle indices) for mark expression i.
    pub marks: Vec<Vec<SerdeRange<u64>>>,
    /// Label for mark expression i (drawn at the top edge).
    pub mark_names: Vec<String>,
}

// Layout constants (px).
pub(crate) const LEFT: f64 = 200.0; // signal-name column
pub(crate) const ROW_H: f64 = 26.0; // one signal row
const WAVE_TOP: f64 = 4.0; // waveform top inside the row
const WAVE_H: f64 = 12.0; // waveform amplitude
pub(crate) const AXIS_H: f64 = 34.0; // bottom cycle/tick axis
pub(crate) const PX_PER_CYCLE: f64 = 8.0;
const X_BAND_OPACITY: f64 = 0.45;

/// Render the plot as an SVG document string.
pub(crate) fn render_svg(d: &PlotData) -> String {
    let n_cycles = d.cycles.len().max(1);
    let width = LEFT + n_cycles as f64 * PX_PER_CYCLE;
    let plot_top = 18.0 + if d.mark_names.is_empty() { 0.0 } else { 16.0 };
    let wave_bottom = plot_top + d.signals.len() as f64 * ROW_H;
    let height = wave_bottom + AXIS_H;

    let mut s = String::new();
    s.push_str(&format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 {:.0} {:.0}\" font-family=\"monospace\">\n",
        width, height
    ));
    s.push_str(&format!("<title>{}</title>\n", escape(&d.title)));

    // Background.
    s.push_str(&format!(
        "<rect width=\"{:.0}\" height=\"{:.0}\" fill=\"#ffffff\"/>\n",
        width, height
    ));

    // Signal rows: name column + row separators + name-column separator.
    for (i, name) in d.signals.iter().enumerate() {
        let row_top = plot_top + i as f64 * ROW_H;
        s.push_str(&format!(
            "<text x=\"8\" y=\"{:.0}\" font-size=\"12\" fill=\"#333\">{}</text>\n",
            row_top + WAVE_TOP + WAVE_H / 2.0 + 4.0,
            escape(name)
        ));
        s.push_str(&format!(
            "<line x1=\"{:.0}\" y1=\"{:.0}\" x2=\"0\" y2=\"{:.0}\" stroke=\"#eee\"/>\n",
            LEFT - 4.0,
            row_top,
            row_top
        ));
        s.push_str(&format!(
            "<line x1=\"{:.0}\" y1=\"0\" x2=\"{:.0}\" y2=\"{:.0}\" stroke=\"#ccc\"/>\n",
            LEFT - 4.0,
            LEFT - 4.0,
            wave_bottom
        ));
    }

    // Waveforms.
    for (i, samples) in d.samples.iter().enumerate() {
        render_wave(&mut s, n_cycles, samples, plot_top + i as f64 * ROW_H);
    }

    // Marks.
    if !d.mark_names.is_empty() {
        render_marks(&mut s, d, plot_top, wave_bottom);
    }

    // Axis.
    render_axis(&mut s, d, wave_bottom);

    s.push_str("</svg>\n");
    s
}

fn x(c: usize) -> f64 {
    LEFT + c as f64 * PX_PER_CYCLE
}

/// One signal's waveform: X bands first, then a step polyline over the known
/// runs (the line is broken at X boundaries so it never crosses a band).
fn render_wave(s: &mut String, n_cycles: usize, samples: &[Val], row_top: f64) {
    let y_low = row_top + WAVE_TOP + WAVE_H;
    let y_high = row_top + WAVE_TOP;

    // X bands.
    let mut in_x = false;
    let mut band_start = 0usize;
    for (c, v) in samples.iter().enumerate() {
        if *v == Val::Unknown {
            if !in_x {
                in_x = true;
                band_start = c;
            }
        } else if in_x {
            push_x_band(s, band_start, c, y_low);
            in_x = false;
        }
    }
    if in_x {
        push_x_band(s, band_start, n_cycles, y_low);
    }

    // Step polyline + vector value labels. The polyline alternates horizontal
    // runs (same level, no points) and vertical edges; each value change emits
    // `(x, old_y) (x, new_y)`, so the line never slopes. X boundaries flush the
    // path so it never crosses a band; the final run extends to the right edge.
    let mut path = String::new();
    let mut pending: Option<(usize, f64)> = None;
    let mut prev_vec: Option<u64> = None;

    for (c, v) in samples.iter().enumerate() {
        let y = match *v {
            Val::Low => y_low,
            Val::High | Val::Vector(_) => y_high,
            Val::Unknown => {
                flush_path(
                    s,
                    &mut path,
                    Some((x(c), pending.map(|(_, py)| py).unwrap_or(y_low))),
                );
                pending = None;
                prev_vec = None;
                continue;
            }
        };
        match pending {
            Some((_, py)) if py != y => {
                // Horizontal run to this cycle's left edge, then vertical edge.
                path.push_str(&format!("{:.1},{:.1} {:.1},{:.1} ", x(c), py, x(c), y));
                pending = Some((c, y));
            }
            Some(_) => { /* same level: nothing to draw */ }
            None => {
                path.push_str(&format!("{:.1},{:.1} ", x(c), y));
                pending = Some((c, y));
            }
        }
        if let Val::Vector(v) = *v {
            if prev_vec != Some(v) {
                s.push_str(&format!(
                    "<text x=\"{:.1}\" y=\"{:.1}\" font-size=\"9\" fill=\"#888\">{:x}</text>\n",
                    x(c) + 1.0,
                    y_low + 2.0,
                    v
                ));
                prev_vec = Some(v);
            }
        } else {
            prev_vec = None;
        }
    }
    // Extend the final run to the window's right edge.
    if let Some((_, py)) = pending {
        flush_path(s, &mut path, Some((x(n_cycles), py)));
    } else {
        flush_path(s, &mut path, None);
    }
}

fn push_x_band(s: &mut String, from: usize, to: usize, y_low: f64) {
    s.push_str(&format!(
        "<rect x=\"{:.1}\" y=\"{:.1}\" width=\"{:.1}\" height=\"{:.1}\" fill=\"#ffd\" opacity=\"{X_BAND_OPACITY}\"/>\n",
        x(from),
        y_low - WAVE_H,
        (to - from).max(1) as f64 * PX_PER_CYCLE,
        WAVE_H
    ));
}

fn flush_path(s: &mut String, path: &mut String, tail: Option<(f64, f64)>) {
    if !path.is_empty() {
        if let Some((tx, ty)) = tail {
            path.push_str(&format!("{tx:.1},{ty:.1} "));
        }
        s.push_str(&format!(
            "<polyline points=\"{}\" fill=\"none\" stroke=\"#000\" stroke-width=\"1.5\"/>\n",
            path.trim()
        ));
        path.clear();
    }
}

/// Vertical lines for point matches, translucent bands for interval matches.
fn render_marks(s: &mut String, d: &PlotData, plot_top: f64, wave_bottom: f64) {
    let colors = ["#d33", "#33d", "#3a8", "#d83"];
    let n_cycles = d.cycles.len().max(1);
    let xm = |c: usize| x(c.min(n_cycles - 1));
    for (i, (name, ranges)) in d.mark_names.iter().zip(&d.marks).enumerate() {
        let color = colors[i % colors.len()];
        let label_y = 12.0 + i as f64 * 11.0;
        s.push_str(&format!(
            "<text x=\"8\" y=\"{label_y:.0}\" font-size=\"10\" fill=\"{color}\">mark {i}: {}</text>\n",
            escape(name)
        ));
        for r in ranges {
            let from = *r.0.start() as usize;
            let to = *r.0.end() as usize;
            if from == to {
                s.push_str(&format!(
                    "<line x1=\"{:.1}\" y1=\"{:.1}\" x2=\"{:.1}\" y2=\"{:.1}\" stroke=\"{color}\" stroke-dasharray=\"3,2\"/>\n",
                    xm(from),
                    plot_top - 8.0,
                    xm(from),
                    wave_bottom
                ));
            } else {
                s.push_str(&format!(
                    "<rect x=\"{:.1}\" y=\"{:.1}\" width=\"{:.1}\" height=\"{:.1}\" fill=\"{color}\" opacity=\"0.15\"/>\n",
                    xm(from),
                    plot_top - 8.0,
                    (to - from).max(1) as f64 * PX_PER_CYCLE,
                    wave_bottom - plot_top + 8.0
                ));
            }
        }
    }
}

/// Bottom axis: nice step over cycle indices, tick value beneath each label.
fn render_axis(s: &mut String, d: &PlotData, wave_bottom: f64) {
    let n = d.cycles.len();
    if n == 0 {
        return;
    }
    let cyc_y = wave_bottom + 14.0;
    let tick_y = wave_bottom + 27.0;
    let step = nice_step(n);
    let from_cycle = |i: usize| -> f64 {
        // First labelled cycle is offset 0, drawn at x(0).
        x(i)
    };
    s.push_str(&format!(
        "<line x1=\"{:.1}\" y1=\"{:.1}\" x2=\"{:.1}\" y2=\"{:.1}\" stroke=\"#999\"/>\n",
        x(0),
        wave_bottom,
        x(n.saturating_sub(1)),
        wave_bottom
    ));
    let mut i = 0usize;
    while i < n {
        let is_last = i + step >= n;
        s.push_str(&format!(
            "<line x1=\"{:.1}\" y1=\"{:.1}\" x2=\"{:.1}\" y2=\"{:.1}\" stroke=\"#999\"/>\n",
            from_cycle(i),
            wave_bottom,
            from_cycle(i),
            wave_bottom + 5.0
        ));
        s.push_str(&format!(
            "<text x=\"{:.1}\" y=\"{:.1}\" font-size=\"10\" fill=\"#444\">{}</text>\n",
            from_cycle(i) + 2.0,
            cyc_y,
            i
        ));
        s.push_str(&format!(
            "<text x=\"{:.1}\" y=\"{:.1}\" font-size=\"9\" fill=\"#999\">{}</text>\n",
            from_cycle(i) + 2.0,
            tick_y,
            d.cycles[i].1
        ));
        if is_last {
            break;
        }
        i += step;
    }
    // Always label the final cycle too (so the right edge is readable).
    let last_txt = if (n - 1).is_multiple_of(step) {
        String::new()
    } else {
        format!(
            "<text x=\"{:.1}\" y=\"{:.1}\" font-size=\"10\" fill=\"#444\">{}</text>\n<text x=\"{:.1}\" y=\"{:.1}\" font-size=\"9\" fill=\"#999\">{}</text>\n",
            from_cycle(n - 1) + 2.0,
            cyc_y,
            n - 1,
            from_cycle(n - 1) + 2.0,
            tick_y,
            d.cycles[n - 1].1
        )
    };
    s.push_str(&last_txt);
}

/// Largest "nice" step (1/2/5 × 10^k) that keeps ~8–16 axis labels.
fn nice_step(n: usize) -> usize {
    if n <= 12 {
        return 1;
    }
    let target = (n as f64 / 10.0).ceil().max(1.0) as u64;
    let mut k = 0u64;
    while 10u64.pow((k + 1) as u32) <= target {
        k += 1;
    }
    let base = 10u64.pow(k as u32);
    for m in [1u64, 2, 5, 10] {
        if m * base >= target {
            return (m * base) as usize;
        }
    }
    (10 * base) as usize
}

/// Minimal XML escaping for names/titles/labels.
fn escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn plot(cycles: usize) -> PlotData {
        PlotData {
            title: "TOP.test".into(),
            signals: vec!["clk".into(), "data".into()],
            cycles: (0..cycles).map(|i| (i, 100 * i as u64)).collect(),
            samples: vec![
                (0..cycles)
                    .map(|c| if c % 2 == 0 { Val::High } else { Val::Low })
                    .collect(),
                (0..cycles)
                    .map(|c| match c {
                        0 => Val::Vector(0xdead),
                        1 => Val::Unknown,
                        2 => Val::Vector(0xbeef),
                        _ => Val::Low,
                    })
                    .collect(),
            ],
            marks: vec![vec![SerdeRange(1..=1)]],
            mark_names: vec!["req_fire".into()],
        }
    }

    #[test]
    fn renders_svg_skeleton() {
        let svg = render_svg(&plot(40));
        assert!(svg.starts_with("<svg xmlns="));
        assert!(svg.contains("</svg>"));
        assert!(svg.contains("TOP.test"));
    }

    #[test]
    fn renders_signal_names_and_waveforms() {
        let svg = render_svg(&plot(40));
        assert!(svg.contains(">clk<"));
        assert!(svg.contains(">data<"));
        assert!(svg.contains("<polyline"));
        // Value label for the vector change.
        assert!(svg.contains("dead"));
        assert!(svg.contains("beef"));
    }

    #[test]
    fn renders_x_band_and_mark() {
        let svg = render_svg(&plot(40));
        // X band rect for cycle 1 (data==Unknown).
        assert!(svg.contains("fill=\"#ffd\""));
        // Mark line at cycle 1.
        assert!(svg.contains("req_fire"));
        assert!(svg.contains("stroke-dasharray=\"3,2\""));
    }

    #[test]
    fn axis_has_labels() {
        let svg = render_svg(&plot(120));
        // First and last cycle indices appear; ticks too.
        assert!(svg.contains(">0<"));
        assert!(svg.contains(">119<"));
        assert!(svg.contains(">11900<"));
    }

    #[test]
    fn escapes_title() {
        let mut d = plot(4);
        d.title = "a<b>&c".into();
        let svg = render_svg(&d);
        assert!(svg.contains("a&lt;b&gt;&amp;c"));
    }
}
