package sbtassembly

import sbt.io.{ IO, Using }

import java.io.{ BufferedReader, BufferedWriter, File, InputStreamReader, OutputStreamWriter }
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{ Files, StandardCopyOption }
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.util.zip.{ GZIPInputStream, GZIPOutputStream }
import scala.util.control.NonFatal

/**
 * Lets an assembly whose inputs haven't changed skip all of its work: no shading, no merging, no writing the jar.
 *
 * Every assembly records, next to its task's other state, a SHA-256 of everything that went into the jar (the input
 * key), every entry name its merge strategy was asked about, a SHA-256 of what the strategy answered for each of them,
 * and the path, size and SHA-256 of the jar it wrote. The next assembly reuses the jar when its own input key matches,
 * the strategy gives the same answers for the recorded names, and the jar on disk still has the recorded size and
 * SHA-256. It never looks at a modification time.
 *
 * The input key covers the content hash of every library jar on the classpath (the one sbt 2 already carries on each
 * classpath entry), the path and bytes of every file in the project's class directories, the shade rules, the
 * manifest and the options that shape the jar. The entry names the strategy saw follow from those inputs and rules, so
 * they only need recording; the strategy itself is a function the plugin can't hash, which is why its answers are
 * compared instead, and why a custom strategy needs a cacheKey for its jar to be reused at all.
 */
private[sbtassembly] object AssemblyOutputCache {

  /**
   * @param inputKey     SHA-256 of the assembly's inputs, as [[InputKey]] builds it
   * @param strategyKey  SHA-256 of the merge strategy's answer for each of `targets`
   * @param output       the jar the assembly wrote
   * @param outputSha256 the jar's SHA-256, in hex
   * @param outputSize   the jar's size in bytes
   * @param targets      every entry name the merge strategy was asked about, sorted
   */
  final case class Record(
      inputKey: String,
      strategyKey: String,
      output: File,
      outputSha256: String,
      outputSize: Long,
      targets: Vector[String]
  )

  /** Changes whenever the record's layout or what goes into a key does, so an older record is never read */
  private val formatVersion = "1"

  def recordFile(cacheDir: File, scalaVersion: String): File = new File(cacheDir, s"assembly-output-$scalaVersion.gz")

  /** Builds an input key from strings and digests, each one delimited so that no two sequences hash alike */
  final class InputKey {
    private val digest = MessageDigest.getInstance("SHA-256")
    add(formatVersion)

    def add(value: String): InputKey = {
      val bytes = value.getBytes(UTF_8)
      digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array())
      digest.update(bytes)
      this
    }

    def hex: String = AssemblyOutputCache.hex(digest.digest())
  }

  /**
   * A SHA-256 of each of `targets` with the merge strategy's answer for it, or None if any answer is uncacheable,
   * that is, a custom merge strategy without a cacheKey.
   */
  def strategyKey(targets: Vector[String], answers: Vector[Option[String]]): Option[String] =
    if (answers.exists(_.isEmpty)) None
    else {
      val key = new InputKey
      targets.iterator.zip(answers.iterator).foreach { case (target, answer) => key.add(target).add(answer.get) }
      Some(key.hex)
    }

  private final case class Stamp(size: Long, lastModified: Long, fileKey: Option[AnyRef])
  private val digests = new ConcurrentHashMap[String, (Stamp, String)]

  /**
   * The SHA-256 of `file`, computed from its bytes the first time this sbt process asks for it and again whenever its
   * size, modification time or identity on disk differ from that time. This is the guard sbt 2 puts on its own digest
   * memo; it saves every assembly in one sbt run from reading the same class files again. sbt's own memo is too small
   * by default for every class file of a large build.
   */
  def memoizedSha256(file: File): String = {
    val attributes = Files.readAttributes(file.toPath, classOf[BasicFileAttributes])
    val stamp = Stamp(attributes.size, attributes.lastModifiedTime.toMillis, Option(attributes.fileKey))
    val path = file.getAbsolutePath
    Option(digests.get(path)) match {
      case Some((`stamp`, digest)) => digest
      case _ =>
        val digest = sha256(file)
        digests.put(path, (stamp, digest))
        digest
    }
  }

  def sha256(file: File): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = new Array[Byte](1 << 16)
    Using.fileInputStream(file) { in =>
      var read = in.read(buffer)
      while (read >= 0) {
        digest.update(buffer, 0, read)
        read = in.read(buffer)
      }
    }
    hex(digest.digest())
  }

  /** The recorded jar, if the record matches these keys and the jar on disk still has the recorded size and hash */
  def upToDate(record: Record, inputKey: String, strategyKey: => Option[String]): Option[Record] =
    if (record.inputKey != inputKey) None
    else if (!strategyKey.contains(record.strategyKey)) None
    else if (!record.output.isFile || record.output.length != record.outputSize) None
    else if (sha256(record.output) != record.outputSha256) None
    else Some(record)

  def read(file: File): Option[Record] =
    if (!file.isFile) None
    else
      try
        Using.resource((f: File) =>
          new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(f.toPath)), UTF_8))
        )(file) { in =>
          if (in.readLine() != formatVersion) None
          else {
            val inputKey = in.readLine()
            val strategyKey = in.readLine()
            val output = new File(in.readLine())
            val outputSha256 = in.readLine()
            val outputSize = in.readLine().toLong
            val targets = Iterator.continually(in.readLine()).takeWhile(_ != null).toVector
            Some(Record(inputKey, strategyKey, output, outputSha256, outputSize, targets))
          }
        }
      catch { case NonFatal(_) => None }

  /** Writes `record` under a temporary name and moves it into place, so a reader never sees half a record */
  def write(file: File, record: Record): Unit = {
    IO.createDirectory(file.getParentFile)
    val tmp = File.createTempFile(file.getName, ".tmp", file.getParentFile)
    try {
      Using.resource((f: File) =>
        new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(f.toPath)), UTF_8))
      )(tmp) { out =>
        Seq(
          formatVersion,
          record.inputKey,
          record.strategyKey,
          record.output.getAbsolutePath,
          record.outputSha256,
          record.outputSize.toString
        ).foreach { line =>
          out.write(line)
          out.write('\n')
        }
        record.targets.foreach { target =>
          out.write(target)
          out.write('\n')
        }
      }
      Files.move(tmp.toPath, file.toPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally Files.deleteIfExists(tmp.toPath)
  }

  def delete(file: File): Unit = Files.deleteIfExists(file.toPath)

  private def hex(bytes: Array[Byte]): String = bytes.map("%02x".format(_)).mkString
}
