package nzea_lab

import me.jiuyang.zaozi.{Generator, Parameter}

/** Which side of the module a port sits on. */
enum PortDir:
    case In, Out

/** One port as the emitted header states it. */
final case class PortSpec(name: String, dir: PortDir, width: Int)

/** One module the lab can generate, mixed into the zaozi generator object itself.
  *
  * The object already carries the parameter case class and the interface; this trait adds only what the tool needs to
  * find it and drive it: a CLI name, a one-line description, and how to build the parameter from command-line values.
  * The port list is read from the emitted header — the lowering is the authority on what it produced.
  */
trait LabGenerator[P <: Parameter & Product](using
    writer: upickle.default.ReadWriter[P],
    schema: ParamSchema[P]
):

    this: Generator[P, ?, ?, ?] =>

    def name: String

    def doc: String

    def parameter(values: ParamValues): P

    /** Modules this generator instantiates as black boxes: the bodies come from the caller's project, not from this
      * dump. `alu` has none; `simple_cpu` will list `alu`, `reg_file`, `shifter` here.
      */
    def submodules: Seq[String] = Nil

    /** Whether the lowering keeps the circuit's named intermediate values. A testbench that probes the module's
      * internals by name needs them: firtool inlines a wire that is assigned once, `--preserve-values=named` keeps it,
      * and `dontTouch` does not.
      */
    def preserveNames: Boolean = false

    /** The parameter fields, from the case class's `Mirror`. */
    def params: Seq[ParamSpec] = schema.specs

    def module(values: ParamValues): String = moduleName(parameter(values))

    def generate(values: ParamValues, out: os.Path): Unit =
        LabElaborate.dump(this, parameter(values), out, preserveNames)
