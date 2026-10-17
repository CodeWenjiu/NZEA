package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The bundle union the uop's operand pool forms: the pool is the storage, and this is the one conversion out of it, a
  * unit's own input bundle.
  *
  * The units' needs overlap (the ALU's `opA`, the branch unit's `base` and the sysu's `rs1` are the same slot), so the
  * union's storage is the shared set — the pool — not the widest member. A member's layout is its own — its fields are
  * named and ordered the way its unit reads them — so the conversion cannot be a bit reinterpretation: each unit
  * declares its own, beside its own bundle, and that declaration is the only way its input is ever built. A new unit
  * plugs in with one `given`.
  */
trait FuView[F <: Bundle]:

    def view(u: Referable[ExecSlot], config: CoreConfig)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext,
        TypeImpl
    ): Wire[F]

extension (u: Referable[ExecSlot])

    def viewOf[F <: Bundle](config: CoreConfig)(using
        FuView[F],
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext,
        TypeImpl
    ): Wire[F] = summon[FuView[F]].view(u, config)
