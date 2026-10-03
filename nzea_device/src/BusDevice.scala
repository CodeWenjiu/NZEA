package nzea_device

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.bus.LiteBusRW
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Wiring every bus device shares: its parent drives its `clock` and `resetN` and connects its read/write `bus`.
  *
  * A device's port table is its own type, so a device carrying a bus is not one type a caller can name — but every bus
  * device declares the same three ports, so the wiring is reached by name and written here once, instead of at each
  * instantiation site. This is what lets a caller pick a device with a `match` on its window name, ascribe the result
  * to `Instance[?, ?]`, and wire it with one call. A device without a `bus` port is a wiring error, reported by name.
  */
extension (device: Instance[?, ?])

    def connect(clock: Referable[Clock], resetN: Referable[Reset], bus: Writable[LiteBusRW])(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line
    ): Unit =
        device.io.field[Clock]("clock") := clock
        device.io.field[Reset]("resetN") := resetN
        device.io.field[LiteBusRW]("bus") :<>= bus
