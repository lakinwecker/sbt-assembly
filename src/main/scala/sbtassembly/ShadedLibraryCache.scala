package sbtassembly

import sbt.io.Using
import sbtassembly.Assembly.{ Library, ModuleCoordinate }

import java.io.{ BufferedOutputStream, File, FileOutputStream }
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{ Files, StandardCopyOption }
import java.security.MessageDigest
import java.util.zip.{ ZipEntry, ZipOutputStream }
import PluginCompat.JavaCollectionConverters.*
import scala.util.control.NonFatal

/**
 * PROTOTYPE. Stores each library jar's shaded entries on disk, so a library that several assemblies share is only
 * shaded once, and an unchanged library isn't shaded again on the next build.
 *
 * Shading one library depends only on the library's bytes and the shade rules that apply to it, so the cache key is a
 * SHA-256 of both. The value is a jar of that library's entries after shading: each entry is stored under its shaded
 * name under the prefix `entries/`, with its original name in the entry comment. The prefix keeps a library's own
 * `META-INF/MANIFEST.MF` out of the root, where `JarFile` would check the signatures of a signed library against
 * entries that shading has rewritten.
 *
 * Turned on by pointing the system property `sbtassembly.shadedLibraryCache` at a directory. Every project that
 * points at the same directory shares it.
 */
private[sbtassembly] object ShadedLibraryCache {
  private val dirProperty = "sbtassembly.shadedLibraryCache"
  private val prefix = "entries/"

  def dir: Option[File] = sys.props.get(dirProperty).filter(_.nonEmpty).map(new File(_))

  def file(dir: File, jar: File, ruleDescriptions: Seq[String], failOnShadeError: Boolean): File = {
    val digest = MessageDigest.getInstance("SHA-256")
    Using.fileInputStream(jar) { in =>
      val buffer = new Array[Byte](1 << 20)
      var read = in.read(buffer)
      while (read >= 0) {
        digest.update(buffer, 0, read)
        read = in.read(buffer)
      }
    }
    digest.update(ruleDescriptions.mkString("\n").getBytes(UTF_8))
    digest.update(failOnShadeError.toString.getBytes(UTF_8))
    val key = digest.digest().map("%02x".format(_)).mkString
    new File(dir, s"$key.jar")
  }

  def read(cached: File, module: ModuleCoordinate, pool: JarFilePool): Vector[Library] =
    pool.withJar(cached) { jar =>
      jar.entries().asScala.toVector.map { entry =>
        val stored = entry.getName
        Library(module, entry.getComment, stored.stripPrefix(prefix), () => pool.stream(cached, stored))
      }
    }

  /** Writes `libraries` to `cached`; leaves nothing behind if it can't, since the cache is only an optimisation */
  def write(cached: File, libraries: Seq[Library]): Unit = {
    cached.getParentFile.mkdirs()
    val tmp = File.createTempFile(cached.getName, ".tmp", cached.getParentFile)
    try {
      Using.resource((f: File) => new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(f))))(tmp) { out =>
        libraries.foreach { library =>
          val entry = new ZipEntry(prefix + library.target)
          entry.setComment(library.source)
          out.putNextEntry(entry)
          Using.resource((l: Library) => l.stream())(library)(in => sbt.io.IO.transfer(in, out))
          out.closeEntry()
        }
      }
      Files.move(tmp.toPath, cached.toPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case NonFatal(_) => tmp.delete()
    }
  }
}
