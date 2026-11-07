package nzea_lab

/** The lab tool's command line: list what can be generated, describe one, generate it, or export it as plain Verilog.
  *
  * The generator list is [[LabRegistry.all]]; this file only turns arguments into a generator call and reads the
  * emitted header back.
  */
object LabCli:

    def main(args: Array[String]): Unit =
        args.toList match
            case "list" :: Nil              => list()
            case "describe" :: name :: rest => describe(name, rest)
            case "gen" :: name :: rest      => gen(name, rest)
            case "export" :: name :: rest   => exportModule(name, rest)
            case _                          => usage()

    private def usage(): Unit =
        println(
            """usage:
              |  lab list
              |  lab describe <name> [--param value ...]
              |  lab gen <name> --out <dir> [--param value ...]
              |  lab export <name> --dest <dir> [--param value ...]
              |""".stripMargin
        )

    private def list(): Unit =
        LabRegistry.all.foreach(g => println(f"${g.name}%-12s ${g.doc}"))

    private def describe(name: String, rest: List[String]): Unit =
        generator(name): g =>
            println(s"${g.name} — ${g.doc}")
            println("params:")
            g.params.foreach(p => println(f"  ${p.name}%-12s ${p.kind}%-6s ${p.doc}"))
            g.submodules match
                case Nil     => ()
                case modules => println(s"submodules: ${modules.mkString(", ")}")
            declared(rest): values =>
                val missing = missingParams(g, values)
                if missing.nonEmpty then println(s"give ${missing.mkString(", ")} to see the ports")
                else
                    val dir = os.temp.dir(prefix = s"nzea-lab-${g.name}")
                    try
                        g.generate(values, dir)
                        println("ports:")
                        LabPorts.of(os.read(dir / s"${g.module(values)}.sv")).foreach { port =>
                            println(f"  ${port.name}%-12s ${port.dir}%-4s ${port.width}%4d")
                        }
                    finally os.remove.all(dir)

    private def gen(name: String, rest: List[String]): Unit =
        generator(name): g =>
            declared(rest): values =>
                requireParams(g, values)
                val out = output("out", values).getOrElse(os.pwd / "build" / "lab" / g.name)
                g.generate(values, out)
                checkModule(g, values, out)
                println(s"generated ${g.module(values)} -> $out")

    private def exportModule(name: String, rest: List[String]): Unit =
        generator(name): g =>
            declared(rest): values =>
                requireParams(g, values)
                val dest = output("dest", values).getOrElse(os.pwd / "build" / "lab" / g.name)
                val stage = os.temp.dir(prefix = s"nzea-lab-${g.name}")
                try
                    g.generate(values, stage)
                    checkModule(g, values, stage)
                    os.makeDir.all(dest)
                    val written = os.list(stage).filter(_.ext == "sv").map { file =>
                        val target = dest / s"${file.baseName}.v"
                        os.copy(file, target, replaceExisting = true, createFolders = true)
                        target
                    }
                    println(s"exported ${g.name} -> $dest")
                    written.sorted.foreach(path => println(s"  ${path.relativeTo(dest)}"))
                finally os.remove.all(stage)

    private def generator(name: String)(body: LabGenerator[?] => Unit): Unit =
        LabRegistry.lookup(name) match
            case Left(error) => fail(error)
            case Right(g)    => body(g)

    private def declared(rest: List[String])(body: ParamValues => Unit): Unit =
        parseParams(rest) match
            case Left(error)   => fail(error)
            case Right(values) => body(values)

    private def parseParams(args: List[String]): Either[String, ParamValues] =
        def loop(rest: List[String], acc: Map[String, String]): Either[String, ParamValues] =
            rest match
                case Nil => Right(ParamValues(acc))
                case key :: value :: tail if key.startsWith("--") && !value.startsWith("--") =>
                    loop(tail, acc + (key.drop(2) -> value))
                case key :: Nil if key.startsWith("--") => Left(s"parameter '${key.drop(2)}' needs a value")
                case other :: _                         => Left(s"expected '--name value', got '$other'")
        loop(args, Map.empty)

    private def missingParams(g: LabGenerator[?], values: ParamValues): Seq[String] =
        g.params.map(_.name).filterNot(values.values.contains)

    private def requireParams(g: LabGenerator[?], values: ParamValues): Unit =
        val missing = missingParams(g, values)
        if missing.nonEmpty then fail(s"missing ${missing.mkString(", ")} (try 'lab describe ${g.name}')")

    private def output(key: String, values: ParamValues): Option[os.Path] =
        values.values.get(key).map(path)

    private def path(text: String): os.Path =
        if text.startsWith("/") then os.Path(text) else os.pwd / os.RelPath(text)

    /** The emitted module exists and its header parses: the lowering's output is the interface the caller compiles. */
    private def checkModule(g: LabGenerator[?], values: ParamValues, out: os.Path): Unit =
        val module = g.module(values)
        val file = out / s"$module.sv"
        if !os.exists(file) then fail(s"no $file after elaboration")
        if LabPorts.of(os.read(file)).isEmpty then fail(s"$file has no parsable port list")

    private def fail(message: String): Nothing =
        System.err.println(s"error: $message")
        sys.exit(1)
