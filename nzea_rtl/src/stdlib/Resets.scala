package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.{Instance, Ref, Referable}
import me.jiuyang.zaozi.valuetpe.{Bool, Clock, Reset}
import nzea_rtl.DumpRegistry
import nzea_rtl.probe.Probes
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import scala.collection.mutable

/** The design's reasons to hold, and the resets they make.
  *
  * A reset here is not one signal that travels through the design: it is the conjunction of the *reasons* a domain
  * waits for, taken through one synchronizer clocked by that domain. Two statements, each made where it belongs:
  *
  *   - a **term** is a named level declared by the part that produces it ([[term]]): the board's button as the pin
  *     presents it, the clock generator's lock, a device's own ready. `1` means *this reason is not holding*. A term is
  *     an *asynchronous* level — that is what it is, and nothing here asks it to be clean;
  *   - a **domain** states the terms it waits for, where the domain's own instances are named ([[domain]]): the
  *     synchronizer is created there on that domain's clock, and the domain gets the reset its registers take.
  *
  * Because of that split, a domain names the *raw* reason rather than a level another domain happened to clean: the
  * board's button reaches every domain as the pin, and each domain's own synchronizer is what makes it belong to that
  * domain. Sharing one domain's synchronized level with another domain would be a crossing we would then have to say
  * something about — the one thing this shape removes instead of papers over.
  *
  * One reset per domain, per recipe: a name identifies a reset, so two consumers in one domain that wait for the same
  * reasons name the same reset and share the one synchronizer — and therefore the one release edge. Two chains sampling
  * the same asynchronous level on the same clock can resolve its metastability differently on the same edge, which is
  * how registers end up leaving a reset in different cycles; asking for the same name is how a design says "these leave
  * together".
  *
  * The composition itself is deferred to [[materialize]], for the same reason the clock plan is: a reason's producer
  * may only exist late (a wizard's `locked` exists once the wizard is built, and the wizard is built once every clock
  * has been asked for — [[nzea_fpga.Clocks]]). A term a domain names but nobody declared fails there, loudly, listing
  * what was declared — not silently as a constant.
  *
  * What this is *not*: a design-wide reset tree. Domains differ on purpose — the oscillator's own domain waits for the
  * button; a domain fed by the generator also waits for its lock; a device also waits for its own calibration — and the
  * point of the network is that each says so without knowing about the others.
  *
  * What the synchronizer is, and is not: it asserts asynchronously and releases synchronously ([[ResetSync]]), which is
  * the shape a domain whose clock can stop needs — a purely synchronous reset cannot hold a domain whose clock is not
  * running yet (the wizard has not locked) or not running any more. Registering the reasons *before* it would not cut a
  * path either: the crossing would only move from the synchronizer's asynchronous input to a flip-flop's `D`, and a
  * single register would put a level that may be metastable on the reset of a whole domain. The two flops here are what
  * make that level safe to use; the conjunction feeding them is an asynchronous input by design, and an input of that
  * kind is not a timing requirement ([[nzea_fpga.boards.lxb_artix7.BoardXdc]] states the exceptions).
  */
