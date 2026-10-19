package nzea_fpga.logic

import nzea_fpga.XdcRegistry
import nzea_rtl.bus.LiteBusCdc

/** The bus crossing's timing, as this flow states it: the fact is [[nzea_rtl.bus.LiteBusCdc]]'s (which names its own
  * registers and bounds its own payload), and what is here is the rendering of that fact into the constraints this flow
  * reads. A level with another flow renders the same fact its own way; a level that instantiates no crossing writes
  * none of this.
  *
  * The tools cannot read the mechanism off the netlist — they model the payload as a same-edge crossing of the two
  * grids — so the exception has to be stated, and stating it is the only thing this file does.
  */
object BusCdcXdc:

    /** Register the crossing's constraints with the dump — said where a crossing is instantiated
      * ([[nzea_fpga.logic.DdrMemory.architecture]]), so the text travels exactly with the crossing it is about.
      */
    def register(syncStages: Int): Unit = XdcRegistry.onEmit("busCdc")(text(syncStages))

    /** `syncStages` is the crossing's own depth (`LiteBusCdcParameter`), from which the library derives the payload's
      * bound; the register names come from the library too, so what the constraint matches and what the body emits are
      * one statement.
      */
    def text(syncStages: Int): String =
        val names = LiteBusCdc.Names
        val edges = LiteBusCdc.payloadEdges(syncStages)
        s"""|# The bus crossing (nzea_rtl.bus.LiteBusCdc): what the two domains' phase relation does not say.
            |#
            |# The crossing is a toggle handshake: the sender latches the payload and flips a level, the receiver
            |# synchronizes that level through its flops and takes the payload the flip is about. So the payload has more
            |# than a cycle to arrive, which the tools cannot read off the netlist: they model it as a same-edge crossing
            |# and ask for the near grid's edge against the far grid's next one — 0.625 ns at this level's clock plan, which
            |# every payload bit misses (measured: 167 of them, 0.636 ns short, against a data path of 0.78 ns between two
            |# adjacent slices).
            |#
            |# 1. The payload's capture is enabled by the flip that has come through the synchronizer — its last flop
            |#    changes several far edges after the near edge that launched it — and the sender does not rewrite the
            |#    payload until the answer's flip is back. Stated as the synchronizer's own depth rather than as no
            |#    requirement at all: that many far edges is the least the chain guarantees, and one edge short of the
            |#    capture the body performs.
            |set_multicycle_path $edges -setup -from [get_cells -hierarchical {*${names.nearReqPayload}_*reg*}] -to [get_cells -hierarchical {*${names.farReqHeld}_*reg*}]
            |set_multicycle_path ${edges - 1} -hold  -from [get_cells -hierarchical {*${names.nearReqPayload}_*reg*}] -to [get_cells -hierarchical {*${names.farReqHeld}_*reg*}]
            |#
            |# The answer's payload is the same crossing the other way, and it is deliberately not stated: the grids'
            |# periods make the tools' own model of that direction already hold (34.375 ns of requirement against a 5 ns
            |# path), and a multicycle stated without its hold edge moves the hold check with it — 132 endpoints of the
            |# answer's own flops go to minus 34 ns, which is what a relayed hold check means between grids this far apart.
            |#
            |# 2. The flips themselves cross by design — a synchronizer's first flop taking a level from the other domain is
            |#    the whole of its job — so no edge relationship is checked against them. The toggles' patterns end in `reg*`
            |#    rather than naming the flop alone: the tool replicates a toggle, and a copy is a flop the same statement is
            |#    about. (The `*_i_*` cells beside them are the cone that computes the flip, not the flip.)
            |set_false_path -from [get_cells -hierarchical {*${names.nearReqToggle}_reg*}] -to [get_cells -hierarchical {*${names.farReqSync}*}]
            |set_false_path -from [get_cells -hierarchical {*${names.farRespToggle}_reg*}] -to [get_cells -hierarchical {*${names.nearRespSync}*}]
            |""".stripMargin
