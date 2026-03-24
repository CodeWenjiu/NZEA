//! RTL-specific analysis passes over loaded waveforms (nzea pipeline debugging).
#![allow(clippy::all)]

mod bug;
mod deadlock;
mod deadlock_tail;
mod dispatch_lsq;
mod enq_match;
mod find_rob;
mod prf_iq;
mod rob_flush;
mod scan;
mod timeline;
mod trace_p_rd;
mod trace_pc;
mod trace_rob;
mod who_find;

pub use bug::bug_scan_pr_in_both;
pub use deadlock::deadlock_analysis;
pub use deadlock_tail::deadlock_tail;
pub use dispatch_lsq::dispatch_lsq_check;
pub use enq_match::find_enq_rob_id_p_rd;
pub use find_rob::find_rob_id_in_pipeline;
pub use prf_iq::prf_iq_mismatch_scan;
pub use rob_flush::{flush_sync_scan, rob_iq_desync_scan};
pub use scan::scan_time_range;
pub use timeline::timeline_trace;
pub use trace_p_rd::trace_p_rd_timeline;
pub use trace_pc::trace_pc_timeline;
pub use trace_rob::trace_rob_id_timeline;
pub use who_find::{find_p_rd_in_pipeline, who_produces_pr};
