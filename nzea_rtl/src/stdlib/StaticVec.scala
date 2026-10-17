package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import scala.compiletime.{constValue, error}

/** A vector whose length is part of its type: `N` is a singleton type parameter, so a literal index is range-checked at
  * compile time — `v.ref(0)` and `v.ref(1)` on a two-element vector, anything else is a compile error naming the call.
  *
  * It is an opaque view of [[me.jiuyang.zaozi.valuetpe.Vec]]: the value is a plain vector, so the MLIR type, the
  * generated ports and the vector API itself (`toSeq`, `asBits`, bulk connect) are unchanged — only the index into it
  * knows the length.
  */
opaque type StaticVec[E <: Data, N <: Int & Singleton] <: Vec[E] = Vec[E]

object StaticVec:

    def apply[E <: Data, N <: Int & Singleton](elementType: E)(using ValueOf[N], ConstructorApi): StaticVec[E, N] =
        summon[ConstructorApi].Vec(summon[ValueOf[N]].value, elementType)

    /** The vector's own access, called through its given explicitly: within this file the opaque type is transparent,
      * so the extension below is a candidate for the very call this forwards.
      */
    private[stdlib] def rawRef[E <: Data](v: Referable[Vec[E]], idx: Referable[UInt] | Int)(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Ref[E] = summon[VecApi[E, Vec[E]]].ref(v)(idx)

extension [E <: Data, N <: Int & Singleton](v: Referable[StaticVec[E, N]])

    /** A literal index, range-checked at compile time. */
    inline def ref(inline idx: Int)(using
        ValueOf[N],
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Ref[E] =
        inline if idx < 0 || idx >= constValue[N] then error("StaticVec index is out of range")
        else StaticVec.rawRef(v, idx)

    /** A dynamic index: the vector's own access, unchecked as any dynamic index is. */
    def ref(idx: Referable[UInt])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Ref[E] = StaticVec.rawRef(v, idx)
