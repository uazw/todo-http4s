# todo-service

A todo REST service in **Scala 3** built in **tagless-final** style: **cats-effect 3** for the
effect system, **doobie** for PostgreSQL, **http4s + circe** at the edge.

## Stack

| Concern      | Choice                                    |
| ------------ | ----------------------------------------- |
| Language     | Scala 3.3.4 (LTS)                         |
| Effects      | cats-effect 3.5                           |
| Persistence  | doobie 1.0 + HikariCP + PostgreSQL        |
| HTTP         | http4s 0.23 (ember server) + circe        |
| Config       | Typesafe Config (HOCON + env overrides)   |
| Tests        | munit + munit-cats-effect                 |

## Layout

```
src/main/scala/todo/
├── Main.scala                     # object graph as a Resource, ember server, request logging
├── config/AppConfig.scala         # HOCON + env vars, validated with readable errors
├── domain/                        # pure: no cats, no doobie, no http4s
│   ├── Todo.scala                 # Todo, TodoStatus, CreateTodo, UpdateTodo, TodoFilter, Page
│   ├── TodoError.scala            # every failure the service expresses on purpose
│   ├── Result.scala               # type Result[F, A] = F[Either[TodoError, A]]
│   └── Validation.scala           # pure payload rules, shared by every interface
├── runtime/Clock.scala            # Clock[F] and IdGen[F] algebras (+ deterministic test impls)
├── repository/
│   ├── TodoRepository.scala       # the persistence algebra
│   └── DoobieTodoRepository.scala # its doobie interpreter
├── service/TodoService.scala      # the use-case algebra and its implementation
├── http/
│   ├── Json.scala                 # request/response DTOs and codecs
│   └── TodoRoutes.scala           # routes; maps TodoError onto status codes
└── db/                            # transactor, schema bootstrap, health check
```

## Architecture

Each layer is an algebra — a trait parameterised by a **type constructor** `F[_]` — plus at least
one interpreter. Nothing in `service/` or `http/` mentions `ConnectionIO`, and nothing in
`repository/` mentions `Request` or `Response`. Swapping PostgreSQL for something else means
writing one new implementation of `TodoRepository[F]`, nothing more.

```
TodoRoutes[F]  ──►  TodoService[F]  ──►  TodoRepository[F]
                        │                     │
                        ├─ Clock[F]           └─ DoobieTodoRepository[F]  (Postgres)
                        └─ IdGen[F]              InMemoryTodoRepository[F] (tests)
```

Errors travel in the **value channel** as `Result[F, A] = F[Either[TodoError, A]]`, so
"not found" and "invalid title" are typed outcomes rather than exceptions. Only genuine
infrastructure failures (a dead connection) are raised as effect errors, and they become a 500.

Two design decisions worth calling out:

- **Timestamps never touch `java.sql.Timestamp`.** `TIMESTAMPTZ` columns are bound as
  `OffsetDateTime` (`DoobieTodoRepository.metas`). Going through `Timestamp` would silently
  interpret stored instants in whatever zone the JVM happens to have, which is a bug you only
  find in production.
- **`PATCH` distinguishes "absent" from "explicit null."** `UpdateTodo` uses `Option[Option[A]]`,
  and `PatchTodoRequest` decodes it from the cursor directly, so `{}` leaves a description alone
  while `{"description":null}` clears it. Circe's derived decoder cannot tell those apart.

## Running

```bash
# 1. PostgreSQL (creates todo on :5432 and todo_test on :55432)
docker compose up -d

# 2. the service
sbt run

# 3. exercise it
scripts/smoke.sh
```

The schema is applied at boot from `src/main/resources/db/schema.sql`. The DDL is idempotent
(`CREATE TABLE IF NOT EXISTS`), so the boot path is a no-op on an existing database. For a schema
that evolves over time, replace `db/Migrator.scala` with Flyway or Liquibase — nothing else changes.

### Configuration

Defaults live in `application.conf`; every value can be overridden by an environment variable.

| Env var                        | Default     |
| ------------------------------ | ----------- |
| `TODO_HTTP_HOST` / `_PORT`     | `0.0.0.0` / `8080` |
| `TODO_DB_HOST` / `_PORT`       | `localhost` / `5432` |
| `TODO_DB_NAME` / `_USER` / `_PASSWORD` | `todo` / `todo` / `todo` |
| `TODO_DB_POOL_SIZE`            | `8`         |
| `TODO_MIGRATE_ON_START`        | `true`      |

