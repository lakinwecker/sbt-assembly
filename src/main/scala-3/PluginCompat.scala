package sbtassembly

import java.io.File
import sbt.*
import sbt.Keys.test
import java.io.InputStream
import java.nio.file.{ Files, Path as NioPath }
import xsbti.{ BasicVirtualFileRef, FileConverter, HashedVirtualFileRef, PathBasedFile }
import sbtcompat.PluginCompat.Out

object PluginCompat:
  type JarManifest = PackageOption.JarManifest
  type MainClass = PackageOption.MainClass
  type ManifestAttributes = PackageOption.ManifestAttributes
  type FixedTimestamp = PackageOption.FixedTimestamp

  val CollectionConverters = scala.collection.parallel.CollectionConverters
  val JavaCollectionConverters = scala.jdk.CollectionConverters

  def baseTestSettings: Seq[sbt.Def.Setting[?]] = Seq(
    AssemblyKeys.assembly / test := TestResult.Empty,
    AssemblyKeys.assemblyPackageScala / test := (AssemblyKeys.assembly / test).evaluated,
    AssemblyKeys.assemblyPackageDependency / test := (AssemblyKeys.assembly / test).evaluated,
  )

  object HListFormats
  val Streamable = scala.reflect.io.Streamable

  extension [A1](init: Def.Initialize[Task[A1]])
    def ? : Def.Initialize[Task[Option[A1]]] = Def.optional(init) {
      case None    => sbt.std.TaskExtra.task { None }
      case Some(t) => t.map(Some.apply)
    }

    def or[A2 >: A1](i: Def.Initialize[Task[A2]]): Def.Initialize[Task[A2]] =
      init.?.zipWith(i) { (toa1: Task[Option[A1]], ta2: Task[A2]) =>
        (toa1, ta2).mapN {
          case (oa1: Option[A1], a2: A2) => oa1.getOrElse(a2)
        }
      }

  val inTask = Project.inTask

  /** The entry's content hash through sbt's own digest memo, which every task in this sbt shares */
  private[sbtassembly] def contentDigest(entry: Attributed[HashedVirtualFileRef]): String =
    sbt.util.CacheImplicits.hashedVirtualFileRefToStr(entry.data)

  /** The jar as sbt's output, carrying the SHA-256 the plugin already has so that sbt doesn't read the jar again */
  private[sbtassembly] def toOutputWithSha256(file: File, sha256: String)(using conv: FileConverter): Out =
    val path = file.toPath
    val ref = conv.toVirtualFile(path)
    new BasicVirtualFileRef(ref.id) with PathBasedFile:
      def contentHash: Long = ref.contentHash
      def contentHashStr: String = "sha256-" + sha256
      def sizeBytes: Long = Files.size(path)
      def input(): InputStream = Files.newInputStream(path)
      def toPath: NioPath = path
end PluginCompat
