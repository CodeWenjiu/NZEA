use std::path::PathBuf;

use clap::Parser;

#[derive(Parser)]
#[command(name = "wave_tracker")]
#[command(about = "Load and inspect FST/VCD waveforms for nzea RTL debugging")]
pub struct Args {
    /// Path to waveform file
    #[arg(short, long)]
    pub file: Option<PathBuf>,

    /// List all signal names and exit
    #[arg(short, long)]
    pub list_signals: bool,

    /// Filter signals by name substring
    #[arg(short, long)]
    pub grep: Option<String>,

    /// Print signal values at this time (timescale units)
    #[arg(short, long)]
    pub time: Option<u64>,

    /// Start time filter (reserved)
    #[arg(short, long)]
    pub start: Option<u64>,

    /// End time filter (reserved)
    #[arg(short, long)]
    pub end: Option<u64>,

    /// Scan time range [start,end], print when signal matching grep changes (e.g. do_flush=1)
    #[arg(long)]
    pub scan: Option<u64>,

    /// With --scan: end time (default: start+500)
    #[arg(long)]
    pub scan_end: Option<u64>,

    /// With --scan: only print when any matching signal's value contains this string (e.g. "8000611" for next_pc ~0x80006118)
    #[arg(long)]
    pub filter_value: Option<String>,

    /// With --scan: only print when commit_bits_rd_index matches this 5-bit binary (e.g. "00010" for sp/x2)
    #[arg(long)]
    pub filter_rd_index: Option<String>,

    /// Bug scan: find cycles where PR is both in FreeList buf AND RMT(sp)=PR. PR in binary (e.g. "000010" for PR2)
    #[arg(long)]
    pub bug_scan: Option<u64>,

    /// With --bug-scan: end time (default: start+500)
    #[arg(long)]
    pub bug_scan_end: Option<u64>,

    /// With --bug-scan: PR value in 6-bit binary (default "000010" for PR2)
    #[arg(long, default_value = "000010")]
    pub bug_pr: String,

    /// Timeline trace: dump commits and enqs from start to end for t=108 bug analysis
    #[arg(long)]
    pub timeline: Option<u64>,

    /// With --timeline: end time (default: start+150)
    #[arg(long)]
    pub timeline_end: Option<u64>,

    /// PRF-IQ mismatch scan: find cycles where PRF has bank_ready=1 but IQ entry has rs1/rs2_ready=0
    #[arg(long)]
    pub prf_iq_mismatch: Option<u64>,

    /// With --prf-iq-mismatch: end time (default: start+5000)
    #[arg(long)]
    pub prf_iq_mismatch_end: Option<u64>,

    /// Deadlock analysis: find first PRF-IQ mismatch, then dump prf_write/bypass/iq state for t-25..t+2
    #[arg(long)]
    pub deadlock: Option<u64>,

    /// With --deadlock: end time (default: start+5000)
    #[arg(long)]
    pub deadlock_end: Option<u64>,

    /// Dump IQ state for last N cycles of trace to find all-blocked deadlock
    #[arg(long)]
    pub deadlock_tail: Option<u64>,

    /// Find who produces a given PR: scan for prf_write/commit with p_rd=PR
    #[arg(long)]
    pub who_produces: Option<String>,

    /// Find instruction with p_rd=PR in IQ/ROB/EXU/MemUnit (binary, e.g. 100101)
    #[arg(long)]
    pub find_p_rd: Option<String>,

    /// Find instruction with rob_id=X in EXU/MemUnit (binary, e.g. 00111 for slot 7)
    #[arg(long)]
    pub find_rob_id: Option<String>,

    /// Trace rob_id through full timeline: enq, IQ, issue, EXU, MemUnit (binary, e.g. 0111)
    #[arg(long)]
    pub trace_rob_id: Option<String>,

    /// With --trace-rob-id: start time (default 0)
    #[arg(long)]
    pub trace_rob_id_start: Option<u64>,

    /// With --trace-rob-id: end time (default: last)
    #[arg(long)]
    pub trace_rob_id_end: Option<u64>,

    /// Trace p_rd (producer) through timeline (binary, e.g. 100101 for PR37)
    #[arg(long)]
    pub trace_p_rd: Option<String>,

    /// With --trace-p-rd: start time (default 0)
    #[arg(long)]
    pub trace_p_rd_start: Option<u64>,

    /// With --trace-p-rd: end time (default: last)
    #[arg(long)]
    pub trace_p_rd_end: Option<u64>,

    /// Find when rob_id=X and p_rd=Y were enqueued together (binary, e.g. --enq-match 0111,100101)
    #[arg(long)]
    pub enq_match: Option<String>,

    /// Check LSQ dispatch sync: when rob_id,p_rd dispatched, did ROB+IQ+LSQ all fire? (e.g. 0111,100101)
    #[arg(long)]
    pub dispatch_lsq: Option<String>,

    /// With --dispatch-lsq: start time (default 0)
    #[arg(long)]
    pub dispatch_lsq_start: Option<u64>,

    /// With --dispatch-lsq: end time (default: last)
    #[arg(long)]
    pub dispatch_lsq_end: Option<u64>,

    /// Trace instruction by PC (hex, e.g. 80005c9c) through IQ/issue/BRU
    #[arg(long)]
    pub trace_pc: Option<String>,

    /// With --trace-pc: start time (default: last-500)
    #[arg(long)]
    pub trace_pc_start: Option<u64>,

    /// With --trace-pc: end time (default: last)
    #[arg(long)]
    pub trace_pc_end: Option<u64>,

    /// Scan for ROB-IQ desync: IQ full but rob_enq fired (instruction entered ROB but not IQ)
    #[arg(long)]
    pub rob_iq_desync: Option<u64>,

    /// With --rob-iq-desync: end time (default: start+50000)
    #[arg(long)]
    pub rob_iq_desync_end: Option<u64>,

    /// Scan for flush timing: when do_flush/iq.flush change, check ROB vs IQ sync
    #[arg(long)]
    pub flush_sync: Option<u64>,

    /// With --flush-sync: end time (default: start+50000)
    #[arg(long)]
    pub flush_sync_end: Option<u64>,
}
