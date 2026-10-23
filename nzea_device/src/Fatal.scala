package nzea_device

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, NamedAttributeApi, OperationApi, given}

/** A simulator stop, for a placeholder that must fail loudly rather than feed the program a value.
  *
  * The FIRRTL `stop` op lowers to `$fatal` inside an `always @(posedge clock)` and is wrapped in `ifndef SYNTHESIS`, so
  * it terminates simulation and leaves synthesis alone. It is fatal by definition — unlike `assert`, whose default
  * lowering only prints — so no lowering flavor has to be chosen for the whole dump.
  */
def fatal(clock: Referable[Clock], when: Referable[Bool])(using Arena, Context, Block): Unit =
    val location = summon[LocationApi].locationUnknownGet
    summon[OperationApi]
        .operationCreate(
            name = "firrtl.stop",
            location = location,
            namedAttributes = Seq(
                summon[NamedAttributeApi]
                    .namedAttributeGet("exitCode".identifierGet, 1L.integerAttrGet(32.integerTypeGet)),
                summon[NamedAttributeApi].namedAttributeGet("name".identifierGet, "".stringAttrGet)
            ),
            operands = Seq(clock.refer, when.refer)
        )
        .appendToBlock()
