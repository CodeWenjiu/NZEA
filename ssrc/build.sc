// import Mill dependency
import mill._
import mill.define.Sources
import mill.modules.Util
import mill.scalalib.scalafmt.ScalafmtModule
import mill.scalalib.TestModule.ScalaTest
import mill.scalalib._
// support BSP
import mill.bsp._

import $file.`rocket-chip`.dependencies.hardfloat.common
import $file.`rocket-chip`.dependencies.cde.common
import $file.`rocket-chip`.dependencies.diplomacy.common
import $file.`rocket-chip`.common

val chiselVersion = "7.0.0-M2"
val defaultScalaVersion = "2.13.10"

object v {
  def chiselIvy: Option[Dep] = Some(ivy"org.chipsalliance::chisel:${chiselVersion}")
  def chiselPluginIvy: Option[Dep] = Some(ivy"org.chipsalliance:::chisel-plugin:${chiselVersion}")
}

trait HasThisChisel extends SbtModule {
  def chiselModule: Option[ScalaModule] = None
  def chiselPluginJar: T[Option[PathRef]] = None
  def chiselIvy: Option[Dep] = v.chiselIvy
  def chiselPluginIvy: Option[Dep] = v.chiselPluginIvy
  override def scalaVersion = defaultScalaVersion
  override def scalacOptions = super.scalacOptions() ++
    Agg("-language:reflectiveCalls", "-Ymacro-annotations", "-Ytasty-reader")
  override def ivyDeps = super.ivyDeps() ++ Agg(chiselIvy.get)
  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(chiselPluginIvy.get)
}

object rocketchip extends RocketChip
trait RocketChip extends millbuild.`rocket-chip`.common.RocketChipModule with HasThisChisel {
  def scalaVersion: T[String] = T(defaultScalaVersion)
  override def millSourcePath = os.pwd / "rocket-chip"
  def dependencyPath = millSourcePath / "dependencies"
  def macrosModule = macros
  def hardfloatModule = hardfloat
  def cdeModule = cde
  def diplomacyModule = diplomacy
  def diplomacyIvy = None
  def mainargsIvy = ivy"com.lihaoyi::mainargs:0.5.4"
  def json4sJacksonIvy = ivy"org.json4s::json4s-jackson:4.0.6"

  object macros extends Macros
  trait Macros extends millbuild.`rocket-chip`.common.MacrosModule with SbtModule {
    def scalaVersion: T[String] = T(defaultScalaVersion)
    def scalaReflectIvy = ivy"org.scala-lang:scala-reflect:${defaultScalaVersion}"
  }

  object hardfloat extends Hardfloat
  trait Hardfloat extends millbuild.`rocket-chip`.dependencies.hardfloat.common.HardfloatModule with HasThisChisel {
    def scalaVersion: T[String] = T(defaultScalaVersion)
    override def millSourcePath = dependencyPath / "hardfloat" / "hardfloat"
  }

  object cde extends CDE
  trait CDE extends millbuild.`rocket-chip`.dependencies.cde.common.CDEModule with ScalaModule {
    def scalaVersion: T[String] = T(defaultScalaVersion)
    override def millSourcePath = dependencyPath / "cde" / "cde"
  }

  object diplomacy extends Diplomacy
  trait Diplomacy extends millbuild.`rocket-chip`.dependencies.diplomacy.common.DiplomacyModule {
    def scalaVersion: T[String] = T(defaultScalaVersion)
    override def millSourcePath = dependencyPath / "diplomacy" / "diplomacy"

    def chiselModule: Option[ScalaModule] = None
    def chiselPluginJar: T[Option[PathRef]] = None
    def chiselIvy: Option[Dep] = v.chiselIvy
    def chiselPluginIvy: Option[Dep] = v.chiselPluginIvy

    def cdeModule = cde
    def sourcecodeIvy = ivy"com.lihaoyi::sourcecode:0.3.1"
  }
}

trait NzeaModule extends ScalaModule {
  def rocketModule: ScalaModule
  override def moduleDeps = super.moduleDeps ++ Seq(
    rocketModule,
  )
}

trait NZEA extends NzeaModule with HasThisChisel {
  override def millSourcePath = os.pwd
  def rocketModule = rocketchip
  override def sources = T.sources {
    super.sources() ++ Seq(
      PathRef(millSourcePath / "HDL"),
      PathRef(millSourcePath / "Elaborate"),
      PathRef(millSourcePath / "rvdecoderdb"),
    )
  }
}

object nzea extends NZEA {
  val useChisel3 = false
  override def scalacOptions = Seq(
    "-language:reflectiveCalls",
    "-deprecation",
    "-feature",
    "-Xcheckinit"
  )
  override def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"edu.berkeley.cs::chiseltest:6.0.0"
  )
  override def scalacPluginIvyDeps = Agg(
    ivy"org.chipsalliance:::chisel-plugin:6.4.0"
  )
  object test extends SbtTests with TestModule.ScalaTest with ScalafmtModule{
    override def sources = T.sources {
      super.sources() ++ Seq(PathRef(this.millSourcePath / "src" / "ssrc" / "ysyx" / "riscv_soc" / "test"))
    }
  }
  def repositoriesTask = T.task { Seq(
    coursier.MavenRepository("https://repo.scala-sbt.org/scalasbt/maven-releases"),
    coursier.MavenRepository("https://oss.sonatype.org/content/repositories/releases"),
    coursier.MavenRepository("https://oss.sonatype.org/content/repositories/snapshots"),
  ) ++ super.repositoriesTask() }
}
