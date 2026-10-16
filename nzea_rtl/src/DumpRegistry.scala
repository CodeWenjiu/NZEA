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
    private val emitters = mutable.LinkedHashMap.empty[String, os.Path => Unit]

    /** Register a registry's reset. Called once per module, when that module's state is first touched. */
    def onReset(reset: () => Unit): Unit = resets += reset

    /** Register what a dump has to write beside its RTL — a module body, an IP's snippets, a debug script — under the
      * emitter's own name. Registering *is* how a module says the dump used it: [[nzea_rtl.stdlib.SyncRam.instantiate]]
      * and its peers register as they instantiate, and a level's own top registers the parts that are the level. The
      * key is what keeps a module instantiated many times — or a level elaborated twice in one JVM — to one emitter,
      * and the order is first use.
      *
      * An emitter reads its own state when it runs, never a captured moment, so what the list holds is the module.
      */
    def onEmit(key: String)(emit: os.Path => Unit): Unit = emitters.getOrElseUpdate(key, emit)

    /** Write every registered emitter's artifacts into `dir`, in the order they were first used. What the design did
      * not use was never registered, so nothing writes a body the circuit does not instantiate.
      */
    def emitAll(dir: os.Path): Unit = emitters.valuesIterator.foreach(_.apply(dir))

    /** Forget every registered module's state; an elaboration calls this before it builds anything. */
    def reset(): Unit =
        resets.foreach(_())
        emitters.clear()
