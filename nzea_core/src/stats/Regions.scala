package nzea_core.stats

import nzea_rtl.stat.StatRegion

/** The subjects statistics are classified by: what a number measures, as opposed to which block declares it. A region
  * spans blocks, so the regions live here, shared, while the counters themselves are declared beside the modules that
  * observe them.
  */
object Regions:

    val Throughput = StatRegion("throughput", "Retire throughput")
    val Fetch = StatRegion("fetch", "Instruction fetch")
    val Bp = StatRegion("bp", "Branch prediction")
