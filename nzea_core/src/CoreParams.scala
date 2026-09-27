package nzea_core

/** What one op producer declares about itself: a name for reports, the dispatch id it answers to, and the window it
  * needs in the shared op field.
  */
case class OpProducer(name: String, dispatch: Int, opWidth: Int)

/** The core's cross-module parameters, resolved by a pure function of the configuration — before any hardware exists.
  *
  * Usage: the owner of a contract calls `CoreParams(config, ...)` and every consumer reads the numbers from the result;
  * nothing downstream re-derives them. Two things happen here that cannot happen at a use site:
  *
  *   - a **derived** width (the shared op field is as wide as the longest producer's window), and
  *   - a **check** across declarations that no single module can make (every dispatch id fits the field the ISA layer
  *     declared).
  *
  * Being a pure case class is what makes this "before construction": resolving is not lazy, so it cannot be triggered
  * halfway through emitting hardware, and it cannot touch the DSL at all — this module has no hardware dependency, so
  * its checks are plain unit tests.
  */
case class CoreParams(
    config: CoreConfig,
    /** Width of the dispatch field, declared by the ISA layer that owns the `Backend` enum. */
    dispatchWidth: Int,
    /** Producers' declarations. */
    declared: Seq[OpProducer]
):

  require(dispatchWidth >= 1, s"dispatch width must be positive, got $dispatchWidth")
  require(declared.nonEmpty, "no op producer declared")

  require(
    declared.map(_.name).distinct.size == declared.size,
    s"duplicate producer names: ${declared.map(_.name).mkString(", ")}"
  )

  require(
    declared.map(_.dispatch).distinct.size == declared.size,
    s"duplicate dispatch ids: ${declared.map(_.dispatch).mkString(", ")}"
  )

  require(
    declared.forall(_.opWidth >= 1),
    s"op windows must be positive: ${declared.map(p => s"${p.name}=${p.opWidth}").mkString(", ")}"
  )

  private val outOfField = declared.filter(p => p.dispatch < 0 || (p.dispatch >> dispatchWidth) != 0)

  require(
    outOfField.isEmpty,
    s"dispatch id does not fit the $dispatchWidth-bit field: ${outOfField.map(p => s"${p.name}=${p.dispatch}").mkString(", ")}"
  )

  /** The shared op field: the longest declared window. Every producer pays for the widest one. */
  val opWidth: Int = declared.map(_.opWidth).max

  /** The producer that sized the op field: whoever adds the widest window moves this number, and reports say who. */
  val widestOp: String = declared.maxBy(_.opWidth).name

  /** Architectural widths, read once here instead of re-derived from the ISA string downstream. */
  val dataWidth: Int = config.width
  val pcWidth: Int = config.width

  /** One line for elaboration output and bug reports. */
  def report: String =
    val windows = declared.map(p => s"${p.name}=${p.opWidth}").mkString(" ")
    s"core params: op=$opWidth ($widestOp), dispatch=$dispatchWidth, width=$dataWidth | declared: $windows"
