package nzea_core.backend.integer

import nzea_core.frontend.FuType
import nzea_config.{CoreConfig, FuConfig, FuKind}

/** Single source of truth for integer issue-port topology and indexing.
  * Keeps FuKind order, index mapping, and wakeup-hint specs consistent across users.
  */
object IssuePortLayout {
  final case class Layout(
    configs: Seq[FuConfig],
    kinds: Seq[FuKind],
    idxByKind: Map[FuKind, Int],
    wakeupHintSpecs: Seq[(Int, Int)]
  ) {
    def idxOpt(kind: FuKind): Option[Int] = idxByKind.get(kind)
    def idx(kind: FuKind): Int =
      idxByKind.getOrElse(kind, throw new IllegalArgumentException(s"Missing issue port for FuKind.$kind"))
  }

  def fuKindForType(ft: FuType.Type): FuKind = ft match {
    case FuType.ALU  => FuKind.Alu
    case FuType.BRU  => FuKind.Bru
    case FuType.LSU  => FuKind.Agu
    case FuType.MUL  => FuKind.Mul
    case FuType.DIV  => FuKind.Div
    case FuType.SYSU => FuKind.Sysu
    case FuType.NNU  => FuKind.Nnu
  }

  def build(implicit config: CoreConfig): Layout = {
    val hasM  = config.isaConfig.hasM
    val hasNn = config.isaConfig.hasWjcus0
    val configs = FuConfig.issuePorts(config)
    val kinds   = configs.map(_.kind)
    val idxByKind = kinds.zipWithIndex.toMap
    require(idxByKind.size == kinds.size, s"Duplicate FuKind in issue port config: $kinds")

    val requiredKinds = Seq(FuKind.Alu, FuKind.Bru, FuKind.Agu, FuKind.Sysu)
    requiredKinds.foreach { k =>
      require(idxByKind.contains(k), s"Required issue port missing for $k")
    }
    if (hasM) {
      require(idxByKind.contains(FuKind.Mul), "M extension enabled but MUL issue port is missing")
      require(idxByKind.contains(FuKind.Div), "M extension enabled but DIV issue port is missing")
    }
    if (hasNn) {
      require(idxByKind.contains(FuKind.Nnu), "WJCUS0 enabled but NNU issue port is missing")
    }

    val wakeupHintSpecs = configs.zipWithIndex.flatMap { case (cfg, idx) =>
      cfg.wakeupHintLatency.map(lat => (idx, lat))
    }
    Layout(configs, kinds, idxByKind, wakeupHintSpecs)
  }
}
