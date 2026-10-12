package nzea_rtl

import scala.collection.mutable

/** The state a dump carries across one elaboration: the bodies and snippets a design instantiates but does not define —
  * the emitted sync-ram bodies, the vendor-IP tcl — are recorded while the circuit is built and written out once it is
  * lowered, so the next elaboration has to start from nothing.
  *
  * Each owner registers its own reset here, because the owners live in different modules (`nzea_rtl`'s ram bodies,
  * `nzea_fpga`'s IPs) and only the elaboration itself — [[Elaborate.withLinked]] — runs for all of them.
  */
object DumpRegistry:

    private val resets = mutable.ArrayBuffer.empty[() => Unit]

    /** Register a registry's reset. Called once per module, when that module's state is first touched. */
    def onReset(reset: () => Unit): Unit = resets += reset

    /** Forget every registered module's state; an elaboration calls this before it builds anything. */
    def reset(): Unit = resets.foreach(_())
