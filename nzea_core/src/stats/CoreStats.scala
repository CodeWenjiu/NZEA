package nzea_core.stats

import nzea_rtl.{StatCounter, StatDerived, StatRegion, StatSchema, StatsRegs}

/** Statistics declared by the baseline core: the single source of truth for both the RTL counters (one
  * [[StatsRegs.verilog]] black box per group, instantiated where the events happen) and the emitted
  * `<design>.stats.toml` schema.
  *
  * Counter names are VPI leaf names and must stay stable — a simulator addresses counters by them, and the wrappers'
  * ports are derived from the same names. Derived entries are evaluated in declaration order (a rule may use counters
  * and rules above it); editing a rule changes what every consumer reports, so keep docs and units honest.
  */
object CoreStats:

  val Throughput = StatRegion("throughput", "Retire throughput")
  val Bp = StatRegion("bp", "Branch prediction")

  val cycle = StatCounter("stat_cycle", StatsRegs.Width, Throughput, "core clock cycles")
  val instCommit = StatCounter("stat_inst_commit", StatsRegs.Width, Throughput, "retired instructions")

  val bpBranch = StatCounter("stat_bp_branch", StatsRegs.Width, Bp, "resolved branches")
  val bpMispred = StatCounter("stat_bp_mispred", StatsRegs.Width, Bp, "branches whose prediction was wrong")
  val bpActualTaken = StatCounter("stat_bp_actual_taken", StatsRegs.Width, Bp, "branches actually taken")

  /** Counters instantiated by the middle. */
  val throughputCounters: Seq[StatCounter] = Seq(cycle, instCommit)

  /** Counters instantiated by the int path, where the branch resolves. */
  val bpCounters: Seq[StatCounter] = Seq(bpBranch, bpMispred, bpActualTaken)

  val schema: StatSchema = StatSchema(
    regions = Seq(Throughput, Bp),
    counters = throughputCounters ++ bpCounters,
    derived = Seq(
      StatDerived("ipc", "stat_inst_commit / stat_cycle", Throughput, "", 4, "instructions per cycle"),
      StatDerived("cpi", "1 / ipc", Throughput, "", 4, "cycles per instruction"),
      StatDerived(
        "mispredict_rate",
        "stat_bp_mispred / stat_bp_branch * 100",
        Bp,
        "%",
        2,
        "share of resolved branches predicted wrongly"
      ),
      StatDerived(
        "taken_rate",
        "stat_bp_actual_taken / stat_bp_branch * 100",
        Bp,
        "%",
        2,
        "share of branches actually taken"
      )
    )
  )
