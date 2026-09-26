package nzea_core.stats

import nzea_rtl.{StatCounter, StatDerived, StatRegion, StatSchema}

/** Statistics declared by the baseline core: the single source of truth for both the RTL counters
  * ([[nzea_rtl.StatsRegs]] instantiations in `Core` and `BRU`) and the emitted `<design>.stats.toml` schema.
  *
  * Counter names are VPI leaf names and must stay stable — a simulator addresses counters by them. Derived entries are
  * evaluated in declaration order (a rule may use counters and rules above it); editing a rule changes what every
  * consumer reports, so keep docs and units honest.
  */
object CoreStats {

  val Throughput = StatRegion("throughput", "Retire throughput")
  val Bp = StatRegion("bp", "Branch prediction")

  val cycle = StatCounter("stat_cycle", 64, Throughput, "core clock cycles")
  val instCommit = StatCounter("stat_inst_commit", 64, Throughput, "retired instructions")

  val bpBranch = StatCounter("stat_bp_branch", 32, Bp, "resolved branches")
  val bpMispred = StatCounter("stat_bp_mispred", 32, Bp, "branches whose prediction was wrong")
  val bpTaken = StatCounter("stat_bp_actual_taken", 32, Bp, "branches actually taken")

  /** Counters instantiated by `Core` / `BRU`; every declared counter must be wired by exactly one of them. */
  val coreCounters: Seq[StatCounter] = Seq(cycle, instCommit)
  val bpCounters: Seq[StatCounter] = Seq(bpBranch, bpMispred, bpTaken)

  val schema: StatSchema = StatSchema(
    regions = Seq(Throughput, Bp),
    counters = coreCounters ++ bpCounters,
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
        "share of resolved branches actually taken"
      )
    )
  )

}
