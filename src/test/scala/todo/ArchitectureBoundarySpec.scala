package todo

import java.io.File
import scala.io.Source

class ArchitectureBoundarySpec extends munit.FunSuite:

  private val mainSrcDir = new File("src/main/scala/todo")

  private def listScalaFiles(dir: File): List[File] =
    if !dir.exists() then Nil
    else
      val files = Option(dir.listFiles()).toList.flatten
      files.filter(f => f.isFile && f.getName.endsWith(".scala")) ++
        files.filter(_.isDirectory).flatMap(listScalaFiles)

  private def importsIn(file: File): List[String] =
    val src = Source.fromFile(file, "UTF-8")
    try
      src
        .getLines()
        .map(_.trim)
        .filter(_.startsWith("import "))
        .map(_.stripPrefix("import ").trim)
        .toList
    finally src.close()

  private def contentOf(file: File): String =
    val src = Source.fromFile(file, "UTF-8")
    try src.mkString
    finally src.close()

  test("domain layer must not depend on external frameworks or other application/adapter layers"):
    val domainFiles = listScalaFiles(new File(mainSrcDir, "domain"))
    assert(domainFiles.nonEmpty, "domain files must exist")

    val forbiddenPrefixes = List(
      "cats",
      "doobie",
      "org.http4s",
      "io.circe",
      "todo.application",
      "todo.adapter",
      "todo.boot"
    )

    for
      file <- domainFiles
      imp <- importsIn(file)
      forbidden <- forbiddenPrefixes
    do
      assert(
        !imp.startsWith(forbidden),
        s"Domain file ${file.getPath} violates architecture boundary by importing '$imp'"
      )

  test("application layer must not depend on adapters or boot/infrastructure"):
    val appFiles = listScalaFiles(new File(mainSrcDir, "application"))
    assert(appFiles.nonEmpty, "application files must exist")

    val forbiddenPrefixes = List(
      "doobie",
      "org.http4s",
      "io.circe",
      "todo.adapter",
      "todo.boot"
    )

    for
      file <- appFiles
      imp <- importsIn(file)
      forbidden <- forbiddenPrefixes
    do
      assert(
        !imp.startsWith(forbidden),
        s"Application file ${file.getPath} violates architecture boundary by importing '$imp'"
      )

  test("application layer must stay effect-agnostic: no concrete effect type, no eager execution"):
    val appFiles = listScalaFiles(new File(mainSrcDir, "application"))
    assert(appFiles.nonEmpty, "application files must exist")

    // `cats.effect` in application/ means someone reached for IO/Sync directly instead of `F`.
    val forbiddenImports = List("cats.effect")
    // `unsafeRun*` anywhere in main sources means an effect was executed instead of composed.
    val forbiddenCalls = List("unsafeRunSync", "unsafeRunTimed", "unsafeRunAndForget", "unsafeToFuture")

    for
      file <- appFiles
      imp <- importsIn(file)
      forbidden <- forbiddenImports
    do
      assert(
        !imp.startsWith(forbidden),
        s"Application file ${file.getPath} must be polymorphic in F, but imports '$imp'"
      )

    for
      file <- listScalaFiles(mainSrcDir)
      call <- forbiddenCalls
      if contentOf(file).contains(call)
    do fail(s"${file.getPath} executes its effect with '$call' instead of composing it")

  test("only boot may name a concrete effect; adapters stay polymorphic in F"):
    // Typeclass imports (`cats.effect.Sync`, `.Async`, `.Concurrent`, `kernel.*`) are the *point*:
    // they let an adapter describe the power it needs without picking the runtime. Naming `IO`
    // itself means the adapter has welded itself to one effect, so it can no longer be run in a
    // different one (test interpreter, tracing wrapper, ...).
    val concreteEffect = """\bIO\b""".r
    val bootFiles = listScalaFiles(new File(mainSrcDir, "boot")).map(_.getPath).toSet
    assert(bootFiles.nonEmpty, "boot files must exist")

    val offenders =
      for
        file <- listScalaFiles(mainSrcDir)
        if !bootFiles.contains(file.getPath)
        if concreteEffect.findFirstIn(contentOf(file)).isDefined
      yield file.getPath

    assert(
      offenders.isEmpty,
      s"These files name the concrete effect IO outside boot/ — they must bind [F[_]: <typeclass>] instead:\n${offenders.mkString("\n")}"
    )

  test("inbound HTTP adapter must not depend on outbound adapters or database drivers"):
    val httpFiles = listScalaFiles(new File(mainSrcDir, "adapter/in/http"))
    assert(httpFiles.nonEmpty, "http adapter files must exist")

    val forbiddenPrefixes = List(
      "doobie",
      "todo.adapter.out",
      "todo.application.TodoServiceImpl"
    )

    for
      file <- httpFiles
      imp <- importsIn(file)
      forbidden <- forbiddenPrefixes
    do
      assert(
        !imp.startsWith(forbidden),
        s"HTTP adapter file ${file.getPath} violates architecture boundary by importing '$imp'"
      )

  test("outbound adapters must not depend on inbound adapters"):
    val outFiles = listScalaFiles(new File(mainSrcDir, "adapter/out"))
    assert(outFiles.nonEmpty, "outbound adapter files must exist")

    val forbiddenPrefixes = List(
      "org.http4s",
      "todo.adapter.in"
    )

    for
      file <- outFiles
      imp <- importsIn(file)
      forbidden <- forbiddenPrefixes
    do
      assert(
        !imp.startsWith(forbidden),
        s"Outbound adapter file ${file.getPath} violates architecture boundary by importing '$imp'"
      )
