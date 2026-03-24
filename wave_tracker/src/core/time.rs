use wellen::{Time, TimeTableIdx};

/// Largest time index `i` such that `time_table[i] <= t`.
pub fn find_time_idx_at_or_before(time_table: &[Time], t: Time) -> Option<TimeTableIdx> {
    if time_table.is_empty() {
        return None;
    }
    let mut best = None;
    for (i, &ti) in time_table.iter().enumerate() {
        if ti <= t {
            best = Some(i as u32);
        } else {
            break;
        }
    }
    best
}
