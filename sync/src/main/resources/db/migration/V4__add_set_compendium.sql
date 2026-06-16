CREATE TABLE set_compendium (
    set_id      text PRIMARY KEY REFERENCES "set"(id),
    raw_json    jsonb NOT NULL,
    total_cards int,
    revision    text NOT NULL,
    fetched_at  timestamptz NOT NULL DEFAULT now()
);