object Resets:

    /** The declared reasons, in declaration order: the vocabulary a domain names its terms from. */
    private val terms = mutable.LinkedHashMap.empty[String, Referable[Bool]]

    /** The domains, by the name they state themselves under: a name is a reset, so asking again with the same name and
      * the same reasons is asking for the same reset, and gets the same net.
      */
    private val domains = mutable.LinkedHashMap.empty[String, Domain]

    private var materialized = false

    private final case class Domain(name: String, terms: Seq[String], sync: Instance[ResetSyncIO, ResetSyncProbe])

    DumpRegistry.onReset: () =>
        terms.clear()
        domains.clear()
        materialized = false

    /** Declare a reason, where it is produced: `value` is the level's own reading, `1` meaning *this reason is not
      * holding* — a button's released level, a generator's `locked`, a device's ready. The level is the producer's own,
      * asynchronous, exactly as it is: a domain waits for the reason, and the domain's own synchronizer is what makes
      * it belong there. The name is what a domain waits for and what a waveform shows it as, so it is also marked
      * watchable ([[Probes]]): the first question a reset network raises is which reason is holding, and this is what
      * answers it from a capture.
      */
    def term(name: String)(value: Referable[Bool])(using
        Arena,
        Context,
        Block,
        TypeImpl,
        InstanceContext,
        sourcecode.File,
        sourcecode.Line
    ): Unit =
        require(
            !terms.contains(name),
            s"two reset terms named '$name' — a term is one reason, declared where it is made"
        )
        terms(name) = value
        Probes.mark(name)(value)

    /** State a domain's reset: it is held while any of `terms` holds, and it is released into `clock`'s domain by the
      * synchronizer created here — the one the domain's own registers take, returned for the caller to hand on. A name
      * is a reset: the first statement creates it, and a second one with the same reasons gets that same net back (one
      * synchronizer, one release edge, however many consumers); a second one with *different* reasons is an error.
      *
      * The terms are named, not passed as values: a reason may be declared *after* the domain that waits for it (a
      * wizard's lock does not exist before the wizard does), and a name that names nothing fails in [[materialize]]
      * rather than binding a wire that was never meant.
      */
    def domain(name: String, clock: Referable[Clock], terms: Seq[String])(using
        arena: Arena,
        context: Context,
        block: Block,
        file: sourcecode.File,
        line: sourcecode.Line,
        instanceContext: InstanceContext
    ): Ref[Reset] =
        require(terms.nonEmpty, s"the '$name' domain waits for nothing — a domain is held by at least one reason")
        domains.get(name) match
            case Some(existing) =>
                require(
                    existing.terms == terms,
                    s"'$name' is asked for twice with different reasons: ${existing.terms.mkString(", ")} and " +
                        s"${terms.mkString(", ")} — one name is one reset"
                )
                existing.sync.io.syncN
            case None =>
                require(!materialized, "the reset network is wired once; state every domain before Resets.materialize")
                // The instance is named after the domain it serves, from here rather than from the caller's `val`: the
                // name is part of the netlist a simulator reaches into, and this is what names it.
                val sync = ResetSync.instantiate(ResetSyncParameter())(using
                    arena,
                    context,
                    block,
                    file,
                    line,
                    sourcecode.Name.Machine(s"${name}Sync"),
                    instanceContext
                )
                sync.io.clock := clock
                domains(name) = Domain(name, terms, sync)
                // One more observation for the board's side: this domain, as its registers see it (1 = in reset).
                val inReset =
                    given sourcecode.Name.Machine = sourcecode.Name.Machine(s"${name}InReset")
                    !sync.io.syncN.asBool
                Probes.mark(s"${name}Reset")(inReset)
                sync.io.syncN

    /** Wire the network: every domain's synchronizer takes the conjunction of the reasons it waits for. Called once,
      * after every term and every domain has been stated — a domain declared after this is refused by [[domain]].
      */
    def materialize()(using
        Arena,
        Context,
        Block,
        TypeImpl,
        InstanceContext,
        sourcecode.File,
        sourcecode.Line
    ): Unit =
        require(!materialized, "the reset network is wired once")
        for domain <- domains.valuesIterator do
            val holding = domain.terms.map { term =>
                terms.getOrElse(
                    term,
                    throw new IllegalArgumentException(
                        s"the '${domain.name}' domain waits for a reason nobody declared: '$term' " +
                            s"(declared: ${terms.keys.mkString(", ")})"
                    )
                )
            }
            // A conjunction, never a chain of anything else: every reason is a level that only ever moves one way while
            // the design runs, so the composed level does too, and a domain released early by a glitch cannot exist.
            // Each step is named after the reasons it joins — not after the domain that asked for it: two domains that
            // wait for the same prefix of reasons share the net (the tools fold identical logic), and a name that says
            // what the net *is* stays true for the first asker and the second.
            val raw = holding.tail.zipWithIndex.foldLeft(holding.head: Referable[Bool]):
                case (held, (term, step)) =>
                    given sourcecode.Name.Machine = sourcecode.Name.Machine(joined(domain.terms.take(step + 2)))
                    held & term
            domain.sync.io.rawN := raw.asReset
        materialized = true

    /** The declared reasons, in declaration order — what a level can check its own vocabulary against. */
    def declared: Seq[String] = terms.keys.toSeq

    /** A conjunction's net name: what it joins, as `firstAndSecondAndThird` — the names are the terms', so a reader (or
      * a waveform) can tell which reasons a net is the conjunction of, wherever the tools decide to share it.
      */
    private def joined(names: Seq[String]): String =
        (names.head +: names.tail.map(_.capitalize)).mkString("And")
