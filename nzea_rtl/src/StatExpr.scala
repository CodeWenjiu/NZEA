package nzea_rtl

/** Closed arithmetic grammar for [[StatDerived.expr]]:
  *
  * {{{
  *   expr   := term (('+' | '-') term)*
  *   term   := factor (('*' | '/') factor)*
  *   factor := '-' factor | number | ident | ident '(' expr (',' expr)* ')'
  * }}}
  *
  * Numbers are decimal (optionally fractional), identifiers are counter or derived names, and the only functions are
  * `abs`, `floor`, `max` and `min`. There is deliberately no conditional, loop or host hook: an evaluator stays a few
  * dozen lines on any host, and every expression is checked when the schema is generated instead of at query time.
  *
  * [[eval]] returns `None` (unavailable) rather than a fabricated number when a reference is missing or a division has
  * a zero denominator.
  */
object StatExpr:

    sealed trait Node
    final case class Num(value: Double) extends Node
    final case class Ref(name: String) extends Node
    final case class Neg(arg: Node) extends Node
    final case class Bin(op: Char, lhs: Node, rhs: Node) extends Node
    final case class Call(fn: String, args: Seq[Node]) extends Node

    private val FnArity: Map[String, Int] = Map("abs" -> 1, "floor" -> 1, "max" -> 2, "min" -> 2)

    def parse(source: String): Node = new Parser(source).parseAll()

    def refs(node: Node): Set[String] = node match
        case Num(_)        => Set.empty
        case Ref(name)     => Set(name)
        case Neg(arg)      => refs(arg)
        case Bin(_, a, b)  => refs(a) ++ refs(b)
        case Call(_, args) => args.flatMap(refs).toSet

    def eval(node: Node, lookup: String => Option[Double]): Option[Double] = node match
        case Num(v)    => Some(v)
        case Ref(name) => lookup(name)
        case Neg(arg)  => eval(arg, lookup).map(-_)
        case Bin(op, a, b) =>
            val lhs = eval(a, lookup)
            val rhs = eval(b, lookup)
            op match
                case '+' => for (x <- lhs; y <- rhs) yield x + y
                case '-' => for (x <- lhs; y <- rhs) yield x - y
                case '*' => for (x <- lhs; y <- rhs) yield x * y
                case '/' => for (x <- lhs; y <- rhs if y != 0.0) yield x / y
                case _   => throw new IllegalArgumentException(s"unknown operator '$op'")
        case Call(fn, args) =>
            val values = args.map(eval(_, lookup))
            if values.exists(_.isEmpty) then None
            else
                values.flatten match
                    case Seq(a) if fn == "abs"    => Some(a.abs)
                    case Seq(a) if fn == "floor"  => Some(a.floor)
                    case Seq(a, b) if fn == "max" => Some(a.max(b))
                    case Seq(a, b) if fn == "min" => Some(a.min(b))
                    case _                        => throw new IllegalArgumentException(s"unknown function '$fn'")

    private final class Parser(src: String):

        private val Eof = '\u0000'
        private var pos = 0

        def parseAll(): Node =
            val node = expr()
            if peek() != Eof then fail("unexpected trailing input")
            node

        private def fail(msg: String): Nothing =
            throw new IllegalArgumentException(s"$msg (at offset $pos of \"$src\")")

        private def skipWs(): Unit = while pos < src.length && src(pos).isWhitespace do pos += 1

        private def peek(): Char =
            skipWs()
            if pos < src.length then src(pos) else Eof

        private def accept(c: Char): Boolean =
            if peek() == c then
                pos += 1
                true
            else false

        private def expect(c: Char): Unit = if !accept(c) then fail(s"expected '$c'")

        private def expr(): Node =
            var node = term()
            var c = peek()
            while c == '+' || c == '-' do
                pos += 1
                node = Bin(c, node, term())
                c = peek()
            node

        private def term(): Node =
            var node = factor()
            var c = peek()
            while c == '*' || c == '/' do
                pos += 1
                node = Bin(c, node, factor())
                c = peek()
            node

        private def factor(): Node = peek() match
            case '-' =>
                pos += 1
                Neg(factor())
            case '(' =>
                pos += 1
                val inner = expr()
                expect(')')
                inner
            case c if c.isDigit || c == '.'  => number()
            case c if c.isLetter || c == '_' => reference()
            case Eof                         => fail("unexpected end of expression")
            case c                           => fail(s"unexpected character '$c'")

        private def number(): Node =
            val start = pos
            while pos < src.length && (src(pos).isDigit || src(pos) == '.') do pos += 1
            val text = src.substring(start, pos)
            try Num(text.toDouble)
            catch case _: NumberFormatException => fail(s"invalid number '$text'")

        private def reference(): Node =
            val start = pos
            while pos < src.length && (src(pos).isLetterOrDigit || src(pos) == '_') do pos += 1
            val name = src.substring(start, pos)
            if peek() != '(' then Ref(name)
            else
                pos += 1
                val args = scala.collection.mutable.ArrayBuffer.empty[Node]
                if peek() != ')' then
                    args += expr()
                    while accept(',') do args += expr()
                expect(')')
                FnArity.get(name) match
                    case None => fail(s"unknown function '$name'")
                    case Some(n) if n != args.length =>
                        fail(s"function '$name' takes $n argument(s), got ${args.length}")
                    case Some(_) => Call(name, args.toSeq)
