-- Initial schema. Flyway applies each versioned script exactly once per database and records it in
-- `flyway_schema_history`. Never edit a migration that has shipped; add `V2__...sql` instead.
CREATE TABLE todos (
    id          UUID        PRIMARY KEY,
    title       TEXT        NOT NULL,
    description TEXT,
    status      TEXT        NOT NULL,
    due_at      TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX todos_status_idx ON todos (status);

CREATE INDEX todos_created_at_idx ON todos (created_at DESC);

CREATE INDEX todos_due_at_idx ON todos (due_at);
