package rainier.compute.asm

import rainier.compute._

private class Translator {
  private val binary = new SymCache[BinaryOp]
  private val unary = new SymCache[UnaryOp]
  private val ifs = new SymCache[Unit]

  def toIR(r: Real): IR = r match {
    case v: Variable         => Parameter(v)
    case Constant(value)     => Const(value)
    case Unary(original, op) => unaryIR(toIR(original), op)
    case i: If               => ifIR(toIR(i.whenNonZero), toIR(i.whenZero), toIR(i.test))
    case l: Line             => lineIR(l)
    case l: LogLine          => logLineIR(l)
  }

  private def unaryIR(original: IR, op: UnaryOp): IR =
    unary.memoize(List(List(original)), op, new UnaryIR(original, op))

  private def binaryIR(left: IR, right: IR, op: BinaryOp): IR = {
    val key = List(left, right)
    val keys =
      if (op.isCommutative)
        List(key)
      else
        List(key, key.reverse)
    binary.memoize(keys, op, new BinaryIR(left, right, op))
  }

  private def ifIR(whenZero: IR, whenNonZero: IR, test: IR): IR =
    ifs.memoize(List(List(test, whenZero, whenNonZero)),
                (),
                new IfIR(test, whenZero, whenNonZero))

  private def lineIR(line: Line): IR = {
    val (y, k) = LineOps.factor(line)
    factoredLine(y.ax, y.b, k, multiplyRing)
  }

  private def logLineIR(line: LogLine): IR = {
    val (y, k) = LogLineOps.factor(line)
    factoredLine(y.ax, 1.0, k, powRing)
  }

  /**
  factoredLine(), along with combineTerms() and combineTree(),
  is responsible for producing IR for both Line and LogLine.
  It is expressed, and most easily understood, in terms of the Line case,
  where it is computing ax + b. The LogLine case uses the same logic, but
  under a non-standard ring, where the + operation is multiplication,
  the * operation is exponentiation, and the identity element is 1.0. All
  of the logic and optimizations work just the same for either ring.

  (Pedantic note: what LogLine uses is technically a Rig not a Ring because you can't
  divide by zero, but this code will not introduce any divisions by zero that
  were not already there to begin with.)

  In general, the strategy is to split the summation into a set of positively-weighted
  terms and negatively-weighted terms, sum the positive terms to get x, sum the
  absolute value of the negative terms to get y, and return x-y.

  Each of the sub-summations proceeds by recursively producing a balanced binary tree
  where every interior node is the sum of its two children; this keeps the tree-depth
  of the AST small.

  Since the summation is a dot product, most of the terms will be of the form a*x.
  If a=1, we can just take x. If a=2, it can be a minor optimization to take x+x.

  The result may also be multiplied by a constant scaling factor (generally
  factored out of the original summation).
  **/
  private def factoredLine(ax: Map[NonConstant, Double],
                           b: Double,
                           factor: Double,
                           ring: Ring): IR = {
    val posTerms = ax.filter(_._2 > 0.0).toList
    val negTerms =
      ax.filter(_._2 < 0.0).map { case (x, a) => x -> a.abs }.toList

    val allPosTerms =
      if (b == ring.zero)
        posTerms
      else
        (Constant(b), 1.0) :: posTerms

    val (ir, sign) =
      (allPosTerms.isEmpty, negTerms.isEmpty) match {
        case (true, true)  => (Const(0.0), 1.0)
        case (true, false) => (combineTerms(negTerms, ring), -1.0)
        case (false, true) => (combineTerms(allPosTerms, ring), 1.0)
        case (false, false) =>
          val posSum = combineTerms(allPosTerms, ring)
          val negSum = combineTerms(negTerms, ring)
          (binaryIR(posSum, negSum, ring.minus), 1.0)
      }

    (factor * sign) match {
      case 1.0 => ir
      case -1.0 =>
        binaryIR(Const(ring.zero), ir, ring.minus)
      case 2.0 =>
        binaryIR(ir, ref(ir), ring.plus)
      case k =>
        binaryIR(ir, Const(k), ring.times)
    }
  }

  private def combineTerms(terms: Seq[(Real, Double)], ring: Ring): IR = {
    val ir = terms.map {
      case (x, 1.0) => toIR(x)
      case (x, 2.0) =>
        binaryIR(toIR(x), toIR(x), ring.plus)
      case (l: LogLine, a) => //this can only happen for a Line's terms
        factoredLine(l.ax, a, 1.0, powRing)
      case (x, a) =>
        binaryIR(toIR(x), Const(a), ring.times)
    }
    combineTree(ir, ring)
  }

  private def combineTree(terms: Seq[IR], ring: Ring): IR =
    if (terms.size == 1)
      terms.head
    else
      combineTree(
        terms.grouped(2).toList.map {
          case oneOrTwo =>
            if (oneOrTwo.size == 1)
              oneOrTwo.head
            else {
              val left = oneOrTwo(0)
              val right = oneOrTwo(1)
              binaryIR(left, right, ring.plus)
            }
        },
        ring
      )

  private def ref(ir: IR): Ref =
    ir match {
      case r: Ref         => r
      case VarDef(sym, _) => VarRef(sym)
      case _              => sys.error("Should only see refs and vardefs")
    }

  private case class Ring(times: BinaryOp,
                          plus: BinaryOp,
                          minus: BinaryOp,
                          zero: Double)
  private val multiplyRing = Ring(MultiplyOp, AddOp, SubtractOp, 0.0)
  private val powRing = Ring(PowOp, MultiplyOp, DivideOp, 1.0)

  /*
  This performs hash-consing aka the flyweight pattern to ensure that we don't
  generate code to compute the same quantity twice. It keeps a cache keyed by one or more IR objects
  along with some operation that will combine them to form a new IR. The IR keys are required
  to be in their lightweight Ref form rather than VarDefs - this is both to avoid the expensive
  recursive equality/hashing of a def, and also to ensure that we can memoize values derived from a def
  and its ref equally well.
   */
  private class SymCache[K] {
    var cache = Map.empty[(List[Ref], K), Sym]
    def memoize(irKeys: Seq[List[IR]], opKey: K, ir: => IR): IR = {
      val refKeys = irKeys.map { l =>
        l.map(ref)
      }
      val hit = refKeys.foldLeft(Option.empty[Sym]) {
        case (opt, k) =>
          opt.orElse { cache.get((k, opKey)) }
      }
      hit match {
        case Some(sym) => VarRef(sym)
        case None =>
          val sym = Sym.freshSym()
          cache += (refKeys.head, opKey) -> sym
          new VarDef(sym, ir)
      }
    }
  }
}
