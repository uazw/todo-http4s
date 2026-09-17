CREATE TABLE IF NOT EXISTS todos (
    id          UUID        PRIMARY KEY,
    title       TEXT        NOT NULL,
    description TEXT,
    status      TEXT        NOT NULL,
    due_at      TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS todos_status_idx ON todos (status);

CREATE INDEX IF NOT EXISTS todos_created_at_idx ON todos (created_at DESC);

CREATE INDEX IF NOT EXISTS todos_due_at_idx ON todos (due_at);
