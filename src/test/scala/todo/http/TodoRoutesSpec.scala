package todo.http

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.Location
import org.http4s.implicits.*
import todo.TestStack
import todo.TestStack.Fixture as Stack

class TodoRoutesSpec extends munit.CatsEffectSuite:

  private def withStack(test: Stack => IO[Unit]): IO[Unit] =
    TestStack.create().flatMap(test)

  /** Runs a request against the real router — no socket, no server. */
  private def send(fixture: Stack, request: Request[IO]): IO[Response[IO]] =
    fixture.http.run(request)

  /** Decodes a JSON body, from either the response or the effect that produces it. */
  private def body(response: IO[Response[IO]]): IO[Json] =
    response.flatMap(_.as[Json])

  private def body(response: Response[IO]): IO[Json] =
    response.as[Json]

  test("POST /api/todos creates a todo and returns 201 with a Location header") {
    withStack { f =>
      val request = Request[IO](Method.POST, uri"/api/todos")
        .withEntity(Json.obj("title" -> Json.fromString("write the tests")))
      for
        response <- send(f, request)
        json <- body(response)
      yield
        assertEquals(response.status, Status.Created)
        assertEquals(
          response.headers.get[Location].map(_.uri.renderString),
          Some("/api/todos/" + json.hcursor.get[String]("id").toOption.get)
        )
        assertEquals(json.hcursor.get[String]("status").toOption, Some("pending"))
        assertEquals(json.hcursor.get[String]("title").toOption, Some("write the tests"))
    }
  }

  test("POST /api/todos rejects a blank title with a 400 naming the field") {
    withStack { f =>
      val request = Request[IO](Method.POST, uri"/api/todos")
        .withEntity(Json.obj("title" -> Json.fromString("")))
      for
        response <- send(f, request)
        json <- body(response)
      yield
        assertEquals(response.status, Status.BadRequest)
        assertEquals(json.hcursor.get[String]("field").toOption, Some("title"))
        assertEquals(json.hcursor.get[String]("error").toOption, Some("invalid_request"))
    }
  }

  test("POST /api/todos rejects malformed JSON with a 400") {
    withStack { f =>
      val request = Request[IO](Method.POST, uri"/api/todos").withEntity(Json.obj("nope" -> Json.True))
      send(f, request).map(response => assertEquals(response.status, Status.BadRequest))
    }
  }

  test("GET /api/todos returns a page envelope with filters applied") {
    withStack { f =>
      def create(title: String, status: Option[String] = None): IO[Unit] =
        val payload = Json.obj("title" -> Json.fromString(title)) deepMerge
          status.fold(Json.obj())(s => Json.obj("status" -> Json.fromString(s)))
        send(f, Request[IO](Method.POST, uri"/api/todos").withEntity(payload)).void

      for
        _ <- create("alpha")
        _ <- create("beta")
        response <- send(f, Request[IO](Method.GET, Uri.unsafeFromString("/api/todos?limit=10&offset=0")))
        json <- body(response)
        cursor = json.hcursor
        items <- IO.fromEither(cursor.get[List[Json]]("items"))
        total <- IO.fromEither(cursor.get[Long]("total"))
        filtered <- body(send(f, Request[IO](Method.GET, Uri.unsafeFromString("/api/todos?q=alpha"))))
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(items.length, 2)
        assertEquals(total, 2L)
        assertEquals(filtered.hcursor.get[Long]("total").toOption, Some(1L))
    }
  }

  test("GET /api/todos rejects a bad status filter with a 400") {
    withStack { f =>
      send(f, Request[IO](Method.GET, Uri.unsafeFromString("/api/todos?status=banana"))).map { response =>
        assertEquals(response.status, Status.BadRequest)
      }
    }
  }

  test("GET /api/todos/{id} returns 404 for an unknown id and 400 for a non-uuid") {
    withStack { f =>
      for
        missing <- send(
          f,
          Request[IO](Method.GET, Uri.unsafeFromString("/api/todos/2f1c1b1e-0000-4000-8000-000000000000"))
        )
        notAnId <- send(f, Request[IO](Method.GET, uri"/api/todos/not-a-uuid"))
      yield
        assertEquals(missing.status, Status.NotFound)
        assertEquals(notAnId.status, Status.BadRequest)
    }
  }

  test("PATCH /api/todos/{id} updates status, and null clears the due date") {
    withStack { f =>
      for
        created <- body(
          send(
            f,
            Request[IO](Method.POST, uri"/api/todos").withEntity(
              Json.obj(
                "title" -> Json.fromString("ship it"),
                "dueAt" -> Json.fromString("2026-04-01T10:00:00Z")
              )
            )
          )
        )
        id = created.hcursor.get[String]("id").toOption.get
        path = Uri.unsafeFromString(s"/api/todos/$id")
        patched <- body(
          send(
            f,
            Request[IO](Method.PATCH, path).withEntity(
              Json.obj(
                "status" -> Json.fromString("done"),
                "dueAt" -> Json.Null
              )
            )
          )
        )
      yield
        assertEquals(patched.hcursor.get[String]("status").toOption, Some("done"))
        assertEquals(patched.hcursor.get[String]("title").toOption, Some("ship it"))
        assertEquals(patched.hcursor.get[Option[String]]("dueAt").toOption, Some(None))
    }
  }

  test("DELETE /api/todos/{id} returns 204, then the todo is gone") {
    withStack { f =>
      for
        created <- body(
          send(
            f,
            Request[IO](Method.POST, uri"/api/todos").withEntity(
              Json.obj("title" -> Json.fromString("temporary"))
            )
          )
        )
        id = created.hcursor.get[String]("id").toOption.get
        path = Uri.unsafeFromString(s"/api/todos/$id")
        first <- send(f, Request[IO](Method.DELETE, path))
        second <- send(f, Request[IO](Method.DELETE, path))
      yield
        assertEquals(first.status, Status.NoContent)
        assertEquals(second.status, Status.NotFound)
    }
  }

  test("GET /health reports the database as up and an unknown route as 404") {
    withStack { f =>
      for
        health <- body(send(f, Request[IO](Method.GET, uri"/health")))
        unknown <- send(f, Request[IO](Method.GET, uri"/nope"))
      yield
        assertEquals(health.hcursor.get[String]("database").toOption, Some("up"))
        assertEquals(unknown.status, Status.NotFound)
    }
  }
