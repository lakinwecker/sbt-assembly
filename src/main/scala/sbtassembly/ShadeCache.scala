package sbtassembly

import com.eed3si9n.jarjarabrams.ShadeRule
import sbt.Logger
import sbt.io.{ IO, Using }
import sbtassembly.Assembly.LazyInputStream

import java.io.{ BufferedOutputStream, File, FileOutputStream, IOException }
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{ Files, StandardCopyOption }
import java.security.MessageDigest
import java.util.zip.{ ZipEntry, ZipFile, ZipOutputStream }
import PluginCompat.JavaCollectionConverters.*
import scala.util.control.NonFatal

/**
 * Keeps each library jar's shaded entries on disk, so a library is shaded once and then reused by every assembly
 * that points at the same directory, in this build or a later one.
 *
 * What shading a library produces depends only on the library's bytes, the shade rules that apply to it, whether a
 * shade error fails the build, and the version of the shading code. A cache file is named by a SHA-256 of all of
 * them, so a file is never updated in place: changing any of them names a different file.
 *
 * A cache file is a zip with one entry per shaded entry, stored under the numbered name `entries/<n>`, with the
 * entry's original and shaded names in its comment. Numbered names keep apart entries that share a name within one
 * library, and keep a signed library's `META-INF/MANIFEST.MF` out of the root, where `JarFile` would check its
 * digests against classes that shading has rewritten.
 */
private[sbtassembly] object ShadeCache {

  /**
   * One shaded entry
   *
   * @param source the entry's name in the library
   * @param target the entry's name after shading
   * @param stream the entry's bytes after shading
   */
  final case class Entry(source: String, target: String, stream: LazyInputStream)

  private val entryPrefix = "entries/"
  private val separator = '\u0000'

  /** Changes whenever the layout of a cache file does, so a file written the old way is never read */
  private val formatVersion = "1"

  /**
   * The file names of the jars that hold the shading code. The names carry the versions, and another version of any
   * of them could shade the same input differently.
   */
  private lazy val shaderJars: String =
    Seq(
      classOf[ShadeRule],
      classOf[com.eed3si9n.jarjar.util.EntryStruct],
      classOf[org.objectweb.asm.ClassReader],
      classOf[org.objectweb.asm.commons.ClassRemapper]
    ).map { c =>
      Option(c.getProtectionDomain.getCodeSource)
        .flatMap(source => Option(source.getLocation))
        .fold("unknown")(location => new File(location.getPath).getName)
    }.mkString(",")

  /** The cache file for `jar` shaded with `rules` */
  def libraryFile(dir: File, jar: File, rules: Seq[ShadeRule], failOnShadeError: Boolean): File = {
    val digest = MessageDigest.getInstance("SHA-256")
    def add(value: String): Unit = {
      digest.update(value.getBytes(UTF_8))
      digest.update(separator.toByte)
    }
    add(formatVersion)
    add(shaderJars)
    add(failOnShadeError.toString)
    rules.foreach(rule => add(rule.toString))
    Using.fileInputStream(jar) { in =>
      val buffer = new Array[Byte](1 << 16)
      var read = in.read(buffer)
      while (read >= 0) {
        digest.update(buffer, 0, read)
        read = in.read(buffer)
      }
    }
    new File(dir, digest.digest().map("%02x".format(_)).mkString + ".jar")
  }

  /**
   * The entries stored in `cached`, or None if it doesn't exist or can't be read. An unreadable file is treated as
   * missing: the caller shades the library again, and the new file replaces it.
   */
  def read(cached: File, pool: JarFilePool, log: Logger): Option[Vector[Entry]] =
    if (!cached.isFile) None
    else
      try {
        val zip = new ZipFile(cached)
        try
          Some(zip.entries().asScala.toVector.map { stored =>
            val name = stored.getName
            Option(stored.getComment).map(_.split(separator)) match {
              case Some(Array(source, target)) => Entry(source, target, () => pool.stream(cached, name))
              case _                           => throw new IOException(s"$name has no source and target names")
            }
          })
        finally zip.close()
      } catch {
        case NonFatal(e) =>
          log.warn(s"Ignoring the unreadable shade cache file $cached: $e")
          None
      }

  /**
   * Writes `entries` to `cached`, opening and closing one entry's stream at a time, so a caller that shades lazily
   * holds one shaded entry in memory rather than the whole library.
   *
   * The file is written under a temporary name and then moved into place, so no assembly ever reads a half-written
   * file. Assemblies that miss the same file at the same time each write it, and the last move wins; the files they
   * write hold the same entries under the same numbers.
   *
   * @throws IOException if the file can't be written. Nothing is left behind.
   */
  def write(cached: File, entries: Iterator[Entry]): Unit = {
    IO.createDirectory(cached.getParentFile)
    val tmp = File.createTempFile(cached.getName, ".tmp", cached.getParentFile)
    try {
      Using.resource((f: File) => new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(f))))(tmp) { out =>
        var index = 0
        entries.foreach { entry =>
          val stored = new ZipEntry(entryPrefix + index)
          stored.setComment(entry.source + separator + entry.target)
          out.putNextEntry(stored)
          Using.resource((stream: LazyInputStream) => stream())(entry.stream)(IO.transfer(_, out))
          out.closeEntry()
          index += 1
        }
      }
      try Files.move(tmp.toPath, cached.toPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      catch {
        // Windows won't replace a file that another process has open. That process wrote the same entries.
        case _: IOException if cached.isFile => ()
      }
    } finally Files.deleteIfExists(tmp.toPath)
  }
}
