package nzea_rtl.bus

import java.lang.foreign.Arena

import scala.annotation.targetName

import me.jiuyang.stdlib.{DecoupledIO, HardwareDataType}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
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

/** The boundary register on a bus's response channel — the mirror of [[bufferConnect]].
  *
  * A response's producer is the slave, and the answer it hands over must not wait on the consumer: an in-order core's
  * readiness is the whole pipeline's back-pressure cone, and a slave whose own handover — a cache's promote, an engine's
  * ack — reads it would drag that cone into the slave's arbitration. So `in.ready` is the buffer's own state (`!full`),
  * two entries absorb a consumer's stall, and an answer that finds the buffer empty bypasses it, which keeps the common
  * case at the same latency. A response is never speculative, so there is no flush.
  *
  * The entries are addressed by pointers, not shifted down: what the consumer's readiness reaches is then the count and
  * the pointers — a couple of gates — instead of the selects of the wide data muxes, which the pointers keep registers.
  */
def responseConnect[T <: HardwareDataType](
    in: Referable[DecoupledIO[T]],
    out: Referable[DecoupledIO[T]],
    name: String
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
    val depth = 2
    val cntW = log2Ceil(depth + 1)

    val slot0 =
        given sourcecode.Name.Machine = sourcecode.Name.Machine(s"${name}Slot0")
        Reg(in.bits.getType)
    val slot1 =
        given sourcecode.Name.Machine = sourcecode.Name.Machine(s"${name}Slot1")
        Reg(in.bits.getType)
    val wrPtr =
        given sourcecode.Name.Machine = sourcecode.Name.Machine(s"${name}WrPtr")
        RegInit(false.B)
    val rdPtr =
        given sourcecode.Name.Machine = sourcecode.Name.Machine(s"${name}RdPtr")
        RegInit(false.B)
    val count =
        given sourcecode.Name.Machine = sourcecode.Name.Machine(s"${name}Count")
        RegInit(0.U(cntW))

    val empty = count === 0.U(cntW)
    val full = count === depth.U(cntW)
    in.ready := !full
    // An answer that finds the buffer empty goes straight out; one that does not is deposited and delivered later. The
    // pointers are what the two data muxes — the deposit's slot and the head's — are selected by.
    val bypass = in.valid & empty & out.ready
    out.valid := !empty | (in.valid & empty)
    out.bits :<= (empty ? (in.bits, (rdPtr ? (slot1, slot0))))
    val push = in.valid & in.ready & !bypass
    val pop = !empty & out.ready

    when(push) {
        when(wrPtr) { slot1 :<= in.bits }.otherwise { slot0 :<= in.bits }
        wrPtr := !wrPtr
    }
    when(pop) { rdPtr := !rdPtr }
    count := subWrap(
        addWrap(count, push ? (1.U(cntW), 0.U(cntW)), cntW),
        pop ? (1.U(cntW), 0.U(cntW)),
        cntW
    )

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
  * The request channel carries the policy and the flush; the read-only flavour's response channel is buffered too
  * ([[responseConnect]]), because its consumer is the pipeline itself — the ready cone the request buffer keeps out of
  * the fabric would otherwise come back in through the answer. The read/write flavour's response stays combinational:
  * its consumers drain (a store's acknowledgment, a refill's beats), so a buffer there is state nobody reads.
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

        // The answer is the mirror buffer: the consumer's ready — the middle's whole readiness, an in-order fetch's
        // back-pressure — stops at this boundary instead of reaching into the fabric.
        responseConnect(far.resp, bus.resp, s"${name}Resp")

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
