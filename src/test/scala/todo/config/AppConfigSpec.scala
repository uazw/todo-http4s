package todo.config

import com.typesafe.config.ConfigFactory
import scala.concurrent.duration.*
import munit.FunSuite

class AppConfigSpec extends FunSuite:

  private def parse(conf: String) = AppConfig.load(ConfigFactory.parseString(conf))

  private val minimal =
    """
      |todo {
      |  http {
      |    host = "127.0.0.1"
      |    port = 9000
      |  }
      |  database {
      |    host = "db"
      |    port = 6543
      |    name = "todos"
      |    user = "u"
      |    password = "p"
      |    pool-size = 2
      |    connection-timeout = 3s
      |  }
      |  migrate-on-start = false
      |}
      |""".stripMargin

  test("the bundled application.conf parses into a complete config") {
    val loaded = AppConfig.load()
    assert(loaded.isRight, s"bundled application.conf should load, got: $loaded")
    val config = loaded.toOption.get
    assertEquals(config.http.port, 8080)
    assertEquals(config.database.name, "todo")
    assertEquals(config.database.poolSize, 8)
    assertEquals(config.database.connectionTimeout, 5.seconds)
    assert(config.migrateOnStart)
  }

  test("a well-formed config is read field by field") {
    val config = parse(minimal).toOption.get
    assertEquals(config.http, HttpConfig("127.0.0.1", 9000))
    assertEquals(config.database, DatabaseConfig("db", 6543, "todos", "u", "p", 2, 3.seconds))
    assertEquals(config.database.jdbcUrl, "jdbc:postgresql://db:6543/todos")
    assertEquals(config.migrateOnStart, false)
  }

  test("a missing section is reported by name") {
    val outcome = parse("""todo { http { host = "h", port = 1 } }""")
    assertEquals(outcome, Left("missing configuration section 'todo.database'"))
  }

  test("an out-of-range port is rejected with the value in the message") {
    val outcome = parse(minimal.replace("port = 9000", "port = 99999"))
    assertEquals(outcome.left.toOption.map(_.contains("'todo.http.port' must be between 1 and 65535")), Some(true))
  }

  test("a wrong type names the offending key rather than crashing") {
    // A list where a scalar is expected is the one case TypeSafe Config will
    // not quietly coerce, so it is the honest way to pin the message down.
    val outcome = parse(minimal.replace("""host = "127.0.0.1"""", "host = [1, 2]"))
    assertEquals(outcome, Left("'todo.http.host' must be a string"))
  }

  test("a connection timeout that is not a duration is rejected") {
    val outcome = parse(minimal.replace("connection-timeout = 3s", "connection-timeout = soon"))
    assertEquals(outcome.left.toOption.exists(_.contains("todo.database.connection-timeout")), true)
  }
