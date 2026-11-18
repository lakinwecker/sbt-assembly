// Projects a and b build the same app from shared/ and lib/, and share one shade cache.
//
// lib/ holds two libraries that broke earlier versions of the cache; lib-src/ has their sources.
//   dupnames.jar has two entries named LICENSE (written with Python's zipfile, which allows that).
//   signed.jar is signed, and its class refers to dupnames.Dup, so shading rewrites it without renaming it.
//     Built with `jar cf` and signed with jarsigner using a throwaway self-signed key.

@transient
lazy val checkJar = taskKey[Unit]("Checks that the assembly is a valid jar, shaded, and runs")
@transient
lazy val keepCopy = taskKey[Unit]("Copies the assembly aside")
@transient
lazy val checkSameAsCopy = taskKey[Unit]("Checks that the assembly has the same entries as the copy")
@transient
lazy val checkCached = taskKey[Unit]("Checks that both libraries in lib/ and the project's classes were cached")
@transient
lazy val recordCache = taskKey[Unit]("Records the cache directory's files and their modification times")
@transient
lazy val checkCacheUnchanged = taskKey[Unit]("Checks that the cache directory is as recorded")

ThisBuild / scalaVersion := "2.12.18"
ThisBuild / version := "0.1"
ThisBuild / assemblyShadeRules := Seq(ShadeRule.rename("dupnames.**" -> "shaded.dupnames.@1").inAll)
ThisBuild / assemblyShadeCacheDirectory := Some((ThisBuild / baseDirectory).value / "shade-cache")
// Build every assembly in full, so the test exercises the shade cache rather than the output cache
ThisBuild / assemblyCacheOutput := false
// Lets a and b assemble at the same time
Global / concurrentRestrictions := Seq(Tags.limitAll(4))

def cacheDir(base: File): File = base / "shade-cache"

/** Name, CRC and size of every entry. Reads each entry in full through ZipInputStream, which checks its CRC. */
def entries(jar: File): Vector[(String, Long, Long)] = {
  val in = new java.util.zip.ZipInputStream(new java.io.FileInputStream(jar))
  try
    Iterator
      .continually(in.getNextEntry)
      .takeWhile(_ != null)
      .map { entry =>
        val size = IO.readBytes(in).length.toLong
        (entry.getName, entry.getCrc, size)
      }
      .toVector
      .sorted
  finally in.close()
}

/** The comment of every entry in a zip file */
def comments(zip: File): Vector[String] = {
  val file = new java.util.zip.ZipFile(zip)
  try {
    val all = file.entries()
    val result = Vector.newBuilder[String]
    while (all.hasMoreElements) result += all.nextElement().getComment
    result.result()
  } finally file.close()
}

def listing(dir: File): String =
  IO.listFiles(dir).toSeq.sortBy(_.getName).map(f => s"${f.getName} ${f.lastModified}").mkString("\n")

lazy val appSettings = Seq(
  exportJars := false,
  Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "shared",
  unmanagedBase := (ThisBuild / baseDirectory).value / "lib",
  assembly / assemblyJarName := name.value + ".jar",
  checkJar := {
    val jar = (assembly / assemblyOutputPath).value
    val names = entries(jar).map(_._1).toSet
    assert(names("shaded/dupnames/Dup.class"), "shaded/dupnames/Dup.class is missing")
    assert(!names("dupnames/Dup.class"), "dupnames/Dup.class was not shaded")
    assert(names("signed/Signed.class"), "signed/Signed.class is missing")
    assert(!names.exists(_.endsWith(".SF")), "the library's signature was kept")
    val out = sys.process.Process("java", Seq("-jar", jar.toString)).!!.trim
    assert(out == "signed dup dup", s"unexpected output: $out")
  },
  keepCopy := IO.copyFile((assembly / assemblyOutputPath).value, target.value / "copy.jar"),
  checkSameAsCopy := {
    val jar = entries((assembly / assemblyOutputPath).value)
    val copy = entries(target.value / "copy.jar")
    assert(
      jar == copy,
      s"entries differ:\nonly in the jar: ${jar.diff(copy).mkString(", ")}\nonly in the copy: ${copy.diff(jar).mkString(", ")}"
    )
  },
)

lazy val a = (project in file("a")).settings(appSettings)
lazy val b = (project in file("b")).settings(appSettings)

lazy val root = (project in file("."))
  .settings(
    checkCached := {
      val files = IO.listFiles(cacheDir(baseDirectory.value)).toVector
      assert(!files.exists(_.getName.endsWith(".tmp")), s"temporary files were left behind: ${files.mkString(", ")}")
      // A cache file's entry comments start with the entry's name in the library
      val sources = files.map(comments(_).map(_.takeWhile(_ != '\u0000')))
      assert(
        sources.exists(s => s.count(_ == "LICENSE") == 2 && s.contains("dupnames/Dup.class")),
        "dupnames.jar was not cached with both of its LICENSE entries"
      )
      assert(
        sources.exists(s => s.contains("signed/Signed.class") && s.contains("META-INF/MANIFEST.MF")),
        "signed.jar was not cached"
      )
      assert(sources.exists(_.contains("Main.class")), "the project's classes were not cached")
    },
    recordCache := IO.write(target.value / "cache-listing.txt", listing(cacheDir(baseDirectory.value))),
    checkCacheUnchanged := {
      val recorded = IO.read(target.value / "cache-listing.txt")
      val current = listing(cacheDir(baseDirectory.value))
      assert(current == recorded, s"the cache changed:\n$recorded\n*** became ***\n$current")
    },
  )
