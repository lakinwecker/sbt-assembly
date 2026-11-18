// lib/dupnames.jar is the library from caching/shadecache; its source is in that test's lib-src/.

lazy val prefix = settingKey[String]("Package the shade rule moves dupnames.** into")
lazy val expectedOutput = settingKey[String]("What the assembled app prints")

@transient
lazy val checkShaded = taskKey[Unit]("Checks that the assembly is shaded with the current rule and runs")
@transient
lazy val recordCache = taskKey[Unit]("Records the cache directory's files and their modification times")
@transient
lazy val checkCacheUnchanged = taskKey[Unit]("Checks that the cache directory is as recorded")
@transient
lazy val checkCacheGrew = taskKey[Unit]("Checks that the cache kept every recorded file and gained new ones")

def entryNames(jar: File): Set[String] = {
  val zip = new java.util.zip.ZipFile(jar)
  try {
    val all = zip.entries()
    val names = Set.newBuilder[String]
    while (all.hasMoreElements) names += all.nextElement().getName
    names.result()
  } finally zip.close()
}

def listing(dir: File): Seq[String] =
  IO.listFiles(dir).toSeq.sortBy(_.getName).map(f => s"${f.getName} ${f.lastModified}")

lazy val root = (project in file("."))
  .settings(
    version := "0.1",
    scalaVersion := "2.12.18",
    exportJars := false,
    assembly / assemblyJarName := "foo.jar",
    prefix := "one",
    expectedOutput := "dup",
    assembly / assemblyShadeRules := Seq(ShadeRule.rename("dupnames.**" -> s"${prefix.value}.@1").inAll),
    assemblyShadeCacheDirectory := Some(baseDirectory.value / "shade-cache"),
    assemblyCacheOutput := false,
    checkShaded := {
      val jar = (assembly / assemblyOutputPath).value
      val names = entryNames(jar)
      val expected = s"${prefix.value}/Dup.class"
      assert(names(expected), s"$expected is missing")
      val stale = names.filter(name => name.endsWith("/Dup.class") && name != expected)
      assert(stale.isEmpty, s"shaded by an old rule: ${stale.mkString(", ")}")
      val out = sys.process.Process("java", Seq("-jar", jar.toString)).!!.trim
      assert(out == expectedOutput.value, s"unexpected output: $out")
    },
    recordCache := IO.writeLines(target.value / "cache-listing.txt", listing(baseDirectory.value / "shade-cache")),
    checkCacheUnchanged := {
      val recorded = IO.readLines(target.value / "cache-listing.txt")
      val current = listing(baseDirectory.value / "shade-cache")
      assert(current == recorded, s"the cache changed:\n${recorded.mkString("\n")}\n*** became ***\n${current.mkString("\n")}")
    },
    checkCacheGrew := {
      val recorded = IO.readLines(target.value / "cache-listing.txt")
      val current = listing(baseDirectory.value / "shade-cache")
      assert(recorded.forall(current.contains), s"recorded files changed or went missing:\n${current.mkString("\n")}")
      assert(current.size > recorded.size, "the new rule wrote no new cache files")
    },
  )
