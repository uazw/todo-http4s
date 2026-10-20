package todo.lint

import scala.meta.*

class FlatMapNestingSpec extends munit.FunSuite:
  private def depths(source: String): List[Int] =
    given Dialect = dialects.Scala3
    FlatMapNesting.violations(source.parse[Source].get).map(_._2)

  test("allows two nested callbacks"):
    assertEquals(depths("val x = a.flatMap(x => b.flatMap(y => c))"), Nil)

  test("rejects the third and deeper callbacks"):
    assertEquals(depths("val x = a.flatMap(x => b.flatMap(y => c.flatMap(z => d.flatMap(w => e))))"), List(3, 4))

  test("sequential chains do not increase nesting"):
    assertEquals(depths("val x = a.flatMap(f).flatMap(x => b).flatMap(y => c).flatMap(z => d)"), Nil)

  test("allows a flat for comprehension with many generators"):
    assertEquals(depths("val x = for { a <- fa; b <- fb; c <- fc; d <- fd } yield d"), Nil)

  test("handles Scala 3 indentation and partial functions"):
    assertEquals(
      depths("""val x = a.flatMap:
      |  case Some(x) => b.flatMap: y =>
      |    c.flatMap: z =>
      |      d
      |""".stripMargin),
      List(3)
    )

  test("handles curried and explicitly typed calls"):
    assertEquals(depths("val x = F.flatMap(a)(x => b.flatMap[Int](y => c.flatMap(z => d)))"), List(3))

  test("handles infix calls and placeholder callbacks"):
    assertEquals(depths("val x = a flatMap (x => b.flatMap(_ => c.flatMap(_.value)))"), List(3))

  test("receiver computations are outside the callback"):
    assertEquals(depths("val x = F.flatMap(a.flatMap(x => b.flatMap(y => c)))(z => d)"), Nil)

  test("ignores comments, strings, and other methods"):
    assertEquals(
      depths("""// a.flatMap(x => b.flatMap(y => c.flatMap(z => d)))
      |val text = "flatMap flatMap flatMap"
      |val x = a.map(x => b.map(y => c.map(z => d)))
      |""".stripMargin),
      Nil
    )

  test("named helper bodies start a separate flow"):
    assertEquals(
      depths("""val x = a.flatMap: x =>
      |  def helper = b.flatMap(y => c.flatMap(z => d))
      |  helper
      |""".stripMargin),
      Nil
    )
