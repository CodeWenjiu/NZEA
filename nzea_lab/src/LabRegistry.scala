package nzea_lab

/** What the lab can generate: one entry per generator object. Adding a module is one object and one line here; the CLI
  * reads this list, so it never grows a branch per module.
  */
object LabRegistry:

    val all: Seq[LabGenerator[?]] = Seq(
        generators.AluGen,
        generators.RegFileGen,
        generators.ShifterGen,
        generators.SimpleCpuGen
    )

    def lookup(name: String): Either[String, LabGenerator[?]] =
        all.find(_.name == name).toRight(s"unknown generator '$name'; known: ${all.map(_.name).mkString(", ")}")
