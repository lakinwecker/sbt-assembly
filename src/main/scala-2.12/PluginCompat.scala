package sbtassembly

import sbt.*
import sbt.Keys.test

private[sbtassembly] object PluginCompat {
  type MainClass = sbt.Package.MainClass

  object CollectionConverters
  val JavaCollectionConverters = scala.collection.JavaConverters

  def baseTestSettings: Seq[sbt.Def.Setting[?]] = Seq(
    AssemblyKeys.assembly / test := (()),
    AssemblyKeys.assemblyPackageScala / test := (AssemblyKeys.assembly / test).value,
    AssemblyKeys.assemblyPackageDependency / test := (AssemblyKeys.assembly / test).value,
  )

  val Streamable = scala.tools.nsc.io.Streamable

  private[sbtassembly] def contentDigest(entry: Attributed[File]): String =
    "sha256-" + AssemblyOutputCache.sha256(entry.data)

  private[sbtassembly] def toOutputWithSha256(file: File, sha256: String): File = file
}
