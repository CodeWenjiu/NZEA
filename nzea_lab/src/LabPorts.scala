package nzea_lab

/** The port list of an emitted Verilog module, read from its header.
  *
  * firtool shares a declaration across a run of ports (`input [31:0] A, B,` and `output Overflow, CarryOut, Zero,`), so
  * a segment without a direction inherits the run's, and only a segment that opens a run resets the width.
  */
object LabPorts:

    def of(text: String): Seq[PortSpec] =
        val start = text.indexOf("module ")
        val open = if start < 0 then -1 else text.indexOf('(', start)
        val end = if open < 0 then -1 else text.indexOf(");", open)
        if start < 0 || open < 0 || end < 0 then Nil
        else
            var direction: Option[PortDir] = None
            var width = 1
            text
                .substring(open + 1, end)
                .split(',')
                .iterator
                .flatMap { segment =>
                    val port = segment.trim
                    if port.isEmpty then None
                    else
                        if port.contains("input") || port.contains("output") then
                            direction = Some(if port.contains("input") then PortDir.In else PortDir.Out)
                            width = 1
                        """\[(\d+):(\d+)\]""".r.findFirstMatchIn(port).foreach { match_ =>
                            width = match_.group(1).toInt - match_.group(2).toInt + 1
                        }
                        val name = """([A-Za-z_][A-Za-z0-9_]*)\s*$""".r.findFirstMatchIn(port).map(_.group(1))
                        for
                            dir <- direction
                            portName <- name
                        yield PortSpec(portName, dir, width)
                }
                .toSeq
