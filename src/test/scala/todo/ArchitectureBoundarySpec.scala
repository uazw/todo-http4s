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