```bash
TODO_HTTP_PORT=9000 TODO_DB_PASSWORD=secret sbt run
```

## API

| Method   | Path                | Notes                                                        |
| -------- | ------------------- | ------------------------------------------------------------ |
| `GET`    | `/health`           | `200` when the database answers, `503` otherwise             |
| `POST`   | `/api/todos`        | `201` + `Location`; body `{"title", "description?", "dueAt?"}` |
| `GET`    | `/api/todos`        | `?status=&q=&due_before=&limit=&offset=` → page envelope      |
| `GET`    | `/api/todos/{id}`   | `200` / `404`                                                |
| `PATCH`  | `/api/todos/{id}`   | `{"title"?, "description"?, "status"?, "dueAt"?}`            |
| `DELETE` | `/api/todos/{id}`   | `204` / `404`                                                |

`status` is one of `pending`, `in_progress`, `done` (the parser also accepts `in-progress` and
`completed`). Instants are ISO-8601, e.g. `2026-01-31T09:00:00Z`.

```bash
curl -sX POST localhost:8080/api/todos \
  -H 'Content-Type: application/json' \
  -d '{"title":"buy milk","description":"the corner shop","dueAt":"2026-09-20T18:00:00Z"}'

curl -s 'localhost:8080/api/todos?status=pending&q=milk&limit=10'

curl -sX PATCH localhost:8080/api/todos/<id> \
  -H 'Content-Type: application/json' -d '{"status":"done","dueAt":null}'
```

Responses:

```json
{
  "id": "0c9f0a5e-2f2f-4a4f-9c1e-2b7a1d0e5f11",
  "title": "buy milk",
  "description": "the corner shop",
  "status": "pending",
  "dueAt": "2026-09-20T18:00:00Z",
  "createdAt": "2026-09-17T13:00:00Z",
  "updatedAt": "2026-09-17T13:00:00Z"
}
```

Errors are uniform, so a client parses one shape:

```json
{ "error": "invalid_request", "message": "must not be blank", "field": "title" }
```

`error` is one of `invalid_request` (400), `invalid_json` (400), `not_found` (404),
`internal_error` (500).

## Tests

```bash
sbt test                       # hermetic: service + HTTP specs, no database
docker compose up -d postgres-test
TODO_TEST_DB=1 sbt test        # adds the doobie integration spec against postgres-test
```

The unit specs run against `InMemoryTodoRepository`, the second interpreter of the repository
algebra, wired with a deterministic `Clock` and `IdGen` — which is the whole reason those two are
algebras rather than direct calls to `Instant.now()` and `UUID.randomUUID()`. The integration spec
covers what in-memory cannot: real `TIMESTAMPTZ` round-tripping, ordering and `OFFSET`, `ILIKE`
semantics, and that a literal `%` in a search term is escaped rather than treated as a wildcard.

## Commit messages

Use Conventional Commits:

Enable the repository's commit message template after cloning:

```bash
git config --local commit.template .gitmessage
```

Run `git commit` without `-m` to open the template in your editor.

```text
<type>(<optional scope>): <short description>
```

Choose a type that describes the change:

| Type       | Use for                              |
| ---------- | ------------------------------------ |
| `feat`     | New features                         |
| `fix`      | Bug fixes                            |
| `docs`     | Documentation changes                |
| `refactor` | Code changes without behavior changes |
| `test`     | Adding or updating tests             |
| `chore`    | Maintenance, tooling, or dependencies |

The scope is optional; use the affected area, such as `http`, `service`, or `repository`.
Write the description in the imperative ("add", "fix", "remove"), aim for a subject of
50–72 characters or fewer, and omit the trailing period. When explanation is needed, add
a blank line followed by a body describing why the change was made.

```text
feat(http): add filtering by due date
fix(repository): escape wildcard characters in search
docs: document commit message conventions
```

For breaking changes, add `!` before the colon and explain the migration in a
`BREAKING CHANGE:` footer:

```text
feat(http)!: rename the due date field

BREAKING CHANGE: Clients must use dueAt instead of dueDate in requests and responses.
```
