@transient
lazy val recordAssemblyMtime = taskKey[Unit]("Records the assembly JAR's modification time")

@transient
lazy val checkAssemblyNotRebuilt = taskKey[Unit]("Checks that unchanged assembly did not rewrite its JAR")

@transient
lazy val checkAssemblyRebuilt = taskKey[Unit]("Checks that assembly rewrote its JAR")

lazy val assemblyMtimeSettings: Seq[Def.Setting[?]] = {
  object AssemblyMtime {
    private val recordFileName = "recorded-assembly-mtime.txt"

    private def recordFile(crossTarget: File): File =
      crossTarget / recordFileName

    private def read(output: File): String =
      java.nio.file.Files.getLastModifiedTime(output.toPath).toString

    def record(output: File, crossTarget: File): Unit =
      IO.write(recordFile(crossTarget), read(output))

    def assertChange(output: File, crossTarget: File, expectedChange: Boolean): Unit = {
      val expected = IO.read(recordFile(crossTarget)).trim
      val actual = read(output)
      val changed = actual != expected
      assert(
        changed == expectedChange,
        if (expectedChange) s"assembly did not rewrite $output"
        else s"unchanged assembly rewrote $output: $actual (expected $expected)",
      )
    }
  }

  // These utilities record the assembly JAR's timestamp and verify whether
  // a subsequent `assembly` invocation did or did not rewrite that JAR.
  Seq(
    recordAssemblyMtime := {
      AssemblyMtime.record((assembly / assemblyOutputPath).value, crossTarget.value)
    },
    checkAssemblyNotRebuilt := {
      AssemblyMtime.assertChange(
        (assembly / assemblyOutputPath).value,
        crossTarget.value,
        expectedChange = false,
      )
    },
    checkAssemblyRebuilt := {
      AssemblyMtime.assertChange(
        (assembly / assemblyOutputPath).value,
        crossTarget.value,
        expectedChange = true,
      )
    },
  )
}

lazy val mergeKey = settingKey[String]("cacheKey given to the custom merge strategy")

ThisBuild / mergeKey := "v1"

ThisBuild / assemblyMergeStrategy := {
  val key = (ThisBuild / mergeKey).value
  val strategy = CustomMergeStrategy("Reverse characters", 1, cacheKey = Some(key)) { dependencies =>
    import java.io.{ BufferedReader, ByteArrayInputStream, InputStreamReader }
    import sbtassembly.Assembly.Dependency
    import sbt.io.Using
    Right(
      dependencies.map { dependency =>
        val reversed = Using.resource((dep: Dependency) => dep.stream())(dependency) { is =>
          IO.readLines(new BufferedReader(new InputStreamReader(is, IO.defaultCharset)))
            .map(_.reverse)
        }.mkString("")
        JarEntry(dependency.target, () => new ByteArrayInputStream(reversed.getBytes(IO.defaultCharset)))
      }
    )
  }
  val oldStrategy = (ThisBuild / assemblyMergeStrategy).value

  {
    case "sbtassembly" => strategy
    case x             => oldStrategy(x)
  }
}

lazy val testmerge = (project in file(".")).
  settings(assemblyMtimeSettings).
  settings(
    version := "0.1",
    assembly / assemblyJarName := "foo.jar",
    TaskKey[Unit]("check") := {
      IO.withTemporaryDirectory { dir =>
        IO.unzip(crossTarget.value / "foo.jar", dir)
        mustContain(dir / "sbtassembly", Seq("reversed"))
      }
    }
  )

def mustContain(f: File, l: Seq[String]): Unit = {
  val lines = IO.readLines(f, IO.utf8)
  if (lines != l)
    throw new Exception("file " + f + " had wrong content:\n" + lines.mkString("\n") +
      "\n*** instead of ***\n" + l.mkString("\n"))
}
