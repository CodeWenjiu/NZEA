package nzea_rtl.bus

import java.lang.foreign.Arena

import scala.annotation.targetName

import me.jiuyang.stdlib.{DecoupledIO, HardwareDataType}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** What a bus boundary register costs, and what it buys.
  *
  * Both policies keep one request per cycle at steady state; they differ in what the producer's `fire` depends on.
  *   - [[Slice]]: one entry, elastic. `ready = !full | out.ready`, so the consumer's ready reaches back into the
  *     producer's handshake. Right when the producer already waits on the bus — a load's address is live off the
  *     execute register and the load is going to wait anyway, so hiding the wait would only move the cost.
  *   - [[Skid]]: two entries. `ready = !full` — the buffer's own state — so nothing downstream reaches the producer's
  *     fire. Right when the producer's fire drives work that must not wait on the fabric (a fetch's pc advances on the
  *     request's acceptance): the second entry absorbs the first cycle of a stall.
  */
enum BufferPolicy:
    case Slice, Skid

/** The boundary register itself, on a bus's request channel.
  *
  * `in` is the near side (the producer's handshake), `out` the far side — what the fabric sees — and `policy`
  * ([[BufferPolicy]]) the shape. The registers land in the caller's scope, named after `name` (`<name>Valid`,
  * `<name>Head`/`<name>Tail`), so waveforms, event files and timing reports read them as the boundary they are.
  *
  * `flush` is the caller's statement that what this buffer holds is speculative: held requests are dropped and nothing
  * held is presented, because the stream that made them is gone. It is the caller's promise to make — a dropped request
  * is never answered, which leaves no one waiting only if the caller is the one that would have given up on the answer
  * anyway. A fetch may say it (the redirect's epoch already digests its responses); a store may not (its write is the
  * only copy).
  */
def bufferConnect[T <: HardwareDataType](
    in: Referable[DecoupledIO[T]],
    out: Referable[DecoupledIO[T]],
    policy: BufferPolicy,
    name: String,
    flush: Option[Referable[Bool]]
)(using
    ClockScope,
    ResetScope,
    Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    InstanceContext
): Unit =
    // A flush is one cycle's event, and the buffer's state is what it drops: a request accepted this cycle never leaves
    // the buffer's register, so it dies with the rest, and one already presented is withdrawn (`out.valid`) rather than
    // sent to a fabric that would answer it.
    val flushing = flush.getOrElse(false.B)
    policy match
        case BufferPolicy.Slice =>
            val valid =
                given sourcecode.Name.Machine = sourcecode.Name.Machine(s"${name}Valid")
                RegInit(false.B)
            val bits =
                given sourcecode.Name.Machine = sourcecode.Name.Machine(name)
                Reg(in.bits.getType)

            val ready = !valid | out.ready
            in.ready := ready
            out.valid := valid & !flushing
            out.bits :<= bits

            // Elastic: a handover when the consumer takes the held request, a fill when the producer fires.
            val fire = in.valid & ready
            when(flushing) {
                valid := false.B
            }.otherwise {
                when(fire) {
                    bits :<= in.bits
                    valid := true.B
                }.otherwise {
                    when(out.valid & out.ready) {
                        valid := false.B
                    }
                }
            }

        case BufferPolicy.Skid =>
            val headValid =
                given sourcecode.Name.Machine = sourcecode.Name.Machine(s"${name}HeadValid")
                RegInit(false.B)
            val headBits =
                given sourcecode.Name.Machine = sourcecode.Name.Machine(s"${name}Head")
                Reg(in.bits.getType)
            val tailValid =
                given sourcecode.Name.Machine = sourcecode.Name.Machine(s"${name}TailValid")
                RegInit(false.B)
            val tailBits =
                given sourcecode.Name.Machine = sourcecode.Name.Machine(s"${name}Tail")
                Reg(in.bits.getType)

            // Ready is `!full` — the buffer's own state — so the producer's fire never waits on the fabric.
            val full = headValid & tailValid
            in.ready := !full
            out.valid := headValid & !flushing
            out.bits :<= headBits

            // The four cases keep one request per cycle when the fabric drains every cycle: a push with the head empty
            // lands in the head (whether or not the head is being taken), and a push with the head occupied lands in
            // the tail; a pop shifts the tail into the head. A flush overrides all of them: what the buffer holds is a
            // dead stream's.
            val inFire = in.valid & in.ready
            val outFire = out.valid & out.ready
            when(flushing) {
                headValid := false.B
                tailValid := false.B
            }.otherwise {
                when(outFire) {
                    when(tailValid) {
                        headValid := true.B
                        headBits :<= tailBits
                        when(inFire) {
                            tailValid := true.B
                            tailBits :<= in.bits
                        }.otherwise {
                            tailValid := false.B
                        }
                    }.otherwise {
                        when(inFire) {
                            headValid := true.B
                            headBits :<= in.bits
                        }.otherwise {
                            headValid := false.B
                        }
                        tailValid := false.B
                    }
                }.otherwise {
                    when(inFire) {
                        when(headValid) {
                            tailValid := true.B
                            tailBits :<= in.bits
                        }.otherwise {
                            headValid := true.B
                            headBits :<= in.bits
                        }
                    }
                }
            }

/** The bus buffered at a boundary: the far end, to hand to whatever is on the other side.
  *
  * One method, one policy ([[BufferPolicy]]) — the shape is the argument's, not the name's, so the two flavours a bus
  * comes in (read-only, read-write) keep their own payload and share the call. The buffer is the caller's own datapath:
  * the registers land in the caller's scope — named after the buffer, `name`, so waveforms, event files and timing
  * reports read them — and take their clock and reset from the ambient scopes, so a boundary is one line at the site
  * that declares it:
  *
  * {{{io.ibus :<>= front.io.ibus.buffered("ibusBuffer", BufferPolicy.Skid, Some(middle.io.flush.valid))}}}
  *
  * The request channel carries the policy and the flush; the response channel passes through combinationally, so
  * nothing crosses the buffer in that direction.
  *
  * `flush` exists on the read-only flavour only: dropping a request is a promise that no one is waiting on its answer,
  * and only a read-only producer can make it — a fetch's redirect digests its responses by epoch, while a store's write
  * is the only copy there is.
  */
extension (bus: Ref[LiteBusRO])

    @targetName("bufferedRO")
    def buffered(name: String, policy: BufferPolicy, flush: Option[Referable[Bool]])(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext,
        ClockScope,
        ResetScope
    ): Wire[LiteBusRO] =
        given sourcecode.Name.Machine = sourcecode.Name.Machine(name)

        val far = Wire(bus.getType)
        bufferConnect(bus.req, far.req, policy, name, flush)

        // Response: straight through; the fabric drives valid/bits and the master drives ready.
        bus.resp.valid := far.resp.valid
        bus.resp.bits :<>= far.resp.bits
        far.resp.ready := bus.resp.ready

        far

extension (bus: Ref[LiteBusRW])

    @targetName("bufferedRW")
    def buffered(name: String, policy: BufferPolicy)(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext,
        ClockScope,
        ResetScope
    ): Wire[LiteBusRW] =
        given sourcecode.Name.Machine = sourcecode.Name.Machine(name)

        val far = Wire(bus.getType)
        // No flush: a read/write bus is the store side, and a store's write is the only copy there is.
        bufferConnect(bus.req, far.req, policy, name, None)

        // Response: straight through; the fabric drives valid/bits and the master drives ready.
        bus.resp.valid := far.resp.valid
        bus.resp.bits :<>= far.resp.bits
        far.resp.ready := bus.resp.ready

        far
