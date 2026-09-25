package nzea_core.back

import chisel3._
import chisel3.util.Valid
import nzea_core.isa.{CsrType, FuType}
import nzea_config.core.CoreConfig

/** Backend exit-latch payload: the result of one uop, from the merging point to the commit side. */
class BackResult(width: Int) extends Bundle {
  val data = UInt(32.W)
  val next_pc = UInt(width.W)
  val csr_type = CsrType()
  val csr_data = UInt(32.W)
  val is_mmio = Bool()
}

/** Execution-path contract (module view): the uniform shape every backend path exposes to [[Back]].
  *
  * `done` is a level — the uop is held until its completion is consumed, and the edge is detected exactly once at the
  * merging point ([[Back]]), so a path never consumes its own completion. `result` is in the exit-latch format. The
  * baseline path is a combinational dispatch; a scheduler (in-order queue or OoO issue) later grows behind this port
  * without the merging point noticing.
  */
class ExecPathPort(width: Int)(implicit config: CoreConfig) extends Bundle {
  val issue = Flipped(Valid(new IssueUop(width)))
  val flush = Input(Bool())
  val done = Output(Bool())
  val result = Output(new BackResult(width))
  val redirect = Output(Valid(UInt(width.W)))
}

/** FuType → execution path routing table. Elaboration fails if a FuType is unclaimed or claimed twice, instead of
  * silently issuing a uop that no path consumes.
  */
object ExecPath {
  val intFuTypes: Seq[FuType.Type] = Seq(FuType.ALU, FuType.BRU, FuType.MUL, FuType.DIV, FuType.SYSU)
  val memFuTypes: Seq[FuType.Type] = Seq(FuType.LSU)

  private val routed = (intFuTypes ++ memFuTypes).map(_.litValue)

  require(
    routed.distinct.sorted == FuType.all.map(_.litValue).sorted,
    "execution paths must cover every FuType exactly once"
  )
}