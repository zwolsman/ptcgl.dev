-- ============================================================
-- Enums
-- ============================================================

CREATE TYPE asset_status AS ENUM (
    'PENDING', 'IN_PROGRESS', 'DONE', 'FAILED_RETRYABLE', 'SKIPPED', 'STALE'
);

CREATE TYPE set_asset_kind AS ENUM ('SYMBOL', 'LOGO');

CREATE TYPE card_asset_kind AS ENUM (
    'HIRES', 'THUMB', 'WHITEPLATE', 'ETCH', 'MATERIAL_MANIFEST', 'RAW'
);

-- ============================================================
-- Auth & operational state
-- ============================================================

CREATE TABLE auth_state (
    id                        int          PRIMARY KEY DEFAULT 1,
    refresh_token             text         NOT NULL,
    refresh_token_updated_at  timestamptz  NOT NULL,
    last_studio_iss           text,
    note                      text,
    CONSTRAINT auth_state_singleton CHECK (id = 1)
);

CREATE TABLE config_revision (
    doc_id      text        PRIMARY KEY,
    revision    text        NOT NULL,
    fetched_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ingest_run (
    id            bigserial    PRIMARY KEY,
    started_at    timestamptz  NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    planned       int,
    done          int,
    skipped       int,
    rate_limited  int,
    summary       text
);

-- ============================================================
-- Domain: sets
-- ============================================================

CREATE TABLE set (
    id            text        PRIMARY KEY,
    code          text        NOT NULL UNIQUE,
    name          text        NOT NULL,
    series        text,
    release_date  date,
    revision      text
);

CREATE TABLE set_localization (
    set_id  text  NOT NULL REFERENCES set (id),
    locale  text  NOT NULL,
    name    text  NOT NULL,
    PRIMARY KEY (set_id, locale)
);

CREATE TABLE set_asset (
    set_id       text           NOT NULL REFERENCES set (id),
    locale       text           NOT NULL,
    kind         set_asset_kind NOT NULL,
    s3_key       text           NOT NULL,
    source_hash  text,
    crc          bigint,
    PRIMARY KEY (set_id, locale, kind)
);

-- ============================================================
-- Domain: cards
-- ============================================================

CREATE TABLE card (
    id               text  PRIMARY KEY,
    set_id           text  NOT NULL REFERENCES set (id),
    number           text  NOT NULL,
    rarity           text,
    regulation_mark  text,
    archetype        text,
    hp               int,
    types            text[],
    evolves_from     text,
    group_id         text
);

CREATE TABLE card_localization (
    card_id  text  NOT NULL REFERENCES card (id),
    locale   text  NOT NULL,
    name     text  NOT NULL,
    PRIMARY KEY (card_id, locale)
);

CREATE TABLE card_variant (
    id       bigserial  PRIMARY KEY,
    card_id  text       NOT NULL REFERENCES card (id),
    variant  text       NOT NULL,
    UNIQUE (card_id, variant)
);

CREATE TABLE card_asset (
    card_id        text            NOT NULL REFERENCES card (id),
    locale         text            NOT NULL,
    kind           card_asset_kind NOT NULL,
    variant        text            NOT NULL DEFAULT '',  -- rarity suffix e.g. 'mph', 'sph'; '' for non-manifest kinds
    s3_key         text,
    manifest_json  jsonb,
    source_hash    text,
    crc            bigint,
    width          int,
    height         int,
    PRIMARY KEY (card_id, locale, kind, variant)
);

-- ============================================================
-- Ledger: asset objects (resume log)
-- ============================================================

CREATE TABLE asset_object (
    asset_name    text          NOT NULL,
    locale        text          NOT NULL,
    bucket        text          NOT NULL,
    crc           bigint,
    source_hash   text,
    s3_key_raw    text,
    s3_key_decoded text,
    status        asset_status  NOT NULL DEFAULT 'PENDING',
    attempts      int           NOT NULL DEFAULT 0,
    last_error    text,
    lease_until   timestamptz,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_name, locale)
);

CREATE INDEX asset_object_status_idx ON asset_object (status);
